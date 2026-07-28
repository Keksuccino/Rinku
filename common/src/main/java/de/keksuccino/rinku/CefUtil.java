package de.keksuccino.rinku;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * This class mostly just interacts with org.cef.* for internal use in {@link Rinku}.
 */
final class CefUtil {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean init;
    private static CefApp cefAppInstance;
    private static CefClient cefClientInstance;

    private CefUtil() {}

    static void addUnixExecutePermissions(Path file) throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posixView == null) {
            addPortableExecutePermissions(file);
            return;
        }

        Set<PosixFilePermission> existing = posixView.readAttributes().permissions();
        Set<PosixFilePermission> updated = EnumSet.noneOf(PosixFilePermission.class);
        updated.addAll(existing);
        updated.add(PosixFilePermission.OWNER_EXECUTE);
        if (existing.contains(PosixFilePermission.GROUP_READ)) {
            updated.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if (existing.contains(PosixFilePermission.OTHERS_READ)) {
            updated.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        posixView.setPermissions(updated);
    }

    static void addPortableExecutePermissions(Path file) throws IOException {
        boolean changed;
        try {
            changed = file.toFile().setExecutable(true, false);
        } catch (SecurityException | UnsupportedOperationException failure) {
            throw new IOException("Could not set executable permissions on " + file, failure);
        }
        if (!Files.isExecutable(file)) {
            throw new IOException("Could not set executable permissions on " + file + "; File.setExecutable returned " + changed);
        }
    }

    static List<Path> unixExecutablePaths(Path installation, OSPlatform platform) {
        if (platform.isLinux()) {
            return List.of(installation.resolve("jcef_helper"), installation.resolve("chrome-sandbox"));
        }
        if (!platform.isMacOS()) {
            return List.of();
        }
        Path contents = installation.resolve("jcef_app.app/Contents");
        Path frameworks = contents.resolve("Frameworks");
        return List.of(contents.resolve("MacOS/JavaAppLauncher"), frameworks.resolve("Chromium Embedded Framework.framework/Chromium Embedded Framework"), frameworks.resolve("jcef Helper.app/Contents/MacOS/jcef Helper"), frameworks.resolve("jcef Helper (Alerts).app/Contents/MacOS/jcef Helper (Alerts)"), frameworks.resolve("jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)"), frameworks.resolve("jcef Helper (Plugin).app/Contents/MacOS/jcef Helper (Plugin)"), frameworks.resolve("jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)"));
    }

    private static void ensureUnixExecutables(Path installation, OSPlatform platform) {
        for (Path file : unixExecutablePaths(installation, platform)) {
            try {
                addUnixExecutePermissions(file);
            } catch (IOException e) {
                LOGGER.error("Failed to set " + file + " as executable.", e);
            }
        }
    }

    static boolean init() {
        OSPlatform platform = OSPlatform.getPlatform();
        String configuredJcefPath = System.getProperty("jcef.path");
        if (configuredJcefPath == null || configuredJcefPath.isBlank()) {
            LOGGER.error("JCEF installation path is unavailable; the downloader must finish before CEF initialization.");
            return false;
        }
        Path jcefInstallation = Path.of(configuredJcefPath);

        // Archive modes are canonicalized during extraction. This remains a non-destructive fallback
        // for installations copied by tools that discarded executable bits.
        ensureUnixExecutables(jcefInstallation, platform);

        RinkuSettings settings = Rinku.getSettings();
        ArrayList<String> cefSwitchesList = new ArrayList<>();
        cefSwitchesList.add("--autoplay-policy=no-user-gesture-required");
        if (settings.isDisableWebSecurity()) {
            cefSwitchesList.add("--disable-web-security");
        }
        if (settings.isEnableWidevineCdm()) {
            cefSwitchesList.add("--enable-widevine-cdm");
        }
        String[] cefSwitches = cefSwitchesList.toArray(String[]::new);

        if (!CefApp.startup(cefSwitches)) {
            return false;
        }

        CefSettings cefSettings = new CefSettings();
        cefSettings.windowless_rendering_enabled = true;
        if (settings.isUsingCache()) {
            Path cachePath = resolvePersistentCefCachePath().toAbsolutePath();
            try {
                Files.createDirectories(cachePath);
                // jcef wants an absolute path, so make sure it's absolute.
                cefSettings.cache_path = cachePath.toString();
                cefSettings.persist_session_cookies = true;
                LOGGER.info("Using persistent Rinku browser data directory: {}", cachePath);
            } catch (IOException e) {
                LOGGER.warn("Failed to create persistent Rinku cache directory {}. Falling back to non-persistent browser data.", cachePath, e);
            }
        }
        cefSettings.log_severity = settings.getNativeCefLogSeverity();
        cefSettings.background_color = cefSettings.new ColorType(0, 255, 255, 255);
        // Set the user agent if there's one defined in RinkuSettings
        if (settings.getUserAgent() != null) {
            cefSettings.user_agent = settings.getUserAgent();
        } else {
            // If there is no custom defined user agent, set a user agent product.
            // Work around for Google sign-in "This browser or app may not be secure."
            cefSettings.user_agent_product = "Rinku/2";
        }

        cefAppInstance = CefApp.getInstance(cefSwitches, cefSettings);
        cefClientInstance = cefAppInstance.createClient();

        return init = true;
    }

    static void shutdown() {
        if (isInit()) {
            init = false;
            cefClientInstance.dispose();
            cefAppInstance.dispose();
        }
    }

    static boolean isInit() {
        return init;
    }

    static CefApp getCefApp() {
        return cefAppInstance;
    }

    static CefClient getCefClient() {
        return cefClientInstance;
    }

    private static Path resolvePersistentCefCachePath() {
        return resolvePersistentDataRoot().resolve("cef-cache");
    }

    private static Path resolvePersistentDataRoot() {
        OSPlatform platform = OSPlatform.getPlatform();
        String userHome = System.getProperty("user.home", ".");

        if (platform.isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData).resolve("Rinku");
            }

            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData).resolve("Rinku");
            }

            return Path.of(userHome, "AppData", "Local", "Rinku");
        }

        if (platform.isMacOS()) {
            return Path.of(userHome, "Library", "Application Support", "Rinku");
        }

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome).resolve("rinku");
        }

        return Path.of(userHome, ".local", "share", "rinku");
    }

}
