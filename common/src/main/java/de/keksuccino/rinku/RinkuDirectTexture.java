package de.keksuccino.rinku;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_T;

/** Minecraft texture-manager wrapper for Rinku's externally uploaded browser pixels. */
public class RinkuDirectTexture extends AbstractTexture {

    private int width;
    private int height;
    private boolean storageAllocated;

    /**
     * Ensures immutable browser dimensions have matching OpenGL storage. Texture allocation must stay on the render
     * thread because Minecraft 1.21.1's texture abstraction exposes an OpenGL ID rather than the later GPU API.
     */
    void ensureStorage(int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (this.storageAllocated && this.width == width && this.height == height) return;

        TextureUtil.prepareImage(this.getId(), width, height);
        this.setFilter(true, false);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        this.width = width;
        this.height = height;
        this.storageAllocated = true;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    /** Compatibility name retained from the newer GPU-backed implementation. */
    public boolean isTextureViewReady() {
        return this.storageAllocated;
    }

    @Override
    public void load(ResourceManager resourceManager) throws IOException {
        // Browser pixels are supplied by CEF paint callbacks, never by the resource manager.
    }

    @Override
    public void close() {
        this.width = 0;
        this.height = 0;
        this.storageAllocated = false;
    }

}
