package de.keksuccino.rinku.example;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

public class RinkuExampleMod {

    public static final KeyMapping KEY_MAPPING = new KeyMapping("Open Browser", InputConstants.KEY_F12, KeyMapping.CATEGORY_MISC);

    public RinkuExampleMod(IEventBus modEventBus) {
        modEventBus.addListener(this::registerKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(this::onTick);
    }

    public void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KEY_MAPPING);
    }

    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        // Check if our key was pressed and make sure the ExampleScreen isn't already open
        if (KEY_MAPPING.consumeClick() && !(Minecraft.getInstance().screen instanceof ExampleScreen)) {
            // Display the ExampleScreen web browser
            Minecraft.getInstance().setScreen(new ExampleScreen(Component.literal("Example Screen")));
        }
    }

}
