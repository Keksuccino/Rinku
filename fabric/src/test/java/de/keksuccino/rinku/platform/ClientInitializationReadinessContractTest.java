package de.keksuccino.rinku.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientInitializationReadinessContractTest {

    @Test
    void fabricDoesNotRequireDeferredInitialization() {
        FabricPlatformHelper platform = new FabricPlatformHelper();

        assertFalse(platform.requiresClientInitializationDeferral());
        assertTrue(platform.isClientInitializationCandidateReady());
    }

    @Test
    void neoForgeCandidateWaitsForCompletedInitialResourceLoading() throws IOException {
        String source = readProjectSource("neoforge/src/main/java/de/keksuccino/rinku/platform/NeoForgePlatformHelper.java");

        assertTrue(source.contains("return Minecraft.getInstance().isGameLoadFinished();"));
    }

    @Test
    void retryUsesCurrentScreenWithoutResettingIt() throws IOException {
        String source = readProjectSource("common/src/main/java/de/keksuccino/rinku/mixins/MixinMinecraft.java");

        assertTrue(source.contains("handleScreenChangeWithRecursion_Rinku(minecraft.screen);"));
        assertFalse(source.contains("setScreen(minecraft.screen)"));
        assertFalse(source.contains("Thread.sleep("));
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
