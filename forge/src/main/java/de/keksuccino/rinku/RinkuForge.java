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

    private final IEventBus modEventBus;

    public RinkuForge() {
        this.modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        this.modEventBus.addListener(this::clientSetup);
        this.modEventBus.addListener(this::serverSetup);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new OptionsScreen(parent)));
        }
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        if (!FMLEnvironment.production) {
            new RinkuExampleMod(this.modEventBus);
        }
    }

    private void serverSetup(final FMLDedicatedServerSetupEvent event) {
        // Rinku server-side does nothing.
    }

}
