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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_B;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.OLD_DIGEST;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.PLATFORM;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.SHA256_COMMIT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFDownloaderTransactionTest extends MCEFInstallerTestBase {
    @TempDir
    Path temporaryDirectory;

    @Test
    void promotionPublishesImmutableGenerationWithoutReplacingLegacyInstallation() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("new");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFInstallerTestSupport.writeLegacyInstallation(temporaryDirectory, "old", OLD_DIGEST);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("new"));

        MCEFDownloader.InstallationResult result = downloader.installOrUpdate(false, true);

        assertTrue(result.downloaded());
        assertEquals("new", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertEquals("old", MCEFInstallerTestSupport.readVersion(temporaryDirectory.resolve(PLATFORM.getNormalizedName())));
        assertNotEquals(temporaryDirectory.resolve(PLATFORM.getNormalizedName()), result.installationDirectory());
        assertTrue(result.installationDirectory().startsWith(generationsDirectory().toRealPath()));
        assertTrue(Files.readString(selectorFile()).contains("java-cef-commit=" + COMMIT_A));
        assertTrue(Files.readString(selectorFile()).contains("archive-sha256=" + digest));
        assertTrue(Files.readString(selectorFile()).contains("checksum-verification=checksum-verified"));
        assertTrue(Files.readString(selectorFile()).contains("checksum-source=official"));
        assertTrue(Files.readString(result.installationDirectory().resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("archive-sha256=" + digest));
        assertTrue(Files.readString(result.installationDirectory().resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("manifest-sha256="));
        assertEquals(OLD_DIGEST, Files.readString(legacyChecksum()).substring(0, 64));
        assertNoActiveTransactions();
    }

    @Test
    void sha256ObjectIdFlowsThroughManifestGenerationStateAndOfflineReuse() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("sha256-object-id");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, SHA256_COMMIT, archive, digest, MCEFInstallerTestSupport.extractor("sha256-object-id", SHA256_COMMIT));

        MCEFDownloader.InstallationResult installed = first.installOrUpdate(false, true);
        MCEFInstallationState.StateRecord selection = MCEFInstallationState.read(selectorFile());
        MCEFInstallationState.StateRecord generation = MCEFInstallationState.read(installed.installationDirectory().resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE));
        MCEFDistributionManifest.ManifestIdentity manifest = MCEFDistributionManifest.validatePublished(installed.installationDirectory(), PLATFORM, SHA256_COMMIT);

        assertTrue(installed.downloaded());
        assertTrue(installed.installationDirectory().getFileName().toString().startsWith(SHA256_COMMIT + "-" + digest + "-"));
        assertEquals(SHA256_COMMIT, selection.javaCefCommit());
        assertEquals(SHA256_COMMIT, generation.javaCefCommit());
        assertEquals(SHA256_COMMIT, manifest.javaCefCommit());
        assertEquals(digest, selection.archiveDigest());
        assertEquals(digest, generation.archiveDigest());

        AtomicInteger downloadCount = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader offline = (urlTemplate, outputFile, maxBytes) -> {
            downloadCount.incrementAndGet();
            throw new IOException("the exact SHA-256 object-ID generation must be reusable offline");
        };
        MCEFDownloader second = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, SHA256_COMMIT, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, offline, MCEFInstallerTestSupport.extractor("unexpected", SHA256_COMMIT));

        MCEFDownloader.InstallationResult reused = second.installOrUpdate(false, true);

        assertFalse(reused.downloaded());
        assertEquals(installed.installationDirectory(), reused.installationDirectory());
        assertEquals(0, downloadCount.get());
    }

    @Test
    void matchingContentBoundGenerationAvoidsArchiveDownload() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("same");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("same"));
        Path installed = first.installOrUpdate(false, true).installationDirectory();
        AtomicInteger downloadCount = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader unavailableNetwork = (urlTemplate, outputFile, maxBytes) -> {
            downloadCount.incrementAndGet();
            throw new IOException("trusted generation must start without network access");
        };
        MCEFDownloader second = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, unavailableNetwork, MCEFInstallerTestSupport.extractor("unexpected"));

        MCEFDownloader.InstallationResult result = second.installOrUpdate(false, true);

        assertFalse(result.downloaded());
        assertEquals(installed, result.installationDirectory());
        assertEquals(0, downloadCount.get());
    }

    @Test
    void damagedSelectedGenerationIsNeverBlessedByMatchingChecksum() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("repair");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("first"));
        Path damaged = first.installOrUpdate(false, true).installationDirectory();
        Files.delete(damaged.resolve("jcef_app.app/Contents/Java/libjcef.dylib"));
        MCEFDownloader repair = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("repaired"));

        MCEFDownloader.InstallationResult result = repair.installOrUpdate(false, true);

        assertTrue(result.downloaded());
        assertNotEquals(damaged, result.installationDirectory());
        assertEquals("repaired", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertFalse(MCEFInstallationTransaction.isUsableInstallation(damaged, PLATFORM));
    }

    @Test
    void compatibilityChecksumCannotBlessUntouchedLegacyAfterGenerationDamage() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("current");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFInstallerTestSupport.writeLegacyInstallation(temporaryDirectory, "legacy", OLD_DIGEST);
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("current"));
        Path damaged = first.installOrUpdate(false, true).installationDirectory();
        Files.delete(damaged.resolve("jcef_app.app/Contents/Java/libjcef.dylib"));
        MCEFDownloader repair = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("repaired"));

        MCEFDownloader.InstallationResult result = repair.installOrUpdate(false, true);

        assertTrue(result.downloaded());
        assertEquals("repaired", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertEquals("legacy", MCEFInstallerTestSupport.readVersion(temporaryDirectory.resolve(PLATFORM.getNormalizedName())));
    }

    @Test
    void macInstallationMissingSpecializedHelperIsIncomplete() throws Exception {
        Path installation = temporaryDirectory.resolve("incomplete-helper");
        MCEFInstallerTestSupport.writeRuntimeInstallation(installation, "missing-helper");
        Files.delete(installation.resolve("jcef_app.app/Contents/Frameworks/jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)"));

        assertFalse(MCEFInstallationTransaction.isUsableInstallation(installation, PLATFORM));
    }

    @Test
    void generationMetadataCannotBeReboundToAnotherTransactionIdentity() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("identity");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("identity"));
        Path installed = first.installOrUpdate(false, true).installationDirectory();
        String forgedIdentity = "transaction-id=" + UUID.randomUUID();
        Path manifest = installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE);
        Files.writeString(manifest, Files.readString(manifest).replaceFirst("transaction-id=[^\\n]+", forgedIdentity));
        Files.writeString(selectorFile(), Files.readString(selectorFile()).replaceFirst("transaction-id=[^\\n]+", forgedIdentity));
        MCEFDownloader localOnly = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, (archiveFile, outputDirectory) -> {
            throw new AssertionError("invalid generation metadata must not reach extraction");
        });

        IOException failure = assertThrows(IOException.class, () -> localOnly.installOrUpdate(true, true));

        assertTrue(failure.getMessage().contains("no complete local JCEF installation"));
    }

    @Test
    void competingCommitsPublishDistinctGenerationsAndKeepBothUsable() throws Exception {
        byte[] archiveA = MCEFInstallerTestSupport.archiveBytes("commit-a");
        byte[] archiveB = MCEFInstallerTestSupport.archiveBytes("commit-b");
        MCEFDownloader downloaderA = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archiveA, MCEFInstallerTestSupport.sha256(archiveA), MCEFInstallerTestSupport.extractor("a"));
        MCEFDownloader downloaderB = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_B, archiveB, MCEFInstallerTestSupport.sha256(archiveB), MCEFInstallerTestSupport.extractor("b", COMMIT_B));

        Path generationA = downloaderA.installOrUpdate(false, true).installationDirectory();
        Path generationB = downloaderB.installOrUpdate(false, true).installationDirectory();

        assertNotEquals(generationA, generationB);
        assertEquals("a", MCEFInstallerTestSupport.readVersion(generationA));
        assertEquals("b", MCEFInstallerTestSupport.readVersion(generationB));
        assertTrue(MCEFInstallationTransaction.isUsableInstallation(generationA, PLATFORM));
        assertTrue(MCEFInstallationTransaction.isUsableInstallation(generationB, PLATFORM));
        assertTrue(Files.readString(selectorFile()).contains("java-cef-commit=" + COMMIT_B));
    }

    @Test
    void deleteFalseRetainsByteIdenticalArchive() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("retained");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("retained"));
        Path outside = temporaryDirectory.resolve("outside-archive.txt");
        Files.writeString(outside, "unchanged", StandardCharsets.UTF_8);
        Path retainedArchive = temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz");
        Files.createSymbolicLink(retainedArchive, outside);

        downloader.installOrUpdate(false, false);

        assertArrayEquals(archive, Files.readAllBytes(retainedArchive));
        assertFalse(Files.isSymbolicLink(retainedArchive));
        assertEquals("unchanged", Files.readString(outside, StandardCharsets.UTF_8));
    }

    @Test
    void optionalChecksumInstallUsesOneArchiveIdentityAcrossAbaPathReplacementAndRetention() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("identity-stable-modern");
        byte[] replacement = MCEFInstallerTestSupport.archiveBytes("replacement-modern");
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, null, optionalChecksums(), MCEFInstallerTestSupport.abaPathSwapExtractor(temporaryDirectory, archive, replacement, "identity-stable-modern"));

        MCEFDownloader.InstallationResult result = downloader.installOrUpdate(false, false);

        assertEquals("identity-stable-modern", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertArrayEquals(archive, Files.readAllBytes(temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz")));
        assertNoActiveTransactions();
    }

    @Test
    void deleteTrueRemovesPreviouslyRetainedArchiveAfterSuccessfulReplacement() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("replacement");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        Path retainedArchive = temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz");
        Files.writeString(retainedArchive, "previous archive", StandardCharsets.UTF_8);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("replacement"));

        downloader.installOrUpdate(false, true);

        assertFalse(Files.exists(retainedArchive));
    }

    @Test
    void retainedArchiveCleanupFailureDoesNotInvalidateCommittedGeneration() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("cleanup-failure");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        Path unsafeRetainedArchive = temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz");
        Files.createDirectory(unsafeRetainedArchive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("cleanup-failure"));

        MCEFDownloader.InstallationResult result = downloader.installOrUpdate(false, true);

        assertTrue(result.downloaded());
        assertEquals("cleanup-failure", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertTrue(Files.isDirectory(unsafeRetainedArchive));
    }

    @Test
    @SuppressWarnings("deprecation")
    void compatibilityPhaseApiUsesSelfContainedTransactionsAndHonorsDeleteFalse() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("compatibility");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("compatibility"));

        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();
        downloader.extractJavaCefBuild(false);

        Path selected = Path.of(System.getProperty("jcef.path"));
        assertEquals("compatibility", MCEFInstallerTestSupport.readVersion(selected));
        assertTrue(MCEFGenerationLeaseRegistry.isLeasedForTests(selected));
        assertArrayEquals(archive, Files.readAllBytes(temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz")));
        assertNoActiveTransactions();
    }

    @Test
    void fallbackKeepsChecksumAndArchiveOnOneMirrorAndRecreatesTheCandidate() throws Exception {
        String configuredBase = "https://mirror.example/private/releases";
        byte[] configuredArchive = MCEFInstallerTestSupport.archiveBytes("configured-invalid-manifest");
        byte[] officialArchive = MCEFInstallerTestSupport.archiveBytes("official-valid");
        String configuredDigest = MCEFInstallerTestSupport.sha256(configuredArchive);
        String officialDigest = MCEFInstallerTestSupport.sha256(officialArchive);
        List<String> requests = new ArrayList<>();
        AtomicInteger extractionCount = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader downloads = (assetUrl, outputFile, maxBytes) -> {
            requests.add(assetUrl);
            boolean configured = assetUrl.startsWith(configuredBase + "/");
            if (outputFile.getName().endsWith(".sha256")) {
                MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), configured ? configuredDigest : officialDigest);
            } else {
                Files.write(outputFile.toPath(), configured ? configuredArchive : officialArchive);
            }
        };
        MCEFDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            int attempt = extractionCount.incrementAndGet();
            Path installation = outputDirectory.toPath().resolve(PLATFORM.getNormalizedName());
            assertFalse(Files.exists(installation), "Each mirror retry must receive a newly prepared extraction tree");
            byte[] extractedArchive = archiveInput.readAllBytes();
            if (java.util.Arrays.equals(configuredArchive, extractedArchive)) {
                MCEFInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "configured-invalid-manifest", COMMIT_B);
            } else {
                assertArrayEquals(officialArchive, extractedArchive);
                MCEFInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "official-valid", COMMIT_A);
            }
            assertTrue(attempt <= 2);
        };
        MCEFDownloader downloader = new MCEFDownloader(configuredBase, COMMIT_A, PLATFORM, mirrorPolicy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED), temporaryDirectory, downloads, extractor);

        MCEFDownloader.InstallationResult result = downloader.installOrUpdate(false, true);

        String release = "/java-cef-" + COMMIT_A + "/" + PLATFORM.getNormalizedName() + ".tar.gz";
        assertEquals(List.of(configuredBase + release + ".sha256", configuredBase + release, MCEFDownloader.OFFICIAL_MIRROR + release + ".sha256", MCEFDownloader.OFFICIAL_MIRROR + release), requests);
        assertEquals(2, extractionCount.get());
        assertEquals("official-valid", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertNoActiveTransactions();
    }

    @Test
    void checksumMismatchRetriesTheCompletePairOnTheNextMirror() throws Exception {
        String configuredBase = "https://mirror.example/releases";
        byte[] configuredArchive = MCEFInstallerTestSupport.archiveBytes("configured-mismatch");
        byte[] officialArchive = MCEFInstallerTestSupport.archiveBytes("official-match");
        String officialDigest = MCEFInstallerTestSupport.sha256(officialArchive);
        List<String> requests = new ArrayList<>();
        AtomicInteger extractionCount = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader downloads = (assetUrl, outputFile, maxBytes) -> {
            requests.add(assetUrl);
            boolean configured = assetUrl.startsWith(configuredBase + "/");
            if (outputFile.getName().endsWith(".sha256")) {
                MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), configured ? "0".repeat(64) : officialDigest);
            } else {
                Files.write(outputFile.toPath(), configured ? configuredArchive : officialArchive);
            }
        };
        MCEFDownloader downloader = new MCEFDownloader(configuredBase, COMMIT_A, PLATFORM, mirrorPolicy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED), temporaryDirectory, downloads, (archiveInput, outputDirectory) -> {
            extractionCount.incrementAndGet();
            assertArrayEquals(officialArchive, archiveInput.readAllBytes());
            MCEFInstallerTestSupport.writeRuntimeInstallation(outputDirectory.toPath().resolve(PLATFORM.getNormalizedName()), "official-match");
        });

        MCEFDownloader.InstallationResult result = downloader.installOrUpdate(false, true);

        String release = "/java-cef-" + COMMIT_A + "/" + PLATFORM.getNormalizedName() + ".tar.gz";
        assertEquals(List.of(configuredBase + release + ".sha256", configuredBase + release, MCEFDownloader.OFFICIAL_MIRROR + release + ".sha256", MCEFDownloader.OFFICIAL_MIRROR + release), requests);
        assertEquals(1, extractionCount.get());
        assertEquals("official-match", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertNoActiveTransactions();
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedPhaseApiPinsChecksumAndArchiveToTheSameMirror() throws Exception {
        String configuredBase = "https://mirror.example/releases";
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("deprecated-pair");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        List<String> requests = new ArrayList<>();
        MCEFDownloader.ArtifactDownloader downloads = (assetUrl, outputFile, maxBytes) -> {
            requests.add(assetUrl);
            if (outputFile.getName().endsWith(".sha256")) {
                MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), digest);
            } else {
                Files.write(outputFile.toPath(), archive);
            }
        };
        MCEFDownloader downloader = new MCEFDownloader(configuredBase, COMMIT_A, PLATFORM, mirrorPolicy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED), temporaryDirectory, downloads, MCEFInstallerTestSupport.extractor("deprecated-pair"));

        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();
        downloader.extractJavaCefBuild(true);

        String release = configuredBase + "/java-cef-" + COMMIT_A + "/" + PLATFORM.getNormalizedName() + ".tar.gz";
        assertEquals(List.of(release + ".sha256", release), requests);
        assertNoActiveTransactions();
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedPhaseApiRestartsTheCompletePairAfterArchiveDownloadFailure() throws Exception {
        String configuredBase = "https://mirror.example/releases";
        byte[] configuredArchive = MCEFInstallerTestSupport.archiveBytes("configured-unavailable");
        byte[] officialArchive = MCEFInstallerTestSupport.archiveBytes("official-fallback");
        String configuredDigest = MCEFInstallerTestSupport.sha256(configuredArchive);
        String officialDigest = MCEFInstallerTestSupport.sha256(officialArchive);
        List<String> requests = new ArrayList<>();
        MCEFDownloader.ArtifactDownloader downloads = (assetUrl, outputFile, maxBytes) -> {
            requests.add(assetUrl);
            boolean configured = assetUrl.startsWith(configuredBase + "/");
            if (outputFile.getName().endsWith(".sha256")) {
                MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), configured ? configuredDigest : officialDigest);
            } else if (configured) {
                throw new IOException("configured archive is unavailable");
            } else {
                Files.write(outputFile.toPath(), officialArchive);
            }
        };
        MCEFDownloader downloader = new MCEFDownloader(configuredBase, COMMIT_A, PLATFORM, mirrorPolicy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED), temporaryDirectory, downloads, MCEFInstallerTestSupport.extractor("official-fallback"));

        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();
        downloader.extractJavaCefBuild(true);

        String configuredRelease = configuredBase + "/java-cef-" + COMMIT_A + "/" + PLATFORM.getNormalizedName() + ".tar.gz";
        String officialRelease = MCEFDownloader.OFFICIAL_MIRROR + "/java-cef-" + COMMIT_A + "/" + PLATFORM.getNormalizedName() + ".tar.gz";
        assertEquals(List.of(configuredRelease + ".sha256", configuredRelease, officialRelease + ".sha256", officialRelease), requests);
        assertEquals("official-fallback", MCEFInstallerTestSupport.readVersion(Path.of(System.getProperty("jcef.path"))));
        assertNoActiveTransactions();
    }

    @Test
    @SuppressWarnings("deprecation")
    void reusedMirrorFailureCannotBypassCompatibilitySessionCleanup() throws Exception {
        String configuredBase = "https://mirror.example/releases";
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("unavailable");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        IOException reusedFailure = new IOException("all archives unavailable");
        MCEFDownloader.ArtifactDownloader downloads = (assetUrl, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), digest);
            } else {
                throw reusedFailure;
            }
        };
        MCEFDownloader downloader = new MCEFDownloader(configuredBase, COMMIT_A, PLATFORM, mirrorPolicy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED), temporaryDirectory, downloads, MCEFInstallerTestSupport.extractor("must-not-install"));

        assertFalse(downloader.downloadJavaCefChecksum());
        assertSame(reusedFailure, assertThrows(IOException.class, downloader::downloadJavaCefBuild));

        assertNoActiveTransactions();
        try (MCEFDownloader.InstallationSession ignored = downloader.openInstallationSession()) {
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install.lock")));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedPhaseApiRetriesTheCompletePairAfterExtractedManifestFailure() throws Exception {
        String configuredBase = "https://mirror.example/releases";
        byte[] configuredArchive = MCEFInstallerTestSupport.archiveBytes("configured-invalid");
        byte[] officialArchive = MCEFInstallerTestSupport.archiveBytes("official-valid");
        String configuredDigest = MCEFInstallerTestSupport.sha256(configuredArchive);
        String officialDigest = MCEFInstallerTestSupport.sha256(officialArchive);
        List<String> requests = new ArrayList<>();
        AtomicInteger extractions = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader downloads = (assetUrl, outputFile, maxBytes) -> {
            requests.add(assetUrl);
            boolean configured = assetUrl.startsWith(configuredBase + "/");
            if (outputFile.getName().endsWith(".sha256")) {
                MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), configured ? configuredDigest : officialDigest);
            } else {
                Files.write(outputFile.toPath(), configured ? configuredArchive : officialArchive);
            }
        };
        MCEFDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            Path installation = outputDirectory.toPath().resolve(PLATFORM.getNormalizedName());
            byte[] extractedArchive = archiveInput.readAllBytes();
            if (java.util.Arrays.equals(configuredArchive, extractedArchive)) {
                MCEFInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "configured-invalid", COMMIT_B);
            } else {
                assertArrayEquals(officialArchive, extractedArchive);
                MCEFInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "official-valid", COMMIT_A);
            }
            extractions.incrementAndGet();
        };
        MCEFDownloader downloader = new MCEFDownloader(configuredBase, COMMIT_A, PLATFORM, mirrorPolicy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED), temporaryDirectory, downloads, extractor);

        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.downloadJavaCefBuild();
        downloader.extractJavaCefBuild(true);

        String configuredRelease = configuredBase + "/java-cef-" + COMMIT_A + "/" + PLATFORM.getNormalizedName() + ".tar.gz";
        String officialRelease = MCEFDownloader.OFFICIAL_MIRROR + "/java-cef-" + COMMIT_A + "/" + PLATFORM.getNormalizedName() + ".tar.gz";
        assertEquals(List.of(configuredRelease + ".sha256", configuredRelease, officialRelease + ".sha256", officialRelease), requests);
        assertEquals(2, extractions.get());
        assertEquals("official-valid", MCEFInstallerTestSupport.readVersion(Path.of(System.getProperty("jcef.path"))));
        assertNoActiveTransactions();
    }

    @Test
    @SuppressWarnings("deprecation")
    void standaloneExtractNeverTrustsLegacyChecksum() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("standalone");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        Path retainedArchive = temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz");
        Files.write(retainedArchive, archive);
        MCEFInstallerTestSupport.writeChecksum(legacyChecksum(), digest);
        MCEFDownloader.ArtifactDownloader unexpectedDownload = (urlTemplate, outputFile, maxBytes) -> {
            throw new AssertionError("standalone extraction must use the retained archive");
        };
        MCEFDownloader downloader = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, unexpectedDownload, MCEFInstallerTestSupport.extractor("standalone"));

        IOException failure = assertThrows(IOException.class, () -> downloader.extractJavaCefBuild(true));

        assertTrue(failure.getMessage().contains("requires downloadJavaCefChecksum"));
        assertTrue(Files.exists(retainedArchive));
        assertEquals(digest, Files.readString(legacyChecksum()).substring(0, 64));
        assertNoActiveTransactions();
    }

    @Test
    @SuppressWarnings("deprecation")
    void oversizedRetainedArchiveIsRejectedBeforeTransactionCopy() throws Exception {
        Path retainedArchive = temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz");
        Files.write(retainedArchive, new byte[1_048_577]);
        MCEFDownloader.DownloadPolicy policy = new MCEFDownloader.DownloadPolicy(MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY, false, 1_000, 1_000, 1_048_576L, 512L, 1_048_576L);
        MCEFDownloader downloader = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, policy, temporaryDirectory, null, MCEFInstallerTestSupport.extractor("must-not-install"));

        IOException failure = assertThrows(IOException.class, () -> downloader.extractJavaCefBuild(true));

        assertTrue(failure.getMessage().contains("size"));
        assertEquals(1_048_577L, Files.size(retainedArchive));
        assertNoActiveTransactions();
    }

    @Test
    void normalInstallNeverSelectsLegacyWhenChecksumEndpointFails() throws Exception {
        MCEFInstallerTestSupport.writeLegacyInstallation(temporaryDirectory, "offline", OLD_DIGEST);
        AtomicInteger archiveDownloads = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader downloads = (urlTemplate, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                throw new IOException("offline checksum endpoint");
            }
            archiveDownloads.incrementAndGet();
            Files.write(outputFile.toPath(), MCEFInstallerTestSupport.archiveBytes("unused"));
        };
        MCEFDownloader downloader = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, optionalChecksums(), temporaryDirectory, downloads, MCEFInstallerTestSupport.extractor("unused"));

        MCEFDownloader.InstallationResult result = downloader.installOrUpdate(false, true);

        assertTrue(result.downloaded());
        assertFalse(Files.isSameFile(temporaryDirectory.resolve(PLATFORM.getNormalizedName()), result.installationDirectory()));
        assertEquals("unused", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertEquals(1, archiveDownloads.get());
        assertEquals(OLD_DIGEST, Files.readString(legacyChecksum()).substring(0, 64));
    }

    @Test
    void optionalChecksumNeverBlessesIncompleteDirectoryAndPreservesOldChecksum() throws Exception {
        Path incomplete = temporaryDirectory.resolve(PLATFORM.getNormalizedName());
        Files.createDirectories(incomplete);
        Files.writeString(incomplete.resolve("placeholder.txt"), "not a runtime", StandardCharsets.UTF_8);
        MCEFInstallerTestSupport.writeChecksum(legacyChecksum(), OLD_DIGEST);
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("unchecked");
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, null, optionalChecksums(), MCEFInstallerTestSupport.extractor("unchecked"));

        MCEFDownloader.InstallationResult result = downloader.installOrUpdate(false, true);

        assertTrue(result.downloaded());
        assertEquals("unchecked", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertTrue(Files.readString(selectorFile()).contains("archive-sha256=" + MCEFInstallerTestSupport.sha256(archive)));
        assertEquals(OLD_DIGEST, Files.readString(legacyChecksum()).substring(0, 64));
        assertFalse(MCEFInstallationTransaction.isUsableInstallation(incomplete, PLATFORM));
    }

    @Test
    void oversizedArchiveIsRejectedBeforeExtractorWhenChecksumsAreOptional() throws Exception {
        byte[] oversized = new byte[1_048_577];
        AtomicBoolean extractorCalled = new AtomicBoolean();
        MCEFDownloader.DownloadPolicy policy = new MCEFDownloader.DownloadPolicy(MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY, false, 1_000, 1_000, 1_048_576L, 512L, 1_048_576L);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, oversized, null, policy, (archive, outputDirectory) -> extractorCalled.set(true));

        IOException failure = assertThrows(IOException.class, () -> downloader.installOrUpdate(false, true));

        assertTrue(failure.getMessage().contains("size"));
        assertFalse(extractorCalled.get());
        assertFalse(Files.exists(selectorFile()));
    }

    @Test
    void validChecksumMismatchStillFailsWhenChecksumEnforcementIsDisabled() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("mismatch");
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, "0".repeat(64), optionalChecksums(), MCEFInstallerTestSupport.extractor("must-not-install"));

        IOException failure = assertThrows(IOException.class, () -> downloader.installOrUpdate(false, true));

        assertTrue(failure.getMessage().contains("Checksum mismatch"));
        assertFalse(Files.exists(selectorFile()));
    }

    @Test
    void ambiguousOrWrongAssetChecksumIsRejected() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("checksum-format");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        AtomicInteger archiveDownloads = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader ambiguousDownloads = (urlTemplate, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                Files.writeString(outputFile.toPath(), digest + "  " + PLATFORM.getNormalizedName() + ".tar.gz\n" + "1".repeat(64) + "  other.tar.gz\n");
            } else {
                archiveDownloads.incrementAndGet();
                Files.write(outputFile.toPath(), archive);
            }
        };
        MCEFDownloader ambiguous = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, ambiguousDownloads, MCEFInstallerTestSupport.extractor("must-not-install"));

        assertThrows(IOException.class, () -> ambiguous.installOrUpdate(false, true));
        assertEquals(0, archiveDownloads.get());

        Path wrongAssetDirectory = temporaryDirectory.resolve("wrong-asset");
        MCEFDownloader.ArtifactDownloader wrongAssetDownloads = (urlTemplate, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                Files.writeString(outputFile.toPath(), digest + "  windows_amd64.tar.gz\n");
            } else {
                archiveDownloads.incrementAndGet();
                Files.write(outputFile.toPath(), archive);
            }
        };
        MCEFDownloader wrongAsset = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), wrongAssetDirectory, wrongAssetDownloads, MCEFInstallerTestSupport.extractor("must-not-install"));

        assertThrows(IOException.class, () -> wrongAsset.installOrUpdate(false, true));
        assertEquals(0, archiveDownloads.get());

        Path misleadingAssetDirectory = temporaryDirectory.resolve("misleading-asset");
        MCEFDownloader.ArtifactDownloader misleadingAssetDownloads = (urlTemplate, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                Files.writeString(outputFile.toPath(), digest + "  windows_amd64.tar.gz " + PLATFORM.getNormalizedName() + ".tar.gz\n");
            } else {
                archiveDownloads.incrementAndGet();
                Files.write(outputFile.toPath(), archive);
            }
        };
        MCEFDownloader misleadingAsset = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), misleadingAssetDirectory, misleadingAssetDownloads, MCEFInstallerTestSupport.extractor("must-not-install"));

        assertThrows(IOException.class, () -> misleadingAsset.installOrUpdate(false, true));
        assertEquals(0, archiveDownloads.get());
    }

    @Test
    void malformedSelectorAndStaleTransactionCannotHideValidGeneration() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("recoverable");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("recoverable"));
        Path installed = first.installOrUpdate(false, true).installationDirectory();
        Files.writeString(selectorFile(), "format-version=broken\n", StandardCharsets.UTF_8);
        Path stale = transactionsDirectory().resolve("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("transaction.properties"), "malformed\n", StandardCharsets.UTF_8);
        MCEFDownloader recovery = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, (archiveFile, outputDirectory) -> {
            throw new AssertionError("skip-download must not extract");
        });

        MCEFDownloader.InstallationResult result = recovery.installOrUpdate(true, true);

        assertEquals(installed, result.installationDirectory());
        assertTrue(Files.readString(selectorFile()).contains("archive-sha256=" + digest));
        assertFalse(Files.exists(stale));
    }

    @Test
    void skipDownloadRejectsLegacyInstallationWithoutDurableChecksumSource() throws Exception {
        MCEFInstallerTestSupport.writeLegacyInstallation(temporaryDirectory, "legacy-only", OLD_DIGEST);
        Files.writeString(legacyChecksum(), "historical malformed checksum\n", StandardCharsets.UTF_8);
        MCEFDownloader.ArtifactDownloader unexpectedNetwork = (urlTemplate, outputFile, maxBytes) -> {
            throw new AssertionError("skip-download must not access the network");
        };
        MCEFDownloader downloader = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, unexpectedNetwork, MCEFInstallerTestSupport.extractor("unused"));

        IOException failure = assertThrows(IOException.class, () -> downloader.installOrUpdate(true, true));

        assertTrue(failure.getMessage().contains("no complete local JCEF installation"));
        assertEquals("legacy-only", MCEFInstallerTestSupport.readVersion(temporaryDirectory.resolve(PLATFORM.getNormalizedName())));
    }

    @Test
    void generationFootprintPermanentlyPreventsLegacyFallback() throws Exception {
        MCEFInstallerTestSupport.writeLegacyInstallation(temporaryDirectory, "legacy", OLD_DIGEST);
        Files.createDirectory(generationsDirectory());
        Files.writeString(selectorFile(), "format-version=1\n", StandardCharsets.UTF_8);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, MCEFInstallerTestSupport.archiveBytes("unused"), OLD_DIGEST, (archive, output) -> {
            throw new AssertionError("skip-download must not extract");
        });

        IOException failure = assertThrows(IOException.class, () -> downloader.installOrUpdate(true, true));

        assertTrue(failure.getMessage().contains("no complete local JCEF installation"));
        assertEquals("legacy", MCEFInstallerTestSupport.readVersion(temporaryDirectory.resolve(PLATFORM.getNormalizedName())));
    }

    @Test
    void uncheckedGenerationCannotStartOffline() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("unchecked-offline");
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, null, optionalChecksums(), MCEFInstallerTestSupport.extractor("unchecked-offline"));
        Path installed = first.installOrUpdate(false, true).installationDirectory();
        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-verification=unchecked"));
        AtomicInteger downloadAttempts = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader offline = (urlTemplate, outputFile, maxBytes) -> {
            downloadAttempts.incrementAndGet();
            throw new IOException("offline");
        };
        MCEFDownloader second = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, offline, MCEFInstallerTestSupport.extractor("must-not-install"));

        assertThrows(IOException.class, () -> second.installOrUpdate(false, true));

        assertEquals(1, downloadAttempts.get());
    }

    @Test
    void onlineChecksumVerifiesUncheckedGenerationForOfflineReuse() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("unchecked-online");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, null, optionalChecksums(), MCEFInstallerTestSupport.extractor("unchecked-online"));
        Path installed = first.installOrUpdate(false, true).installationDirectory();
        AtomicInteger archiveDownloads = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader onlineChecksum = (urlTemplate, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                MCEFInstallerTestSupport.writeChecksum(outputFile.toPath(), digest);
            } else {
                archiveDownloads.incrementAndGet();
            }
        };
        MCEFDownloader second = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, onlineChecksum, MCEFInstallerTestSupport.extractor("must-not-install"));

        MCEFDownloader.InstallationResult result = second.installOrUpdate(false, true);

        assertFalse(result.downloaded());
        assertEquals(installed, result.installationDirectory());
        assertEquals(0, archiveDownloads.get());
        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-verification=checksum-verified"));
        assertTrue(Files.readString(installed.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE)).contains("checksum-source=official"));
        assertTrue(Files.readString(selectorFile()).contains("checksum-verification=checksum-verified"));

        AtomicInteger offlineAttempts = new AtomicInteger();
        MCEFDownloader.ArtifactDownloader offline = (urlTemplate, outputFile, maxBytes) -> {
            offlineAttempts.incrementAndGet();
            throw new IOException("offline");
        };
        MCEFDownloader third = new MCEFDownloader(MCEFDownloader.OFFICIAL_MIRROR, COMMIT_A, PLATFORM, MCEFDownloader.DownloadPolicy.defaults(), temporaryDirectory, offline, MCEFInstallerTestSupport.extractor("must-not-install"));

        MCEFDownloader.InstallationResult offlineResult = third.installOrUpdate(false, true);

        assertFalse(offlineResult.downloaded());
        assertEquals(installed, offlineResult.installationDirectory());
        assertEquals(0, offlineAttempts.get());
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedPhaseStateCanBeExplicitlyAbandoned() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("abandoned");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("abandoned"));

        assertFalse(downloader.downloadJavaCefChecksum());
        downloader.abortJavaCefInstallation();
        downloader.abortJavaCefInstallation();

        assertNoActiveTransactions();
        try (MCEFDownloader.InstallationSession ignored = downloader.openInstallationSession()) {
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-install.lock")));
        }
    }

    @Test
    void generationAndSelectorCannotBeReboundToDifferentManifestBytes() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("manifest-binding");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, MCEFInstallerTestSupport.extractor("manifest-binding"));
        Path installed = first.installOrUpdate(false, true).installationDirectory();
        Path manifest = installed.resolve(MCEFDistributionManifest.FILE_NAME);
        Files.writeString(manifest, Files.readString(manifest) + " \n", StandardCharsets.UTF_8);
        MCEFDownloader localOnly = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, (archiveFile, outputDirectory) -> {
            throw new AssertionError("invalid manifest binding must not extract during skip-download");
        });

        IOException failure = assertThrows(IOException.class, () -> localOnly.installOrUpdate(true, true));

        assertTrue(failure.getMessage().contains("no complete local JCEF installation"));
    }

    @Test
    void stagedInstallationWithoutDistributionManifestIsNeverPublished() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("missing-manifest");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        MCEFDownloader.ArchiveExtractor missingManifest = (archiveFile, outputDirectory) -> {
            Path installation = outputDirectory.toPath().resolve(PLATFORM.getNormalizedName());
            MCEFInstallerTestSupport.writeRuntimeInstallation(installation, "missing-manifest");
            Files.delete(installation.resolve(MCEFDistributionManifest.FILE_NAME));
        };
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, missingManifest);

        assertThrows(IOException.class, () -> downloader.installOrUpdate(false, true));

        assertFalse(Files.exists(selectorFile()));
        assertNoActiveTransactions();
    }

    private MCEFDownloader.DownloadPolicy optionalChecksums() {
        MCEFDownloader.DownloadPolicy defaults = MCEFDownloader.DownloadPolicy.defaults();
        return new MCEFDownloader.DownloadPolicy(defaults.mirrorPolicy(), false, defaults.connectTimeoutMs(), defaults.readTimeoutMs(), defaults.maxArchiveBytes(), defaults.maxChecksumBytes(), defaults.maxExtractedBytes());
    }

    private MCEFDownloader.DownloadPolicy mirrorPolicy(MCEFDownloader.MirrorPolicy mirrorPolicy) {
        MCEFDownloader.DownloadPolicy defaults = MCEFDownloader.DownloadPolicy.defaults();
        return new MCEFDownloader.DownloadPolicy(mirrorPolicy, defaults.enforceChecksums(), defaults.connectTimeoutMs(), defaults.readTimeoutMs(), defaults.maxArchiveBytes(), defaults.maxChecksumBytes(), defaults.maxExtractedBytes());
    }

    private Path generationsDirectory() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-generations");
    }

    private Path transactionsDirectory() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-transactions");
    }

    private Path selectorFile() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-current.properties");
    }

    private Path legacyChecksum() {
        return temporaryDirectory.resolve(PLATFORM.getNormalizedName() + ".tar.gz.sha256");
    }

    private void assertNoActiveTransactions() throws IOException {
        Path transactions = transactionsDirectory();
        if (!Files.exists(transactions)) {
            return;
        }
        try (var entries = Files.list(transactions)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }
}
