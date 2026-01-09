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

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.ByteBuffer;
import java.util.UUID;
import static org.lwjgl.opengl.GL12.*;

public class MCEFRenderer {
    private final boolean transparent;
    private GpuTexture texture;
    private int textureWidth = 0;
    private int textureHeight = 0;
    
    // Identifier for this renderer's texture
    private final Identifier textureIdentifier;
    private MCEFDirectTexture directTexture;
    private boolean textureRegistered = false;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
        // Generate a unique Identifier for this renderer
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureIdentifier = Identifier.fromNamespaceAndPath("mcef", "browser_" + uniqueId);
    }

    public void initialize() {
        // Create and register the direct texture wrapper with Minecraft's TextureManager
        directTexture = new MCEFDirectTexture();
        Minecraft.getInstance().getTextureManager().register(textureIdentifier, directTexture);
        textureRegistered = true;
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
        return texture != null && textureRegistered && directTexture != null;
    }
    
    public int getTextureID() {
        // For compatibility, return the OpenGL ID if texture exists
        if (texture instanceof GlTexture) {
            return ((GlTexture) texture).glId();
        }
        return 0;
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
        
        // Unregister from TextureManager
        if (textureRegistered && textureIdentifier != null) {
            Minecraft.getInstance().getTextureManager().release(textureIdentifier);
            textureRegistered = false;
        }
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
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

            // Configure texture parameters
            //TODO EXPERIMENTAL
//            texture.setTextureFilter(FilterMode.LINEAR, FilterMode.LINEAR, false);
//            texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
            
            textureWidth = width;
            textureHeight = height;
            
            // Update the direct texture wrapper to point to our new texture
            if (directTexture != null && texture instanceof GlTexture glTexture) {
                directTexture.setDirectTextureId(glTexture.glId(), width, height);
            }
        }
        
        if (texture instanceof GlTexture glTexture) {
            // Bind the texture directly using its GL ID
            GlStateManager._bindTexture(glTexture.glId());
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
            
            // Upload the full texture
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        if (texture instanceof GlTexture glTexture) {
            // Bind and update sub-region
            GlStateManager._bindTexture(glTexture.glId());
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                    GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }
    }
}
