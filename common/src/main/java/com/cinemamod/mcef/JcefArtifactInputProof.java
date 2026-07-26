package com.cinemamod.mcef;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Runtime counterpart of the buildSrc raw-HEAD artifact-input proof.
 *
 * <p>The buildSrc and project classloaders are compiled independently, so sharing this helper
 * would create a build cycle. Keep both helpers and their focused tests behaviorally aligned.</p>
 */
final class JcefArtifactInputProof {
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final String GLUEGEN_JAR_PATH = "third_party/jogamp/jar/gluegen-rt.jar";
    private static final String JOGL_JAR_PATH = "third_party/jogamp/jar/jogl-all.jar";
    private static final List<String> ARTIFACT_INPUT_PATHSPECS = List.of("java", GLUEGEN_JAR_PATH, JOGL_JAR_PATH);
    private static final Set<String> REQUIRED_CLASSPATH_INPUTS = Set.of(GLUEGEN_JAR_PATH, JOGL_JAR_PATH);
    private static final Map<String, String> IMMUTABLE_OBJECT_ENVIRONMENT = Map.of("GIT_NO_REPLACE_OBJECTS", "1", "GIT_OPTIONAL_LOCKS", "0");
    private static final int MAX_ARTIFACT_INPUT_COUNT = 8192;
    private static final int MAX_TREE_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_JAVA_INPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CLASSPATH_INPUT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TOTAL_INPUT_BYTES = 128 * 1024 * 1024;
    private static final int MAX_BATCH_OUTPUT_BYTES = MAX_TOTAL_INPUT_BYTES + 1024 * 1024;

    private JcefArtifactInputProof() {
    }

    static void requireMatchesHead(Path repository, String expectedHead, JavaCefCommitResolver.GitCommandRunner gitRunner) throws IOException {
        String objectFormat = gitRunner.run(repository, List.of("rev-parse", "--show-object-format"), true, IMMUTABLE_OBJECT_ENVIRONMENT).output().trim();
        requireObjectFormatMatchesCommit(objectFormat, expectedHead);

        List<ArtifactTreeEntry> treeEntries = readArtifactTree(repository, expectedHead, objectFormat, gitRunner);
        List<byte[]> committedBlobs = readCommittedBlobs(repository, treeEntries, objectFormat, gitRunner);
        ArtifactInputSnapshot before = readArtifactInputs(repository, treeEntries);
        requireSameInputPaths(treeEntries, before);
        requireCommittedContentMatches(treeEntries, committedBlobs, before);

        ArtifactInputSnapshot afterComparison = readArtifactInputs(repository, treeEntries);
        requireSameInputSnapshot(before, afterComparison);
        requireHeadUnchanged(repository, expectedHead, gitRunner);

        // Re-read every compiler input after the final HEAD query. This brackets both repository
        // identity and worktree content, and catches changes made at Git-command boundaries.
        ArtifactInputSnapshot finalSnapshot = readArtifactInputs(repository, treeEntries);
        requireSameInputSnapshot(before, finalSnapshot);
        requireHeadUnchanged(repository, expectedHead, gitRunner);
    }

