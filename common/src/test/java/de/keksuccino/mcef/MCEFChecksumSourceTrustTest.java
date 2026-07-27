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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFChecksumSourceTrustTest extends MCEFInstallerTestBase {
    private static final String CONFIGURED_A = "https://mirror-a.example/releases";
    private static final String CONFIGURED_A_EQUIVALENT = "HTTPS://MIRROR-A.EXAMPLE:443/releases///";
    private static final String CONFIGURED_B = "https://mirror-b.example/releases";

    @TempDir
    Path temporaryDirectory;

    @Test
    void canonicalConfiguredMirrorIdentifierIsStableAndPrivacySafe() {
        String first = MCEFDownloadMirror.parse(CONFIGURED_A).checksumSourceId();
        String equivalent = MCEFDownloadMirror.parse(CONFIGURED_A_EQUIVALENT).checksumSourceId();
        String different = MCEFDownloadMirror.parse(CONFIGURED_B).checksumSourceId();

        assertEquals(first, equivalent);
        assertNotEquals(first, different);
        assertTrue(first.matches("mirror-sha256:[0-9a-f]{64}"));
        assertFalse(first.contains("mirror-a.example"));
    }

    @Test
    void sameConfiguredChecksumSourceRemainsReusableOffline() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("configured-offline");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader first = releaseDownloader(CONFIGURED_A, MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY, archive, digest, new AtomicInteger(), new AtomicInteger(), "configured-offline");
        Path installed = first.installOrUpdate(false, true).installationDirectory();
        String expectedSource = MCEFDownloadMirror.parse(CONFIGURED_A).checksumSourceId();
        String state = Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE));
        assertTrue(state.contains("checksum-source=" + expectedSource));
        assertFalse(state.contains(CONFIGURED_A));

        AtomicInteger offlineAttempts = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader offline = (assetUrl, outputFile, maxBytes) -> {
            offlineAttempts.incrementAndGet();
            throw new IOException("offline");
        };
        MCEFDownloader second = new MCEFDownloader(CONFIGURED_A_EQUIVALENT, COMMIT_A, PLATFORM, policy(MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY), temporaryDirectory, offline, MCEFInstallerTestSupport.extractor("must-not-install"));

        MCEFDownloader.InstallationResult result = second.installOrUpdate(false, true);

        assertFalse(result.downloaded());
        assertEquals(installed, result.installationDirectory());
        assertEquals(0, offlineAttempts.get());
    }

    @Test
    void officialFallbackUnderPreferredPolicyRemainsReusableUnderOfficialOnly() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("official-fallback-trust");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        AtomicInteger configuredAttempts = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader fallbackDownloads = (assetUrl, outputFile, maxBytes) -> {
            if (assetUrl.startsWith(CONFIGURED_A + "/")) {
                configuredAttempts.incrementAndGet();
                throw new IOException("configured mirror unavailable");
            }
            writeReleaseArtifact(outputFile, archive, digest);
        };
        MCEFDownloader preferred = new MCEFDownloader(CONFIGURED_A, COMMIT_A, PLATFORM, policy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED), temporaryDirectory, fallbackDownloads, MCEFInstallerTestSupport.extractor("official-fallback-trust"));
        Path installed = preferred.installOrUpdate(false, true).installationDirectory();
        assertEquals(1, configuredAttempts.get());
        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-source=official"));

        AtomicInteger offlineAttempts = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader offline = (assetUrl, outputFile, maxBytes) -> {
            offlineAttempts.incrementAndGet();
            throw new IOException("offline");
        };
        MCEFDownloader officialOnly = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, policy(MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY), temporaryDirectory, offline, MCEFInstallerTestSupport.extractor("must-not-install"));

        MCEFDownloader.InstallationResult result = officialOnly.installOrUpdate(false, true);

        assertFalse(result.downloaded());
        assertEquals(installed, result.installationDirectory());
        assertEquals(0, offlineAttempts.get());
    }

    @Test
    void configuredGenerationRequiresOfficialChecksumRefetchBeforeOfficialReuse() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("configured-to-official");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader configured = releaseDownloader(CONFIGURED_A, MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY, archive, digest, new AtomicInteger(), new AtomicInteger(), "configured-to-official");
        Path installed = configured.installOrUpdate(false, true).installationDirectory();
        AtomicInteger offlineAttempts = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader offline = (assetUrl, outputFile, maxBytes) -> {
            offlineAttempts.incrementAndGet();
            throw new IOException("offline");
        };
        MCEFDownloader officialOffline = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, policy(MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY), temporaryDirectory, offline, MCEFInstallerTestSupport.extractor("must-not-install"));
        assertThrows(IOException.class, () -> officialOffline.installOrUpdate(false, true));
        assertEquals(1, offlineAttempts.get());

        AtomicInteger checksumDownloads = new AtomicInteger();
        AtomicInteger archiveDownloads = new AtomicInteger();
        MCEFDownloader official = releaseDownloader(MCEFDownloader.OFFICIAL_MIRROR, MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY, archive, digest, checksumDownloads, archiveDownloads, "must-not-install");

        MCEFDownloader.InstallationResult result = official.installOrUpdate(false, true);

        assertFalse(result.downloaded());
        assertEquals(installed, result.installationDirectory());
        assertEquals(1, checksumDownloads.get());
        assertEquals(0, archiveDownloads.get());
        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-source=official"));
    }

    @Test
    void configuredSourceChangeRejectsOfflineReuseAndCanReverifyMatchingContent() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("configured-source-change");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader sourceA = releaseDownloader(CONFIGURED_A, MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY, archive, digest, new AtomicInteger(), new AtomicInteger(), "configured-source-change");
        Path installed = sourceA.installOrUpdate(false, true).installationDirectory();
        AtomicInteger offlineAttempts = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader offline = (assetUrl, outputFile, maxBytes) -> {
            offlineAttempts.incrementAndGet();
            throw new IOException("offline");
        };
        MCEFDownloader sourceBOffline = new MCEFDownloader(CONFIGURED_B, COMMIT_A, PLATFORM, policy(MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY), temporaryDirectory, offline, MCEFInstallerTestSupport.extractor("must-not-install"));

        assertThrows(IOException.class, () -> sourceBOffline.installOrUpdate(false, true));
        assertEquals(1, offlineAttempts.get());

        AtomicInteger checksumDownloads = new AtomicInteger();
        AtomicInteger archiveDownloads = new AtomicInteger();
        MCEFDownloader sourceBOnline = releaseDownloader(CONFIGURED_B, MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY, archive, digest, checksumDownloads, archiveDownloads, "must-not-install");
        MCEFDownloader.InstallationResult result = sourceBOnline.installOrUpdate(false, true);

        assertFalse(result.downloaded());
        assertEquals(installed, result.installationDirectory());
        assertEquals(1, checksumDownloads.get());
        assertEquals(0, archiveDownloads.get());
        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-source=" + MCEFDownloadMirror.parse(CONFIGURED_B).checksumSourceId()));
    }

    @Test
    void checksumSourceUpgradeAtTheLeaseTokenLimitReusesOneSuccessfulPrune() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("lease-limit-source-change");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader sourceA = releaseDownloader(CONFIGURED_A, MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY, archive, digest, new AtomicInteger(), new AtomicInteger(), "lease-limit-source-change");
        Path installed = sourceA.installOrUpdate(false, true).installationDirectory();

        try (LockedLeaseTokens ignored = LockedLeaseTokens.acquire(installed, MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS - 1)) {
            assertEquals(MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS, leaseEntryCount(installed));
            AtomicInteger checksumDownloads = new AtomicInteger();
            AtomicInteger archiveDownloads = new AtomicInteger();
            MCEFDownloader sourceB = releaseDownloader(CONFIGURED_B, MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY, archive, digest, checksumDownloads, archiveDownloads, "must-not-install");

            MCEFDownloader.InstallationResult result = sourceB.installOrUpdate(false, true);

            assertFalse(result.downloaded());
            assertEquals(installed, result.installationDirectory());
            assertEquals(1, checksumDownloads.get());
            assertEquals(0, archiveDownloads.get());
            assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-source=" + MCEFDownloadMirror.parse(CONFIGURED_B).checksumSourceId()));
        }
    }

    @Test
    void legacyVerifiedStateRequiresOnlineReverificationBeforeMigration() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("legacy-state-migration");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader initial = releaseDownloader(MCEFDownloader.OFFICIAL_MIRROR, MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY, archive, digest, new AtomicInteger(), new AtomicInteger(), "legacy-state-migration");
        Path installed = initial.installOrUpdate(false, true).installationDirectory();
        rewriteAsLegacyVerifiedState(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE));
        rewriteAsLegacyVerifiedState(selectorFile());
        AtomicInteger offlineAttempts = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader offline = (assetUrl, outputFile, maxBytes) -> {
            offlineAttempts.incrementAndGet();
            throw new IOException("offline");
        };
        MCEFDownloader offlineDownloader = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, policy(MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY), temporaryDirectory, offline, MCEFInstallerTestSupport.extractor("must-not-install"));

        assertThrows(IOException.class, () -> offlineDownloader.installOrUpdate(false, true));
        assertEquals(1, offlineAttempts.get());

        AtomicInteger checksumDownloads = new AtomicInteger();
        AtomicInteger archiveDownloads = new AtomicInteger();
        MCEFDownloader migration = releaseDownloader(MCEFDownloader.OFFICIAL_MIRROR, MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY, archive, digest, checksumDownloads, archiveDownloads, "must-not-install");
        MCEFDownloader.InstallationResult result = migration.installOrUpdate(false, true);

        assertFalse(result.downloaded());
        assertEquals(installed, result.installationDirectory());
        assertEquals(1, checksumDownloads.get());
        assertEquals(0, archiveDownloads.get());
        String migrated = Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE));
        assertTrue(migrated.contains("format-version=3"));
        assertTrue(migrated.contains("checksum-verification=checksum-verified"));
        assertTrue(migrated.contains("checksum-source=official"));
        assertFalse(migrated.contains("archive-authentication"));
    }

    @Test
    void malformedChecksumSourceStateIsRejectedAndRefetched() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("malformed-source");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader initial = releaseDownloader(MCEFDownloader.OFFICIAL_MIRROR, MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY, archive, digest, new AtomicInteger(), new AtomicInteger(), "malformed-source");
        Path malformedGeneration = initial.installOrUpdate(false, true).installationDirectory();
        replaceChecksumSource(malformedGeneration.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE), "not-a-valid-source");
        replaceChecksumSource(selectorFile(), "not-a-valid-source");
        AtomicInteger checksumDownloads = new AtomicInteger();
        AtomicInteger archiveDownloads = new AtomicInteger();
        MCEFDownloader replacement = releaseDownloader(MCEFDownloader.OFFICIAL_MIRROR, MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY, archive, digest, checksumDownloads, archiveDownloads, "replacement");

        MCEFDownloader.InstallationResult result = replacement.installOrUpdate(false, true);

        assertTrue(result.downloaded());
        assertNotEquals(malformedGeneration, result.installationDirectory());
        assertEquals(1, checksumDownloads.get());
        assertEquals(1, archiveDownloads.get());
        assertEquals("replacement", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
    }

    @Test
    void interruptedConfiguredToOfficialReverificationRecoversTheOfficialSource() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("source-recovery");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader configured = releaseDownloader(CONFIGURED_A, MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY, archive, digest, new AtomicInteger(), new AtomicInteger(), "source-recovery");
        Path installed = configured.installOrUpdate(false, true).installationDirectory();
        MCEFInstallationTransaction.MoveExecutor selectorFailure = (source, target, replaceExisting) -> {
            if (target.getFileName().toString().endsWith(".mcef-current.properties")) {
                throw new IOException("simulated interrupted source selector rewrite");
            }
            MCEFInstallationTransaction.moveWithAtomicFallback(source, target, replaceExisting);
        };

        try (MCEFInstallationTransaction interrupted = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, selectorFailure, failure -> {})) {
            Path matching = interrupted.findUsableInstallation(digest, false, false);
            assertEquals(installed, matching);
            assertThrows(IOException.class, () -> interrupted.markChecksumVerified(matching, digest, MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE));
        }

        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-source=official"));
        assertTrue(Files.readString(selectorFile()).contains("checksum-source=" + MCEFDownloadMirror.parse(CONFIGURED_A).checksumSourceId()));
        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            assertEquals(MCEFInstallationTransaction.RecoveryOutcome.COMMITTED, recovery.recover());
            assertEquals(installed, recovery.findUsableInstallation(digest, Set.of(MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE)));
        }
        assertTrue(Files.readString(selectorFile()).contains("checksum-source=official"));
    }

    private MCEFDownloader releaseDownloader(String mirror, MCEFDownloader.MirrorPolicy mirrorPolicy, byte[] archive, String digest, AtomicInteger checksumDownloads, AtomicInteger archiveDownloads, String extractedVersion) {
        MCEFDownloader.ArtifactDownloader downloads = (assetUrl, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                checksumDownloads.incrementAndGet();
            } else {
                archiveDownloads.incrementAndGet();
            }
            writeReleaseArtifact(outputFile, archive, digest);
        };
        return new MCEFDownloader(mirror, COMMIT_A, PLATFORM, policy(mirrorPolicy), temporaryDirectory, downloads, MCEFInstallerTestSupport.extractor(extractedVersion));
    }

    private static void writeReleaseArtifact(java.io.File outputFile, byte[] archive, String digest) throws IOException {
        if (outputFile.getName().endsWith(".sha256")) {
            MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), digest);
        } else {
            Files.write(outputFile.toPath(), archive);
        }
    }

    private static MCEFDownloader.DownloadPolicy policy(MCEFDownloader.MirrorPolicy mirrorPolicy) {
        MCEFDownloader.DownloadPolicy defaults = MCEFDownloader.DownloadPolicy.defaults();
        return new MCEFDownloader.DownloadPolicy(mirrorPolicy, defaults.enforceChecksums(), defaults.connectTimeoutMs(), defaults.readTimeoutMs(), defaults.maxArchiveBytes(), defaults.maxChecksumBytes(), defaults.maxExtractedBytes());
    }

    private static void rewriteAsLegacyVerifiedState(Path stateFile) throws IOException {
        String state = Files.readString(stateFile);
        state = state.replace("format-version=3\n", "format-version=2\n");
        state = state.replace("checksum-verification=checksum-verified\nchecksum-source=official\n", "archive-authentication=authenticated\n");
        Files.writeString(stateFile, state);
    }

    private static void replaceChecksumSource(Path stateFile, String replacement) throws IOException {
        String state = Files.readString(stateFile);
        Files.writeString(stateFile, state.replaceFirst("checksum-source=[^\\n]+", "checksum-source=" + replacement));
    }

    private static long leaseEntryCount(Path generation) throws IOException {
        try (var entries = Files.list(generation.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME))) {
            return entries.count();
        }
    }

    private static final class LockedLeaseTokens implements AutoCloseable {
        private final List<LockedLeaseToken> tokens = new ArrayList<>();

        private static LockedLeaseTokens acquire(Path generation, int tokenCount) throws IOException {
            LockedLeaseTokens acquired = new LockedLeaseTokens();
            try {
                Path leaseDirectory = generation.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
                for (int index = 0; index < tokenCount; index++) {
                    acquired.acquireToken(leaseDirectory.resolve(UUID.randomUUID() + ".lease"));
                }
                return acquired;
            } catch (IOException | RuntimeException failure) {
                try {
                    acquired.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private void acquireToken(Path token) throws IOException {
            FileChannel channel = FileChannel.open(token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                tokens.add(new LockedLeaseToken(channel, channel.lock()));
            } catch (IOException | RuntimeException failure) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                Files.deleteIfExists(token);
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int index = tokens.size() - 1; index >= 0; index--) {
                try {
                    tokens.get(index).close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record LockedLeaseToken(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private Path selectorFile() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-current.properties");
    }
}
