package com.leafuke.deathrewind.runtime;

import com.leafuke.deathrewind.DeathRewind;
import com.leafuke.deathrewind.command.DeathRewindCommand;
import com.leafuke.deathrewind.config.DeathRewindConfigManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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
        MinecraftForge.EVENT_BUS.register(new EventHandler());
    }

    public static boolean hasIntegratedSession() {
        return session != null;
    }

    public static boolean forceDeathRewind() {
        var current = session;
        return current != null && current.forceDeathRewind();
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

    private static final class EventHandler {
        @SubscribeEvent
        public void onServerStarting(ServerStartingEvent event) {
            start(event.getServer());
        }

        @SubscribeEvent
        public void onServerStopping(ServerStoppingEvent event) {
            stop(event.getServer());
        }

        @SubscribeEvent
        public void onServerStopped(ServerStoppedEvent event) {
            session = null;
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                tick(event.getServer());
            }
        }

        @SubscribeEvent
        public void onPlayerDeath(LivingDeathEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                var current = session;
                if (current != null) {
                    current.onPlayerDeath(player);
                }
            }
        }

        @SubscribeEvent
        public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                var current = session;
                if (current != null) {
                    current.onPlayerRespawn(player);
                }
            }
        }

        @SubscribeEvent
        public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                MinecraftServer server = player.getServer();
                if (server != null) {
                    // Delay 2 seconds to show after MineBackup's welcome message
                    scheduler.schedule(
                            () -> server.execute(() -> {
                                var current = session;
                                if (current != null) {
                                    current.sendStatusWelcome(player);
                                }
                            }),
                            2L,
                            TimeUnit.SECONDS);
                }
            }
        }

        @SubscribeEvent
        public void onRegisterCommands(RegisterCommandsEvent event) {
            DeathRewindCommand.register(event.getDispatcher());
        }
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
