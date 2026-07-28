package de.keksuccino.rinku;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RinkuInstallerTestSupport {
    static final String COMMIT_A = "0123456789abcdef0123456789abcdef01234567";
    static final String COMMIT_B = "89abcdef0123456789abcdef0123456789abcdef";
    static final RinkuPlatform PLATFORM = RinkuPlatform.MACOS_ARM64;
    private static final String[] MAC_HELPERS = {"jcef Helper", "jcef Helper (Alerts)", "jcef Helper (GPU)", "jcef Helper (Plugin)", "jcef Helper (Renderer)"};
    private static final List<String> COMMON_RUNTIME_ROOTS = List.of("chrome_100_percent.pak", "chrome_200_percent.pak", "icudtl.dat", "locales", "resources.pak", "v8_context_snapshot.bin");
    private static final List<String> LINUX_RUNTIME_ROOTS = List.of("chrome-sandbox", "jcef_helper", "libEGL.so", "libGLESv2.so", "libcef.so", "libjcef.so", "libvk_swiftshader.so", "libvulkan.so.1", "vk_swiftshader_icd.json");
    private static final List<String> WINDOWS_RUNTIME_ROOTS = List.of("chrome_elf.dll", "d3dcompiler_47.dll", "jcef.dll", "jcef_helper.exe", "libEGL.dll", "libGLESv2.dll", "libcef.dll", "vk_swiftshader.dll", "vk_swiftshader_icd.json", "vulkan-1.dll");

    private RinkuInstallerTestSupport() {
    }

    static RinkuDownloader downloader(Path libraries, String commit, byte[] archive, String checksum, RinkuDownloader.DownloadPolicy policy, RinkuDownloader.ArchiveExtractor extractor) {
        RinkuDownloader.ArtifactDownloader downloads = (urlTemplate, outputFile, maxBytes) -> {
            if (outputFile.getName().endsWith(".sha256")) {
                if (checksum == null) {
                    throw new IOException("injected checksum endpoint failure");
                }
                writeChecksum(outputFile.toPath(), checksum);
            } else {
                Files.write(outputFile.toPath(), archive);
            }
        };
        return new RinkuDownloader(RinkuDownloader.OFFICIAL_MIRROR, commit, PLATFORM, policy, libraries, downloads, extractor);
    }

    static RinkuDownloader downloader(Path libraries, String commit, byte[] archive, String checksum, RinkuDownloader.ArchiveExtractor extractor) {
        return downloader(libraries, commit, archive, checksum, RinkuDownloader.DownloadPolicy.defaults(), extractor);
    }

    static RinkuDownloader.ArchiveExtractor extractor(String version) {
        return extractor(version, COMMIT_A);
    }

    static RinkuDownloader.ArchiveExtractor extractor(String version, String commit) {
        return (archive, outputDirectory) -> writeRuntimeInstallation(outputDirectory.toPath().resolve(PLATFORM.getNormalizedName()), PLATFORM, version, commit);
    }

    static Path installationDirectory(Path librariesDirectory, RinkuPlatform platform, String commit) {
        return librariesDirectory.toAbsolutePath().normalize().resolve("jcef-v1").resolve(platform.getNormalizedName()).resolve(RinkuJcefInstallationValidator.normalizeCommit(commit));
    }

    static void writeRuntimeInstallation(Path installation, RinkuPlatform platform, String version, String commit) throws IOException {
        Map<String, byte[]> runtimeFiles = runtimeFiles(platform, version);
        Map<String, byte[]> distributionFiles = distributionFiles(platform, runtimeFiles, version);
        for (Map.Entry<String, byte[]> distributionFile : distributionFiles.entrySet()) {
            Path path = installation.resolve(distributionFile.getKey().replace('/', java.io.File.separatorChar));
            Files.createDirectories(path.getParent());
            Files.write(path, distributionFile.getValue());
        }
        Files.writeString(installation.resolve(RinkuJcefInstallationValidator.DISTRIBUTION_MANIFEST_FILE), manifestJson(platform, commit, runtimeFiles, version), StandardCharsets.UTF_8);
    }

    static String readVersion(Path installation) throws IOException {
        return Files.readString(installation.resolve("README.txt"), StandardCharsets.UTF_8);
    }

    static void writeChecksum(Path path, String digest) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, digest + "  " + PLATFORM.getNormalizedName() + ".tar.gz\n", StandardCharsets.US_ASCII);
    }

    static String sha256(byte[] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static byte[] archiveBytes(String version) {
        return ("archive-" + version).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] runtimeTar(String version) throws IOException {
        try (TarBuilder builder = new TarBuilder()) {
            String root = PLATFORM.getNormalizedName();
            Map<String, byte[]> runtimeFiles = runtimeFiles(PLATFORM, version);
            Map<String, byte[]> distributionFiles = distributionFiles(PLATFORM, runtimeFiles, version);
            List<String> distributionDirectories = distributionDirectories(distributionFiles);
            builder.directory(root, 0755);
            for (String directory : distributionDirectories) {
                builder.directory(root + "/" + directory, 0755);
            }
            for (Map.Entry<String, byte[]> distributionFile : distributionFiles.entrySet()) {
                builder.file(root + "/" + distributionFile.getKey(), distributionFile.getValue());
            }
            builder.file(root + "/" + RinkuJcefInstallationValidator.DISTRIBUTION_MANIFEST_FILE, manifestJson(PLATFORM, COMMIT_A, runtimeFiles, version));
            return builder.finish();
        }
    }

    static String manifestJson(RinkuPlatform platform, String commit, Map<String, byte[]> runtimeFiles, String version) {
        Map<String, byte[]> distributionFiles = distributionFiles(platform, runtimeFiles, version);
        return manifestJson(platform, commit, runtimeFiles, runtimeEntries(platform), distributionFiles, distributionDirectories(distributionFiles));
    }

    static String manifestJson(RinkuPlatform platform, String commit, Map<String, byte[]> runtimeFiles, List<String> entries, String version) {
        Map<String, byte[]> distributionFiles = distributionFiles(platform, runtimeFiles, version);
        return manifestJson(platform, commit, runtimeFiles, entries, distributionFiles, distributionDirectories(distributionFiles));
    }

    static String manifestJson(RinkuPlatform platform, String commit, Map<String, byte[]> runtimeFiles, List<String> entries, Map<String, byte[]> distributionFiles, List<String> distributionDirectories) {
        List<String> jogampJars = jogampJars(platform);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"archive_root\": \"").append(platform.getNormalizedName()).append("\",\n");
        json.append("  \"cef_api_version\": \"15100\",\n");
        json.append("  \"cef_version\": \"151.2.3+g89cd581+chromium-151.0.7922.34\",\n");
        json.append("  \"distribution_directories\": ").append(jsonStringArray(distributionDirectories)).append(",\n");
        appendManifestFiles(json, "distribution_files", distributionFiles);
        json.append("  \"java_cef_commit\": \"").append(commit).append("\",\n");
        json.append("  \"java_release\": 17,\n");
        json.append("  \"jogl_swing_osr_supported\": ").append(platform != RinkuPlatform.WINDOWS_ARM64).append(",\n");
        json.append("  \"jogamp_jars\": ").append(jsonStringArray(jogampJars)).append(",\n");
        json.append("  \"jcef_jars\": [\"jcef.jar\", \"jcef-tests.jar\"],\n");
        json.append("  \"manifest_schema\": 2,\n");
        json.append("  \"runtime_entries\": ").append(jsonStringArray(entries)).append(",\n");
        appendManifestFiles(json, "runtime_files", runtimeFiles);
        json.append("  \"target\": \"").append(platform.getNormalizedName()).append("\"\n");
        json.append("}\n");
        return json.toString();
    }

    static Map<String, byte[]> distributionFiles(RinkuPlatform platform, Map<String, byte[]> runtimeFiles, String version) {
        Map<String, byte[]> files = new LinkedHashMap<>(runtimeFiles);
        files.putAll(canonicalAncillaryFiles(platform, version));
        return files.entrySet().stream().sorted(Map.Entry.comparingByKey()).collect(LinkedHashMap::new, (result, entry) -> result.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
    }

    private static Map<String, byte[]> canonicalAncillaryFiles(RinkuPlatform platform, String version) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : List.of("CEF-LICENSE.txt", "CREDITS.html", "LICENSE.txt")) {
            files.put(name, bytes(name + "-" + version));
        }
        files.put("README.txt", bytes(version));
        files.put("docs/index.html", bytes("documentation-" + version));
        files.put("tests/README.txt", bytes("tests-" + version));
        files.put("jcef.jar", bytes("jcef-" + version));
        files.put("jcef-tests.jar", bytes("jcef-tests-" + version));
        files.put(platform.isWindows() ? "java17_check.bat" : "java17_check.sh", bytes("java-check-" + version));
        files.put(platform.isWindows() ? "compile.bat" : "compile.sh", bytes("compile-" + version));
        if (!platform.isMacOS()) {
            files.put(platform.isWindows() ? "run.bat" : "run.sh", bytes("run-" + version));
        }
        for (String jar : jogampJars(platform)) {
            files.put(jar, bytes(jar + "-" + version));
        }
        if (!jogampJars(platform).isEmpty()) {
            files.put("gluegen.LICENSE.txt", bytes("gluegen-license-" + version));
            files.put("jogl.LICENSE.txt", bytes("jogl-license-" + version));
        }
        return files;
    }

    static List<String> distributionDirectories(Map<String, byte[]> distributionFiles) {
        java.util.Set<String> directories = new java.util.TreeSet<>();
        for (String path : distributionFiles.keySet()) {
            int separator = path.indexOf('/');
            while (separator >= 0) {
                directories.add(path.substring(0, separator));
                separator = path.indexOf('/', separator + 1);
            }
        }
        return List.copyOf(directories);
    }

    private static void appendManifestFiles(StringBuilder json, String key, Map<String, byte[]> files) {
        List<String> paths = files.keySet().stream().sorted().toList();
        json.append("  \"").append(key).append("\": [\n");
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            byte[] contents = files.get(path);
            json.append("    {\"path\": \"").append(jsonEscape(path)).append("\", \"sha256\": \"").append(sha256(contents)).append("\", \"size\": ").append(contents.length).append('}');
            json.append(index + 1 == paths.size() ? "\n" : ",\n");
        }
        json.append("  ],\n");
    }

    static Map<String, byte[]> runtimeFiles(RinkuPlatform platform, String version) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (platform.isMacOS()) {
            String app = "jcef_app.app/Contents/";
            String framework = app + "Frameworks/Chromium Embedded Framework.framework/";
            files.put(app + "Info.plist", bytes("app-info-" + version));
            files.put(app + "MacOS/JavaAppLauncher", bytes("launcher-" + version));
            files.put(app + "Java/libjcef.dylib", bytes("native-jcef-" + version));
            files.put(app + "Java/jcef.jar", bytes("app-jcef-" + version));
            files.put(app + "Java/jcef-tests.jar", bytes("app-jcef-tests-" + version));
            for (String jar : jogampJars(platform)) {
                files.put(app + "Java/" + jar, bytes("app-" + jar + "-" + version));
            }
            files.put(app + "_CodeSignature/CodeResources", bytes("app-signature-" + version));
            files.put(framework + "Chromium Embedded Framework", bytes("cef-framework-" + version));
            files.put(framework + "_CodeSignature/CodeResources", bytes("framework-signature-" + version));
            files.put(framework + "Libraries/libEGL.dylib", bytes("egl-" + version));
            files.put(framework + "Libraries/libGLESv2.dylib", bytes("gles-" + version));
            files.put(framework + "Libraries/libvk_swiftshader.dylib", bytes("swiftshader-" + version));
            files.put(framework + "Libraries/vk_swiftshader_icd.json", bytes("swiftshader-config-" + version));
            files.put(framework + "Resources/Info.plist", bytes("framework-info-" + version));
            files.put(framework + "Resources/chrome_100_percent.pak", bytes("chrome-100-" + version));
            files.put(framework + "Resources/chrome_200_percent.pak", bytes("chrome-200-" + version));
            files.put(framework + "Resources/resources.pak", bytes("resources-" + version));
            files.put(framework + "Resources/icudtl.dat", bytes("icu-data-" + version));
            files.put(framework + "Resources/v8_context_snapshot." + (platform == RinkuPlatform.MACOS_AMD64 ? "x86_64" : "arm64") + ".bin", bytes("snapshot-" + version));
            files.put(framework + "Resources/en.lproj/locale.pak", bytes("locale-" + version));
            for (String helper : MAC_HELPERS) {
                String helperRoot = app + "Frameworks/" + helper + ".app/Contents/";
                files.put(helperRoot + "Info.plist", bytes(helper + "-info-" + version));
                files.put(helperRoot + "MacOS/" + helper, bytes(helper + "-" + version));
                files.put(helperRoot + "_CodeSignature/CodeResources", bytes(helper + "-signature-" + version));
            }
            return files.entrySet().stream().sorted(Map.Entry.comparingByKey()).collect(LinkedHashMap::new, (result, entry) -> result.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
        }

        for (String entry : runtimeEntries(platform)) {
            if (entry.equals("locales")) {
                files.put("locales/en-US.pak", bytes("locale-" + version));
            } else {
                files.put(entry, bytes(entry + "-" + version));
            }
        }
        return files;
    }

    static List<String> runtimeEntries(RinkuPlatform platform) {
        if (platform.isMacOS()) {
            return List.of("jcef_app.app");
        }
        List<String> entries = new ArrayList<>(COMMON_RUNTIME_ROOTS);
        entries.addAll(platform.isLinux() ? LINUX_RUNTIME_ROOTS : WINDOWS_RUNTIME_ROOTS);
        if (platform == RinkuPlatform.WINDOWS_AMD64) {
            entries.add("dxcompiler.dll");
            entries.add("dxil.dll");
        }
        entries.sort(Comparator.naturalOrder());
        return List.copyOf(entries);
    }

    private static List<String> jogampJars(RinkuPlatform platform) {
        String suffix = switch (platform) {
            case LINUX_AMD64 -> "linux-amd64";
            case LINUX_ARM64 -> "linux-aarch64";
            case MACOS_AMD64, MACOS_ARM64 -> "macosx-universal";
            case WINDOWS_AMD64 -> "windows-amd64";
            case WINDOWS_ARM64 -> null;
        };
        if (suffix == null) {
            return List.of();
        }
        return List.of("gluegen-rt.jar", "jogl-all.jar", "gluegen-rt-natives-" + suffix + ".jar", "jogl-all-natives-" + suffix + ".jar");
    }

    private static String jsonStringArray(List<String> values) {
        return values.stream().map(value -> "\"" + jsonEscape(value) + "\"").collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] directoryPayloadTar(byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(PLATFORM.getNormalizedName() + "/", TarArchiveEntry.LF_DIR);
        entry.setSize(content.length);
        byte[] header = new byte[512];
        entry.writeEntryHeader(header);
        ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
        tarBytes.write(header);
        tarBytes.write(content);
        int padding = (512 - content.length % 512) % 512;
        tarBytes.write(new byte[padding + 1024]);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(compressed)) {
            tarBytes.writeTo(gzip);
        }
        return compressed.toByteArray();
    }

    static byte[] paxRecord(String key, String value) {
        String payload = " " + key + "=" + value + "\n";
        int length = payload.length() + 1;
        while (Integer.toString(length).length() + payload.length() != length) {
            length = Integer.toString(length).length() + payload.length();
        }
        return (length + payload).getBytes(StandardCharsets.US_ASCII);
    }

    static final class TarBuilder implements AutoCloseable {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final GzipCompressorOutputStream gzip;
        private final TarArchiveOutputStream tar;
        private boolean finished;

        TarBuilder() throws IOException {
            gzip = new GzipCompressorOutputStream(bytes);
            tar = new TarArchiveOutputStream(gzip);
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        }

        TarBuilder file(String name, String content) throws IOException {
            return file(name, content.getBytes(StandardCharsets.UTF_8));
        }

        TarBuilder file(String name, byte[] content) throws IOException {
            return file(name, content, 0644);
        }

        TarBuilder file(String name, String content, int mode) throws IOException {
            return file(name, content.getBytes(StandardCharsets.UTF_8), mode);
        }

        TarBuilder file(String name, byte[] content, int mode) throws IOException {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setMode(mode);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            return this;
        }

        TarBuilder directory(String name, int mode) throws IOException {
            String directoryName = name.endsWith("/") ? name : name + "/";
            TarArchiveEntry entry = new TarArchiveEntry(directoryName, TarArchiveEntry.LF_DIR);
            entry.setMode(mode);
            entry.setSize(0L);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
            return this;
        }

        TarBuilder symbolicLink(String name, String target) throws IOException {
            TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
            entry.setLinkName(target);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
            return this;
        }

        TarBuilder paxExtension(byte[] content) throws IOException {
            TarArchiveEntry entry = new TarArchiveEntry("PaxHeaders/rinku", TarArchiveEntry.LF_PAX_EXTENDED_HEADER_LC);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            return this;
        }

        TarBuilder fifo(String name) throws IOException {
            TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_FIFO);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
            return this;
        }

        byte[] finish() throws IOException {
            if (!finished) {
                finished = true;
                tar.finish();
                tar.close();
            }
            return bytes.toByteArray();
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                finish();
            }
        }
    }
}
