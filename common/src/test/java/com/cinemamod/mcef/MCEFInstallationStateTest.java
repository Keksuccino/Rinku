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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static com.cinemamod.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static com.cinemamod.mcef.MCEFInstallerTestSupport.PLATFORM;
import static com.cinemamod.mcef.MCEFInstallerTestSupport.SHA256_COMMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFInstallationStateTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsCanonicalSha256ObjectIdGenerationState() throws Exception {
        UUID transactionId = UUID.randomUUID();
        String archiveDigest = "a".repeat(64);
        String manifestDigest = "b".repeat(64);
        String generationName = SHA256_COMMIT + "-" + archiveDigest + "-" + transactionId;
        MCEFInstallationState.StateRecord expected = MCEFInstallationState.StateRecord.committed(MCEFInstallationState.StateKind.GENERATION, transactionId, PLATFORM.getNormalizedName(), SHA256_COMMIT, archiveDigest, manifestDigest, MCEFInstallationState.ChecksumVerification.CHECKSUM_VERIFIED, MCEFInstallationState.OFFICIAL_CHECKSUM_SOURCE, generationName);
        Path stateFile = temporaryDirectory.resolve("generation.properties");
        Files.writeString(stateFile, expected.serialize());

        MCEFInstallationState.StateRecord actual = MCEFInstallationState.read(stateFile);

        assertTrue(actual.sameIdentity(expected));
        assertTrue(actual.generationIsExact());
        assertTrue(MCEFInstallationState.isGeneration(generationName));
        assertEquals(SHA256_COMMIT, actual.javaCefCommit());
        assertEquals(archiveDigest, actual.archiveDigest());
    }

    @Test
    void acceptsOnlyCanonicalSha1OrSha256ObjectIdsInPersistedIdentity() {
        assertTrue(MCEFInstallationState.isCommit(COMMIT_A));
        assertTrue(MCEFInstallationState.isCommit(SHA256_COMMIT));
        assertFalse(MCEFInstallationState.isCommit(SHA256_COMMIT.toUpperCase(Locale.ROOT)));
        assertFalse(MCEFInstallationState.isCommit("a".repeat(39)));
        assertFalse(MCEFInstallationState.isCommit("a".repeat(41)));
        assertFalse(MCEFInstallationState.isCommit("a".repeat(63)));
        assertFalse(MCEFInstallationState.isCommit("a".repeat(65)));
        assertEquals(SHA256_COMMIT, MCEFInstallationState.normalizeCommit(SHA256_COMMIT.toUpperCase(Locale.ROOT)));
    }
}
