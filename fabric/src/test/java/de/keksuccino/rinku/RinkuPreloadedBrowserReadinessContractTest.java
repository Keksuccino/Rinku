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
    void acquisitionUsesNativeValidityAndPreservesPendingPreloads() throws IOException {
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        String source = Files.readString(projectDirectory.resolve("common/src/main/java/de/keksuccino/rinku/Rinku.java"));
        int methodStart = source.indexOf("private static RinkuBrowser acquireOrCreateBrowser(");
        int methodEnd = source.indexOf("private static RinkuBrowser createBrowserImmediately(", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "Unable to isolate Rinku.acquireOrCreateBrowser");

        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("ReadyResourceQueue.pollFirstReady(pool, RinkuBrowser::isValid)"));
        assertTrue(method.contains("createBrowserImmediately(url, transparent)"));
        assertFalse(method.contains("pool.pollFirst()"));
        assertFalse(method.contains("getIdentifier()"));
    }
}
