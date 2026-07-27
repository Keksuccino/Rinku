/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package de.keksuccino.mcef;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates terminal admission shutdown and native close as independent exactly-once actions. */
final class BrowserCloseController {
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean nativeCloseStarted = new AtomicBoolean();

    boolean requestClose(Runnable stopAdmission) {
        Objects.requireNonNull(stopAdmission, "stopAdmission");
        if (!closeRequested.compareAndSet(false, true)) {
            return false;
        }
        stopAdmission.run();
        return true;
    }

    boolean closeNative(Runnable nativeClose) {
        Objects.requireNonNull(nativeClose, "nativeClose");
        if (!nativeCloseStarted.compareAndSet(false, true)) {
            return false;
        }
        nativeClose.run();
        return true;
    }

    boolean markNativeClosed() {
        return nativeCloseStarted.compareAndSet(false, true);
    }

    boolean isCloseRequested() {
        return closeRequested.get();
    }
}
