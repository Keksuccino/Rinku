package de.keksuccino.rinku.example;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleScreenRenderingContractTest {

    @Test
    void transparentBrowserUsesMinecraftsPremultipliedGuiPipeline() throws IOException {
        String projectDirectory = Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing rinku.test.projectDir");
        Path sourcePath = Path.of(projectDirectory, "common", "src", "main", "java", "de", "keksuccino", "rinku", "example", "ExampleScreen.java");
        String source = Files.readString(sourcePath);
        int renderStart = source.indexOf("private void renderBrowserTexture(");
        int loadingIndicatorStart = source.indexOf("private void renderLoadingIndicator(", renderStart);
        assertTrue(renderStart >= 0 && loadingIndicatorStart > renderStart, "Unable to isolate ExampleScreen's browser rendering");

        String rendering = source.substring(renderStart, loadingIndicatorStart);
        int transparentCheck = rendering.indexOf("browser.getRenderer().isTransparent()");
        int premultipliedPipeline = rendering.indexOf("RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA", transparentCheck);
        int opaquePipeline = rendering.indexOf("RenderPipelines.GUI_TEXTURED", premultipliedPipeline + 1);
        int blit = rendering.indexOf("guiGraphics.blit(", opaquePipeline);
        assertTrue(transparentCheck >= 0 && premultipliedPipeline > transparentCheck, "Transparent CEF pixels must select the premultiplied-alpha GUI pipeline");
        assertTrue(opaquePipeline > premultipliedPipeline && blit > opaquePipeline, "Opaque browsers must retain the ordinary GUI pipeline before the texture is blitted");
    }
}
