package de.keksuccino.rinku;

import de.keksuccino.rinku.binarydownload.RinkuDownloader;

/**
 * Shared parsing for editable options. Keeping these checks outside the screen makes the accepted
 * input ranges explicit and prevents partially typed numbers from reaching persisted settings.
 */
final class RinkuOptionsInput {

    private RinkuOptionsInput() {
    }

    static int parseInt(String value, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(requireText(value));
            if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("Integer is outside the accepted range");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Value is not an integer", exception);
        }
    }

    static long parseLong(String value, long minimum, long maximum) {
        try {
            long parsed = Long.parseLong(requireText(value));
            if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("Long is outside the accepted range");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Value is not a long", exception);
        }
    }

    static String parseMirror(String value, boolean allowBlank) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            if (allowBlank) return null;
            throw new IllegalArgumentException("A configured-only mirror cannot be blank");
        }
        String normalized = RinkuDownloader.normalizeOfficialMirror(trimmed);
        return RinkuDownloadMirror.parse(normalized).externalForm();
    }

    static String parseUserAgent(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new NumberFormatException("Value is blank");
        return value.trim();
    }

}
