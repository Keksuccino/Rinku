package de.keksuccino.rinku;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL12.glTexSubImage2D;

public class RinkuRenderer {

    private final boolean transparent;
    private final ResourceLocation textureIdentifier;
    private RinkuDirectTexture texture;
    private int textureWidth;
    private int textureHeight;
    private boolean textureRegistered;
    private boolean textureUploaded;

    protected RinkuRenderer(boolean transparent) {
        this.transparent = transparent;
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureIdentifier = new ResourceLocation("rinku", "browser_" + uniqueId);
    }

    public void initialize() {
        RenderSystem.assertOnRenderThread();
        if (this.textureRegistered) return;
        this.texture = new RinkuDirectTexture();
        Minecraft.getInstance().getTextureManager().register(this.textureIdentifier, this.texture);
        this.textureRegistered = true;
    }

    public AbstractTexture getTexture() {
        return this.texture;
    }

    /** Returns the texture-manager location used by immediate-mode {@code GuiGraphics} rendering. */
    public ResourceLocation getTextureIdentifier() {
        return this.textureIdentifier;
    }

    public boolean isTextureReady() {
        return this.textureRegistered && this.textureUploaded && this.texture != null && this.textureWidth > 0 && this.textureHeight > 0;
    }

    public int getTextureID() {
        if (!this.isTextureReady() || !RenderSystem.isOnRenderThreadOrInit()) return 0;
        return this.texture.getId();
    }

    public boolean supportsDirtyRectUpload() {
        return this.isTextureReady();
    }

    public int getTextureWidth() {
        return this.textureWidth;
    }

    public int getTextureHeight() {
        return this.textureHeight;
    }

    public boolean isTransparent() {
        return this.transparent;
    }

    protected void cleanup() {
        RenderSystem.assertOnRenderThread();
        this.textureUploaded = false;
        this.textureWidth = 0;
        this.textureHeight = 0;
        if (this.textureRegistered) {
            Minecraft.getInstance().getTextureManager().release(this.textureIdentifier);
            this.textureRegistered = false;
        }
        this.texture = null;
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null || width <= 0 || height <= 0 || this.texture == null) return;
        if (this.textureWidth != width || this.textureHeight != height) {
            this.texture.ensureStorage(width, height);
            this.textureWidth = width;
            this.textureHeight = height;
            this.textureUploaded = false;
        }
        GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
        this.upload(buffer, 0, 0, width, height);
        this.textureUploaded = true;
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null || width <= 0 || height <= 0 || this.texture == null || !this.textureUploaded) return;
        this.upload(buffer, x, y, width, height);
    }

    private void upload(ByteBuffer buffer, int x, int y, int width, int height) {
        GlStateManager._bindTexture(this.texture.getId());
        try {
            GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        } finally {
            // Pixel-store parameters are global OpenGL state. Leaving CEF's stride/offset behind corrupts later
            // texture uploads in vanilla, Sodium, and Iris, so every upload restores their conventional defaults.
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);
        }
    }

}
