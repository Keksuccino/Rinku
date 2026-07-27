package de.keksuccino.mcef.mixins;

import de.keksuccino.mcef.MCEF;
import de.keksuccino.mcef.internal.MCEFDownloadListener;
import de.keksuccino.mcef.internal.MCEFDownloaderMenu;
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
import org.slf4j.LoggerFactory;
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
    private static final Logger LOGGER_MCEF = LoggerFactory.getLogger("MCEF");

    @Unique
    private static final AtomicBoolean RECURSION_DETECTOR_MCEF = new AtomicBoolean(false);

    @Unique
    private static boolean shouldHandleScreenChange_MCEF(@Nullable Screen screen, boolean recursionValue) {
        // Mods may try to open screens before the first screen exists, so avoid recursive screen replacement unless
        // vanilla is moving through one of the known startup or joining screens that should wait for MCEF.
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
    public void before_setScreen_MCEF(@Nullable Screen screen, CallbackInfo info) {
        if (MCEF.isInitialized()) {
            return;
        }

        boolean recursionValue = RECURSION_DETECTOR_MCEF.get();
        RECURSION_DETECTOR_MCEF.set(true);

        try {
            if (!shouldHandleScreenChange_MCEF(screen, recursionValue)) {
                return;
            }

            if (MCEFDownloadListener.INSTANCE.isDone() && !MCEFDownloadListener.INSTANCE.isFailed()) {
                LOGGER_MCEF.debug("MCEF already finished downloading, scheduling loading.");
                Minecraft.getInstance().execute(() -> {
                    LOGGER_MCEF.debug("MCEF is attempting to load.");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        LOGGER_MCEF.error("I don't even know what occurred here.", e);
                    }
                    MCEF.initialize();
                });
            } else if (!MCEFDownloadListener.INSTANCE.isDone() && !MCEFDownloadListener.INSTANCE.isFailed()) {
                LOGGER_MCEF.debug("MCEF has not finished loading, displaying loading screen.");
                setScreen(new MCEFDownloaderMenu(screen));
                info.cancel();
            } else if (MCEFDownloadListener.INSTANCE.isFailed()) {
                LOGGER_MCEF.error("MCEF failed to initialize!");
            }
        } finally {
            RECURSION_DETECTOR_MCEF.set(recursionValue);
        }
    }

}
