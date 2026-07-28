package de.keksuccino.rinku.mixins;

import com.mojang.logging.LogUtils;
import de.keksuccino.rinku.OSPlatform;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuSettings;
import de.keksuccino.rinku.binarydownload.RinkuDownloadListener;
import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import de.keksuccino.rinku.util.GameDirectoryUtils;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
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
 * rinku.libraries.path is where Rinku will store any required binaries. By default,
 * /path/to/.minecraft/rinku-libraries.
 * <p>
 * jcef.path is resolved after recovery to the completed exact-commit cache leaf.
 * This is what java-cef uses internally to find the installation. Also see {@link org.cef.CefApp}.
 */
@Mixin(ClientPackSource.class)
public class MixinClientPackSource {
    @Unique private static final Logger LOGGER_RINKU = LogUtils.getLogger();

    /**
     * @reason This must remain at TAIL because Mixin merges LOGGER_RINKU's initializer into this same
     * class initializer. At HEAD the field is still null, so even failure reporting would crash.
     */
    @Inject(at = @At("TAIL"), method = "<clinit>")
    private static void on_clinit_RINKU(CallbackInfo callbackInfo) {
        RinkuDownloadListener.INSTANCE.setDone(false);
        RinkuDownloadListener.INSTANCE.setFailed(false);
        RinkuDownloadListener.INSTANCE.setTask(Component.translatable("rinku.downloader.task.preparing"));

        try {
            setupLibraryPath_RINKU();
        } catch (IOException | RuntimeException e) {
            failDownload_RINKU("Failed to prepare Rinku library paths", Component.translatable("rinku.downloader.task.failed_library_paths"), e);
            return;
        }

        Thread downloadThread = new Thread(MixinClientPackSource::runDownloaderFlow_RINKU, "Rinku-Downloader");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    @Unique
    private static void setupLibraryPath_RINKU() throws IOException {
        Path rinkuLibrariesDirectory = GameDirectoryUtils.getGameDirectory().toPath().resolve("rinku-libraries");
        Files.createDirectories(rinkuLibrariesDirectory);
        System.setProperty("rinku.libraries.path", rinkuLibrariesDirectory.toRealPath().toString());
    }

    @Unique
    private static void runDownloaderFlow_RINKU() {
        try {
            String javaCefCommit = Rinku.getJavaCefCommit();
            LOGGER_RINKU.info("java-cef commit: " + javaCefCommit);

            RinkuSettings settings = Rinku.getSettings();
            OSPlatform platform = OSPlatform.getPlatform();
            RinkuDownloader downloader = new RinkuDownloader(settings.getDownloadMirror(), platform, settings.createDownloadPolicy());
            RinkuDownloader.InstallationResult installation = downloader.installOrUpdate(settings.isSkipDownload());
            System.setProperty("jcef.path", installation.installationDirectory().toRealPath().toString());
            RinkuDownloadListener.INSTANCE.setDone(true);
        } catch (IOException e) {
            failDownload_RINKU("Failed to initialize JCEF downloader", Component.translatable("rinku.downloader.task.failed_initialization"), e);
        } catch (RuntimeException e) {
            failDownload_RINKU("JCEF downloader failed due to an invalid configuration", Component.translatable("rinku.downloader.task.failed_configuration"), e);
        }
    }

    @Unique
    private static void failDownload_RINKU(String logMessage, Component task, Exception e) {
        if (e != null) {
            LOGGER_RINKU.error(logMessage, e);
        } else {
            LOGGER_RINKU.error(logMessage);
        }
        RinkuDownloadListener.INSTANCE.setTask(task);
        RinkuDownloadListener.INSTANCE.setFailed(true);
    }

}
