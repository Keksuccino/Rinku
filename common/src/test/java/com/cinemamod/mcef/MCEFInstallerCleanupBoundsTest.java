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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.cinemamod.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static com.cinemamod.mcef.MCEFInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFInstallerCleanupBoundsTest extends MCEFInstallerTestBase {
    private static final int SMALL_CLEANUP_BUDGET = 8;

    @TempDir
    Path temporaryDirectory;

    @Test
    void oversizedGarbageTreeMakesBoundedMonotonicProgressAcrossRecoveries() throws Exception {
        Path root = garbageDirectory().resolve("oversized");
        Files.createDirectories(root);
        for (int index = 0; index < 10; index++) {
            Files.writeString(root.resolve("entry-" + index), "residue");
        }
        long initialNodes = treeNodeCount(root);
        List<IOException> warnings = new ArrayList<>();

        try (MCEFInstallationTransaction recovery = transaction(warnings)) {
            recovery.recover();
        }

        long remainingNodes = treeNodeCount(root);
        assertTrue(remainingNodes > 0L);
        assertTrue(initialNodes - remainingNodes <= SMALL_CLEANUP_BUDGET);
        assertTrue(warnings.stream().anyMatch(failure -> failure.getCause() != null && failure.getCause().getMessage().contains("bounded tree-entry limit")));

        try (MCEFInstallationTransaction recovery = transaction(new ArrayList<>())) {
            recovery.recover();
        }

        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS));
        assertEquals(0L, garbageEntryCount());
    }

    @Test
    void multipleGarbageRootsShareOneRecoveryBudget() throws Exception {
        Path first = garbageDirectory().resolve("first");
        Path second = garbageDirectory().resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        for (int index = 0; index < 6; index++) {
            Files.writeString(first.resolve("entry-" + index), "first");
            Files.writeString(second.resolve("entry-" + index), "second");
        }
        long initialNodes = treeNodeCount(first) + treeNodeCount(second);

        try (MCEFInstallationTransaction recovery = transaction(new ArrayList<>())) {
            recovery.recover();
        }

        long remainingNodes = treeNodeCount(first) + treeNodeCount(second);
        assertTrue(remainingNodes > 0L, "A fresh per-root budget would incorrectly delete both trees");
        assertTrue(initialNodes - remainingNodes <= SMALL_CLEANUP_BUDGET);
    }

    @Test
    void overDepthGarbageIsFlattenedWithoutFollowingSymlinksAndEventuallyDeleted() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outside, "unchanged");
        Path root = garbageDirectory().resolve("deep");
        Path current = root;
        for (int depth = 0; depth <= MCEFInstallationTransaction.MAX_CLEANUP_TREE_DEPTH; depth++) {
            current = current.resolve("d");
        }
        Files.createDirectories(current);
        Files.writeString(current.resolve("leaf.txt"), "leaf");
        Files.createSymbolicLink(current.resolve("outside-link"), outside);

        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            recovery.recover();
        }

        assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS));
        assertTrue(garbageEntryCount() > 0L, "The depth-bound subtree should be flattened for a later pass");
        assertEquals("unchanged", Files.readString(outside));

        try (MCEFInstallationTransaction recovery = new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            recovery.recover();
        }

        assertEquals(0L, garbageEntryCount());
        assertEquals("unchanged", Files.readString(outside));
    }

    private MCEFInstallationTransaction transaction(List<IOException> warnings) throws IOException {
        return new MCEFInstallationTransaction(temporaryDirectory, PLATFORM, COMMIT_A, warnings::add, SMALL_CLEANUP_BUDGET);
    }

    private Path garbageDirectory() {
        return temporaryDirectory.resolve("." + PLATFORM.getNormalizedName() + ".mcef-gc");
    }

    private long garbageEntryCount() throws IOException {
        if (!Files.isDirectory(garbageDirectory(), LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        try (var entries = Files.list(garbageDirectory())) {
            return entries.count();
        }
    }

    private static long treeNodeCount(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        try (var paths = Files.walk(root)) {
            return paths.count();
        }
    }
}
