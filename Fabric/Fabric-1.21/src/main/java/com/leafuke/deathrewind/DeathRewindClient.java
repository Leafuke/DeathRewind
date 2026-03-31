package com.leafuke.deathrewind;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class DeathRewindClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Config.load();
        ClientTickEvents.END_CLIENT_TICK.register(ClientRejoinController::onClientTick);
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("deathrewind.message.restore.success_overlay"), true);
            }
        });
    }
}
