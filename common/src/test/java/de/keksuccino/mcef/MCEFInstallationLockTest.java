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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MCEFInstallationLockTest extends MCEFInstallerTestBase {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameJvmContenderCannotEnterUntilWholeSessionCloses() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("lock");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader firstDownloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("first"));
        MCEFDownloader secondDownloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("second"));
        MCEFDownloader.InstallationSession firstSession = firstDownloader.openInstallationSession();
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> contender = executor.submit(() -> {
                attempting.countDown();
                try (MCEFDownloader.InstallationSession ignored = secondDownloader.openInstallationSession()) {
                    acquired.countDown();
                }
                return null;
            });

            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> contender.get(200, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-transactions")));

            firstSession.close();
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            getWithoutWrapping(contender);
        } finally {
            firstSession.close();
        }
    }

    @Test
    void registryRetainsOneEntryAcrossOwnerAndWaitersThenRemovesIt() throws Exception {
        int baselineEntries = MCEFInstallationTransaction.jvmLockRegistrySize();
        Path lockFile = installerLockFile();
        MCEFInstallationTransaction owner = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {});
        CountDownLatch waiterAcquired = new CountDownLatch(1);
        CountDownLatch releaseWaiter = new CountDownLatch(1);
        CountDownLatch thirdAcquired = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> waiter = executor.submit(() -> {
                try (MCEFInstallationTransaction ignored = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
                    waiterAcquired.countDown();
                    assertTrue(releaseWaiter.await(5, TimeUnit.SECONDS));
                }
                return null;
            });
            awaitReferenceCount(lockFile, 2);

            owner.close();
            assertTrue(waiterAcquired.await(5, TimeUnit.SECONDS));
            assertEquals(1, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));

            Future<?> third = executor.submit(() -> {
                try (MCEFInstallationTransaction ignored = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
                    thirdAcquired.countDown();
                }
                return null;
            });
            awaitReferenceCount(lockFile, 2);
            assertThrows(TimeoutException.class, () -> third.get(200, TimeUnit.MILLISECONDS));

            releaseWaiter.countDown();
            getWithoutWrapping(waiter);
            assertTrue(thirdAcquired.await(5, TimeUnit.SECONDS));
            getWithoutWrapping(third);
        } finally {
            releaseWaiter.countDown();
            owner.close();
        }

        assertEquals(0, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));
        assertEquals(baselineEntries, MCEFInstallationTransaction.jvmLockRegistrySize());
    }

    @Test
    void failedHighCardinalityAcquisitionsDoNotRetainRegistryEntries() throws Exception {
        int baselineEntries = MCEFInstallationTransaction.jvmLockRegistrySize();
        for (int index = 0; index < 128; index++) {
            Path libraries = temporaryDirectory.resolve("failed-" + index);
            Files.createDirectory(libraries);
            Path outside = libraries.resolve("outside.txt");
            Files.writeString(outside, "unchanged");
            Files.createSymbolicLink(libraries.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install.lock"), outside);

            assertThrows(IOException.class, () -> new MCEFInstallationTransaction(libraries, PLATFORM, COMMIT_A, failure -> {}));
            assertEquals(baselineEntries, MCEFInstallationTransaction.jvmLockRegistrySize());
        }
    }

    @Test
    void overlappingFileLockFailureReleasesItsRegistryReference() throws Exception {
        int baselineEntries = MCEFInstallationTransaction.jvmLockRegistrySize();
        Path lockFile = installerLockFile();
        Files.createFile(lockFile);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            assertThrows(IOException.class, () -> new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {}));
            assertEquals(0, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));
            assertEquals(baselineEntries, MCEFInstallationTransaction.jvmLockRegistrySize());
        }

        try (MCEFInstallationTransaction ignored = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertEquals(1, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));
        }
        assertEquals(baselineEntries, MCEFInstallationTransaction.jvmLockRegistrySize());
    }

    @Test
    void reentrantConstructionFailureKeepsTheOwningAcquisitionRegisteredAndLocked() throws Exception {
        int baselineEntries = MCEFInstallationTransaction.jvmLockRegistrySize();
        Path lockFile = installerLockFile();
        try (MCEFInstallationTransaction owner = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertEquals(1, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));

            assertThrows(IOException.class, () -> new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {}));

            assertEquals(1, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));
            owner.prepareFresh();
            owner.abortPrepared();
        }
        assertEquals(baselineEntries, MCEFInstallationTransaction.jvmLockRegistrySize());
    }

    @Test
    void lockFileOpenFailureReleasesItsRegistryReference() throws Exception {
        int baselineEntries = MCEFInstallationTransaction.jvmLockRegistrySize();
        Path lockFile = installerLockFile();
        Files.createFile(lockFile);
        assumeTrue(Files.getFileStore(lockFile).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(lockFile);
        try {
            Files.setPosixFilePermissions(lockFile, Set.of(PosixFilePermission.OWNER_READ));
            assumeFalse(Files.isWritable(lockFile));

            assertThrows(IOException.class, () -> new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {}));
            assertEquals(0, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));
            assertEquals(baselineEntries, MCEFInstallationTransaction.jvmLockRegistrySize());
        } finally {
            Files.setPosixFilePermissions(lockFile, originalPermissions);
        }
    }

    @Test
    void repeatedCloseDoesNotUnlockOrRemoveAnotherAcquisition() throws Exception {
        int baselineEntries = MCEFInstallationTransaction.jvmLockRegistrySize();
        Path lockFile = installerLockFile();
        MCEFInstallationTransaction first = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {});

        first.close();
        first.close();

        assertEquals(0, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));
        assertEquals(baselineEntries, MCEFInstallationTransaction.jvmLockRegistrySize());
        try (MCEFInstallationTransaction second = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            first.close();
            assertEquals(1, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));
            assertTrue(second.lockFile().equals(lockFile));
        }
        assertEquals(baselineEntries, MCEFInstallationTransaction.jvmLockRegistrySize());
    }

    @Test
    void stableLockFileSurvivesTransactionAbortAndReuse() throws Exception {
        Path lockFile;
        try (MCEFInstallationTransaction first = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            lockFile = first.lockFile();
            first.recover();
            first.prepareFresh();
            first.abortPrepared();
            assertTrue(Files.isRegularFile(lockFile));
        }

        try (MCEFInstallationTransaction second = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertTrue(Files.isSameFile(lockFile, second.lockFile()));
        }
        assertTrue(Files.isRegularFile(lockFile));
    }

    @Test
    void uncheckedAbortFailureStillReleasesFileAndJvmLocks() throws Exception {
        IllegalStateException expected = new IllegalStateException("injected abort failure");
        MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, MCEFInstallationLockTest::moveForTest, failure -> {}, ignored -> { throw expected; });
        transaction.prepareFresh();

        assertSame(expected, assertThrows(IllegalStateException.class, transaction::close));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> reacquired = executor.submit(() -> {
                try (MCEFInstallationTransaction ignored = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
                    assertTrue(Files.isRegularFile(ignored.lockFile()));
                }
                return null;
            });
            getWithoutWrapping(reacquired);
        }
    }

    @Test
    void symbolicLinkCannotSubstituteForStableLockFile() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outside, "unchanged");
        Path lockFile = temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install.lock");
        Files.createSymbolicLink(lockFile, outside);

        assertThrows(java.io.IOException.class, () -> new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {}));
        assertTrue(Files.isSymbolicLink(lockFile));
        assertTrue("unchanged".equals(Files.readString(outside)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedPhaseAbortCanRunOnAnotherThreadBecauseNoPhaseRetainsTheLock() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("owner");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("owner"));
        assertFalse(downloader.downloadJavaCefChecksum());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> crossThreadAbort = executor.submit(() -> {
                downloader.abortJavaCefInstallation();
                return null;
            });
            getWithoutWrapping(crossThreadAbort);
        }

        try (MCEFDownloader.InstallationSession ignored = downloader.openInstallationSession()) {
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install.lock")));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void falseDeprecatedChecksumProbeReleasesTheOperatingSystemLockForAnotherJvm() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("process-lock-probe");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("process-lock-probe"));
        assertFalse(downloader.downloadJavaCefChecksum());
        Path lockFile = temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install.lock");

        Process process = new ProcessBuilder(javaExecutable(), "-cp", childProbeClasspath(), MCEFFileLockProbeMain.class.getName(), lockFile.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

        assertEquals(0, process.exitValue(), output);
        assertEquals("UNLOCKED", output);
    }

    private static void getWithoutWrapping(Future<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw failure;
        }
    }

    private static void awaitReferenceCount(Path lockFile, int expectedCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (MCEFInstallationTransaction.jvmLockReferenceCount(lockFile) != expectedCount && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expectedCount, MCEFInstallationTransaction.jvmLockReferenceCount(lockFile));
    }

    private Path installerLockFile() throws IOException {
        return temporaryDirectory.toRealPath().resolve("." + PLATFORM.getNormalizedName() + ".mcef-install.lock");
    }

    private static void moveForTest(Path source, Path target, boolean replaceExisting) throws java.io.IOException {
        if (replaceExisting) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, target);
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String childProbeClasspath() throws Exception {
        return Path.of(MCEFFileLockProbeMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
