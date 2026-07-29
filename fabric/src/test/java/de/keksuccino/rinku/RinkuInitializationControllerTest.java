package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RinkuInitializationControllerTest {
    @Test
    void admitsOnlyOneInitializationAtATime() {
        RinkuInitializationController controller = new RinkuInitializationController();

        assertTrue(controller.canInitialize());
        assertEquals(RinkuInitializationController.BeginResult.STARTED, controller.beginInitialization());
        assertFalse(controller.canInitialize());
        assertEquals(RinkuInitializationController.BeginResult.REJECTED, controller.beginInitialization());
    }

    @Test
    void reportsCompletedInitializationWithoutRestarting() {
        RinkuInitializationController controller = new RinkuInitializationController();

        assertEquals(RinkuInitializationController.BeginResult.STARTED, controller.beginInitialization());
        controller.markInitialized();

        assertTrue(controller.isInitialized());
        assertFalse(controller.canInitialize());
        assertEquals(RinkuInitializationController.BeginResult.ALREADY_INITIALIZED, controller.beginInitialization());
    }

    @Test
    void rejectsCompletionWithoutAnActiveInitialization() {
        RinkuInitializationController controller = new RinkuInitializationController();

        assertThrows(IllegalStateException.class, controller::markInitialized);
        assertTrue(controller.canInitialize());
    }

    @Test
    void permanentlyRejectsInitializationAfterTermination() {
        RinkuInitializationController controller = new RinkuInitializationController();
        controller.terminate();

        assertFalse(controller.isInitialized());
        assertFalse(controller.canInitialize());
        assertEquals(RinkuInitializationController.BeginResult.REJECTED, controller.beginInitialization());
        assertEquals(RinkuInitializationController.BeginResult.REJECTED, controller.beginInitialization());
    }
}
