package de.keksuccino.rinku;

import org.cef.misc.CefCursorType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserCursorControllerTest {

    private final BrowserCursorController controller = new BrowserCursorController();
    private final RecordingBackend backend = new RecordingBackend();

    @Test
    void grabbedMinecraftMouseRejectsVisibleAndHiddenBrowserCursorChanges() {
        this.backend.mouseGrabbed = true;

        this.controller.apply(CefCursorType.HAND, this.backend);
        this.controller.apply(CefCursorType.NONE, this.backend);

        assertEquals(0, this.backend.hideCalls);
        assertEquals(0, this.backend.showCalls);
        assertNull(this.backend.lastShownCursor);
    }

    @Test
    void ungrabbedMouseCanBeHiddenByTheBrowser() {
        this.controller.apply(CefCursorType.NONE, this.backend);

        assertEquals(1, this.backend.hideCalls);
        assertEquals(0, this.backend.showCalls);
    }

    @Test
    void ungrabbedMouseReceivesTheRequestedVisibleCursor() {
        this.controller.apply(CefCursorType.IBEAM, this.backend);

        assertEquals(0, this.backend.hideCalls);
        assertEquals(1, this.backend.showCalls);
        assertEquals(CefCursorType.IBEAM, this.backend.lastShownCursor);
    }

    @Test
    void nullInputsFailBeforeAnyCursorMutation() {
        assertThrows(NullPointerException.class, () -> this.controller.apply(null, this.backend));
        assertThrows(NullPointerException.class, () -> this.controller.apply(CefCursorType.POINTER, null));

        assertEquals(0, this.backend.hideCalls);
        assertEquals(0, this.backend.showCalls);
    }

    private static final class RecordingBackend implements BrowserCursorController.CursorBackend {

        private boolean mouseGrabbed;
        private int hideCalls;
        private int showCalls;
        private CefCursorType lastShownCursor;

        @Override
        public boolean isMouseGrabbed() {
            return this.mouseGrabbed;
        }

        @Override
        public void hideCursor() {
            this.hideCalls++;
        }

        @Override
        public void showCursor(CefCursorType cursorType) {
            this.showCalls++;
            this.lastShownCursor = cursorType;
        }

    }

}
