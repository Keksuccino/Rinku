package de.keksuccino.rinku.mixins;

import de.keksuccino.rinku.Rinku;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    // Pump CEF before GUI extraction captures texture views; a paint callback can resize or replace them.
    @Inject(method = "extract", at = @At("HEAD"))
    public void before_extract_Rinku(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo info) {
        if (Rinku.isInitialized()) {
            Rinku.getApp().getHandle().N_DoMessageLoopWork();
        }
    }

}
