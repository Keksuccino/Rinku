package de.keksuccino.rinku.mixins;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(TextureManager.class)
public interface AccessorMixinTextureManager {

    @Accessor("byPath")
    Map<ResourceLocation, AbstractTexture> getByPath_Rinku();

}
