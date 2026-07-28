package de.keksuccino.rinku;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Performs the intentionally small platform, commit, and launch-file checks for one cache leaf. */
final class RinkuJcefInstallationValidator {
    static final String COMPLETE_MARKER_FILE = ".complete";
    static final String DISTRIBUTION_MANIFEST_FILE = "DISTRIBUTION-MANIFEST.json";

    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MARKER_BYTES = 256;
    private static final Pattern COMMIT_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final Set<String> IDENTITY_KEYS = Set.of("archive_root", "java_cef_commit", "target");

    private RinkuJcefInstallationValidator() {
    }

    static String normalizeCommit(String commit) {
        if (commit == null) {
            throw new IllegalArgumentException("java-cef commit hash is missing");
        }
        String normalized = commit.trim().toLowerCase(Locale.ROOT);
        if (!COMMIT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid full java-cef commit hash: " + commit);
        }
        return normalized;
    }

    static boolean isReusable(Path installation, OSPlatform platform, String expectedCommit) {
        try {
            validate(installation, platform, expectedCommit, true);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static void validateExtracted(Path installation, OSPlatform platform, String expectedCommit) throws IOException {
        validate(installation, platform, expectedCommit, false);
    }

    static void validateCompleted(Path installation, OSPlatform platform, String expectedCommit) throws IOException {
        validate(installation, platform, expectedCommit, true);
    }

    static void writeCompleteMarker(Path installation, OSPlatform platform, String expectedCommit) throws IOException {
        String normalizedCommit = normalizeCommit(expectedCommit);
        byte[] marker = markerContents(platform, normalizedCommit).getBytes(StandardCharsets.UTF_8);
        Path markerPath = installation.resolve(COMPLETE_MARKER_FILE);
        try (FileChannel channel = FileChannel.open(markerPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(marker);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void validate(Path installation, OSPlatform platform, String expectedCommit, boolean requireComplete) throws IOException {
        String normalizedCommit = normalizeCommit(expectedCommit);
        requireSafeDirectory(installation, "JCEF installation directory");
        Path marker = installation.resolve(COMPLETE_MARKER_FILE);
        if (requireComplete) {
            String actualMarker = readSmallUtf8(marker, MAX_MARKER_BYTES, "JCEF completion marker");
            if (!markerContents(platform, normalizedCommit).equals(actualMarker)) {
                throw new IOException("JCEF completion marker does not match the requested platform and commit");
            }
        } else if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Extracted JCEF archive collided with the installer-owned completion marker");
        }

        validateManifestIdentity(installation.resolve(DISTRIBUTION_MANIFEST_FILE), platform, normalizedCommit);
        for (String requiredFile : requiredFiles(platform)) {
            requireSafeNonemptyFile(installation, requiredFile);
        }
    }

    private static void validateManifestIdentity(Path manifestPath, OSPlatform platform, String expectedCommit) throws IOException {
        String manifest = readSmallUtf8(manifestPath, MAX_MANIFEST_BYTES, "JCEF distribution manifest");
        String archiveRoot = null;
        String commit = null;
        String target = null;
        Set<String> seenIdentityKeys = new HashSet<>();

        try (JsonReader reader = new JsonReader(new StringReader(manifest))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IOException("JCEF distribution manifest root is not an object");
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (IDENTITY_KEYS.contains(key) && !seenIdentityKeys.add(key)) {
                    throw new IOException("Duplicate JCEF distribution identity field: " + key);
                }
                switch (key) {
                    case "archive_root" -> archiveRoot = readJsonString(reader, key);
                    case "java_cef_commit" -> commit = readJsonString(reader, key);
                    case "target" -> target = readJsonString(reader, key);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Trailing data after JCEF distribution manifest");
            }
        } catch (IllegalStateException failure) {
            throw new IOException("Malformed JCEF distribution manifest", failure);
        }

        String platformName = platform.getNormalizedName();
        if (!seenIdentityKeys.equals(IDENTITY_KEYS)) {
            throw new IOException("JCEF distribution manifest has an incomplete identity");
        }
        if (!platformName.equals(target) || !platformName.equals(archiveRoot)) {
            throw new IOException("JCEF distribution manifest does not match platform " + platformName);
        }
        if (!expectedCommit.equals(commit)) {
            throw new IOException("JCEF distribution manifest does not match java-cef commit " + expectedCommit);
        }
    }

    private static String readJsonString(JsonReader reader, String key) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw new IOException("JCEF distribution manifest field is not a string: " + key);
        }
        return reader.nextString();
    }

    private static List<String> requiredFiles(OSPlatform platform) {
        List<String> required = new ArrayList<>();
        if (platform.isWindows()) {
            required.addAll(List.of("jcef.dll", "libcef.dll", "jcef_helper.exe", "chrome_elf.dll", "d3dcompiler_47.dll", "libEGL.dll", "libGLESv2.dll", "icudtl.dat", "locales/en-US.pak"));
        } else if (platform.isLinux()) {
            required.addAll(List.of("libjcef.so", "libcef.so", "jcef_helper", "chrome-sandbox", "icudtl.dat", "locales/en-US.pak"));
        } else {
            String contents = "jcef_app.app/Contents/";
            String frameworks = contents + "Frameworks/";
            required.add(contents + "MacOS/JavaAppLauncher");
            required.add(contents + "Java/libjcef.dylib");
            required.add(frameworks + "Chromium Embedded Framework.framework/Chromium Embedded Framework");
            required.add(frameworks + "Chromium Embedded Framework.framework/Resources/icudtl.dat");
            for (String suffix : List.of("", " (Alerts)", " (GPU)", " (Plugin)", " (Renderer)")) {
                String helper = "jcef Helper" + suffix;
                required.add(frameworks + helper + ".app/Contents/MacOS/" + helper);
            }
        }
        return List.copyOf(required);
    }

    private static void requireSafeNonemptyFile(Path root, String relativePath) throws IOException {
        Path current = root;
        String[] components = relativePath.split("/");
        for (int index = 0; index < components.length; index++) {
            current = current.resolve(components[index]);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException failure) {
                throw new IOException("Missing required JCEF file: " + relativePath, failure);
            }
            if (index + 1 < components.length) {
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                    throw new IOException("Unsafe parent directory for required JCEF file: " + relativePath);
                }
            } else if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() <= 0L) {
                throw new IOException("Missing, empty, or unsafe required JCEF file: " + relativePath);
            }
        }
    }

    private static void requireSafeDirectory(Path directory, String description) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IOException("Missing " + description + ": " + directory, failure);
        }
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Missing or unsafe " + description + ": " + directory);
        }
    }

    private static String readSmallUtf8(Path path, int maximumBytes, String description) throws IOException {
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IOException("Missing " + description + ": " + path, failure);
        }
        if (!before.isRegularFile() || before.isSymbolicLink() || before.size() <= 0L || before.size() > maximumBytes) {
            throw new IOException(description + " has an invalid size or type: " + path);
        }

        ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(before.size()));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != before.size()) {
                throw new IOException(description + " changed while opening: " + path);
            }
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) {
                    throw new IOException(description + " changed while reading: " + path);
                }
            }
            if (channel.size() != before.size()) {
                throw new IOException(description + " changed while reading: " + path);
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameFileSnapshot(before, after)) {
            throw new IOException(description + " changed while reading: " + path);
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes.flip());
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new IOException(description + " is not valid UTF-8", failure);
        }
    }

    private static boolean sameFileSnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        return after.isRegularFile() && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime()) && (beforeKey == null || afterKey == null || beforeKey.equals(afterKey));
    }

    private static String markerContents(OSPlatform platform, String commit) {
        return "rinku-jcef-v1\nplatform=" + platform.getNormalizedName() + "\ncommit=" + commit + "\n";
    }
}
