package de.keksuccino.rinku.platform;

import com.mojang.blaze3d.platform.InputConstants;
import de.keksuccino.rinku.platform.services.IPlatformHelper;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.loading.ClientModLoader;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "neoforge";
    }

    @Override
    public String getPlatformDisplayName() {
        return "NeoForge";
    }

    @Override
    public String getLoaderVersion() {
        return this.getModVersion("neoforge");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public String getModVersion(String modId) {
        try {
            Optional<? extends ModContainer> o = ModList.get().getModContainerById(modId);
            if (o.isPresent()) {
                ModContainer c = o.get();
                return c.getModInfo().getVersion().toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "0.0.0";
    }

    @Override
    public List<String> getLoadedModIds() {
        List<String> l = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            l.add(info.getModId());
        }
        return l;
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
    public boolean requiresClientInitializationDeferral() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    @Override
    public boolean isClientInitializationCandidateReady() {
        if (FMLEnvironment.dist != Dist.CLIENT) return true;
        // This is only the first readiness condition. The common frame latch also waits for Minecraft's loading
        // overlay to disappear and for a complete normal display/event-pump frame before native CEF startup.
        return !ClientModLoader.isLoading();
    }

    @Override
    public InputConstants.Key getKeyMappingKey(KeyMapping keyMapping) {
        return keyMapping.getKey();
    }

}
