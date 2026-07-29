package de.keksuccino.rinku.platform;

import com.mojang.blaze3d.platform.InputConstants;
import de.keksuccino.rinku.platform.services.IPlatformHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "forge";
    }

    @Override
    public String getPlatformDisplayName() {
        return "Forge";
    }

    @Override
    public String getLoaderVersion() {
        return this.getModVersion("forge");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public String getModVersion(String modId) {
        try {
            Optional<? extends ModContainer> container = ModList.get().getModContainerById(modId);
            if (container.isPresent()) return container.get().getModInfo().getVersion().toString();
        } catch (RuntimeException ignored) {
        }
        return "0.0.0";
    }

    @Override
    public List<String> getLoadedModIds() {
        List<String> loadedModIds = new ArrayList<>();
        for (IModInfo modInfo : ModList.get().getMods()) loadedModIds.add(modInfo.getModId());
        return loadedModIds;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }

    @Override
    public boolean isOnClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public InputConstants.Key getKeyMappingKey(KeyMapping keyMapping) {
        return InputConstants.getKey(keyMapping.saveString());
    }

}
