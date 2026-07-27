/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package de.keksuccino.mcef;

import de.keksuccino.mcef.MCEFDistributionManifest.ManifestIdentity;
import de.keksuccino.mcef.MCEFInstallationState.ChecksumVerification;
import de.keksuccino.mcef.MCEFInstallationState.StateKind;
import de.keksuccino.mcef.MCEFInstallationState.StateRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Owns one locked JCEF installation/update session.
 *
 * <p>Every published installation is an immutable generation. A process resolves one generation
 * and keeps using that path even if a later process installs another JCEF commit, so native files
 * loaded by a running JVM are never replaced or deleted. The selector, generation manifest, and
 * promotion state all carry the same transaction id, JCEF commit, and archive digest. There is no
 * independent boolean "committed" marker whose deletion order could make cleanup residue look like
 * an incomplete promotion.
 *
 * <p>The in-JVM mutex and operating-system file lock are deliberately held for this object's whole
 * lifetime. Callers must therefore keep a single instance across recovery, local validation,
 * downloads, extraction, promotion, and abort.
 */
final class MCEFInstallationTransaction implements AutoCloseable {
    enum RecoveryOutcome {
        NONE,
        DISCARDED,
        COMMITTED,
        RESTORED_LEGACY
    }

    @FunctionalInterface
    interface MoveExecutor {
        void move(Path source, Path target, boolean replaceExisting) throws IOException;
    }

    @FunctionalInterface
    interface CloseAborter {
        void abort(MCEFInstallationTransaction transaction) throws IOException;
    }

    private static final String TRANSACTION_STATE_FILE = "transaction.properties";
    static final String GENERATION_STATE_FILE = ".mcef-generation-state-v2";
    private static final String CANDIDATE_CHECKSUM_FILE = "candidate.tar.gz.sha256";
    private static final String CANDIDATE_ARCHIVE_FILE = "candidate.tar.gz";
    private static final String EXTRACTION_DIRECTORY = "extracted";
    private static final String COMPATIBILITY_ARCHIVE_PREFIX = "handoff-";
    private static final String COMPATIBILITY_ARCHIVE_SUFFIX = ".tar.gz";
    private static final long COMPATIBILITY_ARCHIVE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    static final int MAX_GENERATIONS_TO_SCAN = 512;
    static final int MAX_TRANSACTIONS_TO_SCAN = 512;
    static final int MAX_GARBAGE_ENTRIES_TO_SCAN = 512;
    static final int MAX_COMPATIBILITY_ARCHIVES_TO_SCAN = 256;
    static final int MAX_LEGACY_TREE_ENTRIES = 8_192;
    static final int MAX_CLEANUP_TREE_ENTRIES_PER_PASS = MCEFSecureArchiveExtractor.MAX_EXTRACTED_FILESYSTEM_ENTRIES + 192;
    static final int MAX_CLEANUP_TREE_DEPTH = 96;
    private static final ConcurrentMap<Path, JvmLockEntry> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path librariesDirectory;
    private final MCEFPlatform platform;
    private final String platformName;
    private final String javaCefCommit;
    private final Path lockFile;
    private final Path transactionsDirectory;
    private final Path garbageDirectory;
    private final Path generationsDirectory;
    private final Path compatibilityArchivesDirectory;
    private final Path selectorFile;
    private final Path legacyInstallation;
    private final Path legacyChecksum;
    private final Path retainedArchive;
    private final Path legacyFixedTransaction;
    private final MoveExecutor moveExecutor;
    private final CloseAborter closeAborter;
    private final Consumer<IOException> cleanupWarning;
    private final CleanupBudget cleanupBudget;
    private final LeasePruningBudget leasePruningBudget;
    private final JvmLockLease jvmLockLease;
    private final FileChannel lockChannel;
    private final FileLock fileLock;

    private UUID transactionId;
    private Path transactionDirectory;
    private Path transactionStateFile;
    private Path candidateChecksum;
    private Path candidateArchive;
    private Path extractionDirectory;
    private Path stagedInstallation;
    private boolean transactionCommitted;
    private boolean closed;

    MCEFInstallationTransaction(Path librariesDirectory, MCEFPlatform platform, String javaCefCommit, Consumer<IOException> cleanupWarning) throws IOException {
        this(librariesDirectory, platform, javaCefCommit, MCEFInstallationTransaction::moveWithAtomicFallback, cleanupWarning, MCEFInstallationTransaction::abortPrepared);
    }

    MCEFInstallationTransaction(Path librariesDirectory, MCEFPlatform platform, String javaCefCommit, Consumer<IOException> cleanupWarning, int cleanupEntryBudget) throws IOException {
        this(librariesDirectory, platform, javaCefCommit, MCEFInstallationTransaction::moveWithAtomicFallback, cleanupWarning, MCEFInstallationTransaction::abortPrepared, cleanupEntryBudget);
    }

    MCEFInstallationTransaction(Path librariesDirectory, MCEFPlatform platform, String javaCefCommit, MoveExecutor moveExecutor, Consumer<IOException> cleanupWarning) throws IOException {
        this(librariesDirectory, platform, javaCefCommit, moveExecutor, cleanupWarning, MCEFInstallationTransaction::abortPrepared);
    }

    MCEFInstallationTransaction(Path librariesDirectory, MCEFPlatform platform, String javaCefCommit, MoveExecutor moveExecutor, Consumer<IOException> cleanupWarning, CloseAborter closeAborter) throws IOException {
        this(librariesDirectory, platform, javaCefCommit, moveExecutor, cleanupWarning, closeAborter, MAX_CLEANUP_TREE_ENTRIES_PER_PASS);
    }

