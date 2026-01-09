package com.cinemamod.mcef.mixins;

import com.cinemamod.mcef.MCEF;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "render", at = @At("HEAD"))
    public void head_render_MCEF(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo info) {
        if (MCEF.isInitialized()) {
            MCEF.getApp().getHandle().N_DoMessageLoopWork();
        }
    }

}
