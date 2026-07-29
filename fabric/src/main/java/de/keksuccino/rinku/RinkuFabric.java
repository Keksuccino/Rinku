package de.keksuccino.rinku;

import de.keksuccino.rinku.example.ExampleModHandler;
import net.fabricmc.api.ModInitializer;

public class RinkuFabric implements ModInitializer {

    @Override
    public void onInitialize() {

        ExampleModHandler.init();

    }

}
