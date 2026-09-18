package de.keksuccino.rinku;

import net.minecraft.client.Minecraft;
import org.cef.misc.CefCursorType;
import org.lwjgl.sdl.SDLMouse;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.sdl.SDLMouse.*;

/** Render-thread-owned SDL cursors. Never changes SDL relative mode, which belongs to Minecraft. */
final class SdlBrowserCursorBackend implements BrowserCursorController.CursorBackend {

    static final SdlBrowserCursorBackend INSTANCE = new SdlBrowserCursorBackend();

    private final Map<Integer, Long> cursors = new HashMap<>();
    private boolean closed;

    private SdlBrowserCursorBackend() {}

    @Override
    public boolean isMouseGrabbed() {
        return closed || Minecraft.getInstance().mouseHandler.isMouseGrabbed();
    }

    @Override
    public void hideCursor() {
        SDLMouse.SDL_HideCursor();
    }

    @Override
    public void showCursor(CefCursorType cursorType) {
        int shape = systemCursor(cursorType);
        long cursor = cursors.computeIfAbsent(shape, SDLMouse::SDL_CreateSystemCursor);
        SDLMouse.SDL_SetCursor(cursor == 0L ? SDLMouse.SDL_GetDefaultCursor() : cursor);
        SDLMouse.SDL_ShowCursor();
    }

    void close() {
        if (closed) return;
        closed = true;
        if (cursors.isEmpty()) return;
        SDLMouse.SDL_SetCursor(SDLMouse.SDL_GetDefaultCursor());
        for (long cursor : cursors.values()) {
            if (cursor != 0L) SDLMouse.SDL_DestroyCursor(cursor);
        }
        cursors.clear();
    }

    static int systemCursor(CefCursorType type) {
        return switch (type) {
            case CROSS, CELL -> SDL_SYSTEM_CURSOR_CROSSHAIR;
            case HAND -> SDL_SYSTEM_CURSOR_POINTER;
            case IBEAM, VERTICAL_IBEAM -> SDL_SYSTEM_CURSOR_TEXT;
            case WAIT -> SDL_SYSTEM_CURSOR_WAIT;
            case PROGRESS -> SDL_SYSTEM_CURSOR_PROGRESS;
            case EAST_RESIZE, WEST_RESIZE, EAST_WEST_RESIZE, COLUMN_RESIZE -> SDL_SYSTEM_CURSOR_EW_RESIZE;
            case NORTH_RESIZE, SOUTH_RESIZE, NORTH_SOUTH_RESIZE, ROW_RESIZE -> SDL_SYSTEM_CURSOR_NS_RESIZE;
            case NORTH_EAST_RESIZE, SOUTH_WEST_RESIZE, NORTH_EAST_SOUTH_WEST_RESIZE -> SDL_SYSTEM_CURSOR_NESW_RESIZE;
            case NORTH_WEST_RESIZE, SOUTH_EAST_RESIZE, NORTH_WEST_SOUTH_EAST_RESIZE -> SDL_SYSTEM_CURSOR_NWSE_RESIZE;
            case MOVE, MIDDLE_PANNING, DND_MOVE -> SDL_SYSTEM_CURSOR_MOVE;
            case NO_DROP, NOT_ALLOWED, DND_NONE -> SDL_SYSTEM_CURSOR_NOT_ALLOWED;
            default -> SDL_SYSTEM_CURSOR_DEFAULT;
        };
    }

}
