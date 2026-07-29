package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuPreloadedBrowserReadinessContractTest {

    @Test
    void acquisitionNeverRemovesAnAsynchronouslyPendingBrowser() throws IOException {
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        String source = Files.readString(projectDirectory.resolve("common/src/main/java/de/keksuccino/rinku/Rinku.java"));
        int methodStart = source.indexOf("private static RinkuBrowser acquireOrCreateBrowser(");
        int methodEnd = source.indexOf("private static RinkuBrowser createBrowserImmediately(", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "Unable to isolate Rinku.acquireOrCreateBrowser");

        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("ReadyResourceQueue.pollFirstReady(pool, RinkuBrowser::isNativeBrowserReady)"));
        assertFalse(method.contains("pool.pollFirst()"));
        assertTrue(method.contains("browser = createBrowserImmediately(url, transparent);"), "The no-ready fallback must preserve the requested URL as JCEF's initial native URL");
    }

    @Test
    void pooledBrowserReadinessUsesJcefsCompletedNativeCreationContract() throws IOException {
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        String source = Files.readString(projectDirectory.resolve("common/src/main/java/de/keksuccino/rinku/RinkuBrowser.java"));
        int methodStart = source.indexOf("boolean isNativeBrowserReady()");
        int methodEnd = source.indexOf("public RinkuCursorChangeListener", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "Unable to isolate RinkuBrowser.isNativeBrowserReady");

        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("return isValid();"));
        assertFalse(method.contains("getIdentifier()"));
    }
}
