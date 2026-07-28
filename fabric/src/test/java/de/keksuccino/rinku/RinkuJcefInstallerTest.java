package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static de.keksuccino.rinku.RinkuInstallerTestSupport.COMMIT_A;
import static de.keksuccino.rinku.RinkuInstallerTestSupport.COMMIT_B;
import static de.keksuccino.rinku.RinkuInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuJcefInstallerTest {
    private static final Pattern STAGING_NAME_PATTERN = Pattern.compile("[0-9a-f]{40}-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @TempDir
    Path temporaryDirectory;

    @Test
    void completedExactCommitIsInstalledAtTheVersionedLeafAndReused() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("first");
        RinkuDownloader first = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), RinkuInstallerTestSupport.extractor("first"));

        RinkuDownloader.InstallationResult installed = first.installOrUpdate(false);

        Path expected = RinkuInstallerTestSupport.installationDirectory(temporaryDirectory.toRealPath(), PLATFORM, COMMIT_A);
        assertEquals(expected, installed.installationDirectory());
        assertTrue(installed.downloaded());
        assertTrue(Files.isRegularFile(expected.resolve(RinkuJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertTrue(Files.readString(expected.resolve(RinkuJcefInstallationValidator.COMPLETE_MARKER_FILE), StandardCharsets.UTF_8).startsWith("rinku-jcef-v1\n"));
        assertTrue(Files.isDirectory(expected.getParent().resolve(".staging")));

        RinkuDownloader reuse = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, RinkuInstallerTestSupport.archiveBytes("unused"), "0".repeat(64), (archiveInput, outputDirectory) -> { throw new AssertionError("completed exact commit must not be extracted again"); });
        RinkuDownloader.InstallationResult reused = reuse.installOrUpdate(false);

        assertEquals(expected, reused.installationDirectory());
        assertFalse(reused.downloaded());
        assertEquals("first", RinkuInstallerTestSupport.readVersion(reused.installationDirectory()));
    }

    @Test
    void incompleteExactCommitAndAbandonedStagingAreReplacedSafely() throws Exception {
        Path incomplete = RinkuInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        RinkuInstallerTestSupport.writeRuntimeInstallation(incomplete, PLATFORM, "incomplete", COMMIT_A);
        Path stagingRoot = incomplete.getParent().resolve(".staging");
        Path abandoned = stagingRoot.resolve(COMMIT_A + "-00000000-0000-4000-8000-000000000000");
        Path unrelated = stagingRoot.resolve("not-an-installer-stage");
        Files.createDirectories(abandoned.resolve("nested"));
        Files.writeString(abandoned.resolve("nested/residue"), "partial", StandardCharsets.UTF_8);
        Files.createDirectories(unrelated);
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("repaired");
        RinkuDownloader repair = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), RinkuInstallerTestSupport.extractor("repaired"));

        RinkuDownloader.InstallationResult result = repair.installOrUpdate(false);

        assertTrue(result.downloaded());
        assertEquals("repaired", RinkuInstallerTestSupport.readVersion(result.installationDirectory()));
        assertTrue(Files.isRegularFile(result.installationDirectory().resolve(RinkuJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertFalse(Files.exists(abandoned));
        assertTrue(Files.isDirectory(unrelated));
        assertFalse(hasStagingDirectories(incomplete.getParent()));
    }

    @Test
    void freshWorkUsesACommitScopedChildOfThePlatformStagingContainer() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("staging-layout");
        AtomicReference<Path> extractionRoot = new AtomicReference<>();
        RinkuDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            extractionRoot.set(outputDirectory.toPath().toAbsolutePath().normalize());
            RinkuInstallerTestSupport.writeRuntimeInstallation(outputDirectory.toPath().resolve(PLATFORM.getNormalizedName()), PLATFORM, "staging-layout", COMMIT_A);
        };
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), extractor);

        Path installation = downloader.installOrUpdate(false).installationDirectory();

        Path stagingChild = extractionRoot.get().getParent();
        assertEquals(installation.getParent().resolve(".staging"), stagingChild.getParent());
        assertTrue(STAGING_NAME_PATTERN.matcher(stagingChild.getFileName().toString()).matches());
        assertTrue(stagingChild.getFileName().toString().startsWith(COMMIT_A + "-"));
        assertFalse(Files.exists(stagingChild));
    }

    @Test
    void symbolicLinkCannotSubstituteForThePlatformStagingContainer() throws Exception {
        Path installation = RinkuInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        Path platformDirectory = installation.getParent();
        Path outside = temporaryDirectory.resolve("outside-staging");
        Files.createDirectories(platformDirectory);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("sentinel"), "untouched", StandardCharsets.UTF_8);
        Files.createSymbolicLink(platformDirectory.resolve(".staging"), outside);
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("unsafe-staging");
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), (archiveInput, outputDirectory) -> { throw new AssertionError("unsafe staging root must fail before extraction"); });

        assertThrows(IOException.class, () -> downloader.installOrUpdate(false));

        assertEquals("untouched", Files.readString(outside.resolve("sentinel"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(installation));
    }

    @Test
    void fullCommitLeavesAreIsolatedAndOlderCompletedCommitsAreNeverDeleted() throws Exception {
        byte[] archiveA = RinkuInstallerTestSupport.archiveBytes("commit-a");
        byte[] archiveB = RinkuInstallerTestSupport.archiveBytes("commit-b");
        RinkuDownloader downloaderA = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archiveA, RinkuInstallerTestSupport.sha256(archiveA), RinkuInstallerTestSupport.extractor("commit-a", COMMIT_A));
        RinkuDownloader downloaderB = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_B, archiveB, RinkuInstallerTestSupport.sha256(archiveB), RinkuInstallerTestSupport.extractor("commit-b", COMMIT_B));

        Path installationA = downloaderA.installOrUpdate(false).installationDirectory();
        Path installationB = downloaderB.installOrUpdate(false).installationDirectory();

        assertEquals(COMMIT_A, installationA.getFileName().toString());
        assertEquals(COMMIT_B, installationB.getFileName().toString());
        assertTrue(Files.isRegularFile(installationA.resolve(RinkuJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertTrue(Files.isRegularFile(installationB.resolve(RinkuJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertEquals("commit-a", RinkuInstallerTestSupport.readVersion(installationA));
        assertEquals("commit-b", RinkuInstallerTestSupport.readVersion(installationB));
    }

    @Test
    void concurrentInstallersPublishOnceAndTheContenderReusesTheLeaf() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("concurrent");
        String digest = RinkuInstallerTestSupport.sha256(archive);
        AtomicInteger extractions = new AtomicInteger();
        RinkuDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            extractions.incrementAndGet();
            RinkuInstallerTestSupport.writeRuntimeInstallation(outputDirectory.toPath().resolve(PLATFORM.getNormalizedName()), PLATFORM, "concurrent", COMMIT_A);
        };
        RinkuDownloader first = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, extractor);
        RinkuDownloader second = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, extractor);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RinkuDownloader.InstallationResult> firstResult = executor.submit(() -> { start.await(); return first.installOrUpdate(false); });
            Future<RinkuDownloader.InstallationResult> secondResult = executor.submit(() -> { start.await(); return second.installOrUpdate(false); });
            start.countDown();
            List<RinkuDownloader.InstallationResult> results = List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));

            assertEquals(1L, results.stream().filter(RinkuDownloader.InstallationResult::downloaded).count());
            assertEquals(1, extractions.get());
            assertEquals(results.get(0).installationDirectory(), results.get(1).installationDirectory());
        }
    }

    @Test
    void failedValidationLeavesNoBlessedLeafAndReleasesTheLockForRetry() throws Exception {
        byte[] brokenArchive = RinkuInstallerTestSupport.archiveBytes("wrong-commit");
        RinkuDownloader broken = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, brokenArchive, RinkuInstallerTestSupport.sha256(brokenArchive), RinkuInstallerTestSupport.extractor("wrong-commit", COMMIT_B));

        assertThrows(IOException.class, () -> broken.installOrUpdate(false));

        Path installation = RinkuInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        assertFalse(Files.exists(installation));
        assertFalse(hasStagingDirectories(installation.getParent()));

        byte[] validArchive = RinkuInstallerTestSupport.archiveBytes("retry");
        RinkuDownloader retry = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, validArchive, RinkuInstallerTestSupport.sha256(validArchive), RinkuInstallerTestSupport.extractor("retry"));
        assertTrue(retry.installOrUpdate(false).downloaded());
    }

    @Test
    void checksumMismatchCannotReachExtractionOrPublishACommitLeaf() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("checksum-mismatch");
        AtomicInteger extractions = new AtomicInteger();
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, "f".repeat(64), (archiveInput, outputDirectory) -> extractions.incrementAndGet());

        assertThrows(IOException.class, () -> downloader.installOrUpdate(false));

        Path installation = RinkuInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        assertEquals(0, extractions.get());
        assertFalse(Files.exists(installation));
        assertFalse(hasStagingDirectories(installation.getParent()));
    }

    @Test
    void archiveCannotSupplyTheInstallerOwnedCompletionMarker() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("forged-marker");
        RinkuDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            Path installation = outputDirectory.toPath().resolve(PLATFORM.getNormalizedName());
            RinkuInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "forged-marker", COMMIT_A);
            Files.writeString(installation.resolve(RinkuJcefInstallationValidator.COMPLETE_MARKER_FILE), "forged", StandardCharsets.UTF_8);
        };
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), extractor);

        assertThrows(IOException.class, () -> downloader.installOrUpdate(false));

        Path installation = RinkuInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        assertFalse(Files.exists(installation));
        assertFalse(hasStagingDirectories(installation.getParent()));
    }

    @Test
    void matchingReleaseIdentityAllowsNewManifestMetadataAndNoUnusedTopLevelJcefJar() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("future-manifest");
        RinkuDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            Path installation = outputDirectory.toPath().resolve(PLATFORM.getNormalizedName());
            RinkuInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "future-manifest", COMMIT_A);
            Path manifest = installation.resolve(RinkuJcefInstallationValidator.DISTRIBUTION_MANIFEST_FILE);
            String updatedManifest = Files.readString(manifest, StandardCharsets.UTF_8).replace("\"java_release\": 17", "\"java_release\": 25").replace("\"manifest_schema\": 2", "\"manifest_schema\": 99");
            Files.writeString(manifest, updatedManifest, StandardCharsets.UTF_8);
            Files.delete(installation.resolve("jcef.jar"));
        };
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), extractor);

        Path installation = downloader.installOrUpdate(false).installationDirectory();

        assertEquals("future-manifest", RinkuInstallerTestSupport.readVersion(installation));
        assertFalse(Files.exists(installation.resolve("jcef.jar")));
        RinkuJcefInstallationValidator.validateCompleted(installation, PLATFORM, COMMIT_A);
    }

    @Test
    void staleCompletionMarkerCannotBlessMissingRuntimeFiles() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("complete");
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), RinkuInstallerTestSupport.extractor("complete"));
        Path installation = downloader.installOrUpdate(false).installationDirectory();
        Files.delete(installation.resolve("jcef_app.app/Contents/Java/libjcef.dylib"));

        RinkuDownloader localOnly = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), RinkuInstallerTestSupport.extractor("must-not-run"));

        assertThrows(IOException.class, () -> localOnly.installOrUpdate(true));
    }

    @Test
    void symbolicLinkCannotSubstituteForTheCompletionMarker() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.archiveBytes("marker-link");
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), RinkuInstallerTestSupport.extractor("marker-link"));
        Path installation = downloader.installOrUpdate(false).installationDirectory();
        Path marker = installation.resolve(RinkuJcefInstallationValidator.COMPLETE_MARKER_FILE);
        Path outside = temporaryDirectory.resolve("outside-marker");
        Files.move(marker, outside);
        Files.createSymbolicLink(marker, outside);

        RinkuDownloader localOnly = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), RinkuInstallerTestSupport.extractor("must-not-run"));

        assertThrows(IOException.class, () -> localOnly.installOrUpdate(true));
        assertTrue(Files.isSymbolicLink(marker));
    }

    @Test
    void operatingSystemLockCoversTheWholeInstallerLifetime() throws Exception {
        Path lockFile;
        try (RinkuJcefInstaller installer = new RinkuJcefInstaller(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
            lockFile = installer.lockFile();
            assertEquals("LOCKED", probeLock(lockFile));
        }
        assertEquals("UNLOCKED", probeLock(lockFile));
    }

    private static boolean hasStagingDirectories(Path platformDirectory) throws IOException {
        Path stagingRoot = platformDirectory.resolve(".staging");
        if (!Files.isDirectory(stagingRoot)) {
            return false;
        }
        try (var entries = Files.newDirectoryStream(stagingRoot)) {
            for (Path entry : entries) {
                if (STAGING_NAME_PATTERN.matcher(entry.getFileName().toString()).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String probeLock(Path lockFile) throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-cp", childProbeClasspath(), RinkuFileLockProbeMain.class.getName(), lockFile.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String childProbeClasspath() throws Exception {
        return Path.of(RinkuFileLockProbeMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
