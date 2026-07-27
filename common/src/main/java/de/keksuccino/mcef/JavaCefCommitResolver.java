package de.keksuccino.mcef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
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
import java.security.CodeSource;
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
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaCefCommitResolver {
    private static final String COMMIT_ATTRIBUTE = "java-cef-commit";
    private static final String JCEF_PATH = "common/java-cef";
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final Pattern INDEX_ENTRY_PATTERN = Pattern.compile("^(\\d{6}) ([0-9a-f]{40}|[0-9a-f]{64}) ([0-3])\\t([\\s\\S]+)$");
    private static final Pattern SHARED_INDEX_NAME_PATTERN = Pattern.compile("^sharedindex\\.(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final long GIT_TIMEOUT_SECONDS = 10;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<String> REPOSITORY_SELECTING_ENVIRONMENT_VARIABLES = Set.of("GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_CEILING_DIRECTORIES", "GIT_COMMON_DIR", "GIT_CONFIG", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_SYSTEM", "GIT_DIR", "GIT_DISCOVERY_ACROSS_FILESYSTEM", "GIT_GLOB_PATHSPECS", "GIT_GRAFT_FILE", "GIT_ICASE_PATHSPECS", "GIT_INDEX_FILE", "GIT_LITERAL_PATHSPECS", "GIT_NAMESPACE", "GIT_NOGLOB_PATHSPECS", "GIT_OBJECT_DIRECTORY", "GIT_PREFIX", "GIT_QUARANTINE_PATH", "GIT_SHALLOW_FILE", "GIT_WORK_TREE");

    private JavaCefCommitResolver() {
    }

    static String resolve(Class<?> definingClass, String configuredCommit) throws IOException {
        return resolve(getCodeSourceLocation(definingClass), configuredCommit);
    }

    static String resolve(URL codeSourceLocation, String configuredCommit) throws IOException {
        if (configuredCommit != null) {
            String normalized = normalizeCommit(configuredCommit);
            if (normalized == null) {
                throw new IOException("System property mcef.java.cef.commit must contain exactly one 40- or 64-character hexadecimal commit.");
            }
            return normalized;
        }

        String manifestCommit = resolveManifestCommit(codeSourceLocation);
        if (manifestCommit != null) {
            return manifestCommit;
        }

        Path codeSourceDirectory = toCodeSourceDirectory(codeSourceLocation);
        if (codeSourceDirectory != null) {
            String developmentCommit = resolveDevelopmentCommit(codeSourceDirectory);
            if (developmentCommit != null) {
                return developmentCommit;
            }
        }

        throw new IOException("Unable to resolve java-cef commit from the MCEF code source. The MCEF JAR must contain manifest attribute java-cef-commit, or development classes must belong to a checkout containing common/java-cef.");
    }

    static String resolveManifestCommit(URL codeSourceLocation) throws IOException {
        Manifest manifest = readCodeSourceManifest(codeSourceLocation);
        if (manifest == null) {
            return null;
        }

        String rawCommit = manifest.getMainAttributes().getValue(COMMIT_ATTRIBUTE);
        if (rawCommit == null) {
            return null;
        }

        String normalized = normalizeCommit(rawCommit);
        if (normalized == null) {
            throw new IOException("MCEF code-source manifest attribute java-cef-commit must contain exactly one 40- or 64-character hexadecimal commit.");
        }
        return normalized;
    }

    static String resolveDevelopmentCommit(Path codeSourceDirectory) throws IOException {
        return resolveDevelopmentCommit(codeSourceDirectory, JavaCefCommitResolver::runGit);
    }

    static String resolveDevelopmentCommit(Path codeSourceDirectory, GitCommandRunner gitRunner) throws IOException {
        SourceRootResolution sourceRootResolution = findSourceRoot(codeSourceDirectory, gitRunner);
        if (sourceRootResolution == null) {
            return null;
        }

        requireUnchangedGitMetadata(sourceRootResolution);
        Path sourceRoot = sourceRootResolution.path();
        Path javaCefRepository = sourceRoot.resolve(JCEF_PATH).normalize();
        if (!Files.isDirectory(javaCefRepository)) {
            return null;
        }

        requireExactGitWorktree(javaCefRepository, "common/java-cef", gitRunner);

        String head = requireCommit(gitRunner.run(javaCefRepository, List.of("rev-parse", "--verify", "HEAD^{commit}"), true, Map.of()).output().trim(), "java-cef HEAD");
        requireUnchangedGitMetadata(sourceRootResolution);
        // Discovery origin is a provenance decision, not a hint. In particular, a root that Git
        // identified must always query the parent index even if its .git entry later vanishes.
        String gitlink = sourceRootResolution.origin() == SourceRootOrigin.GIT_WORKTREE ? resolveTrackedGitlink(sourceRoot, gitRunner) : null;
        if (gitlink != null && !gitlink.equals(head)) {
            throw new IOException("java-cef HEAD " + head + " does not match the tracked gitlink " + gitlink + ".");
        }

        JcefArtifactInputProof.requireMatchesHead(javaCefRepository, head, gitRunner);
        requireUnchangedGitMetadata(sourceRootResolution);
        return gitlink != null ? gitlink : head;
    }

    private static SourceRootResolution findSourceRoot(Path codeSourceDirectory, GitCommandRunner gitRunner) throws IOException {
        Path normalizedCodeSource = codeSourceDirectory.toAbsolutePath().normalize();
        Path layoutRoot = findLayoutSourceRoot(normalizedCodeSource);
        // The snapshots bracket Git discovery so an appearing or disappearing .git entry cannot
        // convert a checkout into a layout-only archive, or an archive into a checkout, mid-query.
        GitMetadataSnapshot layoutMetadataBeforeQuery = layoutRoot == null ? null : inspectGitMetadata(layoutRoot);
        GitResult rootResult = gitRunner.run(normalizedCodeSource, List.of("rev-parse", "--show-toplevel"), false, Map.of());
        if (rootResult.exitCode() == 0) {
            Path gitRoot = parseGitPath(rootResult.output(), "MCEF source root");
            if (isMcefSourceLayout(gitRoot)) {
                GitMetadataSnapshot metadataBeforeQuery = gitRoot.equals(layoutRoot) ? layoutMetadataBeforeQuery : null;
                GitMetadataSnapshot metadataAfterQuery = inspectGitMetadata(gitRoot);
                if (!metadataAfterQuery.present() || (metadataBeforeQuery != null && !metadataBeforeQuery.equals(metadataAfterQuery))) {
                    throw changedGitMetadata(gitRoot, "discovering the MCEF source root");
                }
                return new SourceRootResolution(gitRoot, SourceRootOrigin.GIT_WORKTREE, metadataAfterQuery);
            }
        }

        // Source archives can contain a direct java-cef clone without retaining the MCEF
        // repository metadata. Probe only ancestors of the defining class output, never user.dir.
        if (layoutRoot == null) {
            return null;
        }
        GitMetadataSnapshot layoutMetadataAfterQuery = inspectGitMetadata(layoutRoot);
        if (!layoutMetadataBeforeQuery.equals(layoutMetadataAfterQuery)) {
            throw changedGitMetadata(layoutRoot, "discovering the MCEF source root");
        }
        if (layoutMetadataAfterQuery.present()) {
            throw new IOException("MCEF Git metadata is present at " + layoutRoot.resolve(".git") + ", but Git did not identify that layout as the MCEF worktree; refusing layout-only provenance.");
        }
        return new SourceRootResolution(layoutRoot, SourceRootOrigin.LAYOUT_ONLY, layoutMetadataAfterQuery);
    }

    private static Path findLayoutSourceRoot(Path codeSourceDirectory) {
        Path candidateRoot = codeSourceDirectory;
        while (candidateRoot != null) {
            if (isMcefSourceLayout(candidateRoot)) {
                return candidateRoot;
            }
            candidateRoot = candidateRoot.getParent();
        }
        return null;
    }

    private static boolean isMcefSourceLayout(Path directory) {
        return Files.isRegularFile(directory.resolve("build.gradle")) && Files.isDirectory(directory.resolve(JCEF_PATH));
    }

    private static GitMetadataSnapshot inspectGitMetadata(Path repository) throws IOException {
        Path metadata = repository.toAbsolutePath().normalize().resolve(".git");
        try {
            BasicFileAttributes attributes = Files.readAttributes(metadata, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new GitMetadataSnapshot(metadata, true, attributes.fileKey(), attributes.isDirectory(), attributes.isRegularFile(), attributes.isSymbolicLink(), attributes.size(), attributes.lastModifiedTime());
        } catch (NoSuchFileException e) {
            return GitMetadataSnapshot.missing(metadata);
        } catch (IOException e) {
            throw new IOException("Failed to inspect MCEF Git metadata at " + metadata + ".", e);
        }
    }

    private static void requireUnchangedGitMetadata(SourceRootResolution sourceRootResolution) throws IOException {
        // A missing snapshot is meaningful for a real source archive. Keep checking it just like
        // checkout metadata so either mode fails closed if filesystem provenance changes.
        GitMetadataSnapshot current = inspectGitMetadata(sourceRootResolution.path());
        if (!sourceRootResolution.metadata().equals(current)) {
            throw changedGitMetadata(sourceRootResolution.path(), "resolving java-cef provenance");
        }
    }

    private static IOException changedGitMetadata(Path sourceRoot, String operation) {
        return new IOException("MCEF Git metadata changed while " + operation + " at " + sourceRoot.resolve(".git") + ".");
    }

    private static void requireExactGitWorktree(Path repository, String description, GitCommandRunner gitRunner) throws IOException {
        GitResult result = gitRunner.run(repository, List.of("rev-parse", "--show-toplevel"), true, Map.of());
        Path reportedRoot = parseGitPath(result.output(), description + " source root");
        if (!reportedRoot.toRealPath().equals(repository.toRealPath())) {
            throw new IOException("Expected " + description + " to be a standalone Git worktree, but Git resolved " + reportedRoot + ".");
        }
    }

    private static URL getCodeSourceLocation(Class<?> definingClass) {
        if (definingClass == null || definingClass.getProtectionDomain() == null) {
            return null;
        }
        CodeSource codeSource = definingClass.getProtectionDomain().getCodeSource();
        return codeSource == null ? null : codeSource.getLocation();
    }

    private static Manifest readCodeSourceManifest(URL codeSourceLocation) throws IOException {
        if (codeSourceLocation == null) {
            return null;
        }

        if ("jar".equalsIgnoreCase(codeSourceLocation.getProtocol())) {
            var connection = codeSourceLocation.openConnection();
            if (connection instanceof JarURLConnection jarConnection) {
                return jarConnection.getManifest();
            }
            return null;
        }
        if (!"file".equalsIgnoreCase(codeSourceLocation.getProtocol())) {
            return null;
        }

        Path codeSourcePath = toPath(codeSourceLocation);
        if (Files.isDirectory(codeSourcePath)) {
            Path manifestPath = codeSourcePath.resolve("META-INF/MANIFEST.MF");
            if (!Files.isRegularFile(manifestPath)) {
                return null;
            }
            try (var input = Files.newInputStream(manifestPath)) {
                return new Manifest(input);
            }
        }
        if (!Files.isRegularFile(codeSourcePath)) {
            return null;
        }

        try (JarFile jarFile = new JarFile(codeSourcePath.toFile(), false)) {
            return jarFile.getManifest();
        }
    }

    private static Path toCodeSourceDirectory(URL codeSourceLocation) throws IOException {
        if (codeSourceLocation == null || !"file".equalsIgnoreCase(codeSourceLocation.getProtocol())) {
            return null;
        }
        Path path = toPath(codeSourceLocation);
        return Files.isDirectory(path) ? path : null;
    }

    private static Path toPath(URL url) throws IOException {
        try {
            URI uri = url.toURI();
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Invalid MCEF code-source URL: " + url, e);
        }
    }

    private static String resolveTrackedGitlink(Path sourceRoot, GitCommandRunner gitRunner) throws IOException {
        requireExactGitWorktree(sourceRoot, "MCEF", gitRunner);
        GitResult result;
        try (CapturedGitIndex capturedIndex = captureGitIndex(sourceRoot, gitRunner)) {
            // The live index is deliberately not queried. A process can replace it for exactly the
            // duration of ls-files and restore the original attributes before a later snapshot.
            result = gitRunner.run(sourceRoot, List.of("ls-files", "--stage", "-z", "--", JCEF_PATH), true, capturedIndex.environment());
            capturedIndex.requireLiveFilesUnchanged(sourceRoot, gitRunner);
        }

        List<IndexEntry> entries = parseIndexEntries(result.output());
        if (entries.isEmpty()) {
            return null;
        }
        if (entries.size() != 1 || !"0".equals(entries.getFirst().stage())) {
            throw new IOException("Could not resolve one unambiguous index entry for " + JCEF_PATH + ".");
        }

        IndexEntry entry = entries.getFirst();
        if (!"160000".equals(entry.mode())) {
            return null;
        }
        return requireCommit(entry.objectId(), "java-cef gitlink");
    }

    private static CapturedGitIndex captureGitIndex(Path repository, GitCommandRunner gitRunner) throws IOException {
        GitFileSnapshot indexSnapshot = requireReadableGitIndex(repository, gitRunner);
        String sharedIndexValue = gitRunner.run(repository, List.of("rev-parse", "--shared-index-path"), true, Map.of()).output().trim();
        GitFileSnapshot indexAfterSharedPathQuery = requireReadableGitIndex(repository, gitRunner);
        if (!indexSnapshot.equals(indexAfterSharedPathQuery)) {
            throw new IOException("MCEF Git index changed while preparing the provenance query.");
        }

        Path sharedIndex = sharedIndexValue.isEmpty() ? null : parseRepositoryGitPath(repository, sharedIndexValue, "shared index");
        GitFileSnapshot sharedIndexSnapshot = sharedIndex == null ? null : requireReadableSharedIndex(indexSnapshot.path(), sharedIndex);
        String objectFormat = gitRunner.run(repository, List.of("rev-parse", "--show-object-format"), true, Map.of()).output().trim();
        if (!objectFormat.equals("sha1") && !objectFormat.equals("sha256")) {
            throw new IOException("Git returned an unsupported MCEF object format.");
        }
        String objectDirectoryValue = gitRunner.run(repository, List.of("rev-parse", "--git-path", "objects"), true, Map.of()).output().trim();
        Path objectDirectory = parseRepositoryGitPath(repository, objectDirectoryValue, "object directory");
        if (!Files.isDirectory(objectDirectory)) {
            throw new IOException("MCEF Git object directory is unavailable at " + objectDirectory + ".");
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
            Map<String, String> environment = Map.of("GIT_DIR", privateGitDirectory.toString(), "GIT_WORK_TREE", repository.toString(), "GIT_INDEX_FILE", privateGitDirectory.resolve("index").toString(), "GIT_OBJECT_DIRECTORY", objectDirectory.toRealPath().toString(), "GIT_CONFIG_GLOBAL", privateGitDirectory.resolve("global-config").toString(), "GIT_CONFIG_NOSYSTEM", "1", "GIT_OPTIONAL_LOCKS", "0");
            return new CapturedGitIndex(privateGitDirectory, indexSnapshot, sharedIndexSnapshot, environment);
        } catch (IOException e) {
            try {
                deletePrivateGitDirectory(privateGitDirectory);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private static GitFileSnapshot requireReadableGitIndex(Path repository, GitCommandRunner gitRunner) throws IOException {
        String value = gitRunner.run(repository, List.of("rev-parse", "--git-path", "index"), true, Map.of()).output().trim();
        if (value.isEmpty()) {
            throw new IOException("Git returned an empty MCEF index path for " + repository + ".");
        }
        return readGitFileSnapshot(parseRepositoryGitPath(repository, value, "index"), "MCEF Git index");
    }

    private static GitFileSnapshot requireReadableSharedIndex(Path index, Path sharedIndex) throws IOException {
        Path fileName = sharedIndex.getFileName();
        if (fileName == null || !SHARED_INDEX_NAME_PATTERN.matcher(fileName.toString()).matches()) {
            throw new IOException("Git returned an invalid MCEF shared-index path.");
        }
        Path indexParent = index.getParent();
        Path sharedIndexParent = sharedIndex.getParent();
        if (indexParent == null || sharedIndexParent == null || !indexParent.toRealPath().equals(sharedIndexParent.toRealPath())) {
            throw new IOException("MCEF Git shared index is outside the live index directory at " + sharedIndex + ".");
        }
        return readGitFileSnapshot(sharedIndex, "MCEF Git shared index");
    }

    private static GitFileSnapshot readGitFileSnapshot(Path path, String description) throws IOException {
        BasicFileAttributes attributesBeforeRead;
        try {
            attributesBeforeRead = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IOException(description + " is unavailable at " + path + ".", e);
        }
        if (!attributesBeforeRead.isRegularFile()) {
            throw new IOException(description + " is not a regular file at " + path + ".");
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
            throw new IOException(description + " is unreadable at " + path + ".", e);
        }

        BasicFileAttributes attributesAfterRead;
        try {
            attributesAfterRead = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IOException(description + " changed while being read at " + path + ".", e);
        }
        GitFileSnapshot beforeRead = snapshotGitFile(path, attributesBeforeRead, HexFormat.of().formatHex(digest.digest()));
        if (!sameGitFileAttributes(attributesBeforeRead, attributesAfterRead) || bytesRead != attributesBeforeRead.size()) {
            throw new IOException(description + " changed while being read at " + path + ".");
        }
        return beforeRead;
    }

    private static void copyVerifiedGitFile(GitFileSnapshot expected, Path target, String description) throws IOException {
        GitFileSnapshot beforeCopy = readGitFileSnapshot(expected.path(), description);
        if (!expected.equals(beforeCopy)) {
            throw new IOException(description + " changed before it could be captured.");
        }

        MessageDigest copiedDigest = newSha256Digest();
        try (var input = Files.newInputStream(expected.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS); var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copiedDigest.update(buffer, 0, count);
            }
        } catch (IOException e) {
            throw new IOException("Failed to capture " + description + " at " + expected.path() + ".", e);
        }

        GitFileSnapshot afterCopy = readGitFileSnapshot(expected.path(), description);
        String copiedHash = HexFormat.of().formatHex(copiedDigest.digest());
        if (!expected.equals(afterCopy) || !expected.sha256().equals(copiedHash)) {
            throw new IOException(description + " changed while it was being captured.");
        }
    }

    private static GitFileSnapshot snapshotGitFile(Path path, BasicFileAttributes attributes, String sha256) {
        return new GitFileSnapshot(path, attributes.fileKey(), attributes.size(), sha256);
    }

    private static boolean sameGitFileAttributes(BasicFileAttributes first, BasicFileAttributes second) {
        return first.isRegularFile() == second.isRegularFile() && java.util.Objects.equals(first.fileKey(), second.fileKey()) && first.size() == second.size();
    }

    private static MessageDigest newSha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Java runtime does not provide SHA-256 for Git index capture.", e);
        }
    }

    private static Path parseRepositoryGitPath(Path repository, String value, String description) throws IOException {
        if (value.isEmpty() || value.indexOf('\0') >= 0 || value.lines().count() != 1) {
            throw new IOException("Git returned an invalid MCEF " + description + " path.");
        }
        try {
            Path reportedPath = Path.of(value);
            return reportedPath.isAbsolute() ? reportedPath.toAbsolutePath().normalize() : repository.resolve(reportedPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IOException("Git returned an invalid MCEF " + description + " path.", e);
        }
    }

    private static Path createPrivateGitDirectory() throws IOException {
        try {
            return Files.createTempDirectory("mcef-provenance-index-", PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
        } catch (UnsupportedOperationException e) {
            return Files.createTempDirectory("mcef-provenance-index-");
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

    private static List<IndexEntry> parseIndexEntries(String output) throws IOException {
        List<IndexEntry> entries = new ArrayList<>();
        int offset = 0;
        while (offset < output.length()) {
            int terminator = output.indexOf('\0', offset);
            if (terminator < 0) {
                throw new IOException("Git returned an unterminated index entry for " + JCEF_PATH + ".");
            }
            String record = output.substring(offset, terminator);
            offset = terminator + 1;
            if (record.isEmpty()) {
                continue;
            }

            Matcher matcher = INDEX_ENTRY_PATTERN.matcher(record);
            if (!matcher.matches()) {
                throw new IOException("Git returned an invalid index entry for " + JCEF_PATH + ".");
            }
            if (!JCEF_PATH.equals(matcher.group(4))) {
                continue;
            }
            entries.add(new IndexEntry(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return entries;
    }

    private static Path parseGitPath(String output, String description) throws IOException {
        String value = output.trim();
        if (value.isEmpty() || value.indexOf('\0') >= 0 || value.lines().count() != 1) {
            throw new IOException("Git returned an invalid " + description + ".");
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IOException("Git returned an invalid " + description + ".", e);
        }
    }

    private static String requireCommit(String rawCommit, String description) throws IOException {
        String normalized = normalizeCommit(rawCommit);
        if (normalized == null) {
            throw new IOException("Git returned an invalid " + description + " commit.");
        }
        return normalized;
    }

    private static String normalizeCommit(String rawCommit) {
        if (rawCommit == null) {
            return null;
        }
        String normalized = rawCommit.trim().toLowerCase(Locale.ROOT);
        return COMMIT_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    static GitResult runGit(Path directory, List<String> arguments, boolean failOnError) throws IOException {
        return runGit(directory, arguments, failOnError, Map.of());
    }

    static GitResult runGit(Path directory, List<String> arguments, boolean failOnError, Map<String, String> environmentOverrides) throws IOException {
        List<String> command = new ArrayList<>(arguments.size() + 3);
        command.add("git");
        command.add("-C");
        command.add(directory.toAbsolutePath().normalize().toString());
        command.addAll(arguments);

        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        sanitizeGitEnvironment(processBuilder.environment());
        processBuilder.environment().putAll(environmentOverrides);

        Process process = processBuilder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        // A virtual reader can deadlock with ProcessReaper on Java 25/macOS when the child exits
        // while ProcessPipeInputStream is pinned in a native read. A daemon platform thread lets
        // the operating-system pipe reach EOF normally and is bounded again below.
        Thread outputReader = Thread.ofPlatform().daemon(true).name("MCEF-Git-Output").start(() -> {
            try (var input = process.getInputStream()) {
                input.transferTo(output);
            } catch (IOException e) {
                readFailure.set(e);
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) terminateGitProcess(process, outputReader);
            else awaitOutputReader(process, outputReader);
        } catch (InterruptedException e) {
            terminateGitProcess(process, outputReader);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving java-cef Git provenance.", e);
        }

        if (!finished) {
            throw new IOException("Git command timed out while resolving java-cef provenance.");
        }
        if (readFailure.get() != null) {
            throw new IOException("Failed to read Git output while resolving java-cef provenance.", readFailure.get());
        }

        GitResult result = new GitResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        if (failOnError && result.exitCode() != 0) {
            String detail = result.output().trim();
            throw new IOException("Git command failed while resolving java-cef provenance" + (detail.isEmpty() ? "." : ": " + detail));
        }
        return result;
    }

    static GitBinaryResult runGitBinary(Path directory, List<String> arguments, byte[] input, boolean failOnError, Map<String, String> environmentOverrides, int maximumOutputBytes) throws IOException {
        List<String> command = new ArrayList<>(arguments.size() + 3);
        command.add("git");
        command.add("-C");
        command.add(directory.toAbsolutePath().normalize().toString());
        command.addAll(arguments);

        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        sanitizeGitEnvironment(processBuilder.environment());
        processBuilder.environment().putAll(environmentOverrides);

        Process process = processBuilder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread outputReader = Thread.ofPlatform().daemon(true).name("MCEF-Git-Binary-Output").start(() -> {
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

        try (var processInput = process.getOutputStream()) {
            processInput.write(input);
        } catch (IOException e) {
            terminateGitProcess(process, outputReader);
            throw new IOException("Failed to write Git input while resolving java-cef provenance.", e);
        }

        boolean finished;
        try {
            finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) terminateGitProcess(process, outputReader);
            else awaitOutputReader(process, outputReader);
        } catch (InterruptedException e) {
            terminateGitProcess(process, outputReader);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving java-cef Git provenance.", e);
        }

        if (!finished) {
            throw new IOException("Git command timed out while resolving java-cef provenance.");
        }
        if (readFailure.get() != null) {
            throw new IOException("Failed to read Git output while resolving java-cef provenance.", readFailure.get());
        }

        GitBinaryResult result = new GitBinaryResult(process.exitValue(), output.toByteArray());
        if (failOnError && result.exitCode() != 0) {
            String detail = new String(result.output(), StandardCharsets.UTF_8).trim();
            throw new IOException("Git command failed while resolving java-cef provenance" + (detail.isEmpty() ? "." : ": " + detail));
        }
        return result;
    }

    private static void awaitOutputReader(Process process, Thread outputReader) throws IOException, InterruptedException {
        outputReader.join(TimeUnit.SECONDS.toMillis(GIT_TIMEOUT_SECONDS));
        if (!outputReader.isAlive()) return;
        terminateGitProcess(process, outputReader);
        throw new IOException("Timed out while reading Git output for java-cef provenance.");
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

    private static void sanitizeGitEnvironment(Map<String, String> environment) {
        Set<String> keys = new HashSet<>(environment.keySet());
        for (String key : keys) {
            if (REPOSITORY_SELECTING_ENVIRONMENT_VARIABLES.contains(key) || key.equals("GIT_CONFIG_COUNT") || key.startsWith("GIT_CONFIG_KEY_") || key.startsWith("GIT_CONFIG_VALUE_")) {
                environment.remove(key);
            }
        }
    }

    @FunctionalInterface
    interface GitCommandRunner {
        GitResult run(Path directory, List<String> arguments, boolean failOnError, Map<String, String> environmentOverrides) throws IOException;

        default GitBinaryResult runBinary(Path directory, List<String> arguments, byte[] input, boolean failOnError, Map<String, String> environmentOverrides, int maximumOutputBytes) throws IOException {
            return JavaCefCommitResolver.runGitBinary(directory, arguments, input, failOnError, environmentOverrides, maximumOutputBytes);
        }
    }

    record GitResult(int exitCode, String output) {
    }

    record GitBinaryResult(int exitCode, byte[] output) {
    }

    private record IndexEntry(String mode, String objectId, String stage) {
    }

    private record GitFileSnapshot(Path path, Object fileKey, long size, String sha256) {
    }

    private record CapturedGitIndex(Path privateGitDirectory, GitFileSnapshot liveIndex, GitFileSnapshot liveSharedIndex, Map<String, String> environment) implements AutoCloseable {
        private void requireLiveFilesUnchanged(Path repository, GitCommandRunner gitRunner) throws IOException {
            GitFileSnapshot currentIndex = requireReadableGitIndex(repository, gitRunner);
            if (!liveIndex.equals(currentIndex)) {
                throw new IOException("MCEF Git index changed while resolving " + JCEF_PATH + ".");
            }

            String sharedIndexValue = gitRunner.run(repository, List.of("rev-parse", "--shared-index-path"), true, Map.of()).output().trim();
            if (liveSharedIndex == null) {
                if (!sharedIndexValue.isEmpty()) {
                    throw new IOException("MCEF Git index changed from a full index to a split index while resolving " + JCEF_PATH + ".");
                }
                return;
            }
            Path currentSharedIndex = parseRepositoryGitPath(repository, sharedIndexValue, "shared index");
            if (!liveSharedIndex.path().equals(currentSharedIndex)) {
                throw new IOException("MCEF Git shared-index path changed while resolving " + JCEF_PATH + ".");
            }
            GitFileSnapshot currentSharedIndexSnapshot = requireReadableSharedIndex(liveIndex.path(), currentSharedIndex);
            if (!liveSharedIndex.equals(currentSharedIndexSnapshot)) {
                throw new IOException("MCEF Git shared index changed while resolving " + JCEF_PATH + ".");
            }
        }

        @Override
        public void close() throws IOException {
            deletePrivateGitDirectory(privateGitDirectory);
        }
    }

    private enum SourceRootOrigin {
        GIT_WORKTREE,
        LAYOUT_ONLY
    }

    private record SourceRootResolution(Path path, SourceRootOrigin origin, GitMetadataSnapshot metadata) {
    }

    private record GitMetadataSnapshot(Path path, boolean present, Object fileKey, boolean directory, boolean regularFile, boolean symbolicLink, long size, FileTime lastModifiedTime) {
        private static GitMetadataSnapshot missing(Path path) {
            return new GitMetadataSnapshot(path, false, null, false, false, false, 0L, FileTime.fromMillis(0L));
        }
    }
}
