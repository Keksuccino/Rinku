/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package com.cinemamod.mcef;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MCEFDownloadMirrorTest {
    @Test
    void canonicalMirrorBuildsReleaseAssetsWithoutStringSubstitution() {
        MCEFDownloadMirror mirror = MCEFDownloadMirror.parse("HTTPS://Example.COM:8443/releases/download///");

        assertEquals("https://example.com:8443/releases/download", mirror.externalForm());
        assertEquals("https://example.com:8443", mirror.safeLogIdentity());
        assertEquals("https://example.com:8443/releases/download/java-cef-0123456789abcdef/linux_amd64.tar.gz", mirror.assetUri("java-cef-0123456789abcdef", "linux_amd64.tar.gz").toASCIIString());
    }

    @Test
    void secretBearingAndStructurallyUnsafeMirrorsAreRejected() {
        List<String> rejected = List.of(
                "http://example.com/releases",
                "https:user@example.com",
                "https://user:secret@example.com/releases",
                "https://example.com/releases?token=secret",
                "https://example.com/releases#secret",
                "https://example.com:99999/releases",
                "https://example.com:/releases",
                "https://example.com/releases/../other",
                "https://example.com/releases/%2e%2e/other",
                "https://example.com/releases/%252e%252e/other",
                "https://example.com/releases/%2fother",
                "https://example.com/releases/%5cother",
                "https://example.com/releases/%00other",
                "https://example.com\\releases",
                "example.com/releases"
        );

        for (String mirror : rejected) {
            assertThrows(IllegalArgumentException.class, () -> MCEFDownloadMirror.parse(mirror), mirror);
        }
    }

    @Test
    void releaseAndAssetNamesMustRemainSingleSafePathSegments() {
        MCEFDownloadMirror mirror = MCEFDownloadMirror.parse("https://example.com");

        assertThrows(IllegalArgumentException.class, () -> mirror.assetUri("../release", "linux.tar.gz"));
        assertThrows(IllegalArgumentException.class, () -> mirror.assetUri("release", "nested/linux.tar.gz"));
        assertThrows(IllegalArgumentException.class, () -> mirror.assetUri("release", "asset\nname"));
    }

    @Test
    void downloaderPoliciesSelectOnlyStrictCanonicalMirrors() {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        MCEFDownloader official = new MCEFDownloader("https://user:secret@example.com/private?token=secret", commit, MCEFPlatform.MACOS_ARM64, policy(MCEFDownloader.MirrorPolicy.OFFICIAL_ONLY));
        MCEFDownloader preferred = new MCEFDownloader("HTTPS://Mirror.Example:8443/private/releases/", commit, MCEFPlatform.MACOS_ARM64, policy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED));
        MCEFDownloader invalidPreferred = new MCEFDownloader("https://example.com/private?token=secret", commit, MCEFPlatform.MACOS_ARM64, policy(MCEFDownloader.MirrorPolicy.PREFER_CONFIGURED));

        assertEquals(MCEFDownloader.OFFICIAL_MIRROR + "/java-cef-" + commit + "/macos_arm64.tar.gz", official.getJavaCefDownloadUrl());
        assertEquals("https://mirror.example:8443/private/releases", preferred.getHost());
        assertEquals("https://mirror.example:8443/private/releases/java-cef-" + commit + "/macos_arm64.tar.gz.sha256", preferred.getJavaCefChecksumDownloadUrl());
        assertEquals(MCEFDownloader.OFFICIAL_MIRROR + "/java-cef-" + commit + "/macos_arm64.tar.gz", invalidPreferred.getJavaCefDownloadUrl());
        assertThrows(IllegalArgumentException.class, () -> new MCEFDownloader("https://example.com/private?token=secret", commit, MCEFPlatform.MACOS_ARM64, policy(MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY)));
        assertThrows(IllegalArgumentException.class, () -> new MCEFDownloader("", commit, MCEFPlatform.MACOS_ARM64, policy(MCEFDownloader.MirrorPolicy.CONFIGURED_ONLY)));
    }

    private static MCEFDownloader.DownloadPolicy policy(MCEFDownloader.MirrorPolicy mirrorPolicy) {
        MCEFDownloader.DownloadPolicy defaults = MCEFDownloader.DownloadPolicy.defaults();
        return new MCEFDownloader.DownloadPolicy(mirrorPolicy, defaults.enforceChecksums(), defaults.connectTimeoutMs(), defaults.readTimeoutMs(), defaults.maxArchiveBytes(), defaults.maxChecksumBytes(), defaults.maxExtractedBytes());
    }
}
