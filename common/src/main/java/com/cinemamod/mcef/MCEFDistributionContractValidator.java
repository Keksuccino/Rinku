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

import com.cinemamod.mcef.MCEFDistributionManifest.ManifestData;
import com.cinemamod.mcef.MCEFDistributionTreeValidator.HashedFile;
import com.cinemamod.mcef.MCEFDistributionTreeValidator.ValidatedTree;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mirrors the publisher's exact per-target top-level distribution contract. */
final class MCEFDistributionContractValidator {
    private static final Set<String> COMMON_FILES = Set.of("CEF-LICENSE.txt", "CREDITS.html", MCEFDistributionManifest.FILE_NAME, "LICENSE.txt", "README.txt", "jcef.jar", "jcef-tests.jar");
    private static final Set<String> JOGAMP_LICENSE_FILES = Set.of("gluegen.LICENSE.txt", "jogl.LICENSE.txt");
    private static final String OPTIONAL_LINUX_RUNTIME_FILE = "libminigbm.so";

    private MCEFDistributionContractValidator() {
    }

    static void validate(ManifestData manifest, MCEFPlatform platform, ValidatedTree tree) throws IOException {
        Set<String> expectedFiles = expectedTopLevelFiles(manifest, platform, tree);
        Set<String> expectedDirectories = Set.of("docs", "tests", platform.isMacOS() ? "jcef_app.app" : "locales");
        Set<String> actualTopLevel = new HashSet<>();
        actualTopLevel.add(MCEFDistributionManifest.FILE_NAME);
        tree.files().keySet().stream().filter(MCEFDistributionContractValidator::isTopLevel).forEach(actualTopLevel::add);
        tree.directories().stream().filter(MCEFDistributionContractValidator::isTopLevel).forEach(actualTopLevel::add);

        Set<String> expectedTopLevel = new HashSet<>(expectedFiles);
        expectedTopLevel.addAll(expectedDirectories);
        if (!actualTopLevel.equals(expectedTopLevel)) {
            Set<String> missing = new HashSet<>(expectedTopLevel);
            missing.removeAll(actualTopLevel);
            if (!missing.isEmpty()) {
                throw new IOException("JCEF distribution is missing canonical top-level entry: " + missing.stream().sorted().findFirst().orElseThrow());
            }
            Set<String> unexpected = new HashSet<>(actualTopLevel);
            unexpected.removeAll(expectedTopLevel);
            throw new IOException("JCEF distribution contains unexpected top-level entry: " + unexpected.stream().sorted().findFirst().orElseThrow());
        }

        for (String file : expectedFiles) {
            if (file.equals(MCEFDistributionManifest.FILE_NAME)) {
                continue;
            }
            HashedFile metadata = tree.files().get(file);
            if (metadata == null || metadata.size() <= 0L) {
                throw new IOException("Canonical JCEF top-level file is missing, empty, or has the wrong kind: " + file);
            }
        }
        for (String directory : expectedDirectories) {
            if (!tree.directories().contains(directory)) {
                throw new IOException("Canonical JCEF top-level directory has the wrong kind: " + directory);
            }
            String prefix = directory + "/";
            if (tree.files().keySet().stream().noneMatch(path -> path.startsWith(prefix))) {
                throw new IOException("Canonical JCEF top-level directory contains no regular files: " + directory);
            }
        }
    }

    private static Set<String> expectedTopLevelFiles(ManifestData manifest, MCEFPlatform platform, ValidatedTree tree) throws IOException {
        Set<String> expected = new HashSet<>(COMMON_FILES);
        String runtimeDirectory = platform.isMacOS() ? "jcef_app.app" : "locales";
        MCEFDistributionManifest.expectedRequiredRuntimeEntries(platform).stream().filter(entry -> !entry.equals(runtimeDirectory)).forEach(expected::add);

        boolean manifestDeclaresOptional = manifest.runtimeEntries().contains(OPTIONAL_LINUX_RUNTIME_FILE);
        boolean treeContainsOptional = tree.files().containsKey(OPTIONAL_LINUX_RUNTIME_FILE);
        if (platform.isLinux() && manifestDeclaresOptional != treeContainsOptional) {
            throw new IOException("Optional JCEF runtime entry presence mismatch: " + OPTIONAL_LINUX_RUNTIME_FILE);
        }
        if (platform.isLinux() && treeContainsOptional) {
            expected.add(OPTIONAL_LINUX_RUNTIME_FILE);
        }

        List<String> jogampJars = MCEFDistributionManifest.expectedJogampJars(platform);
        expected.addAll(jogampJars);
        if (!jogampJars.isEmpty()) {
            expected.addAll(JOGAMP_LICENSE_FILES);
        }
        expected.add(platform.isWindows() ? "java17_check.bat" : "java17_check.sh");
        expected.add(platform.isWindows() ? "compile.bat" : "compile.sh");
        if (!platform.isMacOS()) {
            expected.add(platform.isWindows() ? "run.bat" : "run.sh");
        }
        return expected;
    }

    private static boolean isTopLevel(String path) {
        return path.indexOf('/') < 0;
    }
}
