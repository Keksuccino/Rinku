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

package de.keksuccino.mcef;

import net.minecraft.client.Minecraft;
import org.cef.CefSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class MCEFSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");

    private static final Path PATH = Minecraft.getInstance().gameDirectory
            .toPath()
            .resolve("config")
            .resolve("mcef")
            .resolve("mcef.properties");
    private static final String DEFAULT_DOWNLOAD_MIRROR = MCEFDownloader.OFFICIAL_MIRROR;
    private static final MCEFDownloader.MirrorPolicy DEFAULT_DOWNLOAD_MIRROR_POLICY = MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY;
    private static final boolean DEFAULT_ENFORCE_DOWNLOAD_CHECKSUMS = true;
    private static final int DEFAULT_DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_DOWNLOAD_READ_TIMEOUT_MS = 60_000;
    private static final long DEFAULT_DOWNLOAD_MAX_ARCHIVE_BYTES = 750L * 1024L * 1024L;
    private static final long DEFAULT_DOWNLOAD_MAX_CHECKSUM_BYTES = 64L * 1024L;
    private static final long DEFAULT_DOWNLOAD_MAX_EXTRACTED_BYTES = 2_000L * 1024L * 1024L;
    private static final boolean DEFAULT_CEF_DISABLE_WEB_SECURITY = true;
    private static final boolean DEFAULT_CEF_ENABLE_WIDEVINE_CDM = true;
    private static final CefSettings.LogSeverity DEFAULT_NATIVE_CEF_LOG_SEVERITY = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
    private static final CefSettings.LogSeverity DEFAULT_CONSOLE_LOG_FORWARDING_MIN_SEVERITY = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
    private static final boolean DEFAULT_BROWSER_PRELOAD_ENABLED = true;
    private static final int DEFAULT_BROWSER_PRELOAD_TRANSPARENT_POOL_SIZE = 1;
    private static final int DEFAULT_BROWSER_PRELOAD_OPAQUE_POOL_SIZE = 1;

    private boolean skipDownload;
    private String downloadMirror;
    private MCEFDownloader.MirrorPolicy downloadMirrorPolicy;
    private boolean enforceDownloadChecksums;
    private int downloadConnectTimeoutMs;
    private int downloadReadTimeoutMs;
    private long downloadMaxArchiveBytes;
    private long downloadMaxChecksumBytes;
    private long downloadMaxExtractedBytes;
    private String userAgent;
    private boolean useCache;
    private boolean cefDisableWebSecurity;
    private boolean cefEnableWidevineCdm;
    private CefSettings.LogSeverity nativeCefLogSeverity;
    private CefSettings.LogSeverity consoleLogForwardingMinSeverity;
    private boolean browserPreloadEnabled;
    private int browserPreloadTransparentPoolSize;
    private int browserPreloadOpaquePoolSize;

    public MCEFSettings() {
        resetDefaults();
    }

    private void resetDefaults() {
        skipDownload = false;
        downloadMirror = DEFAULT_DOWNLOAD_MIRROR;
        downloadMirrorPolicy = DEFAULT_DOWNLOAD_MIRROR_POLICY;
        enforceDownloadChecksums = DEFAULT_ENFORCE_DOWNLOAD_CHECKSUMS;
        downloadConnectTimeoutMs = DEFAULT_DOWNLOAD_CONNECT_TIMEOUT_MS;
        downloadReadTimeoutMs = DEFAULT_DOWNLOAD_READ_TIMEOUT_MS;
        downloadMaxArchiveBytes = DEFAULT_DOWNLOAD_MAX_ARCHIVE_BYTES;
        downloadMaxChecksumBytes = DEFAULT_DOWNLOAD_MAX_CHECKSUM_BYTES;
        downloadMaxExtractedBytes = DEFAULT_DOWNLOAD_MAX_EXTRACTED_BYTES;
        userAgent = null;
        useCache = true;
        cefDisableWebSecurity = DEFAULT_CEF_DISABLE_WEB_SECURITY;
        cefEnableWidevineCdm = DEFAULT_CEF_ENABLE_WIDEVINE_CDM;
        nativeCefLogSeverity = DEFAULT_NATIVE_CEF_LOG_SEVERITY;
        consoleLogForwardingMinSeverity = DEFAULT_CONSOLE_LOG_FORWARDING_MIN_SEVERITY;
        browserPreloadEnabled = DEFAULT_BROWSER_PRELOAD_ENABLED;
        browserPreloadTransparentPoolSize = DEFAULT_BROWSER_PRELOAD_TRANSPARENT_POOL_SIZE;
        browserPreloadOpaquePoolSize = DEFAULT_BROWSER_PRELOAD_OPAQUE_POOL_SIZE;
    }

    public boolean isSkipDownload() {
        return skipDownload;
    }

    public void setSkipDownload(boolean skipDownload) {
        this.skipDownload = skipDownload;
        saveAsync();
    }

    public String getDownloadMirror() {
        return downloadMirror;
    }

    public void setDownloadMirror(String downloadMirror) {
        this.downloadMirror = parseMirror(downloadMirror, DEFAULT_DOWNLOAD_MIRROR, "download-mirror", downloadMirrorPolicy == MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY);
        saveAsync();
    }

    public MCEFDownloader.MirrorPolicy getDownloadMirrorPolicy() {
        return downloadMirrorPolicy;
    }

    public void setDownloadMirrorPolicy(MCEFDownloader.MirrorPolicy downloadMirrorPolicy) {
        this.downloadMirrorPolicy = downloadMirrorPolicy == null ? DEFAULT_DOWNLOAD_MIRROR_POLICY : downloadMirrorPolicy;
        saveAsync();
    }

    public boolean isEnforceDownloadChecksums() {
        return enforceDownloadChecksums;
    }

    public void setEnforceDownloadChecksums(boolean enforceDownloadChecksums) {
        this.enforceDownloadChecksums = enforceDownloadChecksums;
        saveAsync();
    }

    public int getDownloadConnectTimeoutMs() {
        return downloadConnectTimeoutMs;
    }

    public void setDownloadConnectTimeoutMs(int downloadConnectTimeoutMs) {
        this.downloadConnectTimeoutMs = clampInt(downloadConnectTimeoutMs, 1000, 300_000, DEFAULT_DOWNLOAD_CONNECT_TIMEOUT_MS, "download-connect-timeout-ms");
        saveAsync();
    }

    public int getDownloadReadTimeoutMs() {
        return downloadReadTimeoutMs;
    }

    public void setDownloadReadTimeoutMs(int downloadReadTimeoutMs) {
        this.downloadReadTimeoutMs = clampInt(downloadReadTimeoutMs, 1000, 300_000, DEFAULT_DOWNLOAD_READ_TIMEOUT_MS, "download-read-timeout-ms");
        saveAsync();
    }

    public long getDownloadMaxArchiveBytes() {
        return downloadMaxArchiveBytes;
    }

    public void setDownloadMaxArchiveBytes(long downloadMaxArchiveBytes) {
        this.downloadMaxArchiveBytes = clampLong(downloadMaxArchiveBytes, 1_048_576L, 5_000L * 1024L * 1024L, DEFAULT_DOWNLOAD_MAX_ARCHIVE_BYTES, "download-max-archive-bytes");
        saveAsync();
    }

    public long getDownloadMaxChecksumBytes() {
        return downloadMaxChecksumBytes;
    }

    public void setDownloadMaxChecksumBytes(long downloadMaxChecksumBytes) {
        this.downloadMaxChecksumBytes = clampLong(downloadMaxChecksumBytes, 512L, 1_048_576L, DEFAULT_DOWNLOAD_MAX_CHECKSUM_BYTES, "download-max-checksum-bytes");
        saveAsync();
    }

    public long getDownloadMaxExtractedBytes() {
        return downloadMaxExtractedBytes;
    }

    public void setDownloadMaxExtractedBytes(long downloadMaxExtractedBytes) {
        this.downloadMaxExtractedBytes = clampLong(downloadMaxExtractedBytes, 1_048_576L, 10_000L * 1024L * 1024L, DEFAULT_DOWNLOAD_MAX_EXTRACTED_BYTES, "download-max-extracted-bytes");
        saveAsync();
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = parseUserAgent(userAgent);
        saveAsync();
    }

    public boolean isUsingCache() {
        return useCache;
    }

    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
        saveAsync();
    }

    public boolean isDisableWebSecurity() {
        return cefDisableWebSecurity;
    }

    public void setDisableWebSecurity(boolean cefDisableWebSecurity) {
        this.cefDisableWebSecurity = cefDisableWebSecurity;
        saveAsync();
    }

    public boolean isEnableWidevineCdm() {
        return cefEnableWidevineCdm;
    }

    public void setEnableWidevineCdm(boolean cefEnableWidevineCdm) {
        this.cefEnableWidevineCdm = cefEnableWidevineCdm;
        saveAsync();
    }

    public CefSettings.LogSeverity getNativeCefLogSeverity() {
        return nativeCefLogSeverity;
    }

    public void setNativeCefLogSeverity(CefSettings.LogSeverity nativeCefLogSeverity) {
        this.nativeCefLogSeverity = nativeCefLogSeverity == null
                ? DEFAULT_NATIVE_CEF_LOG_SEVERITY
                : nativeCefLogSeverity;
        saveAsync();
    }

    public CefSettings.LogSeverity getConsoleLogForwardingMinSeverity() {
        return consoleLogForwardingMinSeverity;
    }

    public void setConsoleLogForwardingMinSeverity(CefSettings.LogSeverity consoleLogForwardingMinSeverity) {
        this.consoleLogForwardingMinSeverity = consoleLogForwardingMinSeverity == null
                ? DEFAULT_CONSOLE_LOG_FORWARDING_MIN_SEVERITY
                : consoleLogForwardingMinSeverity;
        saveAsync();
    }

    public boolean isBrowserPreloadEnabled() {
        return browserPreloadEnabled;
    }

    public void setBrowserPreloadEnabled(boolean browserPreloadEnabled) {
        this.browserPreloadEnabled = browserPreloadEnabled;
        saveAsync();
        MCEF.refreshPreloadedBrowserPool();
    }

    public int getBrowserPreloadTransparentPoolSize() {
        return browserPreloadTransparentPoolSize;
    }

    public void setBrowserPreloadTransparentPoolSize(int browserPreloadTransparentPoolSize) {
        this.browserPreloadTransparentPoolSize = clampInt(
                browserPreloadTransparentPoolSize,
                0,
                4,
                DEFAULT_BROWSER_PRELOAD_TRANSPARENT_POOL_SIZE,
                "browser-preload-transparent-pool-size"
        );
        saveAsync();
        MCEF.refreshPreloadedBrowserPool();
    }

    public int getBrowserPreloadOpaquePoolSize() {
        return browserPreloadOpaquePoolSize;
    }

    public void setBrowserPreloadOpaquePoolSize(int browserPreloadOpaquePoolSize) {
        this.browserPreloadOpaquePoolSize = clampInt(
                browserPreloadOpaquePoolSize,
                0,
                4,
                DEFAULT_BROWSER_PRELOAD_OPAQUE_POOL_SIZE,
                "browser-preload-opaque-pool-size"
        );
        saveAsync();
        MCEF.refreshPreloadedBrowserPool();
    }

    public MCEFDownloader.DownloadPolicy createDownloadPolicy() {
        return new MCEFDownloader.DownloadPolicy(
                downloadMirrorPolicy,
                enforceDownloadChecksums,
                downloadConnectTimeoutMs,
                downloadReadTimeoutMs,
                downloadMaxArchiveBytes,
                downloadMaxChecksumBytes,
                downloadMaxExtractedBytes
        );
    }

    public void saveAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                save();
            } catch (IOException e) {
                LOGGER.error("Failed to save MCEF settings", e);
            }
        });
    }

    public void save() throws IOException {
        File file = PATH.toFile();

        file.getParentFile().mkdirs();

        if (!file.exists()) {
            file.createNewFile();
        }

        Properties properties = new Properties();
        properties.setProperty("skip-download", String.valueOf(skipDownload));
        properties.setProperty("download-mirror", downloadMirror == null ? "" : downloadMirror);
        properties.setProperty("download-mirror-policy", downloadMirrorPolicy.name());
        properties.setProperty("enforce-download-checksums", String.valueOf(enforceDownloadChecksums));
        properties.setProperty("download-connect-timeout-ms", String.valueOf(downloadConnectTimeoutMs));
        properties.setProperty("download-read-timeout-ms", String.valueOf(downloadReadTimeoutMs));
        properties.setProperty("download-max-archive-bytes", String.valueOf(downloadMaxArchiveBytes));
        properties.setProperty("download-max-checksum-bytes", String.valueOf(downloadMaxChecksumBytes));
        properties.setProperty("download-max-extracted-bytes", String.valueOf(downloadMaxExtractedBytes));
        properties.setProperty("user-agent", userAgent == null ? "" : userAgent);
        properties.setProperty("use-cache", String.valueOf(useCache));
        properties.setProperty("cef-disable-web-security", String.valueOf(cefDisableWebSecurity));
        properties.setProperty("cef-enable-widevine-cdm", String.valueOf(cefEnableWidevineCdm));
        properties.setProperty("cef-native-log-severity", nativeCefLogSeverity.name());
        properties.setProperty("cef-console-log-forwarding-min-severity", consoleLogForwardingMinSeverity.name());
        properties.setProperty("browser-preload-enabled", String.valueOf(browserPreloadEnabled));
        properties.setProperty("browser-preload-transparent-pool-size", String.valueOf(browserPreloadTransparentPoolSize));
        properties.setProperty("browser-preload-opaque-pool-size", String.valueOf(browserPreloadOpaquePoolSize));

        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, null);
        }
    }

    public void load() throws IOException {
        File file = PATH.toFile();

        if (!file.exists()) {
            save();
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }

        resetDefaults();

        skipDownload = parseBoolean(properties, "skip-download", skipDownload);
        downloadMirrorPolicy = parseMirrorPolicy(properties.getProperty("download-mirror-policy"), downloadMirrorPolicy);
        downloadMirror = parseMirror(properties.getProperty("download-mirror"), downloadMirror, "download-mirror", downloadMirrorPolicy == MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY);
        enforceDownloadChecksums = parseBoolean(properties, "enforce-download-checksums", enforceDownloadChecksums);
        downloadConnectTimeoutMs = parseInt(properties, "download-connect-timeout-ms", downloadConnectTimeoutMs, 1000, 300_000);
        downloadReadTimeoutMs = parseInt(properties, "download-read-timeout-ms", downloadReadTimeoutMs, 1000, 300_000);
        downloadMaxArchiveBytes = parseLong(properties, "download-max-archive-bytes", downloadMaxArchiveBytes, 1_048_576L, 5_000L * 1024L * 1024L);
        downloadMaxChecksumBytes = parseLong(properties, "download-max-checksum-bytes", downloadMaxChecksumBytes, 512L, 1_048_576L);
        downloadMaxExtractedBytes = parseLong(properties, "download-max-extracted-bytes", downloadMaxExtractedBytes, 1_048_576L, 10_000L * 1024L * 1024L);
        userAgent = parseUserAgent(properties.getProperty("user-agent"));
        useCache = parseBoolean(properties, "use-cache", useCache);
        cefDisableWebSecurity = parseBoolean(properties, "cef-disable-web-security", cefDisableWebSecurity);
        cefEnableWidevineCdm = parseBoolean(properties, "cef-enable-widevine-cdm", cefEnableWidevineCdm);
        nativeCefLogSeverity = parseLogSeverity(
                properties.getProperty("cef-native-log-severity"),
                nativeCefLogSeverity,
                "cef-native-log-severity"
        );
        consoleLogForwardingMinSeverity = parseLogSeverity(
                properties.getProperty("cef-console-log-forwarding-min-severity"),
                consoleLogForwardingMinSeverity,
                "cef-console-log-forwarding-min-severity"
        );
        browserPreloadEnabled = parseBoolean(properties, "browser-preload-enabled", browserPreloadEnabled);
        browserPreloadTransparentPoolSize = parseInt(
                properties,
                "browser-preload-transparent-pool-size",
                browserPreloadTransparentPoolSize,
                0,
                4
        );
        browserPreloadOpaquePoolSize = parseInt(
                properties,
                "browser-preload-opaque-pool-size",
                browserPreloadOpaquePoolSize,
                0,
                4
        );
    }

    private static MCEFDownloader.MirrorPolicy parseMirrorPolicy(String raw, MCEFDownloader.MirrorPolicy fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return MCEFDownloader.MirrorPolicy.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid mcef.properties value for download-mirror-policy: {}", raw);
            return fallback;
        }
    }

    private static CefSettings.LogSeverity parseLogSeverity(String raw, CefSettings.LogSeverity fallback, String key) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("OFF".equals(normalized) || "NONE".equals(normalized)) {
            normalized = "LOGSEVERITY_DISABLE";
        } else if (!normalized.startsWith("LOGSEVERITY_")) {
            normalized = "LOGSEVERITY_" + normalized;
        }

        try {
            return CefSettings.LogSeverity.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid mcef.properties value for {}: {}", key, raw);
            return fallback;
        }
    }

    private static boolean parseBoolean(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> {
                LOGGER.warn("Invalid mcef.properties value for {}: {}", key, raw);
                yield fallback;
            }
        };
    }

    private static int parseInt(Properties properties, String key, int fallback, int min, int max) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return clampInt(Integer.parseInt(raw.trim()), min, max, fallback, key);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid mcef.properties value for {}: {}", key, raw);
            return fallback;
        }
    }

    private static long parseLong(Properties properties, String key, long fallback, long min, long max) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return clampLong(Long.parseLong(raw.trim()), min, max, fallback, key);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid mcef.properties value for {}: {}", key, raw);
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max, int fallback, String key) {
        if (value < min || value > max) {
            LOGGER.warn("Out of range mcef.properties value for {}: {}", key, value);
            return fallback;
        }
        return value;
    }

    private static long clampLong(long value, long min, long max, long fallback, String key) {
        if (value < min || value > max) {
            LOGGER.warn("Out of range mcef.properties value for {}: {}", key, value);
            return fallback;
        }
        return value;
    }

    private static String parseMirror(String value, String fallback, String key, boolean requireConfigured) {
        if (value == null || value.isBlank()) {
            if (requireConfigured) {
                LOGGER.warn("Missing mcef.properties value for {}; CONFIGURED_ONLY downloads will remain disabled", key);
                return null;
            }
            return fallback;
        }

        String normalizedOfficialMirror = MCEFDownloader.normalizeOfficialMirror(value.trim());
        if (!normalizedOfficialMirror.equals(value.trim())) {
            LOGGER.warn("Migrating the former default JCEF download mirror to the current official mirror");
        }

        try {
            return MCEFDownloadMirror.parse(normalizedOfficialMirror).externalForm();
        } catch (IllegalArgumentException failure) {
            // Never include the rejected value or exception cause in logs. Mirror paths are not
            // allowed to carry secrets, but malformed legacy configuration may still contain them.
            LOGGER.warn("Invalid mcef.properties value for {}; the unsafe mirror was ignored", key);
            return requireConfigured ? null : fallback;
        }
    }

    private static String parseUserAgent(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

}
