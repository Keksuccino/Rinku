package de.keksuccino.rinku;

import org.cef.event.CefKeyEvent;

import static com.mojang.blaze3d.platform.InputConstants.*;
import static org.lwjgl.sdl.SDLScancode.*;

/** Translates Minecraft's SDL input into the stable GLFW-style ABI of JCEF's legacy event DTOs. */
final class RinkuInput {

    static final int CEF_MOD_CONTROL = 2;
    static final int CEF_MOD_ALT = 4;

    private RinkuInput() {}

    static CefKeyEvent createKeyEvent(int action, int key, int cefModifiers) {
        int cefKey = toCefKeyCode(key);
        // SDL scancodes are USB key identities, not Windows scan codes, macOS key codes, or XKB codes.
        // Leave scancode zero so the pinned JCEF runtime derives the correct platform-native code.
        return new CefKeyEvent(action, cefKey, cefKey >= 32 && cefKey <= 126 ? (char) cefKey : '\uffff', cefModifiers);
    }

    static int toCefKeyCode(int key) {
        if (key >= KEY_A && key <= KEY_Z) return 'A' + key - KEY_A;
        if (key >= KEY_1 && key <= KEY_9) return '1' + key - KEY_1;
        if (key >= KEY_F1 && key <= KEY_F12) return 290 + key - KEY_F1;
        if (key >= KEY_F13 && key <= KEY_F24) return 302 + key - KEY_F13;
        if (key >= KEY_NUMPAD1 && key <= KEY_NUMPAD9) return 321 + key - KEY_NUMPAD1;
        return switch (key) {
            case KEY_0 -> '0';
            case KEY_SPACE -> ' ';
            case KEY_APOSTROPHE -> '\'';
            case KEY_COMMA -> ',';
            case KEY_MINUS -> '-';
            case KEY_PERIOD -> '.';
            case KEY_SLASH -> '/';
            case KEY_SEMICOLON -> ';';
            case KEY_EQUALS -> '=';
            case KEY_LBRACKET -> '[';
            case KEY_BACKSLASH -> '\\';
            case KEY_RBRACKET -> ']';
            case KEY_GRAVE -> '`';
            case SDL_SCANCODE_NONUSBACKSLASH -> 161;
            case SDL_SCANCODE_NONUSHASH -> 162;
            case KEY_ESCAPE -> 256;
            case KEY_RETURN -> 257;
            case KEY_TAB -> 258;
            case KEY_BACKSPACE -> 259;
            case KEY_INSERT -> 260;
            case KEY_DELETE -> 261;
            case KEY_RIGHT -> 262;
            case KEY_LEFT -> 263;
            case KEY_DOWN -> 264;
            case KEY_UP -> 265;
            case KEY_PAGEUP -> 266;
            case KEY_PAGEDOWN -> 267;
            case KEY_HOME -> 268;
            case KEY_END -> 269;
            case KEY_CAPSLOCK -> 280;
            case KEY_SCROLLLOCK -> 281;
            case KEY_NUMLOCK -> 282;
            case KEY_PRINTSCREEN -> 283;
            case KEY_PAUSE -> 284;
            case KEY_NUMPAD0 -> 320;
            case SDL_SCANCODE_KP_PERIOD -> 330;
            case SDL_SCANCODE_KP_DIVIDE -> 331;
            case KEY_MULTIPLY -> 332;
            case SDL_SCANCODE_KP_MINUS -> 333;
            case KEY_ADD -> 334;
            case KEY_NUMPADENTER -> 335;
            case KEY_NUMPADEQUALS -> 336;
            case KEY_LSHIFT -> 340;
            case KEY_LCONTROL -> 341;
            case KEY_LALT -> 342;
            case KEY_LGUI -> 343;
            case KEY_RSHIFT -> 344;
            case KEY_RCONTROL -> 345;
            case KEY_RALT -> 346;
            case KEY_RGUI -> 347;
            case SDL_SCANCODE_APPLICATION, SDL_SCANCODE_MENU -> 348;
            default -> -1;
        };
    }

    static int toCefModifiers(int modifiers) {
        int translated = 0;
        if ((modifiers & MOD_SHIFT) != 0) translated |= 1;
        if ((modifiers & MOD_CONTROL) != 0) translated |= CEF_MOD_CONTROL;
        if ((modifiers & MOD_ALT) != 0) translated |= CEF_MOD_ALT;
        if ((modifiers & MOD_SUPER) != 0) translated |= 8;
        if ((modifiers & MOD_CAPS_LOCK) != 0) translated |= 16;
        if ((modifiers & MOD_NUM_LOCK) != 0) translated |= 32;
        return translated;
    }

    static int toCefMouseButton(int button) {
        return switch (button) {
            case MOUSE_BUTTON_LEFT -> 0;
            case MOUSE_BUTTON_MIDDLE -> 1;
            case MOUSE_BUTTON_RIGHT -> 2;
            default -> -1;
        };
    }

    static int toCefPointerModifiers(int modifiers) {
        // JCEF's legacy pointer DTO uses bits 4/5 for pressed mouse buttons, not Caps/Num Lock.
        return toCefModifiers(modifiers) & 0xf;
    }

}
