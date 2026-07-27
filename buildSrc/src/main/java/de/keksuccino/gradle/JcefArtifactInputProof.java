package de.keksuccino.gradle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Proves that the exact JCEF files consumed by Gradle match raw blobs from one HEAD.
 *
 * <p>This helper is intentionally mirrored in the runtime source set. The buildSrc and project
 * classloaders are compiled independently, so sharing the implementation would create a build
 * cycle; keep both helpers and their focused tests behaviorally aligned.</p>
 */
final class JcefArtifactInputProof {
    private static final Pattern OBJECT_ID_PATTERN = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final String GLUEGEN_JAR_PATH = "third_party/jogamp/jar/gluegen-rt.jar";
    private static final String JOGL_JAR_PATH = "third_party/jogamp/jar/jogl-all.jar";
    private static final List<String> TREE_PATHS = List.of("java", GLUEGEN_JAR_PATH, JOGL_JAR_PATH);
    private static final Set<String> REQUIRED_JARS = Set.of(GLUEGEN_JAR_PATH, JOGL_JAR_PATH);
    private static final Map<String, String> IMMUTABLE_OBJECT_ENVIRONMENT = Map.of("GIT_NO_REPLACE_OBJECTS", "1", "GIT_OPTIONAL_LOCKS", "0");
    private static final int MAX_INPUT_COUNT = 8192;
    private static final int MAX_TREE_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_JAVA_INPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_JAR_INPUT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TOTAL_INPUT_BYTES = 128 * 1024 * 1024;
    private static final int MAX_BATCH_OUTPUT_BYTES = MAX_TOTAL_INPUT_BYTES + 1024 * 1024;

    private JcefArtifactInputProof() {
    }

    static void requireMatchesHead(Path repository, String expectedHead, JcefProvenance.GitCommandRunner gitRunner) {
        String objectFormat = gitRunner.run(repository, List.of("rev-parse", "--show-object-format"), true, IMMUTABLE_OBJECT_ENVIRONMENT).output().trim();
        requireObjectFormatMatchesCommit(objectFormat, expectedHead);

        List<TreeEntry> treeEntries = readTree(repository, expectedHead, objectFormat, gitRunner);
        List<byte[]> committedBlobs = readBlobs(repository, treeEntries, objectFormat, gitRunner);
        InputSnapshot before = readInputs(repository, treeEntries);
        requireSamePaths(treeEntries, before);
        requireMatchingContent(treeEntries, committedBlobs, before);

        requireSameSnapshot(before, readInputs(repository, treeEntries));
        requireHeadUnchanged(repository, expectedHead, gitRunner);
        requireSameSnapshot(before, readInputs(repository, treeEntries));
        requireHeadUnchanged(repository, expectedHead, gitRunner);
    }

    private static List<TreeEntry> readTree(Path repository, String head, String objectFormat, JcefProvenance.GitCommandRunner gitRunner) {
        List<String> arguments = new ArrayList<>(TREE_PATHS.size() + 6);
        arguments.addAll(List.of("ls-tree", "-r", "-z", "--full-tree", head, "--"));
        arguments.addAll(TREE_PATHS);
        byte[] rawOutput = gitRunner.runBinary(repository, arguments, new byte[0], true, IMMUTABLE_OBJECT_ENVIRONMENT, MAX_TREE_OUTPUT_BYTES).output();
        String output = decodeTreeOutput(rawOutput);
        List<TreeEntry> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        int offset = 0;
        while (offset < output.length()) {
            int terminator = output.indexOf('\0', offset);
            if (terminator < 0) {
                throw new IllegalStateException("Git returned an unterminated java-cef artifact tree entry.");
            }
            String record = output.substring(offset, terminator);
            offset = terminator + 1;
            int tab = record.indexOf('\t');
            if (tab <= 0) {
                throw new IllegalStateException("Git returned an invalid java-cef artifact tree entry.");
            }
            String[] metadata = record.substring(0, tab).split(" ", -1);
            String path = record.substring(tab + 1);
            if (metadata.length != 3) {
                throw new IllegalStateException("Git returned an invalid java-cef artifact tree entry for " + path + ".");
            }
            if (!isInputPath(path)) {
                continue;
            }
            if (!paths.add(path)) {
                throw new IllegalStateException("Git returned a duplicate java-cef artifact tree entry for " + path + ".");
            }
            if ((!metadata[0].equals("100644") && !metadata[0].equals("100755")) || !metadata[1].equals("blob")) {
                throw new IllegalStateException("java-cef artifact input " + path + " is not a regular tracked file; symbolic links and submodules cannot provide reproducible compiler input.");
            }
            requireObjectId(metadata[2], objectFormat, path);
            entries.add(new TreeEntry(path, metadata[2]));
            if (entries.size() > MAX_INPUT_COUNT) {
                throw new IllegalStateException("java-cef artifact input count exceeds the provenance limit.");
            }
        }
        entries.sort(Comparator.comparing(TreeEntry::path));
        for (String requiredPath : REQUIRED_JARS) {
            if (!paths.contains(requiredPath)) {
                throw new IllegalStateException("Required java-cef compiler classpath input is not tracked by HEAD: " + requiredPath + ".");
            }
        }
        return List.copyOf(entries);
    }

