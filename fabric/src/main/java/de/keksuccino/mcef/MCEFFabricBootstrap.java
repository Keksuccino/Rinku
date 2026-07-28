package de.keksuccino.mcef;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Registers the loader-owned game root before any transformed Minecraft class can request it. */
public final class MCEFFabricBootstrap implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        MCEFInstallationPaths.registerGameInstanceDirectory(FabricLoader.getInstance().getGameDir());
    }
}
