package de.keksuccino.mcef;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFInitializationControllerTest {
    @Test
    void admitsOnlyOneInitializationAtATime() {
        MCEFInitializationController controller = new MCEFInitializationController();

        assertTrue(controller.canInitialize());
        assertEquals(MCEFInitializationController.BeginResult.STARTED, controller.beginInitialization());
        assertFalse(controller.canInitialize());
        assertEquals(MCEFInitializationController.BeginResult.REJECTED, controller.beginInitialization());
    }

    @Test
    void reportsCompletedInitializationWithoutRestarting() {
        MCEFInitializationController controller = new MCEFInitializationController();

        assertEquals(MCEFInitializationController.BeginResult.STARTED, controller.beginInitialization());
        controller.markInitialized();

        assertTrue(controller.isInitialized());
        assertFalse(controller.canInitialize());
        assertEquals(MCEFInitializationController.BeginResult.ALREADY_INITIALIZED, controller.beginInitialization());
    }

    @Test
    void rejectsCompletionWithoutAnActiveInitialization() {
        MCEFInitializationController controller = new MCEFInitializationController();

        assertThrows(IllegalStateException.class, controller::markInitialized);
        assertTrue(controller.canInitialize());
    }

    @Test
    void permanentlyRejectsInitializationAfterTermination() {
        MCEFInitializationController controller = new MCEFInitializationController();
        controller.terminate();

        assertFalse(controller.isInitialized());
        assertFalse(controller.canInitialize());
        assertEquals(MCEFInitializationController.BeginResult.REJECTED, controller.beginInitialization());
        assertEquals(MCEFInitializationController.BeginResult.REJECTED, controller.beginInitialization());
    }
}
