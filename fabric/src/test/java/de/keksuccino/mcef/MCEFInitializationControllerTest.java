/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

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
