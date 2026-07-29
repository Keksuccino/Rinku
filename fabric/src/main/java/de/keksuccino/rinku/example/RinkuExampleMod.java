package de.keksuccino.rinku.example;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class RinkuExampleMod {

    public static final KeyMapping KEY_MAPPING = new KeyMapping("Open Browser", InputConstants.KEY_F12, KeyMapping.CATEGORY_MISC);

    public RinkuExampleMod() {
        KeyBindingHelper.registerKeyBinding(KEY_MAPPING);
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
