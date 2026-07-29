package de.keksuccino.rinku.example;

import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class ExampleScreenRenderingTest {
    @Test
    void transparentBrowserUsesPremultipliedAlphaPipeline() {
        assertSame(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, ExampleScreen.selectBrowserRenderPipeline(true));
    }

    @Test
    void opaqueBrowserUsesStraightAlphaPipeline() {
        assertSame(RenderPipelines.GUI_TEXTURED, ExampleScreen.selectBrowserRenderPipeline(false));
    }
}
