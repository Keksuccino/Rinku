package de.keksuccino.rinku;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Draws browser textures with the blend convention required by CEF's premultiplied OSR pixels. */
public final class RinkuBrowserTextureBlitter {

    private RinkuBrowserTextureBlitter() {
    }

    public static void blit(PoseStack poseStack, RinkuBrowser browser, int x, int y, int width, int height) {
        Objects.requireNonNull(poseStack, "poseStack");
        Objects.requireNonNull(browser, "browser");
        RinkuRenderer renderer = browser.getRenderer();
        ResourceLocation textureLocation = browser.getTextureIdentifier();
        int textureWidth = renderer.getTextureWidth();
        int textureHeight = renderer.getTextureHeight();
        if (textureLocation == null || textureWidth <= 0 || textureHeight <= 0 || width <= 0 || height <= 0) return;

        boolean transparent = renderer.isTransparent();
        try {
            beginBrowserDraw(transparent, NativeBlendStateAccess.INSTANCE);
            RenderSystem.setShaderTexture(0, textureLocation);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            GuiComponent.blit(poseStack, x, y, width, height, 0.0F, 0.0F, textureWidth, textureHeight, textureWidth, textureHeight);
        } finally {
            finishBrowserDraw(transparent, NativeBlendStateAccess.INSTANCE);
        }
    }

    static void beginBrowserDraw(boolean transparent, BlendStateAccess stateAccess) {
        if (!transparent) {
            stateAccess.disableBlend();
            return;
        }
        stateAccess.enableBlend();
        stateAccess.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    static void finishBrowserDraw(boolean transparent, BlendStateAccess stateAccess) {
        if (transparent) stateAccess.defaultBlendFunc();
        stateAccess.disableBlend();
    }

    /** Narrow seam for checking blend transitions without requiring a native OpenGL context. */
    interface BlendStateAccess {

        void enableBlend();

        void disableBlend();

        void blendFuncSeparate(GlStateManager.SourceFactor sourceColor, GlStateManager.DestFactor destinationColor, GlStateManager.SourceFactor sourceAlpha, GlStateManager.DestFactor destinationAlpha);

        void defaultBlendFunc();
    }

    private enum NativeBlendStateAccess implements BlendStateAccess {
        INSTANCE;

        @Override
        public void enableBlend() {
            RenderSystem.enableBlend();
        }

        @Override
        public void disableBlend() {
            RenderSystem.disableBlend();
        }

        @Override
        public void blendFuncSeparate(GlStateManager.SourceFactor sourceColor, GlStateManager.DestFactor destinationColor, GlStateManager.SourceFactor sourceAlpha, GlStateManager.DestFactor destinationAlpha) {
            RenderSystem.blendFuncSeparate(sourceColor, destinationColor, sourceAlpha, destinationAlpha);
        }

        @Override
        public void defaultBlendFunc() {
            RenderSystem.defaultBlendFunc();
        }
    }
}
