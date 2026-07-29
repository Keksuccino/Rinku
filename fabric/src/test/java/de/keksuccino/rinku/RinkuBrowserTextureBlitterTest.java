package de.keksuccino.rinku;

import com.mojang.blaze3d.platform.GlStateManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RinkuBrowserTextureBlitterTest {

    @Test
    void transparentDrawUsesPremultipliedAlphaAndRestoresScreenDefaults() {
        RecordingBlendStateAccess state = new RecordingBlendStateAccess();

        RinkuBrowserTextureBlitter.beginBrowserDraw(true, state);
        RinkuBrowserTextureBlitter.finishBrowserDraw(true, state);

        assertEquals(List.of("enable", "blend:ONE:ONE_MINUS_SRC_ALPHA:ONE:ONE_MINUS_SRC_ALPHA", "default", "disable"), state.events);
    }

    @Test
    void opaqueDrawRemainsUnblended() {
        RecordingBlendStateAccess state = new RecordingBlendStateAccess();

        RinkuBrowserTextureBlitter.beginBrowserDraw(false, state);
        RinkuBrowserTextureBlitter.finishBrowserDraw(false, state);

        assertEquals(List.of("disable", "disable"), state.events);
    }

    private static final class RecordingBlendStateAccess implements RinkuBrowserTextureBlitter.BlendStateAccess {

        private final List<String> events = new ArrayList<>();

        @Override
        public void enableBlend() {
            events.add("enable");
        }

        @Override
        public void disableBlend() {
            events.add("disable");
        }

        @Override
        public void blendFuncSeparate(GlStateManager.SourceFactor sourceColor, GlStateManager.DestFactor destinationColor, GlStateManager.SourceFactor sourceAlpha, GlStateManager.DestFactor destinationAlpha) {
            events.add("blend:" + sourceColor + ":" + destinationColor + ":" + sourceAlpha + ":" + destinationAlpha);
        }

        @Override
        public void defaultBlendFunc() {
            events.add("default");
        }
    }
}
