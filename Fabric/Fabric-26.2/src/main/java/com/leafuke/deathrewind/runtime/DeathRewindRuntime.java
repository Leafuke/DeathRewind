package com.leafuke.deathrewind.runtime;

import com.leafuke.deathrewind.DeathRewind;
import com.leafuke.deathrewind.command.DeathRewindCommand;
import com.leafuke.deathrewind.config.DeathRewindConfigManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DeathRewindRuntime {
    private static volatile DeathRewindSession session;
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            task -> {
                Thread thread = new Thread(task, "deathrewind-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    private DeathRewindRuntime() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(DeathRewindRuntime::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(DeathRewindRuntime::stop);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> session = null);
        ServerTickEvents.END_SERVER_TICK.register(DeathRewindRuntime::tick);
        ServerPlayConnectionEvents.JOIN.register(DeathRewindRuntime::onPlayerJoin);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DeathRewindCommand.register(dispatcher));
    }

    private static void onPlayerJoin(
            net.minecraft.server.network.ServerGamePacketListenerImpl handler,
            net.fabricmc.fabric.api.networking.v1.PacketSender sender,
            MinecraftServer server) {
        ServerPlayer player = handler.player;
        // Delay 2 seconds to show after MineBackup's welcome message
        scheduler.schedule(
                () -> server.executeIfPossible(() -> {
                    var current = session;
                    if (current != null) {
                        current.sendStatusWelcome(player);
                    }
                }),
                2L,
                TimeUnit.SECONDS);
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

    public static boolean pauseCheckpoints() {
        var current = session;
        return current != null && current.pause();
    }

    public static boolean resumeCheckpoints() {
        var current = session;
        return current != null && current.resume();
    }

    public static boolean setInterval(int minutes) {
        var current = session;
        return current != null && current.setInterval(minutes);
    }

    public static DeathRewindSession.Status getSessionStatus() {
        var current = session;
        return current != null ? current.getStatus() : null;
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