    private static List<ArtifactTreeEntry> readArtifactTree(Path repository, String head, String objectFormat, JavaCefCommitResolver.GitCommandRunner gitRunner) throws IOException {
        List<String> arguments = new ArrayList<>(ARTIFACT_INPUT_PATHSPECS.size() + 6);
        arguments.addAll(List.of("ls-tree", "-r", "-z", "--full-tree", head, "--"));
        arguments.addAll(ARTIFACT_INPUT_PATHSPECS);
        JavaCefCommitResolver.GitBinaryResult treeResult = gitRunner.runBinary(repository, arguments, new byte[0], true, IMMUTABLE_OBJECT_ENVIRONMENT, MAX_TREE_OUTPUT_BYTES);
        String output = decodeGitTreeOutput(treeResult.output());
        List<ArtifactTreeEntry> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        int offset = 0;
        while (offset < output.length()) {
            int terminator = output.indexOf('\0', offset);
            if (terminator < 0) {
                throw new IOException("Git returned an unterminated java-cef artifact tree entry.");
            }
            String record = output.substring(offset, terminator);
            offset = terminator + 1;
            int tab = record.indexOf('\t');
            if (tab <= 0) {
                throw new IOException("Git returned an invalid java-cef artifact tree entry.");
            }
            String[] metadata = record.substring(0, tab).split(" ", -1);
            String path = record.substring(tab + 1);
            if (metadata.length != 3) {
                throw new IOException("Git returned an invalid java-cef artifact tree entry for " + path + ".");
            }
            if (!isArtifactInputPath(path)) {
                continue;
            }
            if (!paths.add(path)) {
                throw new IOException("Git returned a duplicate java-cef artifact tree entry for " + path + ".");
            }
            if ((!metadata[0].equals("100644") && !metadata[0].equals("100755")) || !metadata[1].equals("blob")) {
                throw new IOException("java-cef artifact input " + path + " is not a regular tracked file; symbolic links and submodules cannot provide reproducible compiler input.");
            }
            requireObjectId(metadata[2], objectFormat, "artifact blob", repository.resolve(path));
            entries.add(new ArtifactTreeEntry(path, metadata[2]));
            if (entries.size() > MAX_ARTIFACT_INPUT_COUNT) {
                throw new IOException("java-cef artifact input count exceeds the provenance limit.");
            }
        }
        entries.sort(Comparator.comparing(ArtifactTreeEntry::path));
        for (String requiredPath : REQUIRED_CLASSPATH_INPUTS) {
            if (!paths.contains(requiredPath)) {
                throw new IOException("Required java-cef compiler classpath input is not tracked by HEAD: " + requiredPath + ".");
            }
        }
        return List.copyOf(entries);
    }

