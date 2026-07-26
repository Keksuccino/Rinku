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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.cinemamod.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static com.cinemamod.mcef.MCEFInstallerTestSupport.PLATFORM;
import static com.cinemamod.mcef.MCEFInstallerTestSupport.SHA256_COMMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFDistributionManifestTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesExactRuntimeInventoryForAllSixPlatforms() throws Exception {
        assertEquals(50_000, MCEFDistributionManifest.MAX_RUNTIME_FILES);
        for (MCEFPlatform platform : MCEFPlatform.values()) {
            Path installation = temporaryDirectory.resolve(platform.getNormalizedName());
            MCEFInstallerTestSupport.writeRuntimeInstallation(installation, platform, "valid", COMMIT_A);

            MCEFDistributionManifest.ManifestIdentity identity = MCEFDistributionManifest.validate(installation, platform, COMMIT_A);

            assertEquals(platform.getNormalizedName(), identity.target());
            assertEquals(platform.getNormalizedName(), identity.archiveRoot());
            assertEquals(17, identity.javaRelease());
            assertEquals(COMMIT_A, identity.javaCefCommit());
            assertTrue(identity.sha256().matches("[0-9a-f]{64}"));
        }
    }

    @Test
    void validatesSha256ObjectIdOnlyForTheExactRequestedCommit() throws Exception {
        Path installation = temporaryDirectory.resolve("sha256-object-id");
        MCEFInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "sha256-object-id", SHA256_COMMIT);

        MCEFDistributionManifest.ManifestIdentity identity = MCEFDistributionManifest.validate(installation, PLATFORM, SHA256_COMMIT);

        assertEquals(SHA256_COMMIT, identity.javaCefCommit());
        IOException mismatch = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(installation, PLATFORM, COMMIT_A));
        assertTrue(mismatch.getMessage().contains("does not match"));
    }

    @Test
    void independentlyEnforcesCanonicalTopLevelFilesAndDirectories() throws Exception {
        String missingVersion = "canonical-missing";
        Path missing = temporaryDirectory.resolve(missingVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(missing, missingVersion);
        Map<String, byte[]> missingRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, missingVersion);
        Map<String, byte[]> missingFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, missingRuntime, missingVersion));
        missingFiles.remove("CEF-LICENSE.txt");
        Files.delete(missing.resolve("CEF-LICENSE.txt"));
        writeManifest(missing, PLATFORM, missingRuntime, missingFiles, MCEFInstallerTestSupport.distributionDirectories(missingFiles));
        IOException missingFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(missing, PLATFORM, COMMIT_A));
        assertTrue(missingFailure.getMessage().contains("missing canonical top-level"), missingFailure.getMessage());

        String extraVersion = "canonical-extra";
        Path extra = temporaryDirectory.resolve(extraVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(extra, extraVersion);
        Map<String, byte[]> extraRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, extraVersion);
        Map<String, byte[]> extraFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, extraRuntime, extraVersion));
        extraFiles.put("custom-payload.bin", "custom".getBytes(StandardCharsets.UTF_8));
        Files.write(extra.resolve("custom-payload.bin"), extraFiles.get("custom-payload.bin"));
        writeManifest(extra, PLATFORM, extraRuntime, extraFiles, MCEFInstallerTestSupport.distributionDirectories(extraFiles));
        IOException extraFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(extra, PLATFORM, COMMIT_A));
        assertTrue(extraFailure.getMessage().contains("unexpected top-level"), extraFailure.getMessage());

        String wrongKindVersion = "canonical-wrong-kind";
        Path wrongKind = temporaryDirectory.resolve(wrongKindVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(wrongKind, wrongKindVersion);
        Map<String, byte[]> wrongKindRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, wrongKindVersion);
        Map<String, byte[]> wrongKindFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, wrongKindRuntime, wrongKindVersion));
        wrongKindFiles.remove("README.txt");
        Files.delete(wrongKind.resolve("README.txt"));
        Files.createDirectory(wrongKind.resolve("README.txt"));
        List<String> wrongKindDirectories = new ArrayList<>(MCEFInstallerTestSupport.distributionDirectories(wrongKindFiles));
        wrongKindDirectories.add("README.txt");
        wrongKindDirectories.sort(String::compareTo);
        writeManifest(wrongKind, PLATFORM, wrongKindRuntime, wrongKindFiles, wrongKindDirectories);
        IOException wrongKindFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(wrongKind, PLATFORM, COMMIT_A));
        assertTrue(wrongKindFailure.getMessage().contains("wrong kind"), wrongKindFailure.getMessage());
    }

    @Test
    void rejectsEmptyCanonicalTopLevelFileAndDirectory() throws Exception {
        String emptyFileVersion = "canonical-empty-file";
        Path emptyFile = temporaryDirectory.resolve(emptyFileVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(emptyFile, emptyFileVersion);
        Map<String, byte[]> emptyFileRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, emptyFileVersion);
        Map<String, byte[]> emptyFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, emptyFileRuntime, emptyFileVersion));
        emptyFiles.put("README.txt", new byte[0]);
        Files.write(emptyFile.resolve("README.txt"), new byte[0]);
        writeManifest(emptyFile, PLATFORM, emptyFileRuntime, emptyFiles, MCEFInstallerTestSupport.distributionDirectories(emptyFiles));
        IOException emptyFileFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(emptyFile, PLATFORM, COMMIT_A));
        assertTrue(emptyFileFailure.getMessage().contains("missing, empty, or has the wrong kind"), emptyFileFailure.getMessage());

        String emptyDocsVersion = "canonical-empty-docs";
        Path emptyDocs = temporaryDirectory.resolve(emptyDocsVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(emptyDocs, emptyDocsVersion);
        Map<String, byte[]> emptyDocsRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, emptyDocsVersion);
        Map<String, byte[]> emptyDocsFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, emptyDocsRuntime, emptyDocsVersion));
        emptyDocsFiles.remove("docs/index.html");
        Files.delete(emptyDocs.resolve("docs/index.html"));
        List<String> emptyDocsDirectories = new ArrayList<>(MCEFInstallerTestSupport.distributionDirectories(emptyDocsFiles));
        emptyDocsDirectories.add("docs");
        emptyDocsDirectories.sort(String::compareTo);
        writeManifest(emptyDocs, PLATFORM, emptyDocsRuntime, emptyDocsFiles, emptyDocsDirectories);
        IOException emptyDocsFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(emptyDocs, PLATFORM, COMMIT_A));
        assertTrue(emptyDocsFailure.getMessage().contains("contains no regular files"), emptyDocsFailure.getMessage());
    }

    @Test
    void everyLinuxAndWindowsTargetRequiresTheCanonicalEnUsLocale() throws Exception {
        for (MCEFPlatform platform : List.of(MCEFPlatform.LINUX_AMD64, MCEFPlatform.LINUX_ARM64, MCEFPlatform.WINDOWS_AMD64, MCEFPlatform.WINDOWS_ARM64)) {
            String version = "missing-en-us-" + platform.getNormalizedName();
            Path installation = temporaryDirectory.resolve(version);
            MCEFInstallerTestSupport.writeRuntimeInstallation(installation, platform, version, COMMIT_A);
            Map<String, byte[]> runtimeFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.runtimeFiles(platform, version));
            runtimeFiles.remove("locales/en-US.pak");
            Files.delete(installation.resolve("locales/en-US.pak"));
            Map<String, byte[]> distributionFiles = MCEFInstallerTestSupport.distributionFiles(platform, runtimeFiles, version);
            List<String> directories = new ArrayList<>(MCEFInstallerTestSupport.distributionDirectories(distributionFiles));
            directories.add("locales");
            directories.sort(String::compareTo);
            writeManifest(installation, platform, runtimeFiles, distributionFiles, directories);

            IOException failure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(installation, platform, COMMIT_A));
            assertTrue(failure.getMessage().contains("en-US locale"), platform + ": " + failure.getMessage());
        }
    }

    @Test
    void everyPlatformRejectsAMissingDeclaredRuntimeResource() throws Exception {
        for (MCEFPlatform platform : MCEFPlatform.values()) {
            Path installation = temporaryDirectory.resolve("missing-" + platform.getNormalizedName());
            Map<String, byte[]> runtimeFiles = MCEFInstallerTestSupport.runtimeFiles(platform, "missing");
            MCEFInstallerTestSupport.writeRuntimeInstallation(installation, platform, "missing", COMMIT_A);
            String removed = runtimeFiles.keySet().stream().sorted().findFirst().orElseThrow();
            Files.delete(installation.resolve(removed.replace('/', java.io.File.separatorChar)));

            assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(installation, platform, COMMIT_A), platform.getNormalizedName());
        }
    }

    @Test
    void platformRootMappingIncludesArchitectureSpecificVulkanAndDxFiles() throws Exception {
        for (MCEFPlatform platform : List.of(MCEFPlatform.LINUX_AMD64, MCEFPlatform.LINUX_ARM64)) {
            assertTrue(MCEFInstallerTestSupport.runtimeEntries(platform).contains("libvulkan.so.1"));
        }
        for (MCEFPlatform platform : List.of(MCEFPlatform.WINDOWS_AMD64, MCEFPlatform.WINDOWS_ARM64)) {
            assertTrue(MCEFInstallerTestSupport.runtimeEntries(platform).contains("vulkan-1.dll"));
        }
        assertTrue(MCEFInstallerTestSupport.runtimeEntries(MCEFPlatform.WINDOWS_AMD64).containsAll(List.of("dxcompiler.dll", "dxil.dll")));
        assertFalse(MCEFInstallerTestSupport.runtimeEntries(MCEFPlatform.WINDOWS_ARM64).contains("dxcompiler.dll"));
        assertFalse(MCEFInstallerTestSupport.runtimeEntries(MCEFPlatform.WINDOWS_ARM64).contains("dxil.dll"));

        MCEFPlatform linux = MCEFPlatform.LINUX_ARM64;
        Path optionalMinigbm = temporaryDirectory.resolve("optional-minigbm");
        MCEFInstallerTestSupport.writeRuntimeInstallation(optionalMinigbm, linux, "optional", COMMIT_A);
        Map<String, byte[]> runtimeFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.runtimeFiles(linux, "optional"));
        runtimeFiles.put("libminigbm.so", "optional-minigbm".getBytes(StandardCharsets.UTF_8));
        Files.write(optionalMinigbm.resolve("libminigbm.so"), runtimeFiles.get("libminigbm.so"));
        List<String> entries = new ArrayList<>(MCEFInstallerTestSupport.runtimeEntries(linux));
        entries.add("libminigbm.so");
        entries.sort(String::compareTo);
        Files.writeString(optionalMinigbm.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(linux, COMMIT_A, runtimeFiles, entries, "optional"), StandardCharsets.UTF_8);

        MCEFDistributionManifest.validate(optionalMinigbm, linux, COMMIT_A);
    }

    @Test
    void bothMacTargetsRejectEveryOmittedSignedAppRequirement() throws Exception {
        int caseIndex = 0;
        for (MCEFPlatform platform : List.of(MCEFPlatform.MACOS_AMD64, MCEFPlatform.MACOS_ARM64)) {
            Map<String, byte[]> completeRuntime = MCEFInstallerTestSupport.runtimeFiles(platform, "mac-contract");
            for (String omitted : completeRuntime.keySet()) {
                Path installation = temporaryDirectory.resolve("mac-contract-" + caseIndex++);
                MCEFInstallerTestSupport.writeRuntimeInstallation(installation, platform, "mac-contract", COMMIT_A);
                Files.delete(installation.resolve(omitted.replace('/', java.io.File.separatorChar)));
                Map<String, byte[]> incompleteRuntime = new LinkedHashMap<>(completeRuntime);
                incompleteRuntime.remove(omitted);
                Files.writeString(installation.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(platform, COMMIT_A, incompleteRuntime, "mac-contract"), StandardCharsets.UTF_8);

                assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(installation, platform, COMMIT_A), platform + ": " + omitted);
            }
        }
    }

    @Test
    void rejectsMissingExtraCorruptAndSymlinkedRuntimeFiles() throws Exception {
        Path missingManifest = temporaryDirectory.resolve("missing-manifest");
        MCEFInstallerTestSupport.writeRuntimeInstallation(missingManifest, "missing-manifest");
        Files.delete(missingManifest.resolve(MCEFDistributionManifest.FILE_NAME));
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(missingManifest, PLATFORM, COMMIT_A));

        Path extra = temporaryDirectory.resolve("extra");
        MCEFInstallerTestSupport.writeRuntimeInstallation(extra, "extra");
        MCEFInstallerTestSupport.writeNonempty(extra.resolve("jcef_app.app/Contents/Java/unexpected.jar"), "unexpected");
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(extra, PLATFORM, COMMIT_A));

        Path corrupt = temporaryDirectory.resolve("corrupt");
        MCEFInstallerTestSupport.writeRuntimeInstallation(corrupt, "corrupt");
        Files.writeString(corrupt.resolve("jcef_app.app/Contents/Java/libjcef.dylib"), "changed", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(corrupt, PLATFORM, COMMIT_A));

        Path symlinked = temporaryDirectory.resolve("symlinked");
        MCEFInstallerTestSupport.writeRuntimeInstallation(symlinked, "symlinked");
        Path runtimeFile = symlinked.resolve("jcef_app.app/Contents/Java/libjcef.dylib");
        Path outside = temporaryDirectory.resolve("outside-runtime");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        Files.delete(runtimeFile);
        Files.createSymbolicLink(runtimeFile, outside);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(symlinked, PLATFORM, COMMIT_A));
    }

    @Test
    void rejectsAnEmptyDirectoryTraversalBomb() throws Exception {
        Path installation = temporaryDirectory.resolve("directory-bomb");
        MCEFInstallerTestSupport.writeRuntimeInstallation(installation, "directory-bomb");
        Path emptyDirectories = installation.resolve("jcef_app.app/empty-directories");
        for (int index = 0; index < 4_097; index++) {
            Files.createDirectories(emptyDirectories.resolve(String.format("entry-%04d", index)));
        }

        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(installation, PLATFORM, COMMIT_A));
    }

    @Test
    void acceptsDeclaredEmptyFileAndExplicitDirectoryBelowCanonicalDocs() throws Exception {
        String version = "declared-empty";
        Path installation = temporaryDirectory.resolve(version);
        MCEFInstallerTestSupport.writeRuntimeInstallation(installation, version);
        Map<String, byte[]> runtimeFiles = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, version);
        Map<String, byte[]> distributionFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, runtimeFiles, version));
        distributionFiles.put("docs/empty.txt", new byte[0]);
        Files.write(installation.resolve("docs/empty.txt"), new byte[0]);
        Files.createDirectory(installation.resolve("docs/spare"));
        List<String> directories = new ArrayList<>(MCEFInstallerTestSupport.distributionDirectories(distributionFiles));
        directories.add("docs/spare");
        directories.sort(String::compareTo);
        Files.writeString(installation.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, runtimeFiles, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), distributionFiles, directories), StandardCharsets.UTF_8);

        MCEFDistributionManifest.validate(installation, PLATFORM, COMMIT_A);
    }

    @Test
    void rejectsUndeclaredMissingAndMismatchedDistributionEntries() throws Exception {
        Path extraFile = temporaryDirectory.resolve("distribution-extra-file");
        MCEFInstallerTestSupport.writeRuntimeInstallation(extraFile, "distribution-extra-file");
        Files.writeString(extraFile.resolve("unexpected.txt"), "unexpected", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(extraFile, PLATFORM, COMMIT_A));

        Path extraDirectory = temporaryDirectory.resolve("distribution-extra-directory");
        MCEFInstallerTestSupport.writeRuntimeInstallation(extraDirectory, "distribution-extra-directory");
        Files.createDirectory(extraDirectory.resolve("unexpected"));
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(extraDirectory, PLATFORM, COMMIT_A));

        Path missingFile = temporaryDirectory.resolve("distribution-missing-file");
        MCEFInstallerTestSupport.writeRuntimeInstallation(missingFile, "distribution-missing-file");
        Files.delete(missingFile.resolve("README.txt"));
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(missingFile, PLATFORM, COMMIT_A));

        String missingDirectoryVersion = "distribution-missing-directory";
        Path missingDirectory = temporaryDirectory.resolve(missingDirectoryVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(missingDirectory, missingDirectoryVersion);
        Map<String, byte[]> missingDirectoryRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, missingDirectoryVersion);
        Map<String, byte[]> missingDirectoryFiles = MCEFInstallerTestSupport.distributionFiles(PLATFORM, missingDirectoryRuntime, missingDirectoryVersion);
        List<String> missingDirectories = new ArrayList<>(MCEFInstallerTestSupport.distributionDirectories(missingDirectoryFiles));
        missingDirectories.add("declared-but-missing");
        missingDirectories.sort(String::compareTo);
        Files.writeString(missingDirectory.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, missingDirectoryRuntime, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), missingDirectoryFiles, missingDirectories), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(missingDirectory, PLATFORM, COMMIT_A));

        String checksumVersion = "checksum";
        Path wrongChecksum = temporaryDirectory.resolve("distribution-wrong-checksum");
        MCEFInstallerTestSupport.writeRuntimeInstallation(wrongChecksum, checksumVersion);
        Map<String, byte[]> checksumRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, checksumVersion);
        Map<String, byte[]> wrongDistribution = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, checksumRuntime, checksumVersion));
        wrongDistribution.put("README.txt", "CHECKSUM".getBytes(StandardCharsets.UTF_8));
        Files.writeString(wrongChecksum.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, checksumRuntime, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), wrongDistribution, MCEFInstallerTestSupport.distributionDirectories(wrongDistribution)), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(wrongChecksum, PLATFORM, COMMIT_A));
    }

    @Test
    void rejectsUnsortedCaseCollidingAndReservedDistributionPaths() throws Exception {
        String unsortedVersion = "unsorted-distribution-directories";
        Path unsorted = temporaryDirectory.resolve(unsortedVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(unsorted, unsortedVersion);
        Map<String, byte[]> unsortedRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, unsortedVersion);
        Map<String, byte[]> unsortedFiles = MCEFInstallerTestSupport.distributionFiles(PLATFORM, unsortedRuntime, unsortedVersion);
        List<String> reversedDirectories = new ArrayList<>(MCEFInstallerTestSupport.distributionDirectories(unsortedFiles));
        java.util.Collections.reverse(reversedDirectories);
        Files.writeString(unsorted.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, unsortedRuntime, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), unsortedFiles, reversedDirectories), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(unsorted, PLATFORM, COMMIT_A));

        String collisionVersion = "distribution-case-collision";
        Path collision = temporaryDirectory.resolve(collisionVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(collision, collisionVersion);
        Map<String, byte[]> collisionRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, collisionVersion);
        Map<String, byte[]> collisionFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, collisionRuntime, collisionVersion));
        collisionFiles.put("STRASSE.TXT", collisionVersion.getBytes(StandardCharsets.UTF_8));
        collisionFiles.put("straße.txt", collisionVersion.getBytes(StandardCharsets.UTF_8));
        Files.writeString(collision.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, collisionRuntime, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), collisionFiles, MCEFInstallerTestSupport.distributionDirectories(collisionFiles)), StandardCharsets.UTF_8);
        IOException collisionFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(collision, PLATFORM, COMMIT_A));
        assertTrue(collisionFailure.getMessage().contains("case-colliding"));

        String stateVersion = "reserved-state";
        Path reservedState = temporaryDirectory.resolve(stateVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(reservedState, stateVersion);
        Map<String, byte[]> stateRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, stateVersion);
        Map<String, byte[]> stateFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.distributionFiles(PLATFORM, stateRuntime, stateVersion));
        stateFiles.put(MCEFInstallationTransaction.GENERATION_STATE_FILE, "archive-owned".getBytes(StandardCharsets.UTF_8));
        Files.writeString(reservedState.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE), "archive-owned", StandardCharsets.UTF_8);
        Files.writeString(reservedState.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, stateRuntime, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), stateFiles, MCEFInstallerTestSupport.distributionDirectories(stateFiles)), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(reservedState, PLATFORM, COMMIT_A));

        Path reservedLease = temporaryDirectory.resolve("reserved-lease");
        MCEFInstallerTestSupport.writeRuntimeInstallation(reservedLease, "reserved-lease");
        Files.createDirectory(reservedLease.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME));
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(reservedLease, PLATFORM, COMMIT_A));
    }

    @Test
    void publishedValidationAccountsOnlyForExactInstallerOwnedPaths() throws Exception {
        Path published = temporaryDirectory.resolve("published-owned-paths");
        MCEFInstallerTestSupport.writeRuntimeInstallation(published, "published-owned-paths");
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validatePublished(published, PLATFORM, COMMIT_A));
        Files.writeString(published.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE), "state", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validatePublished(published, PLATFORM, COMMIT_A));
        Path leases = published.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        Files.createDirectory(leases);
        MCEFDistributionManifest.validatePublished(published, PLATFORM, COMMIT_A);

        Files.write(leases.resolve("123e4567-e89b-42d3-a456-426614174000.lease"), new byte[0]);
        MCEFDistributionManifest.validatePublished(published, PLATFORM, COMMIT_A);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(published, PLATFORM, COMMIT_A));

        Files.writeString(published.resolve("unexpected.txt"), "unexpected", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validatePublished(published, PLATFORM, COMMIT_A));

        Path malformedLease = temporaryDirectory.resolve("published-malformed-lease");
        MCEFInstallerTestSupport.writeRuntimeInstallation(malformedLease, "published-malformed-lease");
        Files.writeString(malformedLease.resolve(MCEFInstallationTransaction.GENERATION_STATE_FILE), "state", StandardCharsets.UTF_8);
        Path malformedLeaseDirectory = malformedLease.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME);
        Files.createDirectory(malformedLeaseDirectory);
        Files.write(malformedLeaseDirectory.resolve("not-a-token.lease"), new byte[0]);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validatePublished(malformedLease, PLATFORM, COMMIT_A));
    }

    @Test
    void runtimeMetadataMustMatchTheSharedDistributionSnapshot() throws Exception {
        String version = "shared-runtime-snapshot";
        Path installation = temporaryDirectory.resolve(version);
        MCEFInstallerTestSupport.writeRuntimeInstallation(installation, version);
        Map<String, byte[]> actualRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, version);
        Map<String, byte[]> mismatchedRuntime = new LinkedHashMap<>(actualRuntime);
        String changedPath = mismatchedRuntime.keySet().iterator().next();
        byte[] changedContents = mismatchedRuntime.get(changedPath).clone();
        changedContents[0] ^= 1;
        mismatchedRuntime.put(changedPath, changedContents);
        Map<String, byte[]> actualDistribution = MCEFInstallerTestSupport.distributionFiles(PLATFORM, actualRuntime, version);
        Files.writeString(installation.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, mismatchedRuntime, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), actualDistribution, MCEFInstallerTestSupport.distributionDirectories(actualDistribution)), StandardCharsets.UTF_8);

        IOException failure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(installation, PLATFORM, COMMIT_A));
        assertTrue(failure.getMessage().contains("runtime metadata does not match"), failure.getMessage());
    }

    @Test
    void rejectsWrongManifestIdentityAndAncillaryPlatformMapping() throws Exception {
        assertManifestMutationRejected("wrong-schema", json -> json.replace("\"manifest_schema\": 2", "\"manifest_schema\": 1"));
        assertManifestMutationRejected("wrong-target", json -> json.replace("\"target\": \"" + PLATFORM.getNormalizedName() + "\"", "\"target\": \"linux_amd64\""));
        assertManifestMutationRejected("wrong-root", json -> json.replace("\"archive_root\": \"" + PLATFORM.getNormalizedName() + "\"", "\"archive_root\": \"linux_amd64\""));
        assertManifestMutationRejected("wrong-java", json -> json.replace("\"java_release\": 17", "\"java_release\": 21"));
        assertManifestMutationRejected("wrong-commit", json -> json.replace(COMMIT_A, COMMIT_A.toUpperCase()));
        assertManifestMutationRejected("wrong-cef-api", json -> json.replace("\"cef_api_version\": \"15100\"", "\"cef_api_version\": \"15000\""));
        assertManifestMutationRejected("wrong-cef-version", json -> json.replace("\"cef_version\": \"151.2.3+g89cd581+chromium-151.0.7922.34\"", "\"cef_version\": \"151.2.3+test\""));
        assertManifestMutationRejected("wrong-jogamp", json -> json.replace("\"jogl_swing_osr_supported\": true", "\"jogl_swing_osr_supported\": false"));
        assertManifestMutationRejected("wrong-jars", json -> json.replace("\"jcef_jars\": [\"jcef.jar\", \"jcef-tests.jar\"]", "\"jcef_jars\": [\"jcef.jar\"]"));
    }

    @Test
    void strictParserRejectsDuplicatesUnknownKeysEscapesAndMalformedData() throws Exception {
        assertManifestMutationRejected("duplicate-top", json -> json.replaceFirst("\\{", "{\n  \"target\": \"" + PLATFORM.getNormalizedName() + "\","));
        assertManifestMutationRejected("unknown-top", json -> json.replaceFirst("\\{", "{\n  \"unknown\": true,"));
        assertManifestMutationRejected("duplicate-file-key", json -> json.replaceFirst("\\{\\\"path\\\":", "{\"path\": \"duplicate\", \"path\":"));
        assertManifestMutationRejected("escaping-path", json -> json.replaceFirst("jcef_app\\.app/", "../"));
        assertManifestMutationRejected("backslash-path", json -> json.replaceFirst("jcef_app\\.app/", "jcef_app.app\\\\"));
        assertManifestMutationRejected("zero-size", json -> json.replaceFirst("\\\"size\\\": [0-9]+", "\"size\": 0"));
        assertManifestMutationRejected("uppercase-hash", json -> json.replaceFirst("[0-9a-f]{64}", matchUppercaseDigest()));
        assertManifestMutationRejected("trailing-comma", json -> json.replace("\n}\n", ",\n}\n"));

        Path malformedUtf8 = temporaryDirectory.resolve("malformed-utf8");
        MCEFInstallerTestSupport.writeRuntimeInstallation(malformedUtf8, "malformed-utf8");
        Files.write(malformedUtf8.resolve(MCEFDistributionManifest.FILE_NAME), new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(malformedUtf8, PLATFORM, COMMIT_A));
    }

    @Test
    void rejectsNonNfcAndCaseCollidingRuntimePaths() throws Exception {
        MCEFPlatform linux = MCEFPlatform.LINUX_AMD64;

        Path nonNfcEntry = temporaryDirectory.resolve("non-nfc-entry");
        MCEFInstallerTestSupport.writeRuntimeInstallation(nonNfcEntry, linux, "non-nfc-entry", COMMIT_A);
        List<String> nonNfcEntries = new ArrayList<>(MCEFInstallerTestSupport.runtimeEntries(linux));
        nonNfcEntries.add("locale\u0301s");
        nonNfcEntries.sort(String::compareTo);
        Files.writeString(nonNfcEntry.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(linux, COMMIT_A, MCEFInstallerTestSupport.runtimeFiles(linux, "non-nfc-entry"), nonNfcEntries, "non-nfc-entry"), StandardCharsets.UTF_8);
        IOException nonNfcEntryFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(nonNfcEntry, linux, COMMIT_A));
        assertTrue(nonNfcEntryFailure.getMessage().contains("NFC-normalized"));

        Path nonNfcFile = temporaryDirectory.resolve("non-nfc-file");
        MCEFInstallerTestSupport.writeRuntimeInstallation(nonNfcFile, linux, "non-nfc-file", COMMIT_A);
        Map<String, byte[]> nonNfcFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.runtimeFiles(linux, "non-nfc-file"));
        nonNfcFiles.put("locales/e\u0301.pak", "non-nfc".getBytes(StandardCharsets.UTF_8));
        Files.writeString(nonNfcFile.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(linux, COMMIT_A, nonNfcFiles, "non-nfc-file"), StandardCharsets.UTF_8);
        IOException nonNfcFileFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(nonNfcFile, linux, COMMIT_A));
        assertTrue(nonNfcFileFailure.getMessage().contains("NFC-normalized"));

        Path collidingEntriesInstallation = temporaryDirectory.resolve("colliding-entries");
        MCEFInstallerTestSupport.writeRuntimeInstallation(collidingEntriesInstallation, linux, "colliding-entries", COMMIT_A);
        List<String> collidingEntries = new ArrayList<>(MCEFInstallerTestSupport.runtimeEntries(linux));
        collidingEntries.add(collidingEntries.getFirst().toUpperCase(Locale.ROOT));
        collidingEntries.sort(String::compareTo);
        Files.writeString(collidingEntriesInstallation.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(linux, COMMIT_A, MCEFInstallerTestSupport.runtimeFiles(linux, "colliding-entries"), collidingEntries, "colliding-entries"), StandardCharsets.UTF_8);
        IOException entryCollisionFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(collidingEntriesInstallation, linux, COMMIT_A));
        assertTrue(entryCollisionFailure.getMessage().contains("case-colliding"));

        Path collidingFilesInstallation = temporaryDirectory.resolve("colliding-files");
        MCEFInstallerTestSupport.writeRuntimeInstallation(collidingFilesInstallation, linux, "colliding-files", COMMIT_A);
        Map<String, byte[]> collidingFiles = new LinkedHashMap<>(MCEFInstallerTestSupport.runtimeFiles(linux, "colliding-files"));
        collidingFiles.put("locales/EN-US.PAK", collidingFiles.get("locales/en-US.pak"));
        Files.writeString(collidingFilesInstallation.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(linux, COMMIT_A, collidingFiles, "colliding-files"), StandardCharsets.UTF_8);
        IOException fileCollisionFailure = assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(collidingFilesInstallation, linux, COMMIT_A));
        assertTrue(fileCollisionFailure.getMessage().contains("case-colliding"), fileCollisionFailure.getMessage());
    }

    @Test
    void rejectsUnsortedRuntimeFilesAndOversizedManifest() throws Exception {
        Path unsortedEntries = temporaryDirectory.resolve("unsorted-entries");
        MCEFPlatform linux = MCEFPlatform.LINUX_AMD64;
        MCEFInstallerTestSupport.writeRuntimeInstallation(unsortedEntries, linux, "unsorted-entries", COMMIT_A);
        Path entriesManifest = unsortedEntries.resolve(MCEFDistributionManifest.FILE_NAME);
        String entriesJson = Files.readString(entriesManifest);
        int entriesStart = entriesJson.indexOf("\"runtime_entries\": [") + "\"runtime_entries\": [".length();
        int entriesEnd = entriesJson.indexOf(']', entriesStart);
        String[] entryValues = entriesJson.substring(entriesStart, entriesEnd).split(", ");
        String firstEntry = entryValues[0];
        entryValues[0] = entryValues[1];
        entryValues[1] = firstEntry;
        Files.writeString(entriesManifest, entriesJson.substring(0, entriesStart) + String.join(", ", entryValues) + entriesJson.substring(entriesEnd), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(unsortedEntries, linux, COMMIT_A));

        Path unsorted = temporaryDirectory.resolve("unsorted");
        MCEFInstallerTestSupport.writeRuntimeInstallation(unsorted, "unsorted");
        Path manifest = unsorted.resolve(MCEFDistributionManifest.FILE_NAME);
        String json = Files.readString(manifest);
        int arrayStart = json.indexOf("\"runtime_files\": [") + "\"runtime_files\": [".length();
        int firstStart = json.indexOf('{', arrayStart);
        int firstEnd = json.indexOf('}', firstStart) + 1;
        int secondStart = json.indexOf('{', firstEnd);
        int secondEnd = json.indexOf('}', secondStart) + 1;
        String first = json.substring(firstStart, firstEnd);
        String second = json.substring(secondStart, secondEnd);
        Files.writeString(manifest, json.substring(0, firstStart) + second + json.substring(firstEnd, secondStart) + first + json.substring(secondEnd), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(unsorted, PLATFORM, COMMIT_A));

        Path oversized = temporaryDirectory.resolve("oversized");
        MCEFInstallerTestSupport.writeRuntimeInstallation(oversized, "oversized");
        Files.writeString(oversized.resolve(MCEFDistributionManifest.FILE_NAME), " ".repeat(16 * 1024 * 1024 + 1), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(oversized, PLATFORM, COMMIT_A));
    }

    @Test
    void rejectsUnsortedDuplicateAndOverLimitDistributionInventories() throws Exception {
        assertEquals(100_000, MCEFDistributionManifest.MAX_DISTRIBUTION_DIRECTORIES);

        Path unsortedFiles = temporaryDirectory.resolve("unsorted-distribution-files");
        MCEFInstallerTestSupport.writeRuntimeInstallation(unsortedFiles, "unsorted-distribution-files");
        Path unsortedManifest = unsortedFiles.resolve(MCEFDistributionManifest.FILE_NAME);
        Files.writeString(unsortedManifest, swapFirstTwoFileObjects(Files.readString(unsortedManifest), "distribution_files"), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(unsortedFiles, PLATFORM, COMMIT_A));

        String duplicateVersion = "duplicate-distribution-directory";
        Path duplicateDirectory = temporaryDirectory.resolve(duplicateVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(duplicateDirectory, duplicateVersion);
        Map<String, byte[]> duplicateRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, duplicateVersion);
        Map<String, byte[]> duplicateFiles = MCEFInstallerTestSupport.distributionFiles(PLATFORM, duplicateRuntime, duplicateVersion);
        List<String> duplicateDirectories = new ArrayList<>(MCEFInstallerTestSupport.distributionDirectories(duplicateFiles));
        duplicateDirectories.add(duplicateDirectories.getFirst());
        duplicateDirectories.sort(String::compareTo);
        Files.writeString(duplicateDirectory.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, duplicateRuntime, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), duplicateFiles, duplicateDirectories), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(duplicateDirectory, PLATFORM, COMMIT_A));

        String limitVersion = "distribution-directory-limit";
        Path overLimit = temporaryDirectory.resolve(limitVersion);
        MCEFInstallerTestSupport.writeRuntimeInstallation(overLimit, limitVersion);
        Map<String, byte[]> limitRuntime = MCEFInstallerTestSupport.runtimeFiles(PLATFORM, limitVersion);
        Map<String, byte[]> limitFiles = MCEFInstallerTestSupport.distributionFiles(PLATFORM, limitRuntime, limitVersion);
        List<String> tooManyDirectories = new ArrayList<>();
        for (int index = 0; index <= MCEFDistributionManifest.MAX_DISTRIBUTION_DIRECTORIES; index++) {
            tooManyDirectories.add(String.format("declared-%04d", index));
        }
        Files.writeString(overLimit.resolve(MCEFDistributionManifest.FILE_NAME), MCEFInstallerTestSupport.manifestJson(PLATFORM, COMMIT_A, limitRuntime, MCEFInstallerTestSupport.runtimeEntries(PLATFORM), limitFiles, tooManyDirectories), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(overLimit, PLATFORM, COMMIT_A));
    }

    @Test
    void publisherMemberAndPathBudgetsAcceptExactBoundariesAndRejectTheNextValue() throws Exception {
        assertEquals(100_000, MCEFDistributionManifest.MAX_ARCHIVE_MEMBERS);
        assertEquals(100_000, MCEFDistributionManifest.MAX_DISTRIBUTION_FILES);
        assertEquals(4_096, MCEFDistributionManifest.MAX_PATH_BYTES);
        assertEquals(64, MCEFDistributionManifest.MAX_PATH_DEPTH);
        assertEquals(16L * 1024L * 1024L, MCEFDistributionManifest.MAX_TOTAL_PATH_BYTES);

        String root = PLATFORM.getNormalizedName();
        List<String> exactMemberCount = new ArrayList<>();
        for (int index = 0; index < MCEFDistributionManifest.MAX_ARCHIVE_MEMBERS - 3; index++) {
            exactMemberCount.add(String.format(Locale.ROOT, "d-%05d", index));
        }
        MCEFDistributionManifest.ManifestFile placeholderFile = new MCEFDistributionManifest.ManifestFile("payload", 0L, "0".repeat(64));
        MCEFDistributionTreeValidator.validatePublisherInventoryLimits(root, exactMemberCount, List.of(placeholderFile));
        List<String> excessiveMemberCount = new ArrayList<>(exactMemberCount);
        excessiveMemberCount.add("d-99997");
        assertThrows(IOException.class, () -> MCEFDistributionTreeValidator.validatePublisherInventoryLimits(root, excessiveMemberCount, List.of(placeholderFile)));

        int archivePrefixBytes = (root + "/").getBytes(StandardCharsets.UTF_8).length;
        int exactRelativeBytes = MCEFDistributionManifest.MAX_PATH_BYTES - archivePrefixBytes;
        String exactUtf8Path = "é".repeat(exactRelativeBytes / 2) + "p".repeat(exactRelativeBytes % 2);
        MCEFDistributionTreeValidator.validatePublisherInventoryLimits(root, List.of(exactUtf8Path), List.of());
        assertThrows(IOException.class, () -> MCEFDistributionTreeValidator.validatePublisherInventoryLimits(root, List.of(exactUtf8Path + "é"), List.of()));

        String exactDepthPath = String.join("/", Collections.nCopies(MCEFDistributionManifest.MAX_PATH_DEPTH - 1, "d"));
        MCEFDistributionTreeValidator.validatePublisherInventoryLimits(root, List.of(exactDepthPath), List.of());
        String excessiveDepthPath = exactDepthPath + "/d";
        assertThrows(IOException.class, () -> MCEFDistributionTreeValidator.validatePublisherInventoryLimits(root, List.of(excessiveDepthPath), List.of()));

        long fixedPathBytes = root.getBytes(StandardCharsets.UTF_8).length + (root + "/" + MCEFDistributionManifest.FILE_NAME).getBytes(StandardCharsets.UTF_8).length;
        long remainingPathBytes = MCEFDistributionManifest.MAX_TOTAL_PATH_BYTES - fixedPathBytes;
        int maximumRelativeBytes = MCEFDistributionManifest.MAX_PATH_BYTES - archivePrefixBytes;
        int maximumPathCount = Math.toIntExact(remainingPathBytes / MCEFDistributionManifest.MAX_PATH_BYTES);
        int finalArchivePathBytes = Math.toIntExact(remainingPathBytes % MCEFDistributionManifest.MAX_PATH_BYTES);
        if (finalArchivePathBytes <= archivePrefixBytes) {
            maximumPathCount--;
            finalArchivePathBytes += MCEFDistributionManifest.MAX_PATH_BYTES;
        }
        List<String> exactAggregatePaths = new ArrayList<>();
        String maximumPathPrefix = "p".repeat(maximumRelativeBytes - 8);
        for (int index = 0; index < maximumPathCount; index++) {
            exactAggregatePaths.add(maximumPathPrefix + String.format(Locale.ROOT, "%08x", index));
        }
        exactAggregatePaths.add("q".repeat(finalArchivePathBytes - archivePrefixBytes));
        MCEFDistributionTreeValidator.validatePublisherInventoryLimits(root, exactAggregatePaths, List.of());
        List<String> excessiveAggregatePaths = new ArrayList<>(exactAggregatePaths);
        excessiveAggregatePaths.set(excessiveAggregatePaths.size() - 1, excessiveAggregatePaths.getLast() + "q");
        assertThrows(IOException.class, () -> MCEFDistributionTreeValidator.validatePublisherInventoryLimits(root, excessiveAggregatePaths, List.of()));
    }

    private void assertManifestMutationRejected(String name, ManifestMutation mutation) throws Exception {
        Path installation = temporaryDirectory.resolve(name);
        MCEFInstallerTestSupport.writeRuntimeInstallation(installation, name);
        Path manifest = installation.resolve(MCEFDistributionManifest.FILE_NAME);
        Files.writeString(manifest, mutation.apply(Files.readString(manifest)), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MCEFDistributionManifest.validate(installation, PLATFORM, COMMIT_A));
    }

    private static void writeManifest(Path installation, MCEFPlatform platform, Map<String, byte[]> runtimeFiles, Map<String, byte[]> distributionFiles, List<String> distributionDirectories) throws IOException {
        String manifest = MCEFInstallerTestSupport.manifestJson(platform, COMMIT_A, runtimeFiles, MCEFInstallerTestSupport.runtimeEntries(platform), distributionFiles, distributionDirectories);
        Files.writeString(installation.resolve(MCEFDistributionManifest.FILE_NAME), manifest, StandardCharsets.UTF_8);
    }

    private static String matchUppercaseDigest() {
        return "A".repeat(64);
    }

    private static String swapFirstTwoFileObjects(String json, String key) {
        int arrayStart = json.indexOf("\"" + key + "\": [") + key.length() + 5;
        int firstStart = json.indexOf('{', arrayStart);
        int firstEnd = json.indexOf('}', firstStart) + 1;
        int secondStart = json.indexOf('{', firstEnd);
        int secondEnd = json.indexOf('}', secondStart) + 1;
        String first = json.substring(firstStart, firstEnd);
        String second = json.substring(secondStart, secondEnd);
        return json.substring(0, firstStart) + second + json.substring(firstEnd, secondStart) + first + json.substring(secondEnd);
    }

    @FunctionalInterface
    private interface ManifestMutation {
        String apply(String manifest);
    }
}
