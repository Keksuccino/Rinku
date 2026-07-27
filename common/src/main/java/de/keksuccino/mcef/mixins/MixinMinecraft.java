package de.keksuccino.mcef.mixins;

import de.keksuccino.mcef.MCEF;
import de.keksuccino.mcef.MCEFPlatform;
import de.keksuccino.mcef.MCEFRenderCoordinator;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOGGER_MCEF = LoggerFactory.getLogger("MCEF");

    @Unique
    private static final String JCEF_HELPER_EXECUTABLE_WINDOWS_MCEF = "jcef_helper.exe";

    // Keep this as a direct frame hook: Minecraft may discard queued executor tasks during shutdown.
    @Inject(method = "runTick", at = @At("HEAD"))
    private void before_runTick_MCEF(boolean advanceGameTime, CallbackInfo info) {
        MCEFRenderCoordinator.pumpOnRenderThread();
    }

    // Drain and close browser GPU resources before vanilla tears down the render device and GL context.
    @Inject(method = "close", at = @At("HEAD"))
    private void before_close_MCEF(CallbackInfo info) {
        MCEFRenderCoordinator.shutdownOnRenderThread();
        MCEF.shutdown();
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
            LOGGER_MCEF.warn("mcef.libraries.path is not set, skipping scoped JCEF helper cleanup.");
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
                        LOGGER_MCEF.warn("Terminated lingering JCEF helper process (pid={}).", processHandle.pid());
                    }
                } catch (Exception e) {
                    LOGGER_MCEF.debug("Unable to inspect process {} for scoped JCEF cleanup.", processHandle.pid(), e);
                }
            });
        } catch (Exception e) {
            LOGGER_MCEF.error("Unable to enumerate processes for scoped JCEF cleanup.", e);
            return;
        }

        if (terminatedProcesses.get() > 0) {
            LOGGER_MCEF.warn("Terminated {} lingering JCEF helper process(es) under {}.",
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
