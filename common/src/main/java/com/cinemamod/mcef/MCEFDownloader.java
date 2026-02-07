/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.cinemamod.mcef;

import com.cinemamod.mcef.internal.MCEFDownloadListener;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A downloader and extraction tool for java-cef builds.
 * <p>
 * Downloads for <a href="https://github.com/CinemaMod/java-cef">CinemaMod java-cef</a> are provided by
 * <a href="https://github.com/Keksuccino/mcef_resources">mcef_resources</a> unless changed in the
 * MCEFSettings properties file; see {@link MCEFSettings}.
 */
public class MCEFDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");

    public static final String OFFICIAL_MIRROR = "https://github.com/Keksuccino/mcef_resources/releases/download";
    public static final String LEGACY_OFFICIAL_MIRROR = "https://mcef-download.cinemamod.com";

    private static final String JAVA_CEF_RELEASE_TAG_TEMPLATE = "java-cef-{java-cef-commit}";
    private static final String JAVA_CEF_DOWNLOAD_URL = "{host}/{java-cef-tag}/{platform}.tar.gz";
    private static final String JAVA_CEF_CHECKSUM_DOWNLOAD_URL = "{host}/{java-cef-tag}/{platform}.tar.gz.sha256";
    private static final int DOWNLOAD_BUFFER_SIZE_BYTES = 16 * 1024;
    private static final int EXTRACT_BUFFER_SIZE_BYTES = 16 * 1024;
    private static final Pattern SHA256_TOKEN_PATTERN = Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");

    public enum MirrorPolicy {
        OFFICIAL_ONLY,
        PREFER_CONFIGURED,
        CONFIGURED_ONLY
    }

    public record DownloadPolicy(
            MirrorPolicy mirrorPolicy,
            boolean enforceChecksums,
            int connectTimeoutMs,
            int readTimeoutMs,
            long maxArchiveBytes,
            long maxChecksumBytes,
            long maxExtractedBytes
    ) {
        public DownloadPolicy {
            mirrorPolicy = mirrorPolicy == null ? MirrorPolicy.OFFICIAL_ONLY : mirrorPolicy;
            connectTimeoutMs = Math.max(1000, connectTimeoutMs);
            readTimeoutMs = Math.max(1000, readTimeoutMs);
            maxArchiveBytes = Math.max(1_048_576L, maxArchiveBytes);
            maxChecksumBytes = Math.max(512L, maxChecksumBytes);
            maxExtractedBytes = Math.max(1_048_576L, maxExtractedBytes);
        }

        public static DownloadPolicy defaults() {
            return new DownloadPolicy(
                    MirrorPolicy.OFFICIAL_ONLY,
                    true,
                    15_000,
                    60_000,
                    750L * 1024L * 1024L,
                    64L * 1024L,
                    2_000L * 1024L * 1024L
            );
        }
    }

    private final String host;
    private final String javaCefCommitHash;
    private final MCEFPlatform platform;
    private final DownloadPolicy downloadPolicy;

    public MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform) {
        this(host, javaCefCommitHash, platform, DownloadPolicy.defaults());
    }

    public MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform, DownloadPolicy downloadPolicy) {
        this.host = host;
        this.javaCefCommitHash = javaCefCommitHash;
        this.platform = platform;
        this.downloadPolicy = downloadPolicy == null ? DownloadPolicy.defaults() : downloadPolicy;
    }

    public String getHost() {
        return host;
    }

    public String getJavaCefDownloadUrl() {
        return formatURL(JAVA_CEF_DOWNLOAD_URL, resolveMirrorCandidates().get(0));
    }

    public String getJavaCefChecksumDownloadUrl() {
        return formatURL(JAVA_CEF_CHECKSUM_DOWNLOAD_URL, resolveMirrorCandidates().get(0));
    }

    public DownloadPolicy getDownloadPolicy() {
        return downloadPolicy;
    }

    private String formatURL(String url, String downloadHost) {
        String javaCefTag = JAVA_CEF_RELEASE_TAG_TEMPLATE.replace("{java-cef-commit}", javaCefCommitHash);
        return url
                .replace("{host}", downloadHost)
                .replace("{java-cef-tag}", javaCefTag)
                .replace("{java-cef-commit}", javaCefCommitHash)
                .replace("{platform}", platform.getNormalizedName());
    }

    public void downloadJavaCefBuild() throws IOException {
        File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
        MCEFDownloadListener.INSTANCE.setTask("Downloading Chromium Embedded Framework");
        downloadFileFromMirrors(JAVA_CEF_DOWNLOAD_URL, new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz"), downloadPolicy.maxArchiveBytes());
    }

    /**
     * @return true if the jcef build checksum file matches the remote checksum file (for the {@link MCEFDownloader#javaCefCommitHash}),
     * false if the jcef build checksum file did not exist or did not match; this means we should redownload JCEF
     */
    public boolean downloadJavaCefChecksum() throws IOException {
        File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
        File jcefBuildHashFileTemp = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz.sha256.temp");
        File jcefBuildHashFile = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz.sha256");

        MCEFDownloadListener.INSTANCE.setTask("Downloading Checksum");
        downloadFileFromMirrors(JAVA_CEF_CHECKSUM_DOWNLOAD_URL, jcefBuildHashFileTemp, downloadPolicy.maxChecksumBytes());

        String downloadedChecksum = readChecksum(jcefBuildHashFileTemp, downloadPolicy.enforceChecksums());
        if (downloadedChecksum == null && downloadPolicy.enforceChecksums()) {
            throw new IOException("Missing or invalid checksum in " + jcefBuildHashFileTemp.getName());
        }

        if (jcefBuildHashFile.exists()) {
            String existingChecksum = readChecksum(jcefBuildHashFile, false);
            boolean sameContent = downloadedChecksum != null && downloadedChecksum.equals(existingChecksum);
            if (sameContent) {
                Files.deleteIfExists(jcefBuildHashFileTemp.toPath());
                return true;
            } else {
                LOGGER.warn("JCEF Hash does not match.");
            }
        } else {
            LOGGER.info("Local JCEF hash file does not exist.");
        }

        try {
            Files.move(jcefBuildHashFileTemp.toPath(), jcefBuildHashFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(jcefBuildHashFileTemp.toPath(), jcefBuildHashFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        return false;
    }

    public void extractJavaCefBuild(boolean delete) throws IOException {
        File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
        File tarGzArchive = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz");
        validateJavaCefBuildChecksum(tarGzArchive);
        extractTarGz(tarGzArchive, mcefLibrariesPath);
        if (delete) {
            Files.deleteIfExists(tarGzArchive.toPath());
        }
    }

    private void downloadFileFromMirrors(String urlTemplate, File outputFile, long maxBytes) throws IOException {
        List<String> mirrors = resolveMirrorCandidates();
        IOException lastException = null;

        for (String mirror : mirrors) {
            String url = formatURL(urlTemplate, mirror);
            try {
                downloadFile(url, outputFile, maxBytes);
                return;
            } catch (IOException e) {
                lastException = e;
                LOGGER.warn("Download failed from {}: {}", mirror, e.getMessage());
            }
        }

        throw new IOException(
                "Failed to download " + outputFile.getName() + " from configured mirrors. " +
                        "Ensure mcef_resources release tag " + JAVA_CEF_RELEASE_TAG_TEMPLATE.replace("{java-cef-commit}", javaCefCommitHash) + " contains assets " +
                        platform.getNormalizedName() + ".tar.gz and " +
                        platform.getNormalizedName() + ".tar.gz.sha256",
                lastException
        );
    }

    private void downloadFile(String urlString, File outputFile, long maxBytes) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File tempOutput = new File(outputFile.getAbsolutePath() + ".part");
        long readBytes = 0L;
        HttpURLConnection urlConnection = null;

        try {
            LOGGER.info(urlString + " -> " + outputFile.getCanonicalPath());

            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(downloadPolicy.connectTimeoutMs());
            urlConnection.setReadTimeout(downloadPolicy.readTimeoutMs());
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.connect();

            int responseCode = urlConnection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Unexpected HTTP status " + responseCode);
            }

            long fileSize = urlConnection.getContentLengthLong();
            if (fileSize > maxBytes) {
                throw new IOException("Remote file size exceeds configured limit");
            }

            try (BufferedInputStream inputStream = new BufferedInputStream(urlConnection.getInputStream(), DOWNLOAD_BUFFER_SIZE_BYTES);
                 BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempOutput), DOWNLOAD_BUFFER_SIZE_BYTES)) {
                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE_BYTES];
                int count;
                while ((count = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, count);
                    readBytes += count;
                    if (readBytes > maxBytes) {
                        throw new IOException("Downloaded file size exceeded configured limit");
                    }
                    long progressTotal = fileSize > 0 ? fileSize : maxBytes;
                    if (progressTotal > 0) {
                        float percentComplete = Math.min(0.99f, (float) readBytes / progressTotal);
                        MCEFDownloadListener.INSTANCE.setProgress(percentComplete);
                    }
                }
            }

            try {
                Files.move(tempOutput.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tempOutput.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            MCEFDownloadListener.INSTANCE.setProgress(1.0f);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempOutput.toPath());
            } catch (IOException cleanupException) {
                e.addSuppressed(cleanupException);
            }
            throw new IOException("Failed to download " + urlString + ": " + e.getMessage(), e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private void validateJavaCefBuildChecksum(File tarGzArchive) throws IOException {
        File mcefLibrariesPath = tarGzArchive.getParentFile();
        File checksumFile = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz.sha256");
        if (!checksumFile.exists()) {
            if (downloadPolicy.enforceChecksums()) {
                throw new IOException("Missing checksum file for JCEF archive");
            }
            return;
        }

        String expectedChecksum = readChecksum(checksumFile, downloadPolicy.enforceChecksums());
        if (expectedChecksum == null) {
            if (downloadPolicy.enforceChecksums()) {
                throw new IOException("Checksum enforcement is enabled but checksum is invalid");
            }
            return;
        }

        String actualChecksum = calculateSha256(tarGzArchive, downloadPolicy.maxArchiveBytes());
        if (!expectedChecksum.equals(actualChecksum)) {
            throw new IOException("Checksum mismatch for downloaded JCEF archive");
        }
    }

    private String calculateSha256(File file, long maxBytes) throws IOException {
        if (!file.exists()) {
            throw new IOException("Missing file for checksum: " + file.getAbsolutePath());
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }

        long bytesRead = 0L;
        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file), DOWNLOAD_BUFFER_SIZE_BYTES)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE_BYTES];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                bytesRead += count;
                if (bytesRead > maxBytes) {
                    throw new IOException("Checksum target exceeds configured size limit");
                }
                digest.update(buffer, 0, count);
            }
        }

        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private String readChecksum(File checksumFile, boolean strict) throws IOException {
        if (!checksumFile.exists()) {
            return null;
        }
        long checksumSize = checksumFile.length();
        if (checksumSize <= 0 || checksumSize > downloadPolicy.maxChecksumBytes()) {
            if (strict) {
                throw new IOException("Checksum file size out of bounds: " + checksumFile.getName());
            }
            LOGGER.warn("Checksum file size out of bounds: {}", checksumFile.getName());
            return null;
        }

        String content = Files.readString(checksumFile.toPath(), StandardCharsets.UTF_8);
        String checksum = extractSha256Token(content);
        if (checksum == null) {
            if (strict) {
                throw new IOException("Checksum file does not contain a valid SHA-256 digest: " + checksumFile.getName());
            }
            return null;
        }
        return checksum;
    }

    private static String extractSha256Token(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = SHA256_TOKEN_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeMirror(String mirror) {
        if (mirror == null || mirror.isBlank()) {
            return null;
        }
        String trimmed = stripTrailingSlash(mirror.trim());
        try {
            URI uri = new URI(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return trimmed;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private List<String> resolveMirrorCandidates() {
        String configuredMirror = normalizeMirror(sanitizeMirror(host));
        Set<String> uniqueMirrors = new LinkedHashSet<>();

        switch (downloadPolicy.mirrorPolicy()) {
            case OFFICIAL_ONLY -> uniqueMirrors.add(OFFICIAL_MIRROR);
            case PREFER_CONFIGURED -> {
                if (configuredMirror != null) {
                    uniqueMirrors.add(configuredMirror);
                }
                uniqueMirrors.add(OFFICIAL_MIRROR);
            }
            case CONFIGURED_ONLY -> {
                if (configuredMirror == null) {
                    throw new IllegalStateException("Configured mirror is invalid for CONFIGURED_ONLY policy");
                }
                uniqueMirrors.add(configuredMirror);
            }
        }

        if (uniqueMirrors.isEmpty()) {
            uniqueMirrors.add(OFFICIAL_MIRROR);
        }
        return new ArrayList<>(uniqueMirrors);
    }

    private String normalizeMirror(String mirror) {
        if (mirror == null) {
            return null;
        }
        if (stripTrailingSlash(LEGACY_OFFICIAL_MIRROR).equalsIgnoreCase(mirror)) {
            LOGGER.warn("Legacy MCEF mirror {} is no longer supported; using {}.", LEGACY_OFFICIAL_MIRROR, OFFICIAL_MIRROR);
            return OFFICIAL_MIRROR;
        }
        return mirror;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    private void extractTarGz(File tarGzFile, File outputDirectory) throws IOException {
        MCEFDownloadListener.INSTANCE.setTask("Extracting");

        if (!tarGzFile.exists()) {
            throw new IOException("Missing JCEF archive " + tarGzFile.getAbsolutePath());
        }
        outputDirectory.mkdirs();
        Path outputRoot = outputDirectory.toPath().toAbsolutePath().normalize();
        Path realOutputRoot = outputRoot.toRealPath();

        long expectedExtractedSize = scanArchive(tarGzFile);
        if (expectedExtractedSize <= 0L) {
            expectedExtractedSize = 1L;
        }

        long totalBytesRead = 0L;
        byte[] buffer = new byte[EXTRACT_BUFFER_SIZE_BYTES];
        try (TarArchiveInputStream tarInput = openTarArchive(tarGzFile)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                if (!isExtractableTarEntry(entry)) {
                    continue;
                }

                Path outputPath = resolveOutputPath(realOutputRoot, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }

                Path parent = outputPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                    Path realParent = parent.toRealPath();
                    if (!realParent.startsWith(realOutputRoot)) {
                        throw new IOException("Archive entry escaped target directory: " + entry.getName());
                    }
                }

                if (Files.exists(outputPath) && Files.isSymbolicLink(outputPath)) {
                    throw new IOException("Refusing to overwrite symlink from archive: " + entry.getName());
                }

                try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputPath.toFile()), EXTRACT_BUFFER_SIZE_BYTES)) {
                    int bytesRead;
                    while ((bytesRead = tarInput.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                        if (totalBytesRead > downloadPolicy.maxExtractedBytes()) {
                            throw new IOException("Extracted size exceeded configured limit");
                        }
                        float percentComplete = Math.min(0.99f, (float) totalBytesRead / expectedExtractedSize);
                        MCEFDownloadListener.INSTANCE.setProgress(percentComplete);
                    }
                }
            }
        }

        MCEFDownloadListener.INSTANCE.setProgress(1.0f);
    }

    private long scanArchive(File tarGzFile) throws IOException {
        long totalSize = 0L;
        try (TarArchiveInputStream tarInput = openTarArchive(tarGzFile)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                if (!isExtractableTarEntry(entry)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                long entrySize = Math.max(0L, entry.getSize());
                totalSize += entrySize;
                if (totalSize > downloadPolicy.maxExtractedBytes()) {
                    throw new IOException("Archive exceeds configured extracted size limit");
                }
            }
        }
        return totalSize;
    }

    private static TarArchiveInputStream openTarArchive(File tarGzFile) throws IOException {
        return new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(tarGzFile), EXTRACT_BUFFER_SIZE_BYTES)));
    }

    private static boolean isExtractableTarEntry(TarArchiveEntry entry) throws IOException {
        if (entry.isSymbolicLink() || entry.isLink()) {
            throw new IOException("Unsupported archive entry type: " + entry.getName());
        }
        return entry.isDirectory() || entry.isFile();
    }

    private static Path resolveOutputPath(Path outputRoot, String entryName) throws IOException {
        String normalizedEntryName = entryName.replace('\\', '/');
        Path resolved = outputRoot.resolve(normalizedEntryName).normalize();
        if (!resolved.startsWith(outputRoot)) {
            throw new IOException("Archive entry escaped target directory: " + entryName);
        }
        return resolved;
    }
}
