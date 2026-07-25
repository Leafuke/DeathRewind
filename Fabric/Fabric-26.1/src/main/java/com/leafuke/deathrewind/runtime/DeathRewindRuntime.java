package com.leafuke.deathrewind.runtime;

import com.leafuke.deathrewind.DeathRewind;
import com.leafuke.deathrewind.config.DeathRewindConfigManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

public final class DeathRewindRuntime {
    private static DeathRewindSession session;

    private DeathRewindRuntime() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(DeathRewindRuntime::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(DeathRewindRuntime::stop);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> session = null);
        ServerTickEvents.END_SERVER_TICK.register(DeathRewindRuntime::tick);
    }

    private static void start(MinecraftServer server) {
        stop(server);

        var result = DeathRewindConfigManager.load();
        if (!result.isSuccess()) {
            DeathRewind.LOGGER.error(
                    "Death Rewind is disabled for this server session because its configuration is invalid: {}",
                    result.error());
            return;
        }

        if (server.isDedicatedServer()) {
            DeathRewind.LOGGER.warn(
                    "Death Rewind 2.0 does not support dedicated servers; checkpoint and rewind requests are disabled.");
            return;
        }

        if (!result.config().enabled) {
            DeathRewind.LOGGER.info(
                    "Death Rewind is disabled by configuration for this server session.");
            return;
        }

        session = new DeathRewindSession(server, result.config());
        session.start();
    }

    private static void stop(MinecraftServer server) {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private static void tick(MinecraftServer server) {
        var current = session;
        if (current != null) {
            current.tick();
        }
    }
}
