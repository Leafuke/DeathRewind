package com.leafuke.deathrewind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class DeathRewindClient {
    private static final Map<Screen, Button> REWIND_BUTTONS = new WeakHashMap<>();
    private static final Set<Screen> HANDSHAKE_WARNED_SCREENS = Collections.newSetFromMap(new WeakHashMap<>());

    private DeathRewindClient() {
    }

    public static void initialize() {
        Config.load();
        NeoForge.EVENT_BUS.register(new DeathRewindClient());
    }

    @SubscribeEvent
    public void onDeathScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof DeathScreen)) {
            return;
        }

        DeathRewind.onDeathScreenOpened();

        Button rewindButton = Button.builder(
                Component.translatable("deathrewind.button.rewind"),
                button -> sendRestoreRequest()
        ).bounds(screen.width / 2 - 100, screen.height / 4 + 120, 200, 20).build();

        event.addListener(rewindButton);
        REWIND_BUTTONS.put(screen, rewindButton);
        HANDSHAKE_WARNED_SCREENS.remove(screen);
        applyDeathScreenState(screen, rewindButton);
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        ClientRejoinController.onClientTick(client);
        updateDeathScreenState(client);
    }

    private static void updateDeathScreenState(Minecraft client) {
        if (client == null) {
            return;
        }

        Screen current = client.screen;
        if (!(current instanceof DeathScreen)) {
            DeathRewind.onDeathScreenClosed();
            return;
        }

        Button rewindButton = REWIND_BUTTONS.get(current);
        if (rewindButton == null) {
            return;
        }

        applyDeathScreenState(current, rewindButton);

        if (!DeathRewind.isHandshakeProbePending()
                && !DeathRewind.isHandshakeProbeSucceeded()
                && !HANDSHAKE_WARNED_SCREENS.contains(current)) {
            HANDSHAKE_WARNED_SCREENS.add(current);
            if (client.player != null) {
                client.player.sendSystemMessage(Component.translatable("deathrewind.message.handshake.death_failed"));
            }
        }
    }

    private static void applyDeathScreenState(Screen screen, Button rewindButton) {
        boolean buttonActive = DeathRewind.canUseDeathRewindButton();
        rewindButton.active = buttonActive;

        if (!DeathRewind.isForceDeathRewindEnabled()) {
            return;
        }

        for (GuiEventListener element : screen.children()) {
            if (element instanceof AbstractWidget widget && widget != rewindButton) {
                widget.active = false;
            }
        }

        rewindButton.active = buttonActive;
    }

    private static void sendRestoreRequest() {
        DeathRewind.requestDeathRewind().thenAccept(response -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                return;
            }

            client.execute(() -> {
                if (client.player == null) {
                    return;
                }

                if (response == null || response.startsWith("ERROR:")) {
                    String detail = response == null ? "NO_RESPONSE" : response;
                    client.player.sendSystemMessage(Component.translatable("deathrewind.message.restore.request_failed", detail));
                } else {
                    client.player.sendSystemMessage(Component.translatable("deathrewind.message.restore.request_sent"));
                }
            });
        });
    }

    public static void setWorldToRejoin(String levelId) {
        ClientRejoinController.setWorldToRejoin(levelId);
    }

    public static String getWorldToRejoin() {
        return ClientRejoinController.getWorldToRejoin();
    }

    public static void markReadyToRejoin() {
        ClientRejoinController.markReadyToRejoin();
    }

    public static void clearReadyToRejoin() {
        ClientRejoinController.clearReadyToRejoin();
    }

    public static boolean isReadyToRejoin() {
        return ClientRejoinController.isReadyToRejoin();
    }

    public static void resetRestoreState() {
        ClientRejoinController.resetRestoreState();
    }

    public static void showRestoreSuccessOverlay() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(Component.translatable("deathrewind.message.restore.success_overlay"), true);
            }
        });
    }
}
