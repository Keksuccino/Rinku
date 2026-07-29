package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderCompatibilityDependencyContractTest {

    @Test
    void fabricDevelopmentRuntimeUnconditionallyUsesPinnedSodiumAndIris() throws IOException {
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        Properties properties = new Properties();
        try (var input = Files.newInputStream(projectDirectory.resolve("gradle.properties"))) {
            properties.load(input);
        }

        assertEquals("mc26.2-0.9.1-fabric", properties.getProperty("sodium_version"));
        assertEquals("1.11.2+26.2-fabric", properties.getProperty("iris_version"));

        String fabricBuildScript = Files.readString(projectDirectory.resolve("fabric/build.gradle"));
        assertTrue(fabricBuildScript.contains("runtimeOnly \"maven.modrinth:sodium:${sodium_version}\""));
        assertTrue(fabricBuildScript.contains("runtimeOnly \"maven.modrinth:iris:${iris_version}\""));
        assertTrue(fabricBuildScript.contains("tasks.register(\"verifyRenderCompatibilityRuntime\")"));
        assertFalse(fabricBuildScript.contains("rinku.enableRenderCompatibilityMods"));

        String rootBuildScript = Files.readString(projectDirectory.resolve("build.gradle"));
        assertTrue(rootBuildScript.contains("\"sodium_version\": sodium_version"));
        assertTrue(rootBuildScript.contains("\"iris_version\": iris_version"));

        String readme = Files.readString(projectDirectory.resolve("README.md"));
        assertFalse(readme.contains("rinku.enableRenderCompatibilityMods"));
        assertFalse(readme.contains("To run the Fabric client:"));
        assertFalse(readme.contains("To run the NeoForge client:"));
    }
}
