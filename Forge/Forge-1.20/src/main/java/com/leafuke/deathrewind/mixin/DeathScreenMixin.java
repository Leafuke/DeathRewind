package com.leafuke.deathrewind.mixin;

import com.leafuke.deathrewind.DeathRewind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin extends Screen {
    @Unique
    private Button deathrewind$rewindButton;
    @Unique
    private boolean deathrewind$warnedHandshakeFailure;

    protected DeathScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void deathrewind$onInit(CallbackInfo ci) {
        DeathRewind.onDeathScreenOpened();

        this.deathrewind$rewindButton = this.addRenderableWidget(Button.builder(
                Component.translatable("deathrewind.button.rewind"),
                button -> deathrewind$sendRestoreRequest()
        ).bounds(this.width / 2 - 100, this.height / 4 + 120, 200, 20).build());

        deathrewind$applyButtonStates();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void deathrewind$onTick(CallbackInfo ci) {
        deathrewind$applyButtonStates();

        if (!DeathRewind.isHandshakeProbePending() && !DeathRewind.isHandshakeProbeSucceeded() && !deathrewind$warnedHandshakeFailure) {
            deathrewind$warnedHandshakeFailure = true;
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.translatable("deathrewind.message.handshake.death_failed"));
            }
        }
    }

    @Unique
    private void deathrewind$applyButtonStates() {
        if (deathrewind$rewindButton == null) {
            return;
        }

        boolean buttonActive = DeathRewind.canUseDeathRewindButton();
        deathrewind$rewindButton.active = buttonActive;

        if (!DeathRewind.isForceDeathRewindEnabled()) {
            return;
        }

        for (GuiEventListener element : this.children()) {
            if (element instanceof AbstractWidget widget && widget != deathrewind$rewindButton) {
                widget.active = false;
            }
        }

        deathrewind$rewindButton.active = buttonActive;
    }

    @Unique
    private void deathrewind$sendRestoreRequest() {
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
}
