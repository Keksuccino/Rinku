/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package de.keksuccino.mcef;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Strict parsing and path construction for a configured JCEF release mirror. */
final class MCEFDownloadMirror {
    private static final Pattern SAFE_PATH_SEGMENT = Pattern.compile("[A-Za-z0-9._+-]+");
    private final URI baseUri;
    private final String checksumSourceId;

    private MCEFDownloadMirror(URI baseUri) {
        this.baseUri = baseUri;
        checksumSourceId = createChecksumSourceId(baseUri.toASCIIString());
    }

    static MCEFDownloadMirror parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("JCEF mirror URI is missing");
        }

        URI parsed;
        try {
            parsed = new URI(value.trim());
        } catch (URISyntaxException failure) {
            // URI syntax exceptions embed the rejected input. Do not retain them as a cause because
            // configured mirror paths may contain credentials even though such paths are rejected.
            throw new IllegalArgumentException("JCEF mirror URI is malformed");
        }
        if (parsed.isOpaque() || !"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("JCEF mirror must be an absolute HTTPS URI with a host");
        }
        if (parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("JCEF mirror must not contain user-info, a query, or a fragment");
        }
        if (parsed.getPort() > 65535 || hasMalformedPort(parsed)) {
            throw new IllegalArgumentException("JCEF mirror contains an invalid port");
        }

        String rawPath = parsed.getRawPath();
        String path = parsed.getPath();
        if (rawPath == null || path == null || containsUnsafePathEncoding(rawPath) || containsUnsafePath(path)) {
            throw new IllegalArgumentException("JCEF mirror contains an unsafe path");
        }
        path = stripTrailingSlashes(path);

        try {
            int canonicalPort = parsed.getPort() == 443 ? -1 : parsed.getPort();
            URI canonical = new URI("https", null, parsed.getHost().toLowerCase(Locale.ROOT), canonicalPort, path, null, null);
            return new MCEFDownloadMirror(canonical);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("JCEF mirror could not be normalized", impossible);
        }
    }

    URI assetUri(String releaseTag, String assetName) {
        validatePathSegment(releaseTag, "release tag");
        validatePathSegment(assetName, "asset name");
        String basePath = baseUri.getPath();
        String assetPath = (basePath.isEmpty() ? "" : basePath) + "/" + releaseTag + "/" + assetName;
        try {
            return new URI(baseUri.getScheme(), null, baseUri.getHost(), baseUri.getPort(), assetPath, null, null);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("JCEF release asset URI could not be constructed", impossible);
        }
    }

    String externalForm() {
        return baseUri.toASCIIString();
    }

    String safeLogIdentity() {
        return baseUri.getScheme() + "://" + baseUri.getRawAuthority();
    }

    /** Returns a stable identifier without persisting the configured mirror URI. */
    String checksumSourceId() {
        return checksumSourceId;
    }

    private static String createChecksumSourceId(String canonicalMirrorUri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalMirrorUri.getBytes(StandardCharsets.UTF_8));
            return MCEFInstallationState.configuredChecksumSource(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static boolean hasMalformedPort(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return true;
        }
        int closingBracket = authority.lastIndexOf(']');
        int colon = authority.lastIndexOf(':');
        boolean hasPortDelimiter = colon >= 0 && colon > closingBracket;
        return hasPortDelimiter && uri.getPort() < 0;
    }

    private static boolean containsUnsafePathEncoding(String rawPath) {
        String lowerPath = rawPath.toLowerCase(Locale.ROOT);
        return lowerPath.contains("%25") || lowerPath.contains("%2f") || lowerPath.contains("%5c");
    }

    private static boolean containsUnsafePath(String path) {
        if (path.indexOf('\\') >= 0) {
            return true;
        }
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character < 32 || character == 127) {
                return true;
            }
        }
        for (String component : path.split("/", -1)) {
            if (component.equals(".") || component.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return end == path.length() ? path : path.substring(0, end);
    }

    private static void validatePathSegment(String value, String description) {
        Objects.requireNonNull(value, description);
        if (value.isBlank() || value.equals(".") || value.equals("..") || !SAFE_PATH_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid JCEF " + description);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character == 127) {
                throw new IllegalArgumentException("Invalid JCEF " + description);
            }
        }
    }
}
