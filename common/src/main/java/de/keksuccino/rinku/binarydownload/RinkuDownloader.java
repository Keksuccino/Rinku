package de.keksuccino.rinku.binarydownload;

import de.keksuccino.rinku.*;
import de.keksuccino.rinku.util.GameDirectoryUtils;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RinkuDownloader {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String OFFICIAL_MIRROR = "https://github.com/Keksuccino/jcef-mcef/releases/download";
    public static final String PREVIOUS_OFFICIAL_MIRROR = "https://github.com/Keksuccino/mcef_resources/releases/download";
    public static final String LEGACY_OFFICIAL_MIRROR = "https://mcef-download.cinemamod.com";

    private static final String JAVA_CEF_RELEASE_TAG_PREFIX = "java-cef-";
    private static final RinkuDownloadMirror OFFICIAL_DOWNLOAD_MIRROR = RinkuDownloadMirror.parse(OFFICIAL_MIRROR);
    private static final int DOWNLOAD_BUFFER_SIZE_BYTES = 16 * 1024;
    private static final Pattern GNU_SHA256_PATTERN = Pattern.compile("(?i)^([0-9a-f]{64})(?:[ \\t]+\\*?([^\\r\\n]+))?$");
    private static final Pattern BSD_SHA256_PATTERN = Pattern.compile("(?i)^SHA256[ \\t]*\\(([^\\r\\n]+)\\)[ \\t]*=[ \\t]*([0-9a-f]{64})$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final String host;
    private final RinkuDownloadMirror configuredMirror;
    private final String javaCefCommitHash;
    private final OSPlatform platform;
    private final DownloadPolicy downloadPolicy;
    private final Path librariesDirectoryOverride;
    private final ArtifactDownloader artifactDownloader;
    private final ArchiveExtractor archiveExtractor;

    /** Installer network and decompression limits. */
    public record DownloadPolicy(MirrorPolicy mirrorPolicy, boolean enforceChecksums, int connectTimeoutMs, int readTimeoutMs, long maxArchiveBytes, long maxChecksumBytes, long maxExtractedBytes) {
        public DownloadPolicy {
            mirrorPolicy = mirrorPolicy == null ? MirrorPolicy.OFFICIAL_ONLY : mirrorPolicy;
            connectTimeoutMs = Math.max(1_000, connectTimeoutMs);
            readTimeoutMs = Math.max(1_000, readTimeoutMs);
            maxArchiveBytes = Math.max(1_048_576L, maxArchiveBytes);
            maxChecksumBytes = Math.max(512L, maxChecksumBytes);
            maxExtractedBytes = Math.max(1_048_576L, maxExtractedBytes);
        }

        public static DownloadPolicy defaults() {
            return new DownloadPolicy(MirrorPolicy.OFFICIAL_ONLY, true, 15_000, 60_000, 750L * 1024L * 1024L, 64L * 1024L, 2_000L * 1024L * 1024L);
        }
    }

    public record InstallationResult(Path installationDirectory, boolean downloaded) {
        public InstallationResult {
            installationDirectory = Objects.requireNonNull(installationDirectory, "JCEF installation directory must not be null").toAbsolutePath().normalize();
        }
    }

    public RinkuDownloader(String host, OSPlatform platform) {
        this(host, JcefRuntimeIdentity.JAVA_CEF_COMMIT, platform, DownloadPolicy.defaults(), null, null, null);
    }

    public RinkuDownloader(String host, OSPlatform platform, DownloadPolicy downloadPolicy) {
        this(host, JcefRuntimeIdentity.JAVA_CEF_COMMIT, platform, downloadPolicy, null, null, null);
    }

    RinkuDownloader(String host, String javaCefCommitHash, OSPlatform platform, DownloadPolicy downloadPolicy, Path librariesDirectory, ArtifactDownloader artifactDownloader, ArchiveExtractor archiveExtractor) {
        this.javaCefCommitHash = RinkuJcefInstallationValidator.normalizeCommit(javaCefCommitHash);
        this.platform = Objects.requireNonNull(platform, "Rinku platform must not be null");
        this.downloadPolicy = downloadPolicy == null ? DownloadPolicy.defaults() : downloadPolicy;
        configuredMirror = resolveConfiguredMirror(host, this.downloadPolicy.mirrorPolicy());
        this.host = configuredMirror == null ? OFFICIAL_MIRROR : configuredMirror.externalForm();
        librariesDirectoryOverride = librariesDirectory == null ? null : librariesDirectory.toAbsolutePath().normalize();
        this.artifactDownloader = artifactDownloader;
        this.archiveExtractor = archiveExtractor;
    }

    public String getHost() {
        return host;
    }

    public String getJavaCefDownloadUrl() {
        return archiveUri(resolveMirrorCandidates().getFirst()).toASCIIString();
    }

    public String getJavaCefChecksumDownloadUrl() {
        return checksumUri(resolveMirrorCandidates().getFirst()).toASCIIString();
    }

    public DownloadPolicy getDownloadPolicy() {
        return downloadPolicy;
    }

    /** Reuses only a valid completed exact-commit leaf; otherwise installs it under one platform lock. */
    public InstallationResult installOrUpdate(boolean skipDownload) throws IOException {
        try (RinkuJcefInstaller installer = newInstaller()) {
            installer.recover();
            Path reusable = installer.findReusableInstallation();
            if (reusable != null) {
                return new InstallationResult(reusable, false);
            }
            if (skipDownload) {
                throw new IOException("skip-download=true but the exact JCEF commit has no complete valid local installation");
            }

            Throwable lastFailure = null;
            List<RinkuDownloadMirror> mirrors = resolveMirrorCandidates();
            for (int index = 0; index < mirrors.size(); index++) {
                RinkuDownloadMirror mirror = mirrors.get(index);
                installer.prepareFresh();
                try {
                    Path installed = installFromMirror(installer, mirror);
                    return new InstallationResult(installed, true);
                } catch (IOException | RuntimeException failure) {
                    lastFailure = failure;
                    try {
                        installer.discardPrepared();
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    LOGGER.warn("JCEF release validation failed for {}; trying the next permitted mirror if available", mirror.safeLogIdentity());
                }
            }
            throw asIOExceptionOrThrowRuntime(lastFailure);
        }
    }

    /** Removes abandoned same-filesystem staging directories without touching any commit leaf. */
    public void recoverInterruptedInstallation() throws IOException {
        try (RinkuJcefInstaller installer = newInstaller()) {
            installer.recover();
        }
    }

    private Path installFromMirror(RinkuJcefInstaller installer, RinkuDownloadMirror mirror) throws IOException {
        String expectedChecksum = downloadChecksumFromMirror(installer, mirror);
        RinkuDownloadListener.INSTANCE.setTask("Downloading Chromium Embedded Framework");
        downloadArtifact(mirror, archiveAssetName(), installer.candidateArchive().toFile(), downloadPolicy.maxArchiveBytes());
        try (RinkuVerifiedArchiveSource archive = RinkuVerifiedArchiveSource.open(installer.candidateArchive(), downloadPolicy.maxArchiveBytes())) {
            String actualDigest = archive.calculateDigest();
            if (expectedChecksum != null && !expectedChecksum.equals(actualDigest)) {
                throw new IOException("Checksum mismatch for downloaded JCEF archive");
            }
            extractArchive(archive, actualDigest, installer.extractionDirectory().toFile());
        }
        return installer.publish();
    }

    private String downloadChecksumFromMirror(RinkuJcefInstaller installer, RinkuDownloadMirror mirror) throws IOException {
        try {
            RinkuDownloadListener.INSTANCE.setTask("Downloading Checksum");
            downloadArtifact(mirror, checksumAssetName(), installer.candidateChecksum().toFile(), downloadPolicy.maxChecksumBytes());
            String expectedChecksum = readChecksum(installer.candidateChecksum().toFile(), downloadPolicy.enforceChecksums());
            if (expectedChecksum == null && downloadPolicy.enforceChecksums()) {
                throw new IOException("Missing or invalid JCEF checksum");
            }
            if (expectedChecksum == null) {
                installer.discardCandidateChecksum();
            }
            return expectedChecksum;
        } catch (IOException failure) {
            if (downloadPolicy.enforceChecksums()) {
                throw failure;
            }
            LOGGER.warn("A valid JCEF checksum was unavailable from {}; continuing with the explicitly unchecked archive from that mirror", mirror.safeLogIdentity());
            installer.discardCandidateChecksum();
            return null;
        }
    }

    private RinkuJcefInstaller newInstaller() throws IOException {
        return new RinkuJcefInstaller(getLibrariesDirectory(), platform, javaCefCommitHash, failure -> LOGGER.warn("Could not completely clean JCEF installer staging residue; cleanup will retry later.", failure));
    }

    private Path getLibrariesDirectory() {
        return librariesDirectoryOverride == null ? GameDirectoryUtils.getGameDirectory().toPath().resolve("rinku-libraries") : librariesDirectoryOverride;
    }

    private void downloadArtifact(RinkuDownloadMirror mirror, String assetName, File outputFile, long maxBytes) throws IOException {
        Path output = outputFile.toPath();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace pre-existing JCEF download target: " + output);
        }
        try {
            URI assetUri = mirror.assetUri(javaCefReleaseTag(), assetName);
            if (artifactDownloader != null) {
                artifactDownloader.download(assetUri.toASCIIString(), outputFile, maxBytes);
            } else {
                downloadFile(assetUri, mirror.safeLogIdentity(), outputFile, maxBytes);
            }
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("JCEF download did not create a safe regular file: " + output);
            }
            long size = Files.size(output);
            if (size <= 0L || size > maxBytes) {
                throw new IOException("Downloaded JCEF artifact size is outside the configured limit");
            }
        } catch (IOException | RuntimeException failure) {
            try {
                if (Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
                    Files.deleteIfExists(output);
                }
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void extractArchive(RinkuVerifiedArchiveSource archive, String expectedDigest, File outputDirectory) throws IOException {
        if (archiveExtractor != null) {
            RinkuDownloadListener.INSTANCE.setTask("Extracting");
            archive.verifiedPass(expectedDigest, input -> archiveExtractor.extract(input, outputDirectory));
            return;
        }
        RinkuDownloadListener.INSTANCE.setTask("Extracting");
        RinkuSecureArchiveExtractor.extract(archive, expectedDigest, outputDirectory, platform, downloadPolicy, RinkuDownloadListener.INSTANCE::setProgress);
    }

    private void downloadFile(URI assetUri, String mirrorIdentity, File outputFile, long maxBytes) throws IOException {
        Path output = outputFile.toPath().toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe JCEF download directory: " + parent);
        }
        Path tempOutput = parent.resolve("." + output.getFileName() + ".part-" + java.util.UUID.randomUUID());
        long readBytes = 0L;
        HttpURLConnection urlConnection = null;

        try {
            LOGGER.info("Downloading JCEF asset {} from {}", output.getFileName(), mirrorIdentity);
            URL url = assetUri.toURL();
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(downloadPolicy.connectTimeoutMs());
            urlConnection.setReadTimeout(downloadPolicy.readTimeoutMs());
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.connect();

            int responseCode = urlConnection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Unexpected HTTP status " + responseCode);
            }
            if (!"https".equalsIgnoreCase(urlConnection.getURL().getProtocol())) {
                throw new IOException("JCEF download redirected outside HTTPS");
            }
            long fileSize = urlConnection.getContentLengthLong();
            if (fileSize > maxBytes) {
                throw new IOException("Remote file size exceeds configured limit");
            }

            try (BufferedInputStream inputStream = new BufferedInputStream(urlConnection.getInputStream(), DOWNLOAD_BUFFER_SIZE_BYTES); FileChannel outputChannel = FileChannel.open(tempOutput, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                BufferedOutputStream outputStream = new BufferedOutputStream(Channels.newOutputStream(outputChannel), DOWNLOAD_BUFFER_SIZE_BYTES);
                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE_BYTES];
                int count;
                while ((count = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, count);
                    readBytes += count;
                    if (readBytes > maxBytes) {
                        throw new IOException("Downloaded file size exceeded configured limit");
                    }
                    long progressTotal = fileSize > 0L ? fileSize : maxBytes;
                    RinkuDownloadListener.INSTANCE.setProgress(Math.min(0.99f, (float) readBytes / progressTotal));
                }
                outputStream.flush();
                outputChannel.force(true);
            }
            try {
                Files.move(tempOutput, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tempOutput, output);
            }
            RinkuDownloadListener.INSTANCE.setProgress(1.0f);
        } catch (IOException failure) {
            IOException sanitizedFailure = new IOException("Failed to download a JCEF asset from " + mirrorIdentity + " (" + failure.getClass().getSimpleName() + ")");
            try {
                Files.deleteIfExists(tempOutput);
            } catch (IOException cleanupFailure) {
                sanitizedFailure.addSuppressed(new IOException("Temporary JCEF download cleanup failed"));
            }
            throw sanitizedFailure;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private String readChecksum(File checksumFile, boolean strict) throws IOException {
        Path path = checksumFile.toPath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String content;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long checksumSize = channel.size();
            if (checksumSize <= 0L || checksumSize > downloadPolicy.maxChecksumBytes()) {
                if (strict) {
                    throw new IOException("Checksum file size out of bounds: " + checksumFile.getName());
                }
                return null;
            }
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(checksumSize));
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Checksum file changed while it was being read: " + checksumFile.getName());
                }
            }
            if (channel.size() != checksumSize) {
                throw new IOException("Checksum file changed while it was being read: " + checksumFile.getName());
            }
            content = StandardCharsets.UTF_8.decode(buffer.flip()).toString();
        }
        String checksum = extractSha256Token(content);
        if (checksum == null && strict) {
            throw new IOException("Checksum file does not contain a valid SHA-256 digest: " + checksumFile.getName());
        }
        return checksum;
    }

    private String extractSha256Token(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.strip();
        Matcher gnuMatcher = GNU_SHA256_PATTERN.matcher(trimmed);
        if (gnuMatcher.matches()) {
            String assetName = gnuMatcher.group(2);
            return assetName == null || checksumAssetMatches(assetName) ? normalizeDigest(gnuMatcher.group(1)) : null;
        }
        Matcher bsdMatcher = BSD_SHA256_PATTERN.matcher(trimmed);
        if (bsdMatcher.matches() && checksumAssetMatches(bsdMatcher.group(1))) {
            return normalizeDigest(bsdMatcher.group(2));
        }
        return null;
    }

    private boolean checksumAssetMatches(String assetName) {
        String normalizedName = assetName.strip().replace('\\', '/');
        int finalSeparator = normalizedName.lastIndexOf('/');
        String baseName = finalSeparator < 0 ? normalizedName : normalizedName.substring(finalSeparator + 1);
        return baseName.equalsIgnoreCase(archiveAssetName());
    }

    private String javaCefReleaseTag() {
        return JAVA_CEF_RELEASE_TAG_PREFIX + javaCefCommitHash;
    }

    private String archiveAssetName() {
        return platform.getNormalizedName() + ".tar.gz";
    }

    private String checksumAssetName() {
        return archiveAssetName() + ".sha256";
    }

    private URI archiveUri(RinkuDownloadMirror mirror) {
        return mirror.assetUri(javaCefReleaseTag(), archiveAssetName());
    }

    private URI checksumUri(RinkuDownloadMirror mirror) {
        return mirror.assetUri(javaCefReleaseTag(), checksumAssetName());
    }

    private List<RinkuDownloadMirror> resolveMirrorCandidates() {
        List<RinkuDownloadMirror> mirrors = new ArrayList<>();
        switch (downloadPolicy.mirrorPolicy()) {
            case OFFICIAL_ONLY -> mirrors.add(OFFICIAL_DOWNLOAD_MIRROR);
            case PREFER_CONFIGURED -> {
                if (configuredMirror != null) {
                    mirrors.add(configuredMirror);
                }
                if (configuredMirror == null || !configuredMirror.externalForm().equals(OFFICIAL_DOWNLOAD_MIRROR.externalForm())) {
                    mirrors.add(OFFICIAL_DOWNLOAD_MIRROR);
                }
            }
            case CONFIGURED_ONLY -> {
                if (configuredMirror == null) {
                    throw new IllegalStateException("Configured mirror is invalid for CONFIGURED_ONLY policy");
                }
                mirrors.add(configuredMirror);
            }
        }
        return List.copyOf(mirrors);
    }

    private static RinkuDownloadMirror resolveConfiguredMirror(String host, MirrorPolicy policy) {
        if (policy == MirrorPolicy.OFFICIAL_ONLY) {
            return null;
        }
        if (host == null || host.isBlank()) {
            if (policy == MirrorPolicy.CONFIGURED_ONLY) {
                throw new IllegalArgumentException("CONFIGURED_ONLY requires a valid JCEF mirror");
            }
            return null;
        }
        String trimmed = host.trim();
        String normalized = normalizeOfficialMirror(trimmed);
        if (!normalized.equals(trimmed)) {
            LOGGER.warn("Migrating the former default JCEF download mirror to the current official mirror");
        }
        try {
            return RinkuDownloadMirror.parse(normalized);
        } catch (IllegalArgumentException failure) {
            if (policy == MirrorPolicy.CONFIGURED_ONLY) {
                throw new IllegalArgumentException("CONFIGURED_ONLY requires a valid JCEF mirror");
            }
            LOGGER.warn("Ignoring an invalid configured JCEF mirror and using the official mirror");
            return null;
        }
    }

    public static String normalizeOfficialMirror(String mirror) {
        if (mirror == null) {
            return null;
        }
        String normalized = stripTrailingSlash(mirror.trim());
        if (stripTrailingSlash(PREVIOUS_OFFICIAL_MIRROR).equalsIgnoreCase(normalized) || stripTrailingSlash(LEGACY_OFFICIAL_MIRROR).equalsIgnoreCase(normalized)) {
            return OFFICIAL_MIRROR;
        }
        return normalized;
    }

    private static String normalizeDigest(String digest) {
        String normalized = digest.toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256 digest");
        }
        return normalized;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    private static IOException asIOExceptionOrThrowRuntime(Throwable failure) throws IOException {
        if (failure instanceof IOException ioFailure) {
            return ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        return new IOException("Failed to obtain and validate a JCEF release from the permitted mirror set", failure);
    }

    public enum MirrorPolicy {
        OFFICIAL_ONLY,
        PREFER_CONFIGURED,
        CONFIGURED_ONLY
    }

    @FunctionalInterface
    interface ArtifactDownloader {
        void download(String assetUrl, File outputFile, long maxBytes) throws IOException;
    }

    @FunctionalInterface
    interface ArchiveExtractor {
        void extract(InputStream archive, File outputDirectory) throws IOException;
    }

}
