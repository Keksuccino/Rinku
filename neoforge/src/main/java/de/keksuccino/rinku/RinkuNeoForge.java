package de.keksuccino.rinku;

import de.keksuccino.rinku.example.RinkuExampleMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(Rinku.MOD_ID)
public class RinkuNeoForge {

    public RinkuNeoForge(IEventBus modEventBus) {
        if (!FMLEnvironment.isProduction()) {
            new RinkuExampleMod(modEventBus);
        }
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::serverSetup);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
    }

    private void serverSetup(final FMLDedicatedServerSetupEvent event) {
        // Rinku server-side does nothing
    }

}
