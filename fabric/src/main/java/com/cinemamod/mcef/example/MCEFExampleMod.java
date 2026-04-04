package com.cinemamod.mcef.example;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class MCEFExampleMod {

    public static final KeyMapping KEY_MAPPING = new KeyMapping("Open Browser", InputConstants.KEY_F12, KeyMapping.Category.MISC);

    public MCEFExampleMod() {
        KeyMappingHelper.registerKeyMapping(KEY_MAPPING);
        ClientTickEvents.START_CLIENT_TICK.register((client) -> onTick());
    }

    public void onTick() {
        // Check if our key was pressed
        if (KEY_MAPPING.isDown() && !(Minecraft.getInstance().screen instanceof ExampleScreen)) {
            //Display the web browser UI.
            Minecraft.getInstance().setScreen(new ExampleScreen(Component.literal("Example Screen")));
        }
    }

}
