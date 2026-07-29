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

class ReleaseConfigurationContractTest {

    private static final String EXPECTED_MOD_VERSION = "3.0.0";
    private static final String EXPECTED_MINECRAFT_VERSION = "26.2";

    @Test
    void rootAndModVersionsMatchAndGradleRejectsDivergence() throws IOException {
        Path projectDirectory = projectDirectory();
        Properties properties = new Properties();
        try (var input = Files.newInputStream(projectDirectory.resolve("gradle.properties"))) {
            properties.load(input);
        }

        assertEquals(EXPECTED_MOD_VERSION, properties.getProperty("version"));
        assertEquals(EXPECTED_MOD_VERSION, properties.getProperty("mod_version"));
        assertEquals(EXPECTED_MINECRAFT_VERSION, properties.getProperty("minecraft_version"));

        String buildScript = Files.readString(projectDirectory.resolve("build.gradle"));
        assertTrue(buildScript.contains("if (version.toString() != mod_version)"));
        assertTrue(buildScript.contains("Project version ${version} must match Rinku mod version ${mod_version}."));
    }

    @Test
    void releaseDocumentationAndUploadConfigurationUseCurrentCoordinates() throws IOException {
        Path projectDirectory = projectDirectory();
        String readme = Files.readString(projectDirectory.resolve("README.md"));
        String uploadConfiguration = Files.readString(projectDirectory.resolve("mod_upload_config.json"));

        assertTrue(readme.contains("de.keksuccino:rinku-fabric:3.0.0-26.2"));
        assertTrue(readme.contains("de.keksuccino:rinku-neoforge:3.0.0-26.2"));
        assertFalse(readme.contains("2.2.1-26.2"));
        assertTrue(uploadConfiguration.contains("\"changelog_url\": \"https://github.com/Keksuccino/Rinku/commits/26.2.0\""));
        assertFalse(uploadConfiguration.contains("Keksuccino/mcef"));
    }

    @Test
    void standaloneMavenPublisherFallsBackToModVersionWithoutChangingUploadToolVersion() throws IOException {
        Path projectDirectory = projectDirectory();
        String publisher = Files.readString(projectDirectory.resolve("publish_to_maven.py"));
        String uploadTool = Files.readString(projectDirectory.resolve("mod_upload.py"));

        assertTrue(publisher.contains("project_version=properties.get(\"version\") or properties[\"mod_version\"]"));
        assertFalse(publisher.contains("project_version=properties.get(\"version\") or \"1.0.0\""));
        assertTrue(uploadTool.contains("SCRIPT_VERSION = \"1.0.0\""));
    }

    private static Path projectDirectory() {
        return Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
    }
}
