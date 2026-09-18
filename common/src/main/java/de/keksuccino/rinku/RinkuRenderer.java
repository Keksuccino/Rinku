package de.keksuccino.rinku;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public class RinkuRenderer {

    private final boolean transparent;
    private GpuTexture texture;
    private int textureWidth = 0;
    private int textureHeight = 0;
    
    // Identifier for this renderer's texture
    private final Identifier textureIdentifier;
    private RinkuDirectTexture directTexture;
    private boolean textureRegistered = false;
    private ByteBuffer rgbaUploadBuffer;

    protected RinkuRenderer(boolean transparent) {
        this.transparent = transparent;
        // Generate a unique Identifier for this renderer
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureIdentifier = Identifier.fromNamespaceAndPath("rinku", "browser_" + uniqueId);
    }

    public void initialize() {
        // Create and register the direct texture wrapper with Minecraft's TextureManager
        directTexture = new RinkuDirectTexture();
        Minecraft.getInstance().getTextureManager().register(textureIdentifier, directTexture);
        textureRegistered = true;
        syncDirectTextureViewIfNeeded();
    }

    public GpuTexture getTexture() {
        return texture;
    }
    
    /**
     * Gets the Identifier that can be used with GuiGraphics and other Minecraft rendering methods.
     * This Identifier is registered with the TextureManager and points to the browser's texture.
     */
    public Identifier getTextureIdentifier() {
        return textureIdentifier;
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
        return texture != null && !texture.isClosed();
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
        if (directTexture != null) directTexture.close();
        if (texture != null) {
            texture.close();
            texture = null;
        }
        rgbaUploadBuffer = null;
        
        // Unregister from TextureManager
        if (textureRegistered && textureIdentifier != null) {
            Minecraft.getInstance().getTextureManager().release(textureIdentifier);
            textureRegistered = false;
        }
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (texture == null || textureWidth != width || textureHeight != height) {
            GpuTexture replacement = RenderSystem.getDevice().createTexture("Rinku Browser Texture " + width + "x" + height, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            if (directTexture != null) directTexture.close();
            if (texture != null) texture.close();
            texture = replacement;
            textureWidth = width;
            textureHeight = height;
        }
        onPaint(buffer, width, 0, 0, 0, 0, width, height);
    }

    /** Uploads a tightly packed source rectangle. */
    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        onPaint(buffer, width, 0, 0, x, y, width, height);
    }

    /** Source coordinates and row stride are explicit: popup sources and destination textures have different geometry. */
    protected void onPaint(ByteBuffer buffer, int sourceWidth, int sourceX, int sourceY, int destinationX, int destinationY, int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (!supportsDirtyRectUpload()) return;
        syncDirectTextureViewIfNeeded();
        rgbaUploadBuffer = convertBgraToRgba(buffer, sourceWidth, sourceX, sourceY, width, height, rgbaUploadBuffer);
        // Both RenderPearl backends consume/copy the source bytes during this call. Minecraft submits its shared
        // command encoder at the end of the frame, so this staging buffer can be reused for the next dirty region.
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, rgbaUploadBuffer, 0, 0, destinationX, destinationY, width, height);
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

    static ByteBuffer convertBgraToRgba(ByteBuffer source, int sourceWidth, int sourceX, int sourceY, int width, int height, @Nullable ByteBuffer reusable) {
        long requiredPixels = (long) width * height;
        long lastPixel = ((long) sourceY + height - 1L) * sourceWidth + sourceX + width;
        if (sourceWidth <= 0 || sourceX < 0 || sourceY < 0 || width <= 0 || height <= 0 || (long) sourceX + width > sourceWidth || requiredPixels > Integer.MAX_VALUE / 4 || lastPixel > source.capacity() / 4L) {
            throw new IllegalArgumentException("Browser paint rectangle exceeds its source buffer");
        }
        int requiredBytes = (int) requiredPixels * 4;
        ByteBuffer target = reusable;
        if (target == null || target.capacity() < requiredBytes) target = ByteBuffer.allocateDirect(requiredBytes);
        target.clear();
        target.limit(requiredBytes);
        target.order(ByteOrder.LITTLE_ENDIAN);
        // CEF supplies an entire BGRA surface even for partial paints. Ignore callback buffer cursors and
        // pack only the requested rows as RGBA, preserving alpha for transparent browsers and popup overlays.
        ByteBuffer pixels = source.duplicate().clear().order(ByteOrder.LITTLE_ENDIAN);
        for (int row = 0; row < height; row++) {
            int offset = ((sourceY + row) * sourceWidth + sourceX) * 4;
            for (int column = 0; column < width; column++, offset += 4) {
                int bgra = pixels.getInt(offset);
                target.putInt((bgra & 0xff00ff00) | ((bgra & 0xff) << 16) | ((bgra >>> 16) & 0xff));
            }
        }
        return target.flip();
    }

}
