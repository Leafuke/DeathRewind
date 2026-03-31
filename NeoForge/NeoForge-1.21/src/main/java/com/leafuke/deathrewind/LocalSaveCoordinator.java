package com.leafuke.deathrewind;

import net.minecraft.server.MinecraftServer;

public final class LocalSaveCoordinator {
    private LocalSaveCoordinator() {
    }

    public static boolean saveForLocalRestore(MinecraftServer server) {
        if (server == null) {
            return false;
        }

        try {
            server.getPlayerList().saveAll();
        } catch (Throwable t) {
            DeathRewind.LOGGER.warn("Failed to save player data before local save: {}", t.getMessage());
        }

        try {
            server.saveAllChunks(true, true, true);
            return true;
        } catch (Throwable t) {
            DeathRewind.LOGGER.warn("Failed to save world data before restore: {}", t.getMessage());
            return false;
        }
    }
}
