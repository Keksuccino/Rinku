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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CefUtilPermissionsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void posixFallbackAddsExecuteBitsWithoutRemovingExistingPermissions() throws Exception {
        assumeTrue(Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null);
        Path executable = temporaryDirectory.resolve("helper");
        Files.writeString(executable, "helper");
        Set<PosixFilePermission> original = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_READ);
        Files.setPosixFilePermissions(executable, original);

        CefUtil.addUnixExecutePermissions(executable);

        Set<PosixFilePermission> expected = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
        assertEquals(expected, Files.getPosixFilePermissions(executable));
    }

    @Test
    void portableFallbackChecksAndPublishesAnExecutableFile() throws Exception {
        Path executable = temporaryDirectory.resolve("portable-helper");
        Files.writeString(executable, "helper");

        CefUtil.addPortableExecutePermissions(executable);

        assertTrue(Files.isExecutable(executable));
        assertTrue(Files.isReadable(executable));
        assertTrue(Files.isWritable(executable));
    }

    @Test
    void knownExecutableInventoryCoversLinuxAndEveryMacHelperVariant() {
        Path installation = Path.of("runtime");

        assertEquals(List.of(installation.resolve("jcef_helper"), installation.resolve("chrome-sandbox")), CefUtil.unixExecutablePaths(installation, MCEFPlatform.LINUX_ARM64));
        assertEquals(List.of(), CefUtil.unixExecutablePaths(installation, MCEFPlatform.WINDOWS_AMD64));
        assertEquals(List.of(installation.resolve("jcef_app.app/Contents/MacOS/JavaAppLauncher"), installation.resolve("jcef_app.app/Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework"), installation.resolve("jcef_app.app/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper"), installation.resolve("jcef_app.app/Contents/Frameworks/jcef Helper (Alerts).app/Contents/MacOS/jcef Helper (Alerts)"), installation.resolve("jcef_app.app/Contents/Frameworks/jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)"), installation.resolve("jcef_app.app/Contents/Frameworks/jcef Helper (Plugin).app/Contents/MacOS/jcef Helper (Plugin)"), installation.resolve("jcef_app.app/Contents/Frameworks/jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)")), CefUtil.unixExecutablePaths(installation, MCEFPlatform.MACOS_ARM64));
    }
}
