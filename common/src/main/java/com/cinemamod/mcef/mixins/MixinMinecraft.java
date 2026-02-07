package com.cinemamod.mcef.mixins;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFPlatform;
import com.cinemamod.mcef.internal.MCEFDownloadListener;
import com.cinemamod.mcef.internal.MCEFDownloaderMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.client.gui.screens.worldselection.ExperimentsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.jetbrains.annotations.Nullable;
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

    @Unique
    private static final AtomicBoolean RECURSION_DETECTOR_MCEF = new AtomicBoolean(false);
    @Unique
    private static final String JCEF_HELPER_EXECUTABLE_WINDOWS_MCEF = "jcef_helper.exe";

    @Shadow
    public abstract void setScreen(@Nullable Screen screen);

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    public void head_setScreen_MCEF(Screen screen, CallbackInfo info) {

        if (!MCEF.isInitialized()) {
            boolean recursionValue = RECURSION_DETECTOR_MCEF.get();
            RECURSION_DETECTOR_MCEF.set(true);

            // regardless of what screen the game opens to, MCEF must try to initialize
            // if it does not, there are bigger problems
            if (
                // mods may try to set the screen before the first screen opens
                // in the event that this happens, recursion would happen
                // so if this is detected, not try to open the screen again, as that could cause a crash
                    !recursionValue ||
                            screen instanceof TitleScreen ||
                            screen instanceof LevelLoadingScreen ||
                            screen instanceof SelectWorldScreen ||
                            screen instanceof DirectJoinServerScreen ||
                            screen instanceof ConnectScreen ||
                            screen instanceof AccessibilityOnboardingScreen ||
                            screen instanceof SafetyScreen ||
                            screen instanceof JoinMultiplayerScreen ||
                            screen instanceof CreateWorldScreen ||
                            screen instanceof EditGameRulesScreen ||
                            screen instanceof ExperimentsScreen ||
                            screen instanceof PackSelectionScreen ||
                            screen instanceof CreateFlatWorldScreen ||
                            screen instanceof CreateBuffetWorldScreen
            ) {
                // If the download is done and didn't fail
                if (MCEFDownloadListener.INSTANCE.isDone() && !MCEFDownloadListener.INSTANCE.isFailed()) {
                    MCEF.getLogger().debug("MCEF already finished downloading, scheduling loading.");
                    Minecraft.getInstance().execute((() -> {
                        MCEF.getLogger().debug("MCEF is attempting to load.");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            MCEF.getLogger().error("I don't even know what occurred here.", e);
                        }
                        MCEF.initialize();
                    }));
                }
                // If the download is not done and didn't fail
                else if (!MCEFDownloadListener.INSTANCE.isDone() && !MCEFDownloadListener.INSTANCE.isFailed()) {
                    MCEF.getLogger().debug("MCEF has not finished loading, displaying loading screen.");
                    setScreen(new MCEFDownloaderMenu(screen));
                    info.cancel();
                }
                // If the download failed
                else if (MCEFDownloadListener.INSTANCE.isFailed()) {
                    MCEF.getLogger().error("MCEF failed to initialize!");
                }
            }

            RECURSION_DETECTOR_MCEF.set(recursionValue);
        }

    }

    /**
     * Temporary workaround to address lingering JCEF processes on Windows.
     * @author Blobanium
     */
    @Inject(method = "close", at = @At("TAIL"))
    public void after_close_MCEF(CallbackInfo info) {

        if (!MCEFPlatform.getPlatform().isWindows()) {
            return;
        }

        Path mcefLibrariesPath = resolveMcefLibrariesPath_MCEF();
        if (mcefLibrariesPath == null) {
            MCEF.getLogger().warn("mcef.libraries.path is not set, skipping scoped JCEF helper cleanup.");
            return;
        }

        AtomicInteger terminatedProcesses = new AtomicInteger(0);
        try {
            ProcessHandle.allProcesses().forEach(processHandle -> {
                try {
                    if (!shouldTerminateJcefHelper_MCEF(processHandle, mcefLibrariesPath)) {
                        return;
                    }

                    if (terminateProcess_MCEF(processHandle)) {
                        terminatedProcesses.incrementAndGet();
                        MCEF.getLogger().warn("Terminated lingering JCEF helper process (pid={}).", processHandle.pid());
                    }
                } catch (Exception e) {
                    MCEF.getLogger().debug("Unable to inspect process {} for scoped JCEF cleanup.", processHandle.pid(), e);
                }
            });
        } catch (Exception e) {
            MCEF.getLogger().error("Unable to enumerate processes for scoped JCEF cleanup.", e);
            return;
        }

        if (terminatedProcesses.get() > 0) {
            MCEF.getLogger().warn("Terminated {} lingering JCEF helper process(es) under {}.",
                    terminatedProcesses.get(), mcefLibrariesPath);
        }

    }

    @Unique
    private static boolean shouldTerminateJcefHelper_MCEF(ProcessHandle processHandle, Path mcefLibrariesPath) {
        if (!processHandle.isAlive() || processHandle.pid() == ProcessHandle.current().pid()) {
            return false;
        }

        if (!isJcefHelperProcess_MCEF(processHandle)) {
            return false;
        }

        if (isExecutableInMcefLibraries_MCEF(processHandle, mcefLibrariesPath)) {
            return true;
        }

        // Fallback for environments where executable path is unavailable.
        return isDescendantOfCurrentProcess_MCEF(processHandle)
                && commandLineContainsLibrariesPath_MCEF(processHandle, mcefLibrariesPath);
    }

    @Unique
    private static boolean terminateProcess_MCEF(ProcessHandle processHandle) {
        if (!processHandle.isAlive()) {
            return false;
        }

        if (processHandle.destroy()) {
            return true;
        }

        return processHandle.isAlive() && processHandle.destroyForcibly();
    }

    @Unique
    private static boolean isJcefHelperProcess_MCEF(ProcessHandle processHandle) {
        if (processHandle.info().command().map(MixinMinecraft::isJcefHelperExecutableName_MCEF).orElse(false)) {
            return true;
        }

        return processHandle.info().commandLine()
                .map(commandLine -> commandLine.toLowerCase(Locale.ROOT).contains(JCEF_HELPER_EXECUTABLE_WINDOWS_MCEF))
                .orElse(false);
    }

    @Unique
    private static boolean isJcefHelperExecutableName_MCEF(String command) {
        int lastSeparatorIndex = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        String executableName = lastSeparatorIndex >= 0 ? command.substring(lastSeparatorIndex + 1) : command;
        return executableName.equalsIgnoreCase(JCEF_HELPER_EXECUTABLE_WINDOWS_MCEF);
    }

    @Unique
    private static boolean isExecutableInMcefLibraries_MCEF(ProcessHandle processHandle, Path mcefLibrariesPath) {
        Optional<String> command = processHandle.info().command();
        if (command.isEmpty()) {
            return false;
        }

        try {
            Path commandPath = Path.of(command.get()).normalize();
            if (!commandPath.isAbsolute()) {
                return false;
            }
            return commandPath.startsWith(mcefLibrariesPath);
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    @Unique
    private static boolean commandLineContainsLibrariesPath_MCEF(ProcessHandle processHandle, Path mcefLibrariesPath) {
        String librariesPath = mcefLibrariesPath.toString().toLowerCase(Locale.ROOT);
        return processHandle.info().commandLine()
                .map(commandLine -> commandLine.toLowerCase(Locale.ROOT).contains(librariesPath))
                .orElse(false);
    }

    @Unique
    private static boolean isDescendantOfCurrentProcess_MCEF(ProcessHandle processHandle) {
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
    private static @Nullable Path resolveMcefLibrariesPath_MCEF() {
        String configuredPath = System.getProperty("mcef.libraries.path");
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