    private static ArtifactInputSnapshot readArtifactInputs(Path repository, List<ArtifactTreeEntry> treeEntries) throws IOException {
        TreeMap<String, ArtifactInputFile> files = new TreeMap<>();
        Set<String> expectedPaths = new HashSet<>();
        for (ArtifactTreeEntry entry : treeEntries) {
            expectedPaths.add(entry.path());
        }
        int maximumWalkEntries = Math.min(MAX_ARTIFACT_INPUT_COUNT * 8, Math.max(256, treeEntries.size() * 8 + 256));
        int[] walkedEntries = {0};
        long[] totalBytes = {0L};
        Path javaRoot = repository.resolve("java");
        BasicFileAttributes rootAttributes;
        try {
            rootAttributes = Files.readAttributes(javaRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IOException("java-cef Java source root is unavailable at " + javaRoot + ".", e);
        }
        if (!rootAttributes.isDirectory() || rootAttributes.isSymbolicLink()) {
            throw new IOException("java-cef Java source root is not a real directory at " + javaRoot + ".");
        }

        Files.walkFileTree(javaRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                requireWalkBudget(++walkedEntries[0], maximumWalkEntries);
                if (!directory.equals(javaRoot) && containsTestsDirectory(javaRoot.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                requireWalkBudget(++walkedEntries[0], maximumWalkEntries);
                Path relative = javaRoot.relativize(file);
                if (containsTestsDirectory(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.isSymbolicLink()) {
                    throw new IOException("Symbolic link inside the java-cef Java input tree cannot be proven reproducible: " + file + ".");
                }
                if (file.getFileName().toString().endsWith(".java")) {
                    if (!attributes.isRegularFile()) {
                        throw new IOException("java-cef Java input is not a regular file: " + file + ".");
                    }
                    String gitPath = "java/" + toGitPath(relative);
                    if (!expectedPaths.contains(gitPath)) {
                        throw new IOException("Untracked or ignored java-cef artifact input prevents commit provenance: " + gitPath + ".");
                    }
                    ArtifactInputFile input = readArtifactInputFile(file, gitPath, MAX_JAVA_INPUT_BYTES);
                    totalBytes[0] = addInputBytes(totalBytes[0], input.content().length);
                    files.put(gitPath, input);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw new IOException("Failed to inspect potential java-cef Java input at " + file + ".", failure);
            }
        });

        for (String jarPath : REQUIRED_CLASSPATH_INPUTS) {
            Path jar = repository.resolve(jarPath);
            ArtifactInputFile input = readArtifactInputFile(jar, jarPath, MAX_CLASSPATH_INPUT_BYTES);
            totalBytes[0] = addInputBytes(totalBytes[0], input.content().length);
            files.put(jarPath, input);
        }
        return new ArtifactInputSnapshot(Map.copyOf(files));
    }

    private static ArtifactInputFile readArtifactInputFile(Path path, String gitPath, int maximumBytes) throws IOException {
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IOException("Required java-cef artifact input is unavailable at " + path + ".", e);
        }
        if (!before.isRegularFile() || before.isSymbolicLink()) {
            throw new IOException("Required java-cef artifact input is not a regular file at " + path + ".");
        }
        if (before.size() > maximumBytes) {
            throw new IOException("java-cef artifact input exceeds the provenance size limit: " + gitPath + ".");
        }

        byte[] content;
        try {
            content = readBoundedFile(path, maximumBytes);
        } catch (IOException e) {
            throw new IOException("Failed to read java-cef artifact input at " + path + ".", e);
        }

        BasicFileAttributes after;
        try {
            after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IOException("java-cef artifact input changed while being read at " + path + ".", e);
        }
        if (!sameGitFileAttributes(before, after) || content.length != before.size()) {
            throw new IOException("java-cef artifact input changed while being read at " + path + ".");
        }
        return new ArtifactInputFile(gitPath, content, sha256(content));
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

    private static List<byte[]> readCommittedBlobs(Path repository, List<ArtifactTreeEntry> entries, String objectFormat, JavaCefCommitResolver.GitCommandRunner gitRunner) throws IOException {
        StringBuilder request = new StringBuilder();
        for (ArtifactTreeEntry entry : entries) {
            request.append(entry.objectId()).append('\n');
        }
        JavaCefCommitResolver.GitBinaryResult result = gitRunner.runBinary(repository, List.of("cat-file", "--batch"), request.toString().getBytes(StandardCharsets.US_ASCII), true, IMMUTABLE_OBJECT_ENVIRONMENT, MAX_BATCH_OUTPUT_BYTES);
        List<byte[]> blobs = new ArrayList<>(entries.size());
        int offset = 0;
        long totalBytes = 0L;
        for (ArtifactTreeEntry entry : entries) {
            int lineEnd = indexOf(result.output(), (byte) '\n', offset);
            if (lineEnd < 0) {
                throw new IOException("Git returned a truncated batch header for java-cef artifact input " + entry.path() + ".");
            }
            String header = new String(result.output(), offset, lineEnd - offset, StandardCharsets.US_ASCII);
            String[] fields = header.split(" ", -1);
            if (fields.length != 3 || !fields[0].equals(entry.objectId()) || !fields[1].equals("blob")) {
                throw new IOException("Git returned an invalid batch header for java-cef artifact input " + entry.path() + ".");
            }
            int size;
            try {
                size = Integer.parseInt(fields[2]);
            } catch (NumberFormatException e) {
                throw new IOException("Git returned an invalid blob size for java-cef artifact input " + entry.path() + ".", e);
            }
            int maximumBytes = REQUIRED_CLASSPATH_INPUTS.contains(entry.path()) ? MAX_CLASSPATH_INPUT_BYTES : MAX_JAVA_INPUT_BYTES;
            if (size < 0 || size > maximumBytes) {
                throw new IOException("Committed java-cef artifact input exceeds the provenance size limit: " + entry.path() + ".");
            }
            totalBytes = addInputBytes(totalBytes, size);
            int contentStart = lineEnd + 1;
            long contentEndLong = (long) contentStart + size;
            if (size < 0 || contentEndLong >= result.output().length || result.output()[(int) contentEndLong] != '\n') {
                throw new IOException("Git returned truncated blob content for java-cef artifact input " + entry.path() + ".");
            }
            byte[] content = Arrays.copyOfRange(result.output(), contentStart, (int) contentEndLong);
            requireBlobObjectId(content, entry.objectId(), objectFormat, entry.path());
            blobs.add(content);
            offset = (int) contentEndLong + 1;
        }
        if (offset != result.output().length) {
            throw new IOException("Git returned unexpected trailing data while reading java-cef artifact blobs.");
        }
        return List.copyOf(blobs);
    }

    private static void requireCommittedContentMatches(List<ArtifactTreeEntry> entries, List<byte[]> committedBlobs, ArtifactInputSnapshot worktree) throws IOException {
        for (int index = 0; index < entries.size(); index++) {
            ArtifactTreeEntry entry = entries.get(index);
            ArtifactInputFile input = worktree.files().get(entry.path());
            byte[] committed = committedBlobs.get(index);
            boolean matches = REQUIRED_CLASSPATH_INPUTS.contains(entry.path()) ? Arrays.equals(committed, input.content()) : normalizedJavaSource(entry.path(), committed).equals(normalizedJavaSource(entry.path(), input.content()));
            if (!matches) {
                throw new IOException("java-cef artifact input differs from HEAD: " + entry.path() + ".");
            }
        }
    }

    private static String normalizedJavaSource(String path, byte[] content) throws IOException {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("java-cef Java input is not valid UTF-8: " + path + ".", e);
        }
        StringBuilder normalized = new StringBuilder(decoded.length());
        for (int index = 0; index < decoded.length(); index++) {
            char character = decoded.charAt(index);
            if (character != '\r') {
                normalized.append(character);
                continue;
            }
            if (index + 1 >= decoded.length() || decoded.charAt(index + 1) != '\n') {
                throw new IOException("java-cef Java input uses an unsupported lone carriage return: " + path + ".");
            }
            normalized.append('\n');
            index++;
        }
        return normalized.toString();
    }

    private static String decodeGitTreeOutput(byte[] output) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Git returned a java-cef artifact path that is not valid UTF-8.", e);
        }
    }

