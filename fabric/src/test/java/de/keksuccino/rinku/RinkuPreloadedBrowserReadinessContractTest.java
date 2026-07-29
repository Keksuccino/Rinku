package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuPreloadedBrowserReadinessContractTest {

    @Test
    void acquisitionRequiresExplicitNativeBrowserReadiness() throws IOException {
        String source = readProjectSource("common/src/main/java/de/keksuccino/rinku/Rinku.java");
        int methodStart = source.indexOf("private static RinkuBrowser acquireOrCreateBrowser(");
        int methodEnd = source.indexOf("private static RinkuBrowser createBrowserImmediately(", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "Unable to isolate Rinku.acquireOrCreateBrowser");

        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("ReadyResourceQueue.pollFirstReady(pool, RinkuBrowser::isValid)"));
        assertFalse(method.contains("pool.pollFirst()"));
        assertFalse(method.contains("getIdentifier()"));
    }

    private static String readProjectSource(String relativePath) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
            directory = directory.getParent();
        }
        throw new IOException("Could not locate project source " + relativePath);
    }
}
