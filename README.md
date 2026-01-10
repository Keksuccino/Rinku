<p align="center">
<img width="64" alt="mcef_icon" src="https://github.com/user-attachments/assets/c11845ff-b57c-4e15-928f-19055874903c" />
</p>

# MCEF (Minecraft Chromium Embedded Framework)

MCEF is a mod and library for adding the Chromium web browser into Minecraft.

MCEF is based on java-cef (Java Chromium Embedded Framework), which is based on CEF (Chromium Embedded Framework), which is based on Chromium. It was originally created by montoyo. It was rewritten and currently maintained by the CinemaMod Group.

MCEF contains a downloader system for downloading the java-cef & CEF binaries required by the Chromium browser. This requires a connection to https://mcef-download.cinemamod.com.

Discussion: https://discord.gg/rhayah27GC

Current Chromium version: `116.0.5845.190`

## Supported Platforms

- Windows 10/11 (x86_64, arm64)*
- macOS 11 or greater (Intel, Apple Silicon)
- GNU Linux glibc 2.31 or greater (x86_64, arm64)**

*Some antivirus software may prevent MCEF from initializing. You may have to disable your antivirus or whitelist the mod files for MCEF to work properly.

**This mod will not work on Android.

## Using MCEF in Your Projects

Snapshots and releases are mirrored on a static Maven repository hosted at `https://keksuccino.github.io/maven/`. Add the repository to your build script, then depend on the loader-specific artifact you need. Artifacts follow the pattern `de.keksuccino:<mod_id>-<loader>:<mod_version>-<minecraft_version>`.

### Fabric

```groovy
repositories {
    maven { url = "https://keksuccino.github.io/maven/" }
}

dependencies {
    modImplementation "de.keksuccino:mcef-fabric:2.1.9-1.21.11"
}
```

Replace the MCEF and Minecraft version as required. `modImplementation` makes MCEF available in dev.

### NeoForge

```groovy
repositories {
    maven { url = "https://keksuccino.github.io/maven/" }
}

dependencies {
    implementation "de.keksuccino:mcef-neoforge:2.1.9-1.21.11"
}
```

NeoForge ships deobfuscated jars by default, so the dependency can be declared with a plain `implementation`. Replace the MCEF and Minecraft version as required.

## Building & Modifying MCEF

After cloning this repo, you will need to clone the java-cef git submodule. There is a gradle task for this: `./gradlew cloneJcef`.

To run the Fabric client: `./gradlew fabricClient`
To run the NeoForge client: `./gradlew neoforgeClient`

In-game, there is a demo browser if you press F12 after you're loaded into a world (the demo browser only exists when you're running from a development environment).

## Clearing MCEF Cache

MCEF skips the downloader screen once it detects that all required files are present. Remove the following paths to force a fresh download and clean browser data:

- **Binary bundle (production builds):** `<game directory>/mods/mcef-libraries`
- **Binary bundle (development runs):** `<repo>/fabric/build/mcef-libraries` or `<repo>/neoforge/build/mcef-libraries` (the folder next to the active module's `build` directory)
- **Checksum files:** any `<platform>.tar.gz.sha256` inside the relevant `mcef-libraries` folder; removing these alongside the binaries guarantees the downloader runs again
- **JCEF profile/cache:** `<game directory>/mods/mcef-cache`
- **Config overrides:** `<game directory>/config/mcef/mcef.properties` (delete or edit this file if it sets `skip-download=true`)

After clearing these locations, restart the game and the Download screen will reappear to fetch a fresh Chromium bundle.
