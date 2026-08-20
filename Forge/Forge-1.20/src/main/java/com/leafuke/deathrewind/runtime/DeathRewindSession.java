package com.leafuke.deathrewind.runtime;

import com.leafuke.deathrewind.DeathRewind;
import com.leafuke.deathrewind.backup.BackupNotifier;
import com.leafuke.deathrewind.backup.PeriodicBackupStrategy;
import com.leafuke.deathrewind.config.DeathRewindConfig;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.OperationHandle;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class DeathRewindSession implements AutoCloseable {
    private static final long TICKS_PER_MINUTE = 60L * 20L;

    private final MinecraftServer server;
    private final DeathRewindConfig config;
    private final PeriodicBackupStrategy strategy;
    private final BackupNotifier notifier;
    private long intervalTicks;

    private long elapsedTicks;
    private boolean backupInFlight;
    private final Set<UUID> deathPausedPlayers = new HashSet<>();
    private volatile boolean manuallyPaused;
    private boolean closed;

    public DeathRewindSession(MinecraftServer server, DeathRewindConfig config) {
        this.server = server;
        this.config = config;
        this.strategy = new PeriodicBackupStrategy(config.backup);
        this.notifier = new BackupNotifier(server, config.showBackupInfo);
        this.intervalTicks = config.intervalMinutes * TICKS_PER_MINUTE;
    }

    public void start() {
        DeathRewind.LOGGER.info(
                "Death Rewind session enabled for '{}': interval={} minutes, mode={}, compression={} level={}",
                server.getWorldData().getLevelName(),
                config.intervalMinutes,
                config.backup.mode,
                config.backup.compressionMethod,
                config.backup.compressionLevel);
    }

    public void tick() {
        if (closed || backupInFlight || !deathPausedPlayers.isEmpty() || manuallyPaused) {
            return;
        }
        elapsedTicks++;
        if (elapsedTicks < intervalTicks) {
            return;
        }

        elapsedTicks = 0L;
        submitBackup();
    }

    public void onPlayerDeath(ServerPlayer player) {
        if (closed) {
            return;
        }
        if (deathPausedPlayers.add(player.getUUID())) {
            DeathRewind.LOGGER.info(
                    "Death Rewind checkpoints paused while player '{}' awaits respawn or rewind.",
                    player.getGameProfile().getName());
        }
    }

    public void onPlayerRespawn(ServerPlayer player) {
        if (deathPausedPlayers.remove(player.getUUID())) {
            DeathRewind.LOGGER.info(
                    "Death Rewind checkpoints resumed after player '{}' respawned.",
                    player.getGameProfile().getName());
        }
    }

    public boolean forceDeathRewind() {
        return config.forceDeathRewind;
    }

    public boolean pause() {
        if (manuallyPaused) {
            return false;
        }
        manuallyPaused = true;
        DeathRewind.LOGGER.info("Death Rewind checkpoints paused by command.");
        return true;
    }

    public boolean resume() {
        if (!manuallyPaused) {
            return false;
        }
        manuallyPaused = false;
        DeathRewind.LOGGER.info("Death Rewind checkpoints resumed by command.");
        return true;
    }

    public boolean setInterval(int minutes) {
        if (minutes < 1 || minutes > 1440) {
            return false;
        }
        this.intervalTicks = minutes * TICKS_PER_MINUTE;
        this.elapsedTicks = 0L;
        DeathRewind.LOGGER.info("Death Rewind interval changed to {} minutes.", minutes);
        return true;
    }

    public Status getStatus() {
        int intervalMinutes = (int) (intervalTicks / TICKS_PER_MINUTE);
        int elapsedMinutes = (int) (elapsedTicks / TICKS_PER_MINUTE);
        int remainingMinutes = Math.max(0, intervalMinutes - elapsedMinutes);
        return new Status(!manuallyPaused, intervalMinutes, elapsedMinutes, remainingMinutes);
    }

    public void sendStatusWelcome(ServerPlayer player) {
        MutableComponent message;
        if (manuallyPaused) {
            // Paused state
            message = Component.translatable("deathrewind.message.welcome.paused");
            message.append(Component.literal(" "));
            message.append(Component.translatable("deathrewind.message.welcome.button.resume")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dr resume"))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("deathrewind.message.welcome.button.resume.hover")))));
        } else {
            // Active state
            int intervalMinutes = (int) (intervalTicks / TICKS_PER_MINUTE);
            int remainingMinutes = Math.max(0, intervalMinutes - (int) (elapsedTicks / TICKS_PER_MINUTE));
            message = Component.translatable(
                    "deathrewind.message.welcome.enabled",
                    intervalMinutes,
                    remainingMinutes);
            message.append(Component.literal(" "));
            message.append(Component.translatable("deathrewind.message.welcome.button.pause")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.YELLOW)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dr pause"))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("deathrewind.message.welcome.button.pause.hover")))));
        }

        message.append(Component.literal(" "));
        message.append(Component.translatable("deathrewind.message.welcome.button.config")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/dr interval "))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("deathrewind.message.welcome.button.config.hover")))));

        player.sendSystemMessage(message);
    }

    public record Status(boolean enabled, int intervalMinutes, int elapsedMinutes, int remainingMinutes) {
    }

    private void submitBackup() {
        long startedAt = System.nanoTime();
        final OperationHandle<BackupResult> handle;
        try {
            handle = strategy.submit();
            backupInFlight = true;
            DeathRewind.LOGGER.info(
                    "Submitted Death Rewind checkpoint operation {}.", handle.id());
        } catch (RuntimeException exception) {
            DeathRewind.LOGGER.error("Could not submit Death Rewind checkpoint.", exception);
            notifier.completed(null, exception);
            return;
        }

        handle.completion().whenComplete((result, throwable) -> {
            try {
                server.execute(() -> finishBackup(result, throwable, startedAt));
            } catch (RuntimeException exception) {
                DeathRewind.LOGGER.warn(
                        "Could not deliver Death Rewind backup completion on the server thread.",
                        exception);
            }
        });
    }

    private void finishBackup(BackupResult result, Throwable throwable, long startedAt) {
        backupInFlight = false;
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        notifier.completed(result, throwable);

        if (throwable != null) {
            DeathRewind.LOGGER.error(
                    "Death Rewind checkpoint completed exceptionally after {} ms.",
                    elapsedMillis,
                    throwable);
            return;
        }

        String backup = result.backupId()
                .map(value -> value.value())
                .orElse("");
        String failure = result.failure()
                .map(value -> value.code() + ": " + value.message())
                .orElse("");
        if (result.outcome() == BackupResult.Outcome.CREATED
                || result.outcome() == BackupResult.Outcome.NO_CHANGES) {
            DeathRewind.LOGGER.info(
                    "Death Rewind checkpoint finished after {} ms: outcome={}, file={}",
                    elapsedMillis,
                    result.outcome(),
                    backup);
        } else {
            DeathRewind.LOGGER.warn(
                    "Death Rewind checkpoint finished after {} ms: outcome={}, failure={}",
                    elapsedMillis,
                    result.outcome(),
                    failure);
        }
    }

    @Override
    public void close() {
        closed = true;
        deathPausedPlayers.clear();
    }
}
