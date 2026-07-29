package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderCompatibilityDependencyContractTest {

    @Test
    void fabricDevelopmentRuntimeAlwaysIncludesSodiumAndIrisWithoutLaunchToggles() throws IOException {
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        String fabricBuildScript = Files.readString(projectDirectory.resolve("fabric/build.gradle"));
        String readme = Files.readString(projectDirectory.resolve("README.md"));

        assertTrue(fabricBuildScript.contains("runtimeOnly \"maven.modrinth:sodium:${sodium_version}\""));
        assertTrue(fabricBuildScript.contains("runtimeOnly \"maven.modrinth:iris:${iris_version}\""));
        assertFalse(fabricBuildScript.contains("enableRenderCompatibilityMods"));
        assertFalse(readme.contains("enableRenderCompatibilityMods"));
        assertFalse(readme.contains("To run the Fabric client"));
        assertFalse(readme.contains("To run the NeoForge client"));
    }
}
