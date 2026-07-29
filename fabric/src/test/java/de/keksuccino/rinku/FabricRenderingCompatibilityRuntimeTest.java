package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricRenderingCompatibilityRuntimeTest {

    @Test
    void exposesPinnedRenderingCompatibilityRuntime() {
        ClassLoader classLoader = getClass().getClassLoader();
        assertNotNull(classLoader.getResource("me/jellysquid/mods/sodium/client/SodiumClientMod.class"), "Sodium must remain a normal Fabric development-runtime dependency.");
        assertNotNull(classLoader.getResource("net/coderbot/iris/Iris.class"), "Iris must remain a normal Fabric development-runtime dependency.");
        assertNotNull(classLoader.getResource("org/joml/Matrix4f.class"), "Loom strips Sodium's nested JOML declaration while remapping the Modrinth artifact, so the development runtime must restore it explicitly.");
    }

    @Test
    void declaresRenderingCompatibilityRuntimeWithoutALaunchToggle() throws IOException {
        // This intentionally guards build and documentation text: the unwanted switch was a Gradle property, not Java behavior that can be reached through a runtime unit test.
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        String fabricBuild = Files.readString(projectDirectory.resolve("fabric/build.gradle"));
        assertTrue(fabricBuild.contains("modRuntimeOnly \"maven.modrinth:sodium:${sodium_version}\""));
        assertTrue(fabricBuild.contains("modRuntimeOnly \"maven.modrinth:iris:${iris_version}\""));
        assertTrue(fabricBuild.contains("runtimeOnly \"org.joml:joml:1.10.4\""));

        for (String relativePath : List.of("fabric/build.gradle", "gradle.properties", "README.md", "AGENTS.md")) {
            String source = Files.readString(projectDirectory.resolve(relativePath));
            assertFalse(source.contains("rinku.enableSodiumIris"), relativePath + " must not expose a permanent Sodium/Iris launch toggle");
        }

        String readme = Files.readString(projectDirectory.resolve("README.md"));
        assertFalse(readme.contains("Run the Fabric client"), "README.md must not contain generic Fabric launch instructions");
        assertFalse(readme.contains("Forge client"), "README.md must not contain generic Forge launch instructions");
        assertFalse(readme.contains("runClient"), "README.md must not expose generic client launch commands");
    }

}
