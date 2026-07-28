package de.keksuccino.mcef.mixins;

import de.keksuccino.mcef.MCEF;
import de.keksuccino.mcef.MCEFDownloader;
import de.keksuccino.mcef.MCEFPlatform;
import de.keksuccino.mcef.MCEFSettings;
import de.keksuccino.mcef.internal.MCEFDownloadListener;
import de.keksuccino.mcef.util.GameDirectoryUtils;
import net.minecraft.client.resources.ClientPackSource;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <p>
 * mcef.libraries.path is where MCEF will store any required binaries. By default,
 * /path/to/.minecraft/mcef-libraries.
 * <p>
 * jcef.path is resolved after recovery to the completed exact-commit cache leaf.
 * This is what java-cef uses internally to find the installation. Also see {@link org.cef.CefApp}.
 */
@Mixin(ClientPackSource.class)
public class MixinClientPackSource {
    @Unique private static final Logger LOGGER_MCEF = LogUtils.getLogger();

    /**
     * @reason This must remain at TAIL because Mixin merges LOGGER_MCEF's initializer into this same
     * class initializer. At HEAD the field is still null, so even failure reporting would crash.
     */
    @Inject(at = @At("TAIL"), method = "<clinit>")
    private static void on_clinit_MCEF(CallbackInfo callbackInfo) {
        MCEFDownloadListener.INSTANCE.setDone(false);
        MCEFDownloadListener.INSTANCE.setFailed(false);
        MCEFDownloadListener.INSTANCE.setTask("Preparing Download");

        try {
            setupLibraryPath_MCEF();
        } catch (IOException | RuntimeException e) {
            failDownload_MCEF("Failed to prepare MCEF library paths", e);
            return;
        }

        Thread downloadThread = new Thread(MixinClientPackSource::runDownloaderFlow_MCEF, "MCEF-Downloader");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    @Unique
    private static void setupLibraryPath_MCEF() throws IOException {
        Path mcefLibrariesDirectory = GameDirectoryUtils.getGameDirectory().toPath().resolve("mcef-libraries");
        Files.createDirectories(mcefLibrariesDirectory);
        System.setProperty("mcef.libraries.path", mcefLibrariesDirectory.toRealPath().toString());
    }

    @Unique
    private static void runDownloaderFlow_MCEF() {
        try {
            String javaCefCommit = MCEF.getJavaCefCommit();
            LOGGER_MCEF.info("java-cef commit: " + javaCefCommit);

            MCEFSettings settings = MCEF.getSettings();
            MCEFPlatform platform = MCEFPlatform.getPlatform();
            MCEFDownloader downloader = new MCEFDownloader(settings.getDownloadMirror(), platform, settings.createDownloadPolicy());
            MCEFDownloader.InstallationResult installation = downloader.installOrUpdate(settings.isSkipDownload());
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
            LOGGER_MCEF.error(task, e);
        } else {
            LOGGER_MCEF.error(task);
        }
        MCEFDownloadListener.INSTANCE.setTask(task);
        MCEFDownloadListener.INSTANCE.setFailed(true);
    }

}
