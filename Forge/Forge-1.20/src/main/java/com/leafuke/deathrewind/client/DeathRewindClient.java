package com.leafuke.deathrewind.client;

import com.leafuke.deathrewind.DeathRewind;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = DeathRewind.MOD_ID, value = Dist.CLIENT)
public final class DeathRewindClient {
    private DeathRewindClient() {
    }

    public static void initialize() {
        DeathScreenController.initialize();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DeathScreenController.clientTick(Minecraft.getInstance());
        }
    }
}
