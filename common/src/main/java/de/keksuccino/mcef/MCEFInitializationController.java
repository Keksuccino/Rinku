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

/** Coordinates MCEF's one-shot CEF process lifecycle. */
final class MCEFInitializationController {
    enum BeginResult {
        STARTED,
        ALREADY_INITIALIZED,
        REJECTED
    }

    private enum State {
        READY,
        INITIALIZING,
        INITIALIZED,
        TERMINATED
    }

    private State state = State.READY;

    synchronized BeginResult beginInitialization() {
        return switch (state) {
            case READY -> {
                state = State.INITIALIZING;
                yield BeginResult.STARTED;
            }
            case INITIALIZED -> BeginResult.ALREADY_INITIALIZED;
            case INITIALIZING, TERMINATED -> BeginResult.REJECTED;
        };
    }

    synchronized void markInitialized() {
        if (state != State.INITIALIZING) throw new IllegalStateException("MCEF initialization was not in progress");
        state = State.INITIALIZED;
    }

    synchronized void terminate() {
        state = State.TERMINATED;
    }

    synchronized boolean canInitialize() {
        return state == State.READY;
    }

    synchronized boolean isInitialized() {
        return state == State.INITIALIZED;
    }
}
