package de.keksuccino.rinku;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Covers validation performed before editable option values are persisted. */
class RinkuOptionsInputTest {

    @Test
    void integerParserAcceptsInclusiveBoundsAndTrimsInput() {
        assertEquals(1_000, RinkuOptionsInput.parseInt(" 1000 ", 1_000, 300_000));
        assertEquals(300_000, RinkuOptionsInput.parseInt("300000", 1_000, 300_000));
    }

    @Test
    void integerParserRejectsMalformedAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> RinkuOptionsInput.parseInt("", 1_000, 300_000));
        assertThrows(IllegalArgumentException.class, () -> RinkuOptionsInput.parseInt("1.5", 1_000, 300_000));
        assertThrows(IllegalArgumentException.class, () -> RinkuOptionsInput.parseInt("999", 1_000, 300_000));
        assertThrows(IllegalArgumentException.class, () -> RinkuOptionsInput.parseInt("300001", 1_000, 300_000));
    }

    @Test
    void longParserAcceptsInclusiveBoundsAndRejectsOverflow() {
        assertEquals(1_048_576L, RinkuOptionsInput.parseLong("1048576", 1_048_576L, 10_485_760_000L));
        assertEquals(10_485_760_000L, RinkuOptionsInput.parseLong("10485760000", 1_048_576L, 10_485_760_000L));
        assertThrows(IllegalArgumentException.class, () -> RinkuOptionsInput.parseLong("104857600000000000000", 1_048_576L, 10_485_760_000L));
    }

    @Test
    void mirrorParserCanonicalizesSafeUrlsAndHandlesPolicySensitiveBlanks() {
        assertEquals("https://mirror.example:8443/releases", RinkuOptionsInput.parseMirror(" HTTPS://Mirror.Example:8443/releases/// ", false));
        assertNull(RinkuOptionsInput.parseMirror("  ", true));
        assertThrows(IllegalArgumentException.class, () -> RinkuOptionsInput.parseMirror("  ", false));
        assertThrows(IllegalArgumentException.class, () -> RinkuOptionsInput.parseMirror("https://user:secret@mirror.example/releases", false));
    }

    @Test
    void userAgentParserUsesNullForDefaultAndTrimsOverrides() {
        assertNull(RinkuOptionsInput.parseUserAgent(" "));
        assertNull(RinkuOptionsInput.parseUserAgent("null"));
        assertEquals("Rinku Test/1.0", RinkuOptionsInput.parseUserAgent(" Rinku Test/1.0 "));
    }

}
