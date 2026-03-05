package com.cinemamod.mcef;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import java.nio.ByteBuffer;
import java.util.UUID;
import static org.lwjgl.opengl.GL12.*;

public class MCEFRenderer {
    private final boolean transparent;
    private GpuTexture texture;
    private int textureWidth = 0;
    private int textureHeight = 0;
    
    // ResourceLocation for this renderer's texture
    private final ResourceLocation textureResourceLocation;
    private MCEFDirectTexture directTexture;
    private boolean textureRegistered = false;
    private ByteBuffer fallbackRgbaUploadBuffer_MCEF;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
        // Generate a unique ResourceLocation for this renderer
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureResourceLocation = ResourceLocation.fromNamespaceAndPath("mcef", "browser_" + uniqueId);
    }

    public void initialize() {
        // Create and register the direct texture wrapper with Minecraft's TextureManager
        directTexture = new MCEFDirectTexture();
        Minecraft.getInstance().getTextureManager().register(textureResourceLocation, directTexture);
        textureRegistered = true;
        syncDirectTextureViewIfNeeded();
    }

    public GpuTexture getTexture() {
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
        if (texture == null || !textureRegistered || directTexture == null) {
            return false;
        }

        if (RenderSystem.isOnRenderThread()) {
            syncDirectTextureViewIfNeeded();
        }

        return directTexture.isTextureViewReady();
    }
    
    public int getTextureID() {
        // For compatibility, return the OpenGL ID if texture exists
        if (texture instanceof GlTexture) {
            return ((GlTexture) texture).glId();
        }
        return 0;
    }

    public boolean supportsDirtyRectUpload() {
        return texture instanceof GlTexture && getTextureID() != 0;
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
        if (texture != null) {
            texture.close();
            texture = null;
        }
        fallbackRgbaUploadBuffer_MCEF = null;
        
        // Unregister from TextureManager
        if (textureRegistered && textureResourceLocation != null) {
            Minecraft.getInstance().getTextureManager().release(textureResourceLocation);
            textureRegistered = false;
        }
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        RenderSystem.assertOnRenderThread();
        // Create or recreate texture if size changed
        if (texture == null || textureWidth != width || textureHeight != height) {
            if (texture != null) {
                texture.close();
            }
            
            // Create new GpuTexture using the device
            String label = "MCEF Browser Texture " + width + "x" + height;
            texture = RenderSystem.getDevice().createTexture(
                label,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8,
                width,
                height,
                1, // depthOrLayers
                1  // mipLevels
            );
            
            textureWidth = width;
            textureHeight = height;
        }

        syncDirectTextureViewIfNeeded();

        if (texture instanceof GlTexture glTexture) {
            // Bind the texture directly using its GL ID
            GlStateManager._bindTexture(glTexture.glId());
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
            
            // Upload the full texture
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            return;
        }

        uploadWithCommandEncoder_MCEF(buffer, 0, 0, width, height);
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        RenderSystem.assertOnRenderThread();
        syncDirectTextureViewIfNeeded();
        if (texture instanceof GlTexture glTexture) {
            // Bind and update sub-region
            GlStateManager._bindTexture(glTexture.glId());
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            return;
        }

        // Fallback upload path assumes a tightly packed source rectangle.
        uploadWithCommandEncoder_MCEF(buffer, x, y, width, height);
    }

    private void syncDirectTextureViewIfNeeded() {
        if (!textureRegistered || directTexture == null || texture == null || texture.isClosed()) {
            return;
        }

        boolean needsRebind = !directTexture.isTextureViewReady()
                || directTexture.getWidth() != textureWidth
                || directTexture.getHeight() != textureHeight
                || directTexture.getBoundTexture() != texture;
        if (needsRebind) {
            directTexture.bindTexture(texture, textureWidth, textureHeight);
        }
    }

    private void uploadWithCommandEncoder_MCEF(ByteBuffer buffer, int destinationX, int destinationY, int copyWidth, int copyHeight) {
        if (texture == null || buffer == null) {
            return;
        }

        int requiredBytes = copyWidth * copyHeight * 4;
        if (requiredBytes <= 0 || buffer.capacity() < requiredBytes) {
            return;
        }

        ByteBuffer uploadBuffer = convertBgraToRgba_MCEF(buffer, requiredBytes);
        if (uploadBuffer == null) {
            return;
        }

        RenderSystem.getDevice()
                .createCommandEncoder()
                .writeToTexture(
                        texture,
                        uploadBuffer.slice(),
                        NativeImage.Format.RGBA,
                        0,
                        0,
                        destinationX,
                        destinationY,
                        copyWidth,
                        copyHeight
                );
    }

    private ByteBuffer convertBgraToRgba_MCEF(ByteBuffer sourceBuffer, int requiredBytes) {
        if (requiredBytes <= 0 || (requiredBytes & 3) != 0) {
            return null;
        }

        if (fallbackRgbaUploadBuffer_MCEF == null || fallbackRgbaUploadBuffer_MCEF.capacity() < requiredBytes) {
            fallbackRgbaUploadBuffer_MCEF = ByteBuffer.allocateDirect(requiredBytes);
        }

        ByteBuffer src = sourceBuffer.duplicate();
        src.position(0);
        src.limit(requiredBytes);

        ByteBuffer dst = fallbackRgbaUploadBuffer_MCEF.duplicate();
        dst.clear();
        dst.limit(requiredBytes);

        for (int i = 0; i < requiredBytes; i += 4) {
            byte b = src.get(i);
            byte g = src.get(i + 1);
            byte r = src.get(i + 2);
            byte a = src.get(i + 3);
            dst.put(i, r);
            dst.put(i + 1, g);
            dst.put(i + 2, b);
            dst.put(i + 3, a);
        }

        dst.position(0);
        return dst;
    }

}
