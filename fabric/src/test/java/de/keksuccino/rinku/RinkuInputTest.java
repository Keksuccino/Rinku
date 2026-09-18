package de.keksuccino.rinku;

import org.cef.event.CefKeyEvent;
import org.junit.jupiter.api.Test;

import static com.mojang.blaze3d.platform.InputConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.sdl.SDLKeycode.*;

class RinkuInputTest {

    @Test
    void alphanumericAndFunctionRangesTranslateAtBothEnds() {
        for (int key = KEY_A; key <= KEY_Z; key++) assertEquals('A' + key - KEY_A, RinkuInput.toCefKeyCode(key));
        for (int key = KEY_1; key <= KEY_9; key++) assertEquals('1' + key - KEY_1, RinkuInput.toCefKeyCode(key));
        assertEquals('0', RinkuInput.toCefKeyCode(KEY_0));
        for (int key = KEY_F1; key <= KEY_F12; key++) assertEquals(290 + key - KEY_F1, RinkuInput.toCefKeyCode(key));
        for (int key = KEY_F13; key <= KEY_F24; key++) assertEquals(302 + key - KEY_F13, RinkuInput.toCefKeyCode(key));
    }

    @Test
    void navigationAndKeypadIdentitiesAreNotConfusedWithPrintableCharacters() {
        int[][] keys = {{KEY_RETURN, 257}, {KEY_ESCAPE, 256}, {KEY_BACKSPACE, 259}, {KEY_DELETE, 261}, {KEY_LEFT, 263}, {KEY_RIGHT, 262}, {KEY_UP, 265}, {KEY_DOWN, 264}, {KEY_NUMPADENTER, 335}, {KEY_NUMPAD0, 320}, {KEY_NUMPADEQUALS, 336}, {KEY_LCONTROL, 341}, {KEY_RCONTROL, 345}, {KEY_LALT, 342}, {KEY_RALT, 346}};
        for (int[] pair : keys) assertEquals(pair[1], RinkuInput.toCefKeyCode(pair[0]));
        for (int key = KEY_NUMPAD1; key <= KEY_NUMPAD9; key++) assertEquals(321 + key - KEY_NUMPAD1, RinkuInput.toCefKeyCode(key));
        assertEquals(-1, RinkuInput.toCefKeyCode(0));
        assertEquals(-1, RinkuInput.toCefKeyCode(Integer.MAX_VALUE));
    }

    @Test
    void leftAndRightSdlModifierBitsCollapseIntoJcefFlags() {
        int[][] modifiers = {{SDL_KMOD_LSHIFT, 1}, {SDL_KMOD_RSHIFT, 1}, {SDL_KMOD_LCTRL, 2}, {SDL_KMOD_RCTRL, 2}, {SDL_KMOD_LALT, 4}, {SDL_KMOD_RALT, 4}, {SDL_KMOD_LGUI, 8}, {SDL_KMOD_RGUI, 8}, {SDL_KMOD_CAPS, 16}, {SDL_KMOD_NUM, 32}};
        for (int[] pair : modifiers) assertEquals(pair[1], RinkuInput.toCefModifiers(pair[0]));
        assertEquals(63, RinkuInput.toCefModifiers(MOD_SHIFT | MOD_CONTROL | MOD_ALT | MOD_SUPER | MOD_CAPS_LOCK | MOD_NUM_LOCK));
        assertEquals(0, RinkuInput.toCefModifiers(0));
    }

    @Test
    void pointerModifiersDoNotTurnLockKeysIntoPressedMouseButtons() {
        assertEquals(0, RinkuInput.toCefPointerModifiers(MOD_CAPS_LOCK | MOD_NUM_LOCK));
        assertEquals(3, RinkuInput.toCefPointerModifiers(SDL_KMOD_RCTRL | SDL_KMOD_LSHIFT | MOD_NUM_LOCK));
    }

    @Test
    void mouseButtonsRetainLeftMiddleRightOrderingAndRejectUnsupportedButtons() {
        assertEquals(0, RinkuInput.toCefMouseButton(MOUSE_BUTTON_LEFT));
        assertEquals(1, RinkuInput.toCefMouseButton(MOUSE_BUTTON_MIDDLE));
        assertEquals(2, RinkuInput.toCefMouseButton(MOUSE_BUTTON_RIGHT));
        assertEquals(-1, RinkuInput.toCefMouseButton(0));
        assertEquals(-1, RinkuInput.toCefMouseButton(MOUSE_BUTTON_4));
    }

    @Test
    void pressAndReleaseLeaveNativeCodeResolutionToJcef() {
        for (int action : new int[]{CefKeyEvent.KEY_PRESS, CefKeyEvent.KEY_RELEASE}) {
            CefKeyEvent letter = RinkuInput.createKeyEvent(action, KEY_A, 2);
            assertEquals(action, letter.id);
            assertEquals('A', letter.keyCode);
            assertEquals('A', letter.keyChar);
            assertEquals(2, letter.modifiers);
            assertEquals(0, letter.scancode);
            CefKeyEvent special = RinkuInput.createKeyEvent(action, KEY_LEFT, 0);
            assertEquals('\uffff', special.keyChar);
            assertEquals(0, special.scancode);
        }
    }

}
