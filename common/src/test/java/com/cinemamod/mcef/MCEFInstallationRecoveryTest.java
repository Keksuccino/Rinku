/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package com.cinemamod.mcef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.cinemamod.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static com.cinemamod.mcef.MCEFInstallerTestSupport.OLD_DIGEST;
import static com.cinemamod.mcef.MCEFInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFInstallationRecoveryTest extends MCEFInstallerTestBase {
    @TempDir
    Path temporaryDirectory;

    @Test
    void committedLegacyCleanupFailureMovesToGcAndNeverBlocksValidInstallation() throws Exception {
        MCEFInstallerTestSupport.writeLegacyInstallation(temporaryDirectory, "live", OLD_DIGEST);
        Path legacyTransaction = temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install");
        MCEFInstallerTestSupport.writeRuntimeInstallation(legacyTransaction.resolve("previous-installation"), "locked-old");
        Files.writeString(legacyTransaction.resolve("committed"), "committed\n");
        List<IOException> warnings = new ArrayList<>();
        MCEFInstallationTransaction.MoveExecutor ambiguousGcMove = (source, target, replaceExisting) -> {
            MCEFInstallationTransaction.moveWithAtomicFallback(source, target, replaceExisting);
            if (source.getFileName().equals(legacyTransaction.getFileName())) {
                throw new IOException("simulated Windows-locked cleanup residue");
            }
        };

        try (MCEFInstallationTransaction first = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, ambiguousGcMove, warnings::add)) {
            first.recover();
            assertTrue(Files.isSameFile(temporaryDirectory.resolve(PLATFORM.getNormalizedName()), first.findUsableInstallation(null, true)));
            first.prepareFresh();
            first.abortPrepared();
        }

        assertFalse(warnings.isEmpty());
        assertFalse(Files.exists(legacyTransaction));
        Path garbageDirectory = temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-gc");
        try (var garbage = Files.list(garbageDirectory)) {
            assertTrue(garbage.findAny().isPresent());
        }

        try (MCEFInstallationTransaction second = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            second.recover();
            assertTrue(Files.isSameFile(temporaryDirectory.resolve(PLATFORM.getNormalizedName()), second.findUsableInstallation(null, true)));
        }
        try (var garbage = Files.list(garbageDirectory)) {
            assertTrue(garbage.findAny().isEmpty());
        }
    }

    @Test
    void incompleteLegacyInstallationIsRestoredWhenGarbageDirectoryDoesNotExist() throws Exception {
        Path legacyInstallation = temporaryDirectory.resolve(PLATFORM.getNormalizedName());
        Files.createDirectories(legacyInstallation);
        Files.writeString(legacyInstallation.resolve("incomplete.txt"), "broken");
        Path legacyTransaction = temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install");
        MCEFInstallerTestSupport.writeRuntimeInstallation(legacyTransaction.resolve("previous-installation"), "restored");
        MCEFInstallerTestSupport.writeChecksum(legacyTransaction.resolve("previous.tar.gz.sha256"), OLD_DIGEST);

        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertEquals(MCEFInstallationTransaction.RecoveryOutcome.RESTORED_LEGACY, recovery.recover());
            assertEquals("restored", MCEFInstallerTestSupport.readVersion(legacyInstallation));
            assertEquals(OLD_DIGEST, Files.readString(temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz.sha256")).substring(0, 64));
        }

        assertFalse(Files.exists(legacyTransaction));
    }

    @Test
    void crashAfterGenerationMoveBeforeSelectorIsRecoveredAsCommitted() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("crash-after-generation");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFInstallationTransaction.MoveExecutor selectorFailure = (source, target, replaceExisting) -> {
            if (target.getFileName().toString().endsWith(".mcef-current.properties")) {
                throw new IOException("simulated crash before selector publication");
            }
            MCEFInstallationTransaction.moveWithAtomicFallback(source, target, replaceExisting);
        };

        try (MCEFInstallationTransaction interrupted = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, selectorFailure, failure -> {})) {
            prepareCandidate(interrupted, archive, "new");
            assertThrows(IOException.class, () -> interrupted.promote(digest, MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE));
        }

        assertFalse(Files.exists(selectorFile()));
        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertEquals(MCEFInstallationTransaction.RecoveryOutcome.COMMITTED, recovery.recover());
            Path installed = recovery.findUsableInstallation(digest, false);
            assertNotNull(installed);
            assertEquals("new", MCEFInstallerTestSupport.readVersion(installed));
        }
    }

    @Test
    void failureBeforeGenerationMoveCannotPublishPartialCandidate() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("pre-move");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFInstallerTestSupport.writeLegacyInstallation(temporaryDirectory, "old", OLD_DIGEST);
        MCEFInstallationTransaction.MoveExecutor generationFailure = (source, target, replaceExisting) -> {
            if (source.getFileName().toString().equals(PLATFORM.getNormalizedName()) && target.getParent() != null && target.getParent().getFileName().toString().endsWith(".mcef-generations")) {
                throw new IOException("simulated crash before generation move");
            }
            MCEFInstallationTransaction.moveWithAtomicFallback(source, target, replaceExisting);
        };

        try (MCEFInstallationTransaction interrupted = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, generationFailure, failure -> {})) {
            prepareCandidate(interrupted, archive, "new");
            assertThrows(IOException.class, () -> interrupted.promote(digest, MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE));
        }

        assertFalse(Files.exists(selectorFile()));
        assertEquals("old", MCEFInstallerTestSupport.readVersion(temporaryDirectory.resolve(PLATFORM.getNormalizedName())));
        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            recovery.recover();
            assertNull(recovery.findUsableInstallation(null, true));
        }
    }

    @Test
    void moveThatCompletesBeforeReportingFailureStillCommitsExactGeneration() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("ambiguous-move");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFInstallationTransaction.MoveExecutor ambiguousMove = (source, target, replaceExisting) -> {
            MCEFInstallationTransaction.moveWithAtomicFallback(source, target, replaceExisting);
            if (source.getFileName().toString().equals(PLATFORM.getNormalizedName()) && target.getParent() != null && target.getParent().getFileName().toString().endsWith(".mcef-generations")) {
                throw new IOException("ambiguous completed generation move");
            }
        };

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, ambiguousMove, failure -> {})) {
            prepareCandidate(transaction, archive, "new");
            Path installed = transaction.promote(digest, MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE);
            assertEquals("new", MCEFInstallerTestSupport.readVersion(installed));
            assertTrue(Files.readString(selectorFile()).contains("archive-sha256=" + digest));
        }
    }

    @Test
    void generationMutatedAfterMoveIsQuarantinedBeforeSelectorPublication() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("post-move-mutation");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        String declaredRuntimeFile = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, "new").keySet().iterator().next();
        MCEFInstallationTransaction.MoveExecutor mutatingMove = (source, target, replaceExisting) -> {
            MCEFInstallationTransaction.moveWithAtomicFallback(source, target, replaceExisting);
            if (source.getFileName().toString().equals(PLATFORM.getNormalizedName()) && target.getParent() != null && target.getParent().getFileName().toString().endsWith(".mcef-generations")) {
                Files.writeString(target.resolve(declaredRuntimeFile), "tampered-after-move");
            }
        };

        try (MCEFInstallationTransaction transaction = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, mutatingMove, failure -> {})) {
            prepareCandidate(transaction, archive, "new");
            IOException failure = assertThrows(IOException.class, () -> transaction.promote(digest, MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE));
            assertEquals("Published JCEF generation changed during promotion", failure.getMessage());
        }

        assertFalse(Files.exists(selectorFile()));
        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            recovery.recover();
            assertNull(recovery.findUsableInstallation(digest, false));
        }
    }

    @Test
    void interruptedChecksumVerificationSelectorRewriteIsRecoveredFromGenerationState() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("checksum-verification-recovery");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader.DownloadPolicy defaults = MCEFDownloader.DownloadPolicy.defaults();
        MCEFDownloader.DownloadPolicy optionalChecksums = new MCEFDownloader.DownloadPolicy(defaults.mirrorPolicy(), false, defaults.connectTimeoutMs(), defaults.readTimeoutMs(), defaults.maxArchiveBytes(), defaults.maxChecksumBytes(), defaults.maxExtractedBytes());
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, null, optionalChecksums, MCEFInstallerTestSupport.extractor("checksum-verification-recovery"));
        Path installed = first.installOrUpdate(false, true).installationDirectory();
        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-verification=unchecked"));

        MCEFInstallationTransaction.MoveExecutor selectorFailure = (source, target, replaceExisting) -> {
            if (target.getFileName().toString().endsWith(".mcef-current.properties")) {
                throw new IOException("simulated interrupted checksum verification selector rewrite");
            }
            MCEFInstallationTransaction.moveWithAtomicFallback(source, target, replaceExisting);
        };
        try (MCEFInstallationTransaction interrupted = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, selectorFailure, failure -> {})) {
            Path matching = interrupted.findUsableInstallation(digest, false, false);
            assertNotNull(matching);
            assertThrows(IOException.class, () -> interrupted.markChecksumVerified(matching, digest, MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE));
        }

        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-verification=checksum-verified"));
        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-source=official"));
        assertTrue(Files.readString(selectorFile()).contains("checksum-verification=unchecked"));
        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertEquals(MCEFInstallationTransaction.RecoveryOutcome.COMMITTED, recovery.recover());
            assertEquals(installed, recovery.findUsableInstallation(null, false, true));
        }
        assertTrue(Files.readString(selectorFile()).contains("checksum-verification=checksum-verified"));
        assertTrue(Files.readString(selectorFile()).contains("checksum-source=official"));
    }

    @Test
    void selectorSymlinkIsNeverFollowedOrOverwrittenThroughItsTarget() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("selector-symlink");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        Path outside = temporaryDirectory.resolve("outside-selector.txt");
        Files.writeString(outside, "unchanged");
        Files.createSymbolicLink(selectorFile(), outside);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("new"));

        assertThrows(IOException.class, () -> downloader.installOrUpdate(false, true));
        assertEquals("unchanged", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(selectorFile()));
    }

    @Test
    void transactionEnumerationFailsClosedWhileOversizedGarbageMakesBoundedProgress() throws Exception {
        assertEquals(512, MCEFInstallationTransaction.MAX_TRANSACTIONS_TO_SCAN);
        assertEquals(512, MCEFInstallationTransaction.MAX_GARBAGE_ENTRIES_TO_SCAN);
        Path transactions = temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-transactions");
        Path garbage = temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-gc");
        Files.createDirectories(transactions);
        Files.createDirectories(garbage);
        for (int index = 0; index <= MCEFInstallationTransaction.MAX_TRANSACTIONS_TO_SCAN; index++) {
            Files.createDirectory(transactions.resolve(String.format("transaction-%04d", index)));
        }
        for (int index = 0; index <= MCEFInstallationTransaction.MAX_GARBAGE_ENTRIES_TO_SCAN; index++) {
            Files.createFile(garbage.resolve(String.format("garbage-%04d", index)));
        }
        List<IOException> warnings = new ArrayList<>();

        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, warnings::add)) {
            assertEquals(MCEFInstallationTransaction.RecoveryOutcome.NONE, recovery.recover());
        }

        try (var entries = Files.list(transactions)) {
            assertEquals(MCEFInstallationTransaction.MAX_TRANSACTIONS_TO_SCAN + 1L, entries.count());
        }
        try (var entries = Files.list(garbage)) {
            assertEquals(1L, entries.count());
        }
        assertTrue(warnings.stream().anyMatch(failure -> failure.getMessage().contains("bounded recovery limit")));
        assertTrue(warnings.stream().anyMatch(failure -> failure.getMessage().contains("bounded cleanup limit")));
    }

    @Test
    void legacyCompatibilityTreeWalkIsBounded() throws Exception {
        Path legacy = temporaryDirectory.resolve(PLATFORM.getNormalizedName());
        MCEFInstallerTestSupport.writeRuntimeInstallation(legacy, "legacy-tree-limit");
        Path excess = legacy.resolve("excess");
        Files.createDirectory(excess);
        for (int index = 0; index < MCEFInstallationTransaction.MAX_LEGACY_TREE_ENTRIES; index++) {
            Files.createDirectory(excess.resolve(String.format("entry-%04d", index)));
        }

        assertFalse(MCEFInstallationTransaction.isUsableInstallation(legacy, PLATFORM));
    }

    private void prepareCandidate(MCEFInstallationTransaction transaction, byte[] archive, String version) throws IOException {
        transaction.recover();
        transaction.prepareFresh();
        Files.write(transaction.candidateArchive(), archive);
        MCEFInstallerTestSupport.writeChecksum(transaction.candidateChecksum(), MCEFInstallerTestSupport.sha256(archive));
        MCEFInstallerTestSupport.writeRuntimeInstallation(transaction.stagedInstallation(), version);
    }

    private Path selectorFile() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-current.properties");
    }
}
