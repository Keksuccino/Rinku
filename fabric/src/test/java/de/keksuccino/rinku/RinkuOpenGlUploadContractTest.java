package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuOpenGlUploadContractTest {

    @Test
    void fullPaintUpdatesPreallocatedGpuTextureWithoutReallocation() throws IOException {
        String rendererSource = readCommonSource("RinkuRenderer.java");
        int fullPaintStart = rendererSource.indexOf("protected void onPaint(ByteBuffer buffer, int width, int height)");
        int dirtyPaintStart = rendererSource.indexOf("protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height)", fullPaintStart);
        assertTrue(fullPaintStart >= 0 && dirtyPaintStart > fullPaintStart, "Unable to isolate RinkuRenderer's full-paint upload");

        String fullPaint = rendererSource.substring(fullPaintStart, dirtyPaintStart);
        assertTrue(fullPaint.contains("glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height"), "Full paints must update the storage allocated by RenderDevice.createTexture");
        assertFalse(fullPaint.contains("glTexImage2D("), "Full paints must not reallocate Minecraft's GPU texture");
    }

    @Test
    void paintTransactionEstablishesAlignmentAndRestoresEveryUnpackDefault() throws IOException {
        String browserSource = readCommonSource("RinkuBrowser.java");
        int transactionStart = browserSource.indexOf("private void onPaintRenderThread(");
        int uploadStart = browserSource.indexOf("private void uploadPaintOnRenderThread(", transactionStart);
        assertTrue(transactionStart >= 0 && uploadStart > transactionStart, "Unable to isolate RinkuBrowser's paint transaction");

        String transaction = browserSource.substring(transactionStart, uploadStart);
        int tryStart = transaction.indexOf("try {");
        int uploadCall = transaction.indexOf("uploadPaintOnRenderThread(", tryStart);
        int alignmentSetup = transaction.indexOf("GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);", tryStart);
        int finallyStart = transaction.indexOf("finally {", uploadCall);
        assertTrue(tryStart >= 0 && alignmentSetup > tryStart && uploadCall > alignmentSetup, "BGRA uploads must establish four-byte alignment before reading CEF data");
        assertTrue(finallyStart > uploadCall, "Every paint transaction must restore unpack state from a finally block");

        List<String> defaults = List.of("GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, 0);", "GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);", "GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);", "GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);");
        for (String defaultRestore : defaults) assertTrue(transaction.indexOf(defaultRestore, finallyStart) > finallyStart, "Missing finally-block restore: " + defaultRestore);
    }

    private static String readCommonSource(String fileName) throws IOException {
        String projectDirectory = Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing rinku.test.projectDir");
        return Files.readString(Path.of(projectDirectory, "common", "src", "main", "java", "de", "keksuccino", "rinku", fileName));
    }
}
