package com.cinemamod.mcef;

import com.cinemamod.mcef.example.ExampleModHandler;
import net.fabricmc.api.ModInitializer;

public class MCEFFabric implements ModInitializer {

    @Override
    public void onInitialize() {

        ExampleModHandler.init();

    }

}