    private static void requireWalkBudget(int walkedEntries, int maximumWalkEntries) throws IOException {
        if (walkedEntries > maximumWalkEntries) {
            throw new IOException("java-cef Java input tree exceeds the bounded provenance traversal limit.");
        }
    }

    private static long addInputBytes(long currentBytes, long addedBytes) throws IOException {
        long total = currentBytes + addedBytes;
        if (addedBytes < 0 || total < currentBytes || total > MAX_TOTAL_INPUT_BYTES) {
            throw new IOException("java-cef artifact inputs exceed the cumulative provenance size limit.");
        }
        return total;
    }

    private static void requireSameInputPaths(List<ArtifactTreeEntry> entries, ArtifactInputSnapshot worktree) throws IOException {
        Set<String> committedPaths = new HashSet<>();
        for (ArtifactTreeEntry entry : entries) {
            committedPaths.add(entry.path());
        }
        if (!committedPaths.equals(worktree.files().keySet())) {
            Set<String> untracked = new HashSet<>(worktree.files().keySet());
            untracked.removeAll(committedPaths);
            Set<String> missing = new HashSet<>(committedPaths);
            missing.removeAll(worktree.files().keySet());
            String path = !untracked.isEmpty() ? untracked.stream().sorted().findFirst().orElseThrow() : missing.stream().sorted().findFirst().orElseThrow();
            throw new IOException((!untracked.isEmpty() ? "Untracked or ignored" : "Missing") + " java-cef artifact input prevents commit provenance: " + path + ".");
        }
    }

    private static void requireSameInputSnapshot(ArtifactInputSnapshot expected, ArtifactInputSnapshot actual) throws IOException {
        if (!expected.sameContent(actual)) {
            throw new IOException("java-cef artifact inputs changed while commit provenance was being verified.");
        }
    }

