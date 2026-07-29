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

    @Inject(method = "render", at = @At("HEAD"))
    public void head_render_RINKU(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo info) {
        if (Rinku.isInitialized()) {
            Rinku.getApp().getHandle().N_DoMessageLoopWork();
        }
    }

}
