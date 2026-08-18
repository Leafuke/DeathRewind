package com.leafuke.deathrewind.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class DeathRewindClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DeathScreenController.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(DeathScreenController::clientTick);
    }
}
