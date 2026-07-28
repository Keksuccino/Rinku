package de.keksuccino.mcef;

import de.keksuccino.mcef.example.MCEFExampleMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(MCEF.MOD_ID)
public class MCEFNeoForge {

    public MCEFNeoForge(IEventBus modEventBus) {
        if (!FMLEnvironment.isProduction()) {
            new MCEFExampleMod(modEventBus);
        }
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::serverSetup);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
    }

    private void serverSetup(final FMLDedicatedServerSetupEvent event) {
        // MCEF server-side does nothing
    }

}
