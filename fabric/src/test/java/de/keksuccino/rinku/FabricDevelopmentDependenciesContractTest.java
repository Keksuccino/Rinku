package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricDevelopmentDependenciesContractTest {

    @Test
    void sodiumAndIrisAreUnconditionalDevelopmentRuntimeDependencies() throws IOException {
        Path projectDirectory = projectDirectory();
        String build = Files.readString(projectDirectory.resolve("fabric/build.gradle"));

        assertTrue(build.contains("\n    modLocalRuntime \"maven.modrinth:sodium:${sodium_version}\"\n"));
        assertTrue(build.contains("\n    modLocalRuntime \"maven.modrinth:iris:${iris_version}\"\n"));
        assertFalse(build.contains("disableRenderingCompatMods"));
    }

    @Test
    void repositoryGuidanceExposesNoRenderingCompatibilityLaunchToggle() throws IOException {
        Path projectDirectory = projectDirectory();
        String readme = Files.readString(projectDirectory.resolve("README.md"));

        assertFalse(readme.contains("disableRenderingCompatMods"));
        assertFalse(readme.contains(":fabric:runClient"));
        assertFalse(readme.contains(":forge:Client"));
        assertFalse(Files.readString(projectDirectory.resolve("AGENTS.md")).contains("disableRenderingCompatMods"));
    }

    private static Path projectDirectory() {
        return Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
    }
}
