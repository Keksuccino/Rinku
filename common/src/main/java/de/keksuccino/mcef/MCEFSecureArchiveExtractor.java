package de.keksuccino.mcef;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Extracts a JCEF tarball into a new staging tree without following or replacing paths. */
final class MCEFSecureArchiveExtractor {
    private static final int BUFFER_SIZE_BYTES = 16 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 200_000;
    static final int MAX_EXTRACTED_FILESYSTEM_ENTRIES = 8_000;
    private static final int MAX_ARCHIVE_PATH_CHARACTERS = 4096;
    private static final int MAX_ARCHIVE_PATH_COMPONENTS = 64;
    private static final int MAX_ARCHIVE_COMPONENT_CHARACTERS = 255;
    private static final long MAX_TOTAL_ARCHIVE_PATH_CHARACTERS = 16L * 1024L * 1024L;
    private static final long MAX_EXTENSION_ENTRY_BYTES = 1024L * 1024L;
    private static final long MAX_TOTAL_EXTENSION_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_EXTENSION_CHAIN_DEPTH = 32;
    private static final int MAX_HEADER_RECORDS_PER_ENTRY = 2048;
    private static final int MAX_RAW_ARCHIVE_HEADERS = MAX_ARCHIVE_ENTRIES * 4;
    private static final byte[] GNU_SPARSE_PAX_KEY = "GNU.sparse".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final Set<PosixFilePermission> POSIX_DIRECTORY_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> POSIX_EXECUTABLE_PERMISSIONS = POSIX_DIRECTORY_PERMISSIONS;
    private static final Set<PosixFilePermission> POSIX_REGULAR_FILE_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private MCEFSecureArchiveExtractor() {
    }

    static void extract(File tarGzFile, File outputDirectory, MCEFPlatform platform, MCEFDownloader.DownloadPolicy policy, Consumer<Float> progress) throws IOException {
        try (MCEFVerifiedArchiveSource archive = MCEFVerifiedArchiveSource.open(tarGzFile.toPath(), policy.maxArchiveBytes())) {
            String archiveDigest = archive.calculateDigest();
            extract(archive, archiveDigest, outputDirectory, platform, policy, progress);
        }
    }

    static void extract(MCEFVerifiedArchiveSource archive, String expectedDigest, File outputDirectory, MCEFPlatform platform, MCEFDownloader.DownloadPolicy policy, Consumer<Float> progress) throws IOException {
        Path outputRoot = outputDirectory.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe JCEF extraction directory " + outputRoot);
        }
        try (var existingEntries = Files.list(outputRoot)) {
            if (existingEntries.findAny().isPresent()) {
                throw new IOException("JCEF extraction directory was not empty");
            }
        }
        Path realOutputRoot = outputRoot.toRealPath();
        boolean normalizePosixModes = !platform.isWindows() && Files.getFileStore(realOutputRoot).supportsFileAttributeView(PosixFileAttributeView.class);