    private static void requireHeadUnchanged(Path repository, String expectedHead, JavaCefCommitResolver.GitCommandRunner gitRunner) throws IOException {
        String currentHead = requireCommit(gitRunner.run(repository, List.of("rev-parse", "--verify", "HEAD^{commit}"), true, IMMUTABLE_OBJECT_ENVIRONMENT).output().trim(), "java-cef HEAD");
        if (!expectedHead.equals(currentHead)) {
            throw new IOException("java-cef HEAD changed while artifact inputs were being verified.");
        }
    }

    private static void requireObjectFormatMatchesCommit(String objectFormat, String commit) throws IOException {
        int expectedLength = switch (objectFormat) {
            case "sha1" -> 40;
            case "sha256" -> 64;
            default -> throw new IOException("Git returned an unsupported java-cef object format.");
        };
        if (commit.length() != expectedLength) {
            throw new IOException("java-cef HEAD does not match its Git object format.");
        }
    }

    private static void requireObjectId(String objectId, String objectFormat, String description, Path path) throws IOException {
        int expectedLength = objectFormat.equals("sha1") ? 40 : 64;
        if (objectId.length() != expectedLength || !COMMIT_PATTERN.matcher(objectId).matches()) {
            throw new IOException("Git returned an invalid " + description + " object ID for " + path + ".");
        }
    }

    private static void requireBlobObjectId(byte[] content, String expectedObjectId, String objectFormat, String path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(objectFormat.equals("sha1") ? "SHA-1" : "SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Java runtime does not provide the Git object hash algorithm.", e);
        }
        digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(content);
        if (!HexFormat.of().formatHex(digest.digest()).equals(expectedObjectId)) {
            throw new IOException("Git returned corrupt blob content for java-cef artifact input " + path + ".");
        }
    }

    private static boolean isArtifactInputPath(String path) {
        return REQUIRED_CLASSPATH_INPUTS.contains(path) || path.startsWith("java/") && path.endsWith(".java") && !containsTestsDirectory(Path.of(path.substring("java/".length())));
    }

    private static boolean containsTestsDirectory(Path relativePath) {
        for (Path component : relativePath) {
            if (component.toString().equals("tests")) {
                return true;
            }
        }
        return false;
    }

    private static String toGitPath(Path relativePath) {
        String value = relativePath.toString();
        return java.io.File.separatorChar == '/' ? value : value.replace(java.io.File.separatorChar, '/');
    }

    private static int indexOf(byte[] bytes, byte value, int offset) {
        for (int index = offset; index < bytes.length; index++) {
            if (bytes[index] == value) {
                return index;
            }
        }
        return -1;
    }

    private static String sha256(byte[] content) throws IOException {
        MessageDigest digest = newSha256Digest();
        return HexFormat.of().formatHex(digest.digest(content));
    }

    private static MessageDigest newSha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Java runtime does not provide SHA-256 for java-cef provenance.", e);
        }
    }

    private static boolean sameGitFileAttributes(BasicFileAttributes first, BasicFileAttributes second) {
        return first.isRegularFile() == second.isRegularFile() && java.util.Objects.equals(first.fileKey(), second.fileKey()) && first.size() == second.size();
    }

    private static String requireCommit(String rawCommit, String description) throws IOException {
        String normalized = rawCommit.trim().toLowerCase(Locale.ROOT);
        if (!COMMIT_PATTERN.matcher(normalized).matches()) {
            throw new IOException("Git returned an invalid " + description + " commit.");
        }
        return normalized;
    }

    private record ArtifactTreeEntry(String path, String objectId) {
    }

    private record ArtifactInputFile(String path, byte[] content, String sha256) {
    }

    private record ArtifactInputSnapshot(Map<String, ArtifactInputFile> files) {
        private boolean sameContent(ArtifactInputSnapshot other) {
            if (!files.keySet().equals(other.files.keySet())) {
                return false;
            }
            for (Map.Entry<String, ArtifactInputFile> entry : files.entrySet()) {
                ArtifactInputFile otherFile = other.files.get(entry.getKey());
                if (otherFile == null || !entry.getValue().sha256().equals(otherFile.sha256()) || entry.getValue().content().length != otherFile.content().length) {
                    return false;
                }
            }
            return true;
        }
    }
}
