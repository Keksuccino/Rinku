package de.keksuccino.rinku;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Exposes Rinku's shared options screen through Fabric's Mod Menu. */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return OptionsScreen::new;
    }

}
