package com.leafuke.deathrewind.client;

import com.leafuke.deathrewind.DeathRewind;
import com.leafuke.deathrewind.runtime.DeathRewindRuntime;
import com.leafuke.minebackup.api.v2.FeedbackPolicy;
import com.leafuke.minebackup.api.v2.MessageSlot;
import com.leafuke.minebackup.api.v2.MessageTemplate;
import com.leafuke.minebackup.api.v2.MineBackupApi;
import com.leafuke.minebackup.api.v2.OperationPhase;
import com.leafuke.minebackup.api.v2.OperationPresentation;
import com.leafuke.minebackup.api.v2.RestoreRequest;
import com.leafuke.minebackup.api.v2.RestoreResult;
import com.leafuke.minebackup.api.v2.RuntimeEnvironment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;

import java.util.Map;

public final class DeathScreenController {
    private static final String CALLER_ID = "deathrewind:death_screen";
    private static final OperationPresentation PRESENTATION = new OperationPresentation(
            FeedbackPolicy.CALLER_MANAGED,
            Map.of(
                    MessageSlot.RESTORE_PREPARING,
                    new MessageTemplate("deathrewind.message.restore.preparing"),
                    MessageSlot.RESTORE_KICK,
                    new MessageTemplate("deathrewind.message.restore.kick"),
                    MessageSlot.RESTORE_REJOIN,
                    new MessageTemplate("deathrewind.message.restore.rejoining"),
                    MessageSlot.RESTORE_SUCCEEDED,
                    new MessageTemplate("deathrewind.message.restore.succeeded"),
                    MessageSlot.RESTORE_FAILED,
                    new MessageTemplate("deathrewind.message.restore.failed")));

    private static boolean restoreInFlight;
    private static boolean failureUnlocked;
    private static boolean forceMode;
    private static boolean screenOpen;

    private DeathScreenController() {
    }

    public static void initialize() {
        restoreInFlight = false;
        failureUnlocked = false;
        forceMode = false;
        screenOpen = false;
    }

    public static void screenOpened() {
        screenOpen = true;
        failureUnlocked = false;
        forceMode = DeathRewindRuntime.forceDeathRewind();
        DeathRewindRuntime.deathScreenOpened();
    }

    public static void screenClosed() {
        if (!screenOpen) {
            return;
        }
        screenOpen = false;
        DeathRewindRuntime.deathScreenClosed(restoreInFlight);
        forceMode = false;
        failureUnlocked = false;
    }

    public static void clientTick(Minecraft client) {
        if (screenOpen && !(client.gui.screen() instanceof DeathScreen)) {
            screenClosed();
        }
    }

    public static boolean canRewind() {
        if (restoreInFlight || !DeathRewindRuntime.hasIntegratedSession()) {
            return false;
        }
        try {
            var status = MineBackupApi.getInstance().runtimeStatus();
            return status.environment() == RuntimeEnvironment.INTEGRATED
                    && status.currentWorldAvailable()
                    && status.activeOperation().isEmpty();
        } catch (RuntimeException exception) {
            DeathRewind.LOGGER.warn("Could not query MineBackup runtime status.", exception);
            return false;
        }
    }

    public static boolean shouldLockVanillaButtons(boolean canRewind) {
        return forceMode && !failureUnlocked && (canRewind || restoreInFlight);
    }

    public static void requestRewind() {
        if (!canRewind()) {
            return;
        }

        restoreInFlight = true;
        failureUnlocked = false;
        final com.leafuke.minebackup.api.v2.OperationHandle<RestoreResult> handle;
        try {
            var request = RestoreRequest.latest(CALLER_ID)
                    .immediate()
                    .withPresentation(PRESENTATION);
            handle = MineBackupApi.getInstance().restoreCurrent(request);
        } catch (RuntimeException exception) {
            finish(null, exception);
            return;
        }

        if (handle.phase() != OperationPhase.REJECTED) {
            send(Component.translatable(
                    "deathrewind.message.restore.submitted").withStyle(ChatFormatting.YELLOW));
        }

        handle.completion().whenComplete((result, throwable) -> {
            var client = Minecraft.getInstance();
            client.execute(() -> finish(result, throwable));
        });
    }

    public static boolean restoreInFlight() {
        return restoreInFlight;
    }

    private static void finish(RestoreResult result, Throwable throwable) {
        restoreInFlight = false;
        if (throwable != null) {
            failureUnlocked = true;
            DeathRewind.LOGGER.error("Death Rewind restore completed exceptionally.", throwable);
            sendFailure(safeMessage(throwable));
            return;
        }
        if (result == null) {
            failureUnlocked = true;
            sendFailure("unknown");
            return;
        }

        switch (result.outcome()) {
            case RESTORED, RESTART_HANDOFF_ACCEPTED -> {
                failureUnlocked = false;
                DeathRewind.LOGGER.info(
                        "Death Rewind restore finished: outcome={}, file={}",
                        result.outcome(),
                        result.backupId().map(value -> value.value()).orElse(""));
            }
            case RESTORED_REJOIN_FAILED, CANCELLED, REJECTED, FAILED -> {
                failureUnlocked = true;
                String failure = result.failure()
                        .map(value -> value.code() + ": " + value.message())
                        .filter(value -> !value.isBlank())
                        .orElse(result.outcome().name());
                DeathRewind.LOGGER.warn(
                        "Death Rewind restore did not complete normally: outcome={}, failure={}",
                        result.outcome(),
                        failure);
                sendFailure(failure);
            }
        }
    }

    private static void sendFailure(String detail) {
        send(Component.translatable(
                "deathrewind.message.restore.request_failed",
                detail).withStyle(ChatFormatting.RED));
    }

    private static void send(Component message) {
        var client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(message);
        }
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }
}
