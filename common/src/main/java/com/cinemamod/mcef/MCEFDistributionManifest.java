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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Coordinates identity, platform-contract, full-distribution, and runtime
 * validation.
 */
final class MCEFDistributionManifest {
    static final String FILE_NAME = "DISTRIBUTION-MANIFEST.json";
    // The publisher permits up to 1,024 roots, but every supported target has
    // fewer than 32. Keeping the consumer's smaller ceiling bounds overlap
    // validation without narrowing reality.
    static final int MAX_RUNTIME_ENTRIES = 512;
    static final int MAX_RUNTIME_FILES = 50_000;
    static final int MAX_ARCHIVE_MEMBERS = 100_000;
    static final int MAX_DISTRIBUTION_DIRECTORIES = MAX_ARCHIVE_MEMBERS;
    static final int MAX_DISTRIBUTION_FILES = MAX_ARCHIVE_MEMBERS;
    static final int MAX_PATH_BYTES = 4_096;
    static final int MAX_PATH_DEPTH = 64;
    static final long MAX_TOTAL_PATH_BYTES = 16L * 1024L * 1024L;

    private static final int MANIFEST_SCHEMA = 2;
    private static final int JAVA_RELEASE = 17;
    private static final String CEF_API_VERSION = "15100";
    private static final String CEF_VERSION = "151.2.3+g89cd581+chromium-151.0.7922.34";
    private static final List<String> JCEF_JARS = List.of("jcef.jar", "jcef-tests.jar");
    private static final List<String> COMMON_RESOURCE_ROOTS = List.of("chrome_100_percent.pak", "chrome_200_percent.pak", "icudtl.dat", "locales", "resources.pak", "v8_context_snapshot.bin");
    private static final List<String> LINUX_REQUIRED_ROOTS = List.of("chrome-sandbox", "jcef_helper", "libEGL.so", "libGLESv2.so", "libcef.so", "libjcef.so", "libvk_swiftshader.so", "libvulkan.so.1", "vk_swiftshader_icd.json");
    private static final List<String> WINDOWS_REQUIRED_ROOTS = List.of("chrome_elf.dll", "d3dcompiler_47.dll", "jcef.dll", "jcef_helper.exe", "libEGL.dll", "libGLESv2.dll", "libcef.dll", "vk_swiftshader.dll", "vk_swiftshader_icd.json", "vulkan-1.dll");
    private static final List<String> WINDOWS_AMD64_ONLY_ROOTS = List.of("dxcompiler.dll", "dxil.dll");
    private static final List<String> MAC_HELPER_SUFFIXES = List.of("", " (Alerts)", " (GPU)", " (Plugin)", " (Renderer)");

    private MCEFDistributionManifest() {
    }

    static ManifestIdentity validate(Path installation, MCEFPlatform platform, String expectedCommit) throws IOException {
        return validate(installation, platform, expectedCommit, false);
    }

    static ManifestIdentity validatePublished(Path installation, MCEFPlatform platform, String expectedCommit) throws IOException {
        return validate(installation, platform, expectedCommit, true);
    }

    private static ManifestIdentity validate(Path installation, MCEFPlatform platform, String expectedCommit, boolean published) throws IOException {
        Path normalizedInstallation = installation.toAbsolutePath().normalize();
        MCEFDistributionTreeValidator.requireSafeInstallationRoot(normalizedInstallation);
        Path manifestPath = normalizedInstallation.resolve(FILE_NAME);
        ParsedManifest parsed = MCEFDistributionManifestParser.parse(manifestPath);
        ManifestData manifest = parsed.manifest();
        validateIdentity(manifest, platform, expectedCommit);
        validateAuxiliaryMetadata(manifest, platform);
        validateRuntimeEntries(manifest, platform);
        validateRequiredRuntimeFiles(manifest, platform);
        MCEFDistributionTreeValidator.ValidatedTree tree = MCEFDistributionTreeValidator.validate(normalizedInstallation, manifest, published);
        MCEFDistributionContractValidator.validate(manifest, platform, tree);
        MCEFRuntimeManifestValidator.validate(manifest, tree);

        // Runtime hashing can take noticeable time on macOS. Re-reading the small
        // manifest closes the easy replacement window before its digest is
        // persisted in generation state.
        if (!parsed.sha256().equals(MCEFDistributionManifestParser.digest(manifestPath))) {
            throw new IOException("JCEF distribution manifest changed during validation");
        }
        return new ManifestIdentity(parsed.sha256(), manifest.target(), manifest.archiveRoot(), manifest.javaRelease(), manifest.javaCefCommit());
    }

