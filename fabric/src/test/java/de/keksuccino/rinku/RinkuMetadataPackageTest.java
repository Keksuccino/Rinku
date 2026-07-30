package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuMetadataPackageTest {
    @Test
    void commonMixinMetadataUsesTheCurrentPackage() throws IOException {
        String metadata = readResource("rinku.mixins.json");

        assertTrue(metadata.contains("\"package\": \"de.keksuccino.rinku.mixins\""));
        assertTrue(metadata.contains("\"MixinClientPackSource\""));
        assertFalse(metadata.contains("\"MixinGui\""));
        assertTrue(metadata.contains("\"MixinMinecraft\""));
        assertTrue(metadata.contains("\"MixinGameRenderer\""));
        assertFalse(metadata.contains("cinemamod"));
    }

    @Test
    void minecraftMixinOwnsTheVersionSpecificScreenHook() throws NoSuchMethodException {
        assertNotNull(de.keksuccino.rinku.mixins.MixinMinecraft.class.getDeclaredMethod("before_setScreen_Rinku", net.minecraft.client.gui.screens.Screen.class, org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class));
    }

    @Test
    void fabricMetadataUsesTheCurrentPackage() throws IOException {
        String modMetadata = readResource("fabric.mod.json");
        String mixinMetadata = readResource("rinku.fabric.mixins.json");

        assertTrue(modMetadata.contains("de.keksuccino.rinku.RinkuFabric"));
        assertTrue(modMetadata.contains("\"version\": \"" + Rinku.VERSION + "\""));
        assertTrue(modMetadata.contains("\"minecraft\": \">=1.21.11\""));
        assertTrue(modMetadata.contains("\"java\": \">=21\""));
        assertTrue(mixinMetadata.contains("\"package\": \"de.keksuccino.rinku.mixins.fabric\""));
        assertFalse(modMetadata.contains("\"preLaunch\""));
        assertFalse(modMetadata.contains("RinkuFabricBootstrap"));
        assertFalse(modMetadata.contains("cinemamod"));
        assertFalse(mixinMetadata.contains("cinemamod"));
    }

    @Test
    void resourcePackUsesTheMinecraft12111FormatRange() throws IOException {
        String metadata = readResource("pack.mcmeta");

        assertTrue(metadata.contains("\"min_format\": 75"));
        assertTrue(metadata.contains("\"max_format\": 75"));
        assertFalse(metadata.contains("\"pack_format\""));
    }

    private static String readResource(String name) throws IOException {
        try (InputStream input = RinkuMetadataPackageTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, "Missing runtime resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
