package io.github.draysus.chattingvillagers.platform; // GEÄNDERT

import io.github.draysus.chattingvillagers.platform.services.IPlatformHelper; // GEÄNDERT
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths; // NEU

import java.nio.file.Path; // NEU

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() { // WIEDERHERGESTELLT
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.isProduction();
    }

    @Override
    public Path getConfigDir() { // NEU
        return FMLPaths.CONFIGDIR.get();
    }
}