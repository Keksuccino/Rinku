package de.keksuccino.rinku;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.ByteBuffer;
import java.util.UUID;
import static org.lwjgl.opengl.GL12.*;

public class RinkuRenderer {
    private final boolean transparent;
    private GpuTexture texture;
    private int textureWidth = 0;
    private int textureHeight = 0;

    // Identifier for this renderer's texture
    private final Identifier textureIdentifier;
    private RinkuDirectTexture directTexture;
    private boolean textureRegistered = false;
    private ByteBuffer fallbackRgbaUploadBuffer;

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
        if (texture == null || texture.isClosed()) return false;
        // A loader or render-validation layer may wrap GlTexture. Its command encoder still supports destination
        // subregions, while BgraRgbaConverter supplies the tightly packed source region that API requires.
        return !(texture instanceof GlTexture) || getTextureID() != 0;
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
        fallbackRgbaUploadBuffer = null;

        // Unregister from TextureManager
        if (textureRegistered && textureIdentifier != null) {
            Minecraft.getInstance().getTextureManager().release(textureIdentifier);
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
            String label = "Rinku Browser Texture " + width + "x" + height;
            texture = RenderSystem.getDevice().createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, width, height, 1, 1);

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

            // createTexture already allocated the RGBA8 storage. Reallocating it for every CEF frame would discard
            // the device-managed allocation and add avoidable driver work, so full paints update level zero in place.
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            return;
        }

        uploadWithCommandEncoder(buffer, width, 0, 0, 0, 0, width, height);
    }

    protected void onPaint(ByteBuffer buffer, int sourceRowLength, int sourceX, int sourceY, int destinationX, int destinationY, int width, int height) {
        RenderSystem.assertOnRenderThread();
        syncDirectTextureViewIfNeeded();
        if (texture instanceof GlTexture glTexture) {
            // Bind and update sub-region
            GlStateManager._bindTexture(glTexture.glId());
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, sourceRowLength);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, sourceX);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, sourceY);
            glTexSubImage2D(GL_TEXTURE_2D, 0, destinationX, destinationY, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            return;
        }

        uploadWithCommandEncoder(buffer, sourceRowLength, sourceX, sourceY, destinationX, destinationY, width, height);
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

    private void uploadWithCommandEncoder(ByteBuffer buffer, int sourceRowLength, int sourceX, int sourceY, int destinationX, int destinationY, int copyWidth, int copyHeight) {
        if (texture == null || buffer == null) {
            return;
        }

        ByteBuffer uploadBuffer = convertBgraToRgba(buffer, sourceRowLength, sourceX, sourceY, copyWidth, copyHeight);
        if (uploadBuffer == null) {
            return;
        }

        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, uploadBuffer.slice(), NativeImage.Format.RGBA, 0, 0, destinationX, destinationY, copyWidth, copyHeight);
    }

    private ByteBuffer convertBgraToRgba(ByteBuffer sourceBuffer, int sourceRowLength, int sourceX, int sourceY, int copyWidth, int copyHeight) {
        long requiredBytesLong = (long) copyWidth * copyHeight * 4L;
        if (requiredBytesLong <= 0L || requiredBytesLong > Integer.MAX_VALUE) {
            return null;
        }
        int requiredBytes = (int) requiredBytesLong;

        if (fallbackRgbaUploadBuffer == null || fallbackRgbaUploadBuffer.capacity() < requiredBytes) {
            fallbackRgbaUploadBuffer = ByteBuffer.allocateDirect(requiredBytes);
        }

        ByteBuffer dst = fallbackRgbaUploadBuffer.duplicate();
        dst.clear();
        dst.limit(requiredBytes);
        if (BgraRgbaConverter.convertRegion(sourceBuffer, sourceRowLength, sourceX, sourceY, copyWidth, copyHeight, dst) != requiredBytes) return null;
        dst.position(0);
        return dst;
    }
}