    private MCEFInstallationTransaction(Path librariesDirectory, MCEFPlatform platform, String javaCefCommit, MoveExecutor moveExecutor, Consumer<IOException> cleanupWarning, CloseAborter closeAborter, int cleanupEntryBudget) throws IOException {
        this.platform = Objects.requireNonNull(platform, "MCEF platform must not be null");
        platformName = platform.getNormalizedName();
        if (!MCEFInstallationState.isPlatform(platformName)) {
            throw new IllegalArgumentException("Invalid MCEF platform name: " + platformName);
        }
        this.javaCefCommit = MCEFInstallationState.normalizeCommit(javaCefCommit);
        this.moveExecutor = Objects.requireNonNull(moveExecutor, "MCEF move executor must not be null");
        this.cleanupWarning = Objects.requireNonNull(cleanupWarning, "MCEF cleanup warning handler must not be null");
        this.closeAborter = Objects.requireNonNull(closeAborter, "MCEF close aborter must not be null");
        if (cleanupEntryBudget <= 0) {
            throw new IllegalArgumentException("JCEF cleanup entry budget must be positive");
        }
        cleanupBudget = new CleanupBudget(cleanupEntryBudget);
        leasePruningBudget = new LeasePruningBudget(MCEFGenerationLeaseRegistry.MAX_LEASE_SCAN_PROBES_PER_TRANSACTION, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);

        Path configuredDirectory = Objects.requireNonNull(librariesDirectory, "MCEF libraries directory must not be null").toAbsolutePath().normalize();
        Files.createDirectories(configuredDirectory);
        this.librariesDirectory = configuredDirectory.toRealPath();
        lockFile = this.librariesDirectory.resolve("." + platformName + ".mcef-install.lock");
        transactionsDirectory = this.librariesDirectory.resolve("." + platformName + ".mcef-transactions");
        garbageDirectory = this.librariesDirectory.resolve("." + platformName + ".mcef-gc");
        generationsDirectory = this.librariesDirectory.resolve("." + platformName + ".mcef-generations");
        compatibilityArchivesDirectory = this.librariesDirectory.resolve("." + platformName + ".mcef-phase-archives");
        selectorFile = this.librariesDirectory.resolve("." + platformName + ".mcef-current.properties");
        legacyInstallation = this.librariesDirectory.resolve(platformName);
        legacyChecksum = this.librariesDirectory.resolve(platformName + ".tar.gz.sha256");
        retainedArchive = this.librariesDirectory.resolve(platformName + ".tar.gz");
        legacyFixedTransaction = this.librariesDirectory.resolve("." + platformName + ".mcef-install");

        JvmLockLease acquiredJvmLock = acquireJvmLock(lockFile);

        FileChannel openedChannel = null;
        FileLock acquiredFileLock = null;
        try {
            validateLockFile();
            openedChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                acquiredFileLock = openedChannel.lock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("JCEF installation lock is already held in this JVM: " + lockFile, exception);
            }
        } catch (Throwable failure) {
            if (openedChannel != null) {
                try {
                    openedChannel.close();
                } catch (Throwable closeFailure) {
                    failure = appendFailure(failure, closeFailure);
                }
            }
            try {
                acquiredJvmLock.close();
            } catch (Throwable unlockFailure) {
                failure = appendFailure(failure, unlockFailure);
            }
            throw rethrowCloseFailure(failure);
        }
        jvmLockLease = acquiredJvmLock;
        lockChannel = openedChannel;
        fileLock = acquiredFileLock;
    }

    Path librariesDirectory() {
        return librariesDirectory;
    }

    Path lockFile() {
        return lockFile;
    }

    Path transactionDirectory() {
        return transactionDirectory;
    }

    Path candidateChecksum() {
        requirePrepared();
        return candidateChecksum;
    }

    Path candidateArchive() {
        requirePrepared();
        return candidateArchive;
    }

    Path extractionDirectory() {
        requirePrepared();
        return extractionDirectory;
    }

    Path stagedInstallation() {
        requirePrepared();
        return stagedInstallation;
    }

    Path retainedArchive() {
        return retainedArchive;
    }

    RecoveryOutcome recover() {
        requireOpen();
        RecoveryOutcome outcome = RecoveryOutcome.NONE;
        cleanupGarbageBestEffort();
        if (recoverLegacyFixedTransactionBestEffort()) {
            outcome = RecoveryOutcome.RESTORED_LEGACY;
        }

        List<Path> entries = List.of();
        if (!isSafeDirectory(transactionsDirectory)) {
            if (pathExists(transactionsDirectory)) {
                warnCleanup(new IOException("Ignoring unsafe JCEF transaction directory: " + transactionsDirectory));
            }
        } else {
            try (var stream = Files.list(transactionsDirectory)) {
                entries = stream.limit(MAX_TRANSACTIONS_TO_SCAN + 1L).sorted().toList();
                if (entries.size() > MAX_TRANSACTIONS_TO_SCAN) {
                    warnCleanup(new IOException("JCEF transaction count exceeds the bounded recovery limit"));
                    entries = List.of();
                }
            } catch (IOException failure) {
                warnCleanup(new IOException("Could not inspect stale JCEF installation transactions", failure));
            }
        }

        for (Path entry : entries) {
            boolean committed = recoverTransactionBestEffort(entry);
            if (committed) {
                outcome = RecoveryOutcome.COMMITTED;
            } else if (outcome == RecoveryOutcome.NONE) {
                outcome = RecoveryOutcome.DISCARDED;
            }
        }
        if (recoverChecksumVerificationSelectionBestEffort()) {
            outcome = RecoveryOutcome.COMMITTED;
        }
        return outcome;
    }

    void prepareFresh() throws IOException {
        requireOpen();
        if (transactionDirectory != null) {
            throw new IllegalStateException("A JCEF transaction is already prepared");
        }
        ensureSafeDirectory(transactionsDirectory);

        transactionId = UUID.randomUUID();
        transactionDirectory = transactionsDirectory.resolve(transactionId.toString());
        Files.createDirectory(transactionDirectory);
        transactionStateFile = transactionDirectory.resolve(TRANSACTION_STATE_FILE);
        candidateChecksum = transactionDirectory.resolve(CANDIDATE_CHECKSUM_FILE);
        candidateArchive = transactionDirectory.resolve(CANDIDATE_ARCHIVE_FILE);
        extractionDirectory = transactionDirectory.resolve(EXTRACTION_DIRECTORY);
        stagedInstallation = extractionDirectory.resolve(platformName);
        Files.createDirectory(extractionDirectory);
        writeStateAtomically(transactionStateFile, StateRecord.prepared(transactionId, platformName, javaCefCommit), false);
        forceDirectoryBestEffort(transactionDirectory);
        forceDirectoryBestEffort(transactionsDirectory);
    }

    Path findUsableInstallation(String requiredDigest, boolean allowLegacy) throws IOException {
        return findUsableInstallation(requiredDigest, allowLegacy, false);
    }

    Path findUsableInstallation(String requiredDigest, boolean allowLegacy, boolean requireChecksumVerified) throws IOException {
        return findUsableInstallation(requiredDigest, allowLegacy, requireChecksumVerified, null);
    }

    Path findUsableInstallation(String requiredDigest, Set<String> allowedChecksumSources) throws IOException {
        Objects.requireNonNull(allowedChecksumSources, "Allowed JCEF checksum sources must not be null");
        return findUsableInstallation(requiredDigest, false, true, Set.copyOf(allowedChecksumSources));
    }

    private Path findUsableInstallation(String requiredDigest, boolean allowLegacy, boolean requireChecksumVerified, Set<String> allowedChecksumSources) throws IOException {
        requireOpen();
        String normalizedDigest = requiredDigest == null ? null : MCEFInstallationState.normalizeDigest(requiredDigest);

        StateRecord selection = readStateQuietly(selectorFile, StateKind.SELECTION);
        if (selection != null && selection.matches(javaCefCommit, normalizedDigest) && checksumSourceIsEligible(selection, requireChecksumVerified, allowedChecksumSources) && validatePublishedGeneration(selection)) {
            Path selected = tryAcquireGenerationLease(selection);
            if (selected != null) {
                reclaimSupersededGenerationsBestEffort(selected);
                return selected;
            }
        }

        StateRecord discovered = findPublishedGeneration(normalizedDigest, requireChecksumVerified, allowedChecksumSources);
        if (discovered != null) {
            Path selected = tryAcquireGenerationLease(discovered);
            if (selected == null) {
                return null;
            }
            publishSelection(discovered);
            reclaimSupersededGenerationsBestEffort(selected);
            return selected;
        }

        // The fixed legacy directory has no commit or manifest identity. It is therefore available
        // only to the caller's explicit local-only compatibility path, and only before immutable
        // generation publication has left any selector/directory footprint.
        if (!allowLegacy || normalizedDigest != null || hasPublishedGenerationFootprint() || !isUsableInstallation(legacyInstallation, platform)) {
            return null;
        }
        return legacyInstallation;
    }

    Path markChecksumVerified(Path installation, String requiredDigest, String checksumSource) throws IOException {
        requireOpen();
        String normalizedDigest = MCEFInstallationState.normalizeDigest(requiredDigest);
        String normalizedChecksumSource = MCEFInstallationState.normalizeChecksumSource(checksumSource);
        Path normalizedInstallation = Objects.requireNonNull(installation, "JCEF installation must not be null").toAbsolutePath().normalize();
        if (!generationsDirectory.equals(normalizedInstallation.getParent())) {
            throw new IOException("JCEF checksum verification target is not a published generation");
        }
        Path generation = generationPath(normalizedInstallation.getFileName().toString());
        if (!generation.equals(normalizedInstallation)) {
            throw new IOException("JCEF checksum verification target escaped its generation directory");
        }

        StateRecord current = readStateQuietly(generation.resolve(GENERATION_STATE_FILE), StateKind.GENERATION);
        if (current == null || !current.matches(javaCefCommit, normalizedDigest) || !generation.getFileName().toString().equals(current.generation()) || !validatePublishedGeneration(current)) {
            throw new IOException("JCEF generation changed before checksum verification");
        }
        if (current.checksumVerified() && current.checksumSource().equals(normalizedChecksumSource)) {
            Path leasedGeneration = acquireGenerationLease(current);
            publishSelection(current);
            reclaimSupersededGenerationsBestEffort(leasedGeneration);
            return leasedGeneration;
        }
        if (current.checksumVerification() == ChecksumVerification.PENDING) {
            throw new IOException("JCEF generation is not eligible for checksum verification");
        }

        // Only checksum metadata changes here; runtime bytes and their manifest remain immutable.
        // Persist the generation first so recovery always has an authoritative record if selector
        // publication is interrupted. A source change is permitted only after the caller fetched a
        // matching checksum from that exact source during the current locked transaction.
        StateRecord checksumVerified = StateRecord.committed(StateKind.GENERATION, current.transactionId(), current.platform(), current.javaCefCommit(), current.archiveDigest(), current.manifestDigest(), ChecksumVerification.CHECKSUM_VERIFIED, normalizedChecksumSource, current.generation());
        try {
            writeStateAtomically(generation.resolve(GENERATION_STATE_FILE), checksumVerified, true);
        } catch (IOException stateFailure) {
            StateRecord persisted = readStateQuietly(generation.resolve(GENERATION_STATE_FILE), StateKind.GENERATION);
            if (persisted == null || !persisted.sameIdentity(checksumVerified) || !validatePublishedGeneration(persisted)) {
                throw stateFailure;
            }
        }
        if (!validatePublishedGeneration(checksumVerified)) {
            throw new IOException("JCEF generation changed during checksum verification");
        }
        Path leasedGeneration = acquireGenerationLease(checksumVerified);
        publishSelection(checksumVerified);
        reclaimSupersededGenerationsBestEffort(leasedGeneration);
        return leasedGeneration;
    }

    void retainCandidateArchive(MCEFVerifiedArchiveSource archive, String expectedDigest) throws IOException {
        requirePrepared();
        Objects.requireNonNull(archive, "Verified JCEF archive source must not be null");
        Path temporary = librariesDirectory.resolve("." + platformName + ".tar.gz." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                archive.verifiedPass(expectedDigest, input -> copyInput(input, output));
                output.force(true);
            }
            moveExecutor.move(temporary, retainedArchive, true);
            forceDirectoryBestEffort(librariesDirectory);
        } finally {
            deleteFileBestEffort(temporary);
        }
    }

    /**
     * Publishes a private, unique archive handoff for one deprecated phase-API caller. The final
     * name becomes visible only after the complete candidate has been forced to disk. No installer
     * lock is retained after this transaction closes; the downloader keeps the unguessable path and
     * binds it to the verified digest and checksum source in memory.
     */
    Path preserveCandidateArchiveForCompatibility() throws IOException {
        requirePrepared();
        requireRegularFile(candidateArchive, "candidate JCEF archive");
        ensurePrivateCompatibilityArchivesDirectory();
        ensureCompatibilityArchiveCapacity();

        String archiveName = COMPATIBILITY_ARCHIVE_PREFIX + ProcessHandle.current().pid() + "-" + UUID.randomUUID() + COMPATIBILITY_ARCHIVE_SUFFIX;
        Path target = compatibilityArchivesDirectory.resolve(archiveName);
        Path temporary = compatibilityArchivesDirectory.resolve("." + archiveName + "." + UUID.randomUUID() + ".tmp");
        boolean published = false;
        try {
            moveCompatibilityArchiveAtomically(candidateArchive, temporary);
            hardenPermissionsIfSupported(temporary, PRIVATE_FILE_PERMISSIONS);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            moveCompatibilityArchiveAtomically(temporary, target);
            requireRegularFile(target, "deprecated JCEF phase archive");
            hardenPermissionsIfSupported(target, PRIVATE_FILE_PERMISSIONS);
            forceDirectoryBestEffort(compatibilityArchivesDirectory);
            published = true;
            return target;
        } finally {
            deleteFileBestEffort(temporary);
            if (!published) {
                deleteFileBestEffort(target);
            }
        }
    }

    /** Moves only a handoff created for this platform into the current unique transaction. */
    void stageCompatibilityArchive(Path archive, long maxBytes) throws IOException {
        requirePrepared();
        if (pathExists(candidateArchive)) {
            throw new IOException("A candidate JCEF archive is already staged");
        }
        if (!isSafeDirectory(compatibilityArchivesDirectory)) {
            throw new IOException("Unsafe deprecated JCEF phase archive directory: " + compatibilityArchivesDirectory);
        }
        Path normalizedArchive = validateCompatibilityArchivePath(archive);
        requireRegularFile(normalizedArchive, "deprecated JCEF phase archive");
        long archiveSize = Files.size(normalizedArchive);
        if (archiveSize <= 0L || archiveSize > maxBytes) {
            throw new IOException("Deprecated JCEF phase archive size is outside the configured limit");
        }
        moveCompatibilityArchiveAtomically(normalizedArchive, candidateArchive);
        requireRegularFile(candidateArchive, "candidate JCEF archive");
    }

    /**
     * Removes abandoned handoffs while the normal installer lock is held. Live handoffs in this JVM
     * are explicitly excluded; another live process is given a bounded grace period. The hard cap
     * also prevents abandoned or hostile entries from growing this directory without limit.
     */
    void cleanupCompatibilityArchivesBestEffort(Set<Path> activeArchives) {
        requireOpen();
        Set<Path> normalizedActiveArchives = Objects.requireNonNull(activeArchives, "Active compatibility archives must not be null").stream().map(path -> path.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!pathExists(compatibilityArchivesDirectory)) {
            return;
        }
        if (!isSafeDirectory(compatibilityArchivesDirectory)) {
            warnCleanup(new IOException("Ignoring unsafe deprecated JCEF phase archive directory: " + compatibilityArchivesDirectory));
            return;
        }

        try {
            hardenPermissionsIfSupported(compatibilityArchivesDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
            List<Path> archives;
            try (var stream = Files.list(compatibilityArchivesDirectory)) {
                archives = stream.limit(MAX_COMPATIBILITY_ARCHIVES_TO_SCAN + 1L).sorted().toList();
            }
            if (archives.size() > MAX_COMPATIBILITY_ARCHIVES_TO_SCAN) {
                warnCleanup(new IOException("Deprecated JCEF phase archive count exceeds the bounded cleanup scan limit"));
                archives = archives.subList(0, MAX_COMPATIBILITY_ARCHIVES_TO_SCAN);
            }
            for (Path archive : archives) {
                cleanupCompatibilityArchiveBestEffort(archive, normalizedActiveArchives);
            }
            try (var remaining = Files.list(compatibilityArchivesDirectory)) {
                if (remaining.findAny().isEmpty()) {
                    Files.deleteIfExists(compatibilityArchivesDirectory);
                }
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            warnCleanup(new IOException("Could not clean abandoned deprecated JCEF phase archives", cleanupFailure));
        }
    }

    void stageRetainedArchiveIfMissing(long maxBytes) throws IOException {
        requirePrepared();
        if (pathExists(candidateArchive)) {
            requireRegularFile(candidateArchive, "candidate JCEF archive");
            return;
        }
        requireRegularFile(retainedArchive, "retained JCEF archive");
        long archiveSize = Files.size(retainedArchive);
        if (archiveSize <= 0L || archiveSize > maxBytes) {
            throw new IOException("Retained JCEF archive size is outside the configured limit");
        }
        copyFileCreateNew(retainedArchive, candidateArchive);
    }

    void deleteRetainedArchiveBestEffort() {
        requireOpen();
        try {
            if (!pathExists(retainedArchive)) {
                return;
            }
            if (!Files.isRegularFile(retainedArchive, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(retainedArchive)) {
                throw new IOException("Unsafe retained JCEF archive path: " + retainedArchive);
            }
            Files.delete(retainedArchive);
            forceDirectoryBestEffort(librariesDirectory);
        } catch (IOException cleanupFailure) {
            // Archive retention is independent from the immutable selected generation. A cleanup
            // failure must not turn an already valid native installation into a startup failure.
            warnCleanup(new IOException("Could not delete retained JCEF archive " + retainedArchive, cleanupFailure));
        }
    }

    void discardCandidateChecksum() throws IOException {
        requirePrepared();
        if (pathExists(candidateChecksum) && !Files.isRegularFile(candidateChecksum, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe candidate JCEF checksum path: " + candidateChecksum);
        }
        Files.deleteIfExists(candidateChecksum);
    }

    /** Validates the complete extracted tree before a caller selects a mirror attempt for promotion. */
    void validatePreparedInstallation() throws IOException {
        requirePrepared();
        validateStagedInstallation();
    }

    Path promote(String archiveDigest, String checksumSource) throws IOException {
        requirePrepared();
        String normalizedDigest = MCEFInstallationState.normalizeDigest(archiveDigest);
        ManifestIdentity manifest = validateStagedInstallation();
        MCEFGenerationLeaseRegistry.initializeLeaseProtocol(stagedInstallation);
        ChecksumVerification checksumVerification = checksumSource == null ? ChecksumVerification.UNCHECKED : ChecksumVerification.CHECKSUM_VERIFIED;
        String durableChecksumSource = checksumSource == null ? MCEFInstallationState.NO_CHECKSUM_SOURCE : MCEFInstallationState.normalizeChecksumSource(checksumSource);

        String generationName = javaCefCommit + "-" + normalizedDigest + "-" + transactionId;
        StateRecord generationState = StateRecord.committed(StateKind.GENERATION, transactionId, platformName, javaCefCommit, normalizedDigest, manifest.sha256(), checksumVerification, durableChecksumSource, generationName);
        Path generation = generationsDirectory.resolve(generationName);
        writeStateAtomically(stagedInstallation.resolve(GENERATION_STATE_FILE), generationState, false);

        StateRecord promotingState = StateRecord.promoting(transactionId, platformName, javaCefCommit, normalizedDigest, manifest.sha256(), checksumVerification, durableChecksumSource, generationName);
        writeStateAtomically(transactionStateFile, promotingState, true);
        ensureSafeDirectory(generationsDirectory);

        try {
            moveExecutor.move(stagedInstallation, generation, false);
        } catch (IOException moveFailure) {
            if (!validatePublishedGeneration(generationState)) {
                throw moveFailure;
            }
        }
        forceDirectoryBestEffort(generationsDirectory);

        // The staged validation happened before both the generation state write and the move. Validate
        // the published path again so concurrent replacement or mutation cannot reach the selector.
        if (!validatePublishedGeneration(generationState)) {
            quarantinePublishedGenerationBestEffort(generation, transactionId);
            throw new IOException("Published JCEF generation changed during promotion");
        }

        Path leasedGeneration = acquireGenerationLease(generationState);
        publishSelection(generationState);
        reclaimSupersededGenerationsBestEffort(leasedGeneration);

        transactionCommitted = true;
        try {
            writeStateAtomically(transactionStateFile, StateRecord.committed(StateKind.TRANSACTION, transactionId, platformName, javaCefCommit, normalizedDigest, manifest.sha256(), checksumVerification, durableChecksumSource, generationName), true);
        } catch (IOException stateFailure) {
            // The content-bound selector and generation manifest already establish the commit. The
            // PROMOTING state is sufficient for recovery if this final bookkeeping write fails.
            warnCleanup(new IOException("Could not finalize committed JCEF transaction metadata", stateFailure));
        }
        quarantineTransactionBestEffort(transactionDirectory, transactionId);
        clearPreparedTransaction();
        return leasedGeneration;
    }

    void abortPrepared() throws IOException {
        requireOpen();
        if (transactionDirectory == null) {
            return;
        }
        if (transactionCommitted) {
            quarantineTransactionBestEffort(transactionDirectory, transactionId);
            clearPreparedTransaction();
            return;
        }

        StateRecord state = readStateQuietly(transactionStateFile, StateKind.TRANSACTION);
        if (state != null && state.hasExactDigest() && state.generationIsExact() && validatePublishedGeneration(state)) {
            StateRecord selection = readStateQuietly(selectorFile, StateKind.SELECTION);
            if (selection != null && selection.sameIdentity(state)) {
                transactionCommitted = true;
                quarantineTransactionBestEffort(transactionDirectory, transactionId);
                clearPreparedTransaction();
                return;
            }
            // A promotion that has moved its immutable generation but has not durably selected it
            // must remain recoverable. Deleting this state would create an unidentifiable orphan.
            return;
        }

        Path abandonedTransaction = transactionDirectory;
        UUID abandonedTransactionId = transactionId;
        clearPreparedTransaction();
        quarantineTransactionBestEffort(abandonedTransaction, abandonedTransactionId);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        if (!jvmLockLease.isHeldByCurrentThread()) {
            throw new IllegalStateException("JCEF installation transaction must be closed by its lock-owning thread");
        }
        Throwable failure = null;
        try {
            if (transactionDirectory != null && !transactionCommitted) {
                closeAborter.abort(this);
            }
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        }

        try {
            fileLock.release();
        } catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        try {
            lockChannel.close();
        } catch (Throwable channelFailure) {
            failure = appendFailure(failure, channelFailure);
        }
        try {
            jvmLockLease.close();
        } catch (Throwable unlockFailure) {
            failure = appendFailure(failure, unlockFailure);
        } finally {
            closed = true;
        }
        if (failure != null) {
            throw rethrowCloseFailure(failure);
        }
    }

    /** Performs the minimal bounded check used only for pre-generation legacy installations. */
    static boolean isUsableInstallation(Path installation, MCEFPlatform platform) throws IOException {
        if (!isSafeDirectory(installation)) {
            return false;
        }
        try {
            if (containsSymbolicLinkBounded(installation)) {
                return false;
            }
        } catch (IOException inaccessible) {
            return false;
        }

        List<Path> requiredFiles = new ArrayList<>();
        if (platform.isWindows()) {
            requiredFiles.add(installation.resolve("jcef.dll"));
            requiredFiles.add(installation.resolve("libcef.dll"));
            requiredFiles.add(installation.resolve("jcef_helper.exe"));
            requiredFiles.add(installation.resolve("chrome_elf.dll"));
            requiredFiles.add(installation.resolve("d3dcompiler_47.dll"));
            requiredFiles.add(installation.resolve("libEGL.dll"));
            requiredFiles.add(installation.resolve("libGLESv2.dll"));
            requiredFiles.add(installation.resolve("icudtl.dat"));
        } else if (platform.isLinux()) {
            requiredFiles.add(installation.resolve("libjcef.so"));
            requiredFiles.add(installation.resolve("libcef.so"));
            requiredFiles.add(installation.resolve("jcef_helper"));
            requiredFiles.add(installation.resolve("icudtl.dat"));
        } else if (platform.isMacOS()) {
            Path appContents = installation.resolve("jcef_app.app").resolve("Contents");
            Path frameworks = appContents.resolve("Frameworks");
            requiredFiles.add(appContents.resolve("Java").resolve("libjcef.dylib"));
            requiredFiles.add(frameworks.resolve("Chromium Embedded Framework.framework").resolve("Chromium Embedded Framework"));
            requiredFiles.add(frameworks.resolve("Chromium Embedded Framework.framework").resolve("Resources").resolve("icudtl.dat"));
            requiredFiles.add(frameworks.resolve("jcef Helper.app").resolve("Contents").resolve("MacOS").resolve("jcef Helper"));
            requiredFiles.add(frameworks.resolve("jcef Helper (GPU).app").resolve("Contents").resolve("MacOS").resolve("jcef Helper (GPU)"));
            requiredFiles.add(frameworks.resolve("jcef Helper (Plugin).app").resolve("Contents").resolve("MacOS").resolve("jcef Helper (Plugin)"));
            requiredFiles.add(frameworks.resolve("jcef Helper (Renderer).app").resolve("Contents").resolve("MacOS").resolve("jcef Helper (Renderer)"));
        }

        for (Path requiredFile : requiredFiles) {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(requiredFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException inaccessible) {
                return false;
            }
            if (!attributes.isRegularFile() || attributes.size() <= 0L) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsSymbolicLinkBounded(Path installation) throws IOException {
        boolean[] found = {false};
        int[] entries = {0};
        Files.walkFileTree(installation, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(installation) && ++entries[0] > MAX_LEGACY_TREE_ENTRIES) {
                    throw new IOException("Legacy JCEF installation exceeds the bounded tree limit");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (++entries[0] > MAX_LEGACY_TREE_ENTRIES) {
                    throw new IOException("Legacy JCEF installation exceeds the bounded tree limit");
                }
                if (attributes.isSymbolicLink()) {
                    found[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    static void moveWithAtomicFallback(Path source, Path target, boolean replaceExisting) throws IOException {
        CopyOption[] atomicOptions = replaceExisting ? new CopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING} : new CopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        CopyOption[] fallbackOptions = replaceExisting ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new CopyOption[0];
        try {
            Files.move(source, target, atomicOptions);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, fallbackOptions);
        }
    }

    private static void moveCompatibilityArchiveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Deprecated JCEF phase archives require atomic moves within the libraries filesystem", unsupported);
        }
    }

    private boolean recoverTransactionBestEffort(Path entry) {
        UUID directoryTransactionId = parseUuid(entry.getFileName().toString());
        if (directoryTransactionId == null || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            quarantineTransactionBestEffort(entry, directoryTransactionId);
            return false;
        }

        StateRecord state = readStateQuietly(entry.resolve(TRANSACTION_STATE_FILE), StateKind.TRANSACTION);
        if (state == null || !directoryTransactionId.equals(state.transactionId()) || !platformName.equals(state.platform())) {
            quarantineTransactionBestEffort(entry, directoryTransactionId);
            return false;
        }

        if (!state.hasExactDigest() || !state.generationIsExact() || !validatePublishedGeneration(state)) {
            quarantineTransactionBestEffort(entry, directoryTransactionId);
            return false;
        }

        StateRecord selection = readStateQuietly(selectorFile, StateKind.SELECTION);
        if (selection != null && selection.sameIdentity(state) && validatePublishedGeneration(selection)) {
            quarantineTransactionBestEffort(entry, directoryTransactionId);
            return true;
        }

        if (selection != null && validatePublishedGeneration(selection)) {
            // Another fully committed generation won the selector after this generation was moved.
            // Both are immutable, so the older unfinished transaction is safe to garbage-collect.
            quarantineTransactionBestEffort(entry, directoryTransactionId);
            return false;
        }

        try {
            publishSelection(state);
            quarantineTransactionBestEffort(entry, directoryTransactionId);
            return true;
        } catch (IOException recoveryFailure) {
            warnCleanup(new IOException("Could not finish recoverable JCEF transaction " + directoryTransactionId, recoveryFailure));
            return false;
        }
    }

    private boolean recoverLegacyFixedTransactionBestEffort() {
        if (!pathExists(legacyFixedTransaction)) {
            return false;
        }
        if (!Files.isDirectory(legacyFixedTransaction, LinkOption.NOFOLLOW_LINKS)) {
            quarantineTransactionBestEffort(legacyFixedTransaction, null);
            return false;
        }

        try {
            if (isUsableInstallation(legacyInstallation, platform)) {
                // Old committed cleanup can contain Windows-locked backup DLLs. Moving the whole
                // residue to a unique GC generation makes its marker deletion order irrelevant.
                quarantineTransactionBestEffort(legacyFixedTransaction, null);
                return false;
            }

            Path previousInstallation = legacyFixedTransaction.resolve("previous-installation");
            if (!isUsableInstallation(previousInstallation, platform)) {
                quarantineTransactionBestEffort(legacyFixedTransaction, null);
                return false;
            }

            if (pathExists(legacyInstallation)) {
                ensureSafeDirectory(garbageDirectory);
                Path displaced = garbagePath("legacy-displaced");
                moveExecutor.move(legacyInstallation, displaced, false);
                deletePathBestEffort(displaced);
            }
            moveExecutor.move(previousInstallation, legacyInstallation, false);
            forceDirectoryBestEffort(librariesDirectory);

            Path previousChecksum = legacyFixedTransaction.resolve("previous.tar.gz.sha256");
            if (Files.isRegularFile(previousChecksum, LinkOption.NOFOLLOW_LINKS)) {
                Path checksumTemp = librariesDirectory.resolve("." + platformName + ".sha256.recovery." + UUID.randomUUID() + ".tmp");
                try {
                    copyFileCreateNew(previousChecksum, checksumTemp);
                    moveExecutor.move(checksumTemp, legacyChecksum, true);
                    forceDirectoryBestEffort(librariesDirectory);
                } finally {
                    deleteFileBestEffort(checksumTemp);
                }
            }
            quarantineTransactionBestEffort(legacyFixedTransaction, null);
            return true;
        } catch (IOException recoveryFailure) {
            warnCleanup(new IOException("Could not recover legacy JCEF installation transaction", recoveryFailure));
            return false;
        }
    }

    private boolean recoverChecksumVerificationSelectionBestEffort() {
        StateRecord selection = readStateQuietly(selectorFile, StateKind.SELECTION);
        if (selection == null) {
            return false;
        }
        try {
            Path generation = generationPath(selection.generation());
            StateRecord checksumVerified = readStateQuietly(generation.resolve(GENERATION_STATE_FILE), StateKind.GENERATION);
            if (checksumVerified == null || !checksumVerified.checksumVerified() || checksumVerified.sameIdentity(selection) || !checksumVerified.sameGenerationIdentityIgnoringChecksumVerification(selection) || !validatePublishedGeneration(checksumVerified)) {
                return false;
            }
            publishSelection(checksumVerified);
            return true;
        } catch (IOException recoveryFailure) {
            warnCleanup(new IOException("Could not recover interrupted JCEF checksum verification", recoveryFailure));
            return false;
        }
    }

    private void cleanupGarbageBestEffort() {
        if (!isSafeDirectory(garbageDirectory)) {
            if (pathExists(garbageDirectory)) {
                warnCleanup(new IOException("Ignoring unsafe JCEF garbage directory: " + garbageDirectory));
            }
            return;
        }
        List<Path> entries;
        try (var stream = Files.list(garbageDirectory)) {
            entries = stream.limit(MAX_GARBAGE_ENTRIES_TO_SCAN + 1L).toList();
        } catch (IOException cleanupFailure) {
            warnCleanup(new IOException("Could not inspect JCEF garbage generations", cleanupFailure));
            return;
        }
        if (entries.size() > MAX_GARBAGE_ENTRIES_TO_SCAN) {
            warnCleanup(new IOException("JCEF garbage entry count exceeds the bounded cleanup limit"));
            entries = entries.subList(0, MAX_GARBAGE_ENTRIES_TO_SCAN);
        }
        for (Path entry : entries) {
            if (cleanupBudget.exhausted()) {
                warnCleanup(new IOException("JCEF garbage cleanup exhausted its bounded tree-entry budget"));
                break;
            }
            deletePathBestEffort(entry, cleanupBudget);
        }
    }

    private void quarantineTransactionBestEffort(Path source, UUID id) {
        quarantinePathBestEffort(source, id, "stale JCEF transaction");
    }

    private void quarantinePublishedGenerationBestEffort(Path source, UUID id) {
        quarantinePathBestEffort(source, id, "invalid published JCEF generation");
    }

    private void quarantinePathBestEffort(Path source, UUID id, String description) {
        if (source == null || !pathExists(source)) {
            return;
        }
        try {
            ensureSafeDirectory(garbageDirectory);
            String identity = id == null ? "unknown" : id.toString();
            Path garbageGeneration = garbagePath("gc-" + identity);
            moveExecutor.move(source, garbageGeneration, false);
            forceDirectoryBestEffort(garbageDirectory);
            deletePathBestEffort(garbageGeneration, cleanupBudget);
        } catch (IOException cleanupFailure) {
            // Selection is content-bound and never trusts the quarantined source path. Cleanup failure
            // can leave residue, but it cannot make invalid content eligible for installation reuse.
            warnCleanup(new IOException("Could not quarantine " + description + " " + source, cleanupFailure));
        }
    }

    private Path garbagePath(String prefix) {
        return garbageDirectory.resolve(prefix + "-" + UUID.randomUUID());
    }

    private StateRecord findPublishedGeneration(String requiredDigest, boolean requireChecksumVerified, Set<String> allowedChecksumSources) throws IOException {
        if (!isSafeDirectory(generationsDirectory)) {
            return null;
        }
        List<Path> generations;
        try (var stream = Files.list(generationsDirectory)) {
            generations = stream.limit(MAX_GENERATIONS_TO_SCAN + 1L).toList();
        }
        if (generations.size() > MAX_GENERATIONS_TO_SCAN) {
            warnCleanup(new IOException("JCEF generation count exceeds the bounded discovery limit"));
            return null;
        }
        generations = generations.stream().sorted(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder())).toList();
        for (Path generation : generations) {
            if (!Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            StateRecord state = readStateQuietly(generation.resolve(GENERATION_STATE_FILE), StateKind.GENERATION);
            if (state != null && state.matches(javaCefCommit, requiredDigest) && checksumSourceIsEligible(state, requireChecksumVerified, allowedChecksumSources) && generation.getFileName().toString().equals(state.generation()) && validatePublishedGeneration(state)) {
                return state;
            }
        }
        return null;
    }

    private static boolean checksumSourceIsEligible(StateRecord state, boolean requireChecksumVerified, Set<String> allowedChecksumSources) {
        if (allowedChecksumSources != null) {
            return state.checksumVerifiedBy(allowedChecksumSources);
        }
        return !requireChecksumVerified || state.checksumVerified();
    }

    private Path tryAcquireGenerationLease(StateRecord state) {
        try {
            return acquireGenerationLease(state);
        } catch (IOException leaseFailure) {
            warnCleanup(new IOException("Could not acquire the JCEF generation lifetime lease", leaseFailure));
            return null;
        }
    }

    private Path acquireGenerationLease(StateRecord state) throws IOException {
        Path generation = generationPath(state.generation());
        return MCEFGenerationLeaseRegistry.acquire(generation);
    }

    /** Proves that lease pruning is running on the owner thread under this platform's live lock. */
    LeasePruningBudget requireLeasePruningLock(Path generation) {
        requireOpen();
        Path normalizedGeneration = Objects.requireNonNull(generation, "JCEF generation must not be null").toAbsolutePath().normalize();
        if (!jvmLockLease.isHeldByCurrentThread() || !fileLock.isValid() || !lockChannel.isOpen()) {
            throw new IllegalStateException("The JCEF platform installer lock must be held while pruning generation leases");
        }
        if (!generationsDirectory.equals(normalizedGeneration.getParent())) {
            throw new IllegalArgumentException("JCEF lease pruning target is not a platform generation: " + normalizedGeneration);
        }
        return leasePruningBudget;
    }

    private void reclaimSupersededGenerationsBestEffort(Path selectedGeneration) {
        try {
            reclaimSupersededGenerations(selectedGeneration);
        } catch (IOException | RuntimeException cleanupFailure) {
            warnCleanup(new IOException("Could not inspect superseded JCEF generations", cleanupFailure));
        }
    }

    private void reclaimSupersededGenerations(Path selectedGeneration) throws IOException {
        if (!isSafeDirectory(generationsDirectory)) {
            return;
        }
        List<Path> generations;
        try (var stream = Files.list(generationsDirectory)) {
            generations = stream.limit(MAX_GENERATIONS_TO_SCAN + 1L).toList();
        }
        if (generations.size() > MAX_GENERATIONS_TO_SCAN) {
            warnCleanup(new IOException("JCEF generation count exceeds the bounded cleanup limit"));
            return;
        }

        for (Path generation : generations) {
            if (generation.equals(selectedGeneration) || !Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String generationName = generation.getFileName().toString();
            if (!MCEFInstallationState.isGeneration(generationName)) {
                continue;
            }
            StateRecord state = readStateQuietly(generation.resolve(GENERATION_STATE_FILE), StateKind.GENERATION);
            if (state == null || !platformName.equals(state.platform()) || !generationName.equals(state.generation()) || !state.generationIsExact()) {
                continue;
            }
            if (!MCEFGenerationLeaseRegistry.hasRecognizedLeaseProtocol(generation)) {
                continue;
            }
            try {
                boolean liveLease = MCEFGenerationLeaseRegistry.pruneStaleTokens(generation, this);
                if (!liveLease && MCEFGenerationLeaseRegistry.canReclaim(generation, this)) {
                    quarantinePublishedGenerationBestEffort(generation, state.transactionId());
                }
            } catch (IOException | RuntimeException cleanupFailure) {
                warnCleanup(new IOException("Could not verify superseded JCEF generation leases for " + generation, cleanupFailure));
            }
        }
    }

    private boolean hasPublishedGenerationFootprint() {
        return pathExists(selectorFile) || pathExists(generationsDirectory);
    }

    private boolean validatePublishedGeneration(StateRecord state) {
        if (state == null || !state.hasExactDigest() || !state.generationIsExact() || !platformName.equals(state.platform())) {
            return false;
        }
        Path generation;
        try {
            generation = generationPath(state.generation());
            MCEFGenerationLeaseRegistry.pruneStaleTokens(generation, this);
            StateRecord persistedState = readStateQuietly(generation.resolve(GENERATION_STATE_FILE), StateKind.GENERATION);
            if (persistedState == null || !persistedState.sameIdentity(state)) {
                return false;
            }
            ManifestIdentity distributionManifest = MCEFDistributionManifest.validatePublished(generation, platform, javaCefCommit);
            StateRecord afterValidation = readStateQuietly(generation.resolve(GENERATION_STATE_FILE), StateKind.GENERATION);
            return afterValidation != null && afterValidation.sameIdentity(state) && state.manifestDigest().equals(distributionManifest.sha256());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private Path generationPath(String generationName) throws IOException {
        if (!MCEFInstallationState.isGeneration(generationName)) {
            throw new IOException("Invalid JCEF generation name: " + generationName);
        }
        Path generation = generationsDirectory.resolve(generationName).normalize();
        if (!generation.getParent().equals(generationsDirectory)) {
            throw new IOException("JCEF generation escaped its installation directory");
        }
        return generation;
    }

    private ManifestIdentity validateStagedInstallation() throws IOException {
        try (var entries = Files.list(extractionDirectory)) {
            List<Path> roots = entries.limit(2L).toList();
            if (roots.size() != 1 || !roots.getFirst().equals(stagedInstallation)) {
                throw new IOException("JCEF archive must contain exactly one top-level " + platformName + " directory");
            }
        }
        return MCEFDistributionManifest.validate(stagedInstallation, platform, javaCefCommit);
    }

    private void writeSelection(StateRecord source) throws IOException {
        StateRecord selection = StateRecord.committed(StateKind.SELECTION, source.transactionId(), platformName, source.javaCefCommit(), source.archiveDigest(), source.manifestDigest(), source.checksumVerification(), source.checksumSource(), source.generation());
        writeStateAtomically(selectorFile, selection, true);
        forceDirectoryBestEffort(librariesDirectory);
    }

    private void publishSelection(StateRecord source) throws IOException {
        try {
            writeSelection(source);
        } catch (IOException selectionFailure) {
            StateRecord selection = readStateQuietly(selectorFile, StateKind.SELECTION);
            if (selection == null || !selection.sameIdentity(source) || !validatePublishedGeneration(selection)) {
                throw selectionFailure;
            }
        }
    }

    private void writeStateAtomically(Path target, StateRecord state, boolean replaceExisting) throws IOException {
        writeTextAtomically(target, state.serialize(), replaceExisting);
    }

    private void writeTextAtomically(Path target, String content, boolean replaceExisting) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !isSafeDirectory(parent)) {
            throw new IOException("Unsafe parent for JCEF state file: " + target);
        }
        if (pathExists(target) && Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to replace symbolic link with JCEF state: " + target);
        }

        Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            moveExecutor.move(temporary, target, replaceExisting);
            forceDirectoryBestEffort(parent);
        } finally {
            deleteFileBestEffort(temporary);
        }
    }

    private StateRecord readStateQuietly(Path path, StateKind expectedKind) {
        return MCEFInstallationState.readQuietly(path, expectedKind);
    }

    private void ensureSafeDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (pathExists(directory)) {
            throw new IOException("Unsafe JCEF installer path: " + directory);
        }
        Path parent = directory.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe parent for JCEF installer directory: " + directory);
        }
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException race) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe JCEF installer directory created concurrently: " + directory, race);
            }
        }
    }

    private void ensurePrivateCompatibilityArchivesDirectory() throws IOException {
        if (!Files.isDirectory(compatibilityArchivesDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (pathExists(compatibilityArchivesDirectory)) {
                throw new IOException("Unsafe deprecated JCEF phase archive directory: " + compatibilityArchivesDirectory);
            }
            Path parent = compatibilityArchivesDirectory.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe parent for deprecated JCEF phase archive directory: " + compatibilityArchivesDirectory);
            }
            try {
                PosixFileAttributeView parentAttributes = Files.getFileAttributeView(parent, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (parentAttributes == null) {
                    Files.createDirectory(compatibilityArchivesDirectory);
                } else {
                    Files.createDirectory(compatibilityArchivesDirectory, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
                }
            } catch (FileAlreadyExistsException race) {
                if (!Files.isDirectory(compatibilityArchivesDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unsafe deprecated JCEF phase archive directory created concurrently: " + compatibilityArchivesDirectory, race);
                }
            }
        }
        hardenPermissionsIfSupported(compatibilityArchivesDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
    }

    private void ensureCompatibilityArchiveCapacity() throws IOException {
        try (var archives = Files.list(compatibilityArchivesDirectory)) {
            if (archives.limit(MAX_COMPATIBILITY_ARCHIVES_TO_SCAN).count() >= MAX_COMPATIBILITY_ARCHIVES_TO_SCAN) {
                throw new IOException("Too many pending deprecated JCEF phase archives");
            }
        }
    }

    private Path validateCompatibilityArchivePath(Path archive) throws IOException {
        Path normalizedArchive = Objects.requireNonNull(archive, "Deprecated JCEF phase archive must not be null").toAbsolutePath().normalize();
        if (!compatibilityArchivesDirectory.equals(normalizedArchive.getParent()) || parseCompatibilityArchiveOwner(normalizedArchive.getFileName().toString()) < 0L) {
            throw new IOException("Unsafe deprecated JCEF phase archive path: " + normalizedArchive);
        }
        return normalizedArchive;
    }

    private void cleanupCompatibilityArchiveBestEffort(Path archive, Set<Path> activeArchives) {
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        if (activeArchives.contains(normalizedArchive)) {
            return;
        }
        try {
            if (!Files.isRegularFile(normalizedArchive, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalizedArchive)) {
                throw new IOException("Unsafe deprecated JCEF phase archive entry: " + normalizedArchive);
            }
            long ownerProcess = parseCompatibilityArchiveOwner(normalizedArchive.getFileName().toString());
            boolean expired = compatibilityArchiveIsExpired(normalizedArchive);
            if (ownerProcess < 0L && !expired) {
                return;
            }
            if (ownerProcess > 0L && ownerProcess != ProcessHandle.current().pid() && processIsAlive(ownerProcess) && !expired) {
                return;
            }
            Files.deleteIfExists(normalizedArchive);
        } catch (IOException cleanupFailure) {
            warnCleanup(new IOException("Could not delete abandoned deprecated JCEF phase archive " + normalizedArchive, cleanupFailure));
        }
    }

    private static long parseCompatibilityArchiveOwner(String fileName) {
        if (!fileName.startsWith(COMPATIBILITY_ARCHIVE_PREFIX) || !fileName.endsWith(COMPATIBILITY_ARCHIVE_SUFFIX)) {
            return -1L;
        }
        int ownerEnd = fileName.indexOf('-', COMPATIBILITY_ARCHIVE_PREFIX.length());
        if (ownerEnd < 0) {
            return -1L;
        }
        try {
            long owner = Long.parseLong(fileName.substring(COMPATIBILITY_ARCHIVE_PREFIX.length(), ownerEnd));
            String identifier = fileName.substring(ownerEnd + 1, fileName.length() - COMPATIBILITY_ARCHIVE_SUFFIX.length());
            UUID.fromString(identifier);
            return owner > 0L ? owner : -1L;
        } catch (IllegalArgumentException invalidName) {
            return -1L;
        }
    }

    private static boolean compatibilityArchiveIsExpired(Path archive) throws IOException {
        long modified = Files.getLastModifiedTime(archive, LinkOption.NOFOLLOW_LINKS).toMillis();
        return modified > System.currentTimeMillis() || System.currentTimeMillis() - modified >= COMPATIBILITY_ARCHIVE_MAX_AGE_MILLIS;
    }

    private static boolean processIsAlive(long processId) {
        try {
            return ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
        } catch (SecurityException inaccessible) {
            return true;
        }
    }

    private static void hardenPermissionsIfSupported(Path path, Set<PosixFilePermission> permissions) throws IOException {
        PosixFileAttributeView attributes = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes != null) {
            attributes.setPermissions(permissions);
        }
    }

    private void validateLockFile() throws IOException {
        if (!pathExists(lockFile)) {
            return;
        }
        if (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe JCEF installation lock path: " + lockFile);
        }
    }

    private void clearPreparedTransaction() {
        transactionId = null;
        transactionDirectory = null;
        transactionStateFile = null;
        candidateChecksum = null;
        candidateArchive = null;
        extractionDirectory = null;
        stagedInstallation = null;
        transactionCommitted = false;
    }

    private void requirePrepared() {
        requireOpen();
        if (transactionDirectory == null) {
            throw new IllegalStateException("JCEF installation transaction is not prepared");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("JCEF installation transaction is closed");
        }
    }

    private void warnCleanup(IOException failure) {
        try {
            cleanupWarning.accept(failure);
        } catch (RuntimeException ignored) {
            // Cleanup reporting must never turn harmless committed residue into a startup failure.
        }
    }

    private void deletePathBestEffort(Path path) {
        deletePathBestEffort(path, cleanupBudget);
    }

    private void deletePathBestEffort(Path path, CleanupBudget cleanupBudget) {
        try {
            deletePath(path, cleanupBudget);
        } catch (IOException cleanupFailure) {
            warnCleanup(new IOException("Could not delete JCEF installer garbage " + path, cleanupFailure));
        }
    }

    private static void deleteFileBestEffort(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static void requireRegularFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or unsafe " + description + ": " + path);
        }
    }

    private static void copyFileCreateNew(Path source, Path target) throws IOException {
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS); FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long position = 0L;
            long size = input.size();
            while (position < size) {
                long transferred = input.transferTo(position, size - position, output);
                if (transferred <= 0L) {
                    ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
                    input.position(position);
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    transferred = read;
                }
                position += transferred;
            }
            if (position != size) {
                throw new IOException("Could not completely copy JCEF archive");
            }
            output.force(true);
        }
    }

    private static void copyInput(java.io.InputStream input, FileChannel output) throws IOException {
        byte[] bytes = new byte[16 * 1024];
        int read;
        while ((read = input.read(bytes)) != -1) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
            while (buffer.hasRemaining()) {
                output.write(buffer);
            }
        }
    }

    private static boolean isSafeDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean pathExists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private void deletePath(Path path, CleanupBudget cleanupBudget) throws IOException {
        if (!pathExists(path)) {
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(path, cleanupBudget);
        } else {
            cleanupBudget.recordEntry();
            Files.delete(path);
        }
    }

    private void deleteRecursively(Path root, CleanupBudget cleanupBudget) throws IOException {
        if (!pathExists(root)) {
            return;
        }
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_CLEANUP_TREE_DEPTH, new BoundedDeletingFileVisitor(cleanupBudget));
    }

    private void flattenCleanupBoundary(Path directory) throws IOException {
        Path flattened = garbagePath("gc-depth");
        moveExecutor.move(directory, flattened, false);
    }

    private static void forceDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some virtual filesystems cannot fsync directories. Every state file is
            // still forced before its atomic rename, and recovery tolerates either rename outcome.
        }
    }

    private static JvmLockLease acquireJvmLock(Path lockFile) throws IOException {
        JvmLockEntry entry = JVM_LOCKS.compute(lockFile, (ignored, current) -> {
            JvmLockEntry selected = current == null ? new JvmLockEntry() : current;
            selected.references = Math.addExact(selected.references, 1);
            return selected;
        });
        try {
            entry.lock.lockInterruptibly();
            return new JvmLockLease(lockFile, entry);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            releaseJvmLockReference(lockFile, entry);
            throw new IOException("Interrupted while waiting for the JCEF installation lock", interrupted);
        } catch (RuntimeException | Error failure) {
            try {
                releaseJvmLockReference(lockFile, entry);
            } catch (Throwable releaseFailure) {
                appendFailure(failure, releaseFailure);
            }
            throw failure;
        }
    }

    private static void releaseJvmLockReference(Path lockFile, JvmLockEntry entry) {
        JVM_LOCKS.compute(lockFile, (ignored, current) -> {
            if (current != entry || entry.references <= 0) {
                throw new IllegalStateException("JCEF JVM lock registry ownership changed unexpectedly: " + lockFile);
            }
            entry.references--;
            return entry.references == 0 ? null : entry;
        });
    }

    static int jvmLockRegistrySize() {
        return JVM_LOCKS.size();
    }

    static int jvmLockReferenceCount(Path lockFile) {
        JvmLockEntry entry = JVM_LOCKS.get(lockFile.toAbsolutePath().normalize());
        return entry == null ? 0 : entry.references;
    }

    private static final class JvmLockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile int references;
    }

    /** Owns exactly one registry reference and at most one hold of its entry's lock. */
    private static final class JvmLockLease implements AutoCloseable {
        private final Path lockFile;
        private final JvmLockEntry entry;
        private boolean closed;

        private JvmLockLease(Path lockFile, JvmLockEntry entry) {
            this.lockFile = lockFile;
            this.entry = entry;
        }

        private boolean isHeldByCurrentThread() {
            return !closed && entry.lock.isHeldByCurrentThread();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            entry.lock.unlock();
            // Mark the lease closed before registry cleanup. Even if an invariant failure occurs,
            // a repeated close must never unlock a later acquisition of the same entry.
            closed = true;
            releaseJvmLockReference(lockFile, entry);
        }
    }

    private static Throwable appendFailure(Throwable existing, Throwable additional) {
        if (existing == null) {
            return additional;
        }
        if (existing != additional) {
            try {
                existing.addSuppressed(additional);
            } catch (Throwable ignored) {
                // Suppression bookkeeping must never prevent the remaining lock releases.
            }
        }
        return existing;
    }

    private static IOException rethrowCloseFailure(Throwable failure) {
        if (failure instanceof IOException ioFailure) {
            return ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        return new IOException("Unexpected JCEF installation cleanup failure", failure);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static final class CleanupBudget {
        private int remainingEntries;

        private CleanupBudget(int remainingEntries) {
            this.remainingEntries = remainingEntries;
        }

        private boolean exhausted() {
            return remainingEntries <= 0;
        }

        private void recordEntry() throws IOException {
            if (exhausted()) {
                throw new IOException("JCEF cleanup exceeded its bounded tree-entry limit");
            }
            remainingEntries--;
        }
    }

    record LeasePruningResult(Path leaseDirectory, BasicFileAttributes directorySnapshot, boolean liveToken) {
        LeasePruningResult {
            Objects.requireNonNull(leaseDirectory, "JCEF lease directory must not be null");
            Objects.requireNonNull(directorySnapshot, "JCEF lease directory snapshot must not be null");
        }
    }

    /** Caps all attacker-controlled lease-directory work performed under one installer lock. */
    static final class LeasePruningBudget {
        private static final String ENTRY_LIMIT_FAILURE = "JCEF generation lease pruning exceeded its bounded installer-lock entry limit";
        private static final String SCAN_LIMIT_FAILURE = "JCEF generation lease pruning exceeded its bounded installer-lock scan limit";

        private final Map<Path, LeasePruningResult> successfulPrunes = new HashMap<>();
        private int remainingScanProbes;
        private int remainingEntries;
        // Validation and cleanup intentionally catch pruning failures. Latching the first exhausted
        // limit prevents those later callers from reopening and advancing the same hostile stream.
        private String terminalFailure;

        private LeasePruningBudget(int remainingScanProbes, int remainingEntries) {
            this.remainingScanProbes = remainingScanProbes;
            this.remainingEntries = remainingEntries;
        }

        void recordScanProbe() throws IOException {
            requireNotTerminal();
            if (remainingScanProbes <= 0) {
                failTerminal(SCAN_LIMIT_FAILURE);
            }
            remainingScanProbes--;
        }

        void recordEntry() throws IOException {
            requireNotTerminal();
            if (remainingEntries <= 0) {
                failTerminal(ENTRY_LIMIT_FAILURE);
            }
            remainingEntries--;
        }

        private void requireNotTerminal() throws IOException {
            if (terminalFailure != null) {
                throw new IOException(terminalFailure);
            }
        }

        private void failTerminal(String message) throws IOException {
            terminalFailure = message;
            throw new IOException(message);
        }

        LeasePruningResult successfulPrune(Path canonicalGeneration) {
            return successfulPrunes.get(canonicalGeneration);
        }

        void invalidateSuccessfulPrune(Path canonicalGeneration) {
            successfulPrunes.remove(canonicalGeneration);
        }

        void recordSuccessfulPrune(Path canonicalGeneration, LeasePruningResult result) {
            successfulPrunes.put(canonicalGeneration, result);
        }
    }

    private final class BoundedDeletingFileVisitor extends SimpleFileVisitor<Path> {
        private final CleanupBudget cleanupBudget;

        private BoundedDeletingFileVisitor(CleanupBudget cleanupBudget) {
            this.cleanupBudget = cleanupBudget;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            cleanupBudget.recordEntry();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            cleanupBudget.recordEntry();
            if (attributes.isDirectory()) {
                flattenCleanupBoundary(file);
                return FileVisitResult.CONTINUE;
            }
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
            if (failure != null) {
                throw failure;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
        }
    }
}
