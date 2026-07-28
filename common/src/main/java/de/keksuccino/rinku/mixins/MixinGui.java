package de.keksuccino.rinku.mixins;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.binarydownload.RinkuDownloadListener;
import de.keksuccino.rinku.binarydownload.RinkuDownloaderScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
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
import net.minecraft.client.gui.screens.worldselection.AbstractGameRulesScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.ExperimentsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(Gui.class)
public abstract class MixinGui {
    @Unique
    private static final Logger LOGGER_RINKU = LogUtils.getLogger();

    @Unique
    private static final AtomicBoolean RECURSION_DETECTOR_RINKU = new AtomicBoolean(false);

    @Unique
    private static boolean shouldHandleScreenChange_RINKU(@Nullable Screen screen, boolean recursionValue) {
        // Mods may try to open screens before the first screen exists, so avoid recursive screen replacement unless
        // vanilla is moving through one of the known startup or joining screens that should wait for Rinku.
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
                || screen instanceof AbstractGameRulesScreen
                || screen instanceof ExperimentsScreen
                || screen instanceof PackSelectionScreen
                || screen instanceof CreateFlatWorldScreen
                || screen instanceof CreateBuffetWorldScreen;
    }

    @Shadow
    public abstract void setScreen(@Nullable Screen screen);

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    public void before_setScreen_RINKU(@Nullable Screen screen, CallbackInfo info) {
        if (!Rinku.isInitializationAllowed()) {
            return;
        }

        boolean recursionValue = RECURSION_DETECTOR_RINKU.get();
        RECURSION_DETECTOR_RINKU.set(true);

        try {
            if (!shouldHandleScreenChange_RINKU(screen, recursionValue)) {
                return;
            }

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

}
