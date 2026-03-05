package com.cinemamod.mcef;

import com.cinemamod.mcef.example.MCEFExampleMod;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(MCEF.MOD_ID)
public class MCEFForge {

    public MCEFForge(IEventBus modEventBus) {
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::serverSetup);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        if (!FMLEnvironment.production) {
            new MCEFExampleMod();
        }
    }

    private void serverSetup(final FMLDedicatedServerSetupEvent event) {
        // MCEF server-side does nothing
    }

}