    private static void validateIdentity(ManifestData manifest, MCEFPlatform platform, String expectedCommit) throws IOException {
        String expectedTarget = platform.getNormalizedName();
        if (manifest.manifestSchema() != MANIFEST_SCHEMA) {
            throw new IOException("Unsupported JCEF distribution manifest schema: " + manifest.manifestSchema());
        }
        if (!expectedTarget.equals(manifest.target()) || !expectedTarget.equals(manifest.archiveRoot())) {
            throw new IOException("JCEF distribution manifest target does not match " + expectedTarget);
        }
        if (manifest.javaRelease() != JAVA_RELEASE) {
            throw new IOException("JCEF distribution requires Java " + manifest.javaRelease() + " instead of Java " + JAVA_RELEASE);
        }
        if (!MCEFInstallationState.isCommit(manifest.javaCefCommit()) || !manifest.javaCefCommit().equals(expectedCommit)) {
            throw new IOException("JCEF distribution manifest commit does not match the requested java-cef commit");
        }
    }

    private static void validateAuxiliaryMetadata(ManifestData manifest, MCEFPlatform platform) throws IOException {
        if (!CEF_API_VERSION.equals(manifest.cefApiVersion()) || !CEF_VERSION.equals(manifest.cefVersion())) {
            throw new IOException("JCEF distribution manifest CEF version does not match the supported runtime contract");
        }
        if (!manifest.jcefJars().equals(JCEF_JARS)) {
            throw new IOException("Unexpected JCEF jar inventory in distribution manifest");
        }
        if (!manifest.jogampJars().equals(expectedJogampJars(platform))) {
            throw new IOException("Unexpected JogAmp jar inventory in distribution manifest");
        }
        if (manifest.joglSwingOsrSupported() != (platform != MCEFPlatform.WINDOWS_ARM64)) {
            throw new IOException("Incorrect JOGL Swing OSR capability in distribution manifest");
        }
    }

    private static void validateRuntimeEntries(ManifestData manifest, MCEFPlatform platform) throws IOException {
        List<String> entries = manifest.runtimeEntries();
        if (entries.isEmpty() || entries.size() > MAX_RUNTIME_ENTRIES) {
            throw new IOException("JCEF runtime entry count is outside the supported limit");
        }
        String previous = null;
        Set<String> foldedEntries = new HashSet<>();
        for (String entry : entries) {
            MCEFRuntimeManifestValidator.validateRuntimePath(entry);
            if (!foldedEntries.add(MCEFRuntimeManifestValidator.caseFoldPath(entry))) {
                throw new IOException("JCEF runtime entries contain a case-colliding path: " + entry);
            }
            if (previous != null && previous.compareTo(entry) >= 0) {
                throw new IOException("JCEF runtime entries must be unique and sorted");
            }
            previous = entry;
        }
        for (int index = 0; index < entries.size(); index++) {
            for (int otherIndex = index + 1; otherIndex < entries.size(); otherIndex++) {
                if (entries.get(otherIndex).startsWith(entries.get(index) + "/")) {
                    throw new IOException("JCEF runtime entries overlap: " + entries.get(index));
                }
            }
        }

        if (platform.isMacOS()) {
            if (!entries.equals(List.of("jcef_app.app"))) {
                throw new IOException("macOS JCEF distributions must declare jcef_app.app as their single runtime root");
            }
            return;
        }
        for (String entry : entries) {
            if (entry.indexOf('/') >= 0) {
                throw new IOException("Linux and Windows JCEF runtime entries must be top-level files or the locales directory");
            }
        }

        Set<String> expected = expectedRequiredRuntimeEntries(platform);
        Set<String> actual = new HashSet<>(entries);
        if (platform.isLinux()) {
            actual.remove("libminigbm.so");
        }
        if (!actual.equals(expected)) {
            throw new IOException("JCEF distribution manifest has an incorrect " + platform.getNormalizedName() + " runtime root set");
        }
    }