    private static List<byte[]> readBlobs(Path repository, List<TreeEntry> entries, String objectFormat, JcefProvenance.GitCommandRunner gitRunner) {
        StringBuilder request = new StringBuilder();
        for (TreeEntry entry : entries) {
            request.append(entry.objectId()).append('\n');
        }
        byte[] output = gitRunner.runBinary(repository, List.of("cat-file", "--batch"), request.toString().getBytes(StandardCharsets.US_ASCII), true, IMMUTABLE_OBJECT_ENVIRONMENT, MAX_BATCH_OUTPUT_BYTES).output();
        List<byte[]> blobs = new ArrayList<>(entries.size());
        int offset = 0;
        long totalBytes = 0L;
        for (TreeEntry entry : entries) {
            int lineEnd = indexOf(output, (byte) '\n', offset);
            if (lineEnd < 0) {
                throw new IllegalStateException("Git returned a truncated batch header for java-cef artifact input " + entry.path() + ".");
            }
            String[] fields = new String(output, offset, lineEnd - offset, StandardCharsets.US_ASCII).split(" ", -1);
            if (fields.length != 3 || !fields[0].equals(entry.objectId()) || !fields[1].equals("blob")) {
                throw new IllegalStateException("Git returned an invalid batch header for java-cef artifact input " + entry.path() + ".");
            }
            int size = parseBlobSize(fields[2], entry.path());
            int maximumBytes = REQUIRED_JARS.contains(entry.path()) ? MAX_JAR_INPUT_BYTES : MAX_JAVA_INPUT_BYTES;
            if (size > maximumBytes) {
                throw new IllegalStateException("Committed java-cef artifact input exceeds the provenance size limit: " + entry.path() + ".");
            }
            totalBytes = addInputBytes(totalBytes, size);
            int contentStart = lineEnd + 1;
            long contentEnd = (long) contentStart + size;
            if (contentEnd >= output.length || output[(int) contentEnd] != '\n') {
                throw new IllegalStateException("Git returned truncated blob content for java-cef artifact input " + entry.path() + ".");
            }
            byte[] content = Arrays.copyOfRange(output, contentStart, (int) contentEnd);
            requireBlobObjectId(content, entry.objectId(), objectFormat, entry.path());
            blobs.add(content);
            offset = (int) contentEnd + 1;
        }
        if (offset != output.length) {
            throw new IllegalStateException("Git returned unexpected trailing data while reading java-cef artifact blobs.");
        }
        return List.copyOf(blobs);
    }

