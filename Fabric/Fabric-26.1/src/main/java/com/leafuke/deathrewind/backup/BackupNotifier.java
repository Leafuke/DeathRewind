package com.leafuke.deathrewind.backup;

import com.leafuke.minebackup.api.v2.BackupResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class BackupNotifier {
    private final MinecraftServer server;
    private final boolean enabled;

    public BackupNotifier(MinecraftServer server, boolean enabled) {
        this.server = server;
        this.enabled = enabled;
    }

    public void completed(BackupResult result, Throwable throwable) {
        if (!enabled) {
            return;
        }
        if (throwable != null) {
            broadcast(Component.translatable(
                    "deathrewind.message.backup.failed",
                    safeMessage(throwable)).withStyle(ChatFormatting.RED));
            return;
        }

        switch (result.outcome()) {
            case CREATED -> broadcast(Component.translatable(
                    "deathrewind.message.backup.created").withStyle(ChatFormatting.GREEN));
            case NO_CHANGES -> broadcast(Component.translatable(
                    "deathrewind.message.backup.no_changes").withStyle(ChatFormatting.GRAY));
            case CANCELLED -> broadcast(Component.translatable(
                    "deathrewind.message.backup.cancelled",
                    failureMessage(result)).withStyle(ChatFormatting.YELLOW));
            case REJECTED -> broadcast(Component.translatable(
                    "deathrewind.message.backup.rejected",
                    failureMessage(result)).withStyle(ChatFormatting.RED));
            case FAILED -> broadcast(Component.translatable(
                    "deathrewind.message.backup.failed",
                    failureMessage(result)).withStyle(ChatFormatting.RED));
        }
    }

    private void broadcast(Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static String failureMessage(BackupResult result) {
        return result.failure()
                .map(failure -> failure.code() + ": " + failure.message())
                .filter(value -> !value.isBlank())
                .orElse("unknown");
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }
}
