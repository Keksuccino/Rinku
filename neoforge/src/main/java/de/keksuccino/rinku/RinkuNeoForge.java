package de.keksuccino.rinku;

import de.keksuccino.rinku.example.RinkuExampleMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(Rinku.MOD_ID)
public class RinkuNeoForge {

    public RinkuNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        if (!FMLEnvironment.isProduction()) {
            new RinkuExampleMod(modEventBus);
        }
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            IConfigScreenFactory configScreenFactory = (container, parent) -> new OptionsScreen(parent);
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, configScreenFactory);
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
