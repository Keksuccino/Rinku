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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Unique
    private static final AtomicBoolean RECURSION_DETECTOR_MCEF = new AtomicBoolean(false);

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
    public void tail_close_MCEF(CallbackInfo info) {

        if (MCEFPlatform.getPlatform().isWindows()) {
            String processName = "jcef_helper.exe";
            try {
                ProcessBuilder processBuilder = new ProcessBuilder("tasklist");
                Process process = processBuilder.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                boolean isRunning = false;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(processName)) {
                        isRunning = true;
                        break;
                    }
                }
                reader.close();

                if (isRunning) {
                    MCEF.getLogger().warn("JCEF is still running, killing to avoid lingering processes.");
                    ProcessBuilder killProcess = new ProcessBuilder("taskkill", "/F", "/IM", processName);
                    killProcess.start();
                }
            } catch (Exception e) {
                MCEF.getLogger().error("Unable to check if JCEF is still running. There may be lingering processes.", e);
            }
        }

    }

}
