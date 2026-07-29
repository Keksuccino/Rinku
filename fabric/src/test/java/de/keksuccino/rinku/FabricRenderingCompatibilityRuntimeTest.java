package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FabricRenderingCompatibilityRuntimeTest {

    @Test
    void exposesSodiumsEmbeddedJomlLibraryWhenCompatibilityModsAreEnabled() {
        ClassLoader classLoader = getClass().getClassLoader();
        boolean sodiumEnabled = classLoader.getResource("me/jellysquid/mods/sodium/client/SodiumClientMod.class") != null;
        assumeTrue(sodiumEnabled, "Sodium/Iris development-runtime coverage was explicitly disabled.");

        assertNotNull(classLoader.getResource("org/joml/Matrix4f.class"), "Loom strips Sodium's nested JOML declaration while remapping the Modrinth artifact, so the development runtime must restore it explicitly.");
    }

}
