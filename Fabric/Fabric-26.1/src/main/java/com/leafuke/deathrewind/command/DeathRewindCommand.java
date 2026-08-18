package com.leafuke.deathrewind.command;

import com.leafuke.deathrewind.runtime.DeathRewindRuntime;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public final class DeathRewindCommand {
    private DeathRewindCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dr")
                .requires(DeathRewindCommand::canUseCommand)
                .then(Commands.literal("status")
                        .executes(DeathRewindCommand::executeStatus))
                .then(Commands.literal("pause")
                        .executes(DeathRewindCommand::executePause))
                .then(Commands.literal("resume")
                        .executes(DeathRewindCommand::executeResume))
                .then(Commands.literal("interval")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(DeathRewindCommand::executeInterval))));
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var status = DeathRewindRuntime.getSessionStatus();

        if (status == null) {
            source.sendFailure(Component.translatable("deathrewind.command.status.disabled"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "deathrewind.command.status.info",
                status.enabled() ? Component.translatable("deathrewind.command.status.enabled_state")
                        : Component.translatable("deathrewind.command.status.paused_state"),
                status.intervalMinutes(),
                status.elapsedMinutes(),
                status.remainingMinutes()), false);
        return 1;
    }

    private static int executePause(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        if (!DeathRewindRuntime.hasIntegratedSession()) {
            source.sendFailure(Component.translatable("deathrewind.command.no_session"));
            return 0;
        }

        if (DeathRewindRuntime.pauseCheckpoints()) {
            source.sendSuccess(() -> Component.translatable("deathrewind.command.pause.success")
                    .withStyle(ChatFormatting.YELLOW), true);
            return 1;
        } else {
            source.sendFailure(Component.translatable("deathrewind.command.pause.already_paused"));
            return 0;
        }
    }

    private static int executeResume(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        if (!DeathRewindRuntime.hasIntegratedSession()) {
            source.sendFailure(Component.translatable("deathrewind.command.no_session"));
            return 0;
        }

        if (DeathRewindRuntime.resumeCheckpoints()) {
            source.sendSuccess(() -> Component.translatable("deathrewind.command.resume.success")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } else {
            source.sendFailure(Component.translatable("deathrewind.command.resume.already_running"));
            return 0;
        }
    }

    private static int executeInterval(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        int minutes = IntegerArgumentType.getInteger(context, "minutes");

        if (!DeathRewindRuntime.hasIntegratedSession()) {
            source.sendFailure(Component.translatable("deathrewind.command.no_session"));
            return 0;
        }

        if (DeathRewindRuntime.setInterval(minutes)) {
            source.sendSuccess(() -> Component.translatable("deathrewind.command.interval.success", minutes)
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } else {
            source.sendFailure(Component.translatable("deathrewind.command.interval.failed"));
            return 0;
        }
    }

    private static boolean canUseCommand(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }
        // Allow console
        if (source.getPlayer() == null) {
            return true;
        }
        // Allow singleplayer owner
        ServerPlayer player = source.getPlayer();
        GameProfile owner = server.getSingleplayerProfile();
        if (owner != null && owner.id().equals(player.getGameProfile().id())) {
            return true;
        }
        // Allow ops level 2 (COMMANDS_MODERATOR)
        return source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
    }
}
