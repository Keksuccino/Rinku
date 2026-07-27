package de.keksuccino.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JcefProvenanceTest {
    private static final List<String> SOURCE_ROOT_QUERY = List.of("rev-parse", "--show-toplevel");
    private static final List<String> HEAD_QUERY = List.of("rev-parse", "--verify", "HEAD^{commit}");

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesExactTrackedGitlinkFromCleanSubmodule() throws Exception {
        RepositoryFixture fixture = createTrackedSubmodule();

        JcefProvenance.Resolution resolution = JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath());

        assertEquals(fixture.jcefCommit(), resolution.commit());
        assertTrue(resolution.trackedSubmodule());
    }

    @Test
    void repeatedTextAndBinaryGitCommandsDrainWithoutHanging() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            JcefProvenance.runGit(temporaryDirectory, List.of("init", "--quiet"), true);
            for (int i = 0; i < 24; i++) {
                JcefProvenance.GitResult textResult = JcefProvenance.runGit(temporaryDirectory, List.of("rev-parse", "--git-dir"), true);
                JcefProvenance.GitBinaryResult binaryResult = JcefProvenance.runGitBinary(temporaryDirectory, List.of("hash-object", "--stdin"), "mcef".getBytes(StandardCharsets.UTF_8), true, Map.of(), 128);
                assertEquals(".git", textResult.output().trim());
                assertTrue(binaryResult.output().length > 0);
            }
        });
    }

    @Test
    void resolvesCleanDirectCloneHeadWhenNoGitlinkExists() throws Exception {
        RepositoryFixture fixture = createDirectClone();

        JcefProvenance.Resolution resolution = JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath());

        assertEquals(fixture.jcefCommit(), resolution.commit());
        assertFalse(resolution.trackedSubmodule());
    }

    @Test
    void resolvesCleanDirectCloneWhenParentGitMetadataIsUnavailable() throws Exception {
        Path root = temporaryDirectory.resolve("source-archive");
        String commit = initializeJcefRepository(root);

        JcefProvenance.Resolution resolution = JcefProvenance.resolve(root, "common/java-cef");

        assertEquals(commit, resolution.commit());
        assertFalse(resolution.trackedSubmodule());
    }

    @Test
    void resolvesSourceArchiveNestedInsideUnrelatedGitWorktree() throws Exception {
        Path enclosingRepository = temporaryDirectory.resolve("enclosing-repository");
        initializeRepositoryWithCommit(enclosingRepository);
        Path root = enclosingRepository.resolve("untracked/source-archive");
        String commit = initializeJcefRepository(root);

        JcefProvenance.Resolution resolution = JcefProvenance.resolve(root, "common/java-cef");

        assertEquals(commit, resolution.commit());
        assertFalse(resolution.trackedSubmodule());
    }

    @Test
    void rejectsParentGitMetadataDisappearanceAfterCheckoutOriginIsRecorded() throws Exception {
        RepositoryFixture fixture = createTrackedSubmodule();
        Path removedMetadata = fixture.root().resolve(".git.removed");
        AtomicBoolean removed = new AtomicBoolean();
        JcefProvenance.GitCommandRunner gitRunner = mutateBeforeGitCommand(fixture.jcef(), SOURCE_ROOT_QUERY, () -> Files.move(fixture.root().resolve(".git"), removedMetadata), removed);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath(), gitRunner));

        assertTrue(removed.get());
        assertTrue(failure.getMessage().contains("MCEF Git metadata changed while resolving java-cef provenance"));
    }

    @Test
    void rejectsParentGitMetadataAppearanceAfterArchiveOriginIsRecorded() throws Exception {
        Path root = temporaryDirectory.resolve("archive-metadata-appears");
        String commit = initializeJcefRepository(root);
        RepositoryFixture fixture = new RepositoryFixture(root, root.resolve("common/java-cef"), "common/java-cef", commit);
        AtomicBoolean appeared = new AtomicBoolean();
        JcefProvenance.GitCommandRunner gitRunner = mutateAfterGitCommand(fixture.jcef(), HEAD_QUERY, () -> Files.createDirectory(fixture.root().resolve(".git")), appeared);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath(), gitRunner));

        assertTrue(appeared.get());
        assertTrue(failure.getMessage().contains("MCEF Git metadata changed while resolving java-cef provenance"));
    }

    @Test
    void rejectsTrackedGitlinkHeadMismatch() throws Exception {
        RepositoryFixture fixture = createTrackedSubmodule();
        Files.writeString(fixture.jcef().resolve("second.txt"), "second\n");
        git(fixture.jcef(), "add", "second.txt");
        git(fixture.jcef(), "commit", "-m", "second");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()));

        assertTrue(failure.getMessage().contains("does not match the tracked gitlink"), failure.getMessage());
    }

    @Test
    void rejectsUnstagedTrackedChanges() throws Exception {
        RepositoryFixture fixture = createDirectClone();
        Files.writeString(javaSource(fixture), "final class Source { int changed; }\n");

        assertDirtyRejected(fixture);
    }

    @Test
    void rejectsStagedChanges() throws Exception {
        RepositoryFixture fixture = createDirectClone();
        Files.writeString(javaSource(fixture), "final class Source { int changed; }\n");
        git(fixture.jcef(), "add", "java/org/cef/Source.java");

        assertDirtyRejected(fixture);
    }

    @Test
    void rejectsUntrackedChanges() throws Exception {
        RepositoryFixture fixture = createDirectClone();
        Files.writeString(fixture.jcef().resolve("java/org/cef/Untracked.java"), "final class Untracked {}\n");

        assertDirtyRejected(fixture);
    }

    @Test
    void rejectsAssumeUnchangedSourceWithPreservedStatData() throws Exception {
        RepositoryFixture fixture = createDirectClone("assume-unchanged");
        Path source = javaSource(fixture);
        FileTime modifiedTime = Files.getLastModifiedTime(source);
        git(fixture.jcef(), "update-index", "--assume-unchanged", "java/org/cef/Source.java");
        Files.writeString(source, "final class Sourcf {}\n");
        Files.setLastModifiedTime(source, modifiedTime);

        assertTrue(git(fixture.jcef(), "status", "--porcelain=v1").isEmpty());
        assertDirtyRejected(fixture);
    }

    @Test
    void rejectsSkipWorktreeSourceModification() throws Exception {
        RepositoryFixture fixture = createDirectClone("skip-worktree");
        git(fixture.jcef(), "update-index", "--skip-worktree", "java/org/cef/Source.java");
        Files.writeString(javaSource(fixture), "final class Sourcf {}\n");

        assertTrue(git(fixture.jcef(), "status", "--porcelain=v1").isEmpty());
        assertDirtyRejected(fixture);
    }

    @Test
    void rejectsJavaInputIgnoredByGitignore() throws Exception {
        RepositoryFixture fixture = createDirectClone("gitignore-input");
        Files.writeString(fixture.jcef().resolve(".gitignore"), "/java/ignored/\n");

        assertIgnoredJavaInputRejected(fixture);
    }

    @Test
    void rejectsJavaInputIgnoredByInfoExclude() throws Exception {
        RepositoryFixture fixture = createDirectClone("info-exclude-input");
        Files.writeString(gitPath(fixture.jcef(), "info/exclude"), "/java/ignored/\n");

        assertIgnoredJavaInputRejected(fixture);
    }

    @Test
    void rejectsJavaInputIgnoredByConfiguredGlobalExcludeFile() throws Exception {
        RepositoryFixture fixture = createDirectClone("global-exclude-input");
        Path excludesFile = temporaryDirectory.resolve("global-excludes");
        Files.writeString(excludesFile, "/java/ignored/\n");
        Path globalConfig = temporaryDirectory.resolve("global.gitconfig");
        Files.writeString(globalConfig, "[core]\n\texcludesFile = " + excludesFile.toString().replace('\\', '/') + "\n");
        Path ignoredSource = createIgnoredJavaInput(fixture);

        assertEquals("java/ignored/Ignored.java", git(fixture.jcef(), Map.of("GIT_CONFIG_GLOBAL", globalConfig.toString()), "check-ignore", "java/ignored/Ignored.java").trim());
        assertTrue(Files.isRegularFile(ignoredSource));
        assertDirtyRejected(fixture);
    }

    @Test
    void acceptsIgnoredBuildAndDownloadOutputsOutsideArtifactInputs() throws Exception {
        RepositoryFixture fixture = createDirectClone("ignored-build-output");
        Files.writeString(fixture.jcef().resolve(".gitignore"), "/jcef_build/\n/binary_distrib/\n");
        Files.createDirectories(fixture.jcef().resolve("jcef_build/native/Release"));
        Files.writeString(fixture.jcef().resolve("jcef_build/native/Release/generated.bin"), "generated\n");
        Files.createDirectories(fixture.jcef().resolve("binary_distrib/macos_arm64"));
        Files.writeString(fixture.jcef().resolve("binary_distrib/macos_arm64/downloaded.bin"), "downloaded\n");

        assertEquals(fixture.jcefCommit(), JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()).commit());
    }

    @Test
    void acceptsCrLfCheckoutForLfJavaBlob() throws Exception {
        RepositoryFixture fixture = createDirectClone("crlf-source");
        Files.writeString(javaSource(fixture), "final class Source {}\r\n");

        assertEquals(fixture.jcefCommit(), JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()).commit());
    }

    @Test
    void rejectsSourceChangeHiddenByRepositoryCleanFilter() throws Exception {
        RepositoryFixture fixture = createDirectClone("clean-filter");
        Files.writeString(fixture.jcef().resolve(".gitattributes"), "java/**/*.java filter=provenance-hide\n");
        git(fixture.jcef(), "add", ".gitattributes");
        git(fixture.jcef(), "commit", "-m", "configure source filter");
        git(fixture.jcef(), "config", "filter.provenance-hide.clean", "git show HEAD:%f");
        git(fixture.jcef(), "config", "filter.provenance-hide.required", "true");
        Files.writeString(javaSource(fixture), "final class Sourcf {}\n");

        assertFalse(git(fixture.jcef(), "status", "--porcelain=v1").contains("Source.java"));
        assertDirtyRejected(fixture);
    }

    @Test
    void boundsIgnoredNonInputFloodInsideJavaTree() throws Exception {
        RepositoryFixture fixture = createDirectClone("bounded-java-walk");
        Path generated = fixture.jcef().resolve("java/generated");
        Files.createDirectories(generated);
        for (int index = 0; index < 400; index++) {
            Files.writeString(generated.resolve("generated-" + index + ".class"), "generated\n");
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()));

        assertTrue(failure.getMessage().contains("bounded provenance traversal limit"));
    }

    @Test
    void resolvesLinkedJavaCefWorktreeWithDetachedHead() throws Exception {
        Path root = temporaryDirectory.resolve("linked-jcef-root");
        initializeRepositoryWithCommit(root);
        Path origin = temporaryDirectory.resolve("linked-jcef-origin");
        initializeRepository(origin);
        createArtifactInputs(origin);
        git(origin, "add", ".");
        git(origin, "commit", "-m", "java-cef");
        String commit = git(origin, "rev-parse", "HEAD").trim();
        Path jcef = root.resolve("common/java-cef");
        Files.createDirectories(jcef.getParent());
        git(origin, "worktree", "add", "--detach", jcef.toString(), commit);

        JcefProvenance.Resolution resolution = JcefProvenance.resolve(root, "common/java-cef");

        assertEquals(commit, resolution.commit());
        assertFalse(resolution.trackedSubmodule());
    }

    @Test
    void resolvesSha256JavaCefHead() throws Exception {
        Path root = temporaryDirectory.resolve("sha256-root");
        initializeRepositoryWithCommit(root);
        Path jcef = root.resolve("common/java-cef");
        Files.createDirectories(jcef);
        git(jcef, "init", "--quiet", "--object-format=sha256");
        git(jcef, "config", "user.name", "MCEF Tests");
        git(jcef, "config", "user.email", "mcef-tests@example.invalid");
        createArtifactInputs(jcef);
        git(jcef, "add", ".");
        git(jcef, "commit", "-m", "sha256 java-cef");
        String commit = git(jcef, "rev-parse", "HEAD").trim();

        assertEquals(64, commit.length());
        assertEquals(commit, JcefProvenance.resolve(root, "common/java-cef").commit());
    }

    @Test
    void resolvesWithSplitJavaCefIndex() throws Exception {
        RepositoryFixture fixture = createDirectClone("split-jcef-index");
        git(fixture.jcef(), "update-index", "--split-index");

        assertEquals(fixture.jcefCommit(), JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()).commit());
    }

    @Test
    void resolvesSparseJavaCefCheckoutWhenEveryArtifactInputIsPresent() throws Exception {
        RepositoryFixture fixture = createDirectClone("sparse-jcef-checkout");
        git(fixture.jcef(), "sparse-checkout", "init", "--cone", "--sparse-index");
        git(fixture.jcef(), "sparse-checkout", "set", "java", "third_party");

        assertEquals(fixture.jcefCommit(), JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()).commit());
    }

    @Test
    void rejectsSparseJavaCefCheckoutMissingArtifactInputs() throws Exception {
        RepositoryFixture fixture = createDirectClone("sparse-jcef-missing");
        git(fixture.jcef(), "sparse-checkout", "init", "--cone", "--sparse-index");
        git(fixture.jcef(), "sparse-checkout", "set", "third_party");

        assertDirtyRejected(fixture);
    }

    @Test
    void rejectsArtifactInputRaceBetweenFinalHeadChecks() throws Exception {
        RepositoryFixture fixture = createDirectClone("artifact-race");
        AtomicInteger headQueries = new AtomicInteger();
        AtomicBoolean mutated = new AtomicBoolean();
        JcefProvenance.GitCommandRunner gitRunner = (directory, arguments, failOnError, environmentOverrides) -> {
            JcefProvenance.GitResult result = JcefProvenance.runGit(directory, arguments, failOnError, environmentOverrides);
            try {
                if (directory.toRealPath().equals(fixture.jcef().toRealPath()) && arguments.equals(HEAD_QUERY) && headQueries.incrementAndGet() == 2) {
                    Files.writeString(javaSource(fixture), "final class Sourcf {}\n");
                    mutated.set(true);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to mutate java-cef input during race test.", e);
            }
            return result;
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath(), gitRunner));

        assertTrue(mutated.get());
        assertTrue(failure.getMessage().contains("changed while commit provenance"));
    }

    @Test
    void rejectsMissingParentIndexForDirectClone() throws Exception {
        RepositoryFixture fixture = createDirectClone();
        Files.delete(gitPath(fixture.root(), "index"));

        assertParentIndexRejected(fixture);
    }

    @Test
    void rejectsMissingParentIndexWhenHeadTracksGitlink() throws Exception {
        RepositoryFixture fixture = createTrackedSubmodule();
        assertTrue(git(fixture.root(), "ls-tree", "HEAD", "--", fixture.relativeJcefPath()).startsWith("160000 "));
        Files.delete(gitPath(fixture.root(), "index"));

        assertParentIndexRejected(fixture);
    }

    @Test
    void rejectsUnreadableParentIndex() throws Exception {
        RepositoryFixture fixture = createDirectClone();
        Path index = gitPath(fixture.root(), "index");
        assumeTrue(Files.getFileStore(index).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(index);
        try {
            Files.setPosixFilePermissions(index, Set.of());
            assumeFalse(Files.isReadable(index));
            assertParentIndexRejected(fixture);
        } finally {
            Files.setPosixFilePermissions(index, originalPermissions);
        }
    }

    @Test
    void rejectsFailedParentIndexQuery() throws Exception {
        RepositoryFixture fixture = createDirectClone();
        Files.writeString(gitPath(fixture.root(), "index"), "not a Git index\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()));

        assertTrue(failure.getMessage().contains("Git command failed"));
    }

    @Test
    void rejectsReplaceQueryRestoreIndexSubstitution() throws Exception {
        RepositoryFixture fixture = createTrackedSubmodule();
        Files.writeString(fixture.jcef().resolve("second.txt"), "second\n");
        git(fixture.jcef(), "add", "second.txt");
        git(fixture.jcef(), "commit", "-m", "second");
        String advancedHead = git(fixture.jcef(), "rev-parse", "HEAD").trim();
        Path forgedIndex = createIndexWithGitlink(fixture.root(), fixture.relativeJcefPath(), advancedHead, "forged-parent-index");
        AtomicBoolean substituted = new AtomicBoolean();
        AtomicReference<Path> privateGitDirectory = new AtomicReference<>();
        AtomicReference<String> queryOutput = new AtomicReference<>();
        JcefProvenance.GitCommandRunner gitRunner = substituteParentIndexDuringQuery(fixture.root(), fixture.relativeJcefPath(), forgedIndex, substituted, privateGitDirectory, queryOutput);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath(), gitRunner));

        assertTrue(substituted.get());
        assertTrue(queryOutput.get().contains(fixture.jcefCommit()));
        assertFalse(queryOutput.get().contains(advancedHead));
        assertTrue(failure.getMessage().contains("does not match the tracked gitlink") || failure.getMessage().contains("MCEF Git metadata changed"));
        assertTrue(privateGitDirectory.get() != null);
        assertFalse(Files.exists(privateGitDirectory.get()));
    }

    @Test
    void resolvesTrackedGitlinkFromSplitIndex() throws Exception {
        RepositoryFixture fixture = createTrackedSubmodule();
        git(fixture.root(), "update-index", "--split-index");

        JcefProvenance.Resolution resolution = JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath());

        assertEquals(fixture.jcefCommit(), resolution.commit());
        assertTrue(resolution.trackedSubmodule());
    }

    @Test
    void resolvesTrackedGitlinkFromSparseIndex() throws Exception {
        RepositoryFixture fixture = createDirectClone("sparse-index");
        Files.createDirectories(fixture.root().resolve("other/nested"));
        Files.writeString(fixture.root().resolve("other/nested/tracked.txt"), "tracked\n");
        git(fixture.root(), "add", fixture.relativeJcefPath(), "other/nested/tracked.txt");
        git(fixture.root(), "commit", "-m", "track sparse fixture");
        git(fixture.root(), "sparse-checkout", "init", "--cone", "--sparse-index");
        git(fixture.root(), "sparse-checkout", "set", "common");
        assertTrue(git(fixture.root(), "ls-files", "--sparse").contains("other/"));

        JcefProvenance.Resolution resolution = JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath());

        assertEquals(fixture.jcefCommit(), resolution.commit());
        assertTrue(resolution.trackedSubmodule());
    }

    @Test
    void rejectsDirectoryThatOnlyBelongsToParentRepository() throws Exception {
        Path root = temporaryDirectory.resolve("parent-only");
        Path jcef = root.resolve("common/java-cef");
        Files.createDirectories(jcef);
        Files.writeString(jcef.resolve("tracked.txt"), "tracked\n");
        initializeRepository(root);
        git(root, "add", ".");
        git(root, "commit", "-m", "parent");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(root, "common/java-cef"));

        assertTrue(failure.getMessage().contains("standalone Git worktree"));
    }

    @Test
    void removesRepositorySelectingGitEnvironmentWithoutRemovingTransportConfiguration() {
        Map<String, String> environment = new HashMap<>();
        environment.put("GIT_DIR", "/tmp/foreign.git");
        environment.put("GIT_WORK_TREE", "/tmp/foreign-worktree");
        environment.put("GIT_CONFIG_COUNT", "1");
        environment.put("GIT_CONFIG_KEY_0", "core.worktree");
        environment.put("GIT_CONFIG_VALUE_0", "/tmp/foreign-worktree");
        environment.put("GIT_SSH_COMMAND", "ssh -i test-key");
        environment.put("PATH", "/usr/bin");

        JcefProvenance.sanitizeGitEnvironment(environment);

        assertFalse(environment.containsKey("GIT_DIR"));
        assertFalse(environment.containsKey("GIT_WORK_TREE"));
        assertFalse(environment.containsKey("GIT_CONFIG_COUNT"));
        assertFalse(environment.containsKey("GIT_CONFIG_KEY_0"));
        assertFalse(environment.containsKey("GIT_CONFIG_VALUE_0"));
        assertEquals("ssh -i test-key", environment.get("GIT_SSH_COMMAND"));
        assertEquals("/usr/bin", environment.get("PATH"));
    }

    private void assertDirtyRejected(RepositoryFixture fixture) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()));
        assertTrue(failure.getMessage().contains("artifact input") || failure.getMessage().contains("Java source root"), failure.getMessage());
    }

    private void assertIgnoredJavaInputRejected(RepositoryFixture fixture) throws Exception {
        createIgnoredJavaInput(fixture);
        assertEquals("java/ignored/Ignored.java", git(fixture.jcef(), "check-ignore", "java/ignored/Ignored.java").trim());
        assertDirtyRejected(fixture);
    }

    private static Path createIgnoredJavaInput(RepositoryFixture fixture) throws IOException {
        Path ignoredSource = fixture.jcef().resolve("java/ignored/Ignored.java");
        Files.createDirectories(ignoredSource.getParent());
        Files.writeString(ignoredSource, "final class Ignored {}\n");
        return ignoredSource;
    }

    private void assertParentIndexRejected(RepositoryFixture fixture) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JcefProvenance.resolve(fixture.root(), fixture.relativeJcefPath()));
        assertTrue(failure.getMessage().contains("MCEF Git index"));
    }

    private RepositoryFixture createTrackedSubmodule() throws Exception {
        RepositoryFixture fixture = createDirectClone("tracked-submodule");
        git(fixture.root(), "add", fixture.relativeJcefPath());
        git(fixture.root(), "commit", "-m", "track java-cef gitlink");
        return fixture;
    }

    private RepositoryFixture createDirectClone() throws Exception {
        return createDirectClone("direct-clone");
    }

    private RepositoryFixture createDirectClone(String name) throws Exception {
        Path root = temporaryDirectory.resolve(name);
        Path jcef = root.resolve("common/java-cef");
        initializeRepositoryWithCommit(root);
        String jcefCommit = initializeJcefRepository(root);
        return new RepositoryFixture(root, jcef, "common/java-cef", jcefCommit);
    }

    private static String initializeJcefRepository(Path root) throws Exception {
        Path jcef = root.resolve("common/java-cef");
        initializeRepository(jcef);
        createArtifactInputs(jcef);
        Files.writeString(jcef.resolve("tracked.txt"), "tracked\n");
        git(jcef, "add", ".");
        git(jcef, "commit", "-m", "java-cef");
        return git(jcef, "rev-parse", "HEAD").trim();
    }

    private static void createArtifactInputs(Path jcef) throws IOException {
        Files.createDirectories(jcef.resolve("java/org/cef"));
        Files.createDirectories(jcef.resolve("third_party/jogamp/jar"));
        Files.writeString(jcef.resolve("java/org/cef/Source.java"), "final class Source {}\n");
        Files.write(jcef.resolve("third_party/jogamp/jar/gluegen-rt.jar"), new byte[]{1, 2, 3});
        Files.write(jcef.resolve("third_party/jogamp/jar/jogl-all.jar"), new byte[]{4, 5, 6});
    }

    private static Path javaSource(RepositoryFixture fixture) {
        return fixture.jcef().resolve("java/org/cef/Source.java");
    }

    private static void initializeRepositoryWithCommit(Path repository) throws Exception {
        initializeRepository(repository);
        Files.writeString(repository.resolve("root.txt"), "root\n");
        git(repository, "add", "root.txt");
        git(repository, "commit", "-m", "root");
    }

    private static void initializeRepository(Path repository) throws Exception {
        Files.createDirectories(repository);
        git(repository, "init", "--quiet");
        git(repository, "config", "user.name", "MCEF Tests");
        git(repository, "config", "user.email", "mcef-tests@example.invalid");
    }

    private static String git(Path directory, String... arguments) throws Exception {
        return git(directory, Map.of(), arguments);
    }

    private static String git(Path directory, Map<String, String> environment, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Git command failed: " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    private static Path gitPath(Path repository, String name) throws Exception {
        Path path = Path.of(git(repository, "rev-parse", "--git-path", name).trim());
        return path.isAbsolute() ? path.normalize() : repository.resolve(path).toAbsolutePath().normalize();
    }

    private Path createIndexWithGitlink(Path root, String repositoryPath, String commit, String name) throws Exception {
        Path forgedIndex = temporaryDirectory.resolve(name);
        Files.copy(gitPath(root, "index"), forgedIndex);
        JcefProvenance.runGit(root, List.of("update-index", "--add", "--cacheinfo", "160000," + commit + "," + repositoryPath), true, Map.of("GIT_INDEX_FILE", forgedIndex.toString()));
        return forgedIndex;
    }

    private static JcefProvenance.GitCommandRunner substituteParentIndexDuringQuery(Path root, String repositoryPath, Path forgedIndex, AtomicBoolean substituted, AtomicReference<Path> privateGitDirectory, AtomicReference<String> queryOutput) throws Exception {
        Path realRoot = root.toRealPath();
        Path liveIndex = gitPath(root, "index");
        Path heldIndex = liveIndex.resolveSibling("index.provenance-original");
        return (directory, arguments, failOnError, environmentOverrides) -> {
            try {
                if (!directory.toRealPath().equals(realRoot) || !arguments.equals(List.of("ls-files", "--stage", "-z", "--", repositoryPath)) || !substituted.compareAndSet(false, true)) {
                    return JcefProvenance.runGit(directory, arguments, failOnError, environmentOverrides);
                }
                privateGitDirectory.set(Path.of(environmentOverrides.get("GIT_DIR")));

                Files.move(liveIndex, heldIndex);
                try {
                    Files.copy(forgedIndex, liveIndex);
                    try {
                        JcefProvenance.GitResult result = JcefProvenance.runGit(directory, arguments, failOnError, environmentOverrides);
                        queryOutput.set(result.output());
                        return result;
                    } finally {
                        Files.deleteIfExists(liveIndex);
                    }
                } finally {
                    Files.move(heldIndex, liveIndex, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to substitute the parent index during the test query.", e);
            }
        };
    }

    private static JcefProvenance.GitCommandRunner mutateBeforeGitCommand(Path expectedDirectory, List<String> expectedArguments, IoAction mutation, AtomicBoolean mutated) throws Exception {
        Path realExpectedDirectory = expectedDirectory.toRealPath();
        return (directory, arguments, failOnError, environmentOverrides) -> {
            try {
                if (directory.toRealPath().equals(realExpectedDirectory) && arguments.equals(expectedArguments) && mutated.compareAndSet(false, true)) {
                    mutation.run();
                }
                return JcefProvenance.runGit(directory, arguments, failOnError, environmentOverrides);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to mutate parent Git metadata during the test query.", e);
            }
        };
    }

    private static JcefProvenance.GitCommandRunner mutateAfterGitCommand(Path expectedDirectory, List<String> expectedArguments, IoAction mutation, AtomicBoolean mutated) throws Exception {
        Path realExpectedDirectory = expectedDirectory.toRealPath();
        return (directory, arguments, failOnError, environmentOverrides) -> {
            JcefProvenance.GitResult result = JcefProvenance.runGit(directory, arguments, failOnError, environmentOverrides);
            try {
                if (directory.toRealPath().equals(realExpectedDirectory) && arguments.equals(expectedArguments) && mutated.compareAndSet(false, true)) {
                    mutation.run();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to mutate parent Git metadata during the test query.", e);
            }
            return result;
        };
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private record RepositoryFixture(Path root, Path jcef, String relativeJcefPath, String jcefCommit) {
    }
}
