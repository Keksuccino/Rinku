package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;

class RinkuGlStateGuardTest {

    @Test
    void restoresAllTextureUploadStateWhenGuardedUploadFails() {
        FakeStateAccess state = new FakeStateAccess();
        state.values.put(GL_TEXTURE_BINDING_2D, 91);
        state.values.put(GL_UNPACK_ALIGNMENT, 8);
        state.values.put(GL_UNPACK_ROW_LENGTH, 47);
        state.values.put(GL_UNPACK_SKIP_PIXELS, 3);
        state.values.put(GL_UNPACK_SKIP_ROWS, 5);
        state.values.put(GL_PIXEL_UNPACK_BUFFER_BINDING, 22);

        assertThrows(ExpectedUploadFailure.class, () -> {
            try (RinkuGlStateGuard ignored = new RinkuGlStateGuard(state)) {
                assertEquals(0, state.values.get(GL_PIXEL_UNPACK_BUFFER_BINDING));
                state.bindTexture(1001);
                state.pixelStore(GL_UNPACK_ALIGNMENT, 4);
                state.pixelStore(GL_UNPACK_ROW_LENGTH, 1920);
                state.pixelStore(GL_UNPACK_SKIP_PIXELS, 13);
                state.pixelStore(GL_UNPACK_SKIP_ROWS, 17);
                throw new ExpectedUploadFailure();
            }
        });

        assertEquals(91, state.values.get(GL_TEXTURE_BINDING_2D));
        assertEquals(8, state.values.get(GL_UNPACK_ALIGNMENT));
        assertEquals(47, state.values.get(GL_UNPACK_ROW_LENGTH));
        assertEquals(3, state.values.get(GL_UNPACK_SKIP_PIXELS));
        assertEquals(5, state.values.get(GL_UNPACK_SKIP_ROWS));
        assertEquals(22, state.values.get(GL_PIXEL_UNPACK_BUFFER_BINDING));
    }

    private static final class FakeStateAccess implements RinkuGlStateGuard.StateAccess {

        private final Map<Integer, Integer> values = new HashMap<>();

        @Override
        public int getInteger(int parameter) {
            return this.values.getOrDefault(parameter, 0);
        }

        @Override
        public void pixelStore(int parameter, int value) {
            this.values.put(parameter, value);
        }

        @Override
        public void bindTexture(int textureId) {
            this.values.put(GL_TEXTURE_BINDING_2D, textureId);
        }

        @Override
        public void bindPixelUnpackBuffer(int bufferId) {
            this.values.put(GL_PIXEL_UNPACK_BUFFER_BINDING, bufferId);
        }
    }

    private static final class ExpectedUploadFailure extends RuntimeException {
    }
}
