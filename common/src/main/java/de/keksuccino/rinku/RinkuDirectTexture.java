package de.keksuccino.rinku;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_T;

/** Minecraft texture wrapper for Rinku's render-thread-owned OpenGL browser texture. */
public final class RinkuDirectTexture extends AbstractTexture {

    private int width;
    private int height;
    private boolean allocated;

    @Override
    public void load(ResourceManager resourceManager) {
        // Browser pixels are supplied by CEF callbacks; there is no resource-pack payload to load.
    }

    /** Allocates this wrapper's owned OpenGL texture storage for the requested browser dimensions. */
    void allocate(int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Browser texture dimensions must be positive");
        TextureUtil.prepareImage(this.getId(), width, height);
        this.setFilter(true, false);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        this.width = width;
        this.height = height;
        this.allocated = true;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public boolean isAllocated() {
        return this.allocated;
    }

    void markReleased() {
        this.width = 0;
        this.height = 0;
        this.allocated = false;
    }

}
