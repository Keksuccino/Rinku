package com.cinemamod.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JcefArchiveProvenanceTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsSourceMutationAfterArchiveCopy() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build();
        Path source = temporaryDirectory.resolve("Source.java");
        Files.writeString(source, "final class Source {}\n");
        Jar archive = project.getTasks().create("provenanceArchive", Jar.class);
        archive.from(source.toFile());
        archive.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("libs"));
        archive.getArchiveFileName().set("provenance.jar");
        Files.createDirectories(archive.getDestinationDirectory().get().getAsFile().toPath());
        Provider<RegularFile> commitFile = project.getLayout().getBuildDirectory().file("jcef-provenance/java-cef-commit.txt");
        AtomicInteger validations = new AtomicInteger();

        // This action is appended after Jar's copy action and before the production post-copy guard.
        archive.doLast(ignored -> {
            try {
                Files.writeString(source, "final class Changed {}\n");
            } catch (java.io.IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        JcefArchiveProvenance.configure(archive, project.getTasks().register("captureJcefProvenance"), commitFile, () -> {
            validations.incrementAndGet();
            try {
                if (!Files.readString(source).equals("final class Source {}\n")) {
                    throw new GradleException("java-cef changed after provenance capture");
                }
            } catch (java.io.IOException failure) {
                throw new GradleException("Could not validate java-cef source", failure);
            }
            return COMMIT;
        });

        GradleException failure = assertThrows(GradleException.class, () -> executeActions(archive));

        assertTrue(failure.getMessage().contains("changed after provenance capture"), failure.getMessage());
        assertEquals(2, validations.get());
        try (JarFile copiedArchive = new JarFile(archive.getArchiveFile().get().getAsFile())) {
            assertEquals(COMMIT, copiedArchive.getManifest().getMainAttributes().getValue("java-cef-commit"));
            assertTrue(copiedArchive.getEntry("Source.java") != null);
        }
    }

    private static void executeActions(Task task) {
        for (var action : task.getActions()) {
            action.execute(task);
        }
    }
}
