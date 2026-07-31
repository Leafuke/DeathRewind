package com.leafuke.deathrewind.runtime;

import com.leafuke.deathrewind.DeathRewind;
import com.leafuke.deathrewind.config.DeathRewindConfigManager;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class DeathRewindRuntime {
    private static volatile DeathRewindSession session;

    private DeathRewindRuntime() {
    }

    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(DeathRewindRuntime::onServerStarting);
        NeoForge.EVENT_BUS.addListener(DeathRewindRuntime::onServerStopping);
        NeoForge.EVENT_BUS.addListener(DeathRewindRuntime::onServerStopped);
        NeoForge.EVENT_BUS.addListener(DeathRewindRuntime::onServerTick);
    }

    public static boolean hasIntegratedSession() {
        return session != null;
    }

    public static boolean forceDeathRewind() {
        var current = session;
        return current != null && current.forceDeathRewind();
    }

    public static void deathScreenOpened() {
        var current = session;
        if (current != null) {
            current.pauseForDeathScreen();
        }
    }

    public static void deathScreenClosed(boolean restoreInFlight) {
        if (restoreInFlight) {
            return;
        }
        var current = session;
        if (current != null) {
            current.resumeAfterDeathScreen();
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        start(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        stop(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        session = null;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        tick(event.getServer());
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
