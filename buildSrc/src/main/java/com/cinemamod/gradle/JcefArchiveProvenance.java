package com.cinemamod.gradle;

import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.bundling.Jar;

import java.util.Map;
import java.util.Objects;

/** Brackets every archive copy with validation of the exact JCEF source provenance. */
public final class JcefArchiveProvenance {
    private static final String COMMIT_ATTRIBUTE = "java-cef-commit";

    private JcefArchiveProvenance() {
    }

    public static void configure(Jar archiveTask, Object captureDependency, Provider<RegularFile> commitFile, CommitRevalidator revalidator) {
        Objects.requireNonNull(archiveTask, "JCEF provenance archive task must not be null");
        Objects.requireNonNull(captureDependency, "JCEF provenance capture dependency must not be null");
        Objects.requireNonNull(commitFile, "JCEF provenance commit file must not be null");
        Objects.requireNonNull(revalidator, "JCEF provenance revalidator must not be null");

        archiveTask.dependsOn(captureDependency);
        archiveTask.getInputs().file(commitFile);
        archiveTask.doFirst(ignored -> archiveTask.getManifest().attributes(Map.of(COMMIT_ATTRIBUTE, revalidator.revalidate())));
        // sourcesJar copies live JCEF files. Revalidate after the copy as well so a concurrent
        // source mutation cannot leave a successfully attested archive with mismatched contents.
        archiveTask.doLast(ignored -> revalidator.revalidate());
    }

    @FunctionalInterface
    public interface CommitRevalidator {
        String revalidate();
    }
}
