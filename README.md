<p align="center">
<img width="64" alt="mcef_icon" src="https://github.com/user-attachments/assets/c11845ff-b57c-4e15-928f-19055874903c" />
</p>

# MCEF (Minecraft Chromium Embedded Framework)

MCEF is a mod and library for adding the Chromium web browser into Minecraft.

**Support & Discussion:** https://discord.gg/rhayah27GC

**Current Chromium version:** `151.0.7922.34`

## Supported Platforms

- Windows 10/11 (x86_64, arm64)*
- macOS 11 or greater (Intel, Apple Silicon)
- GNU Linux glibc 2.31 or greater (x86_64, arm64)**

**This mod will not work on Android.**

## Using MCEF in Your Projects

Snapshots and releases are mirrored on a static Maven repository hosted at `https://keksuccino.github.io/maven/`. Add the repository to your build script, then depend on the loader-specific artifact you need. Artifacts follow the pattern `de.keksuccino:<mod_id>-<loader>:<mod_version>-<minecraft_version>`.

### Fabric

```groovy
repositories {
    maven { url = "https://keksuccino.github.io/maven/" }
}

dependencies {
    modImplementation "de.keksuccino:mcef-fabric:2.2.1-26.2"
}
```

Replace the MCEF and Minecraft version as required. `modImplementation` makes MCEF available in dev.

### NeoForge

```groovy
repositories {
    maven { url = "https://keksuccino.github.io/maven/" }
}

dependencies {
    implementation "de.keksuccino:mcef-neoforge:2.2.1-26.2"
}
```

NeoForge ships deobfuscated jars by default, so the dependency can be declared with a plain `implementation`. Replace the MCEF and Minecraft version as required.

## Building & Modifying MCEF

The build resolves the JCEF Java binary and source JARs from the `Keksuccino/jcef-mcef` GitHub release selected by `jcef_commit` in `gradle.properties`; no submodule checkout is required. When updating JCEF, select a lowercase 40-character commit that has a matching `java-cef-<commit>` release containing both `jcef-mcef.jar` and `jcef-mcef-sources.jar`. The same compiled identity selects the matching native runtime release. JCEF classes are flat-merged into MCEF binaries and the upstream source classifier is flat-merged into every MCEF sources JAR, so published metadata needs no transitive JCEF dependency and consumers still receive JCEF sources.

To run the Fabric client: `./gradlew fabricClient`
To run the NeoForge client: `./gradlew neoforgeClient`

In-game, there is a demo browser if you press F12 after you're loaded into a world (the demo browser only exists when you're running from a development environment).

### JCEF runtime cache and checksum trust

MCEF validates each downloaded runtime archive against the `.sha256` file supplied by the selected endpoint before extraction. This establishes consistency with that endpoint; it is not an independent publisher signature. Mirror policy affects where a missing runtime is downloaded from, while completed cache entries are source-agnostic.

Each complete installation is published atomically under `<game instance>/mcef-libraries/jcef-v1/<platform>/<java-cef commit>`. MCEF reuses only the structurally valid entry for the exact commit compiled into the mod. `skip-download=true` therefore requires that exact completed entry to exist already.

## Clearing MCEF Cache

MCEF skips the downloader screen once it detects that the exact compiled JCEF runtime is complete. Remove the following paths when needed:

- **JCEF runtime installations:** `<game instance>/mcef-libraries`. Development game instances are `<repo>/fabric/run_client` for Fabric and `<repo>/run_client` for NeoForge with the supplied run configurations.
- **Config overrides:** `<game instance>/config/mcef/mcef.properties` (delete or edit this file if it sets `skip-download=true`).
- **Persistent browser data:** `%LOCALAPPDATA%\MCEF\cef-cache` on Windows, `~/Library/Application Support/MCEF/cef-cache` on macOS, or `${XDG_DATA_HOME:-~/.local/share}/mcef/cef-cache` on Linux.

Stop every Minecraft instance using a cache before removing it. MCEF intentionally never auto-deletes completed directories for older commits; delete the selected completed commit directory, or the whole `mcef-libraries` tree, manually only while those instances are stopped.

After clearing these locations, restart the game and the Download screen will reappear to fetch a fresh Chromium bundle.
