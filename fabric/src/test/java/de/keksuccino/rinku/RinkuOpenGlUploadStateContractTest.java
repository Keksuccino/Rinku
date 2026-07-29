package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuOpenGlUploadStateContractTest {

    @Test
    void everyJcefUploadRestoresMinecraftPixelUnpackDefaultsFromFinally() throws IOException {
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        Path browserSource = projectDirectory.resolve("common/src/main/java/de/keksuccino/rinku/RinkuBrowser.java");
        String source = Files.readString(browserSource);
        int methodStart = source.indexOf("private void onPaintRenderThread(");
        int methodEnd = source.indexOf("private void uploadPaintOnRenderThread(", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "Unable to isolate RinkuBrowser.onPaintRenderThread");

        String method = source.substring(methodStart, methodEnd);
        int finallyStart = method.indexOf("finally {");
        assertTrue(finallyStart >= 0, "JCEF uploads must restore global OpenGL pixel-unpack state from a finally block");
        int uploadCall = method.indexOf("uploadPaintOnRenderThread(");
        int preUploadAlignment = method.indexOf("GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);");
        assertTrue(preUploadAlignment >= 0 && preUploadAlignment < uploadCall, "JCEF uploads must establish four-byte unpack alignment before reading BGRA rows");
        List<String> expectedRestores = List.of("GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, 0);", "GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);", "GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);", "GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);");
        for (String expectedRestore : expectedRestores) assertTrue(method.indexOf(expectedRestore, finallyStart) > finallyStart, "Missing finally-block restore: " + expectedRestore);
    }
}
