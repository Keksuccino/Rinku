package de.keksuccino.rinku;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_T;

/** Texture-manager entry whose OpenGL storage is updated directly by Chromium paint callbacks. */
public final class RinkuDirectTexture extends AbstractTexture {

    private int width;
    private int height;

    @Override
    public void load(ResourceManager resourceManager) {
        // Browser pixels are supplied by CEF, so resource reloads must leave this dynamic texture untouched.
    }

    void ensureStorage(int width, int height) {
        if (this.width == width && this.height == height) return;
        TextureUtil.prepareImage(this.getId(), width, height);
        this.setFilter(true, false);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    @Override
    public void close() {
        this.width = 0;
        this.height = 0;
    }

}
