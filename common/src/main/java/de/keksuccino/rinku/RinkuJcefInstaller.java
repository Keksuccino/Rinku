package de.keksuccino.rinku;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Owns one locked, exact-commit JCEF installation attempt. */
public final class RinkuJcefInstaller implements AutoCloseable {
    private static final String CACHE_VERSION_DIRECTORY_NAME = "jcef-v1";
    private static final String LOCK_FILE_NAME = ".install.lock";
    private static final String STAGING_DIRECTORY_NAME = ".staging";
    private static final Pattern STAGING_NAME_PATTERN = Pattern.compile("[0-9a-f]{40}-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final int MAX_STAGING_ENTRIES_PER_CLEANUP = 256;
    private static final int MAX_STAGING_DIRECTORIES_PER_CLEANUP = 64;
    private static final int MAX_CLEANUP_ENTRIES = RinkuSecureArchiveExtractor.MAX_EXTRACTED_FILESYSTEM_ENTRIES + 192;
    private static final Object JVM_LOCK_REGISTRY_MONITOR = new Object();
    private static final Map<Path, JvmLockEntry> JVM_LOCKS = new HashMap<>();

    private final OSPlatform platform;
    private final String javaCefCommit;
    private final Path platformDirectory;
    private final Path stagingRootDirectory;
    private final Path installationDirectory;
    private final Path lockFile;
    private final Consumer<IOException> cleanupWarning;
    private final Thread ownerThread = Thread.currentThread();
    private final JvmLockLease jvmLockLease;
    private final FileChannel lockChannel;
    private final FileLock fileLock;

    private Path stagingDirectory;
    private Path candidateArchive;
    private Path candidateChecksum;
    private Path extractionDirectory;
    private boolean closed;

    public RinkuJcefInstaller(Path librariesDirectory, OSPlatform platform, String javaCefCommit, Consumer<IOException> cleanupWarning) throws IOException {
        this.platform = Objects.requireNonNull(platform, "Rinku platform must not be null");
        this.javaCefCommit = RinkuJcefInstallationValidator.normalizeCommit(javaCefCommit);
        this.cleanupWarning = Objects.requireNonNull(cleanupWarning, "JCEF cleanup warning handler must not be null");

        Path configuredLibrariesDirectory = Objects.requireNonNull(librariesDirectory, "Rinku libraries directory must not be null").toAbsolutePath().normalize();
        Files.createDirectories(configuredLibrariesDirectory);
        requireSafeDirectory(configuredLibrariesDirectory, "Rinku libraries directory");
        Path realLibrariesDirectory = configuredLibrariesDirectory.toRealPath();
        Path cacheDirectory = ensureSafeChildDirectory(realLibrariesDirectory, CACHE_VERSION_DIRECTORY_NAME);
        platformDirectory = ensureSafeChildDirectory(cacheDirectory, platform.getNormalizedName());
        stagingRootDirectory = ensureSafeChildDirectory(platformDirectory, STAGING_DIRECTORY_NAME);
        installationDirectory = platformDirectory.resolve(this.javaCefCommit);
        lockFile = platformDirectory.resolve(LOCK_FILE_NAME);

        JvmLockLease acquiredJvmLock = acquireJvmLock(lockFile);
        FileChannel openedChannel = null;
        FileLock acquiredFileLock = null;
        try {
            validateLockFile();
            openedChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                acquiredFileLock = openedChannel.lock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("JCEF platform installation lock is already held by this thread", failure);
            }
        } catch (Throwable failure) {
            if (openedChannel != null) {
                try {
                    openedChannel.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                acquiredJvmLock.close();
            } catch (Throwable unlockFailure) {
                failure.addSuppressed(unlockFailure);
            }
            throw rethrow(failure);
        }
        jvmLockLease = acquiredJvmLock;
        lockChannel = openedChannel;
        fileLock = acquiredFileLock;
    }

    Path lockFile() {
        return lockFile;
    }

    public Path candidateArchive() {
        requirePrepared();
        return candidateArchive;
    }

    public Path candidateChecksum() {
        requirePrepared();
        return candidateChecksum;
    }

    public Path extractionDirectory() {
        requirePrepared();
        return extractionDirectory;
    }

    public void recover() {
        requireOpenAndOwner();
        cleanupAbandonedStagingBestEffort();
    }

    public Path findReusableInstallation() {
        requireOpenAndOwner();
        return RinkuJcefInstallationValidator.isReusable(installationDirectory, platform, javaCefCommit) ? installationDirectory : null;
    }

    public void prepareFresh() throws IOException {
        requireOpenAndOwner();
        if (stagingDirectory != null) {
            throw new IllegalStateException("A JCEF staging directory is already prepared");
        }
        requireSafeDirectory(stagingRootDirectory, "JCEF staging root directory");
        Path created = stagingRootDirectory.resolve(javaCefCommit + "-" + UUID.randomUUID());
        Files.createDirectory(created);
        stagingDirectory = created;
        candidateArchive = created.resolve("archive.tar.gz");
        candidateChecksum = created.resolve("archive.tar.gz.sha256");
        extractionDirectory = created.resolve("extracted");
        Files.createDirectory(extractionDirectory);
        forceDirectoryBestEffort(created);
        forceDirectoryBestEffort(stagingRootDirectory);
    }

    public void discardCandidateChecksum() throws IOException {
        requirePrepared();
        if (Files.exists(candidateChecksum, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(candidateChecksum, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe candidate JCEF checksum path: " + candidateChecksum);
        }
        Files.deleteIfExists(candidateChecksum);
    }

    public Path publish() throws IOException {
        requirePrepared();
        Path stagedInstallation = extractionDirectory.resolve(platform.getNormalizedName());
        RinkuJcefInstallationValidator.validateExtracted(stagedInstallation, platform, javaCefCommit);

        // A completed cache leaf must never claim readiness before every executable needed by CEF
        // has its launch permission restored on filesystems that preserve Unix modes.
        for (Path executable : CefUtil.unixExecutablePaths(stagedInstallation, platform)) {
            CefUtil.addUnixExecutePermissions(executable);
        }
        RinkuJcefInstallationValidator.writeCompleteMarker(stagedInstallation, platform, javaCefCommit);
        forceDirectoryBestEffort(stagedInstallation);

        if (Files.exists(installationDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (RinkuJcefInstallationValidator.isReusable(installationDirectory, platform, javaCefCommit)) {
                discardPreparedBestEffort();
                return installationDirectory;
            }
            deleteInstallerOwnedPath(installationDirectory);
        }

        try {
            Files.move(stagedInstallation, installationDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("JCEF cache publication requires an atomic move on the rinku-libraries filesystem", failure);
        } catch (FileAlreadyExistsException race) {
            if (!RinkuJcefInstallationValidator.isReusable(installationDirectory, platform, javaCefCommit)) {
                throw new IOException("A non-reusable JCEF cache leaf appeared during atomic publication", race);
            }
        }
        forceDirectoryBestEffort(platformDirectory);
        RinkuJcefInstallationValidator.validateCompleted(installationDirectory, platform, javaCefCommit);
        discardPreparedBestEffort();
        return installationDirectory;
    }

    public void discardPrepared() throws IOException {
        requireOpenAndOwner();
        if (stagingDirectory == null) {
            return;
        }
        Path abandoned = stagingDirectory;
        clearPreparedPaths();
        deleteInstallerOwnedPath(abandoned);
    }

    private void discardPreparedBestEffort() {
        if (stagingDirectory == null) {
            return;
        }
        Path abandoned = stagingDirectory;
        clearPreparedPaths();
        try {
            deleteInstallerOwnedPath(abandoned);
        } catch (IOException failure) {
            cleanupWarning.accept(new IOException("Could not delete JCEF staging residue " + abandoned, failure));
        }
    }

    private void cleanupAbandonedStagingBestEffort() {
        int inspected = 0;
        int recognized = 0;
        try {
            requireSafeDirectory(stagingRootDirectory, "JCEF staging root directory");
        } catch (IOException failure) {
            cleanupWarning.accept(failure);
            return;
        }
        try (var entries = Files.newDirectoryStream(stagingRootDirectory)) {
            for (Path entry : entries) {
                if (++inspected > MAX_STAGING_ENTRIES_PER_CLEANUP) {
                    cleanupWarning.accept(new IOException("JCEF staging cleanup exceeded its bounded entry-inspection limit"));
                    break;
                }
                String name = entry.getFileName().toString();
                if (!STAGING_NAME_PATTERN.matcher(name).matches()) {
                    continue;
                }
                if (++recognized > MAX_STAGING_DIRECTORIES_PER_CLEANUP) {
                    cleanupWarning.accept(new IOException("JCEF staging cleanup exceeded its bounded directory limit"));
                    break;
                }
                try {
                    deleteInstallerOwnedPath(entry);
                } catch (IOException failure) {
                    cleanupWarning.accept(new IOException("Could not delete abandoned JCEF staging directory " + entry, failure));
                }
            }
        } catch (IOException failure) {
            cleanupWarning.accept(new IOException("Could not inspect abandoned JCEF staging directories", failure));
        }
    }

    private void deleteInstallerOwnedPath(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        String name = normalized.getFileName().toString();
        boolean currentCommitLeaf = platformDirectory.equals(normalized.getParent()) && name.equals(javaCefCommit);
        boolean stagingChild = stagingRootDirectory.equals(normalized.getParent()) && STAGING_NAME_PATTERN.matcher(name).matches();
        if (!currentCommitLeaf && !stagingChild) {
            throw new IOException("Refusing to delete a non-installer JCEF cache path: " + normalized);
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(normalized);
            return;
        }

        int[] visited = {0};
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            private void count(Path entry) throws IOException {
                if (++visited[0] > MAX_CLEANUP_ENTRIES) {
                    throw new IOException("JCEF staging cleanup exceeded its bounded tree-entry limit at " + entry);
                }
            }

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                count(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                count(file);
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
        });
    }

    private void validateLockFile() throws IOException {
        if (!Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(lockFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Unsafe JCEF platform installation lock path: " + lockFile);
        }
    }

    private void clearPreparedPaths() {
        stagingDirectory = null;
        candidateArchive = null;
        candidateChecksum = null;
        extractionDirectory = null;
    }

    private void requirePrepared() {
        requireOpenAndOwner();
        if (stagingDirectory == null) {
            throw new IllegalStateException("JCEF staging directory is not prepared");
        }
    }

    private void requireOpenAndOwner() {
        if (closed) {
            throw new IllegalStateException("JCEF installer is closed");
        }
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("JCEF installer must be used by its opening thread");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        requireOpenAndOwner();
        Throwable failure = null;
        try {
            discardPrepared();
        } catch (Throwable cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            fileLock.release();
        } catch (Throwable releaseFailure) {
            failure = appendFailure(failure, releaseFailure);
        }
        try {
            lockChannel.close();
        } catch (Throwable closeFailure) {
            failure = appendFailure(failure, closeFailure);
        }
        try {
            jvmLockLease.close();
        } catch (Throwable unlockFailure) {
            failure = appendFailure(failure, unlockFailure);
        } finally {
            closed = true;
        }
        if (failure != null) {
            throw rethrow(failure);
        }
    }

    private static Path ensureSafeChildDirectory(Path parent, String name) throws IOException {
        Path directory = parent.resolve(name);
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ignored) {
        }
        requireSafeDirectory(directory, "JCEF cache directory");
        return directory.toRealPath();
    }

    private static void requireSafeDirectory(Path directory, String description) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Missing or unsafe " + description + ": " + directory);
        }
    }

    private static void forceDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    private static JvmLockLease acquireJvmLock(Path lockFile) throws IOException {
        JvmLockEntry entry;
        synchronized (JVM_LOCK_REGISTRY_MONITOR) {
            entry = JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new JvmLockEntry());
            entry.references++;
        }
        try {
            entry.lock.lockInterruptibly();
            return new JvmLockLease(lockFile, entry);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            releaseJvmLockReference(lockFile, entry);
            throw new IOException("Interrupted while waiting for the JCEF platform installation lock", failure);
        }
    }

    private static void releaseJvmLockReference(Path lockFile, JvmLockEntry entry) {
        synchronized (JVM_LOCK_REGISTRY_MONITOR) {
            entry.references--;
            if (entry.references == 0) {
                JVM_LOCKS.remove(lockFile, entry);
            }
        }
    }

    private static Throwable appendFailure(Throwable current, Throwable additional) {
        if (current == null) {
            return additional;
        }
        if (current != additional) {
            current.addSuppressed(additional);
        }
        return current;
    }

    private static IOException rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException ioFailure) {
            return ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IOException("Unexpected JCEF installer failure", failure);
    }

    private static final class JvmLockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    private static final class JvmLockLease implements AutoCloseable {
        private final Path lockFile;
        private final JvmLockEntry entry;
        private boolean closed;

        private JvmLockLease(Path lockFile, JvmLockEntry entry) {
            this.lockFile = lockFile;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            entry.lock.unlock();
            releaseJvmLockReference(lockFile, entry);
        }
    }
}
