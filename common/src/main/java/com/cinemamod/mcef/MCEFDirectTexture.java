/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.cinemamod.mcef;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * A more efficient texture implementation that directly wraps an existing OpenGL texture ID.
 * This bypasses the normal texture creation pipeline and allows us to use an existing texture
 * directly with Minecraft's rendering system.
 */
public class MCEFDirectTexture extends AbstractTexture {

    private int width;
    private int height;

    public MCEFDirectTexture() {
    }

    /**
     * Directly set the texture to an existing OpenGL texture ID.
     * This is more efficient than creating a new texture and copying data.
     *
     * @param textureId The OpenGL texture ID to wrap
     * @param width The width of the texture
     * @param height The height of the texture
     */
    public void setDirectTextureId(int textureId, int width, int height) {
        // If we already have a texture and it's not the same ID, don't close it
        // (we don't own these textures, MCEFRenderer does)

        if (this.textureView != null) {
            this.textureView.close();
            this.textureView = null;
        }

        this.texture = null;

        if (textureId > 0) {
            // Create a custom GlTexture that wraps the existing ID
            this.texture = new DirectGlTexture(textureId, width, height);
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

    public int getDirectTextureId() {
        if (this.texture instanceof GlTexture glTexture && !glTexture.isClosed()) {
            return glTexture.glId();
        }
        return 0;
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

    /**
     * Custom GlTexture implementation that wraps an existing OpenGL texture ID
     * without managing its lifecycle.
     */
    private static class DirectGlTexture extends GlTexture {

        private final int width;
        private final int height;

        protected DirectGlTexture(int textureId, int width, int height) {
            // Provide the wrapped texture's metadata to the GPU texture base
            super(GpuTexture.USAGE_TEXTURE_BINDING, "MCEF Direct Texture", TextureFormat.RGBA8, width, height, 1, 1, textureId);
            this.width = width;
            this.height = height;
            // Mark as not closed
            this.closed = false;
        }

        @Override
        public void close() {
            // Texture lifecycle is owned externally; no-op to avoid deleting the GL resource
        }

        @Override
        public int getWidth(int mipLevel) {
            return width >> mipLevel;
        }

        @Override
        public int getHeight(int mipLevel) {
            return height >> mipLevel;
        }

    }

}
