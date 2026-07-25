package com.leafuke.deathrewind.client;

import net.fabricmc.api.ClientModInitializer;

public final class DeathRewindClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DeathScreenController.initialize();
    }
}
