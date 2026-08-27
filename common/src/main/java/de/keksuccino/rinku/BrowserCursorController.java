package de.keksuccino.rinku;

import org.cef.misc.CefCursorType;

import java.util.Objects;

/** Applies browser cursor changes without taking cursor ownership away from Minecraft gameplay. */
final class BrowserCursorController {

    void apply(CefCursorType cursorType, CursorBackend backend) {
        Objects.requireNonNull(cursorType);
        Objects.requireNonNull(backend);
        if (backend.isMouseGrabbed()) return;

        if (cursorType == CefCursorType.NONE) {
            backend.hideCursor();
        } else {
            backend.showCursor(cursorType);
        }
    }

    interface CursorBackend {

        boolean isMouseGrabbed();

        void hideCursor();

        void showCursor(CefCursorType cursorType);

    }

}
