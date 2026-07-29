package de.keksuccino.rinku;

import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Covers canonical and secret-safe release mirror resolution. */
class RinkuDownloadMirrorTest {
    @Test
    void officialMirrorTargetsRenamedJcefRepository() {
        assertEquals("https://github.com/Keksuccino/jcef-rinku/releases/download", RinkuDownloader.OFFICIAL_MIRROR);
    }

    @Test
    void formerRepositoryMirrorNormalizesToRenamedRepository() {
        assertEquals(RinkuDownloader.OFFICIAL_MIRROR, RinkuDownloader.normalizeOfficialMirror(RinkuDownloader.FORMER_REPOSITORY_OFFICIAL_MIRROR));
        assertEquals(RinkuDownloader.OFFICIAL_MIRROR, RinkuDownloader.normalizeOfficialMirror("HTTPS://GITHUB.COM/KEKSUCCINO/JCEF-MCEF/RELEASES/DOWNLOAD/"));
    }

    @Test
    void canonicalMirrorBuildsReleaseAssetsWithoutStringSubstitution() {
        RinkuDownloadMirror mirror = RinkuDownloadMirror.parse("HTTPS://Example.COM:8443/releases/download///");

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
            assertThrows(IllegalArgumentException.class, () -> RinkuDownloadMirror.parse(mirror), mirror);
        }
    }

    @Test
    void releaseAndAssetNamesMustRemainSingleSafePathSegments() {
        RinkuDownloadMirror mirror = RinkuDownloadMirror.parse("https://example.com");

        assertThrows(IllegalArgumentException.class, () -> mirror.assetUri("../release", "linux.tar.gz"));
        assertThrows(IllegalArgumentException.class, () -> mirror.assetUri("release", "nested/linux.tar.gz"));
        assertThrows(IllegalArgumentException.class, () -> mirror.assetUri("release", "asset\nname"));
    }

    @Test
    void downloaderPoliciesSelectOnlyStrictCanonicalMirrors() {
        String commit = JcefRuntimeIdentity.JAVA_CEF_COMMIT;
        RinkuDownloader official = new RinkuDownloader("https://user:secret@example.com/private?token=secret", OSPlatform.MACOS_ARM64, policy(RinkuDownloader.MirrorPolicy.OFFICIAL_ONLY));
        RinkuDownloader preferred = new RinkuDownloader("HTTPS://Mirror.Example:8443/private/releases/", OSPlatform.MACOS_ARM64, policy(RinkuDownloader.MirrorPolicy.PREFER_CONFIGURED));
        RinkuDownloader invalidPreferred = new RinkuDownloader("https://example.com/private?token=secret", OSPlatform.MACOS_ARM64, policy(RinkuDownloader.MirrorPolicy.PREFER_CONFIGURED));

        assertEquals(RinkuDownloader.OFFICIAL_MIRROR + "/java-cef-" + commit + "/macos_arm64.tar.gz", official.getJavaCefDownloadUrl());
        assertEquals("https://mirror.example:8443/private/releases", preferred.getHost());
        assertEquals("https://mirror.example:8443/private/releases/java-cef-" + commit + "/macos_arm64.tar.gz.sha256", preferred.getJavaCefChecksumDownloadUrl());
        assertEquals(RinkuDownloader.OFFICIAL_MIRROR + "/java-cef-" + commit + "/macos_arm64.tar.gz", invalidPreferred.getJavaCefDownloadUrl());
        assertThrows(IllegalArgumentException.class, () -> new RinkuDownloader("https://example.com/private?token=secret", OSPlatform.MACOS_ARM64, policy(RinkuDownloader.MirrorPolicy.CONFIGURED_ONLY)));
        assertThrows(IllegalArgumentException.class, () -> new RinkuDownloader("", OSPlatform.MACOS_ARM64, policy(RinkuDownloader.MirrorPolicy.CONFIGURED_ONLY)));
    }

    private static RinkuDownloader.DownloadPolicy policy(RinkuDownloader.MirrorPolicy mirrorPolicy) {
        RinkuDownloader.DownloadPolicy defaults = RinkuDownloader.DownloadPolicy.defaults();
        return new RinkuDownloader.DownloadPolicy(mirrorPolicy, defaults.enforceChecksums(), defaults.connectTimeoutMs(), defaults.readTimeoutMs(), defaults.maxArchiveBytes(), defaults.maxChecksumBytes(), defaults.maxExtractedBytes());
    }
}
