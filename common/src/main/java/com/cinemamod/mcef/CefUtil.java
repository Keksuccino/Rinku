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

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * This class mostly just interacts with org.cef.* for internal use in {@link MCEF}
 */
final class CefUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");

    private CefUtil() {
    }

    private static boolean init;
    private static CefApp cefAppInstance;
    private static CefClient cefClientInstance;

    private static void setUnixExecutable(File file) {
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);

        try {
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (IOException e) {
            LOGGER.error("Failed to set " + file + " as executable.", e);
        }
    }

    static boolean init() {
        MCEFPlatform platform = MCEFPlatform.getPlatform();

        // Ensure binaries are executable
        if (platform.isLinux()) {
            File jcefHelperFile = new File(System.getProperty("mcef.libraries.path"), platform.getNormalizedName() + "/jcef_helper");
            setUnixExecutable(jcefHelperFile);
        } else if (platform.isMacOS()) {
            File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
            File jcefHelperFile = new File(mcefLibrariesPath, platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper");
            File jcefHelperGPUFile = new File(mcefLibrariesPath, platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)");
            File jcefHelperPluginFile = new File(mcefLibrariesPath, platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/jcef Helper (Plugin).app/Contents/MacOS/jcef Helper (Plugin)");
            File jcefHelperRendererFile = new File(mcefLibrariesPath, platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)");
            setUnixExecutable(jcefHelperFile);
            setUnixExecutable(jcefHelperGPUFile);
            setUnixExecutable(jcefHelperPluginFile);
            setUnixExecutable(jcefHelperRendererFile);
        }

        MCEFSettings settings = MCEF.getSettings();
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
            Path cachePath = resolvePersistentCefCachePath_MCEF().toAbsolutePath();
            try {
                Files.createDirectories(cachePath);
                // jcef wants an absolute path, so make sure it's absolute.
                cefSettings.cache_path = cachePath.toString();
                cefSettings.persist_session_cookies = true;
                LOGGER.info("Using persistent MCEF browser data directory: {}", cachePath);
            } catch (IOException e) {
                LOGGER.warn("Failed to create persistent MCEF cache directory {}. Falling back to non-persistent browser data.", cachePath, e);
            }
        }
        cefSettings.log_severity = settings.getNativeCefLogSeverity();
        cefSettings.background_color = cefSettings.new ColorType(0, 255, 255, 255);
        // Set the user agent if there's one defined in MCEFSettings
        if (settings.getUserAgent() != null) {
            cefSettings.user_agent = settings.getUserAgent();
        } else {
            // If there is no custom defined user agent, set a user agent product.
            // Work around for Google sign-in "This browser or app may not be secure."
            cefSettings.user_agent_product = "MCEF/2";
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

    private static Path resolvePersistentCefCachePath_MCEF() {
        return resolvePersistentDataRoot_MCEF().resolve("cef-cache");
    }

    private static Path resolvePersistentDataRoot_MCEF() {
        MCEFPlatform platform = MCEFPlatform.getPlatform();
        String userHome = System.getProperty("user.home", ".");

        if (platform.isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData).resolve("MCEF");
            }

            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData).resolve("MCEF");
            }

            return Path.of(userHome, "AppData", "Local", "MCEF");
        }

        if (platform.isMacOS()) {
            return Path.of(userHome, "Library", "Application Support", "MCEF");
        }

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome).resolve("mcef");
        }

        return Path.of(userHome, ".local", "share", "mcef");
    }
}
