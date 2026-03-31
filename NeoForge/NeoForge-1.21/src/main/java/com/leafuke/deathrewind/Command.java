package com.leafuke.deathrewind;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.leafuke.deathrewind.restore.HotRestoreState;

public final class Command {

    private Command() {
    }

        public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context, Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("dr")
                .requires(Command::hasCommandAccess)
            .executes(ctx -> {
                ctx.getSource().sendSuccess(Command::buildRootMessage, false);
                return 1;
            })
            .then(Commands.literal("status")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(Command::buildStatusMessage, false);
                    return 1;
                }))
            .then(Commands.literal("plugin")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(Command::buildPluginLinkMessage, false);
                    return 1;
                }))
        );
    }

    private static boolean hasCommandAccess(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }
        if (server.isDedicatedServer()) {
            return source.hasPermission(2);
        }
        return isLocalHost(source);
    }

    private static boolean isLocalHost(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return false;
        }
        GameProfile profile = player.getGameProfile();
        return profile != null && source.getServer().isSingleplayerOwner(profile);
    }

    private static Component buildRootMessage() {
        MutableComponent text = Component.literal("Death-Rewind commands:");
        text.append(Component.literal("\n/dr status - Show backend handshake and restore state"));
        text.append(Component.literal("\n/dr plugin - Show dedicated-server plugin link"));
        return text;
    }

    private static Component buildStatusMessage() {
        boolean handshakeOk = DeathRewind.isHandshakeProbeSucceeded();
        boolean handshakePending = DeathRewind.isHandshakeProbePending();
        boolean restoring = HotRestoreState.isRestoring;
        return Component.literal(String.format(
                "Death-Rewind status | handshake=%s pending=%s restoring=%s",
                handshakeOk ? "ok" : "failed",
                handshakePending,
                restoring
        ));
    }

    private static MutableComponent buildPluginLinkMessage() {
        return Component.translatable("deathrewind.message.plugin_link_prefix")
                .append(Component.literal(DeathRewind.PLUGIN_GUIDE_URL).withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, DeathRewind.PLUGIN_GUIDE_URL))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("deathrewind.message.plugin_link_hover")))
                        .withUnderlined(true)));
    }
}
