package com.leafuke.deathrewind.mixin.client;

import com.leafuke.deathrewind.client.DeathScreenController;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin extends Screen {
    @Shadow
    private int delayTicker;

    @Unique
    private Button deathrewind$rewindButton;

    @Unique
    private boolean deathrewind$lockedVanillaButtons;

    protected DeathScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void deathrewind$onInit(CallbackInfo callbackInfo) {
        DeathScreenController.screenOpened();
        deathrewind$rewindButton = addRenderableWidget(Button.builder(
                Component.translatable("deathrewind.button.rewind"),
                button -> DeathScreenController.requestRewind()
        ).bounds(width / 2 - 100, height / 4 + 120, 200, 20).build());
        deathrewind$applyButtonStates();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void deathrewind$onTick(CallbackInfo callbackInfo) {
        deathrewind$applyButtonStates();
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void deathrewind$onRemoved(CallbackInfo callbackInfo) {
        DeathScreenController.screenClosed();
    }

    @Unique
    private void deathrewind$applyButtonStates() {
        if (deathrewind$rewindButton == null) {
            return;
        }

        boolean canRewind = DeathScreenController.canRewind();
        deathrewind$rewindButton.active = canRewind && delayTicker >= 20;

        boolean lockVanillaButtons =
                DeathScreenController.shouldLockVanillaButtons(canRewind);
        if (lockVanillaButtons) {
            for (var child : children()) {
                if (child instanceof AbstractWidget widget
                        && widget != deathrewind$rewindButton) {
                    widget.active = false;
                }
            }
            deathrewind$lockedVanillaButtons = true;
            return;
        }

        if (deathrewind$lockedVanillaButtons) {
            for (var child : children()) {
                if (child instanceof AbstractWidget widget
                        && widget != deathrewind$rewindButton) {
                    widget.active = delayTicker >= 20;
                }
            }
            deathrewind$lockedVanillaButtons = false;
        }
    }
}
