package io.github.draysus.chattingvillagers.platform; // GEÄNDERT

import io.github.draysus.chattingvillagers.platform.services.IPlatformHelper; // GEÄNDERT
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path; // NEU

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path getConfigDir() { // NEU
        return FabricLoader.getInstance().getConfigDir();
    }
}