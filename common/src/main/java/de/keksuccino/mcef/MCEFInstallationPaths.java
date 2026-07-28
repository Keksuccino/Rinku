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

import java.nio.file.Path;
import java.util.Objects;

/** Resolves installer paths from the game-instance directory selected by the launcher. */
public final class MCEFInstallationPaths {
    static final String LIBRARIES_DIRECTORY_NAME = "mcef-libraries";
    static final String CACHE_VERSION_DIRECTORY_NAME = "jcef-v1";
    private static Path gameInstanceDirectory;

    private MCEFInstallationPaths() {
    }

    public static Path librariesDirectory(Path gameInstanceDirectory) {
        return Objects.requireNonNull(gameInstanceDirectory, "Game instance directory must not be null").toAbsolutePath().normalize().resolve(LIBRARIES_DIRECTORY_NAME);
    }

    public static synchronized void registerGameInstanceDirectory(Path directory) {
        Path normalized = Objects.requireNonNull(directory, "Loader-provided game instance directory must not be null").toAbsolutePath().normalize();
        if (gameInstanceDirectory != null && !gameInstanceDirectory.equals(normalized)) {
            throw new IllegalStateException("MCEF game instance directory was already registered as " + gameInstanceDirectory);
        }
        gameInstanceDirectory = normalized;
        System.setProperty("mcef.libraries.path", librariesDirectory(normalized).toString());
    }

    public static synchronized Path gameInstanceDirectory() {
        if (gameInstanceDirectory == null) {
            throw new IllegalStateException("The mod loader has not registered MCEF's game instance directory");
        }
        return gameInstanceDirectory;
    }

    public static Path librariesDirectory() {
        return librariesDirectory(gameInstanceDirectory());
    }

    static Path settingsFile() {
        return gameInstanceDirectory().resolve("config").resolve("mcef").resolve("mcef.properties");
    }

    static Path installationDirectory(Path librariesDirectory, MCEFPlatform platform, String javaCefCommit) {
        return Objects.requireNonNull(librariesDirectory, "MCEF libraries directory must not be null").toAbsolutePath().normalize().resolve(CACHE_VERSION_DIRECTORY_NAME).resolve(Objects.requireNonNull(platform, "MCEF platform must not be null").getNormalizedName()).resolve(MCEFJcefInstallationValidator.normalizeCommit(javaCefCommit));
    }
}
