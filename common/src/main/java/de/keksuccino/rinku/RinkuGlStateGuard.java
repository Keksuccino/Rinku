package de.keksuccino.rinku;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;

/** Restores every mutable OpenGL input touched by legacy browser texture uploads. */
final class RinkuGlStateGuard implements AutoCloseable {

    private final StateAccess stateAccess;
    private final int textureBinding;
    private final int unpackAlignment;
    private final int unpackRowLength;
    private final int unpackSkipPixels;
    private final int unpackSkipRows;
    private final int pixelUnpackBufferBinding;
    private boolean closed;

    RinkuGlStateGuard(StateAccess stateAccess) {
        this.stateAccess = Objects.requireNonNull(stateAccess, "stateAccess");
        this.textureBinding = stateAccess.getInteger(GL_TEXTURE_BINDING_2D);
        this.unpackAlignment = stateAccess.getInteger(GL_UNPACK_ALIGNMENT);
        this.unpackRowLength = stateAccess.getInteger(GL_UNPACK_ROW_LENGTH);
        this.unpackSkipPixels = stateAccess.getInteger(GL_UNPACK_SKIP_PIXELS);
        this.unpackSkipRows = stateAccess.getInteger(GL_UNPACK_SKIP_ROWS);
        this.pixelUnpackBufferBinding = stateAccess.getInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        if (this.pixelUnpackBufferBinding != 0) stateAccess.bindPixelUnpackBuffer(0);
    }

    static RinkuGlStateGuard capture() {
        RenderSystem.assertOnRenderThread();
        return new RinkuGlStateGuard(NativeStateAccess.INSTANCE);
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        this.stateAccess.pixelStore(GL_UNPACK_ALIGNMENT, this.unpackAlignment);
        this.stateAccess.pixelStore(GL_UNPACK_ROW_LENGTH, this.unpackRowLength);
        this.stateAccess.pixelStore(GL_UNPACK_SKIP_PIXELS, this.unpackSkipPixels);
        this.stateAccess.pixelStore(GL_UNPACK_SKIP_ROWS, this.unpackSkipRows);
        this.stateAccess.bindTexture(this.textureBinding);
        this.stateAccess.bindPixelUnpackBuffer(this.pixelUnpackBufferBinding);
    }

    /** Narrow seam that lets the restoration contract be tested without creating a native OpenGL context. */
    interface StateAccess {

        int getInteger(int parameter);

        void pixelStore(int parameter, int value);

        void bindTexture(int textureId);

        void bindPixelUnpackBuffer(int bufferId);
    }

    private enum NativeStateAccess implements StateAccess {
        INSTANCE;

        @Override
        public int getInteger(int parameter) {
            return GlStateManager._getInteger(parameter);
        }

        @Override
        public void pixelStore(int parameter, int value) {
            GlStateManager._pixelStore(parameter, value);
        }

        @Override
        public void bindTexture(int textureId) {
            GlStateManager._bindTexture(textureId);
        }

        @Override
        public void bindPixelUnpackBuffer(int bufferId) {
            GlStateManager._glBindBuffer(GL_PIXEL_UNPACK_BUFFER, bufferId);
        }
    }

}
