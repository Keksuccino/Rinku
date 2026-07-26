package com.cinemamod.mcef.mixins;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFDownloader;
import com.cinemamod.mcef.MCEFPlatform;
import com.cinemamod.mcef.MCEFSettings;
import com.cinemamod.mcef.internal.MCEFDownloadListener;
import net.minecraft.client.resources.ClientPackSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * jcef.path is resolved after recovery to one immutable, content-addressed java-cef generation.
 * This is what java-cef uses internally to find the installation. Also see {@link org.cef.CefApp}.
 */
@Mixin(ClientPackSource.class)
public class MixinClientPackSource {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");

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

        java.nio.file.Files.createDirectories(mcefLibrariesDir.toPath());
        System.setProperty("mcef.libraries.path", mcefLibrariesDir.getCanonicalPath());
    }

    @Unique
    private static void runDownloaderFlow_MCEF() {
        try {
            String javaCefCommit = MCEF.getJavaCefCommit();
            LOGGER.info("java-cef commit: " + javaCefCommit);

            MCEFSettings settings = MCEF.getSettings();
            MCEFPlatform platform = MCEFPlatform.getPlatform();
            MCEFDownloader downloader = new MCEFDownloader(settings.getDownloadMirror(), javaCefCommit, platform, settings.createDownloadPolicy());
            MCEFDownloader.InstallationResult installation = downloader.installOrUpdate(settings.isSkipDownload(), true);
            System.setProperty("jcef.path", installation.installationDirectory().toRealPath().toString());
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
            LOGGER.error(task, e);
        } else {
            LOGGER.error(task);
        }
        MCEFDownloadListener.INSTANCE.setTask(task);
        MCEFDownloadListener.INSTANCE.setFailed(true);
    }

}
