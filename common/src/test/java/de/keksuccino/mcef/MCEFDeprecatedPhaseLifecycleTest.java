/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("deprecation")
class MCEFDeprecatedPhaseLifecycleTest extends MCEFInstallerTestBase {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @TempDir
    Path temporaryDirectory;

    @Test
    void falseChecksumProbeReturnsWithoutHoldingEitherInstallerLock() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("probe-only");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = downloader(archive, digest, "probe-only");

        assertFalse(downloader.downloadJavaCefChecksum());

        assertNoActiveTransactions();
        assertCanAcquireInstallerLock("after-probe");
        assertEquals(0L, compatibilityArchiveCount());
    }

    @Test
    void trueChecksumProbeReturnsWithoutHoldingEitherInstallerLock() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("probe-installed");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = downloader(archive, digest, "probe-installed");
        Path installed = downloader.installOrUpdate(false, true).installationDirectory();

        assertTrue(downloader.downloadJavaCefChecksum());

        assertEquals(installed, Path.of(System.getProperty("jcef.path")));
        assertNoActiveTransactions();
        assertCanAcquireInstallerLock("after-true-probe");
        assertEquals(0L, compatibilityArchiveCount());
    }

    @Test
    void abandonedBuildReleasesLocksAndCrossThreadAbortDeletesItsPrivateHandoff() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("abandoned-build");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = downloader(archive, digest, "abandoned-build");
        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();

        Path handoff = onlyCompatibilityArchive();
        assertArrayEquals(archive, Files.readAllBytes(handoff));
        assertPrivatePermissions(handoff);
        assertCanAcquireInstallerLock("while-build-is-abandoned");

        runOnNewThread("compatibility-abort", () -> {
            downloader.abortJavaCefInstallation();
            return null;
        });
        downloader.abortJavaCefInstallation();

        assertFalse(Files.exists(handoff, LinkOption.NOFOLLOW_LINKS));
        assertEquals(0L, compatibilityArchiveCount());
        assertNoActiveTransactions();
    }

    @Test
    void allCompatibilityPhasesCanContinueOnDifferentThreads() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("cross-thread");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = downloader(archive, digest, "cross-thread");

        assertFalse(runOnNewThread("compatibility-checksum", downloader::downloadJavaCefChecksum));
        runOnNewThread("compatibility-download", () -> {
            downloader.downloadJavaCefBuild();
            return null;
        });
        runOnNewThread("compatibility-extract", () -> {
            downloader.extractJavaCefBuild(true);
            return null;
        });

        assertEquals("cross-thread", MCEFInstallerTestSupport.readVersion(Path.of(System.getProperty("jcef.path"))));
        assertEquals(0L, compatibilityArchiveCount());
        assertNoActiveTransactions();
    }

    @Test
    void compatibilityExtractionUsesOneArchiveIdentityAcrossAbaPathReplacementAndRetention() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("identity-stable-compatibility");
        byte[] replacement = MCEFInstallerTestSupport.archiveBytes("replacement-compatibility");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.abaPathSwapExtractor(temporaryDirectory, archive, replacement, "identity-stable-compatibility"));
        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();

        downloader.extractJavaCefBuild(false);

        assertEquals("identity-stable-compatibility", MCEFInstallerTestSupport.readVersion(Path.of(System.getProperty("jcef.path"))));
        assertArrayEquals(archive, Files.readAllBytes(temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz")));
        assertEquals(0L, compatibilityArchiveCount());
        assertNoActiveTransactions();
    }

    @Test
    void extractionFailureReleasesLocksAndDeletesTheHandoff() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("failed-extract");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, (archiveFile, outputDirectory) -> { throw new IOException("injected extraction failure"); });
        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();

        IOException failure = assertThrows(IOException.class, () -> downloader.extractJavaCefBuild(true));

        assertTrue(failure.getMessage().contains("injected extraction failure"));
        assertEquals(0L, compatibilityArchiveCount());
        assertNoActiveTransactions();
        assertCanAcquireInstallerLock("after-failure");
    }

    @Test
    void failedNewProbeCannotReuseThePreviousHandoffOrChecksumSource() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("failed-new-probe");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        AtomicBoolean failChecksum = new AtomicBoolean();
        MCEFDownloader.ArtifactDownloader downloads = (assetUrl, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                if (failChecksum.get()) {
                    throw new IOException("injected replacement probe failure");
                }
                MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), digest);
            } else {
                Files.write(outputFile.toPath(), archive);
            }
        };
        MCEFDownloader downloader = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, downloads, MCEFInstallerTestSupport.extractor("failed-new-probe"));
        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();
        Path previousHandoff = onlyCompatibilityArchive();
        failChecksum.set(true);

        IOException failure = assertThrows(IOException.class, downloader::downloadJavaCefChecksum);

        assertTrue(failure.getMessage().contains("Failed to obtain a valid JCEF checksum"));
        assertFalse(Files.exists(previousHandoff, LinkOption.NOFOLLOW_LINKS));
        assertEquals(0L, compatibilityArchiveCount());
        assertNoActiveTransactions();
        assertThrows(IOException.class, () -> downloader.extractJavaCefBuild(true));
    }

    @Test
    void handoffMutationCannotReachExtractionOrPromotion() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("bound-archive");
        byte[] replacement = MCEFInstallerTestSupport.archiveBytes("other-archive");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        AtomicBoolean extractorCalled = new AtomicBoolean();
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, (archiveFile, outputDirectory) -> extractorCalled.set(true));
        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();
        Files.write(onlyCompatibilityArchive(), replacement);

        IOException failure = assertThrows(IOException.class, () -> downloader.extractJavaCefBuild(true));

        assertTrue(failure.getMessage().contains("Checksum mismatch") || failure.getMessage().contains("changed between download and extraction"));
        assertFalse(extractorCalled.get());
        assertFalse(Files.exists(selectorFile()));
        assertEquals(0L, compatibilityArchiveCount());
        assertNoActiveTransactions();
    }

    @Test
    void handoffSymlinkSwapCannotReadOrDeleteTheLinkTarget() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("symlink-handoff");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        AtomicBoolean extractorCalled = new AtomicBoolean();
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, (archiveFile, outputDirectory) -> extractorCalled.set(true));
        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();
        Path handoff = onlyCompatibilityArchive();
        Path outside = temporaryDirectory.resolve("outside-archive.tar.gz");
        Files.write(outside, archive);
        Files.delete(handoff);
        Files.createSymbolicLink(handoff, outside);

        assertThrows(IOException.class, () -> downloader.extractJavaCefBuild(true));

        assertFalse(extractorCalled.get());
        assertArrayEquals(archive, Files.readAllBytes(outside));
        assertFalse(Files.exists(handoff, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(selectorFile()));
        assertNoActiveTransactions();
    }

    @Test
    void interleavedDownloaderInstancesCannotOverwriteOrConsumeEachOthersHandoffs() throws Exception {
        byte[] firstArchive = MCEFInstallerTestSupport.archiveBytes("interleaved-first");
        byte[] secondArchive = MCEFInstallerTestSupport.archiveBytes("interleaved-second");
        MCEFDownloader first = downloader(firstArchive, MCEFInstallerTestSupport.sha256(firstArchive), "interleaved-first");
        MCEFDownloader second = downloader(secondArchive, MCEFInstallerTestSupport.sha256(secondArchive), "interleaved-second");
        assertFalse(first.downloadJavaCefChecksum());
        assertFalse(second.downloadJavaCefChecksum());

        FutureTask<Void> firstBuild = startThread("first-compatibility-download", () -> {
            first.downloadJavaCefBuild();
            return null;
        });
        FutureTask<Void> secondBuild = startThread("second-compatibility-download", () -> {
            second.downloadJavaCefBuild();
            return null;
        });
        firstBuild.get(5, TimeUnit.SECONDS);
        secondBuild.get(5, TimeUnit.SECONDS);

        assertEquals(2L, compatibilityArchiveCount());
        first.extractJavaCefBuild(true);
        Path firstInstallation = Path.of(System.getProperty("jcef.path"));
        second.extractJavaCefBuild(true);
        Path secondInstallation = Path.of(System.getProperty("jcef.path"));

        assertNotEquals(firstInstallation, secondInstallation);
        assertEquals("interleaved-first", MCEFInstallerTestSupport.readVersion(firstInstallation));
        assertEquals("interleaved-second", MCEFInstallerTestSupport.readVersion(secondInstallation));
        assertEquals(0L, compatibilityArchiveCount());
        assertNoActiveTransactions();
    }

    @Test
    void nextPhaseBoundedlyRemovesAnUnregisteredSameProcessHandoff() throws Exception {
        Path directory = compatibilityArchivesDirectory();
        Files.createDirectories(directory);
        Path orphan = directory.resolve("handoff-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID() + ".tar.gz");
        Files.write(orphan, MCEFInstallerTestSupport.archiveBytes("orphan"));
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("cleanup");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = downloader(archive, digest, "cleanup");

        assertFalse(downloader.downloadJavaCefChecksum());

        assertFalse(Files.exists(orphan, LinkOption.NOFOLLOW_LINKS));
        assertEquals(0L, compatibilityArchiveCount());
        downloader.abortJavaCefInstallation();
    }

    @Test
    void compatibilityArchiveCapacityPreventsUnboundedFreshResidue() throws Exception {
        Path directory = compatibilityArchivesDirectory();
        Files.createDirectories(directory);
        for (int index = 0; index < MCEFInstallationTransaction.MAX_COMPATIBILITY_ARCHIVES_TO_SCAN; index++) {
            Files.writeString(directory.resolve("unrecognized-" + index), "residue");
        }
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("bounded-capacity");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = downloader(archive, digest, "bounded-capacity");
        assertFalse(downloader.downloadJavaCefChecksum());

        IOException failure = assertThrows(IOException.class, downloader::downloadJavaCefBuild);

        assertTrue(failure.getMessage().contains("Too many pending"));
        assertEquals(MCEFInstallationTransaction.MAX_COMPATIBILITY_ARCHIVES_TO_SCAN, compatibilityArchiveCount());
        assertNoActiveTransactions();
        assertCanAcquireInstallerLock("after-capacity-failure");
    }

    private MCEFDownloader downloader(byte[] archive, String digest, String version) {
        return MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor(version));
    }

    private void assertCanAcquireInstallerLock(String threadName) throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes(threadName);
        MCEFDownloader contender = downloader(archive, MCEFInstallerTestSupport.sha256(archive), threadName);
        runOnNewThread(threadName, () -> {
            try (MCEFDownloader.InstallationSession ignored = contender.openInstallationSession()) {
                assertTrue(Files.isRegularFile(temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install.lock")));
            }
            return null;
        });
    }

    private Path onlyCompatibilityArchive() throws IOException {
        try (var archives = Files.list(compatibilityArchivesDirectory())) {
            return archives.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).findFirst().orElseThrow();
        }
    }

    private long compatibilityArchiveCount() throws IOException {
        Path directory = compatibilityArchivesDirectory();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        try (var archives = Files.list(directory)) {
            return archives.count();
        }
    }

    private Path compatibilityArchivesDirectory() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-phase-archives");
    }

    private Path transactionsDirectory() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-transactions");
    }

    private Path selectorFile() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-current.properties");
    }

    private void assertNoActiveTransactions() throws IOException {
        Path transactions = transactionsDirectory();
        if (!Files.isDirectory(transactions, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var entries = Files.list(transactions)) {
            assertEquals(0L, entries.count());
        }
    }

    private void assertPrivatePermissions(Path handoff) throws IOException {
        PosixFileAttributeView directoryAttributes = Files.getFileAttributeView(handoff.getParent(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributeView fileAttributes = Files.getFileAttributeView(handoff, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (directoryAttributes != null && fileAttributes != null) {
            assertEquals(PRIVATE_DIRECTORY_PERMISSIONS, directoryAttributes.readAttributes().permissions());
            assertEquals(PRIVATE_FILE_PERMISSIONS, fileAttributes.readAttributes().permissions());
        }
    }

    private static <T> T runOnNewThread(String threadName, Callable<T> action) throws Exception {
        return startThread(threadName, action).get(5, TimeUnit.SECONDS);
    }

    private static <T> FutureTask<T> startThread(String threadName, Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        Thread.ofPlatform().name(threadName).start(task);
        return task;
    }
}
