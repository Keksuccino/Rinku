package de.keksuccino.rinku;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * Texture wrapper that exposes an externally managed GPU texture to Minecraft's texture manager.
 */
public class RinkuDirectTexture extends AbstractTexture {

    private int width;
    private int height;

    public RinkuDirectTexture() {
    }

    /**
     * Bind this wrapper to an existing GPU texture managed by RinkuRenderer.
     *
     * @param textureSource The texture to expose to Minecraft's texture manager
     * @param width The width of the texture
     * @param height The height of the texture
     */
    public void bindTexture(GpuTexture textureSource, int width, int height) {
        if (this.textureView != null) {
            this.textureView.close();
            this.textureView = null;
        }

        this.texture = null;

        if (textureSource != null && !textureSource.isClosed() && width > 0 && height > 0) {
            this.texture = textureSource;
            this.textureView = RenderSystem.getDevice().createTextureView(this.texture);
            this.width = width;
            this.height = height;
        } else {
            this.texture = null;
            this.width = 0;
            this.height = 0;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public GpuTexture getBoundTexture() {
        return this.texture;
    }

    public boolean isTextureViewReady() {
        return this.texture != null
                && !this.texture.isClosed()
                && this.textureView != null
                && !this.textureView.isClosed();
    }

    @Override
    public void close() {
        if (this.textureView != null) {
            this.textureView.close();
            this.textureView = null;
        }
        this.texture = null;
        this.width = 0;
        this.height = 0;
    }

}
