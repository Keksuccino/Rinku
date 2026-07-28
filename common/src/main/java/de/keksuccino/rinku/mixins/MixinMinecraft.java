package de.keksuccino.rinku.mixins;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.OSPlatform;
import de.keksuccino.rinku.RinkuRenderCoordinator;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Unique
    private static final Logger LOGGER_RINKU = LogUtils.getLogger();

    @Unique
    private static final String JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU = "jcef_helper.exe";

    // Keep this as a direct frame hook: Minecraft may discard queued executor tasks during shutdown.
    @Inject(method = "runTick", at = @At("HEAD"))
    private void before_runTick_RINKU(boolean advanceGameTime, CallbackInfo info) {
        RinkuRenderCoordinator.pumpOnRenderThread();
    }

    // Drain and close browser GPU resources before vanilla tears down the render device and GL context.
    @Inject(method = "close", at = @At("HEAD"))
    private void before_close_RINKU(CallbackInfo info) {
        RinkuRenderCoordinator.shutdownOnRenderThread();
        Rinku.shutdown();
    }

    /**
     * Temporary workaround to address lingering JCEF processes on Windows.
     * @author Blobanium
     */
    @Inject(method = "close", at = @At("TAIL"))
    public void after_close_RINKU(CallbackInfo info) {

        if (!OSPlatform.getPlatform().isWindows()) {
            return;
        }

        Path rinkuLibrariesPath = resolveRinkuLibrariesPath_RINKU();
        if (rinkuLibrariesPath == null) {
            LOGGER_RINKU.warn("rinku.libraries.path is not set, skipping scoped JCEF helper cleanup.");
            return;
        }

        AtomicInteger terminatedProcesses = new AtomicInteger(0);
        try {
            ProcessHandle.allProcesses().forEach(processHandle -> {
                try {
                    if (!shouldTerminateJcefHelper_RINKU(processHandle, rinkuLibrariesPath)) {
                        return;
                    }

                    if (terminateProcess_RINKU(processHandle)) {
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
            LOGGER_RINKU.warn("Terminated {} lingering JCEF helper process(es) under {}.",
                    terminatedProcesses.get(), rinkuLibrariesPath);
        }

    }

    @Unique
    private static boolean shouldTerminateJcefHelper_RINKU(ProcessHandle processHandle, Path rinkuLibrariesPath) {
        if (!processHandle.isAlive() || processHandle.pid() == ProcessHandle.current().pid()) {
            return false;
        }

        if (!isJcefHelperProcess_RINKU(processHandle)) {
            return false;
        }

        if (isExecutableInRinkuLibraries_RINKU(processHandle, rinkuLibrariesPath)) {
            return true;
        }

        // Fallback for environments where executable path is unavailable.
        return isDescendantOfCurrentProcess_RINKU(processHandle)
                && commandLineContainsLibrariesPath_RINKU(processHandle, rinkuLibrariesPath);
    }

    @Unique
    private static boolean terminateProcess_RINKU(ProcessHandle processHandle) {
        if (!processHandle.isAlive()) {
            return false;
        }

        if (processHandle.destroy()) {
            return true;
        }

        return processHandle.isAlive() && processHandle.destroyForcibly();
    }

    @Unique
    private static boolean isJcefHelperProcess_RINKU(ProcessHandle processHandle) {
        if (processHandle.info().command().map(MixinMinecraft::isJcefHelperExecutableName_RINKU).orElse(false)) {
            return true;
        }

        return processHandle.info().commandLine()
                .map(commandLine -> commandLine.toLowerCase(Locale.ROOT).contains(JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU))
                .orElse(false);
    }

    @Unique
    private static boolean isJcefHelperExecutableName_RINKU(String command) {
        int lastSeparatorIndex = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        String executableName = lastSeparatorIndex >= 0 ? command.substring(lastSeparatorIndex + 1) : command;
        return executableName.equalsIgnoreCase(JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU);
    }

    @Unique
    private static boolean isExecutableInRinkuLibraries_RINKU(ProcessHandle processHandle, Path rinkuLibrariesPath) {
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
    private static boolean commandLineContainsLibrariesPath_RINKU(ProcessHandle processHandle, Path rinkuLibrariesPath) {
        String librariesPath = rinkuLibrariesPath.toString().toLowerCase(Locale.ROOT);
        return processHandle.info().commandLine()
                .map(commandLine -> commandLine.toLowerCase(Locale.ROOT).contains(librariesPath))
                .orElse(false);
    }

    @Unique
    private static boolean isDescendantOfCurrentProcess_RINKU(ProcessHandle processHandle) {
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
    private static @Nullable Path resolveRinkuLibrariesPath_RINKU() {
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
