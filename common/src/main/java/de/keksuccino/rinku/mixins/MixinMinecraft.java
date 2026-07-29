package de.keksuccino.rinku.mixins;

import com.mojang.logging.LogUtils;
import de.keksuccino.rinku.OSPlatform;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuRenderCoordinator;
import de.keksuccino.rinku.binarydownload.RinkuDownloadListener;
import de.keksuccino.rinku.binarydownload.RinkuDownloaderScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.CreateBuffetWorldScreen;
import net.minecraft.client.gui.screens.CreateFlatWorldScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.client.gui.screens.worldselection.ExperimentsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Shadow public abstract void setScreen(@Nullable Screen screen);

    @Unique private static final Logger LOGGER_RINKU = LogUtils.getLogger();
    @Unique private static final AtomicBoolean RECURSION_DETECTOR_RINKU = new AtomicBoolean(false);
    @Unique private static final String JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU = "jcef_helper.exe";

    /** @reason Rinku must finish its one-shot native-runtime preparation before vanilla enters a client screen. */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void before_setScreen_Rinku(@Nullable Screen screen, CallbackInfo info) {
        if (!Rinku.isInitializationAllowed()) return;

        boolean recursionValue = RECURSION_DETECTOR_RINKU.get();
        RECURSION_DETECTOR_RINKU.set(true);
        try {
            if (!shouldHandleScreenChange_Rinku(screen, recursionValue)) return;

            if (RinkuDownloadListener.INSTANCE.isDone() && !RinkuDownloadListener.INSTANCE.isFailed()) {
                LOGGER_RINKU.debug("Rinku already finished downloading, scheduling loading.");
                Minecraft.getInstance().execute(() -> {
                    if (!Rinku.isInitializationAllowed()) return;
                    LOGGER_RINKU.debug("Rinku is attempting to load.");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOGGER_RINKU.warn("Interrupted while waiting to initialize Rinku.", e);
                        return;
                    }
                    if (Rinku.isInitializationAllowed()) Rinku.initialize();
                });
            } else if (!RinkuDownloadListener.INSTANCE.isDone() && !RinkuDownloadListener.INSTANCE.isFailed()) {
                LOGGER_RINKU.debug("Rinku has not finished loading, displaying loading screen.");
                setScreen(new RinkuDownloaderScreen(screen));
                info.cancel();
            } else if (RinkuDownloadListener.INSTANCE.isFailed()) {
                LOGGER_RINKU.error("Rinku failed to initialize!");
            }
        } finally {
            RECURSION_DETECTOR_RINKU.set(recursionValue);
        }
    }

    // Keep this as a direct frame hook: Minecraft may discard queued executor tasks during shutdown.
    @Inject(method = "runTick", at = @At("HEAD"))
    private void before_runTick_Rinku(boolean advanceGameTime, CallbackInfo info) {
        RinkuRenderCoordinator.pumpOnRenderThread();
    }

    // Drain and close browser GPU resources before vanilla tears down the render device and GL context.
    @Inject(method = "close", at = @At("HEAD"))
    private void before_close_Rinku(CallbackInfo info) {
        RinkuRenderCoordinator.shutdownOnRenderThread();
        Rinku.shutdown();
    }

    /**
     * Temporary workaround to address lingering JCEF processes on Windows.
     * @author Blobanium
     */
    @Inject(method = "close", at = @At("TAIL"))
    private void after_close_Rinku(CallbackInfo info) {

        if (!OSPlatform.getPlatform().isWindows()) {
            return;
        }

        Path rinkuLibrariesPath = resolveRinkuLibrariesPath_Rinku();
        if (rinkuLibrariesPath == null) {
            LOGGER_RINKU.warn("rinku.libraries.path is not set, skipping scoped JCEF helper cleanup.");
            return;
        }

        AtomicInteger terminatedProcesses = new AtomicInteger(0);
        try {
            ProcessHandle.allProcesses().forEach(processHandle -> {
                try {
                    if (!shouldTerminateJcefHelper_Rinku(processHandle, rinkuLibrariesPath)) {
                        return;
                    }

                    if (terminateProcess_Rinku(processHandle)) {
                        terminatedProcesses.incrementAndGet();
                        LOGGER_RINKU.warn("Terminated lingering JCEF helper process (pid={}).", processHandle.pid());
                    }
                } catch (Exception e) {
                    LOGGER_RINKU.debug("Unable to inspect process {} for scoped JCEF cleanup.", processHandle.pid(), e);
                }
            });
        } catch (Exception e) {
            LOGGER_RINKU.error("Unable to enumerate processes for scoped JCEF cleanup.", e);
            return;
        }

        if (terminatedProcesses.get() > 0) {
            LOGGER_RINKU.warn("Terminated {} lingering JCEF helper process(es) under {}.", terminatedProcesses.get(), rinkuLibrariesPath);
        }

    }

    @Unique
    private static boolean shouldTerminateJcefHelper_Rinku(ProcessHandle processHandle, Path rinkuLibrariesPath) {
        if (!processHandle.isAlive() || processHandle.pid() == ProcessHandle.current().pid()) {
            return false;
        }

        if (!isJcefHelperProcess_Rinku(processHandle)) {
            return false;
        }

        if (isExecutableInRinkuLibraries_Rinku(processHandle, rinkuLibrariesPath)) {
            return true;
        }

        // Fallback for environments where executable path is unavailable.
        return isDescendantOfCurrentProcess_Rinku(processHandle) && commandLineContainsLibrariesPath_Rinku(processHandle, rinkuLibrariesPath);
    }

    @Unique
    private static boolean shouldHandleScreenChange_Rinku(@Nullable Screen screen, boolean recursionValue) {
        // Mods may open screens before vanilla's initial screen. Recursive replacement is safe only while vanilla is
        // moving through a known startup, world-creation, or connection screen that must wait for Rinku.
        return !recursionValue
                || screen instanceof TitleScreen
                || screen instanceof LevelLoadingScreen
                || screen instanceof SelectWorldScreen
                || screen instanceof DirectJoinServerScreen
                || screen instanceof ConnectScreen
                || screen instanceof AccessibilityOnboardingScreen
                || screen instanceof SafetyScreen
                || screen instanceof JoinMultiplayerScreen
                || screen instanceof CreateWorldScreen
                || screen instanceof EditGameRulesScreen
                || screen instanceof ExperimentsScreen
                || screen instanceof PackSelectionScreen
                || screen instanceof CreateFlatWorldScreen
                || screen instanceof CreateBuffetWorldScreen;
    }

    @Unique
    private static boolean terminateProcess_Rinku(ProcessHandle processHandle) {
        if (!processHandle.isAlive()) {
            return false;
        }

        if (processHandle.destroy()) {
            return true;
        }

        return processHandle.isAlive() && processHandle.destroyForcibly();
    }

    @Unique
    private static boolean isJcefHelperProcess_Rinku(ProcessHandle processHandle) {
        if (processHandle.info().command().map(MixinMinecraft::isJcefHelperExecutableName_Rinku).orElse(false)) {
            return true;
        }

        return processHandle.info().commandLine()
                .map(commandLine -> commandLine.toLowerCase(Locale.ROOT).contains(JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU))
                .orElse(false);
    }

    @Unique
    private static boolean isJcefHelperExecutableName_Rinku(String command) {
        int lastSeparatorIndex = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        String executableName = lastSeparatorIndex >= 0 ? command.substring(lastSeparatorIndex + 1) : command;
        return executableName.equalsIgnoreCase(JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU);
    }

    @Unique
    private static boolean isExecutableInRinkuLibraries_Rinku(ProcessHandle processHandle, Path rinkuLibrariesPath) {
        Optional<String> command = processHandle.info().command();
        if (command.isEmpty()) {
            return false;
        }

        try {
            Path commandPath = Path.of(command.get()).normalize();
            if (!commandPath.isAbsolute()) {
                return false;
            }
            return commandPath.startsWith(rinkuLibrariesPath);
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    @Unique
    private static boolean commandLineContainsLibrariesPath_Rinku(ProcessHandle processHandle, Path rinkuLibrariesPath) {
        String librariesPath = rinkuLibrariesPath.toString().toLowerCase(Locale.ROOT);
        return processHandle.info().commandLine()
                .map(commandLine -> commandLine.toLowerCase(Locale.ROOT).contains(librariesPath))
                .orElse(false);
    }

    @Unique
    private static boolean isDescendantOfCurrentProcess_Rinku(ProcessHandle processHandle) {
        long currentPid = ProcessHandle.current().pid();

        Optional<ProcessHandle> currentParent = processHandle.parent();
        while (currentParent.isPresent()) {
            ProcessHandle parent = currentParent.get();
            if (parent.pid() == currentPid) {
                return true;
            }
            currentParent = parent.parent();
        }

        return false;
    }

    @Unique
    private static @Nullable Path resolveRinkuLibrariesPath_Rinku() {
        String configuredPath = System.getProperty("rinku.libraries.path");
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }

        try {
            return Path.of(configuredPath).toRealPath().normalize();
        } catch (IOException | InvalidPathException e) {
            try {
                return Path.of(configuredPath).toAbsolutePath().normalize();
            } catch (InvalidPathException ignored) {
                return null;
            }
        }
    }

}
