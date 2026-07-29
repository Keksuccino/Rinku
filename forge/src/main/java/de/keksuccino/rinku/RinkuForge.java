package de.keksuccino.rinku;

import de.keksuccino.rinku.example.RinkuExampleMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Rinku.MOD_ID)
public class RinkuForge {

    public RinkuForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new OptionsScreen(parent)));
            if (!FMLEnvironment.production) new RinkuExampleMod(modEventBus);
        }
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::serverSetup);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
    }

    private void serverSetup(final FMLDedicatedServerSetupEvent event) {
        // Rinku server-side does nothing.
    }

}
