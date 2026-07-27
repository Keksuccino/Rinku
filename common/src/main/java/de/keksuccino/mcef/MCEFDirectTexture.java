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

package de.keksuccino.mcef;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * Texture wrapper that exposes an externally managed GPU texture to Minecraft's texture manager.
 */
public class MCEFDirectTexture extends AbstractTexture {

    private int width;
    private int height;

    public MCEFDirectTexture() {
    }

    /**
     * Bind this wrapper to an existing GPU texture managed by MCEFRenderer.
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
