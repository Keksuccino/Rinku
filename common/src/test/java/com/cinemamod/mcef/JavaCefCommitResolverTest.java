package com.cinemamod.mcef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCefCommitResolverTest {
    private static final String COMMIT_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA256_COMMIT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String UPPERCASE_COMMIT = "ABCDEF0123456789ABCDEF0123456789ABCDEF01";
    private static final List<String> SOURCE_ROOT_QUERY = List.of("rev-parse", "--show-toplevel");
    private static final List<String> JAVA_CEF_HEAD_QUERY = List.of("rev-parse", "--verify", "HEAD^{commit}");

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesCommitOnlyFromOwnCodeSourceManifest() throws Exception {
        URL ownSource = createJar("own.jar", "java-cef-commit", COMMIT_A);

        try (URLClassLoader ownClassLoader = new URLClassLoader(new URL[]{ownSource}, null)) {
            Class<?> definingClass = Class.forName(JavaCefCommitResolverCodeSourceFixture.class.getName(), true, ownClassLoader);
            assertEquals(COMMIT_A, JavaCefCommitResolver.resolve(definingClass, null));
        }
    }

    @Test
    void ignoresForeignClasspathManifest() throws Exception {
        URL ownSource = createJar("own-without-commit.jar", null, null);
        URL foreignSource = createJar("foreign.jar", "java-cef-commit", COMMIT_A);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();

        try (URLClassLoader ownClassLoader = new URLClassLoader(new URL[]{ownSource}, null); URLClassLoader foreignClassLoader = new URLClassLoader(new URL[]{foreignSource}, previous)) {
            Class<?> definingClass = Class.forName(JavaCefCommitResolverCodeSourceFixture.class.getName(), true, ownClassLoader);
            Thread.currentThread().setContextClassLoader(foreignClassLoader);
            IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolve(definingClass, null));
            assertTrue(failure.getMessage().contains("MCEF code source"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void returnsNoCommitForMissingManifestAttribute() throws Exception {
        URL source = createJar("missing.jar", null, null);

        assertNull(JavaCefCommitResolver.resolveManifestCommit(source));
        assertThrows(IOException.class, () -> JavaCefCommitResolver.resolve(source, null));
    }

    @Test
    void rejectsInvalidManifestAttribute() throws Exception {
        URL source = createJar("invalid.jar", "java-cef-commit", "+" + COMMIT_A);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveManifestCommit(source));

        assertTrue(failure.getMessage().contains("exactly one 40- or 64-character hexadecimal commit"));
    }

    @Test
    void usesCaseInsensitiveManifestAttributeSemantics() throws Exception {
        URL source = createJar("mixed-case.jar", "JaVa-CeF-CoMmIt", UPPERCASE_COMMIT);

        assertEquals(UPPERCASE_COMMIT.toLowerCase(java.util.Locale.ROOT), JavaCefCommitResolver.resolveManifestCommit(source));
    }

    @Test
    void acceptsSha256ManifestCommit() throws Exception {
        URL source = createJar("sha256.jar", "java-cef-commit", SHA256_COMMIT);

        assertEquals(SHA256_COMMIT, JavaCefCommitResolver.resolveManifestCommit(source));
    }

    @Test
    void rejectsInvalidConfiguredCommitInsteadOfFallingThrough() throws Exception {
        URL source = createJar("valid.jar", "java-cef-commit", COMMIT_A);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolve(source, "not-a-commit"));

        assertTrue(failure.getMessage().contains("mcef.java.cef.commit"));
    }

    @Test
    void resolvesCleanDirectCloneHeadFromDevelopmentCodeSource() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("direct-clone");

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void resolvesDirectCloneWhenMcefSourceMetadataIsUnavailable() throws Exception {
        DevelopmentFixture fixture = createSourceArchiveFixture("source-archive");

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void rejectsParentGitMetadataDisappearanceAfterGitSourceDiscovery() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("metadata-disappears-after-discovery");
        git(fixture.sourceRoot(), "add", "common/java-cef");
        git(fixture.sourceRoot(), "commit", "-m", "track java-cef");
        Files.writeString(fixture.javaCefRepository().resolve("second.txt"), "second\n");
        git(fixture.javaCefRepository(), "add", "second.txt");
        git(fixture.javaCefRepository(), "commit", "-m", "second");
        Path removedMetadata = fixture.sourceRoot().resolve(".git.removed");
        AtomicBoolean removed = new AtomicBoolean();
        JavaCefCommitResolver.GitCommandRunner gitRunner = mutateBeforeGitCommand(fixture.javaCefRepository(), SOURCE_ROOT_QUERY, () -> Files.move(fixture.sourceRoot().resolve(".git"), removedMetadata), removed);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory(), gitRunner));

        assertTrue(removed.get(), failure.getMessage());
        assertTrue(failure.getMessage().contains("MCEF Git metadata changed while resolving java-cef provenance"));
    }

    @Test
    void rejectsParentGitMetadataDisappearanceDuringLayoutSourceDiscovery() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("metadata-disappears-during-discovery");
        Path removedMetadata = fixture.sourceRoot().resolve(".git.removed");
        AtomicBoolean removed = new AtomicBoolean();
        JavaCefCommitResolver.GitCommandRunner gitRunner = mutateBeforeGitCommand(fixture.classesDirectory(), SOURCE_ROOT_QUERY, () -> Files.move(fixture.sourceRoot().resolve(".git"), removedMetadata), removed);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory(), gitRunner));

        assertTrue(removed.get());
        assertTrue(failure.getMessage().contains("MCEF Git metadata changed while discovering the MCEF source root"));
    }

    @Test
    void rejectsParentGitMetadataAppearanceDuringLayoutSourceDiscovery() throws Exception {
        DevelopmentFixture fixture = createSourceArchiveFixture("metadata-appears-during-discovery");
        AtomicBoolean appeared = new AtomicBoolean();
        JavaCefCommitResolver.GitCommandRunner gitRunner = mutateAfterGitCommand(fixture.classesDirectory(), SOURCE_ROOT_QUERY, () -> Files.createDirectory(fixture.sourceRoot().resolve(".git")), appeared);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory(), gitRunner));

        assertTrue(appeared.get());
        assertTrue(failure.getMessage().contains("MCEF Git metadata changed while discovering the MCEF source root"));
    }

    @Test
    void rejectsParentGitMetadataAppearanceAfterLayoutSourceDiscovery() throws Exception {
        DevelopmentFixture fixture = createSourceArchiveFixture("metadata-appears-after-discovery");
        AtomicBoolean appeared = new AtomicBoolean();
        JavaCefCommitResolver.GitCommandRunner gitRunner = mutateAfterGitCommand(fixture.javaCefRepository(), JAVA_CEF_HEAD_QUERY, () -> Files.createDirectory(fixture.sourceRoot().resolve(".git")), appeared);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory(), gitRunner));

        assertTrue(appeared.get());
        assertTrue(failure.getMessage().contains("MCEF Git metadata changed while resolving java-cef provenance"));
    }

    @Test
    void resolvesTrackedSubmoduleGitlinkFromDevelopmentCodeSource() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("tracked-submodule");
        git(fixture.sourceRoot(), "add", "common/java-cef");
        git(fixture.sourceRoot(), "commit", "-m", "track java-cef");

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void rejectsMissingParentIndexInsteadOfBypassingTrackedGitlink() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("missing-parent-index");
        git(fixture.sourceRoot(), "add", "common/java-cef");
        git(fixture.sourceRoot(), "commit", "-m", "track java-cef");
        Files.writeString(fixture.javaCefRepository().resolve("second.txt"), "second\n");
        git(fixture.javaCefRepository(), "add", "second.txt");
        git(fixture.javaCefRepository(), "commit", "-m", "second");
        Files.delete(gitPath(fixture.sourceRoot(), "index"));

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));

        assertTrue(failure.getMessage().contains("MCEF Git index is unavailable"));
    }

    @Test
    void rejectsUnreadableParentIndex() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("unreadable-parent-index");
        Path index = gitPath(fixture.sourceRoot(), "index");
        assumeTrue(Files.getFileStore(index).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(index);
        try {
            Files.setPosixFilePermissions(index, Set.of());
            assumeFalse(Files.isReadable(index));

            IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));

            assertTrue(failure.getMessage().contains("MCEF Git index is unreadable"));
        } finally {
            Files.setPosixFilePermissions(index, originalPermissions);
        }
    }

    @Test
    void rejectsCorruptParentIndex() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("corrupt-parent-index");
        Files.writeString(gitPath(fixture.sourceRoot(), "index"), "not a Git index\n");

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));

        assertTrue(failure.getMessage().contains("Git command failed while resolving java-cef provenance"));
    }

    @Test
    void rejectsParentIndexContentChangeDuringQuery() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("changing-parent-index");
        Path index = gitPath(fixture.sourceRoot(), "index");
        AtomicBoolean changed = new AtomicBoolean();
        JavaCefCommitResolver.GitCommandRunner gitRunner = mutateParentIndexAfterQuery(fixture.sourceRoot(), () -> Files.write(index, new byte[]{0}, StandardOpenOption.APPEND), changed);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory(), gitRunner));

        assertTrue(changed.get());
        assertTrue(failure.getMessage().contains("MCEF Git index changed while resolving common/java-cef"));
    }

    @Test
    void rejectsParentIndexReplacementDuringQuery() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("replaced-parent-index");
        Path index = gitPath(fixture.sourceRoot(), "index");
        Path replacement = index.resolveSibling("index.replacement");
        Files.copy(index, replacement);
        Files.setLastModifiedTime(replacement, FileTime.fromMillis(Files.getLastModifiedTime(index).toMillis() + 60_000L));
        AtomicBoolean replaced = new AtomicBoolean();
        JavaCefCommitResolver.GitCommandRunner gitRunner = mutateParentIndexAfterQuery(fixture.sourceRoot(), () -> Files.move(replacement, index, StandardCopyOption.REPLACE_EXISTING), replaced);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory(), gitRunner));

        assertTrue(replaced.get());
        assertTrue(failure.getMessage().contains("MCEF Git index changed while resolving common/java-cef"));
    }

    @Test
    void rejectsReplaceQueryRestoreIndexSubstitution() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("replace-query-restore-index");
        git(fixture.sourceRoot(), "add", "common/java-cef");
        git(fixture.sourceRoot(), "commit", "-m", "track java-cef");
        Files.writeString(fixture.javaCefRepository().resolve("second.txt"), "second\n");
        git(fixture.javaCefRepository(), "add", "second.txt");
        git(fixture.javaCefRepository(), "commit", "-m", "second");
        String advancedHead = git(fixture.javaCefRepository(), "rev-parse", "HEAD").trim();
        Path forgedIndex = createIndexWithGitlink(fixture.sourceRoot(), advancedHead, "forged-parent-index");
        AtomicBoolean substituted = new AtomicBoolean();
        AtomicReference<Path> privateGitDirectory = new AtomicReference<>();
        AtomicReference<String> queryOutput = new AtomicReference<>();
        JavaCefCommitResolver.GitCommandRunner gitRunner = substituteParentIndexDuringQuery(fixture.sourceRoot(), forgedIndex, substituted, privateGitDirectory, queryOutput);

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory(), gitRunner));

        assertTrue(substituted.get());
        assertTrue(queryOutput.get().contains(fixture.commit()));
        assertFalse(queryOutput.get().contains(advancedHead));
        assertTrue(failure.getMessage().contains("does not match the tracked gitlink") || failure.getMessage().contains("MCEF Git metadata changed"));
        assertTrue(privateGitDirectory.get() != null);
        assertTrue(Files.notExists(privateGitDirectory.get()));
    }

    @Test
    void resolvesTrackedGitlinkFromSplitIndex() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("split-index");
        git(fixture.sourceRoot(), "add", "common/java-cef");
        git(fixture.sourceRoot(), "commit", "-m", "track java-cef");
        git(fixture.sourceRoot(), "update-index", "--split-index");

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void resolvesTrackedGitlinkFromSparseIndex() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("sparse-index");
        Files.createDirectories(fixture.sourceRoot().resolve("other/nested"));
        Files.writeString(fixture.sourceRoot().resolve("other/nested/tracked.txt"), "tracked\n");
        git(fixture.sourceRoot(), "add", "common/java-cef", "other/nested/tracked.txt");
        git(fixture.sourceRoot(), "commit", "-m", "track sparse fixture");
        git(fixture.sourceRoot(), "sparse-checkout", "init", "--cone", "--sparse-index");
        git(fixture.sourceRoot(), "sparse-checkout", "set", "common");
        assertTrue(git(fixture.sourceRoot(), "ls-files", "--sparse").contains("other/"));

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void supportsLinkedWorktreeWithDetachedHead() throws Exception {
        Path sourceRoot = createSourceRoot("linked-worktree");
        Path origin = temporaryDirectory.resolve("java-cef-origin");
        initializeRepository(origin);
        createArtifactInputs(origin);
        git(origin, "add", ".");
        git(origin, "commit", "-m", "java-cef");
        String commit = git(origin, "rev-parse", "HEAD").trim();
        Path javaCefWorktree = sourceRoot.resolve("common/java-cef");
        Files.createDirectories(javaCefWorktree.getParent());
        git(origin, "worktree", "add", "--detach", javaCefWorktree.toString(), commit);

        assertEquals(commit, JavaCefCommitResolver.resolveDevelopmentCommit(classesDirectory(sourceRoot)));
    }

    @Test
    void rejectsDirtyDevelopmentSource() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("dirty");
        Files.writeString(fixture.javaCefRepository().resolve("java/org/cef/Untracked.java"), "final class Untracked {}\n");

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));

        assertTrue(failure.getMessage().contains("artifact input"));
    }

    @Test
    void rejectsAssumeUnchangedDevelopmentSourceWithPreservedStatData() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("assume-unchanged");
        Path source = javaSource(fixture);
        FileTime modifiedTime = Files.getLastModifiedTime(source);
        git(fixture.javaCefRepository(), "update-index", "--assume-unchanged", "java/org/cef/Source.java");
        Files.writeString(source, "final class Sourcf {}\n");
        Files.setLastModifiedTime(source, modifiedTime);

        assertTrue(git(fixture.javaCefRepository(), "status", "--porcelain=v1").isEmpty());
        assertDevelopmentInputRejected(fixture);
    }

    @Test
    void rejectsSkipWorktreeDevelopmentSourceModification() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("skip-worktree");
        git(fixture.javaCefRepository(), "update-index", "--skip-worktree", "java/org/cef/Source.java");
        Files.writeString(javaSource(fixture), "final class Sourcf {}\n");

        assertTrue(git(fixture.javaCefRepository(), "status", "--porcelain=v1").isEmpty());
        assertDevelopmentInputRejected(fixture);
    }

    @Test
    void rejectsDevelopmentJavaInputIgnoredByGitignore() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("gitignore-input");
        Files.writeString(fixture.javaCefRepository().resolve(".gitignore"), "/java/ignored/\n");

        assertIgnoredJavaInputRejected(fixture);
    }

    @Test
    void rejectsDevelopmentJavaInputIgnoredByInfoExclude() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("info-exclude-input");
        Files.writeString(gitPath(fixture.javaCefRepository(), "info/exclude"), "/java/ignored/\n");

        assertIgnoredJavaInputRejected(fixture);
    }

    @Test
    void rejectsDevelopmentJavaInputIgnoredByConfiguredGlobalExcludeFile() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("global-exclude-input");
        Path excludesFile = temporaryDirectory.resolve("runtime-global-excludes");
        Files.writeString(excludesFile, "/java/ignored/\n");
        Path globalConfig = temporaryDirectory.resolve("runtime-global.gitconfig");
        Files.writeString(globalConfig, "[core]\n\texcludesFile = " + excludesFile.toString().replace('\\', '/') + "\n");
        Path ignoredSource = createIgnoredJavaInput(fixture);

        assertEquals("java/ignored/Ignored.java", git(fixture.javaCefRepository(), Map.of("GIT_CONFIG_GLOBAL", globalConfig.toString()), "check-ignore", "java/ignored/Ignored.java").trim());
        assertTrue(Files.isRegularFile(ignoredSource));
        assertDevelopmentInputRejected(fixture);
    }

    @Test
    void acceptsIgnoredDevelopmentBuildOutputsOutsideArtifactInputs() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("ignored-build-output");
        Files.writeString(fixture.javaCefRepository().resolve(".gitignore"), "/jcef_build/\n/binary_distrib/\n");
        Files.createDirectories(fixture.javaCefRepository().resolve("jcef_build/native/Release"));
        Files.writeString(fixture.javaCefRepository().resolve("jcef_build/native/Release/generated.bin"), "generated\n");
        Files.createDirectories(fixture.javaCefRepository().resolve("binary_distrib/linux_amd64"));
        Files.writeString(fixture.javaCefRepository().resolve("binary_distrib/linux_amd64/downloaded.bin"), "downloaded\n");

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void acceptsCrLfDevelopmentCheckoutForLfJavaBlob() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("crlf-source");
        Files.writeString(javaSource(fixture), "final class Source {}\r\n");

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void rejectsDevelopmentSourceChangeHiddenByRepositoryCleanFilter() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("clean-filter");
        Files.writeString(fixture.javaCefRepository().resolve(".gitattributes"), "java/**/*.java filter=provenance-hide\n");
        git(fixture.javaCefRepository(), "add", ".gitattributes");
        git(fixture.javaCefRepository(), "commit", "-m", "configure source filter");
        git(fixture.javaCefRepository(), "config", "filter.provenance-hide.clean", "git show HEAD:%f");
        git(fixture.javaCefRepository(), "config", "filter.provenance-hide.required", "true");
        Files.writeString(javaSource(fixture), "final class Sourcf {}\n");

        assertFalse(git(fixture.javaCefRepository(), "status", "--porcelain=v1").contains("Source.java"));
        assertDevelopmentInputRejected(fixture);
    }

    @Test
    void boundsIgnoredDevelopmentNonInputFloodInsideJavaTree() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("bounded-java-walk");
        Path generated = fixture.javaCefRepository().resolve("java/generated");
        Files.createDirectories(generated);
        for (int index = 0; index < 400; index++) {
            Files.writeString(generated.resolve("generated-" + index + ".class"), "generated\n");
        }

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));

        assertTrue(failure.getMessage().contains("bounded provenance traversal limit"));
    }

    @Test
    void resolvesSha256DevelopmentHead() throws Exception {
        Path sourceRoot = createSourceRoot("sha256-head");
        Path javaCefRepository = sourceRoot.resolve("common/java-cef");
        Files.createDirectories(javaCefRepository);
        git(javaCefRepository, "init", "--quiet", "--object-format=sha256");
        git(javaCefRepository, "config", "user.name", "MCEF Tests");
        git(javaCefRepository, "config", "user.email", "mcef-tests@example.invalid");
        createArtifactInputs(javaCefRepository);
        git(javaCefRepository, "add", ".");
        git(javaCefRepository, "commit", "-m", "sha256 java-cef");
        String commit = git(javaCefRepository, "rev-parse", "HEAD").trim();

        assertEquals(64, commit.length());
        assertEquals(commit, JavaCefCommitResolver.resolveDevelopmentCommit(classesDirectory(sourceRoot)));
    }

    @Test
    void resolvesDevelopmentCommitWithSplitJavaCefIndex() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("split-jcef-index");
        git(fixture.javaCefRepository(), "update-index", "--split-index");

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void resolvesSparseJavaCefDevelopmentCheckoutWhenEveryArtifactInputIsPresent() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("sparse-jcef-checkout");
        git(fixture.javaCefRepository(), "sparse-checkout", "init", "--cone", "--sparse-index");
        git(fixture.javaCefRepository(), "sparse-checkout", "set", "java", "third_party");

        assertEquals(fixture.commit(), JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
    }

    @Test
    void rejectsSparseJavaCefDevelopmentCheckoutMissingArtifactInputs() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("sparse-jcef-missing");
        git(fixture.javaCefRepository(), "sparse-checkout", "init", "--cone", "--sparse-index");
        git(fixture.javaCefRepository(), "sparse-checkout", "set", "third_party");

        assertDevelopmentInputRejected(fixture);
    }

    @Test
    void rejectsDevelopmentArtifactInputRaceBetweenFinalHeadChecks() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("artifact-race");
        AtomicInteger headQueries = new AtomicInteger();
        AtomicBoolean mutated = new AtomicBoolean();
        JavaCefCommitResolver.GitCommandRunner gitRunner = (directory, arguments, failOnError, environmentOverrides) -> {
            JavaCefCommitResolver.GitResult result = JavaCefCommitResolver.runGit(directory, arguments, failOnError, environmentOverrides);
            if (directory.toRealPath().equals(fixture.javaCefRepository().toRealPath()) && arguments.equals(JAVA_CEF_HEAD_QUERY) && headQueries.incrementAndGet() == 2) {
                Files.writeString(javaSource(fixture), "final class Sourcf {}\n");
                mutated.set(true);
            }
            return result;
        };

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory(), gitRunner));

        assertTrue(mutated.get());
        assertTrue(failure.getMessage().contains("changed while commit provenance"));
    }

    @Test
    void rejectsDevelopmentHeadThatDiffersFromTrackedGitlink() throws Exception {
        DevelopmentFixture fixture = createDirectCloneFixture("mismatch");
        git(fixture.sourceRoot(), "add", "common/java-cef");
        git(fixture.sourceRoot(), "commit", "-m", "track java-cef");
        Files.writeString(fixture.javaCefRepository().resolve("second.txt"), "second\n");
        git(fixture.javaCefRepository(), "add", "second.txt");
        git(fixture.javaCefRepository(), "commit", "-m", "second");

        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));

        assertTrue(failure.getMessage().contains("does not match the tracked gitlink"));
    }

    private void assertDevelopmentInputRejected(DevelopmentFixture fixture) {
        IOException failure = assertThrows(IOException.class, () -> JavaCefCommitResolver.resolveDevelopmentCommit(fixture.classesDirectory()));
        assertTrue(failure.getMessage().contains("artifact input") || failure.getMessage().contains("Java source root"), failure.getMessage());
    }

    private void assertIgnoredJavaInputRejected(DevelopmentFixture fixture) throws Exception {
        createIgnoredJavaInput(fixture);
        assertEquals("java/ignored/Ignored.java", git(fixture.javaCefRepository(), "check-ignore", "java/ignored/Ignored.java").trim());
        assertDevelopmentInputRejected(fixture);
    }

    private static Path createIgnoredJavaInput(DevelopmentFixture fixture) throws IOException {
        Path ignoredSource = fixture.javaCefRepository().resolve("java/ignored/Ignored.java");
        Files.createDirectories(ignoredSource.getParent());
        Files.writeString(ignoredSource, "final class Ignored {}\n");
        return ignoredSource;
    }

    private static Path javaSource(DevelopmentFixture fixture) {
        return fixture.javaCefRepository().resolve("java/org/cef/Source.java");
    }

    private URL createJar(String fileName, String attributeName, String attributeValue) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (attributeName != null) {
            manifest.getMainAttributes().put(new Attributes.Name(attributeName), attributeValue);
        }
        Path jar = temporaryDirectory.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            String fixtureEntry = JavaCefCommitResolverCodeSourceFixture.class.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(fixtureEntry));
            try (var input = JavaCefCommitResolverCodeSourceFixture.class.getResourceAsStream("/" + fixtureEntry)) {
                if (input == null) {
                    throw new IOException("Missing compiled code-source fixture " + fixtureEntry);
                }
                input.transferTo(output);
            }
            output.closeEntry();
        }
        return jar.toUri().toURL();
    }

    private DevelopmentFixture createDirectCloneFixture(String name) throws Exception {
        Path sourceRoot = createSourceRoot(name);
        return createJavaCefFixture(sourceRoot);
    }

    private DevelopmentFixture createSourceArchiveFixture(String name) throws Exception {
        Path sourceRoot = temporaryDirectory.resolve(name);
        Files.createDirectories(classesDirectory(sourceRoot));
        Files.writeString(sourceRoot.resolve("build.gradle"), "// source archive fixture\n");
        return createJavaCefFixture(sourceRoot);
    }

    private DevelopmentFixture createJavaCefFixture(Path sourceRoot) throws Exception {
        Path javaCefRepository = sourceRoot.resolve("common/java-cef");
        initializeRepository(javaCefRepository);
        createArtifactInputs(javaCefRepository);
        Files.writeString(javaCefRepository.resolve("tracked.txt"), "tracked\n");
        git(javaCefRepository, "add", ".");
        git(javaCefRepository, "commit", "-m", "java-cef");
        String commit = git(javaCefRepository, "rev-parse", "HEAD").trim();
        return new DevelopmentFixture(sourceRoot, classesDirectory(sourceRoot), javaCefRepository, commit);
    }

    private static void createArtifactInputs(Path javaCefRepository) throws IOException {
        Files.createDirectories(javaCefRepository.resolve("java/org/cef"));
        Files.createDirectories(javaCefRepository.resolve("third_party/jogamp/jar"));
        Files.writeString(javaCefRepository.resolve("java/org/cef/Source.java"), "final class Source {}\n");
        Files.write(javaCefRepository.resolve("third_party/jogamp/jar/gluegen-rt.jar"), new byte[]{1, 2, 3});
        Files.write(javaCefRepository.resolve("third_party/jogamp/jar/jogl-all.jar"), new byte[]{4, 5, 6});
    }

    private Path createSourceRoot(String name) throws Exception {
        Path sourceRoot = temporaryDirectory.resolve(name);
        initializeRepository(sourceRoot);
        Path classesDirectory = classesDirectory(sourceRoot);
        Files.createDirectories(classesDirectory);
        Files.writeString(sourceRoot.resolve("build.gradle"), "// fixture\n");
        git(sourceRoot, "add", "build.gradle");
        git(sourceRoot, "commit", "-m", "MCEF source");
        return sourceRoot;
    }

    private static Path classesDirectory(Path sourceRoot) {
        return sourceRoot.resolve("common/build/classes/java/main");
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
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

    private static JavaCefCommitResolver.GitCommandRunner mutateParentIndexAfterQuery(Path sourceRoot, IoAction mutation, AtomicBoolean mutated) throws IOException {
        Path realSourceRoot = sourceRoot.toRealPath();
        return (directory, arguments, failOnError, environmentOverrides) -> {
            JavaCefCommitResolver.GitResult result = JavaCefCommitResolver.runGit(directory, arguments, failOnError, environmentOverrides);
            if (directory.toRealPath().equals(realSourceRoot) && arguments.equals(List.of("ls-files", "--stage", "-z", "--", "common/java-cef")) && mutated.compareAndSet(false, true)) {
                mutation.run();
            }
            return result;
        };
    }

    private static JavaCefCommitResolver.GitCommandRunner mutateBeforeGitCommand(Path expectedDirectory, List<String> expectedArguments, IoAction mutation, AtomicBoolean mutated) throws IOException {
        Path realExpectedDirectory = expectedDirectory.toRealPath();
        return (directory, arguments, failOnError, environmentOverrides) -> {
            if (directory.toRealPath().equals(realExpectedDirectory) && arguments.equals(expectedArguments) && mutated.compareAndSet(false, true)) {
                mutation.run();
            }
            return JavaCefCommitResolver.runGit(directory, arguments, failOnError, environmentOverrides);
        };
    }

    private static JavaCefCommitResolver.GitCommandRunner mutateAfterGitCommand(Path expectedDirectory, List<String> expectedArguments, IoAction mutation, AtomicBoolean mutated) throws IOException {
        Path realExpectedDirectory = expectedDirectory.toRealPath();
        return (directory, arguments, failOnError, environmentOverrides) -> {
            JavaCefCommitResolver.GitResult result = JavaCefCommitResolver.runGit(directory, arguments, failOnError, environmentOverrides);
            if (directory.toRealPath().equals(realExpectedDirectory) && arguments.equals(expectedArguments) && mutated.compareAndSet(false, true)) {
                mutation.run();
            }
            return result;
        };
    }

    private Path createIndexWithGitlink(Path sourceRoot, String commit, String name) throws Exception {
        Path forgedIndex = temporaryDirectory.resolve(name);
        Files.copy(gitPath(sourceRoot, "index"), forgedIndex);
        JavaCefCommitResolver.runGit(sourceRoot, List.of("update-index", "--add", "--cacheinfo", "160000," + commit + ",common/java-cef"), true, Map.of("GIT_INDEX_FILE", forgedIndex.toString()));
        return forgedIndex;
    }

    private static JavaCefCommitResolver.GitCommandRunner substituteParentIndexDuringQuery(Path sourceRoot, Path forgedIndex, AtomicBoolean substituted, AtomicReference<Path> privateGitDirectory, AtomicReference<String> queryOutput) throws Exception {
        Path realSourceRoot = sourceRoot.toRealPath();
        Path liveIndex = gitPath(sourceRoot, "index");
        Path heldIndex = liveIndex.resolveSibling("index.provenance-original");
        return (directory, arguments, failOnError, environmentOverrides) -> {
            if (!directory.toRealPath().equals(realSourceRoot) || !arguments.equals(List.of("ls-files", "--stage", "-z", "--", "common/java-cef")) || !substituted.compareAndSet(false, true)) {
                return JavaCefCommitResolver.runGit(directory, arguments, failOnError, environmentOverrides);
            }
            privateGitDirectory.set(Path.of(environmentOverrides.get("GIT_DIR")));

            Files.move(liveIndex, heldIndex);
            try {
                Files.copy(forgedIndex, liveIndex);
                try {
                    JavaCefCommitResolver.GitResult result = JavaCefCommitResolver.runGit(directory, arguments, failOnError, environmentOverrides);
                    queryOutput.set(result.output());
                    return result;
                } finally {
                    Files.deleteIfExists(liveIndex);
                }
            } finally {
                Files.move(heldIndex, liveIndex, StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }

    private record DevelopmentFixture(Path sourceRoot, Path classesDirectory, Path javaCefRepository, String commit) {
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}

final class JavaCefCommitResolverCodeSourceFixture {
}
