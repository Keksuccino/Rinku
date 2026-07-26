/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package com.cinemamod.mcef;

import com.cinemamod.mcef.MCEFDistributionManifest.ManifestData;
import com.cinemamod.mcef.MCEFDistributionManifest.ManifestFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds one stable, bounded snapshot of every publisher-owned distribution
 * entry.
 */
final class MCEFDistributionTreeValidator {
    private static final long MAX_DECLARED_DISTRIBUTION_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final int HASH_BUFFER_BYTES = 64 * 1024;
    private static final Pattern DIGEST_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private MCEFDistributionTreeValidator() {
    }

    static void requireSafeInstallationRoot(Path installation) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(installation, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IOException("Missing or unsafe JCEF installation directory: " + installation, failure);
        }
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Missing or unsafe JCEF installation directory: " + installation);
        }
    }

    static ValidatedTree validate(Path installation, ManifestData manifest, boolean published) throws IOException {
        requireSafeInstallationRoot(installation);
        DeclaredInventory declared = validateDeclaredInventory(manifest);
        InventoryVisitor visitor = new InventoryVisitor(installation, declared, published);
        Files.walkFileTree(installation, visitor);
        return visitor.finish();
    }

    private static DeclaredInventory validateDeclaredInventory(ManifestData manifest) throws IOException {
        List<String> declaredDirectories = manifest.distributionDirectories();
        List<ManifestFile> declaredFiles = manifest.distributionFiles();
        if (declaredDirectories.size() > MCEFDistributionManifest.MAX_DISTRIBUTION_DIRECTORIES || declaredFiles.isEmpty() || declaredFiles.size() > MCEFDistributionManifest.MAX_DISTRIBUTION_FILES) {
            throw new IOException("JCEF distribution inventory count is outside the supported limit");
        }
        validatePublisherInventoryLimits(manifest.archiveRoot(), declaredDirectories, declaredFiles);

        Set<String> directories = new LinkedHashSet<>();
        Map<String, ManifestFile> files = new LinkedHashMap<>();
        Map<String, String> foldedPaths = new HashMap<>();
        String previous = null;
        for (String directory : declaredDirectories) {
            MCEFRuntimeManifestValidator.validateRuntimePath(directory);
            rejectReservedPath(directory);
            requireSorted(previous, directory, "directories");
            registerFoldedPath(foldedPaths, directory, "distribution inventory");
            directories.add(directory);
            previous = directory;
        }

        long declaredBytes = 0L;
        previous = null;
        for (ManifestFile file : declaredFiles) {
            MCEFRuntimeManifestValidator.validateRuntimePath(file.path());
            rejectReservedPath(file.path());
            requireSorted(previous, file.path(), "files");
            registerFoldedPath(foldedPaths, file.path(), "distribution inventory");
            if (file.size() < 0L || file.size() > MAX_DECLARED_DISTRIBUTION_BYTES || !DIGEST_PATTERN.matcher(file.sha256()).matches()) {
                throw new IOException("Invalid JCEF distribution file metadata for " + file.path());
            }
            declaredBytes = addBounded(declaredBytes, file.size());
            files.put(file.path(), file);
            previous = file.path();
        }

        for (String directory : directories) {
            requireDeclaredParents(directory, directories);
        }
        for (String file : files.keySet()) {
            requireDeclaredParents(file, directories);
        }
        return new DeclaredInventory(Map.copyOf(files), Set.copyOf(directories), declaredBytes);
    }

    /**
     * Applies the publisher's archive-member and path budgets to the extracted
     * inventory.
     */
    static void validatePublisherInventoryLimits(String archiveRoot, List<String> directories, List<ManifestFile> files) throws IOException {
        long memberCount = 2L + directories.size() + files.size();
        if (memberCount > MCEFDistributionManifest.MAX_ARCHIVE_MEMBERS) {
            throw new IOException("JCEF distribution inventory exceeds the publisher archive-member limit");
        }

        long pathBytes = publisherPathBytes(archiveRoot);
        pathBytes = addPathBytes(pathBytes, publisherPathBytes(archiveRoot + "/" + MCEFDistributionManifest.FILE_NAME));
        for (String directory : directories) {
            pathBytes = addPathBytes(pathBytes, publisherPathBytes(archiveRoot + "/" + directory));
        }
        for (ManifestFile file : files) {
            pathBytes = addPathBytes(pathBytes, publisherPathBytes(archiveRoot + "/" + file.path()));
        }
    }

    private static long publisherPathBytes(String path) throws IOException {
        MCEFRuntimeManifestValidator.validateRuntimePath(path);
        return path.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long addPathBytes(long current, long additional) throws IOException {
        if (current > MCEFDistributionManifest.MAX_TOTAL_PATH_BYTES - additional) {
            throw new IOException("JCEF distribution member paths exceed the publisher aggregate byte limit");
        }
        return current + additional;
    }

    private static void requireSorted(String previous, String current, String description) throws IOException {
        if (previous != null && previous.compareTo(current) >= 0) {
            throw new IOException("JCEF distribution " + description + " must be unique and sorted by path");
        }
    }

    private static void requireDeclaredParents(String path, Set<String> directories) throws IOException {
        int separator = path.lastIndexOf('/');
        if (separator >= 0 && !directories.contains(path.substring(0, separator))) {
            throw new IOException("JCEF distribution inventory omits an explicit parent directory for " + path);
        }
    }

    private static void rejectReservedPath(String path) throws IOException {
        String firstComponent = path;
        int separator = path.indexOf('/');
        if (separator >= 0) {
            firstComponent = path.substring(0, separator);
        }
        if (firstComponent.equalsIgnoreCase(MCEFDistributionManifest.FILE_NAME) || firstComponent.equalsIgnoreCase(MCEFInstallationTransaction.GENERATION_STATE_FILE) || firstComponent.equalsIgnoreCase(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME)) {
            throw new IOException("JCEF distribution inventory collides with an installer-owned path: " + path);
        }
    }

    private static void registerFoldedPath(Map<String, String> foldedPaths, String path, String description) throws IOException {
        String previous = foldedPaths.putIfAbsent(MCEFRuntimeManifestValidator.caseFoldPath(path), path);
        if (previous != null) {
            throw new IOException("JCEF " + description + " contains a case-colliding path: " + path);
        }
    }

    private static long addBounded(long current, long additional) throws IOException {
        if (additional < 0L || current > MAX_DECLARED_DISTRIBUTION_BYTES - additional) {
            throw new IOException("JCEF distribution byte inventory exceeds the supported limit");
        }
        return current + additional;
    }

    private static String relativePosixPath(Path root, Path path) {
        Path relative = root.relativize(path);
        StringBuilder value = new StringBuilder();
        for (Path component : relative) {
            if (!value.isEmpty()) {
                value.append('/');
            }
            value.append(component);
        }
        return value.toString();
    }

    private static boolean sameFileSnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isRegularFile() && before.size() == after.size() && before.creationTime().equals(after.creationTime()) && before.lastModifiedTime().equals(after.lastModifiedTime()) && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static boolean sameDirectorySnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isDirectory() && before.creationTime().equals(after.creationTime()) && before.lastModifiedTime().equals(after.lastModifiedTime()) && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is not available", impossible);
        }
    }

    record HashedFile(long size, String sha256) {
    }

    record ValidatedTree(Map<String, HashedFile> files, Set<String> directories) {
        ValidatedTree {
            files = Map.copyOf(files);
            directories = Set.copyOf(directories);
        }
    }

    private record DeclaredInventory(Map<String, ManifestFile> files, Set<String> directories, long bytes) {}

    private static final class InventoryVisitor extends SimpleFileVisitor<Path> {
        private final Path installation;
        private final DeclaredInventory declared;
        private final boolean published;
        private final Map<String, HashedFile> actualFiles = new LinkedHashMap<>();
        private final Set<String> actualDirectories = new LinkedHashSet<>();
        private final Map<String, String> foldedActualPaths = new HashMap<>();
        private final Map<Path, BasicFileAttributes> directorySnapshots = new HashMap<>();
        private final Map<Path, BasicFileAttributes> validatedDirectorySnapshots = new HashMap<>();
        private final Map<Path, BasicFileAttributes> distributionFileSnapshots = new HashMap<>();
        private final Map<Path, BasicFileAttributes> installerFileSnapshots = new HashMap<>();
        private final ByteBuffer hashBuffer = ByteBuffer.allocateDirect(HASH_BUFFER_BYTES);
        private long hashedBytes;
        private int leaseTokenCount;
        private boolean foundManifest;
        private boolean foundGenerationState;
        private boolean foundLeaseDirectory;

        private InventoryVisitor(Path installation, DeclaredInventory declared, boolean published) {
            this.installation = installation;
            this.declared = declared;
            this.published = published;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new IOException("JCEF distribution contains an unsafe directory: " + relativePosixPath(installation, directory));
            }
            directorySnapshots.put(directory, attributes);
            if (directory.equals(installation)) {
                return FileVisitResult.CONTINUE;
            }

            String relativePath = checkedRelativePath(directory);
            registerActualPath(relativePath);
            if (relativePath.equals(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME)) {
                if (!published) {
                    throw new IOException("JCEF archive collided with the reserved generation lease directory");
                }
                foundLeaseDirectory = true;
                return FileVisitResult.CONTINUE;
            }
            if (relativePath.startsWith(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME + "/")) {
                throw new IOException("JCEF generation lease directory contains a nested directory");
            }
            if (!declared.directories().contains(relativePath)) {
                throw new IOException("JCEF distribution contains an undeclared directory: " + relativePath);
            }
            actualDirectories.add(relativePath);
            if (actualDirectories.size() > MCEFDistributionManifest.MAX_DISTRIBUTION_DIRECTORIES) {
                throw new IOException("JCEF distribution contains too many directories");
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            String relativePath = checkedRelativePath(file);
            registerActualPath(relativePath);
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw new IOException("JCEF distribution contains a symbolic link or special file: " + relativePath);
            }

            if (relativePath.equals(MCEFDistributionManifest.FILE_NAME)) {
                if (attributes.size() <= 0L) {
                    throw new IOException("JCEF distribution manifest is empty");
                }
                foundManifest = true;
                return FileVisitResult.CONTINUE;
            }
            if (relativePath.equals(MCEFInstallationTransaction.GENERATION_STATE_FILE)) {
                if (!published || attributes.size() <= 0L) {
                    throw new IOException("JCEF archive collided with the reserved generation state file");
                }
                foundGenerationState = true;
                installerFileSnapshots.put(file, attributes);
                return FileVisitResult.CONTINUE;
            }
            if (relativePath.startsWith(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME + "/")) {
                validateLeaseToken(file, relativePath, attributes);
                return FileVisitResult.CONTINUE;
            }

            ManifestFile expected = declared.files().get(relativePath);
            if (expected == null) {
                throw new IOException("JCEF distribution contains an undeclared file: " + relativePath);
            }
            HashedFile actual = hashFile(file, relativePath, attributes, expected);
            actualFiles.put(relativePath, actual);
            if (actualFiles.size() > MCEFDistributionManifest.MAX_DISTRIBUTION_FILES) {
                throw new IOException("JCEF distribution contains too many files");
            }
            hashedBytes = addBounded(hashedBytes, actual.size());
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
            if (failure != null) {
                throw failure;
            }
            BasicFileAttributes before = directorySnapshots.remove(directory);
            BasicFileAttributes after = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before == null || !sameDirectorySnapshot(before, after)) {
                throw new IOException("JCEF distribution directory changed during validation: " + relativePosixPath(installation, directory));
            }
            validatedDirectorySnapshots.put(directory, after);
            return FileVisitResult.CONTINUE;
        }

        private String checkedRelativePath(Path path) throws IOException {
            String relativePath = relativePosixPath(installation, path);
            MCEFRuntimeManifestValidator.validateRuntimePath(relativePath);
            return relativePath;
        }

        private void registerActualPath(String relativePath) throws IOException {
            registerFoldedPath(foldedActualPaths, relativePath, "filesystem tree");
        }

        private void validateLeaseToken(Path file, String relativePath, BasicFileAttributes attributes) throws IOException {
            if (!published || !foundLeaseDirectory || file.getParent() == null || !file.getParent().equals(installation.resolve(MCEFGenerationLeaseRegistry.LEASE_DIRECTORY_NAME))) {
                throw new IOException("JCEF archive collided with the reserved generation lease directory");
            }
            String tokenName = file.getFileName().toString();
            leaseTokenCount++;
            if (leaseTokenCount > MCEFGenerationLeaseRegistry.MAX_LEASE_TOKENS || !MCEFGenerationLeaseRegistry.isRecognizedTokenName(tokenName) || attributes.size() != 0L) {
                throw new IOException("JCEF generation contains an invalid lease token: " + relativePath);
            }
            installerFileSnapshots.put(file, attributes);
        }

        private HashedFile hashFile(Path file, String relativePath, BasicFileAttributes before, ManifestFile expected) throws IOException {
            if (before.size() != expected.size()) {
                throw new IOException("JCEF distribution file size mismatch: " + relativePath);
            }

            MessageDigest digest = newSha256();
            long bytesRead = 0L;
            hashBuffer.clear();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (channel.size() != expected.size()) {
                    throw new IOException("JCEF distribution file changed while opening: " + relativePath);
                }
                while (true) {
                    int count = channel.read(hashBuffer);
                    if (count < 0) {
                        break;
                    }
                    if (count == 0) {
                        continue;
                    }
                    bytesRead += count;
                    if (bytesRead > expected.size()) {
                        throw new IOException("JCEF distribution file grew while hashing: " + relativePath);
                    }
                    hashBuffer.flip();
                    digest.update(hashBuffer);
                    hashBuffer.clear();
                }
                if (channel.size() != expected.size()) {
                    throw new IOException("JCEF distribution file changed while hashing: " + relativePath);
                }
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (bytesRead != expected.size() || !sameFileSnapshot(before, after)) {
                throw new IOException("JCEF distribution file changed while hashing: " + relativePath);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (!sha256.equals(expected.sha256())) {
                throw new IOException("JCEF distribution file checksum mismatch: " + relativePath);
            }
            distributionFileSnapshots.put(file, after);
            return new HashedFile(bytesRead, sha256);
        }

        private ValidatedTree finish() throws IOException {
            if (!directorySnapshots.isEmpty()) {
                throw new IOException("JCEF distribution directory validation did not complete");
            }
            if (!foundManifest) {
                throw new IOException("Missing JCEF distribution manifest");
            }
            if (published != (foundGenerationState && foundLeaseDirectory)) {
                throw new IOException(published ? "Published JCEF generation omits installer-owned metadata" : "Staged JCEF archive contains installer-owned metadata");
            }
            if (!actualFiles.keySet().equals(declared.files().keySet()) || !actualDirectories.equals(declared.directories()) || hashedBytes != declared.bytes()) {
                throw new IOException("JCEF distribution inventory does not exactly cover the installation tree");
            }
            revalidateFileSnapshots(distributionFileSnapshots, "JCEF distribution file changed after hashing");
            revalidateFileSnapshots(installerFileSnapshots, "JCEF installer-owned file changed during validation");
            revalidateDirectorySnapshots();
            return new ValidatedTree(actualFiles, actualDirectories);
        }

        private void revalidateFileSnapshots(Map<Path, BasicFileAttributes> snapshots, String message) throws IOException {
            for (Map.Entry<Path, BasicFileAttributes> snapshot : snapshots.entrySet()) {
                BasicFileAttributes after = Files.readAttributes(snapshot.getKey(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!sameFileSnapshot(snapshot.getValue(), after)) {
                    throw new IOException(message + ": " + relativePosixPath(installation, snapshot.getKey()));
                }
            }
        }

        private void revalidateDirectorySnapshots() throws IOException {
            for (Map.Entry<Path, BasicFileAttributes> snapshot : validatedDirectorySnapshots.entrySet()) {
                BasicFileAttributes after = Files.readAttributes(snapshot.getKey(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!sameDirectorySnapshot(snapshot.getValue(), after)) {
                    throw new IOException("JCEF distribution directory changed after traversal: " + relativePosixPath(installation, snapshot.getKey()));
                }
            }
        }
    }
}
