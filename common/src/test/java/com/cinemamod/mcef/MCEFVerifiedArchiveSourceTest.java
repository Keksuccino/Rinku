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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFVerifiedArchiveSourceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pathReplacementCannotChangeTheAlreadyOpenedArchiveIdentity() throws Exception {
        byte[] original = MCEFInstallerTestSupport.archiveBytes("opened-identity");
        byte[] replacement = MCEFInstallerTestSupport.archiveBytes("replacement-identity");
        Path archive = temporaryDirectory.resolve("candidate.tar.gz");
        Path displaced = temporaryDirectory.resolve("displaced.tar.gz");
        Files.write(archive, original);

        try (MCEFVerifiedArchiveSource source = MCEFVerifiedArchiveSource.open(archive, 1024L)) {
            Files.move(archive, displaced, StandardCopyOption.ATOMIC_MOVE);
            Files.write(archive, replacement, StandardOpenOption.CREATE_NEW);

            assertEquals(MCEFInstallerTestSupport.sha256(original), source.calculateDigest());
            source.verifiedPass(MCEFInstallerTestSupport.sha256(original), input -> assertArrayEquals(original, input.readAllBytes()));
        }
    }

    @Test
    void sameInodeRewriteAndRestoreCannotPassAbaVerification() throws Exception {
        byte[] original = new byte[64 * 1024];
        Arrays.fill(original, (byte) 'a');
        byte[] mutated = original.clone();
        Arrays.fill(mutated, 1024, mutated.length, (byte) 'b');
        Path archive = temporaryDirectory.resolve("candidate.tar.gz");
        Files.write(archive, original);

        try (MCEFVerifiedArchiveSource source = MCEFVerifiedArchiveSource.open(archive, original.length)) {
            IOException failure = assertThrows(IOException.class, () -> source.verifiedPass(MCEFInstallerTestSupport.sha256(original), input -> {
                assertEquals(1024, input.readNBytes(1024).length);
                Files.write(archive, mutated, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                byte[] observedRemainder = input.readAllBytes();
                assertTrue(Arrays.equals(Arrays.copyOfRange(mutated, 1024, mutated.length), observedRemainder));
                Files.write(archive, original, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }));
            assertTrue(failure.getMessage().contains("changed during a verified read pass"));
        }
    }

    @Test
    void unreadTrailingBytesRemainPartOfEveryVerifiedPass() throws Exception {
        byte[] prefix = MCEFInstallerTestSupport.archiveBytes("parsed-prefix");
        byte[] archiveBytes = Arrays.copyOf(prefix, prefix.length + 32);
        Arrays.fill(archiveBytes, prefix.length, archiveBytes.length, (byte) '!');
        Path archive = temporaryDirectory.resolve("candidate.tar.gz");
        Files.write(archive, archiveBytes);

        try (MCEFVerifiedArchiveSource source = MCEFVerifiedArchiveSource.open(archive, 1024L)) {
            assertThrows(IOException.class, () -> source.verifiedPass(MCEFInstallerTestSupport.sha256(prefix), input -> assertArrayEquals(prefix, input.readNBytes(prefix.length))));
            source.verifiedPass(MCEFInstallerTestSupport.sha256(archiveBytes), input -> assertEquals(prefix.length, input.skip(prefix.length)));
        }
    }

    @Test
    void finalComponentSymlinkIsRejectedAtOpen() throws Exception {
        Path target = temporaryDirectory.resolve("target.tar.gz");
        Path archive = temporaryDirectory.resolve("candidate.tar.gz");
        Files.write(target, MCEFInstallerTestSupport.archiveBytes("symlink-target"));
        Files.createSymbolicLink(archive, target);

        assertThrows(IOException.class, () -> MCEFVerifiedArchiveSource.open(archive, 1024L));
    }
}
