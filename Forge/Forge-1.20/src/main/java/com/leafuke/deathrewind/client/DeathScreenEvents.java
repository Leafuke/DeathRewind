package com.leafuke.deathrewind.client;

import com.leafuke.deathrewind.DeathRewind;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(
        modid = DeathRewind.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DeathScreenEvents {
    private DeathScreenEvents() {
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof DeathScreen screen)) {
            return;
        }

        List<AbstractWidget> vanillaWidgets = event.getListenersList().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .toList();
        Button rewindButton = Button.builder(
                Component.translatable("deathrewind.button.rewind"),
                button -> DeathScreenController.requestRewind())
                .bounds(screen.width / 2 - 100, screen.height / 4 + 120, 200, 20)
                .build();

        DeathScreenController.open(screen, vanillaWidgets, rewindButton);
        event.addListener(rewindButton);
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof DeathScreen screen) {
            DeathScreenController.close(screen);
        }
    }
}
