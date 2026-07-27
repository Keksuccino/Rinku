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

import de.keksuccino.mcef.internal.MCEFDownloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A downloader and extraction tool for java-cef builds.
 * <p>
 * Downloads are published with <a href="https://github.com/Keksuccino/jcef-mcef">Keksuccino
 * jcef-mcef</a> unless changed in the MCEFSettings properties file; see {@link MCEFSettings}.
 */
public class MCEFDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");

    @FunctionalInterface
    interface ArtifactDownloader {
        void download(String assetUrl, File outputFile, long maxBytes) throws IOException;
    }

    @FunctionalInterface
    interface ArchiveExtractor {
        void extract(InputStream archive, File outputDirectory) throws IOException;
    }

    public static final String OFFICIAL_MIRROR = "https://github.com/Keksuccino/jcef-mcef/releases/download";
    public static final String PREVIOUS_OFFICIAL_MIRROR = "https://github.com/Keksuccino/mcef_resources/releases/download";
    public static final String LEGACY_OFFICIAL_MIRROR = "https://mcef-download.cinemamod.com";

    private static final String JAVA_CEF_RELEASE_TAG_PREFIX = "java-cef-";
    private static final MCEFDownloadMirror OFFICIAL_DOWNLOAD_MIRROR = MCEFDownloadMirror.parse(OFFICIAL_MIRROR);
    private static final int DOWNLOAD_BUFFER_SIZE_BYTES = 16 * 1024;
    private static final Pattern GNU_SHA256_PATTERN = Pattern.compile("(?i)^([0-9a-f]{64})(?:[ \\t]+\\*?([^\\r\\n]+))?$");
    private static final Pattern BSD_SHA256_PATTERN = Pattern.compile("(?i)^SHA256[ \\t]*\\(([^\\r\\n]+)\\)[ \\t]*=[ \\t]*([0-9a-f]{64})$");
    private static final Cleaner COMPATIBILITY_ARCHIVE_CLEANER = Cleaner.create();
    private static final ConcurrentMap<Path, WeakReference<CompatibilityArchiveHandle>> ACTIVE_COMPATIBILITY_ARCHIVES = new ConcurrentHashMap<>();

    /** Controls both download fallback and which checksum-verified cached generations may be reused. */
    public enum MirrorPolicy {
        /** Uses and reuses only releases whose checksum came from the official endpoint. */
        OFFICIAL_ONLY,
        /** Tries the configured endpoint first and permits official fallback generations as well. */
        PREFER_CONFIGURED,
        /** Uses and reuses only releases whose checksum came from the configured endpoint. */
        CONFIGURED_ONLY
    }

    /**
     * Installer network and resource limits. A matching checksum establishes consistency with the
     * selected endpoint; it is not an independent publisher signature or authentication proof.
     */
    public record DownloadPolicy(
            MirrorPolicy mirrorPolicy,
            boolean enforceChecksums,
            int connectTimeoutMs,
            int readTimeoutMs,
            long maxArchiveBytes,
            long maxChecksumBytes,
            long maxExtractedBytes
    ) {
        public DownloadPolicy {
            mirrorPolicy = mirrorPolicy == null ? MirrorPolicy.OFFICIAL_ONLY : mirrorPolicy;
            connectTimeoutMs = Math.max(1000, connectTimeoutMs);
            readTimeoutMs = Math.max(1000, readTimeoutMs);
            maxArchiveBytes = Math.max(1_048_576L, maxArchiveBytes);
            maxChecksumBytes = Math.max(512L, maxChecksumBytes);
            maxExtractedBytes = Math.max(1_048_576L, maxExtractedBytes);
        }

        public static DownloadPolicy defaults() {
            return new DownloadPolicy(
                    MirrorPolicy.OFFICIAL_ONLY,
                    true,
                    15_000,
                    60_000,
                    750L * 1024L * 1024L,
                    64L * 1024L,
                    2_000L * 1024L * 1024L
            );
        }
    }

    private final String host;
    private final MCEFDownloadMirror configuredMirror;
    private final Set<String> allowedChecksumSources;
    private final String javaCefCommitHash;
    private final MCEFPlatform platform;
    private final DownloadPolicy downloadPolicy;
    private final Path librariesDirectoryOverride;
    private final ArtifactDownloader artifactDownloader;
    private final ArchiveExtractor archiveExtractor;
    private CompatibilityRelease compatibilityRelease;
    private CompatibilityArchiveHandle compatibilityArchive;

    public MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform) {
        this(host, javaCefCommitHash, platform, DownloadPolicy.defaults());
    }

    public MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform, DownloadPolicy downloadPolicy) {
        this(host, javaCefCommitHash, platform, downloadPolicy, null, null, null);
    }

    MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform, DownloadPolicy downloadPolicy, Path librariesDirectory, ArtifactDownloader artifactDownloader, ArchiveExtractor archiveExtractor) {
        this.javaCefCommitHash = normalizeCommitHash_MCEF(javaCefCommitHash);
        this.platform = Objects.requireNonNull(platform, "MCEF platform must not be null");
        this.downloadPolicy = downloadPolicy == null ? DownloadPolicy.defaults() : downloadPolicy;
        configuredMirror = resolveConfiguredMirror(host, this.downloadPolicy.mirrorPolicy());
        this.host = configuredMirror == null ? OFFICIAL_MIRROR : configuredMirror.externalForm();
        allowedChecksumSources = resolveMirrorCandidates().stream().map(this::checksumSource).collect(java.util.stream.Collectors.toUnmodifiableSet());
        librariesDirectoryOverride = librariesDirectory == null ? null : librariesDirectory.toAbsolutePath().normalize();
        this.artifactDownloader = artifactDownloader;
        this.archiveExtractor = archiveExtractor;
    }

    public String getHost() {
        return host;
    }

    public String getJavaCefDownloadUrl() {
        return archiveUri(resolveMirrorCandidates().getFirst()).toASCIIString();
    }

    public String getJavaCefChecksumDownloadUrl() {
        return checksumUri(resolveMirrorCandidates().getFirst()).toASCIIString();
    }

    public DownloadPolicy getDownloadPolicy() {
        return downloadPolicy;
    }

    public record InstallationResult(Path installationDirectory, boolean downloaded) {
        public InstallationResult {
            installationDirectory = Objects.requireNonNull(installationDirectory, "JCEF installation directory must not be null").toAbsolutePath().normalize();
        }
    }

    private record CompatibilityRelease(MCEFDownloadMirror mirror, int mirrorIndex, String expectedChecksum) {
        private CompatibilityRelease {
            Objects.requireNonNull(mirror, "Deprecated JCEF phase mirror must not be null");
            if (mirrorIndex < 0) {
                throw new IllegalArgumentException("Deprecated JCEF phase mirror index must not be negative");
            }
            expectedChecksum = expectedChecksum == null ? null : MCEFInstallationState.normalizeDigest(expectedChecksum);
        }
    }

    private record CompatibilityArchiveDownload(CompatibilityRelease release, String archiveDigest, long archiveSize) {
        private CompatibilityArchiveDownload {
            Objects.requireNonNull(release, "Deprecated JCEF phase release must not be null");
            archiveDigest = MCEFInstallationState.normalizeDigest(archiveDigest);
            if (archiveSize <= 0L) {
                throw new IllegalArgumentException("Deprecated JCEF phase archive size must be positive");
            }
            if (release.expectedChecksum() != null && !release.expectedChecksum().equals(archiveDigest)) {
                throw new IllegalArgumentException("Deprecated JCEF phase archive is not bound to its checksum source");
            }
        }
    }

    private static final class CompatibilityArchiveHandle {
        private final CompatibilityRelease release;
        private final String archiveDigest;
        private final long archiveSize;
        private final CompatibilityArchiveCleanup cleanup;
        private final Cleaner.Cleanable cleanable;

        private CompatibilityArchiveHandle(CompatibilityArchiveDownload download, Path path) {
            release = download.release();
            archiveDigest = download.archiveDigest();
            archiveSize = download.archiveSize();
            Path normalizedPath = Objects.requireNonNull(path, "Deprecated JCEF phase archive path must not be null").toAbsolutePath().normalize();
            cleanup = new CompatibilityArchiveCleanup(normalizedPath);
            ACTIVE_COMPATIBILITY_ARCHIVES.put(normalizedPath, new WeakReference<>(this));
            try {
                cleanable = COMPATIBILITY_ARCHIVE_CLEANER.register(this, cleanup);
            } catch (RuntimeException | Error registrationFailure) {
                ACTIVE_COMPATIBILITY_ARCHIVES.remove(normalizedPath);
                try {
                    deleteCompatibilityArchivePath(normalizedPath);
                } catch (IOException cleanupFailure) {
                    collectFailure(registrationFailure, cleanupFailure);
                }
                throw registrationFailure;
            }
        }

        private Path path() {
            return cleanup.path();
        }

        private void delete() throws IOException {
            try {
                cleanup.delete();
            } finally {
                cleanable.clean();
            }
        }
    }

    private static final class CompatibilityArchiveCleanup implements Runnable {
        private Path path;

        private CompatibilityArchiveCleanup(Path path) {
            this.path = path;
        }

        private synchronized Path path() {
            if (path == null) {
                throw new IllegalStateException("Deprecated JCEF phase archive has already been discarded");
            }
            return path;
        }

        private synchronized void delete() throws IOException {
            if (path == null) {
                return;
            }
            deleteCompatibilityArchivePath(path);
            ACTIVE_COMPATIBILITY_ARCHIVES.remove(path);
            path = null;
        }

        @Override
        public void run() {
            try {
                delete();
            } catch (IOException cleanupFailure) {
                releaseForInstallerCleanup();
                LOGGER.warn("Could not delete an abandoned deprecated JCEF phase archive; a later installer phase will retry cleanup.", cleanupFailure);
            }
        }

        private synchronized void releaseForInstallerCleanup() {
            if (path != null) {
                ACTIVE_COMPATIBILITY_ARCHIVES.remove(path);
            }
        }
    }

    /**
     * Holds the JVM mutex and operating-system lock for one complete installer interaction.
     * Sessions are single-use and must be closed by the thread that opened them.
     */
    public final class InstallationSession implements AutoCloseable {
        private final MCEFInstallationTransaction transaction;
        private final Thread owner = Thread.currentThread();
        private boolean used;
        private boolean closed;

        private InstallationSession() throws IOException {
            transaction = newInstallationTransaction();
        }

        public InstallationResult installOrUpdate(boolean skipDownload, boolean deleteArchive) throws IOException {
            checkOwner();
            if (used) {
                throw new IllegalStateException("JCEF installation session has already been used");
            }
            used = true;
            return performInstallOrUpdate(transaction, skipDownload, deleteArchive);
        }

        @Override
        public void close() throws IOException {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("JCEF installation session must be closed by its opening thread");
            }
            if (closed) {
                return;
            }
            closed = true;
            transaction.close();
        }

        private void checkOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("JCEF installation session must be used by its opening thread");
            }
            if (closed) {
                throw new IllegalStateException("JCEF installation session is closed");
            }
        }
    }

    private static String normalizeCommitHash_MCEF(String commitHash) {
        if (commitHash == null || commitHash.isBlank()) {
            throw new IllegalArgumentException("java-cef commit hash is missing");
        }

        return MCEFInstallationState.normalizeCommit(commitHash.trim());
    }

    public InstallationSession openInstallationSession() throws IOException {
        return new InstallationSession();
    }

    /** Runs recovery, validation, downloads, extraction, and publication under one lock. */
    public InstallationResult installOrUpdate(boolean skipDownload, boolean deleteArchive) throws IOException {
        try (InstallationSession session = openInstallationSession()) {
            return session.installOrUpdate(skipDownload, deleteArchive);
        }
    }

    /** Restores or finalizes transactions interrupted before the current process started. */
    public synchronized void recoverInterruptedInstallation() throws IOException {
        try (MCEFInstallationTransaction transaction = newInstallationTransaction()) {
            transaction.recover();
            cleanupCompatibilityArchives(transaction);
        }
    }

    /**
     * Compatibility entry point for callers using the former phase API. New code should use
     * {@link #installOrUpdate(boolean, boolean)}. This method is a self-contained probe: it releases
     * every installer lock before returning, including after a false result. A false result retains
     * only immutable checksum-source metadata in this downloader for later phase calls, which may be
     * made sequentially by another thread.
     */
    @Deprecated
    public synchronized boolean downloadJavaCefChecksum() throws IOException {
        discardCompatibilityState();
        try (MCEFInstallationTransaction transaction = newInstallationTransaction()) {
            transaction.recover();
            cleanupCompatibilityArchives(transaction);
            transaction.prepareFresh();
            List<MCEFDownloadMirror> mirrors = resolveMirrorCandidates();
            for (int index = 0; index < mirrors.size(); index++) {
                MCEFDownloadMirror mirror = mirrors.get(index);
                try {
                    String downloadedChecksum = downloadChecksumFromMirror(transaction, mirror);
                    Path installed = downloadedChecksum == null ? null : transaction.findUsableInstallation(downloadedChecksum, false, false);
                    if (installed != null) {
                        installed = transaction.markChecksumVerified(installed, downloadedChecksum, checksumSource(mirror));
                        transaction.abortPrepared();
                        System.setProperty("jcef.path", installed.toString());
                        return true;
                    }
                    CompatibilityRelease release = new CompatibilityRelease(mirror, index, downloadedChecksum);
                    transaction.abortPrepared();
                    compatibilityRelease = release;
                    return false;
                } catch (IOException mirrorFailure) {
                    LOGGER.warn("JCEF checksum validation failed for {}", mirror.safeLogIdentity());
                    if (index + 1 < mirrors.size()) {
                        resetPreparedTransactionForRetry(transaction);
                    }
                }
            }
            throw new IOException("Failed to obtain a valid JCEF checksum from the permitted mirror set");
        } catch (IOException | RuntimeException | Error failure) {
            discardCompatibilityState(failure);
            throw failure;
        }
    }

    /**
     * Downloads and validates one release into a private, digest-bound handoff, then releases every
     * installer lock before returning. Call {@link #abortJavaCefInstallation()} if the following
     * extract phase is abandoned. Because this deprecated handoff owns no lock or explicit closeable
     * session, another installer process may reclaim it after a 24-hour abandonment grace period.
     * Continue with the same downloader instance; separate instances deliberately own isolated
     * handoffs, although the next call on this instance may run on any thread.
     *
     * @deprecated Use {@link #installOrUpdate(boolean, boolean)}.
     */
    @Deprecated
    public synchronized void downloadJavaCefBuild() throws IOException {
        discardCompatibilityArchive();
        CompatibilityArchiveHandle downloadedArchive = null;
        try {
            try (MCEFInstallationTransaction transaction = newInstallationTransaction()) {
                transaction.recover();
                cleanupCompatibilityArchives(transaction);
                transaction.prepareFresh();
                CompatibilityArchiveDownload download = downloadCompatibilityArchive(transaction, compatibilityRelease);
                Path handoff = transaction.preserveCandidateArchiveForCompatibility();
                downloadedArchive = new CompatibilityArchiveHandle(download, handoff);
                transaction.abortPrepared();
            }
            compatibilityRelease = downloadedArchive.release;
            compatibilityArchive = downloadedArchive;
        } catch (IOException | RuntimeException | Error failure) {
            discardCompatibilityArchive(downloadedArchive, failure);
            discardCompatibilityState(failure);
            throw failure;
        }
    }

    /**
     * Reacquires the installer transaction, moves the private handoff into it without following
     * links, and revalidates the captured digest and checksum source before extraction or promotion.
     * The call may run on a different thread from either preceding compatibility phase, but must use
     * the same downloader instance that owns the private handoff.
     *
     * @deprecated Use {@link #installOrUpdate(boolean, boolean)}.
     */
    @Deprecated
    public synchronized void extractJavaCefBuild(boolean delete) throws IOException {
        CompatibilityArchiveHandle phaseArchive = compatibilityArchive;
        CompatibilityRelease phaseRelease = phaseArchive == null ? compatibilityRelease : phaseArchive.release;
        try {
            try (MCEFInstallationTransaction transaction = newInstallationTransaction()) {
                transaction.recover();
                cleanupCompatibilityArchives(transaction);
                transaction.prepareFresh();
                PreparedDownload prepared;
                try {
                    prepared = prepareCompatibilityCandidate(transaction, phaseArchive, phaseRelease, delete);
                } catch (IOException | RuntimeException firstFailure) {
                    if (phaseRelease == null) {
                        throw asIOExceptionOrThrowRuntime(firstFailure);
                    }
                    prepared = prepareCompatibilityDownloadFromLaterMirror(transaction, phaseRelease.mirrorIndex() + 1, delete, firstFailure);
                }
                Path installed = prepared.existingInstallation() == null ? transaction.promote(prepared.archiveDigest(), prepared.checksumSource()) : prepared.existingInstallation();
                completeInstallation(transaction, installed, prepared.existingInstallation() == null, delete);
                System.setProperty("jcef.path", installed.toString());
            }
            discardCompatibilityStateBestEffort();
        } catch (IOException | RuntimeException | Error failure) {
            discardCompatibilityState(failure);
            throw failure;
        }
    }

    /**
     * Discards checksum-source metadata and any private archive retained between deprecated phase
     * calls. Compatibility phases never retain installer locks, so this method is idempotent and may
     * be called by any thread after the preceding phase has returned.
     *
     * @deprecated Prefer a try-with-resources {@link InstallationSession}.
     */
    @Deprecated
    public synchronized void abortJavaCefInstallation() throws IOException {
        discardCompatibilityState();
    }

    private MCEFInstallationTransaction newInstallationTransaction() throws IOException {
        return new MCEFInstallationTransaction(getLibrariesDirectory(), platform, javaCefCommitHash, failure -> LOGGER.warn("Could not completely clean JCEF installer residue; cleanup will retry later.", failure));
    }

    private record PreparedDownload(Path existingInstallation, String archiveDigest, String checksumSource) {
        static PreparedDownload existing(Path installation) {
            return new PreparedDownload(installation, null, null);
        }

        static PreparedDownload staged(String archiveDigest, String checksumSource) {
            return new PreparedDownload(null, archiveDigest, checksumSource);
        }
    }

    private InstallationResult performInstallOrUpdate(MCEFInstallationTransaction transaction, boolean skipDownload, boolean deleteArchive) throws IOException {
        try {
            transaction.recover();
            cleanupCompatibilityArchives(transaction);
            if (skipDownload) {
                Path localInstallation = transaction.findUsableInstallation(null, allowedChecksumSources);
                if (localInstallation == null) {
                    throw new IOException("skip-download=true but no complete local JCEF installation checksum-verified by a currently permitted source is available");
                }
                return completeInstallation(transaction, localInstallation, false, deleteArchive);
            }

            // A generation verified by a checksum source still allowed by the current mirror policy
            // is bound to the exact requested commit and a freshly verified runtime manifest. It
            // does not need an availability-dependent checksum request on every startup.
            Path checksumVerifiedInstallation = transaction.findUsableInstallation(null, allowedChecksumSources);
            if (checksumVerifiedInstallation != null) {
                return completeInstallation(transaction, checksumVerifiedInstallation, false, deleteArchive);
            }

            PreparedDownload prepared = prepareDownloadFromMirrors(transaction, deleteArchive);
            if (prepared.existingInstallation() != null) {
                return completeInstallation(transaction, prepared.existingInstallation(), false, deleteArchive);
            }
            // Promotion mutates durable generation state and therefore deliberately sits outside
            // the mirror retry loop. Recovery, not another download attempt, owns any failure here.
            Path installed = transaction.promote(prepared.archiveDigest(), prepared.checksumSource());
            return completeInstallation(transaction, installed, true, deleteArchive);
        } catch (IOException | RuntimeException failure) {
            abortPreparedTransaction(transaction, failure);
            throw failure;
        }
    }

    private PreparedDownload prepareDownloadFromMirrors(MCEFInstallationTransaction transaction, boolean deleteArchive) throws IOException {
        List<MCEFDownloadMirror> mirrors = resolveMirrorCandidates();
        Throwable lastFailure = null;
        transaction.prepareFresh();
        for (int index = 0; index < mirrors.size(); index++) {
            MCEFDownloadMirror mirror = mirrors.get(index);
            try {
                return prepareDownloadFromMirror(transaction, mirror, deleteArchive);
            } catch (IOException | RuntimeException mirrorFailure) {
                lastFailure = mirrorFailure;
                LOGGER.warn("JCEF release validation failed for {}; trying the next permitted mirror if available", mirror.safeLogIdentity());
                if (index + 1 < mirrors.size()) {
                    resetPreparedTransactionForRetry(transaction);
                }
            }
        }
        if (lastFailure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        throw new IOException("Failed to obtain and validate a JCEF release from the permitted mirror set");
    }

    private PreparedDownload prepareDownloadFromMirror(MCEFInstallationTransaction transaction, MCEFDownloadMirror mirror, boolean deleteArchive) throws IOException {
        String expectedChecksum = downloadChecksumFromMirror(transaction, mirror);

        if (expectedChecksum != null) {
            Path matchingInstallation = transaction.findUsableInstallation(expectedChecksum, false, false);
            if (matchingInstallation != null) {
                matchingInstallation = transaction.markChecksumVerified(matchingInstallation, expectedChecksum, checksumSource(mirror));
                transaction.abortPrepared();
                return PreparedDownload.existing(matchingInstallation);
            }
        }

        MCEFDownloadListener.INSTANCE.setTask("Downloading Chromium Embedded Framework");
        downloadArtifact(mirror, archiveAssetName(), transaction.candidateArchive().toFile(), downloadPolicy.maxArchiveBytes());
        try (MCEFVerifiedArchiveSource archive = MCEFVerifiedArchiveSource.open(transaction.candidateArchive(), downloadPolicy.maxArchiveBytes())) {
            String actualDigest = validateJavaCefBuildChecksum(archive, expectedChecksum);
            extractArchive(archive, actualDigest, transaction.extractionDirectory().toFile());
            transaction.validatePreparedInstallation();
            if (!deleteArchive) {
                transaction.retainCandidateArchive(archive, actualDigest);
            }
            return PreparedDownload.staged(actualDigest, expectedChecksum == null ? null : checksumSource(mirror));
        }
    }

    private String downloadChecksumFromMirror(MCEFInstallationTransaction transaction, MCEFDownloadMirror mirror) throws IOException {
        try {
            MCEFDownloadListener.INSTANCE.setTask("Downloading Checksum");
            downloadArtifact(mirror, checksumAssetName(), transaction.candidateChecksum().toFile(), downloadPolicy.maxChecksumBytes());
            String expectedChecksum = readChecksum(transaction.candidateChecksum().toFile(), downloadPolicy.enforceChecksums());
            if (expectedChecksum == null && downloadPolicy.enforceChecksums()) {
                throw new IOException("Missing or invalid JCEF checksum");
            }
            if (expectedChecksum == null) {
                transaction.discardCandidateChecksum();
            }
            return expectedChecksum;
        } catch (IOException checksumFailure) {
            if (downloadPolicy.enforceChecksums()) {
                throw checksumFailure;
            }
            LOGGER.warn("A valid JCEF checksum was unavailable from {}; continuing with an explicitly unchecked archive from the same mirror", mirror.safeLogIdentity());
            transaction.discardCandidateChecksum();
            return null;
        }
    }

    private CompatibilityArchiveDownload downloadCompatibilityArchive(MCEFInstallationTransaction transaction, CompatibilityRelease release) throws IOException {
        List<MCEFDownloadMirror> mirrors = resolveMirrorCandidates();
        CompatibilityRelease selectedRelease = release == null ? new CompatibilityRelease(mirrors.getFirst(), 0, null) : release;
        try {
            MCEFDownloadListener.INSTANCE.setTask("Downloading Chromium Embedded Framework");
            downloadArtifact(selectedRelease.mirror(), archiveAssetName(), transaction.candidateArchive().toFile(), downloadPolicy.maxArchiveBytes());
            try (MCEFVerifiedArchiveSource archive = MCEFVerifiedArchiveSource.open(transaction.candidateArchive(), downloadPolicy.maxArchiveBytes())) {
                String archiveDigest = validateJavaCefBuildChecksum(archive, selectedRelease.expectedChecksum());
                return new CompatibilityArchiveDownload(selectedRelease, archiveDigest, archive.size());
            }
        } catch (IOException | RuntimeException firstFailure) {
            LOGGER.warn("The deprecated JCEF phase API rejected an archive from {}; trying the next permitted mirror if available", selectedRelease.mirror().safeLogIdentity());
            return downloadCompatibilityPairFromLaterMirror(transaction, selectedRelease.mirrorIndex() + 1, firstFailure);
        }
    }

    private CompatibilityArchiveDownload downloadCompatibilityPairFromLaterMirror(MCEFInstallationTransaction transaction, int firstMirrorIndex, Throwable firstFailure) throws IOException {
        List<MCEFDownloadMirror> mirrors = resolveMirrorCandidates();
        Throwable lastFailure = firstFailure;
        for (int index = Math.max(0, firstMirrorIndex); index < mirrors.size(); index++) {
            MCEFDownloadMirror mirror = mirrors.get(index);
            resetPreparedTransactionForRetry(transaction);
            try {
                String expectedChecksum = downloadChecksumFromMirror(transaction, mirror);
                MCEFDownloadListener.INSTANCE.setTask("Downloading Chromium Embedded Framework");
                downloadArtifact(mirror, archiveAssetName(), transaction.candidateArchive().toFile(), downloadPolicy.maxArchiveBytes());
                try (MCEFVerifiedArchiveSource archive = MCEFVerifiedArchiveSource.open(transaction.candidateArchive(), downloadPolicy.maxArchiveBytes())) {
                    String archiveDigest = validateJavaCefBuildChecksum(archive, expectedChecksum);
                    return new CompatibilityArchiveDownload(new CompatibilityRelease(mirror, index, expectedChecksum), archiveDigest, archive.size());
                }
            } catch (IOException | RuntimeException mirrorFailure) {
                collectFailure(mirrorFailure, lastFailure);
                lastFailure = mirrorFailure;
                LOGGER.warn("The deprecated JCEF phase API could not obtain a complete release pair from {}; trying the next permitted mirror if available", mirror.safeLogIdentity());
            }
        }
        throw asIOExceptionOrThrowRuntime(lastFailure);
    }

    private PreparedDownload prepareCompatibilityCandidate(MCEFInstallationTransaction transaction, CompatibilityArchiveHandle phaseArchive, CompatibilityRelease release, boolean deleteArchive) throws IOException {
        if (phaseArchive == null) {
            transaction.stageRetainedArchiveIfMissing(downloadPolicy.maxArchiveBytes());
        } else {
            transaction.stageCompatibilityArchive(phaseArchive.path(), downloadPolicy.maxArchiveBytes());
        }
        String expectedChecksum = release == null ? null : release.expectedChecksum();
        if (expectedChecksum == null && downloadPolicy.enforceChecksums()) {
            throw new IOException("The deprecated phase API requires downloadJavaCefChecksum() before extracting a retained JCEF archive when checksum enforcement is enabled");
        }
        try (MCEFVerifiedArchiveSource archive = MCEFVerifiedArchiveSource.open(transaction.candidateArchive(), downloadPolicy.maxArchiveBytes())) {
            if (phaseArchive != null && archive.size() != phaseArchive.archiveSize) {
                throw new IOException("Deprecated JCEF phase archive size changed between download and extraction");
            }
            String actualDigest = validateJavaCefBuildChecksum(archive, expectedChecksum);
            if (phaseArchive != null && !phaseArchive.archiveDigest.equals(actualDigest)) {
                throw new IOException("Deprecated JCEF phase archive changed between download and extraction");
            }
            extractArchive(archive, actualDigest, transaction.extractionDirectory().toFile());
            transaction.validatePreparedInstallation();
            if (!deleteArchive) {
                transaction.retainCandidateArchive(archive, actualDigest);
            }
            return PreparedDownload.staged(actualDigest, expectedChecksum == null ? null : checksumSource(release.mirror()));
        }
    }

    private PreparedDownload prepareCompatibilityDownloadFromLaterMirror(MCEFInstallationTransaction transaction, int firstMirrorIndex, boolean deleteArchive, Throwable firstFailure) throws IOException {
        List<MCEFDownloadMirror> mirrors = resolveMirrorCandidates();
        Throwable lastFailure = firstFailure;
        for (int index = Math.max(0, firstMirrorIndex); index < mirrors.size(); index++) {
            MCEFDownloadMirror mirror = mirrors.get(index);
            resetPreparedTransactionForRetry(transaction);
            try {
                return prepareDownloadFromMirror(transaction, mirror, deleteArchive);
            } catch (IOException | RuntimeException mirrorFailure) {
                collectFailure(mirrorFailure, lastFailure);
                lastFailure = mirrorFailure;
                LOGGER.warn("The deprecated JCEF phase API rejected a release pair from {}; trying the next permitted mirror if available", mirror.safeLogIdentity());
            }
        }
        throw asIOExceptionOrThrowRuntime(lastFailure);
    }

    private static IOException asIOExceptionOrThrowRuntime(Throwable failure) {
        if (failure instanceof IOException ioFailure) {
            return ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        return new IOException("Unexpected JCEF mirror validation failure", failure);
    }

    private static Throwable collectFailure(Throwable current, Throwable additional) {
        if (current == null) {
            return additional;
        }
        if (additional == null || current == additional) {
            return current;
        }
        try {
            current.addSuppressed(additional);
        } catch (Throwable ignored) {
            // Diagnostic attachment must never prevent transaction abort or lock release.
        }
        return current;
    }

    private InstallationResult completeInstallation(MCEFInstallationTransaction transaction, Path installation, boolean downloaded, boolean deleteArchive) {
        if (deleteArchive) {
            transaction.deleteRetainedArchiveBestEffort();
        }
        return new InstallationResult(installation, downloaded);
    }

    private static void cleanupCompatibilityArchives(MCEFInstallationTransaction transaction) {
        Set<Path> activeArchives = ConcurrentHashMap.newKeySet();
        ACTIVE_COMPATIBILITY_ARCHIVES.forEach((path, reference) -> {
            if (reference.get() == null) {
                ACTIVE_COMPATIBILITY_ARCHIVES.remove(path, reference);
            } else {
                activeArchives.add(path);
            }
        });
        transaction.cleanupCompatibilityArchivesBestEffort(Set.copyOf(activeArchives));
    }

    private void discardCompatibilityArchive() throws IOException {
        CompatibilityArchiveHandle archive = compatibilityArchive;
        compatibilityArchive = null;
        if (archive != null) {
            archive.delete();
        }
    }

    private void discardCompatibilityState() throws IOException {
        compatibilityRelease = null;
        discardCompatibilityArchive();
    }

    private void discardCompatibilityState(Throwable failure) {
        compatibilityRelease = null;
        CompatibilityArchiveHandle archive = compatibilityArchive;
        compatibilityArchive = null;
        discardCompatibilityArchive(archive, failure);
    }

    private void discardCompatibilityStateBestEffort() {
        compatibilityRelease = null;
        CompatibilityArchiveHandle archive = compatibilityArchive;
        compatibilityArchive = null;
        if (archive == null) {
            return;
        }
        try {
            archive.delete();
        } catch (IOException cleanupFailure) {
            LOGGER.warn("Could not delete a completed deprecated JCEF phase archive; a later installer phase will retry cleanup.", cleanupFailure);
        }
    }

    private static void discardCompatibilityArchive(CompatibilityArchiveHandle archive, Throwable failure) {
        if (archive == null) {
            return;
        }
        try {
            archive.delete();
        } catch (Throwable cleanupFailure) {
            collectFailure(failure, cleanupFailure);
        }
    }

    private static void deleteCompatibilityArchivePath(Path archive) throws IOException {
        Path parent = archive.getParent();
        if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe deprecated JCEF phase archive cleanup directory: " + parent);
        }
        if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(archive)) {
            throw new IOException("Unsafe deprecated JCEF phase archive cleanup target: " + archive);
        }
        Files.deleteIfExists(archive);
    }

    private Path getLibrariesDirectory() throws IOException {
        if (librariesDirectoryOverride != null) {
            return librariesDirectoryOverride;
        }
        String configuredPath = System.getProperty("mcef.libraries.path");
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IOException("System property mcef.libraries.path is missing");
        }
        return Path.of(configuredPath).toAbsolutePath().normalize();
    }

    private void downloadArtifact(MCEFDownloadMirror mirror, String assetName, File outputFile, long maxBytes) throws IOException {
        Path output = outputFile.toPath();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace pre-existing JCEF download target: " + output);
        }
        try {
            URI assetUri = mirror.assetUri(javaCefReleaseTag(), assetName);
            if (artifactDownloader != null) {
                artifactDownloader.download(assetUri.toASCIIString(), outputFile, maxBytes);
            } else {
                downloadFile(assetUri, mirror.safeLogIdentity(), outputFile, maxBytes);
            }
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("JCEF download did not create a safe regular file: " + output);
            }
            long size = Files.size(output);
            if (size <= 0L || size > maxBytes) {
                throw new IOException("Downloaded JCEF artifact size is outside the configured limit");
            }
        } catch (IOException | RuntimeException failure) {
            try {
                if (Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
                    Files.deleteIfExists(output);
                }
            } catch (IOException cleanupFailure) {
                collectFailure(failure, cleanupFailure);
            }
            throw failure;
        }
    }

    private void extractArchive(MCEFVerifiedArchiveSource archive, String expectedDigest, File outputDirectory) throws IOException {
        if (archiveExtractor != null) {
            MCEFDownloadListener.INSTANCE.setTask("Extracting");
            archive.verifiedPass(expectedDigest, input -> archiveExtractor.extract(input, outputDirectory));
            return;
        }
        extractTarGz(archive, expectedDigest, outputDirectory);
    }

    private static void abortPreparedTransaction(MCEFInstallationTransaction transaction, Throwable failure) {
        try {
            transaction.abortPrepared();
        } catch (Throwable cleanupFailure) {
            collectFailure(failure, cleanupFailure);
        }
    }

    private static void resetPreparedTransactionForRetry(MCEFInstallationTransaction transaction) throws IOException {
        transaction.abortPrepared();
        transaction.prepareFresh();
    }

    private void downloadFile(URI assetUri, String mirrorIdentity, File outputFile, long maxBytes) throws IOException {
        Path output = outputFile.toPath().toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe JCEF download directory: " + parent);
        }
        Path tempOutput = parent.resolve("." + output.getFileName() + ".part-" + java.util.UUID.randomUUID());
        long readBytes = 0L;
        HttpURLConnection urlConnection = null;

        try {
            LOGGER.info("Downloading JCEF asset {} from {}", output.getFileName(), mirrorIdentity);

            URL url = assetUri.toURL();
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(downloadPolicy.connectTimeoutMs());
            urlConnection.setReadTimeout(downloadPolicy.readTimeoutMs());
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.connect();

            int responseCode = urlConnection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Unexpected HTTP status " + responseCode);
            }
            if (!"https".equalsIgnoreCase(urlConnection.getURL().getProtocol())) {
                throw new IOException("JCEF download redirected outside HTTPS");
            }

            long fileSize = urlConnection.getContentLengthLong();
            if (fileSize > maxBytes) {
                throw new IOException("Remote file size exceeds configured limit");
            }

            try (BufferedInputStream inputStream = new BufferedInputStream(urlConnection.getInputStream(), DOWNLOAD_BUFFER_SIZE_BYTES); FileChannel outputChannel = FileChannel.open(tempOutput, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                BufferedOutputStream outputStream = new BufferedOutputStream(Channels.newOutputStream(outputChannel), DOWNLOAD_BUFFER_SIZE_BYTES);
                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE_BYTES];
                int count;
                while ((count = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, count);
                    readBytes += count;
                    if (readBytes > maxBytes) {
                        throw new IOException("Downloaded file size exceeded configured limit");
                    }
                    long progressTotal = fileSize > 0 ? fileSize : maxBytes;
                    if (progressTotal > 0) {
                        float percentComplete = Math.min(0.99f, (float) readBytes / progressTotal);
                        MCEFDownloadListener.INSTANCE.setProgress(percentComplete);
                    }
                }
                outputStream.flush();
                outputChannel.force(true);
            }

            try {
                Files.move(tempOutput, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tempOutput, output);
            }
            MCEFDownloadListener.INSTANCE.setProgress(1.0f);
        } catch (IOException failure) {
            IOException sanitizedFailure = new IOException("Failed to download a JCEF asset from " + mirrorIdentity + " (" + failure.getClass().getSimpleName() + ")");
            try {
                Files.deleteIfExists(tempOutput);
            } catch (IOException cleanupException) {
                collectFailure(sanitizedFailure, new IOException("Temporary JCEF download cleanup failed"));
            }
            throw sanitizedFailure;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private String validateJavaCefBuildChecksum(MCEFVerifiedArchiveSource archive, String expectedChecksum) throws IOException {
        String actualChecksum = archive.calculateDigest();
        if (expectedChecksum != null && !expectedChecksum.equals(actualChecksum)) {
            throw new IOException("Checksum mismatch for downloaded JCEF archive");
        }
        return actualChecksum;
    }

    private String readChecksum(File checksumFile, boolean strict) throws IOException {
        Path path = checksumFile.toPath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String content;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long checksumSize = channel.size();
            if (checksumSize <= 0 || checksumSize > downloadPolicy.maxChecksumBytes()) {
                if (strict) {
                    throw new IOException("Checksum file size out of bounds: " + checksumFile.getName());
                }
                LOGGER.warn("Checksum file size out of bounds: {}", checksumFile.getName());
                return null;
            }
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(checksumSize));
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Checksum file changed while it was being read: " + checksumFile.getName());
                }
            }
            if (channel.size() != checksumSize) {
                throw new IOException("Checksum file changed while it was being read: " + checksumFile.getName());
            }
            content = StandardCharsets.UTF_8.decode(buffer.flip()).toString();
        }
        String checksum = extractSha256Token(content);
        if (checksum == null) {
            if (strict) {
                throw new IOException("Checksum file does not contain a valid SHA-256 digest: " + checksumFile.getName());
            }
            return null;
        }
        return checksum;
    }

    private String extractSha256Token(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.strip();
        Matcher gnuMatcher = GNU_SHA256_PATTERN.matcher(trimmed);
        if (gnuMatcher.matches()) {
            String assetName = gnuMatcher.group(2);
            return assetName == null || checksumAssetMatches(assetName) ? gnuMatcher.group(1).toLowerCase(Locale.ROOT) : null;
        }
        Matcher bsdMatcher = BSD_SHA256_PATTERN.matcher(trimmed);
        if (bsdMatcher.matches() && checksumAssetMatches(bsdMatcher.group(1))) {
            return bsdMatcher.group(2).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private boolean checksumAssetMatches(String assetName) {
        String normalizedName = assetName.strip().replace('\\', '/');
        int finalSeparator = normalizedName.lastIndexOf('/');
        String baseName = finalSeparator < 0 ? normalizedName : normalizedName.substring(finalSeparator + 1);
        return baseName.equalsIgnoreCase(platform.getNormalizedName() + ".tar.gz");
    }

    private String javaCefReleaseTag() {
        return JAVA_CEF_RELEASE_TAG_PREFIX + javaCefCommitHash;
    }

    private String archiveAssetName() {
        return platform.getNormalizedName() + ".tar.gz";
    }

    private String checksumAssetName() {
        return archiveAssetName() + ".sha256";
    }

    private URI archiveUri(MCEFDownloadMirror mirror) {
        return mirror.assetUri(javaCefReleaseTag(), archiveAssetName());
    }

    private URI checksumUri(MCEFDownloadMirror mirror) {
        return mirror.assetUri(javaCefReleaseTag(), checksumAssetName());
    }

    private String checksumSource(MCEFDownloadMirror mirror) {
        return OFFICIAL_DOWNLOAD_MIRROR.externalForm().equals(mirror.externalForm()) ? MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE : mirror.checksumSourceId();
    }

    private List<MCEFDownloadMirror> resolveMirrorCandidates() {
        List<MCEFDownloadMirror> orderedMirrors = new ArrayList<>();

        switch (downloadPolicy.mirrorPolicy()) {
            case OFFICIAL_ONLY -> orderedMirrors.add(OFFICIAL_DOWNLOAD_MIRROR);
            case PREFER_CONFIGURED -> {
                if (configuredMirror != null) {
                    orderedMirrors.add(configuredMirror);
                }
                if (configuredMirror == null || !configuredMirror.externalForm().equals(OFFICIAL_DOWNLOAD_MIRROR.externalForm())) {
                    orderedMirrors.add(OFFICIAL_DOWNLOAD_MIRROR);
                }
            }
            case CONFIGURED_ONLY -> {
                if (configuredMirror == null) {
                    throw new IllegalStateException("Configured mirror is invalid for CONFIGURED_ONLY policy");
                }
                orderedMirrors.add(configuredMirror);
            }
        }
        return List.copyOf(orderedMirrors);
    }

    private static MCEFDownloadMirror resolveConfiguredMirror(String host, MirrorPolicy policy) {
        if (policy == MirrorPolicy.OFFICIAL_ONLY) {
            return null;
        }
        if (host == null || host.isBlank()) {
            if (policy == MirrorPolicy.CONFIGURED_ONLY) {
                throw new IllegalArgumentException("CONFIGURED_ONLY requires a valid JCEF mirror");
            }
            return null;
        }
        String trimmed = host.trim();
        String normalized = normalizeOfficialMirror(trimmed);
        if (!normalized.equals(trimmed)) {
            LOGGER.warn("Migrating the former default JCEF download mirror to the current official mirror");
        }
        try {
            return MCEFDownloadMirror.parse(normalized);
        } catch (IllegalArgumentException failure) {
            if (policy == MirrorPolicy.CONFIGURED_ONLY) {
                throw new IllegalArgumentException("CONFIGURED_ONLY requires a valid JCEF mirror");
            }
            LOGGER.warn("Ignoring an invalid configured JCEF mirror and using the official mirror");
            return null;
        }
    }

    static String normalizeOfficialMirror(String mirror) {
        if (mirror == null) {
            return null;
        }
        String normalized = stripTrailingSlash(mirror.trim());
        if (stripTrailingSlash(PREVIOUS_OFFICIAL_MIRROR).equalsIgnoreCase(normalized) || stripTrailingSlash(LEGACY_OFFICIAL_MIRROR).equalsIgnoreCase(normalized)) {
            return OFFICIAL_MIRROR;
        }
        return normalized;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    private void extractTarGz(MCEFVerifiedArchiveSource archive, String expectedDigest, File outputDirectory) throws IOException {
        MCEFDownloadListener.INSTANCE.setTask("Extracting");
        MCEFSecureArchiveExtractor.extract(archive, expectedDigest, outputDirectory, platform, downloadPolicy, MCEFDownloadListener.INSTANCE::setProgress);
    }
}
