package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleScreenRenderingContractTest {

    @Test
    void transparentBrowserUsesPremultipliedBlendAndRestoresGuiState() throws IOException {
        String projectDirectory = Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing rinku.test.projectDir");
        Path sourcePath = Path.of(projectDirectory, "common", "src", "main", "java", "de", "keksuccino", "rinku", "example", "ExampleScreen.java");
        String source = Files.readString(sourcePath);
        int renderStart = source.indexOf("private void renderBrowserTexture(");
        int loadingIndicatorStart = source.indexOf("private void renderLoadingIndicator(", renderStart);
        assertTrue(renderStart >= 0 && loadingIndicatorStart > renderStart, "Unable to isolate ExampleScreen's browser rendering");

        String rendering = source.substring(renderStart, loadingIndicatorStart);
        int transparentBranch = rendering.indexOf("if (!browser.getRenderer().isTransparent())");
        int enableBlend = rendering.indexOf("RenderSystem.enableBlend();", transparentBranch);
        int premultipliedBlend = rendering.indexOf("RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);", enableBlend);
        int tryStart = rendering.indexOf("try {", premultipliedBlend);
        int blit = rendering.indexOf("guiGraphics.blit(", tryStart);
        int finallyStart = rendering.indexOf("finally {", blit);
        assertTrue(transparentBranch >= 0 && enableBlend > transparentBranch && premultipliedBlend > enableBlend, "Transparent CEF pixels must use premultiplied-alpha blending");
        assertTrue(tryStart > premultipliedBlend && blit > tryStart && finallyStart > blit, "The transparent texture draw must be guarded by finally");

        List<String> restores = List.of("RenderSystem.defaultBlendFunc();", "RenderSystem.disableBlend();");
        for (String restore : restores) assertTrue(rendering.indexOf(restore, finallyStart) > finallyStart, "Missing finally-block GUI state restore: " + restore);
    }
}