    private static InputSnapshot readInputs(Path repository, List<TreeEntry> treeEntries) {
        Set<String> expectedPaths = new HashSet<>();
        for (TreeEntry entry : treeEntries) {
            expectedPaths.add(entry.path());
        }
        int maximumWalkEntries = Math.min(MAX_INPUT_COUNT * 8, Math.max(256, treeEntries.size() * 8 + 256));
        int[] walkedEntries = {0};
        long[] totalBytes = {0L};
        TreeMap<String, InputFile> files = new TreeMap<>();
        Path javaRoot = repository.resolve("java");
        BasicFileAttributes rootAttributes = readAttributes(javaRoot, "java-cef Java source root is unavailable at ");
        if (!rootAttributes.isDirectory() || rootAttributes.isSymbolicLink()) {
            throw new IllegalStateException("java-cef Java source root is not a real directory at " + javaRoot + ".");
        }
        try {
            Files.walkFileTree(javaRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    requireWalkBudget(++walkedEntries[0], maximumWalkEntries);
                    return !directory.equals(javaRoot) && containsTestsDirectory(javaRoot.relativize(directory)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    requireWalkBudget(++walkedEntries[0], maximumWalkEntries);
                    Path relative = javaRoot.relativize(file);
                    if (containsTestsDirectory(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attributes.isSymbolicLink()) {
                        throw new IllegalStateException("Symbolic link inside the java-cef Java input tree cannot be proven reproducible: " + file + ".");
                    }
                    if (file.getFileName().toString().endsWith(".java")) {
                        if (!attributes.isRegularFile()) {
                            throw new IllegalStateException("java-cef Java input is not a regular file: " + file + ".");
                        }
                        String gitPath = "java/" + toGitPath(relative);
                        if (!expectedPaths.contains(gitPath)) {
                            throw new IllegalStateException("Untracked or ignored java-cef artifact input prevents commit provenance: " + gitPath + ".");
                        }
                        InputFile input = readInput(file, gitPath, MAX_JAVA_INPUT_BYTES);
                        totalBytes[0] = addInputBytes(totalBytes[0], input.content().length);
                        files.put(gitPath, input);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    throw new IllegalStateException("Failed to inspect potential java-cef Java input at " + file + ".", failure);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to enumerate java-cef Java compiler inputs at " + javaRoot + ".", e);
        }
        for (String jarPath : REQUIRED_JARS) {
            InputFile input = readInput(repository.resolve(jarPath), jarPath, MAX_JAR_INPUT_BYTES);
            totalBytes[0] = addInputBytes(totalBytes[0], input.content().length);
            files.put(jarPath, input);
        }
        return new InputSnapshot(Map.copyOf(files));
    }

    private static InputFile readInput(Path path, String gitPath, int maximumBytes) {
        BasicFileAttributes before = readAttributes(path, "Required java-cef artifact input is unavailable at ");
        if (!before.isRegularFile() || before.isSymbolicLink()) {
            throw new IllegalStateException("Required java-cef artifact input is not a regular file at " + path + ".");
        }
        if (before.size() > maximumBytes) {
            throw new IllegalStateException("java-cef artifact input exceeds the provenance size limit: " + gitPath + ".");
        }
        byte[] content;
        try {
            content = readBoundedFile(path, maximumBytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read java-cef artifact input at " + path + ".", e);
        }
        BasicFileAttributes after = readAttributes(path, "java-cef artifact input changed while being read at ");
        if (!sameFile(before, after) || content.length != before.size()) {
            throw new IllegalStateException("java-cef artifact input changed while being read at " + path + ".");
        }
        return new InputFile(content, sha256(content));
    }

    private static byte[] readBoundedFile(Path path, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((long) output.size() + count > maximumBytes) {
                    throw new IOException("java-cef artifact input exceeds its provenance size limit at " + path + ".");
                }
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static void requireMatchingContent(List<TreeEntry> entries, List<byte[]> blobs, InputSnapshot worktree) {
        for (int index = 0; index < entries.size(); index++) {
            TreeEntry entry = entries.get(index);
            byte[] current = worktree.files().get(entry.path()).content();
            boolean matches = REQUIRED_JARS.contains(entry.path()) ? Arrays.equals(blobs.get(index), current) : normalizeJava(entry.path(), blobs.get(index)).equals(normalizeJava(entry.path(), current));
            if (!matches) {
                throw new IllegalStateException("java-cef artifact input differs from HEAD: " + entry.path() + ".");
            }
        }
    }

    private static String normalizeJava(String path, byte[] content) {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("java-cef Java input is not valid UTF-8: " + path + ".", e);
        }
        StringBuilder normalized = new StringBuilder(decoded.length());
        for (int index = 0; index < decoded.length(); index++) {
            char character = decoded.charAt(index);
            if (character != '\r') {
                normalized.append(character);
            } else if (index + 1 < decoded.length() && decoded.charAt(index + 1) == '\n') {
                normalized.append('\n');
                index++;
            } else {
                throw new IllegalStateException("java-cef Java input uses an unsupported lone carriage return: " + path + ".");
            }
        }
        return normalized.toString();
    }

    private static void requireSamePaths(List<TreeEntry> entries, InputSnapshot worktree) {
        Set<String> committed = new HashSet<>();
        for (TreeEntry entry : entries) {
            committed.add(entry.path());
        }
        if (committed.equals(worktree.files().keySet())) {
            return;
        }
        Set<String> untracked = new HashSet<>(worktree.files().keySet());
        untracked.removeAll(committed);
        Set<String> missing = new HashSet<>(committed);
        missing.removeAll(worktree.files().keySet());
        String path = !untracked.isEmpty() ? untracked.stream().sorted().findFirst().orElseThrow() : missing.stream().sorted().findFirst().orElseThrow();
        throw new IllegalStateException((!untracked.isEmpty() ? "Untracked or ignored" : "Missing") + " java-cef artifact input prevents commit provenance: " + path + ".");
    }

    private static void requireSameSnapshot(InputSnapshot expected, InputSnapshot actual) {
        if (!expected.sameContent(actual)) {
            throw new IllegalStateException("java-cef artifact inputs changed while commit provenance was being verified.");
        }
    }

    private static void requireHeadUnchanged(Path repository, String expectedHead, JcefProvenance.GitCommandRunner gitRunner) {
        String current = gitRunner.run(repository, List.of("rev-parse", "--verify", "HEAD^{commit}"), true, IMMUTABLE_OBJECT_ENVIRONMENT).output().trim().toLowerCase(java.util.Locale.ROOT);
        if (!OBJECT_ID_PATTERN.matcher(current).matches() || !expectedHead.equals(current)) {
            throw new IllegalStateException("java-cef HEAD changed while artifact inputs were being verified.");
        }
    }

    private static void requireObjectFormatMatchesCommit(String objectFormat, String commit) {
        int expectedLength = switch (objectFormat) {
            case "sha1" -> 40;
            case "sha256" -> 64;
            default -> throw new IllegalStateException("Git returned an unsupported java-cef object format.");
        };
        if (commit.length() != expectedLength) {
            throw new IllegalStateException("java-cef HEAD does not match its Git object format.");
        }
    }

    private static void requireObjectId(String objectId, String objectFormat, String path) {
        int expectedLength = objectFormat.equals("sha1") ? 40 : 64;
        if (objectId.length() != expectedLength || !OBJECT_ID_PATTERN.matcher(objectId).matches()) {
            throw new IllegalStateException("Git returned an invalid artifact blob object ID for " + path + ".");
        }
    }

    private static void requireBlobObjectId(byte[] content, String expectedObjectId, String objectFormat, String path) {
        MessageDigest digest = newDigest(objectFormat.equals("sha1") ? "SHA-1" : "SHA-256");
        digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(content);
        if (!HexFormat.of().formatHex(digest.digest()).equals(expectedObjectId)) {
            throw new IllegalStateException("Git returned corrupt blob content for java-cef artifact input " + path + ".");
        }
    }

    private static int parseBlobSize(String value, String path) {
        try {
            int size = Integer.parseInt(value);
            if (size < 0) {
                throw new NumberFormatException("negative");
            }
            return size;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Git returned an invalid blob size for java-cef artifact input " + path + ".", e);
        }
    }

    private static BasicFileAttributes readAttributes(Path path, String message) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalStateException(message + path + ".", e);
        }
    }

    private static boolean sameFile(BasicFileAttributes first, BasicFileAttributes second) {
        return first.isRegularFile() == second.isRegularFile() && java.util.Objects.equals(first.fileKey(), second.fileKey()) && first.size() == second.size();
    }

    private static boolean isInputPath(String path) {
        return REQUIRED_JARS.contains(path) || path.startsWith("java/") && path.endsWith(".java") && !containsTestsDirectory(path.substring("java/".length()));
    }

    private static boolean containsTestsDirectory(Path relativePath) {
        for (Path component : relativePath) {
            if (component.toString().equals("tests")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTestsDirectory(String relativePath) {
        String[] components = relativePath.split("/", -1);
        for (int index = 0; index + 1 < components.length; index++) {
            if (components[index].equals("tests")) {
                return true;
            }
        }
        return false;
    }

    private static String toGitPath(Path relativePath) {
        String value = relativePath.toString();
        return java.io.File.separatorChar == '/' ? value : value.replace(java.io.File.separatorChar, '/');
    }

    private static String decodeTreeOutput(byte[] output) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Git returned a java-cef artifact path that is not valid UTF-8.", e);
        }
    }

    private static int indexOf(byte[] bytes, byte value, int offset) {
        for (int index = offset; index < bytes.length; index++) {
            if (bytes[index] == value) {
                return index;
            }
        }
        return -1;
    }

    private static void requireWalkBudget(int walkedEntries, int maximumWalkEntries) {
        if (walkedEntries > maximumWalkEntries) {
            throw new IllegalStateException("java-cef Java input tree exceeds the bounded provenance traversal limit.");
        }
    }

    private static long addInputBytes(long currentBytes, long addedBytes) {
        long total = currentBytes + addedBytes;
        if (addedBytes < 0 || total < currentBytes || total > MAX_TOTAL_INPUT_BYTES) {
            throw new IllegalStateException("java-cef artifact inputs exceed the cumulative provenance size limit.");
        }
        return total;
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(newDigest("SHA-256").digest(content));
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime does not provide " + algorithm + " for java-cef provenance.", e);
        }
    }

    private record TreeEntry(String path, String objectId) {
    }

    private record InputFile(byte[] content, String sha256) {
    }

    private record InputSnapshot(Map<String, InputFile> files) {
        private boolean sameContent(InputSnapshot other) {
            if (!files.keySet().equals(other.files.keySet())) {
                return false;
            }
            for (Map.Entry<String, InputFile> entry : files.entrySet()) {
                InputFile otherFile = other.files.get(entry.getKey());
                if (otherFile == null || !entry.getValue().sha256().equals(otherFile.sha256()) || entry.getValue().content().length != otherFile.content().length) {
                    return false;
                }
            }
            return true;
        }
    }
}
