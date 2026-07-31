package com.leafuke.deathrewind.client;

import com.leafuke.deathrewind.DeathRewind;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = DeathRewind.MOD_ID, value = Dist.CLIENT)
public final class DeathRewindClient {
    private DeathRewindClient() {
    }

    public static void initialize() {
        DeathScreenController.initialize();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        DeathScreenController.clientTick(Minecraft.getInstance());
    }
}
