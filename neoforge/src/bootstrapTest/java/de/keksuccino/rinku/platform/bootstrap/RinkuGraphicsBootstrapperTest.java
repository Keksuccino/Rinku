package de.keksuccino.rinku.platform.bootstrap;

import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuGraphicsBootstrapperTest {

    @Test
    void serviceLoaderFindsExactlyOneRinkuProvider() {
        List<GraphicsBootstrapper> providers = ServiceLoader.load(GraphicsBootstrapper.class).stream().map(ServiceLoader.Provider::get).filter(RinkuGraphicsBootstrapper.class::isInstance).toList();

        assertEquals(1, providers.size());
        assertEquals("Rinku headless AWT bootstrap", providers.getFirst().name());
    }

    @Test
    void providerOverwritesUnsafeSystemProperty() {
        String previousValue = System.getProperty(AwtHeadlessBootstrap.HEADLESS_PROPERTY);
        try {
            System.setProperty(AwtHeadlessBootstrap.HEADLESS_PROPERTY, "false");

            new RinkuGraphicsBootstrapper().bootstrap(new String[0]);

            assertTrue(Boolean.getBoolean(AwtHeadlessBootstrap.HEADLESS_PROPERTY));
        } finally {
            if (previousValue == null) System.clearProperty(AwtHeadlessBootstrap.HEADLESS_PROPERTY);
            else System.setProperty(AwtHeadlessBootstrap.HEADLESS_PROPERTY, previousValue);
        }
    }
}
