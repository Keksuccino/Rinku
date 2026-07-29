package de.keksuccino.rinku;

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
