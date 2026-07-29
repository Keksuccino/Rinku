package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuGlUploadContractTest {
    @Test
    void fullUploadUsesAllocatedTextureStorage() throws IOException {
        String source = readProjectSource("common/src/main/java/de/keksuccino/rinku/RinkuRenderer.java");

        assertFalse(source.contains("glTexImage2D("));
        assertTrue(source.contains("glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);"));
    }

    @Test
    void browserPaintTransactionAlwaysRestoresUnpackState() throws IOException {
        String source = readProjectSource("common/src/main/java/de/keksuccino/rinku/RinkuBrowser.java");
        int transactionStart = source.indexOf("    private void onPaintRenderThread(");
        int nextMethodStart = source.indexOf("    private static void restoreUnpackPixelStoreDefaults()", transactionStart);
        String transaction = source.substring(transactionStart, nextMethodStart);

        assertTrue(transaction.contains("try {\n            GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);"));
        assertTrue(transaction.contains("} finally {\n            restoreUnpackPixelStoreDefaults();\n        }"));
        assertTrue(source.contains("GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, 0);"));
        assertTrue(source.contains("GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);"));
        assertTrue(source.contains("GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);"));
        assertTrue(source.contains("GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);"));
    }

    private static String readProjectSource(String relativePath) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
            directory = directory.getParent();
        }
        throw new IOException("Could not locate project source " + relativePath);
    }
}
