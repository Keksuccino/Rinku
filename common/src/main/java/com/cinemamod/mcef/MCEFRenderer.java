package com.cinemamod.mcef;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import java.nio.ByteBuffer;
import java.util.UUID;
import static org.lwjgl.opengl.GL12.*;

public class MCEFRenderer {
    private final boolean transparent;
    private ManagedTexture texture;
    private int textureWidth = 0;
    private int textureHeight = 0;
    private boolean textureUploaded = false;
    
    // ResourceLocation for this renderer's texture
    private final ResourceLocation textureResourceLocation;
    private boolean textureRegistered = false;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
        // Generate a unique ResourceLocation for this renderer
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureResourceLocation = new ResourceLocation("mcef", "browser_" + uniqueId);
    }

    public void initialize() {
        texture = new ManagedTexture();
        Minecraft.getInstance().getTextureManager().register(textureResourceLocation, texture);
        textureRegistered = true;
    }

    public AbstractTexture getTexture() {
        return texture;
    }
    
    /**
     * Gets the ResourceLocation that can be used with GuiGraphics and other Minecraft rendering methods.
     * This ResourceLocation is registered with the TextureManager and points to the browser's texture.
     */
    public ResourceLocation getTextureLocation() {
        return textureResourceLocation;
    }
    
    /**
     * Check if the texture is ready for rendering with GuiGraphics
     */
    public boolean isTextureReady() {
        return textureRegistered && textureUploaded && textureWidth > 0 && textureHeight > 0;
    }
    
    public int getTextureID() {
        if (texture == null || !textureUploaded || !RenderSystem.isOnRenderThreadOrInit()) {
            return 0;
        }

        return texture.getId();
    }

    public boolean supportsDirtyRectUpload() {
        return texture != null && textureUploaded && textureWidth > 0 && textureHeight > 0;
    }
    
    public int getTextureWidth() {
        return textureWidth;
    }
    
    public int getTextureHeight() {
        return textureHeight;
    }

    public boolean isTransparent() {
        return transparent;
    }

    protected void cleanup() {
        textureUploaded = false;
        textureWidth = 0;
        textureHeight = 0;
        
        // Unregister from TextureManager
        if (textureRegistered) {
            Minecraft.getInstance().getTextureManager().release(textureResourceLocation);
            textureRegistered = false;
        }

        texture = null;
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        RenderSystem.assertOnRenderThread();

        if (buffer == null || width <= 0 || height <= 0 || texture == null) {
            return;
        }

        ensureTextureStorage_MCEF(width, height);

        int textureId = texture.getId();
        GlStateManager._bindTexture(textureId);
        GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        textureUploaded = true;
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null || width <= 0 || height <= 0 || texture == null || !textureUploaded) {
            return;
        }

        GlStateManager._bindTexture(texture.getId());
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
    }

    private void ensureTextureStorage_MCEF(int width, int height) {
        if (textureWidth == width && textureHeight == height) {
            return;
        }

        int textureId = texture.getId();
        TextureUtil.prepareImage(textureId, width, height);

        texture.setFilter(true, false);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        textureWidth = width;
        textureHeight = height;
        textureUploaded = false;
    }

    private static final class ManagedTexture extends AbstractTexture {
        @Override
        public void load(ResourceManager resourceManager) {
        }
    }
}
