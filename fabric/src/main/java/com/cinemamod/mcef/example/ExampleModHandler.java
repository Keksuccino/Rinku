package com.cinemamod.mcef.example;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

public class ExampleModHandler {

    public static void init() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment() && (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)) {
            new MCEFExampleMod();
        }
    }

}
