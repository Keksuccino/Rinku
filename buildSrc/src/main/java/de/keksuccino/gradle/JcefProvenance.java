package de.keksuccino.gradle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JcefProvenance {
    // Build setup may clone the complete JCEF repository, so allow network-bound Git operations
    // substantially longer than local provenance reads while still preventing an infinite hang.
    private static final long GIT_PROCESS_TIMEOUT_SECONDS = 300;
    private static final long GIT_OUTPUT_READER_TIMEOUT_SECONDS = 10;
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final Pattern INDEX_ENTRY_PATTERN = Pattern.compile("^(\\d{6}) ([0-9a-f]{40}|[0-9a-f]{64}) ([0-3])\\t([\\s\\S]+)$");
    private static final Pattern SHARED_INDEX_NAME_PATTERN = Pattern.compile("^sharedindex\\.(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<String> REPOSITORY_SELECTING_ENVIRONMENT_VARIABLES = Set.of("GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_CEILING_DIRECTORIES", "GIT_COMMON_DIR", "GIT_CONFIG", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_SYSTEM", "GIT_DIR", "GIT_DISCOVERY_ACROSS_FILESYSTEM", "GIT_GLOB_PATHSPECS", "GIT_GRAFT_FILE", "GIT_ICASE_PATHSPECS", "GIT_INDEX_FILE", "GIT_LITERAL_PATHSPECS", "GIT_NAMESPACE", "GIT_NOGLOB_PATHSPECS", "GIT_OBJECT_DIRECTORY", "GIT_PREFIX", "GIT_QUARANTINE_PATH", "GIT_SHALLOW_FILE", "GIT_WORK_TREE");

    private JcefProvenance() {
    }

    public static Resolution resolve(Path rootDirectory, String repositoryPath) {
        return resolve(rootDirectory, repositoryPath, JcefProvenance::runGit);
    }

    static Resolution resolve(Path rootDirectory, String repositoryPath, GitCommandRunner gitRunner) {
        Path root = absoluteNormalized(rootDirectory);
        Path repository = absoluteNormalized(root.resolve(repositoryPath));
        GitMetadataSnapshot rootMetadata = inspectGitMetadata(root);
        requireExactRepositoryRoot(repository, "java-cef", gitRunner);

        String head = requireCommit(gitRunner.run(repository, List.of("rev-parse", "--verify", "HEAD^{commit}"), true, Map.of()).output().trim(), "HEAD", repository);
        String gitlink = trackedGitlinkCommit(root, repositoryPath, rootMetadata, gitRunner);
        if (gitlink != null && !gitlink.equals(head)) {
            throw new IllegalStateException("java-cef HEAD " + head + " does not match the tracked gitlink " + gitlink + ".");
        }

        JcefArtifactInputProof.requireMatchesHead(repository, head, gitRunner);

        requireUnchangedGitMetadata(root, rootMetadata);
        return new Resolution(gitlink != null ? gitlink : head, gitlink != null);
    }

    public static boolean isTrackedSubmodule(Path rootDirectory, String repositoryPath) {
        Path root = absoluteNormalized(rootDirectory);
        return trackedGitlinkCommit(root, repositoryPath, inspectGitMetadata(root), JcefProvenance::runGit) != null;
    }

    public static boolean hasGitMetadata(Path repository) {
        return inspectGitMetadata(absoluteNormalized(repository)).present();
    }

    private static GitMetadataSnapshot inspectGitMetadata(Path repository) {
        Path metadata = absoluteNormalized(repository).resolve(".git");
        try {
            BasicFileAttributes attributes = Files.readAttributes(metadata, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new GitMetadataSnapshot(metadata, true, attributes.fileKey(), attributes.isDirectory(), attributes.isRegularFile(), attributes.isSymbolicLink(), attributes.size(), attributes.lastModifiedTime());
        } catch (NoSuchFileException e) {
            return GitMetadataSnapshot.missing(metadata);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect Git metadata at " + metadata + ".", e);
        }
    }

    private static void requireUnchangedGitMetadata(Path root, GitMetadataSnapshot expected) {
        if (!expected.equals(inspectGitMetadata(root))) {
            throw new IllegalStateException("MCEF Git metadata changed while resolving java-cef provenance at " + root.resolve(".git") + ".");
        }
    }

    public static GitResult runGit(Path directory, List<String> arguments, boolean failOnError) {
        return runGit(directory, arguments, failOnError, Map.of());
    }

    static GitResult runGit(Path directory, List<String> arguments, boolean failOnError, Map<String, String> environmentOverrides) {
        List<String> command = new ArrayList<>(arguments.size() + 3);
        command.add("git");
        command.add("-C");
        command.add(absoluteNormalized(directory).toString());
        command.addAll(arguments);

        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        sanitizeGitEnvironment(processBuilder.environment());
        processBuilder.environment().putAll(environmentOverrides);

        Process process = null;
        Thread outputReader = null;
        try {
            process = processBuilder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicReference<IOException> readFailure = new AtomicReference<>();
            outputReader = startGitOutputReader(process, output, readFailure, Integer.MAX_VALUE, "MCEF-Git-Output");
            int exitCode = awaitGitProcess(process, outputReader, command);
            if (readFailure.get() != null) {
                throw new IllegalStateException("Failed to read Git command output: " + String.join(" ", command), readFailure.get());
            }
            GitResult result = new GitResult(exitCode, output.toString(StandardCharsets.UTF_8));
            if (failOnError && exitCode != 0) {
                throw gitFailure(command, result);
            }
            return result;
        } catch (IOException e) {
            if (process != null && outputReader != null) terminateGitProcess(process, outputReader);
            throw new IllegalStateException("Failed to execute Git command: " + String.join(" ", command), e);
        }
    }

    static GitBinaryResult runGitBinary(Path directory, List<String> arguments, byte[] input, boolean failOnError, Map<String, String> environmentOverrides, int maximumOutputBytes) {
        List<String> command = new ArrayList<>(arguments.size() + 3);
        command.add("git");
        command.add("-C");
        command.add(absoluteNormalized(directory).toString());
        command.addAll(arguments);

        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        sanitizeGitEnvironment(processBuilder.environment());
        processBuilder.environment().putAll(environmentOverrides);

        Process process = null;
        Thread outputReader = null;
        try {
            process = processBuilder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicReference<IOException> readFailure = new AtomicReference<>();
            outputReader = startGitOutputReader(process, output, readFailure, maximumOutputBytes, "MCEF-Git-Binary-Output");
            try (var processInput = process.getOutputStream()) {
                processInput.write(input);
            } catch (IOException e) {
                terminateGitProcess(process, outputReader);
                throw new IllegalStateException("Failed to write Git command input: " + String.join(" ", command), e);
            }
            int exitCode = awaitGitProcess(process, outputReader, command);
            if (readFailure.get() != null) {
                throw new IllegalStateException("Failed to read Git command output: " + String.join(" ", command), readFailure.get());
            }
            GitBinaryResult result = new GitBinaryResult(exitCode, output.toByteArray());
            if (failOnError && exitCode != 0) {
                throw gitBinaryFailure(command, result);
            }
            return result;
        } catch (IOException e) {
            if (process != null && outputReader != null) terminateGitProcess(process, outputReader);
            throw new IllegalStateException("Failed to execute Git command: " + String.join(" ", command), e);
        }
    }

    private static Thread startGitOutputReader(Process process, ByteArrayOutputStream output, AtomicReference<IOException> readFailure, int maximumOutputBytes, String threadName) {
        // Keep process-pipe reads off virtual carriers. Java 25/macOS can otherwise deadlock a
        // pinned pipe read against ProcessReaper after Git has already exited.
        return Thread.ofPlatform().daemon(true).name(threadName).start(() -> {
            try (var processOutput = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = processOutput.read(buffer)) != -1) {
                    if ((long) output.size() + count > maximumOutputBytes) {
                        throw new IOException("Git command output exceeds the provenance limit.");
                    }
                    output.write(buffer, 0, count);
                }
            } catch (IOException e) {
                readFailure.set(e);
                process.destroyForcibly();
            }
        });
    }

    private static int awaitGitProcess(Process process, Thread outputReader, List<String> command) {
        try {
            if (!process.waitFor(GIT_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminateGitProcess(process, outputReader);
                throw new IllegalStateException("Timed out while executing Git command: " + String.join(" ", command));
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(GIT_OUTPUT_READER_TIMEOUT_SECONDS));
            if (outputReader.isAlive()) {
                terminateGitProcess(process, outputReader);
                throw new IllegalStateException("Timed out while reading Git command output: " + String.join(" ", command));
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            terminateGitProcess(process, outputReader);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing Git command: " + String.join(" ", command), e);
        }
    }

    private static void terminateGitProcess(Process process, Thread outputReader) {
        process.destroyForcibly();
        // Interrupt alone does not reliably wake a platform thread blocked in a native pipe read.
        // Closing every process stream makes timeout and interruption cleanup deterministic.
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        outputReader.interrupt();
    }

    static void sanitizeGitEnvironment(Map<String, String> environment) {
        Set<String> keys = new HashSet<>(environment.keySet());
        for (String key : keys) {
            if (REPOSITORY_SELECTING_ENVIRONMENT_VARIABLES.contains(key) || key.equals("GIT_CONFIG_COUNT") || key.startsWith("GIT_CONFIG_KEY_") || key.startsWith("GIT_CONFIG_VALUE_")) {
                environment.remove(key);
            }
        }
    }

    private static String trackedGitlinkCommit(Path root, String repositoryPath, GitMetadataSnapshot rootMetadata, GitCommandRunner gitRunner) {
        // Source archives have no parent Git metadata, so their standalone java-cef clone is
        // intentionally authoritative. Once MCEF has its own metadata, every index failure is
        // ambiguous and must stop the build instead of being treated as "not a submodule".
        requireUnchangedGitMetadata(root, rootMetadata);
        if (!rootMetadata.present()) {
            return null;
        }

        requireExactRepositoryRoot(root, "MCEF", gitRunner);
        GitResult result;
        try (CapturedGitIndex capturedIndex = captureGitIndex(root, gitRunner)) {
            // Querying the private capture prevents a replace-query-restore cycle on the live
            // index from manufacturing a gitlink without changing either live snapshot.
            result = gitRunner.run(root, List.of("ls-files", "--stage", "-z", "--", repositoryPath), true, capturedIndex.environment());
            capturedIndex.requireLiveFilesUnchanged(root, repositoryPath, gitRunner);
        }
        requireUnchangedGitMetadata(root, rootMetadata);

        List<IndexEntry> entries = parseIndexEntries(result.output(), repositoryPath);
        if (entries.isEmpty()) {
            return null;
        }
        if (entries.size() != 1 || !"0".equals(entries.getFirst().stage())) {
            throw new IllegalStateException("Could not resolve one unambiguous index entry for " + repositoryPath + ".");
        }

        IndexEntry entry = entries.getFirst();
        if (!"160000".equals(entry.mode())) {
            return null;
        }
        return requireCommit(entry.objectId(), "gitlink", root.resolve(repositoryPath));
    }

    private static List<IndexEntry> parseIndexEntries(String output, String repositoryPath) {
        List<IndexEntry> entries = new ArrayList<>();
        int offset = 0;
        while (offset < output.length()) {
            int terminator = output.indexOf('\0', offset);
            if (terminator < 0) {
                throw new IllegalStateException("Git returned an unterminated index entry for " + repositoryPath + ".");
            }
            String record = output.substring(offset, terminator);
            offset = terminator + 1;
            if (record.isEmpty()) {
                continue;
            }
            Matcher matcher = INDEX_ENTRY_PATTERN.matcher(record);
            if (!matcher.matches()) {
                throw new IllegalStateException("Git returned an invalid index entry for " + repositoryPath + ".");
            }
            if (!repositoryPath.equals(matcher.group(4))) {
                continue;
            }
            entries.add(new IndexEntry(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return entries;
    }

    private static void requireExactRepositoryRoot(Path repository, String description, GitCommandRunner gitRunner) {
        if (!Files.isDirectory(repository)) {
            throw new IllegalStateException(description + " repository does not exist at " + repository + ".");
        }

        GitResult result = gitRunner.run(repository, List.of("rev-parse", "--show-toplevel"), true, Map.of());
        Path reportedRoot = absoluteNormalized(Path.of(result.output().trim()));
        Path expectedRoot = realPath(repository);
        if (!realPath(reportedRoot).equals(expectedRoot)) {
            throw new IllegalStateException("Expected " + description + " to be a standalone Git worktree at " + repository + ", but Git resolved " + reportedRoot + ".");
        }
    }

    private static CapturedGitIndex captureGitIndex(Path repository, GitCommandRunner gitRunner) {
        GitFileSnapshot indexSnapshot = requireReadableGitIndex(repository, gitRunner);
        String sharedIndexValue = gitRunner.run(repository, List.of("rev-parse", "--shared-index-path"), true, Map.of()).output().trim();
        GitFileSnapshot indexAfterSharedPathQuery = requireReadableGitIndex(repository, gitRunner);
        if (!indexSnapshot.equals(indexAfterSharedPathQuery)) {
            throw new IllegalStateException("MCEF Git index changed while preparing the provenance query.");
        }

        Path sharedIndex = sharedIndexValue.isEmpty() ? null : parseRepositoryGitPath(repository, sharedIndexValue, "shared index");
        GitFileSnapshot sharedIndexSnapshot = sharedIndex == null ? null : requireReadableSharedIndex(indexSnapshot.path(), sharedIndex);
        String objectFormat = gitRunner.run(repository, List.of("rev-parse", "--show-object-format"), true, Map.of()).output().trim();
        if (!objectFormat.equals("sha1") && !objectFormat.equals("sha256")) {
            throw new IllegalStateException("Git returned an unsupported MCEF object format.");
        }
        String objectDirectoryValue = gitRunner.run(repository, List.of("rev-parse", "--git-path", "objects"), true, Map.of()).output().trim();
        Path objectDirectory = parseRepositoryGitPath(repository, objectDirectoryValue, "object directory");
        if (!Files.isDirectory(objectDirectory)) {
            throw new IllegalStateException("MCEF Git object directory is unavailable at " + objectDirectory + ".");
        }

        Path privateGitDirectory = createPrivateGitDirectory();
        try {
            Files.createDirectory(privateGitDirectory.resolve("objects"));
            Files.createDirectory(privateGitDirectory.resolve("refs"));
            Files.writeString(privateGitDirectory.resolve("HEAD"), "ref: refs/heads/mcef-provenance\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(privateGitDirectory.resolve("config"), privateGitConfig(objectFormat), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(privateGitDirectory.resolve("global-config"), "", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            copyVerifiedGitFile(indexSnapshot, privateGitDirectory.resolve("index"), "MCEF Git index");
            if (sharedIndexSnapshot != null) {
                copyVerifiedGitFile(sharedIndexSnapshot, privateGitDirectory.resolve(sharedIndexSnapshot.path().getFileName()), "MCEF Git shared index");
            }
            Map<String, String> environment = Map.of("GIT_DIR", privateGitDirectory.toString(), "GIT_WORK_TREE", repository.toString(), "GIT_INDEX_FILE", privateGitDirectory.resolve("index").toString(), "GIT_OBJECT_DIRECTORY", realPath(objectDirectory).toString(), "GIT_CONFIG_GLOBAL", privateGitDirectory.resolve("global-config").toString(), "GIT_CONFIG_NOSYSTEM", "1", "GIT_OPTIONAL_LOCKS", "0");
            return new CapturedGitIndex(privateGitDirectory, indexSnapshot, sharedIndexSnapshot, environment);
        } catch (IOException e) {
            try {
                deletePrivateGitDirectory(privateGitDirectory);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Failed to capture the MCEF Git index.", e);
        } catch (RuntimeException e) {
            try {
                deletePrivateGitDirectory(privateGitDirectory);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private static GitFileSnapshot requireReadableGitIndex(Path repository, GitCommandRunner gitRunner) {
        String value = gitRunner.run(repository, List.of("rev-parse", "--git-path", "index"), true, Map.of()).output().trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Git returned an empty MCEF index path for " + repository + ".");
        }
        return readGitFileSnapshot(parseRepositoryGitPath(repository, value, "index"), "MCEF Git index");
    }

    private static GitFileSnapshot requireReadableSharedIndex(Path index, Path sharedIndex) {
        Path fileName = sharedIndex.getFileName();
        if (fileName == null || !SHARED_INDEX_NAME_PATTERN.matcher(fileName.toString()).matches()) {
            throw new IllegalStateException("Git returned an invalid MCEF shared-index path.");
        }
        Path indexParent = index.getParent();
        Path sharedIndexParent = sharedIndex.getParent();
        if (indexParent == null || sharedIndexParent == null || !realPath(indexParent).equals(realPath(sharedIndexParent))) {
            throw new IllegalStateException("MCEF Git shared index is outside the live index directory at " + sharedIndex + ".");
        }
        return readGitFileSnapshot(sharedIndex, "MCEF Git shared index");
    }

    private static GitFileSnapshot readGitFileSnapshot(Path path, String description) {
        BasicFileAttributes attributesBeforeRead;
        try {
            attributesBeforeRead = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalStateException(description + " is unavailable at " + path + ".", e);
        }
        if (!attributesBeforeRead.isRegularFile()) {
            throw new IllegalStateException(description + " is not a regular file at " + path + ".");
        }

        MessageDigest digest = newSha256Digest();
        long bytesRead = 0L;
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                bytesRead += count;
            }
        } catch (IOException e) {
            throw new IllegalStateException(description + " is unreadable at " + path + ".", e);
        }

        BasicFileAttributes attributesAfterRead;
        try {
            attributesAfterRead = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalStateException(description + " changed while being read at " + path + ".", e);
        }
        GitFileSnapshot beforeRead = snapshotGitFile(path, attributesBeforeRead, HexFormat.of().formatHex(digest.digest()));
        if (!sameGitFileAttributes(attributesBeforeRead, attributesAfterRead) || bytesRead != attributesBeforeRead.size()) {
            throw new IllegalStateException(description + " changed while being read at " + path + ".");
        }
        return beforeRead;
    }

    private static void copyVerifiedGitFile(GitFileSnapshot expected, Path target, String description) throws IOException {
        GitFileSnapshot beforeCopy = readGitFileSnapshot(expected.path(), description);
        if (!expected.equals(beforeCopy)) {
            throw new IllegalStateException(description + " changed before it could be captured.");
        }

        MessageDigest copiedDigest = newSha256Digest();
        try (var input = Files.newInputStream(expected.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS); var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copiedDigest.update(buffer, 0, count);
            }
        }

        GitFileSnapshot afterCopy = readGitFileSnapshot(expected.path(), description);
        String copiedHash = HexFormat.of().formatHex(copiedDigest.digest());
        if (!expected.equals(afterCopy) || !expected.sha256().equals(copiedHash)) {
            throw new IllegalStateException(description + " changed while it was being captured.");
        }
    }

    private static GitFileSnapshot snapshotGitFile(Path path, BasicFileAttributes attributes, String sha256) {
        return new GitFileSnapshot(path, attributes.fileKey(), attributes.size(), sha256);
    }

    private static boolean sameGitFileAttributes(BasicFileAttributes first, BasicFileAttributes second) {
        return first.isRegularFile() == second.isRegularFile() && java.util.Objects.equals(first.fileKey(), second.fileKey()) && first.size() == second.size();
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime does not provide SHA-256 for Git index capture.", e);
        }
    }

    private static Path parseRepositoryGitPath(Path repository, String value, String description) {
        if (value.isEmpty() || value.indexOf('\0') >= 0 || value.lines().count() != 1) {
            throw new IllegalStateException("Git returned an invalid MCEF " + description + " path.");
        }
        try {
            Path reportedPath = Path.of(value);
            return reportedPath.isAbsolute() ? absoluteNormalized(reportedPath) : absoluteNormalized(repository.resolve(reportedPath));
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Git returned an invalid MCEF " + description + " path.", e);
        }
    }

    private static Path createPrivateGitDirectory() {
        try {
            try {
                return Files.createTempDirectory("mcef-provenance-index-", PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
            } catch (UnsupportedOperationException e) {
                return Files.createTempDirectory("mcef-provenance-index-");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create a private Git index directory.", e);
        }
    }

    private static String privateGitConfig(String objectFormat) {
        StringBuilder config = new StringBuilder("[core]\n\trepositoryformatversion = ");
        config.append(objectFormat.equals("sha256") ? "1" : "0");
        config.append("\n\tbare = false\n");
        if (objectFormat.equals("sha256")) {
            config.append("[extensions]\n\tobjectFormat = sha256\n");
        }
        config.append("[advice]\n\tsparseIndexExpanded = false\n");
        return config.toString();
    }

    private static void deletePrivateGitDirectory(Path directory) throws IOException {
        IOException failure = null;
        List<Path> paths;
        try (var stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                if (failure == null) {
                    failure = new IOException("Failed to clean private Git index directory " + directory + ".", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String requireCommit(String value, String source, Path repository) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!COMMIT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException("Git returned an invalid " + source + " commit for " + repository + ".");
        }
        return normalized;
    }

    private static IllegalStateException gitFailure(List<String> command, GitResult result) {
        String detail = result.output().trim();
        String message = "Git command failed (" + String.join(" ", command) + ")";
        if (!detail.isEmpty()) {
            message += ":\n" + detail;
        }
        return new IllegalStateException(message);
    }

    private static IllegalStateException gitBinaryFailure(List<String> command, GitBinaryResult result) {
        String detail = new String(result.output(), StandardCharsets.UTF_8).trim();
        String message = "Git command failed (" + String.join(" ", command) + ")";
        if (!detail.isEmpty()) {
            message += ":\n" + detail;
        }
        return new IllegalStateException(message);
    }

    private static Path absoluteNormalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve repository path " + path + ".", e);
        }
    }

    public record Resolution(String commit, boolean trackedSubmodule) {
    }

    public record GitResult(int exitCode, String output) {
    }

    record GitBinaryResult(int exitCode, byte[] output) {
    }

    @FunctionalInterface
    interface GitCommandRunner {
        GitResult run(Path directory, List<String> arguments, boolean failOnError, Map<String, String> environmentOverrides);

        default GitBinaryResult runBinary(Path directory, List<String> arguments, byte[] input, boolean failOnError, Map<String, String> environmentOverrides, int maximumOutputBytes) {
            return JcefProvenance.runGitBinary(directory, arguments, input, failOnError, environmentOverrides, maximumOutputBytes);
        }
    }

    private record IndexEntry(String mode, String objectId, String stage) {
    }

    private record GitFileSnapshot(Path path, Object fileKey, long size, String sha256) {
    }

    private record GitMetadataSnapshot(Path path, boolean present, Object fileKey, boolean directory, boolean regularFile, boolean symbolicLink, long size, FileTime lastModifiedTime) {
        private static GitMetadataSnapshot missing(Path path) {
            return new GitMetadataSnapshot(path, false, null, false, false, false, 0L, FileTime.fromMillis(0L));
        }
    }

    private record CapturedGitIndex(Path privateGitDirectory, GitFileSnapshot liveIndex, GitFileSnapshot liveSharedIndex, Map<String, String> environment) implements AutoCloseable {
        private void requireLiveFilesUnchanged(Path repository, String repositoryPath, GitCommandRunner gitRunner) {
            GitFileSnapshot currentIndex = requireReadableGitIndex(repository, gitRunner);
            if (!liveIndex.equals(currentIndex)) {
                throw new IllegalStateException("MCEF Git index changed while resolving " + repositoryPath + ".");
            }

            String sharedIndexValue = gitRunner.run(repository, List.of("rev-parse", "--shared-index-path"), true, Map.of()).output().trim();
            if (liveSharedIndex == null) {
                if (!sharedIndexValue.isEmpty()) {
                    throw new IllegalStateException("MCEF Git index changed from a full index to a split index while resolving " + repositoryPath + ".");
                }
                return;
            }
            Path currentSharedIndex = parseRepositoryGitPath(repository, sharedIndexValue, "shared index");
            if (!liveSharedIndex.path().equals(currentSharedIndex)) {
                throw new IllegalStateException("MCEF Git shared-index path changed while resolving " + repositoryPath + ".");
            }
            GitFileSnapshot currentSharedIndexSnapshot = requireReadableSharedIndex(liveIndex.path(), currentSharedIndex);
            if (!liveSharedIndex.equals(currentSharedIndexSnapshot)) {
                throw new IllegalStateException("MCEF Git shared index changed while resolving " + repositoryPath + ".");
            }
        }

        @Override
        public void close() {
            try {
                deletePrivateGitDirectory(privateGitDirectory);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to clean private Git index directory " + privateGitDirectory + ".", e);
            }
        }
    }
}
