package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static de.keksuccino.rinku.RinkuInstallerTestSupport.COMMIT_A;
import static de.keksuccino.rinku.RinkuInstallerTestSupport.PLATFORM;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuArchiveExtractionTest {
    private static final Set<PosixFilePermission> MODE_0644 = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
    private static final Set<PosixFilePermission> MODE_0755 = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);

    @TempDir
    Path temporaryDirectory;

    @Test
    void realArchiveWithExpectedRuntimeLayoutIsInstalled() throws Exception {
        byte[] archive = RinkuInstallerTestSupport.runtimeTar("real");
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(temporaryDirectory, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), null);

        RinkuDownloader.InstallationResult result = downloader.installOrUpdate(false);

        assertTrue(result.downloaded());
        assertEquals("real", RinkuInstallerTestSupport.readVersion(result.installationDirectory()));
        RinkuJcefInstallationValidator.validateCompleted(result.installationDirectory(), PLATFORM, COMMIT_A);
    }

    @Test
    void traversalAndAbsoluteArchivePathsAreRejectedWithoutTouchingOutsideFiles() throws Exception {
        Path sentinel = temporaryDirectory.resolve("outside.txt");
        Files.writeString(sentinel, "unchanged", StandardCharsets.UTF_8);
        byte[] parentTraversal;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            parentTraversal = builder.file("../outside.txt", "overwritten").finish();
        }
        byte[] backslashTraversal;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            backslashTraversal = builder.file(PLATFORM.getNormalizedName() + "\\..\\outside.txt", "overwritten").finish();
        }
        byte[] absolute;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            absolute = builder.file("/absolute.txt", "overwritten").finish();
        }

        assertRejected(temporaryDirectory.resolve("parent"), parentTraversal);
        assertRejected(temporaryDirectory.resolve("backslash"), backslashTraversal);
        assertRejected(temporaryDirectory.resolve("absolute"), absolute);
        assertEquals("unchanged", Files.readString(sentinel, StandardCharsets.UTF_8));
    }

    @Test
    void symbolicLinksAndSpecialEntriesAreRejected() throws Exception {
        byte[] symlink;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            symlink = builder.symbolicLink(PLATFORM.getNormalizedName() + "/link", "../../outside").finish();
        }
        byte[] fifo;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            fifo = builder.fifo(PLATFORM.getNormalizedName() + "/pipe").finish();
        }

        assertRejected(temporaryDirectory.resolve("symlink"), symlink);
        assertRejected(temporaryDirectory.resolve("fifo"), fifo);
    }

    @Test
    void directoryPayloadCannotBypassExtractedSizeAccounting() throws Exception {
        byte[] directoryPayload = RinkuInstallerTestSupport.directoryPayloadTar("unexpected".getBytes(StandardCharsets.UTF_8));

        assertRejected(temporaryDirectory.resolve("directory-payload"), directoryPayload);
    }

    @Test
    void oversizedAndSparsePaxExtensionsAreRejectedBeforeEntryPublication() throws Exception {
        byte[] oversizedPax;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            oversizedPax = builder.paxExtension(new byte[1_048_577]).file(PLATFORM.getNormalizedName() + "/file", "content").finish();
        }
        byte[] sparsePax;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            sparsePax = builder.paxExtension(RinkuInstallerTestSupport.paxRecord("GNU.sparse.major", "1")).file(PLATFORM.getNormalizedName() + "/file", "content").finish();
        }

        IOException oversizedFailure = reject(temporaryDirectory.resolve("oversized-pax"), oversizedPax);
        IOException sparseFailure = reject(temporaryDirectory.resolve("sparse-pax"), sparsePax);
        assertTrue(oversizedFailure.getMessage().contains("metadata limit"));
        assertTrue(sparseFailure.getMessage().contains("Sparse PAX"));
    }

    @Test
    void duplicateCaseCollisionAndFilePrefixConflictAreRejected() throws Exception {
        byte[] duplicate;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            duplicate = builder.file(PLATFORM.getNormalizedName() + "/same", "one").file(PLATFORM.getNormalizedName() + "/same", "two").finish();
        }
        byte[] caseCollision;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            caseCollision = builder.file(PLATFORM.getNormalizedName() + "/Case", "one").file(PLATFORM.getNormalizedName() + "/case", "two").finish();
        }
        byte[] prefixConflict;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            prefixConflict = builder.file(PLATFORM.getNormalizedName() + "/parent", "file").file(PLATFORM.getNormalizedName() + "/parent/child", "child").finish();
        }

        assertRejected(temporaryDirectory.resolve("duplicate"), duplicate);
        assertRejected(temporaryDirectory.resolve("case"), caseCollision);
        assertRejected(temporaryDirectory.resolve("prefix"), prefixConflict);
    }

    @Test
    void wrongTopLevelAndExcessiveDepthAreRejected() throws Exception {
        byte[] wrongRoot;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            wrongRoot = builder.file("wrong-platform/file", "content").finish();
        }
        StringBuilder deepName = new StringBuilder(PLATFORM.getNormalizedName());
        for (int index = 0; index < 65; index++) {
            deepName.append("/d").append(index);
        }
        byte[] tooDeep;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            tooDeep = builder.file(deepName.toString(), "content").finish();
        }

        assertRejected(temporaryDirectory.resolve("wrong-root"), wrongRoot);
        assertRejected(temporaryDirectory.resolve("deep"), tooDeep);
    }

    @Test
    void implicitDirectoryExpansionCannotExceedTheBoundedCleanupCapacity() throws Exception {
        byte[] excessiveFilesystemTree;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            for (int branch = 0; branch < 130; branch++) {
                StringBuilder path = new StringBuilder(PLATFORM.getNormalizedName()).append("/branch-").append(branch);
                for (int depth = 0; depth < 61; depth++) {
                    path.append("/d").append(depth);
                }
                builder.file(path.append("/file").toString(), "content");
            }
            excessiveFilesystemTree = builder.finish();
        }

        IOException failure = reject(temporaryDirectory.resolve("filesystem-entry-limit"), excessiveFilesystemTree);

        assertTrue(failure.getMessage().contains("too many filesystem entries"));
    }

    @Test
    void linuxArchiveModesBecomeCanonicalAndSpecialBitsAreStripped() throws Exception {
        assumePosixFileStore();
        RinkuPlatform platform = RinkuPlatform.LINUX_AMD64;
        String rootName = platform.getNormalizedName();
        byte[] archive;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            archive = builder.directory(rootName, 07777)
                    .file(rootName + "/chrome-sandbox", "sandbox", 04755)
                    .file(rootName + "/jcef_helper", "helper", 0100)
                    .file(rootName + "/resources.pak", "data", 06644)
                    .finish();
        }

        Path output = extractDirectly("linux-modes", archive, platform);
        Path root = output.resolve(rootName);

        assertEquals(MODE_0755, Files.getPosixFilePermissions(root));
        assertEquals(MODE_0755, Files.getPosixFilePermissions(root.resolve("chrome-sandbox")));
        assertEquals(MODE_0755, Files.getPosixFilePermissions(root.resolve("jcef_helper")));
        assertEquals(MODE_0644, Files.getPosixFilePermissions(root.resolve("resources.pak")));
    }

    @Test
    void macArchiveModesCoverLauncherFrameworkAndEveryHelper() throws Exception {
        assumePosixFileStore();
        RinkuPlatform platform = RinkuPlatform.MACOS_ARM64;
        String rootName = platform.getNormalizedName();
        List<String> executablePaths = List.of("jcef_app.app/Contents/MacOS/JavaAppLauncher", "jcef_app.app/Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework", "jcef_app.app/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper", "jcef_app.app/Contents/Frameworks/jcef Helper (Alerts).app/Contents/MacOS/jcef Helper (Alerts)", "jcef_app.app/Contents/Frameworks/jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)", "jcef_app.app/Contents/Frameworks/jcef Helper (Plugin).app/Contents/MacOS/jcef Helper (Plugin)", "jcef_app.app/Contents/Frameworks/jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)");
        byte[] archive;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            for (String executablePath : executablePaths) {
                builder.file(rootName + "/" + executablePath, executablePath, 07111);
            }
            builder.file(rootName + "/jcef_app.app/Contents/Info.plist", "metadata", 0666);
            archive = builder.finish();
        }

        Path root = extractDirectly("mac-modes", archive, platform).resolve(rootName);

        for (String executablePath : executablePaths) {
            assertEquals(MODE_0755, Files.getPosixFilePermissions(root.resolve(executablePath)));
        }
        assertEquals(MODE_0755, Files.getPosixFilePermissions(root.resolve("jcef_app.app/Contents/Frameworks")));
        assertEquals(MODE_0644, Files.getPosixFilePermissions(root.resolve("jcef_app.app/Contents/Info.plist")));
    }

    @Test
    void windowsTargetLeavesHostFileCreationModesUnchanged() throws Exception {
        assumePosixFileStore();
        RinkuPlatform platform = RinkuPlatform.WINDOWS_AMD64;
        String rootName = platform.getNormalizedName();
        byte[] archive;
        try (RinkuInstallerTestSupport.TarBuilder builder = new RinkuInstallerTestSupport.TarBuilder()) {
            archive = builder.file(rootName + "/executable-in-tar.exe", "executable", 0777).file(rootName + "/non-executable-in-tar.dat", "data", 0000).finish();
        }

        Path root = extractDirectly("windows-modes", archive, platform).resolve(rootName);

        assertEquals(Files.getPosixFilePermissions(root.resolve("non-executable-in-tar.dat")), Files.getPosixFilePermissions(root.resolve("executable-in-tar.exe")));
    }

    private void assertRejected(Path libraries, byte[] archive) throws Exception {
        reject(libraries, archive);
    }

    private IOException reject(Path libraries, byte[] archive) throws Exception {
        Files.createDirectories(libraries);
        RinkuDownloader downloader = RinkuInstallerTestSupport.downloader(libraries, COMMIT_A, archive, RinkuInstallerTestSupport.sha256(archive), null);

        IOException failure = assertThrows(IOException.class, () -> downloader.installOrUpdate(false));
        assertFalse(Files.exists(RinkuInstallerTestSupport.installationDirectory(libraries, PLATFORM, COMMIT_A)));
        return failure;
    }

    private Path extractDirectly(String name, byte[] archive, RinkuPlatform platform) throws Exception {
        Path directory = temporaryDirectory.resolve(name);
        Path output = directory.resolve("output");
        Files.createDirectories(output);
        Path archiveFile = directory.resolve("runtime.tar.gz");
        Files.write(archiveFile, archive);
        RinkuSecureArchiveExtractor.extract(archiveFile.toFile(), output.toFile(), platform, RinkuDownloader.DownloadPolicy.defaults(), ignored -> {});
        return output;
    }

    private void assumePosixFileStore() throws IOException {
        assumeTrue(Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null);
    }
}
