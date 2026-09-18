package de.keksuccino.rinku;

import org.cef.misc.CefCursorType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.sdl.SDLMouse.*;

class SdlBrowserCursorBackendTest {

    @Test
    void browserCursorsMapToSdlShapes() {
        assertEquals(SDL_SYSTEM_CURSOR_POINTER, SdlBrowserCursorBackend.systemCursor(CefCursorType.HAND));
        assertEquals(SDL_SYSTEM_CURSOR_TEXT, SdlBrowserCursorBackend.systemCursor(CefCursorType.IBEAM));
        assertEquals(SDL_SYSTEM_CURSOR_NESW_RESIZE, SdlBrowserCursorBackend.systemCursor(CefCursorType.NORTH_EAST_RESIZE));
        assertEquals(SDL_SYSTEM_CURSOR_NWSE_RESIZE, SdlBrowserCursorBackend.systemCursor(CefCursorType.SOUTH_EAST_RESIZE));
        assertEquals(SDL_SYSTEM_CURSOR_NS_RESIZE, SdlBrowserCursorBackend.systemCursor(CefCursorType.ROW_RESIZE));
        assertEquals(SDL_SYSTEM_CURSOR_EW_RESIZE, SdlBrowserCursorBackend.systemCursor(CefCursorType.COLUMN_RESIZE));
        assertEquals(SDL_SYSTEM_CURSOR_MOVE, SdlBrowserCursorBackend.systemCursor(CefCursorType.DND_MOVE));
        assertEquals(SDL_SYSTEM_CURSOR_NOT_ALLOWED, SdlBrowserCursorBackend.systemCursor(CefCursorType.NO_DROP));
    }

    @Test
    void unsupportedAndUnknownCursorsUseTheSystemDefault() {
        assertEquals(SDL_SYSTEM_CURSOR_DEFAULT, SdlBrowserCursorBackend.systemCursor(CefCursorType.UNKNOWN));
        assertEquals(SDL_SYSTEM_CURSOR_DEFAULT, SdlBrowserCursorBackend.systemCursor(CefCursorType.CUSTOM));
        assertEquals(SDL_SYSTEM_CURSOR_DEFAULT, SdlBrowserCursorBackend.systemCursor(CefCursorType.POINTER));
    }

}
