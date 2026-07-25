package com.leafuke.deathrewind.runtime;

import com.leafuke.deathrewind.DeathRewind;
import com.leafuke.deathrewind.backup.BackupNotifier;
import com.leafuke.deathrewind.backup.PeriodicBackupStrategy;
import com.leafuke.deathrewind.config.DeathRewindConfig;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.OperationHandle;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;

public final class DeathRewindSession implements AutoCloseable {
    private static final long TICKS_PER_MINUTE = 60L * 20L;

    private final MinecraftServer server;
    private final DeathRewindConfig config;
    private final PeriodicBackupStrategy strategy;
    private final BackupNotifier notifier;
    private final long intervalTicks;

    private long elapsedTicks;
    private boolean backupInFlight;
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
        if (closed || backupInFlight) {
            return;
        }
        elapsedTicks++;
        if (elapsedTicks < intervalTicks) {
            return;
        }

        elapsedTicks = 0L;
        submitBackup();
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
    }
}
