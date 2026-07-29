package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricDevelopmentDependenciesContractTest {

    @Test
    void sodiumAndIrisAreUnconditionalDevelopmentModsWithoutLaunchOptions() throws IOException {
        Path projectDirectory = findProjectDirectory();
        String fabricBuildScript = Files.readString(projectDirectory.resolve("fabric/build.gradle"));
        String properties = Files.readString(projectDirectory.resolve("gradle.properties"));
        String readme = Files.readString(projectDirectory.resolve("README.md"));

        assertTrue(fabricBuildScript.contains("modRuntimeOnly \"maven.modrinth:AANobbMI:${sodium_modrinth_version}\""));
        assertTrue(fabricBuildScript.contains("modRuntimeOnly \"maven.modrinth:YL57xq9U:${iris_modrinth_version}\""));
        assertFalse(fabricBuildScript.contains("enableRenderingCompatMods"));
        assertFalse(properties.contains("enableRenderingCompatMods"));
        assertFalse(readme.contains("To run the Fabric client"));
        assertFalse(readme.contains("To run the NeoForge client"));
    }

    private static Path findProjectDirectory() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("fabric/build.gradle"))) return directory;
            directory = directory.getParent();
        }
        throw new IOException("Could not locate the project directory");
    }
}
