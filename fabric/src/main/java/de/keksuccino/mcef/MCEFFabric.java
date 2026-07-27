package de.keksuccino.mcef;

import de.keksuccino.mcef.example.ExampleModHandler;
import net.fabricmc.api.ModInitializer;

public class MCEFFabric implements ModInitializer {

    @Override
    public void onInitialize() {

        ExampleModHandler.init();

    }

}
