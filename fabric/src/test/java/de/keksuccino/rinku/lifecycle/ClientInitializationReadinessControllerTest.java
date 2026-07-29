package de.keksuccino.rinku.lifecycle;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientInitializationReadinessControllerTest {

    @Test
    void exposesEveryMemberReferencedAfterMixinMergeFromANeutralPackage() throws NoSuchMethodException {
        assertFalse(ClientInitializationReadinessController.class.getPackageName().startsWith("de.keksuccino.rinku.mixins"));
        assertTrue(Modifier.isPublic(ClientInitializationReadinessController.class.getModifiers()));
        assertTrue(Modifier.isPublic(ClientInitializationReadinessController.class.getConstructor().getModifiers()));
        assertTrue(Modifier.isPublic(ClientInitializationReadinessController.class.getMethod("shouldDeferInitialization", boolean.class).getModifiers()));
        assertTrue(Modifier.isPublic(ClientInitializationReadinessController.class.getMethod("observeTick", boolean.class, boolean.class).getModifiers()));
    }

    @Test
    void fabricNeverDefersScreenInitialization() {
        ClientInitializationReadinessController controller = new ClientInitializationReadinessController();

        assertFalse(controller.shouldDeferInitialization(false));
        assertFalse(controller.observeTick(false, false));
    }

    @Test
    void neoForgeRequiresTwoConsecutiveStableTicksBeforeRetry() {
        ClientInitializationReadinessController controller = new ClientInitializationReadinessController();

        assertTrue(controller.shouldDeferInitialization(true));
        assertFalse(controller.observeTick(true, true));
        assertTrue(controller.shouldDeferInitialization(true));
        assertFalse(controller.observeTick(true, false));
        assertFalse(controller.observeTick(true, true));
        assertTrue(controller.shouldDeferInitialization(true));
        assertTrue(controller.observeTick(true, true));
        assertFalse(controller.shouldDeferInitialization(true));
    }

    @Test
    void deferredRetryIsConsumedExactlyOnce() {
        ClientInitializationReadinessController controller = new ClientInitializationReadinessController();

        assertTrue(controller.shouldDeferInitialization(true));
        assertTrue(controller.shouldDeferInitialization(true));
        assertFalse(controller.observeTick(true, true));
        assertTrue(controller.observeTick(true, true));
        assertFalse(controller.observeTick(true, true));
        assertFalse(controller.shouldDeferInitialization(true));
    }

    @Test
    void readinessWithoutADeferredScreenDoesNotInventARetry() {
        ClientInitializationReadinessController controller = new ClientInitializationReadinessController();

        assertFalse(controller.observeTick(true, true));
        assertFalse(controller.observeTick(true, true));
        assertFalse(controller.shouldDeferInitialization(true));
    }
}