    static Set<String> expectedRequiredRuntimeEntries(MCEFPlatform platform) {
        if (platform.isMacOS()) {
            return Set.of("jcef_app.app");
        }
        Set<String> expected = new HashSet<>(COMMON_RESOURCE_ROOTS);
        expected.addAll(platform.isLinux() ? LINUX_REQUIRED_ROOTS : WINDOWS_REQUIRED_ROOTS);
        if (platform == MCEFPlatform.WINDOWS_AMD64) {
            expected.addAll(WINDOWS_AMD64_ONLY_ROOTS);
        }
        return Set.copyOf(expected);
    }

    private static void validateRequiredRuntimeFiles(ManifestData manifest, MCEFPlatform platform) throws IOException {
        Set<String> runtimeFiles = new HashSet<>();
        for (ManifestFile runtimeFile : manifest.runtimeFiles()) {
            runtimeFiles.add(runtimeFile.path());
        }
        if (!platform.isMacOS()) {
            if (!runtimeFiles.contains("locales/en-US.pak")) {
                throw new IOException("JCEF distribution manifest omits the required en-US locale pack");
            }
            return;
        }
        List<String> required = macRequiredFiles(platform);
        if (!runtimeFiles.containsAll(required)) {
            throw new IOException("macOS JCEF distribution manifest omits required app resources");
        }
    }

    private static List<String> macRequiredFiles(MCEFPlatform platform) {
        String app = "jcef_app.app/Contents/";
        String framework = app + "Frameworks/Chromium Embedded Framework.framework/";
        List<String> required = new ArrayList<>();
        required.add(app + "Info.plist");
        required.add(app + "MacOS/JavaAppLauncher");
        required.add(app + "Java/libjcef.dylib");
        for (String jcefJar : JCEF_JARS) {
            required.add(app + "Java/" + jcefJar);
        }
        for (String jogampJar : expectedJogampJars(platform)) {
            required.add(app + "Java/" + jogampJar);
        }
        required.add(app + "_CodeSignature/CodeResources");
        required.add(framework + "Chromium Embedded Framework");
        required.add(framework + "_CodeSignature/CodeResources");
        required.add(framework + "Libraries/libEGL.dylib");
        required.add(framework + "Libraries/libGLESv2.dylib");
        required.add(framework + "Libraries/libvk_swiftshader.dylib");
        required.add(framework + "Libraries/vk_swiftshader_icd.json");
        required.add(framework + "Resources/Info.plist");
        required.add(framework + "Resources/chrome_100_percent.pak");
        required.add(framework + "Resources/chrome_200_percent.pak");
        required.add(framework + "Resources/resources.pak");
        required.add(framework + "Resources/icudtl.dat");
        required.add(framework + "Resources/v8_context_snapshot." + (platform == MCEFPlatform.MACOS_AMD64 ? "x86_64" : "arm64") + ".bin");
        required.add(framework + "Resources/en.lproj/locale.pak");
        for (String suffix : MAC_HELPER_SUFFIXES) {
            String helperName = "jcef Helper" + suffix;
            String helper = app + "Frameworks/" + helperName + ".app/Contents/";
            required.add(helper + "Info.plist");
            required.add(helper + "MacOS/" + helperName);
            required.add(helper + "_CodeSignature/CodeResources");
        }
        return required;
    }

    static List<String> expectedJogampJars(MCEFPlatform platform) {
        String suffix = switch (platform) {
            case LINUX_AMD64 -> "linux-amd64";
            case LINUX_ARM64 -> "linux-aarch64";
            case WINDOWS_AMD64 -> "windows-amd64";
            case MACOS_AMD64, MACOS_ARM64 -> "macosx-universal";
            case WINDOWS_ARM64 -> null;
        };
        if (suffix == null) {
            return List.of();
        }
        return List.of("gluegen-rt.jar", "jogl-all.jar", "gluegen-rt-natives-" + suffix + ".jar", "jogl-all-natives-" + suffix + ".jar");
    }

    record ManifestIdentity(String sha256, String target, String archiveRoot, int javaRelease, String javaCefCommit) {
    }

    record ManifestFile(String path, long size, String sha256) {
    }

    record ParsedManifest(ManifestData manifest, String sha256) {
    }

    record ManifestData(
            int manifestSchema,
            String archiveRoot,
            String cefApiVersion,
            String cefVersion,
            int javaRelease,
            String javaCefCommit,
            boolean joglSwingOsrSupported,
            List<String> jogampJars,
            List<String> jcefJars,
            List<String> distributionDirectories,
            List<ManifestFile> distributionFiles,
            List<String> runtimeEntries,
            List<ManifestFile> runtimeFiles,
            String target
    ) {
    }
}
