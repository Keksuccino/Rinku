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
    void acquisitionRetainsPendingBrowsersAndCreatesRequestedUrlWhenNoneAreReady() throws IOException {
        String source = readProductionSource("Rinku.java");
        String method = isolateMethod(source, "private static RinkuBrowser acquireOrCreateBrowser(", "private static RinkuBrowser createBrowserImmediately(");

        assertTrue(method.contains("ReadyResourceQueue.pollFirstReady(pool, RinkuBrowser::isNativeBrowserReady)"));
        assertFalse(method.contains("pool.pollFirst()"));
        assertTrue(method.contains("browser = createBrowserImmediately(url, transparent);"));
    }

    @Test
    void pooledBrowserReadinessUsesJcefValidityDirectly() throws IOException {
        String source = readProductionSource("RinkuBrowser.java");
        String method = isolateMethod(source, "boolean isNativeBrowserReady()", "public RinkuCursorChangeListener getCursorChangeListener()");

        assertTrue(method.contains("return isValid();"));
        assertFalse(method.contains("getIdentifier("));
    }

    private static String readProductionSource(String fileName) throws IOException {
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        return Files.readString(projectDirectory.resolve("common/src/main/java/de/keksuccino/rinku").resolve(fileName));
    }

    private static String isolateMethod(String source, String startMarker, String endMarker) {
        int methodStart = source.indexOf(startMarker);
        int methodEnd = source.indexOf(endMarker, methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "Unable to isolate method beginning with " + startMarker);
        return source.substring(methodStart, methodEnd);
    }
}
