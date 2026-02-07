package com.cinemamod.mcef.mixins;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFDownloader;
import com.cinemamod.mcef.MCEFPlatform;
import com.cinemamod.mcef.MCEFSettings;
import com.cinemamod.mcef.internal.MCEFDownloadListener;
import net.minecraft.client.resources.ClientPackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.File;
import java.io.IOException;

/**
 * <p>
 * mcef.libraries.path is where MCEF will store any required binaries. By default,
 * /path/to/.minecraft/mods/mcef-libraries.
 * <p>
 * jcef.path is the location of the standard java-cef bundle. By default,
 * /path/to/mcef-libraries/<normalized platform name> where normalized platform name comes from
 * {@link MCEFPlatform#getNormalizedName()}. This is what java-cef uses internally to find the
 * installation. Also see {@link org.cef.CefApp}.
 */
@Mixin(ClientPackSource.class)
public class MixinClientPackSource {

    @Inject(at = @At("HEAD"), method = "<clinit>")
    private static void on_clinit_MCEF(CallbackInfo callbackInfo) {
        MCEFDownloadListener.INSTANCE.setDone(false);
        MCEFDownloadListener.INSTANCE.setFailed(false);
        MCEFDownloadListener.INSTANCE.setTask("Preparing Download");

        try {
            setupLibraryPath_MCEF();
        } catch (IOException e) {
            failDownload_MCEF("Failed to prepare MCEF library paths", e);
            return;
        }

        Thread downloadThread = new Thread(MixinClientPackSource::runDownloaderFlow_MCEF, "MCEF-Downloader");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    @Unique
    private static void setupLibraryPath_MCEF() throws IOException {
        final File mcefLibrariesDir;

        // Check for development environment
        // TODO: handle eclipse/others
        // i.e. mcef-repo/neoforge/build
        File buildDir = new File("../build");
        if (buildDir.exists() && buildDir.isDirectory()) {
            mcefLibrariesDir = new File(buildDir, "mcef-libraries/");
        } else {
            mcefLibrariesDir = new File("mods/mcef-libraries/");
        }

        mcefLibrariesDir.mkdirs();

        System.setProperty("mcef.libraries.path", mcefLibrariesDir.getCanonicalPath());
        System.setProperty("jcef.path", new File(mcefLibrariesDir, MCEFPlatform.getPlatform().getNormalizedName()).getCanonicalPath());
    }

    @Unique
    private static void runDownloaderFlow_MCEF() {
        try {
            String javaCefCommit = MCEF.getJavaCefCommit();
            MCEF.getLogger().info("java-cef commit: " + javaCefCommit);

            MCEFSettings settings = MCEF.getSettings();
            MCEFPlatform platform = MCEFPlatform.getPlatform();
            MCEFDownloader downloader = new MCEFDownloader(
                    settings.getDownloadMirror(),
                    javaCefCommit,
                    platform,
                    settings.createDownloadPolicy()
            );

            File mcefLibrariesDir = new File(System.getProperty("mcef.libraries.path"));
            File platformLibrariesDir = new File(mcefLibrariesDir, platform.getNormalizedName());
            boolean hasPlatformLibrariesDir = platformLibrariesDir.exists() && platformLibrariesDir.isDirectory();

            if (settings.isSkipDownload()) {
                if (!hasPlatformLibrariesDir) {
                    failDownload_MCEF("skip-download=true but local MCEF libraries are missing", null);
                    return;
                }
                MCEFDownloadListener.INSTANCE.setDone(true);
                return;
            }

            boolean hasChecksumResult = false;
            boolean checksumMatches = false;
            try {
                checksumMatches = downloader.downloadJavaCefChecksum();
                hasChecksumResult = true;
            } catch (IOException e) {
                if (settings.isEnforceDownloadChecksums()) {
                    failDownload_MCEF("Failed to download JCEF checksum", e);
                    return;
                }
                MCEF.getLogger().warn("Failed to download JCEF checksum with checksum enforcement disabled", e);
            }

            boolean downloadJcefBuild = !hasPlatformLibrariesDir || (hasChecksumResult && !checksumMatches);

            if (downloadJcefBuild) {
                try {
                    downloader.downloadJavaCefBuild();
                    downloader.extractJavaCefBuild(true);
                } catch (IOException e) {
                    failDownload_MCEF("Failed to download or extract JCEF", e);
                    return;
                }
            }

            MCEFDownloadListener.INSTANCE.setDone(true);
        } catch (IOException e) {
            failDownload_MCEF("Failed to initialize JCEF downloader", e);
        } catch (RuntimeException e) {
            failDownload_MCEF("JCEF downloader failed due to an invalid configuration", e);
        }
    }

    @Unique
    private static void failDownload_MCEF(String task, Exception e) {
        if (e != null) {
            MCEF.getLogger().error(task, e);
        } else {
            MCEF.getLogger().error(task);
        }
        MCEFDownloadListener.INSTANCE.setTask(task);
        MCEFDownloadListener.INSTANCE.setFailed(true);
    }

}
