package de.keksuccino.rinku.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

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
    void neoForgeCandidateWaitsForItsClientLoader() throws IOException {
        String source = readProjectSource("neoforge/src/main/java/de/keksuccino/rinku/platform/NeoForgePlatformHelper.java");

        assertTrue(source.contains("return !ClientModLoader.isLoading();"));
    }

    @Test
    void retryUsesCurrentScreenWithoutResettingIt() throws IOException {
        String source = readProjectSource("common/src/main/java/de/keksuccino/rinku/mixins/MixinMinecraft.java");

        assertTrue(source.contains("handleScreenChangeWithRecursion_Rinku(minecraft.screen);"));
        assertFalse(source.contains("setScreen(minecraft.screen)"));
    }

    private static String readProjectSource(String relativePath) throws IOException {
        Path projectDirectory = Path.of(Objects.requireNonNull(System.getProperty("rinku.test.projectDir"), "Missing project directory test property"));
        return Files.readString(projectDirectory.resolve(relativePath));
    }
}
