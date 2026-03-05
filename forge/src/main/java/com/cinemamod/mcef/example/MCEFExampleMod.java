package com.cinemamod.mcef.example;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

public class MCEFExampleMod {

    public static final KeyMapping KEY_MAPPING = new KeyMapping("Open Browser", InputConstants.KEY_F12, KeyMapping.CATEGORY_MISC);

    public MCEFExampleMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onTick);
    }

    public void onTick(TickEvent.ClientTickEvent event) {
        // Check if our key was pressed and make sure the ExampleScreen isn't already open
        if (KEY_MAPPING.isDown() && !(Minecraft.getInstance().screen instanceof ExampleScreen)) {
            // Display the ExampleScreen web browser
            Minecraft.getInstance().setScreen(new ExampleScreen(Component.literal("Example Screen")));
        }
    }

}
