package de.keksuccino.rinku.platform.bootstrap;

import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapServiceProviderContractTest {

    @Test
    void graphicsBootstrapperSatisfiesAutomaticModuleServiceLoaderContract() throws ReflectiveOperationException {
        assertProviderContract(RinkuGraphicsBootstrapper.class, GraphicsBootstrapper.class);
        assertEquals("Rinku headless AWT bootstrap", new RinkuGraphicsBootstrapper().name());
    }

    @Test
    void embeddedModLocatorSatisfiesAutomaticModuleServiceLoaderContract() throws ReflectiveOperationException {
        assertProviderContract(RinkuEmbeddedModLocator.class, IModFileCandidateLocator.class);
        assertEquals("Rinku embedded-mod locator", new RinkuEmbeddedModLocator().toString());
    }

    private static void assertProviderContract(Class<?> providerClass, Class<?> serviceClass) throws ReflectiveOperationException {
        int modifiers = providerClass.getModifiers();
        assertTrue(Modifier.isPublic(modifiers));
        assertTrue(Modifier.isFinal(modifiers));
        assertFalse(Modifier.isAbstract(modifiers));
        assertFalse(providerClass.isMemberClass());
        assertTrue(serviceClass.isAssignableFrom(providerClass));
        assertTrue(Modifier.isPublic(providerClass.getConstructor().getModifiers()));
        providerClass.getConstructor().newInstance();
    }

}
