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
        assertFalse(modMetadata.contains("\"preLaunch\""));
        assertFalse(modMetadata.contains("MCEFFabricBootstrap"));
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
