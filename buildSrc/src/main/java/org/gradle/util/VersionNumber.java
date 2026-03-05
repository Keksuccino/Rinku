package org.gradle.util;

import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compatibility shim for Gradle 9+ where org.gradle.util.VersionNumber was removed.
 * This is only present so legacy plugins (for example mixingradle 0.7.x) can still load.
 */
public final class VersionNumber implements Comparable<VersionNumber>, Serializable {
    private static final long serialVersionUID = 1L;

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-.]?([A-Za-z0-9][A-Za-z0-9._-]*))?$"
    );

    public static final VersionNumber UNKNOWN = new VersionNumber(0, 0, 0, null);

    private final int major;
    private final int minor;
    private final int micro;
    private final String qualifier;

    public VersionNumber(int major, int minor, int micro, String qualifier) {
        this.major = major;
        this.minor = minor;
        this.micro = micro;
        this.qualifier = qualifier;
    }

    public static VersionNumber parse(String value) {
        if (value == null) {
            return UNKNOWN;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return UNKNOWN;
        }

        Matcher matcher = VERSION_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return UNKNOWN;
        }

        int major = parseIntSafe(matcher.group(1));
        int minor = parseIntSafe(matcher.group(2));
        int micro = parseIntSafe(matcher.group(3));
        String qualifier = matcher.group(4);
        if (qualifier != null && qualifier.isBlank()) {
            qualifier = null;
        }
        return new VersionNumber(major, minor, micro, qualifier);
    }

    private static int parseIntSafe(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getMicro() {
        return micro;
    }

    public String getQualifier() {
        return qualifier;
    }

    public VersionNumber getBaseVersion() {
        return new VersionNumber(major, minor, micro, null);
    }

    @Override
    public int compareTo(VersionNumber other) {
        if (other == null) {
            return 1;
        }

        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) {
            return cmp;
        }

        cmp = Integer.compare(this.minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }

        cmp = Integer.compare(this.micro, other.micro);
        if (cmp != 0) {
            return cmp;
        }

        if (Objects.equals(this.qualifier, other.qualifier)) {
            return 0;
        }

        if (this.qualifier == null) {
            return 1;
        }
        if (other.qualifier == null) {
            return -1;
        }

        return this.qualifier.compareToIgnoreCase(other.qualifier);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof VersionNumber other)) {
            return false;
        }
        return major == other.major
                && minor == other.minor
                && micro == other.micro
                && Objects.equals(qualifier, other.qualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, micro, qualifier);
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + micro;
        return qualifier == null ? base : base + "-" + qualifier;
    }
}
