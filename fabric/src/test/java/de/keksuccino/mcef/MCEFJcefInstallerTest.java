package de.keksuccino.mcef;

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

import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_A;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.COMMIT_B;
import static de.keksuccino.mcef.MCEFInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFJcefInstallerTest {
    private static final Pattern STAGING_NAME_PATTERN = Pattern.compile("[0-9a-f]{40}-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @TempDir
    Path temporaryDirectory;

    @Test
    void completedExactCommitIsInstalledAtTheVersionedLeafAndReused() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("first");
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), MCEFInstallerTestSupport.extractor("first"));

        MCEFDownloader.InstallationResult installed = first.installOrUpdate(false);

        Path expected = MCEFInstallerTestSupport.installationDirectory(temporaryDirectory.toRealPath(), PLATFORM, COMMIT_A);
        assertEquals(expected, installed.installationDirectory());
        assertTrue(installed.downloaded());
        assertTrue(Files.isRegularFile(expected.resolve(MCEFJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertTrue(Files.isDirectory(expected.getParent().resolve(".staging")));

        MCEFDownloader reuse = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, MCEFInstallerTestSupport.archiveBytes("unused"), "0".repeat(64), (archiveInput, outputDirectory) -> { throw new AssertionError("completed exact commit must not be extracted again"); });
        MCEFDownloader.InstallationResult reused = reuse.installOrUpdate(false);

        assertEquals(expected, reused.installationDirectory());
        assertFalse(reused.downloaded());
        assertEquals("first", MCEFInstallerTestSupport.readVersion(reused.installationDirectory()));
    }

    @Test
    void incompleteExactCommitAndAbandonedStagingAreReplacedSafely() throws Exception {
        Path incomplete = MCEFInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        MCEFInstallerTestSupport.writeRuntimeInstallation(incomplete, PLATFORM, "incomplete", COMMIT_A);
        Path stagingRoot = incomplete.getParent().resolve(".staging");
        Path abandoned = stagingRoot.resolve(COMMIT_A + "-00000000-0000-4000-8000-000000000000");
        Path unrelated = stagingRoot.resolve("not-an-installer-stage");
        Files.createDirectories(abandoned.resolve("nested"));
        Files.writeString(abandoned.resolve("nested/residue"), "partial", StandardCharsets.UTF_8);
        Files.createDirectories(unrelated);
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("repaired");
        MCEFDownloader repair = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), MCEFInstallerTestSupport.extractor("repaired"));

        MCEFDownloader.InstallationResult result = repair.installOrUpdate(false);

        assertTrue(result.downloaded());
        assertEquals("repaired", MCEFInstallerTestSupport.readVersion(result.installationDirectory()));
        assertTrue(Files.isRegularFile(result.installationDirectory().resolve(MCEFJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertFalse(Files.exists(abandoned));
        assertTrue(Files.isDirectory(unrelated));
        assertFalse(hasStagingDirectories(incomplete.getParent()));
    }

    @Test
    void freshWorkUsesACommitScopedChildOfThePlatformStagingContainer() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("staging-layout");
        AtomicReference<Path> extractionRoot = new AtomicReference<>();
        MCEFDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            extractionRoot.set(outputDirectory.toPath().toAbsolutePath().normalize());
            MCEFInstallerTestSupport.writeRuntimeInstallation(outputDirectory.toPath().resolve(PLATFORM.getNormalizedName()), PLATFORM, "staging-layout", COMMIT_A);
        };
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), extractor);

        Path installation = downloader.installOrUpdate(false).installationDirectory();

        Path stagingChild = extractionRoot.get().getParent();
        assertEquals(installation.getParent().resolve(".staging"), stagingChild.getParent());
        assertTrue(STAGING_NAME_PATTERN.matcher(stagingChild.getFileName().toString()).matches());
        assertTrue(stagingChild.getFileName().toString().startsWith(COMMIT_A + "-"));
        assertFalse(Files.exists(stagingChild));
    }

    @Test
    void symbolicLinkCannotSubstituteForThePlatformStagingContainer() throws Exception {
        Path installation = MCEFInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        Path platformDirectory = installation.getParent();
        Path outside = temporaryDirectory.resolve("outside-staging");
        Files.createDirectories(platformDirectory);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("sentinel"), "untouched", StandardCharsets.UTF_8);
        Files.createSymbolicLink(platformDirectory.resolve(".staging"), outside);
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("unsafe-staging");
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), (archiveInput, outputDirectory) -> { throw new AssertionError("unsafe staging root must fail before extraction"); });

        assertThrows(IOException.class, () -> downloader.installOrUpdate(false));

        assertEquals("untouched", Files.readString(outside.resolve("sentinel"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(installation));
    }

    @Test
    void fullCommitLeavesAreIsolatedAndOlderCompletedCommitsAreNeverDeleted() throws Exception {
        byte[] archiveA = MCEFInstallerTestSupport.archiveBytes("commit-a");
        byte[] archiveB = MCEFInstallerTestSupport.archiveBytes("commit-b");
        MCEFDownloader downloaderA = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archiveA, MCEFInstallerTestSupport.sha256(archiveA), MCEFInstallerTestSupport.extractor("commit-a", COMMIT_A));
        MCEFDownloader downloaderB = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_B, archiveB, MCEFInstallerTestSupport.sha256(archiveB), MCEFInstallerTestSupport.extractor("commit-b", COMMIT_B));

        Path installationA = downloaderA.installOrUpdate(false).installationDirectory();
        Path installationB = downloaderB.installOrUpdate(false).installationDirectory();

        assertEquals(COMMIT_A, installationA.getFileName().toString());
        assertEquals(COMMIT_B, installationB.getFileName().toString());
        assertTrue(Files.isRegularFile(installationA.resolve(MCEFJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertTrue(Files.isRegularFile(installationB.resolve(MCEFJcefInstallationValidator.COMPLETE_MARKER_FILE)));
        assertEquals("commit-a", MCEFInstallerTestSupport.readVersion(installationA));
        assertEquals("commit-b", MCEFInstallerTestSupport.readVersion(installationB));
    }

    @Test
    void concurrentInstallersPublishOnceAndTheContenderReusesTheLeaf() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("concurrent");
        String digest = MCEFInstallerTestSupport.sha256(archive);
        AtomicInteger extractions = new AtomicInteger();
        MCEFDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            extractions.incrementAndGet();
            MCEFInstallerTestSupport.writeRuntimeInstallation(outputDirectory.toPath().resolve(PLATFORM.getNormalizedName()), PLATFORM, "concurrent", COMMIT_A);
        };
        MCEFDownloader first = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, extractor);
        MCEFDownloader second = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, digest, extractor);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MCEFDownloader.InstallationResult> firstResult = executor.submit(() -> { start.await(); return first.installOrUpdate(false); });
            Future<MCEFDownloader.InstallationResult> secondResult = executor.submit(() -> { start.await(); return second.installOrUpdate(false); });
            start.countDown();
            List<MCEFDownloader.InstallationResult> results = List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));

            assertEquals(1L, results.stream().filter(MCEFDownloader.InstallationResult::downloaded).count());
            assertEquals(1, extractions.get());
            assertEquals(results.get(0).installationDirectory(), results.get(1).installationDirectory());
        }
    }

    @Test
    void failedValidationLeavesNoBlessedLeafAndReleasesTheLockForRetry() throws Exception {
        byte[] brokenArchive = MCEFInstallerTestSupport.archiveBytes("wrong-commit");
        MCEFDownloader broken = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, brokenArchive, MCEFInstallerTestSupport.sha256(brokenArchive), MCEFInstallerTestSupport.extractor("wrong-commit", COMMIT_B));

        assertThrows(IOException.class, () -> broken.installOrUpdate(false));

        Path installation = MCEFInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        assertFalse(Files.exists(installation));
        assertFalse(hasStagingDirectories(installation.getParent()));

        byte[] validArchive = MCEFInstallerTestSupport.archiveBytes("retry");
        MCEFDownloader retry = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, validArchive, MCEFInstallerTestSupport.sha256(validArchive), MCEFInstallerTestSupport.extractor("retry"));
        assertTrue(retry.installOrUpdate(false).downloaded());
    }

    @Test
    void checksumMismatchCannotReachExtractionOrPublishACommitLeaf() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("checksum-mismatch");
        AtomicInteger extractions = new AtomicInteger();
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, "f".repeat(64), (archiveInput, outputDirectory) -> extractions.incrementAndGet());

        assertThrows(IOException.class, () -> downloader.installOrUpdate(false));

        Path installation = MCEFInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        assertEquals(0, extractions.get());
        assertFalse(Files.exists(installation));
        assertFalse(hasStagingDirectories(installation.getParent()));
    }

    @Test
    void archiveCannotSupplyTheInstallerOwnedCompletionMarker() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("forged-marker");
        MCEFDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            Path installation = outputDirectory.toPath().resolve(PLATFORM.getNormalizedName());
            MCEFInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "forged-marker", COMMIT_A);
            Files.writeString(installation.resolve(MCEFJcefInstallationValidator.COMPLETE_MARKER_FILE), "forged", StandardCharsets.UTF_8);
        };
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), extractor);

        assertThrows(IOException.class, () -> downloader.installOrUpdate(false));

        Path installation = MCEFInstallerTestSupport.installationDirectory(temporaryDirectory, PLATFORM, COMMIT_A);
        assertFalse(Files.exists(installation));
        assertFalse(hasStagingDirectories(installation.getParent()));
    }

    @Test
    void matchingReleaseIdentityAllowsNewManifestMetadataAndNoUnusedTopLevelJcefJar() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("future-manifest");
        MCEFDownloader.ArchiveExtractor extractor = (archiveInput, outputDirectory) -> {
            Path installation = outputDirectory.toPath().resolve(PLATFORM.getNormalizedName());
            MCEFInstallerTestSupport.writeRuntimeInstallation(installation, PLATFORM, "future-manifest", COMMIT_A);
            Path manifest = installation.resolve(MCEFJcefInstallationValidator.DISTRIBUTION_MANIFEST_FILE);
            String updatedManifest = Files.readString(manifest, StandardCharsets.UTF_8).replace("\"java_release\": 17", "\"java_release\": 25").replace("\"manifest_schema\": 2", "\"manifest_schema\": 99");
            Files.writeString(manifest, updatedManifest, StandardCharsets.UTF_8);
            Files.delete(installation.resolve("jcef.jar"));
        };
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), extractor);

        Path installation = downloader.installOrUpdate(false).installationDirectory();

        assertEquals("future-manifest", MCEFInstallerTestSupport.readVersion(installation));
        assertFalse(Files.exists(installation.resolve("jcef.jar")));
        MCEFJcefInstallationValidator.validateCompleted(installation, PLATFORM, COMMIT_A);
    }

    @Test
    void staleCompletionMarkerCannotBlessMissingRuntimeFiles() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("complete");
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), MCEFInstallerTestSupport.extractor("complete"));
        Path installation = downloader.installOrUpdate(false).installationDirectory();
        Files.delete(installation.resolve("jcef_app.app/Contents/Java/libjcef.dylib"));

        MCEFDownloader localOnly = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), MCEFInstallerTestSupport.extractor("must-not-run"));

        assertThrows(IOException.class, () -> localOnly.installOrUpdate(true));
    }

    @Test
    void symbolicLinkCannotSubstituteForTheCompletionMarker() throws Exception {
        byte[] archive = MCEFInstallerTestSupport.archiveBytes("marker-link");
        MCEFDownloader downloader = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), MCEFInstallerTestSupport.extractor("marker-link"));
        Path installation = downloader.installOrUpdate(false).installationDirectory();
        Path marker = installation.resolve(MCEFJcefInstallationValidator.COMPLETE_MARKER_FILE);
        Path outside = temporaryDirectory.resolve("outside-marker");
        Files.move(marker, outside);
        Files.createSymbolicLink(marker, outside);

        MCEFDownloader localOnly = MCEFInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, MCEFInstallerTestSupport.sha256(archive), MCEFInstallerTestSupport.extractor("must-not-run"));

        assertThrows(IOException.class, () -> localOnly.installOrUpdate(true));
        assertTrue(Files.isSymbolicLink(marker));
    }

    @Test
    void operatingSystemLockCoversTheWholeInstallerLifetime() throws Exception {
        Path lockFile;
        try (MCEFJcefInstaller installer = new MCEFJcefInstaller(temporaryDirectory, PLATFORM, COMMIT_A, failure -> {})) {
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
        Process process = new ProcessBuilder(javaExecutable(), "-cp", childProbeClasspath(), MCEFFileLockProbeMain.class.getName(), lockFile.toString()).redirectErrorStream(true).start();
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
        return Path.of(MCEFFileLockProbeMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
