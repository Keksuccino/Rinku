/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFMetadataPackageTest {
    @Test
    void commonMixinMetadataUsesTheCurrentPackage() throws IOException {
        String metadata = readResource("mcef.mixins.json");

        assertTrue(metadata.contains("\"package\": \"de.keksuccino.mcef.mixins\""));
        assertTrue(metadata.contains("\"MixinClientPackSource\""));
        assertTrue(metadata.contains("\"MixinGui\""));
        assertTrue(metadata.contains("\"MixinMinecraft\""));
        assertTrue(metadata.contains("\"MixinGameRenderer\""));
        assertFalse(metadata.contains("cinemamod"));
    }

    @Test
    void fabricMetadataUsesTheCurrentPackage() throws IOException {
        String modMetadata = readResource("fabric.mod.json");
        String mixinMetadata = readResource("mcef.fabric.mixins.json");

        assertTrue(modMetadata.contains("de.keksuccino.mcef.MCEFFabric"));
        assertTrue(mixinMetadata.contains("\"package\": \"de.keksuccino.mcef.mixins.fabric\""));
        assertFalse(modMetadata.contains("cinemamod"));
        assertFalse(mixinMetadata.contains("cinemamod"));
    }

    private static String readResource(String name) throws IOException {
        try (InputStream input = MCEFMetadataPackageTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, "Missing runtime resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
