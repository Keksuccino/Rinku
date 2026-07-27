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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_B;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFGenerationLeaseTest extends MCEFInstallerTestBase {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameJvmLeaseRetainsOldGenerationWhileNewSelectionIsPublished() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "same-jvm-a");
        InstalledGeneration second = install(temporaryDirectory, COMMIT_B, "same-jvm-b");

        assertNotEquals(first.path(), second.path());
        assertTrue(Files.isDirectory(first.path()));
        assertTrue(Files.isDirectory(second.path()));
        assertTrue(MCEFGenerationLeaseRegistry.isLeasedForTests(first.path()));
        assertTrue(MCEFGenerationLeaseRegistry.isLeasedForTests(second.path()));
        assertTrue(Files.readString(selectorFile(temporaryDirectory)).contains("generation=" + second.path().getFileName()));
    }

    @Test
    void unusedSupersededGenerationIsReclaimedAfterNewSelectionIsDurable() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "unused-a");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), true);

        InstalledGeneration second = install(temporaryDirectory, COMMIT_B, "unused-b");

        assertFalse(Files.exists(first.path()));
        assertTrue(Files.isDirectory(second.path()));
        assertTrue(Files.readString(selectorFile(temporaryDirectory)).contains("generation=" + second.path().getFileName()));
    }

    @Test
    void unlockedCrashTokenDoesNotPreventReclamation() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "stale-a");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), false);
        assertEquals(1L, leaseEntryCount(first.path()));

        install(temporaryDirectory, COMMIT_B, "stale-b");

        assertFalse(Files.exists(first.path()));
    }

    @Test
    void missingProtocolMalformedTokenAndWrongPlatformStateAllFailClosed() throws Exception {
        Path missingRoot = temporaryDirectory.resolve("missing-protocol");
        InstalledGeneration missing = install(missingRoot, COMMIT_A, "missing-a");
        MCEFGenerationLeaseRegistry.releaseForTests(missing.path(), true);
        Files.delete(missing.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME));
        install(missingRoot, COMMIT_B, "missing-b");
        assertTrue(Files.isDirectory(missing.path()));

        Path malformedRoot = temporaryDirectory.resolve("malformed-token");
        InstalledGeneration malformed = install(malformedRoot, COMMIT_A, "malformed-a");
        MCEFGenerationLeaseRegistry.releaseForTests(malformed.path(), true);
        Files.writeString(malformed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME).resolve("unrecognized-token"), "unsafe");
        install(malformedRoot, COMMIT_B, "malformed-b");
        assertTrue(Files.isDirectory(malformed.path()));

        Path wrongStateRoot = temporaryDirectory.resolve("wrong-platform");
        InstalledGeneration wrongState = install(wrongStateRoot, COMMIT_A, "wrong-state-a");
        MCEFGenerationLeaseRegistry.releaseForTests(wrongState.path(), true);
        Path state = wrongState.path().resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE);
        Files.writeString(state, Files.readString(state).replace("platform=" + PLATFORM.getNormalizedName(), "platform=linux_amd64"));
        Path malformedName = generationsDirectory(wrongStateRoot).resolve("not-a-generation");
        Files.createDirectory(malformedName);
        install(wrongStateRoot, COMMIT_B, "wrong-state-b");
        assertTrue(Files.isDirectory(wrongState.path()));
        assertTrue(Files.isDirectory(malformedName));
    }

    @Test
    void recognizedNonemptyLeaseTokenFailsClosedWithoutDeletion() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "nonempty-a");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), true);
        Path token = first.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME).resolve(UUID.randomUUID() + ".lease");
        Files.writeString(token, "unsafe");

        install(temporaryDirectory, COMMIT_B, "nonempty-b");

        assertTrue(Files.isDirectory(first.path()));
        assertEquals("unsafe", Files.readString(token));
    }

    @Test
    void symbolicLeaseTokenFailsClosedWithoutFollowingOrDeletion() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "symlink-a");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), true);
        Path outside = temporaryDirectory.resolve("outside-lease-target");
        Files.writeString(outside, "unchanged");
        Path token = first.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME).resolve(UUID.randomUUID() + ".lease");
        try {
            Files.createSymbolicLink(token, outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "Symbolic links are unavailable: " + unsupported.getMessage());
        }

        install(temporaryDirectory, COMMIT_B, "symlink-b");

        assertTrue(Files.isDirectory(first.path()));
        assertTrue(Files.isSymbolicLink(token));
        assertEquals("unchanged", Files.readString(outside));
    }

    @Test
    void leaseTokenMetadataRaceFailsClosedBeforeDeletion() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "race");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path token = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME).resolve(UUID.randomUUID() + ".lease");
        Files.createFile(token);
        FileTime racedTime = FileTime.fromMillis(Files.getLastModifiedTime(token).toMillis() + TimeUnit.SECONDS.toMillis(2L));

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            IOException failure = assertThrows(IOException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, candidate -> Files.setLastModifiedTime(candidate, racedTime)));
            assertTrue(failure.getMessage().contains("changed while pruning"));
            int[] retryDeletes = {0};
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, candidate -> retryDeletes[0]++));
            assertEquals(1, retryDeletes[0]);
        }

        assertFalse(Files.exists(token));
    }

    @Test
    void overlappingSameJvmLeaseTokenSurvivesPruning() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "overlap");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path token = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME).resolve(UUID.randomUUID() + ".lease");
        try (FileChannel channel = FileChannel.open(token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); FileLock lock = channel.lock(); MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            MCEFGenerationLeaseRegistry.pruneStaleTokens(installed.path(), transaction);
            assertTrue(Files.isRegularFile(token));
            assertTrue(lock.isValid());
            assertEquals("LOCKED", probeTokenLockFromChildJvm(token));
        }
    }

    @Test
    void leasePruningRejectsAnUnrelatedPlatformInstallerLock() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "lock-proof");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), false);
        try (MCEFInstallationTransaction unrelated = new MCEFInstallationTransaction(temporaryDirectory.resolve("unrelated"), PLATFORM, COMMIT_A, failure -> {})) {
            assertThrows(IllegalArgumentException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokens(installed.path(), unrelated));
        }
        assertEquals(1L, leaseEntryCount(installed.path()));
    }

    @Test
    void reservedLeaseDirectoryCollisionIsRejectedBeforePublication() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("reserved-collision");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader.ArchiveExtractor collidingExtractor = (archiveFile, outputDirectory) -> {
            Path staged = outputDirectory.toPath().resolve(PLATFORM.getNormalizedName());
            MCEFInstallerTestSupport.writeRuntimeInstallation(staged, PLATFORM, "reserved-collision", COMMIT_A);
            Files.createDirectory(staged.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME));
        };
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, collidingExtractor);

        IOException failure = assertThrows(IOException.class, () -> downloader.installOrUpdate(false, true));

        assertTrue(failure.getMessage().contains("reserved generation lease directory"));
        assertFalse(Files.exists(selectorFile(temporaryDirectory)));
    }

    @Test
    void generationIsLeasedBeforeInstallerLockIsReleasedAndAcquisitionIsDeduplicated() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "lock-order");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {});
        CountDownLatch contenderStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            transaction.recover();
            Path firstSelection = transaction.findUsableInstallation(installed.digest(), false);
            Path secondSelection = transaction.findUsableInstallation(installed.digest(), false);
            assertEquals(installed.path(), firstSelection);
            assertEquals(installed.path(), secondSelection);
            assertTrue(MCEFGenerationLeaseRegistry.isLeasedForTests(installed.path()));
            assertEquals(1L, leaseEntryCount(installed.path()));

            Callable<Void> contenderTask = () -> {
                contenderStarted.countDown();
                try (MCEFInstallationTransaction ignored = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
                    return null;
                }
            };
            Future<?> contender = executor.submit(contenderTask);
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> contender.get(200, TimeUnit.MILLISECONDS));
            transaction.close();
            contender.get(5, TimeUnit.SECONDS);
        } finally {
            transaction.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupFailureOnlyLeavesTheOldGenerationAsResidue() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "cleanup-a");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), true);
        Path garbageDirectory = temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-gc");
        Files.delete(garbageDirectory);
        Files.writeString(garbageDirectory, "blocks quarantine");

        InstalledGeneration second = install(temporaryDirectory, COMMIT_B, "cleanup-b");

        assertTrue(Files.isDirectory(first.path()));
        assertTrue(Files.isDirectory(second.path()));
        assertTrue(Files.readString(selectorFile(temporaryDirectory)).contains("generation=" + second.path().getFileName()));
    }

    @Test
    void failedQuarantineCanRetryUsingTheSameSuccessfulReclaimProof() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "retry-quarantine-a");
        InstalledGeneration second = install(temporaryDirectory, COMMIT_B, "retry-quarantine-b");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), true);
        int[] failedMoves = {0};
        MCEFInstallationTransaction.MoveExecutor moveExecutor = (source, target, replaceExisting) -> {
            if (source.equals(first.path())) {
                failedMoves[0]++;
                throw new IOException("Injected superseded-generation quarantine failure");
            }
            moveForTest(source, target, replaceExisting);
        };

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_B, moveExecutor, failure -> {})) {
            transaction.recover();
            assertEquals(second.path(), transaction.findUsableInstallation(second.digest(), false));
            assertEquals(1, failedMoves[0]);
            assertTrue(Files.isDirectory(first.path()));

            assertEquals(second.path(), transaction.findUsableInstallation(second.digest(), false));
            assertEquals(2, failedMoves[0]);
            assertTrue(Files.isDirectory(first.path()));
        }
    }

    @Test
    void staleProbeLockIsClosedBeforeTheWindowsCompatibleQuarantineMove() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "windows-move-a");
        InstalledGeneration second = install(temporaryDirectory, COMMIT_B, "windows-move-b");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), true);
        Path token = first.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME).resolve(UUID.randomUUID() + ".lease");
        Files.createFile(token);
        Path hardLink = temporaryDirectory.resolve("stale-token-lock-probe");
        try {
            Files.createLink(hardLink, token);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "Hard links are unavailable: " + unsupported.getMessage());
            return;
        }
        int[] lockChecks = {0};
        MCEFInstallationTransaction.MoveExecutor moveExecutor = (source, target, replaceExisting) -> {
            if (source.equals(first.path())) {
                assertFalse(Files.exists(token));
                try (FileChannel channel = FileChannel.open(hardLink, StandardOpenOption.WRITE)) {
                    FileLock lock = channel.tryLock();
                    assertNotNull(lock, "The prune probe lock must be closed before quarantine moves the generation");
                    try (lock) {
                        lockChecks[0]++;
                    }
                }
            }
            moveForTest(source, target, replaceExisting);
        };

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_B, moveExecutor, failure -> {})) {
            transaction.recover();
            assertEquals(second.path(), transaction.findUsableInstallation(second.digest(), false));
        }

        assertEquals(1, lockChecks[0]);
        assertFalse(Files.exists(first.path()));
    }

    @Test
    void unlockedTokensBeyondValidationLimitArePrunedAndGenerationIsReused() throws Exception {
        Path tokenRoot = temporaryDirectory.resolve("token-limit");
        InstalledGeneration tokenGeneration = install(tokenRoot, COMMIT_A, "token-limit-a");
        MCEFGenerationLeaseRegistry.releaseForTests(tokenGeneration.path(), false);
        Path leases = tokenGeneration.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        for (int index = 0; index < MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS; index++) {
            Files.createFile(leases.resolve(UUID.randomUUID() + ".lease"));
        }
        assertEquals(MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS + 1L, leaseEntryCount(tokenGeneration.path()));

        InstalledGeneration reused = install(tokenRoot, COMMIT_A, "token-limit-a");

        assertEquals(tokenGeneration.path(), reused.path());
        assertEquals(1L, leaseEntryCount(tokenGeneration.path()));
    }

    @Test
    void successfulLiveTokenPruneIsReusedWithoutRechargingTheTransactionBudget() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "prune-cache");

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            for (int index = 0; index <= MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION; index++) {
                assertTrue(MCEFGenerationLeaseRegistry.pruneStaleTokens(installed.path(), transaction));
            }
        }

        assertEquals(1L, leaseEntryCount(installed.path()));
    }

    @Test
    void emptyLeasePruneConsumesOnlyOneOpenAndOneEofProbeThenUsesTheCache() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "prune-empty");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        CountingLeaseDirectoryStreamOpener opener = new CountingLeaseDirectoryStreamOpener(leases, List.of());

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertDirectoryWork(opener, 1, 1, 0);
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertDirectoryWork(opener, 1, 1, 0);
        }
    }

    @Test
    void reclaimUsesTheSuccessfulPruneProofWithoutASecondEnumeration() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "reclaim-proof");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        // The injected walk is the proof-producing operation. This sentinel is intentionally outside
        // its view so any accidental second walk through the real filesystem changes the result.
        Path secondWalkSentinel = leases.resolve("second-walk-must-not-run");
        Files.createFile(secondWalkSentinel);
        CountingLeaseDirectoryStreamOpener opener = new CountingLeaseDirectoryStreamOpener(leases, List.of());

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertTrue(MCEFGenerationLeaseRegistry.canReclaim(installed.path(), transaction));
            assertTrue(MCEFGenerationLeaseRegistry.canReclaim(installed.path(), transaction));
            assertDirectoryWork(opener, 1, 1, 0);
        }

        assertTrue(Files.exists(secondWalkSentinel));
    }

    @Test
    void reclaimRejectsLeaseDirectorySnapshotAndIdentityMutationWithoutRescanning() throws Exception {
        InstalledGeneration snapshotMutation = install(temporaryDirectory.resolve("snapshot"), COMMIT_A, "reclaim-snapshot");
        MCEFGenerationLeaseRegistry.releaseForTests(snapshotMutation.path(), true);
        Path snapshotLeases = snapshotMutation.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory.resolve("snapshot"), PLATFORM, COMMIT_A, failure -> {})) {
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokens(snapshotMutation.path(), transaction));
            Path addedToken = snapshotLeases.resolve(UUID.randomUUID() + ".lease");
            Files.createFile(addedToken);
            Files.setLastModifiedTime(snapshotLeases, FileTime.fromMillis(Files.getLastModifiedTime(snapshotLeases).toMillis() + TimeUnit.SECONDS.toMillis(2L)));

            assertFalse(MCEFGenerationLeaseRegistry.canReclaim(snapshotMutation.path(), transaction));
            assertFalse(MCEFGenerationLeaseRegistry.canReclaim(snapshotMutation.path(), transaction));
            assertTrue(Files.exists(addedToken));
        }

        Path identityRoot = temporaryDirectory.resolve("identity");
        InstalledGeneration identityMutation = install(identityRoot, COMMIT_A, "reclaim-identity");
        MCEFGenerationLeaseRegistry.releaseForTests(identityMutation.path(), true);
        Path identityLeases = identityMutation.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(identityRoot, PLATFORM, COMMIT_A, failure -> {})) {
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokens(identityMutation.path(), transaction));
            Files.move(identityLeases, identityMutation.path().resolve("replaced-lease-directory"));
            Files.createDirectory(identityLeases);

            assertFalse(MCEFGenerationLeaseRegistry.canReclaim(identityMutation.path(), transaction));
        }
    }

    @Test
    void livePruneCacheRejectsReclaimAfterTheOwningLeaseIsReleased() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "reclaim-live-cache");
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        List<Path> tokens;
        try (var entries = Files.list(leases)) {
            tokens = entries.toList();
        }
        assertEquals(1, tokens.size());
        CountingLeaseDirectoryStreamOpener opener = new CountingLeaseDirectoryStreamOpener(leases, tokens);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertTrue(MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), false);

            assertFalse(MCEFGenerationLeaseRegistry.canReclaim(installed.path(), transaction));
            assertDirectoryWork(opener, 1, 2, 1);
        }
    }

    @Test
    void activeProcessLeaseRejectsAnOtherwiseReclaimableCachedProof() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "reclaim-active-process");
        Path canonicalGeneration = installed.path().toRealPath();
        Path leaseDirectory = canonicalGeneration.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME).toRealPath();
        BasicFileAttributes directorySnapshot = Files.readAttributes(leaseDirectory, BasicFileAttributes.class);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            MCEFInstallationTransaction.LeasePruningBudget budget = transaction.requireLeasePruningLock(canonicalGeneration);
            budget.recordSuccessfulPrune(canonicalGeneration, new MCEFInstallationTransaction.LeasePruningResult(leaseDirectory, directorySnapshot, false));

            assertFalse(MCEFGenerationLeaseRegistry.canReclaim(installed.path(), transaction));
        }
    }

    @Test
    void missingAndFailedPruneProofsCannotFallBackToAReclaimScan() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "reclaim-missing-proof");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        CountingLeaseDirectoryStreamOpener opener = new CountingLeaseDirectoryStreamOpener(leases, List.of(), 1, 0);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertFalse(MCEFGenerationLeaseRegistry.canReclaim(installed.path(), transaction));
            assertThrows(DirectoryIteratorException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertFalse(MCEFGenerationLeaseRegistry.canReclaim(installed.path(), transaction));
            assertDirectoryWork(opener, 1, 1, 0);
        }
    }

    @Test
    void terminalPruningBudgetWithoutAProofCannotFallBackToAReclaimScan() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "reclaim-terminal-budget");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            MCEFInstallationTransaction.LeasePruningBudget budget = transaction.requireLeasePruningLock(installed.path());
            for (int probe = 0; probe < MCEFGenerationLeaseRegistry.MAX_LEASE_SCAN_PROBES_PER_TRANSACTION; probe++) {
                budget.recordScanProbe();
            }
            IOException terminalFailure = assertThrows(IOException.class, budget::recordScanProbe);

            assertFalse(MCEFGenerationLeaseRegistry.canReclaim(installed.path(), transaction));
            IOException repeatedFailure = assertThrows(IOException.class, budget::recordScanProbe);
            assertEquals(terminalFailure.getMessage(), repeatedFailure.getMessage());
        }
    }

    @Test
    void exactLeasePruningEntryCapReachesEofAndCachesTheSuccessfulScan() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "prune-exact-cap");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        List<Path> tokens = createStaleLeaseTokens(installed.path(), MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);
        CountingLeaseDirectoryStreamOpener opener = new CountingLeaseDirectoryStreamOpener(leases, tokens);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertDirectoryWork(opener, 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertDirectoryWork(opener, 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);
        }

        assertEquals(0L, leaseEntryCount(installed.path()));
    }

    @Test
    void leasePruningBudgetSupportsEveryBoundedGenerationAtTheCombinedEntryCap() throws Exception {
        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            MCEFInstallationTransaction.LeasePruningBudget budget = transaction.requireLeasePruningLock(generationsDirectory(temporaryDirectory.toRealPath()).resolve("budget-only-generation"));
            int baseEntries = MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION / MCEFInstallationTransaction.MAX_GENERATIONS_TO_SCAN;
            int extraEntryGenerations = MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION % MCEFInstallationTransaction.MAX_GENERATIONS_TO_SCAN;
            for (int generation = 0; generation < MCEFInstallationTransaction.MAX_GENERATIONS_TO_SCAN; generation++) {
                budget.recordScanProbe();
                int generationEntries = baseEntries + (generation < extraEntryGenerations ? 1 : 0);
                for (int entry = 0; entry < generationEntries; entry++) {
                    budget.recordScanProbe();
                    budget.recordEntry();
                }
                budget.recordScanProbe();
            }

            IOException failure = assertThrows(IOException.class, budget::recordScanProbe);
            assertTrue(failure.getMessage().contains("bounded installer-lock scan limit"));
            IOException retryFailure = assertThrows(IOException.class, budget::recordScanProbe);
            assertEquals(failure.getMessage(), retryFailure.getMessage());
        }
    }

    @Test
    void throwingHasNextConsumesOpenAndProbePermitsUntilEnumerationIsTerminal() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "prune-throwing-has-next");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        int throwingAttempts = (MCEFGenerationLeaseRegistry.MAX_LEASE_SCAN_PROBES_PER_TRANSACTION - 1) / 2;
        CountingLeaseDirectoryStreamOpener opener = new CountingLeaseDirectoryStreamOpener(leases, List.of(), throwingAttempts, 0);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            for (int attempt = 0; attempt < throwingAttempts; attempt++) {
                assertThrows(DirectoryIteratorException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            }
            assertDirectoryWork(opener, throwingAttempts, throwingAttempts, 0);

            IOException failure = assertThrows(IOException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertTrue(failure.getMessage().contains("bounded installer-lock scan limit"));
            assertDirectoryWork(opener, throwingAttempts + 1, throwingAttempts, 0);
            IOException retryFailure = assertThrows(IOException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertEquals(failure.getMessage(), retryFailure.getMessage());
            assertDirectoryWork(opener, throwingAttempts + 1, throwingAttempts, 0);
        }
    }

    @Test
    void throwingNextConsumesAnEntryPermitBeforeThePathIsFetched() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "prune-throwing-next");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        List<Path> tokens = createStaleLeaseTokens(installed.path(), MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);
        Path unprocessedToken = tokens.getLast();
        CountingLeaseDirectoryStreamOpener opener = new CountingLeaseDirectoryStreamOpener(leases, tokens, 0, 1);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertThrows(DirectoryIteratorException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertDirectoryWork(opener, 1, 1, 1);

            IOException failure = assertThrows(IOException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertTrue(failure.getMessage().contains("bounded installer-lock entry limit"));
            assertDirectoryWork(opener, 2, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);
            assertTrue(Files.exists(unprocessedToken));
            assertEquals(1L, leaseEntryCount(installed.path()));

            IOException retryFailure = assertThrows(IOException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> {}, opener));
            assertEquals(failure.getMessage(), retryFailure.getMessage());
            assertDirectoryWork(opener, 2, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);
        }
    }

    @Test
    void leaseDirectoryMutationInvalidatesTheSuccessfulPruneCache() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "prune-cache-mutation");
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertTrue(MCEFGenerationLeaseRegistry.pruneStaleTokens(installed.path(), transaction));
            FileTime changedDirectoryTime = FileTime.fromMillis(Files.getLastModifiedTime(leases).toMillis() + TimeUnit.SECONDS.toMillis(2L));
            Path staleToken = leases.resolve(UUID.randomUUID() + ".lease");
            Files.createFile(staleToken);
            Files.setLastModifiedTime(leases, changedDirectoryTime);

            assertTrue(MCEFGenerationLeaseRegistry.pruneStaleTokens(installed.path(), transaction));
            assertFalse(Files.exists(staleToken));
        }

        assertEquals(1L, leaseEntryCount(installed.path()));
    }

    @Test
    void overBudgetUnlockedTokenFloodFailsClosedAndResumesUnderNextInstallerLock() throws Exception {
        InstalledGeneration installed = install(temporaryDirectory, COMMIT_A, "prune-budget");
        MCEFGenerationLeaseRegistry.releaseForTests(installed.path(), true);
        Path leases = installed.path().resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        List<Path> tokens = createStaleLeaseTokens(installed.path(), MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 1);
        Path unprocessedToken = tokens.getLast();
        CountingLeaseDirectoryStreamOpener opener = new CountingLeaseDirectoryStreamOpener(leases, tokens);

        Set<Path> deletedTokens = new LinkedHashSet<>();
        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            IOException failure = assertThrows(IOException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> deletedTokens.add(token), opener));
            assertTrue(failure.getMessage().contains("bounded installer-lock entry limit"));
            assertEquals(MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION, deletedTokens.size());
            assertDirectoryWork(opener, 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);
            assertEquals(1L, leaseEntryCount(installed.path()));
            assertFalse(deletedTokens.contains(unprocessedToken));
            assertEquals(0L, Files.size(unprocessedToken));

            int[] retryDeletes = {0};
            for (int retry = 0; retry < 3; retry++) {
                IOException retryFailure = assertThrows(IOException.class, () -> MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> retryDeletes[0]++, opener));
                assertEquals(failure.getMessage(), retryFailure.getMessage());
            }
            assertEquals(0, retryDeletes[0]);
            assertDirectoryWork(opener, 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 1, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION);
            assertEquals(1L, leaseEntryCount(installed.path()));
        }

        int[] resumedDeletes = {0};
        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertFalse(MCEFGenerationLeaseRegistry.pruneStaleTokensForTests(installed.path(), transaction, token -> resumedDeletes[0]++, opener));
        }
        assertEquals(1, resumedDeletes[0]);
        assertDirectoryWork(opener, 2, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 3, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS_TO_PRUNE_PER_TRANSACTION + 1);
        assertEquals(0L, leaseEntryCount(installed.path()));
    }

    @Test
    void generationEnumerationLimitRetainsEverythingFailClosed() throws Exception {
        Path generationRoot = temporaryDirectory.resolve("generation-limit");
        InstalledGeneration boundedGeneration = install(generationRoot, COMMIT_A, "generation-limit-a");
        MCEFGenerationLeaseRegistry.releaseForTests(boundedGeneration.path(), true);
        for (int index = 0; index < MCEFInstallationTransaction.MAX_GENERATIONS_TO_SCAN; index++) {
            Files.createDirectory(generationsDirectory(generationRoot).resolve("unrecognized-" + index));
        }
        InstalledGeneration selected = install(generationRoot, COMMIT_B, "generation-limit-b");
        assertTrue(Files.isDirectory(boundedGeneration.path()));
        assertTrue(Files.isDirectory(selected.path()));
    }

    @Test
    void lockedChildJvmTokenSurvivesPruningAndBlocksReclamation() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "cross-jvm-a");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), true);
        try (LeaseHolderProcess child = startLeaseHolder(temporaryDirectory, COMMIT_A, first.digest())) {
            InstalledGeneration second = install(temporaryDirectory, COMMIT_B, "cross-jvm-b");

            assertTrue(Files.isDirectory(first.path()));
            assertTrue(Files.isDirectory(second.path()));
            assertEquals(1L, leaseEntryCount(first.path()));
        }
        assertEquals(1L, leaseEntryCount(first.path()));
    }

    @Test
    void exitedChildJvmTokenIsPrunedOnNextStartup() throws Exception {
        InstalledGeneration first = install(temporaryDirectory, COMMIT_A, "exited-child");
        MCEFGenerationLeaseRegistry.releaseForTests(first.path(), true);
        try (LeaseHolderProcess ignored = startLeaseHolder(temporaryDirectory, COMMIT_A, first.digest())) {
            assertEquals(1L, leaseEntryCount(first.path()));
        }
        assertEquals(1L, leaseEntryCount(first.path()));

        InstalledGeneration reused = install(temporaryDirectory, COMMIT_A, "exited-child");

        assertEquals(first.path(), reused.path());
        assertEquals(1L, leaseEntryCount(first.path()));
    }

    @Test
    void immutableGenerationCleanupNeverTouchesLegacyInstallation() throws Exception {
        MCEFInstallerTestSupport.writeLegacyInstallation(temporaryDirectory, "legacy", MCEFInstallerTestSupport.OLD_DIGEST);

        install(temporaryDirectory, COMMIT_B, "published");

        Path legacy = temporaryDirectory.resolve(PLATFORM.getNormalizedName());
        assertTrue(Files.isDirectory(legacy));
        assertEquals("legacy", MCEFInstallerTestSupport.readVersion(legacy));
    }

    private InstalledGeneration install(Path libraries, String commit, String version) throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes(version + "-" + commit);
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(libraries, commit, archive, digest, MCEFInstallerTestSupport.extractor(version, commit));
        Path installed = downloader.installOrUpdate(false, true).installationDirectory();
        return new InstalledGeneration(installed, digest);
    }

    private static long leaseEntryCount(Path generation) throws Exception {
        try (var entries = Files.list(generation.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME))) {
            return entries.count();
        }
    }

    private static List<Path> createStaleLeaseTokens(Path generation, int tokenCount) throws IOException {
        Path leases = generation.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        List<Path> tokens = new ArrayList<>(tokenCount);
        for (int index = 0; index < tokenCount; index++) {
            Path token = leases.resolve(UUID.randomUUID() + ".lease");
            Files.createFile(token);
            tokens.add(token);
        }
        return List.copyOf(tokens);
    }

    private static void moveForTest(Path source, Path target, boolean replaceExisting) throws IOException {
        if (replaceExisting) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.move(source, target);
    }

    private static void assertDirectoryWork(CountingLeaseDirectoryStreamOpener opener, int openCalls, int hasNextCalls, int nextCalls) {
        assertEquals(openCalls, opener.openCalls());
        assertEquals(hasNextCalls, opener.hasNextCalls());
        assertEquals(nextCalls, opener.nextCalls());
    }

    private static Path selectorFile(Path libraries) {
        return libraries.resolve("." + PLATFORM.getNormalizedName() + ".mcef-current.properties");
    }

    private static Path generationsDirectory(Path libraries) {
        return libraries.resolve("." + PLATFORM.getNormalizedName() + ".mcef-generations");
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String testClasspath() throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        addClasspathProperty(entries);
        addCodeSource(entries, MCEFGenerationLeaseTest.class);
        addCodeSource(entries, MCEFInstallationTransaction.class);
        for (ClassLoader loader = MCEFGenerationLeaseTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlClassLoader) {
                for (URL url : urlClassLoader.getURLs()) {
                    if (url.getProtocol().equals("file")) {
                        entries.add(Path.of(url.toURI()).toString());
                    }
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void addClasspathProperty(Set<String> entries) {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
    }

    private static void addCodeSource(Set<String> entries, Class<?> type) throws Exception {
        URL location = type.getProtectionDomain().getCodeSource().getLocation();
        URI uri = location.toURI();
        entries.add(Path.of(uri).toString());
    }

    private record InstalledGeneration(Path path, String digest) {
    }

    private static final class CountingLeaseDirectoryStreamOpener implements MCEFGenerationLeaseRegistry.LeaseDirectoryStreamOpener {
        private final Path expectedDirectory;
        private final List<Path> entries;
        private int remainingHasNextFailures;
        private int remainingNextFailures;
        private int openCalls;
        private int hasNextCalls;
        private int nextCalls;

        private CountingLeaseDirectoryStreamOpener(Path expectedDirectory, List<Path> entries) {
            this(expectedDirectory, entries, 0, 0);
        }

        private CountingLeaseDirectoryStreamOpener(Path expectedDirectory, List<Path> entries, int hasNextFailures, int nextFailures) {
            this.expectedDirectory = expectedDirectory;
            this.entries = List.copyOf(entries);
            remainingHasNextFailures = hasNextFailures;
            remainingNextFailures = nextFailures;
        }

        @Override
        public DirectoryStream<Path> open(Path leaseDirectory) throws IOException {
            if (!expectedDirectory.equals(leaseDirectory)) {
                throw new IOException("Unexpected lease directory: " + leaseDirectory);
            }
            openCalls++;
            List<Path> existingEntries = entries.stream().filter(Files::exists).toList();
            return new DirectoryStream<>() {
                private boolean iteratorCreated;

                @Override
                public Iterator<Path> iterator() {
                    if (iteratorCreated) {
                        throw new IllegalStateException("The test lease directory stream supports one iterator");
                    }
                    iteratorCreated = true;
                    Iterator<Path> iterator = existingEntries.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            hasNextCalls++;
                            if (remainingHasNextFailures > 0) {
                                remainingHasNextFailures--;
                                throw new DirectoryIteratorException(new IOException("Injected hasNext failure"));
                            }
                            return iterator.hasNext();
                        }

                        @Override
                        public Path next() {
                            nextCalls++;
                            if (remainingNextFailures > 0) {
                                remainingNextFailures--;
                                throw new DirectoryIteratorException(new IOException("Injected next failure"));
                            }
                            return iterator.next();
                        }
                    };
                }

                @Override
                public void close() {
                }
            };
        }

        private int openCalls() {
            return openCalls;
        }

        private int hasNextCalls() {
            return hasNextCalls;
        }

        private int nextCalls() {
            return nextCalls;
        }
    }

    private static LeaseHolderProcess startLeaseHolder(Path libraries, String commit, String digest) throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-cp", testClasspath(), LeaseHolderMain.class.getName(), libraries.toString(), commit, digest).redirectErrorStream(true).start();
        BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        ExecutorService reader = Executors.newSingleThreadExecutor();
        boolean ready = false;
        try {
            Future<String> status = reader.submit(output::readLine);
            assertEquals("READY", status.get(15, TimeUnit.SECONDS));
            ready = true;
            return new LeaseHolderProcess(process, output);
        } finally {
            reader.shutdownNow();
            if (!ready) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                output.close();
            }
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static String probeTokenLockFromChildJvm(Path token) throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-cp", testClasspath(), TokenLockProbeMain.class.getName(), token.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private record LeaseHolderProcess(Process process, BufferedReader output) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
            output.close();
            assertEquals(0, process.exitValue());
        }
    }

    public static final class LeaseHolderMain {
        private LeaseHolderMain() {
        }

        public static void main(String[] arguments) throws Exception {
            Path libraries = Path.of(arguments[0]);
            try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(libraries, PLATFORM, arguments[1], failure -> {})) {
                transaction.recover();
                Path selected = transaction.findUsableInstallation(arguments[2], false);
                if (selected == null) {
                    throw new IllegalStateException("Child JVM could not select the requested JCEF generation");
                }
            }
            System.out.println("READY");
            System.out.flush();
            System.in.read();
        }
    }

    public static final class TokenLockProbeMain {
        private TokenLockProbeMain() {
        }

        public static void main(String[] arguments) throws Exception {
            try (FileChannel channel = FileChannel.open(Path.of(arguments[0]), StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
                System.out.println(lock == null ? "LOCKED" : "UNLOCKED");
            }
        }
    }
}
