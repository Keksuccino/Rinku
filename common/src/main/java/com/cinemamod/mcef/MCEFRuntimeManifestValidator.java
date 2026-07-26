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
import com.cinemamod.mcef.MCEFDistributionManifest.ManifestFile;
import com.cinemamod.mcef.MCEFDistributionTreeValidator.HashedFile;
import com.cinemamod.mcef.MCEFDistributionTreeValidator.ValidatedTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates runtime metadata as the exact runtime-root subset of a shared tree
 * snapshot.
 */
final class MCEFRuntimeManifestValidator {
    private static final long MAX_DECLARED_RUNTIME_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final Pattern DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private MCEFRuntimeManifestValidator() {
    }

    static void validate(ManifestData manifest, ValidatedTree tree) throws IOException {
        List<ManifestFile> declared = manifest.runtimeFiles();
        if (declared.isEmpty() || declared.size() > MCEFDistributionManifest.MAX_RUNTIME_FILES) {
            throw new IOException("JCEF runtime file count is outside the supported limit");
        }

        validateRuntimeRootTypes(tree, manifest.runtimeEntries());
        long declaredBytes = 0L;
        long snapshotBytes = 0L;
        boolean hasLocalePack = false;
        String previousPath = null;
        Set<String> foldedPaths = new HashSet<>();
        List<String> declaredPaths = new ArrayList<>(declared.size());
        for (ManifestFile runtimeFile : declared) {
            validateRuntimePath(runtimeFile.path());
            if (!foldedPaths.add(caseFoldPath(runtimeFile.path()))) {
                throw new IOException("JCEF runtime files contain a case-colliding path: " + runtimeFile.path());
            }
            if (previousPath != null && previousPath.compareTo(runtimeFile.path()) >= 0) {
                throw new IOException("JCEF runtime files must be unique and sorted by path");
            }
            previousPath = runtimeFile.path();
            if (runtimeFile.size() <= 0L || runtimeFile.size() > MAX_DECLARED_RUNTIME_BYTES || !DIGEST_PATTERN.matcher(runtimeFile.sha256()).matches()) {
                throw new IOException("Invalid JCEF runtime file metadata for " + runtimeFile.path());
            }
            declaredBytes = addBounded(declaredBytes, runtimeFile.size());
            if (containingEntryCount(runtimeFile.path(), manifest.runtimeEntries()) != 1) {
                throw new IOException("JCEF runtime file is not covered by exactly one runtime entry: " + runtimeFile.path());
            }

            HashedFile actual = tree.files().get(runtimeFile.path());
            if (actual == null || actual.size() != runtimeFile.size() || !actual.sha256().equals(runtimeFile.sha256())) {
                throw new IOException("JCEF runtime metadata does not match the validated distribution file: " + runtimeFile.path());
            }
            snapshotBytes = addBounded(snapshotBytes, actual.size());
            declaredPaths.add(runtimeFile.path());
            if (runtimeFile.path().startsWith("locales/") && runtimeFile.path().endsWith(".pak")) {
                hasLocalePack = true;
            }
        }
        if (manifest.runtimeEntries().contains("locales") && !hasLocalePack) {
            throw new IOException("JCEF distribution locales directory contains no locale pack");
        }

        List<String> snapshotRuntimePaths = tree.files().keySet().stream().filter(path -> containingEntryCount(path, manifest.runtimeEntries()) == 1).sorted().toList();
        if (!snapshotRuntimePaths.equals(declaredPaths)) {
            throw new IOException("JCEF runtime file inventory does not exactly cover the validated runtime roots");
        }
        if (snapshotBytes != declaredBytes) {
            throw new IOException("JCEF runtime byte inventory changed during validation");
        }
    }

    static void validateRuntimePath(String value) throws IOException {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > MCEFDistributionManifest.MAX_PATH_BYTES || value.startsWith("/") || value.endsWith("/") || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            throw new IOException("Unsafe path in JCEF distribution manifest: " + value);
        }
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new IOException("JCEF distribution manifest path is not NFC-normalized: " + value);
        }
        String[] components = value.split("/", -1);
        if (components.length > MCEFDistributionManifest.MAX_PATH_DEPTH) {
            throw new IOException("JCEF distribution manifest path exceeds the supported depth limit: " + value);
        }
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Unsafe path in JCEF distribution manifest: " + value);
            }
            for (int index = 0; index < component.length(); index++) {
                if (Character.isISOControl(component.charAt(index))) {
                    throw new IOException("Unsafe path in JCEF distribution manifest: " + value);
                }
            }
        }
    }

    static String caseFoldPath(String value) {
        // Java has no direct Unicode case-fold API. Uppercasing before lowercasing
        // also collapses multi-character expansions such as sharp-s and ligatures,
        // unlike lowercasing alone.
        return value.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static void validateRuntimeRootTypes(ValidatedTree tree, List<String> entries) throws IOException {
        for (String entry : entries) {
            boolean mustBeDirectory = entry.equals("locales") || entry.equals("jcef_app.app");
            boolean isDirectory = tree.directories().contains(entry);
            boolean isFile = tree.files().containsKey(entry);
            if (mustBeDirectory != isDirectory || !mustBeDirectory && !isFile) {
                throw new IOException("JCEF runtime entry has the wrong filesystem type: " + entry);
            }
        }
    }

    private static int containingEntryCount(String path, List<String> entries) {
        int matches = 0;
        for (String entry : entries) {
            if (path.equals(entry) || path.startsWith(entry + "/")) {
                matches++;
            }
        }
        return matches;
    }

    private static long addBounded(long current, long additional) throws IOException {
        if (additional < 0L || current > MAX_DECLARED_RUNTIME_BYTES - additional) {
            throw new IOException("JCEF runtime byte inventory exceeds the supported limit");
        }
        return current + additional;
    }
}