        ArchiveScan[] scannedArchive = new ArchiveScan[1];
        archive.verifiedPass(expectedDigest, input -> scannedArchive[0] = scanArchive(input, platform, policy));
        ArchiveScan archiveScan = scannedArchive[0];
        long expectedExtractedSize = Math.max(1L, archiveScan.totalSize());
        ArchiveScan[] extractedArchive = new ArchiveScan[1];
        archive.verifiedPass(expectedDigest, input -> extractedArchive[0] = extractArchive(input, realOutputRoot, platform, policy, progress, normalizePosixModes, expectedExtractedSize));
        if (!archiveScan.equals(extractedArchive[0])) {
            throw new IOException("JCEF archive changed while it was being extracted");
        }
        forceDirectoryTreeBestEffort(realOutputRoot);
        progress.accept(1.0f);
    }

    private static ArchiveScan extractArchive(InputStream archiveInput, Path realOutputRoot, MCEFPlatform platform, MCEFDownloader.DownloadPolicy policy, Consumer<Float> progress, boolean normalizePosixModes, long expectedExtractedSize) throws IOException {
        long totalBytesRead = 0L;
        int entryCount = 0;
        byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        ArchivePathTracker pathTracker = new ArchivePathTracker(platform.getNormalizedName());
        try (TarArchiveInputStream tarInput = openTarArchive(archiveInput)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                entryCount++;
                validateArchiveEntry(tarInput, entry, entryCount, pathTracker);

                Path outputPath = resolveOutputPath(realOutputRoot, entry.getName());
                if (entry.isDirectory()) {
                    createSafeDirectoryTree(realOutputRoot, outputPath, normalizePosixModes);
                    continue;
                }

                Path parent = outputPath.getParent();
                if (parent != null) {
                    createSafeDirectoryTree(realOutputRoot, parent, normalizePosixModes);
                }

                long entryBytesRead = 0L;
                try (FileChannel outputChannel = FileChannel.open(outputPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    BufferedOutputStream outputStream = new BufferedOutputStream(Channels.newOutputStream(outputChannel), BUFFER_SIZE_BYTES);
                    int bytesRead;
                    while ((bytesRead = tarInput.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        entryBytesRead += bytesRead;
                        totalBytesRead += bytesRead;
                        if (entryBytesRead > entry.getSize() || totalBytesRead > policy.maxExtractedBytes()) {
                            throw new IOException("Extracted size exceeded configured limit");
                        }
                        progress.accept(Math.min(0.99f, (float) totalBytesRead / expectedExtractedSize));
                    }
                    outputStream.flush();
                    if (normalizePosixModes) {
                        Files.setPosixFilePermissions(outputPath, (entry.getMode() & 0111) != 0 ? POSIX_EXECUTABLE_PERMISSIONS : POSIX_REGULAR_FILE_PERMISSIONS);
                    }
                    outputChannel.force(true);
                } catch (FileAlreadyExistsException collision) {
                    throw new IOException("Archive attempted to replace an existing path: " + entry.getName(), collision);
                }
                if (entryBytesRead != entry.getSize()) {
                    throw new IOException("Archive entry size did not match its header: " + entry.getName());
                }
            }
        }
        return new ArchiveScan(totalBytesRead, entryCount);
    }

    private static ArchiveScan scanArchive(InputStream archiveInput, MCEFPlatform platform, MCEFDownloader.DownloadPolicy policy) throws IOException {
        long totalSize = 0L;
        int entryCount = 0;
        byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        ArchivePathTracker pathTracker = new ArchivePathTracker(platform.getNormalizedName());
        try (TarArchiveInputStream tarInput = openTarArchive(archiveInput)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextEntry()) != null) {
                entryCount++;
                validateArchiveEntry(tarInput, entry, entryCount, pathTracker);
                if (entry.isDirectory()) {
                    continue;
                }
                long entrySize = entry.getSize();
                try {
                    totalSize = Math.addExact(totalSize, entrySize);
                } catch (ArithmeticException overflow) {
                    throw new IOException("Archive extracted size overflowed", overflow);
                }
                if (entrySize > policy.maxExtractedBytes() || totalSize > policy.maxExtractedBytes()) {
                    throw new IOException("Archive exceeds configured extracted size limit");
                }
                long consumed = 0L;
                int bytesRead;
                while ((bytesRead = tarInput.read(buffer)) != -1) {
                    consumed += bytesRead;
                    if (consumed > entrySize) {
                        throw new IOException("Archive entry exceeded its declared size: " + entry.getName());
                    }
                }
                if (consumed != entrySize) {
                    throw new IOException("Archive entry size did not match its header: " + entry.getName());
                }
            }
        }
        return new ArchiveScan(totalSize, entryCount);
    }

    private static TarArchiveInputStream openTarArchive(InputStream archiveInput) throws IOException {
        InputStream decompressed = new GzipCompressorInputStream(new BufferedInputStream(archiveInput, BUFFER_SIZE_BYTES));
        return new HardenedTarArchiveInputStream(decompressed);
    }

    private static void validateArchiveEntry(TarArchiveInputStream tarInput, TarArchiveEntry entry, int entryCount, ArchivePathTracker pathTracker) throws IOException {
        if (entryCount > MAX_ARCHIVE_ENTRIES) {
            throw new IOException("Archive contains too many entries");
        }
        if (entry.isSymbolicLink() || entry.isLink() || (!entry.isDirectory() && !entry.isFile())) {
            throw new IOException("Unsupported archive entry type: " + entry.getName());
        }
        if (entry.isSparse()) {
            throw new IOException("Sparse archive entries are unsupported: " + entry.getName());
        }
        if (!tarInput.canReadEntryData(entry)) {
            throw new IOException("Unsupported archive entry encoding: " + entry.getName());
        }
        if (entry.getSize() < 0L) {
            throw new IOException("Archive entry has a negative size: " + entry.getName());
        }
        if (entry.isDirectory() && entry.getSize() != 0L) {
            // Directory payloads are never part of a normal JCEF tarball. Rejecting them prevents
            // decompression work from bypassing the aggregate extracted-byte limit.
            throw new IOException("Archive directory entry contained an unexpected payload: " + entry.getName());
        }
        pathTracker.add(entry.getName(), entry.isDirectory());
    }

    private static Path resolveOutputPath(Path outputRoot, String entryName) throws IOException {
        String normalizedEntryName = entryName.replace('\\', '/');
        Path resolved = outputRoot.resolve(normalizedEntryName).normalize();
        if (!resolved.startsWith(outputRoot)) {
            throw new IOException("Archive entry escaped target directory: " + entryName);
        }
        return resolved;
    }

    private static void createSafeDirectoryTree(Path root, Path directory, boolean normalizePosixModes) throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(root)) {
            throw new IOException("Archive directory escaped its extraction root: " + directory);
        }
        Path current = root;
        for (Path component : root.relativize(normalizedDirectory)) {
            current = current.resolve(component);
            if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                if (normalizePosixModes) {
                    Files.setPosixFilePermissions(current, POSIX_DIRECTORY_PERMISSIONS);
                }
                continue;
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe archive directory component: " + current);
            }
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException race) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unsafe archive directory created concurrently: " + current, race);
                }
            }
            if (normalizePosixModes) {
                Files.setPosixFilePermissions(current, POSIX_DIRECTORY_PERMISSIONS);
            }
        }
    }

    private static void forceDirectoryTreeBestEffort(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path directory : paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).sorted(Comparator.reverseOrder()).toList()) {
                try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                    channel.force(true);
                } catch (IOException | UnsupportedOperationException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private record ArchiveScan(long totalSize, int entryCount) {
    }

    /**
     * Commons Compress resolves PAX and GNU extension records before returning an archive entry.
     * These bounds therefore live inside the stream, before its extension parsers can allocate
     * unbounded buffers or recurse through an attacker-controlled header chain.
     */
    private static final class HardenedTarArchiveInputStream extends TarArchiveInputStream {
        private TarArchiveEntry validatedExtensionEntry;
        private int entryResolutionDepth;
        private int headerRecordsDuringResolution;
        private int rawHeaderRecords;
        private int extensionEntries;
        private int sparseKeyMatchLength;
        private long totalExtensionBytes;

        private HardenedTarArchiveInputStream(InputStream input) {
            super(input);
        }

        @Override
        public TarArchiveEntry getNextEntry() throws IOException {
            boolean outermost = entryResolutionDepth == 0;
            if (outermost) {
                headerRecordsDuringResolution = 0;
            }
            entryResolutionDepth++;
            if (entryResolutionDepth > MAX_EXTENSION_CHAIN_DEPTH) {
                entryResolutionDepth--;
                throw new IOException("Archive extension header chain was too deep");
            }
            try {
                return super.getNextEntry();
            } finally {
                entryResolutionDepth--;
            }
        }

        @Override
        protected byte[] getLongNameData() throws IOException {
            validateExtensionEntry(getCurrentEntry());
            return super.getLongNameData();
        }

        @Override
        protected byte[] readRecord() throws IOException {
            headerRecordsDuringResolution++;
            rawHeaderRecords++;
            if (headerRecordsDuringResolution > MAX_HEADER_RECORDS_PER_ENTRY) {
                throw new IOException("Archive entry used too many extension header records");
            }
            if (rawHeaderRecords > MAX_RAW_ARCHIVE_HEADERS) {
                throw new IOException("Archive contained too many raw header records");
            }
            return super.readRecord();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            TarArchiveEntry currentEntry = getCurrentEntry();
            boolean paxExtension = currentEntry != null && (currentEntry.isPaxHeader() || currentEntry.isGlobalPaxHeader());
            boolean extension = paxExtension || currentEntry != null && (currentEntry.isGNULongNameEntry() || currentEntry.isGNULongLinkEntry());
            if (extension) {
                validateExtensionEntry(currentEntry);
            }
            int bytesRead = super.read(buffer, offset, length);
            if (extension && bytesRead > 0) {
                try {
                    totalExtensionBytes = Math.addExact(totalExtensionBytes, bytesRead);
                } catch (ArithmeticException overflow) {
                    throw new IOException("Archive extension metadata size overflowed", overflow);
                }
                if (totalExtensionBytes > MAX_TOTAL_EXTENSION_BYTES) {
                    throw new IOException("Archive extension metadata exceeded the configured safety limit");
                }
                if (paxExtension) {
                    rejectSparsePaxMetadata(buffer, offset, bytesRead);
                }
            }
            return bytesRead;
        }

        private void validateExtensionEntry(TarArchiveEntry entry) throws IOException {
            if (entry == null || entry == validatedExtensionEntry) {
                return;
            }
            long entrySize = entry.getSize();
            if (entrySize < 0L || entrySize > MAX_EXTENSION_ENTRY_BYTES) {
                throw new IOException("Archive extension entry exceeded the configured metadata limit");
            }
            extensionEntries++;
            if (extensionEntries > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("Archive contained too many extension entries");
            }
            validatedExtensionEntry = entry;
            sparseKeyMatchLength = 0;
        }

        private void rejectSparsePaxMetadata(byte[] buffer, int offset, int length) throws IOException {
            int end = offset + length;
            for (int index = offset; index < end; index++) {
                byte value = buffer[index];
                if (value == GNU_SPARSE_PAX_KEY[sparseKeyMatchLength]) {
                    sparseKeyMatchLength++;
                    if (sparseKeyMatchLength == GNU_SPARSE_PAX_KEY.length) {
                        throw new IOException("Sparse PAX archive entries are unsupported");
                    }
                } else {
                    sparseKeyMatchLength = value == GNU_SPARSE_PAX_KEY[0] ? 1 : 0;
                }
            }
        }
    }

    private static final class ArchivePathTracker {
        private final String expectedRoot;
        private final Set<String> paths = new HashSet<>();
        private final Set<String> foldedPaths = new HashSet<>();
        private final Set<String> files = new HashSet<>();
        private final Set<String> directories = new HashSet<>();
        private long totalPathCharacters;

        private ArchivePathTracker(String expectedRoot) {
            this.expectedRoot = expectedRoot;
        }

        private void add(String entryName, boolean directory) throws IOException {
            if (entryName == null || entryName.isBlank() || entryName.length() > MAX_ARCHIVE_PATH_CHARACTERS || entryName.indexOf('\0') >= 0) {
                throw new IOException("Archive entry has an invalid name");
            }
            try {
                totalPathCharacters = Math.addExact(totalPathCharacters, entryName.length());
            } catch (ArithmeticException overflow) {
                throw new IOException("Archive path metadata size overflowed", overflow);
            }
            if (totalPathCharacters > MAX_TOTAL_ARCHIVE_PATH_CHARACTERS) {
                throw new IOException("Archive path metadata exceeded the configured safety limit");
            }
            String normalizedSeparators = entryName.replace('\\', '/');
            if (normalizedSeparators.startsWith("/") || normalizedSeparators.matches("(?i)^[a-z]:.*")) {
                throw new IOException("Archive entry used an absolute path: " + entryName);
            }
            while (normalizedSeparators.endsWith("/")) {
                normalizedSeparators = normalizedSeparators.substring(0, normalizedSeparators.length() - 1);
            }
            String[] components = normalizedSeparators.split("/", -1);
            if (components.length == 0 || components.length > MAX_ARCHIVE_PATH_COMPONENTS || !expectedRoot.equals(components[0])) {
                throw new IOException("Archive entry was outside the expected " + expectedRoot + " root: " + entryName);
            }
            for (String component : components) {
                if (component.isEmpty() || component.length() > MAX_ARCHIVE_COMPONENT_CHARACTERS || ".".equals(component) || "..".equals(component)) {
                    throw new IOException("Archive entry used an ambiguous path: " + entryName);
                }
            }

            String normalized = String.join("/", components);
            String folded = normalized.toLowerCase(Locale.ROOT);
            if (!paths.add(normalized) || !foldedPaths.add(folded)) {
                throw new IOException("Archive contains a duplicate or case-colliding path: " + entryName);
            }
            StringBuilder parent = new StringBuilder();
            for (int index = 0; index < components.length - 1; index++) {
                if (index > 0) {
                    parent.append('/');
                }
                parent.append(components[index]);
                if (files.contains(parent.toString())) {
                    throw new IOException("Archive path descends through a file: " + entryName);
                }
                directories.add(parent.toString());
            }
            if (directory) {
                if (files.contains(normalized)) {
                    throw new IOException("Archive path is both a file and directory: " + entryName);
                }
                directories.add(normalized);
            } else {
                if (directories.contains(normalized)) {
                    throw new IOException("Archive path is both a file and directory: " + entryName);
                }
                files.add(normalized);
            }
            if (directories.size() + files.size() > MAX_EXTRACTED_FILESYSTEM_ENTRIES) {
                throw new IOException("Archive would create too many filesystem entries");
            }
        }
    }
}
