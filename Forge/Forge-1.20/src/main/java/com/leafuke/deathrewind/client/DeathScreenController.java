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
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;

import java.util.List;
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
    private static int deathScreenTicks;
    private static DeathScreen currentScreen;
    private static Button rewindButton;
    private static List<AbstractWidget> vanillaWidgets = List.of();
    private static boolean lockedVanillaButtons;

    private static final int MIN_DEATH_SCREEN_TICKS = 20;

    private DeathScreenController() {
    }

    public static void initialize() {
        restoreInFlight = false;
        failureUnlocked = false;
        forceMode = false;
        screenOpen = false;
        deathScreenTicks = 0;
        currentScreen = null;
        rewindButton = null;
        vanillaWidgets = List.of();
        lockedVanillaButtons = false;
    }

    public static void open(
            DeathScreen screen,
            List<AbstractWidget> vanillaWidgets,
            Button rewindButton) {
        boolean sameScreen = screenOpen && currentScreen == screen;
        screenOpen = true;
        currentScreen = screen;
        DeathScreenController.vanillaWidgets = List.copyOf(vanillaWidgets);
        DeathScreenController.rewindButton = rewindButton;
        if (!sameScreen) {
            deathScreenTicks = 0;
            failureUnlocked = false;
        }
        forceMode = DeathRewindRuntime.forceDeathRewind();
        lockedVanillaButtons = false;
        applyButtonStates();
    }

    public static void close(DeathScreen screen) {
        if (!screenOpen || currentScreen != screen) {
            return;
        }
        screenOpen = false;
        currentScreen = null;
        rewindButton = null;
        vanillaWidgets = List.of();
        deathScreenTicks = 0;
        forceMode = false;
        failureUnlocked = false;
        lockedVanillaButtons = false;
    }

    public static void clientTick(Minecraft client) {
        if (!(client.screen instanceof DeathScreen screen)) {
            if (screenOpen && currentScreen != null) {
                close(currentScreen);
            }
            return;
        }
        if (!screenOpen || currentScreen != screen) {
            return;
        }

        if (deathScreenTicks < MIN_DEATH_SCREEN_TICKS) {
            deathScreenTicks++;
        }
        applyButtonStates();
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
        if (!screenOpen || deathScreenTicks < MIN_DEATH_SCREEN_TICKS || !canRewind()) {
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

        var client = Minecraft.getInstance();
        handle.completion().whenComplete((result, throwable) -> {
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
            applyButtonStates();
            return;
        }
        if (result == null) {
            failureUnlocked = true;
            sendFailure("unknown");
            applyButtonStates();
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
        applyButtonStates();
    }

    private static void applyButtonStates() {
        if (!screenOpen || rewindButton == null) {
            return;
        }

        boolean canRewind = canRewind();
        rewindButton.active = canRewind && deathScreenTicks >= MIN_DEATH_SCREEN_TICKS;

        boolean lockVanillaButtons = shouldLockVanillaButtons(canRewind);
        if (lockVanillaButtons) {
            for (AbstractWidget widget : vanillaWidgets) {
                widget.active = false;
            }
            lockedVanillaButtons = true;
            return;
        }

        if (lockedVanillaButtons) {
            for (AbstractWidget widget : vanillaWidgets) {
                widget.active = deathScreenTicks >= MIN_DEATH_SCREEN_TICKS;
            }
            lockedVanillaButtons = false;
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
