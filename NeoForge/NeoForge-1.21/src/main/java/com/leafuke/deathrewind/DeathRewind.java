package com.leafuke.deathrewind;

import com.leafuke.deathrewind.knotlink.OpenSocketQuerier;
import com.leafuke.deathrewind.knotlink.SignalSubscriber;
import com.leafuke.deathrewind.restore.HotRestoreState;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Mod(DeathRewind.MOD_ID)
public final class DeathRewind {
    public static final String MOD_ID = "deathrewind";
    public static final String PLUGIN_GUIDE_URL = "https://modrinth.com/plugin/minebackupplugin";
    public static final String MOD_VERSION = "2.0.0";
    private static final String MIN_MAIN_PROGRAM_VERSION = "1.14.0";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String BROADCAST_APP_ID = "0x00000020";
    public static final String BROADCAST_SIGNAL_ID = "0x00000020";
    private static final String QUERIER_APP_ID = "0x00000020";
    private static final String QUERIER_SOCKET_ID = "0x00000010";

    private static final long INTEGRATED_RESTORE_ACK_POLL_MS = 100L;
    private static final long INTEGRATED_RESTORE_ACK_TIMEOUT_MS = 10_000L;
    private static final long AUTO_BACKUP_TIMEOUT_MS = 120_000L;
    private static final long RESTORE_REQUEST_COOLDOWN_MS = 1_500L;

    private static SignalSubscriber knotLinkSubscriber;
    private static volatile MinecraftServer serverInstance;

    private static volatile boolean saveFrozen = false;
    private static final List<ServerLevel> frozenWorlds = new ArrayList<>();
    private static volatile long freezeTimestamp = 0L;

    private static volatile long autoBackupTickCounter = 0L;
    private static volatile boolean autoBackupInFlight = false;
    private static volatile long autoBackupSentAtMs = 0L;
    private static volatile boolean autoBackupPausedOnDeath = false;

    private static volatile boolean handshakeProbeSucceeded = false;
    private static volatile boolean handshakeProbePending = false;
    private static volatile long lastProbeAtMs = 0L;
    private static volatile long lastRestoreRequestAtMs = 0L;
    private static volatile boolean restoreRequestInFlight = false;

    public DeathRewind(IEventBus modEventBus, Dist dist) {
        NeoForge.EVENT_BUS.register(this);
        if (dist.isClient()) {
            DeathRewindClient.initialize();
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Command.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        serverInstance = event.getServer();
        resetRuntimeState();

        if (event.getServer().isDedicatedServer()) {
            LOGGER.info("DeathRewind is running on a dedicated server with limited support.");
        }

        if (knotLinkSubscriber == null) {
            knotLinkSubscriber = new SignalSubscriber(BROADCAST_APP_ID, BROADCAST_SIGNAL_ID);
            knotLinkSubscriber.setSignalListener(DeathRewind::handleBroadcastEvent);
            Thread subscriberThread = new Thread(knotLinkSubscriber::start, "DeathRewind-KnotLinkSubscriber");
            subscriberThread.setDaemon(true);
            subscriberThread.start();
        } else {
            knotLinkSubscriber.setSignalListener(DeathRewind::handleBroadcastEvent);
        }

        Config.load();
        requestHandshakeProbe(true);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (saveFrozen) {
            unfreezeAutoSave(event.getServer());
        }

        if (event.getServer().isDedicatedServer() && knotLinkSubscriber != null) {
            knotLinkSubscriber.stop();
            knotLinkSubscriber = null;
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        checkFreezeTimeout();
        checkAutoBackupTimeout();
        tickAutoBackup(event.getServer());
    }

    public static void onDeathScreenOpened() {
        autoBackupPausedOnDeath = true;
        requestHandshakeProbe(false);
    }

    public static void onDeathScreenClosed() {
        if (HotRestoreState.isRestoring) {
            return;
        }
        restoreRequestInFlight = false;
        autoBackupPausedOnDeath = false;
    }

    public static boolean canUseDeathRewindButton() {
        MinecraftServer server = serverInstance;
        if (server == null || server.isDedicatedServer()) {
            return false;
        }
        if (handshakeProbePending || !handshakeProbeSucceeded) {
            return false;
        }
        if (restoreRequestInFlight) {
            return false;
        }
        if (HotRestoreState.isRestoring) {
            return false;
        }
        return !isRestoreRequestOnCooldown();
    }

    public static CompletableFuture<String> requestDeathRewind() {
        if (!canUseDeathRewindButton()) {
            return CompletableFuture.completedFuture("ERROR:UNAVAILABLE");
        }

        autoBackupPausedOnDeath = true;
        restoreRequestInFlight = true;
        lastRestoreRequestAtMs = System.currentTimeMillis();
        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "RESTORE_CURRENT_LATEST");
        if (future == null) {
            restoreRequestInFlight = false;
            autoBackupPausedOnDeath = false;
            return CompletableFuture.completedFuture("ERROR:NO_RESPONSE");
        }
        return future.exceptionally(ex -> "ERROR:COMMUNICATION_FAILED")
                .thenApply(response -> {
                    if (response == null || response.startsWith("ERROR:")) {
                        restoreRequestInFlight = false;
                        autoBackupPausedOnDeath = false;
                    }
                    return response;
                });
    }

    public static boolean isHandshakeProbeSucceeded() {
        return handshakeProbeSucceeded;
    }

    public static boolean isHandshakeProbePending() {
        return handshakeProbePending;
    }

    public static boolean isForceDeathRewindEnabled() {
        return Config.isForceDeathRewind();
    }

    public static void onClientRejoinSuccess() {
        restoreRequestInFlight = false;
        autoBackupPausedOnDeath = false;
        autoBackupTickCounter = 0L;
    }

    public static void onClientRejoinCancelled() {
        restoreRequestInFlight = false;
        autoBackupPausedOnDeath = false;
    }

    public static void freezeAutoSave(MinecraftServer server) {
        if (saveFrozen || server == null) {
            return;
        }

        synchronized (frozenWorlds) {
            frozenWorlds.clear();
            for (ServerLevel level : server.getAllLevels()) {
                if (level != null && !level.noSave) {
                    level.noSave = true;
                    frozenWorlds.add(level);
                }
            }
        }

        saveFrozen = true;
        freezeTimestamp = System.currentTimeMillis();
    }

    public static void unfreezeAutoSave(MinecraftServer server) {
        if (!saveFrozen || server == null) {
            return;
        }

        synchronized (frozenWorlds) {
            for (ServerLevel level : frozenWorlds) {
                if (level != null && level.noSave) {
                    level.noSave = false;
                }
            }
            frozenWorlds.clear();
        }

        saveFrozen = false;
        freezeTimestamp = 0L;
    }

    public static boolean isVersionCompatible(String current, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        if (current == null || current.isBlank()) {
            return false;
        }

        try {
            int[] currentParts = parseVersionParts(current);
            int[] requiredParts = parseVersionParts(required);
            for (int i = 0; i < 3; i++) {
                if (currentParts[i] > requiredParts[i]) {
                    return true;
                }
                if (currentParts[i] < requiredParts[i]) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse version numbers: current={}, required={}", current, required);
            return false;
        }
    }

    private static void resetRuntimeState() {
        autoBackupTickCounter = 0L;
        autoBackupInFlight = false;
        autoBackupSentAtMs = 0L;
        autoBackupPausedOnDeath = false;
        handshakeProbeSucceeded = false;
        handshakeProbePending = false;
        lastProbeAtMs = 0L;
        lastRestoreRequestAtMs = 0L;
        restoreRequestInFlight = false;
        HotRestoreState.resetHandshake();
        HotRestoreState.resetRestore();
    }

    private static void requestHandshakeProbe(boolean announceFailureToPlayers) {
        handshakeProbePending = true;
        lastProbeAtMs = System.currentTimeMillis();

        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "LIST_CONFIGS");
        if (future == null) {
            finishHandshakeProbe(false, announceFailureToPlayers);
            return;
        }

        future.exceptionally(ex -> "ERROR:COMMUNICATION_FAILED")
                .thenAccept(response -> {
                    boolean success = response != null && response.startsWith("OK:");
                    finishHandshakeProbe(success, announceFailureToPlayers);
                });
    }

    private static void finishHandshakeProbe(boolean success, boolean announceFailureToPlayers) {
        handshakeProbeSucceeded = success;
        handshakeProbePending = false;

        if (!success && announceFailureToPlayers && serverInstance != null) {
            serverInstance.execute(() -> {
                if (!serverInstance.getPlayerList().getPlayers().isEmpty()) {
                    serverInstance.getPlayerList().broadcastSystemMessage(
                            Component.translatable("deathrewind.message.handshake.open_failed"), false);
                }
            });
        }
    }

    private static void tickAutoBackup(MinecraftServer server) {
        if (server == null || server.isDedicatedServer()) {
            return;
        }
        if (autoBackupPausedOnDeath || HotRestoreState.isRestoring || autoBackupInFlight) {
            return;
        }

        long intervalTicks = Math.max(1, Config.getIntervalMinutes()) * 60L * 20L;
        autoBackupTickCounter++;
        if (autoBackupTickCounter < intervalTicks) {
            return;
        }

        autoBackupTickCounter = 0L;
        autoBackupInFlight = true;
        autoBackupSentAtMs = System.currentTimeMillis();

        CompletableFuture<String> future = OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "BACKUP_CURRENT");
        if (future == null) {
            markAutoBackupFailed();
            return;
        }

        future.exceptionally(ex -> "ERROR:COMMUNICATION_FAILED")
                .thenAccept(response -> {
                    if (response == null || response.startsWith("ERROR:")) {
                        markAutoBackupFailed();
                    }
                });
    }

    private static void checkAutoBackupTimeout() {
        if (!autoBackupInFlight) {
            return;
        }
        if (System.currentTimeMillis() - autoBackupSentAtMs <= AUTO_BACKUP_TIMEOUT_MS) {
            return;
        }

        LOGGER.warn("Auto backup timed out while waiting backend events.");
        markAutoBackupFailed();
    }

    private static void markAutoBackupFailed() {
        autoBackupInFlight = false;
        autoBackupSentAtMs = 0L;
        notifyAutoBackupResult(false);
    }

    private static void notifyAutoBackupResult(boolean success) {
        if (!Config.isShowBackupInfo() || serverInstance == null) {
            return;
        }

        serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                Component.translatable(success ? "deathrewind.message.auto_backup.success" : "deathrewind.message.auto_backup.failed"),
                false
        ));
    }

    private static Map<String, String> parsePayload(String payload) {
        Map<String, String> dataMap = new HashMap<>();
        if (payload == null || payload.isEmpty()) {
            return dataMap;
        }

        for (String pair : payload.split(";")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                dataMap.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return dataMap;
    }

    private static void handleBroadcastEvent(String payload) {
        if (serverInstance == null) {
            return;
        }

        Map<String, String> eventData = parsePayload(payload);
        String eventType = eventData.get("event");
        if (eventType == null) {
            return;
        }

        if (serverInstance.isDedicatedServer() && !isDedicatedEventAllowed(eventType)) {
            handleIgnoredDedicatedEvent(eventType);
            return;
        }

        switch (eventType) {
            case "handshake" -> handleHandshake(eventData);
            case "pre_hot_restore" -> handlePreHotRestore(eventData);
            case "restore_success", "restore_finished" -> handleRestoreFinished(eventData, eventType);
            case "rejoin_world" -> handleRejoinWorld(eventData);
            case "pre_hot_backup" -> handlePreHotBackup();
            case "backup_success" -> handleBackupFinished(true);
            case "backup_failed" -> handleBackupFinished(false);
            default -> {
            }
        }
    }

    private static boolean isDedicatedEventAllowed(String eventType) {
        return switch (eventType) {
            case "handshake", "pre_hot_backup", "backup_started", "backup_success", "backup_failed",
                    "auto_backup_started", "we_snapshot_completed", "game_session_end" -> true;
            default -> false;
        };
    }

    private static void handleIgnoredDedicatedEvent(String eventType) {
        switch (eventType) {
            case "pre_hot_restore" -> {
                LOGGER.warn("Ignoring unsupported dedicated-server restore event: {}", eventType);
                broadcastDedicatedRestoreUnsupported();
            }
            case "restore_started", "restore_finished", "restore_success", "rejoin_world" ->
                    LOGGER.warn("Ignoring unsupported dedicated-server restore event: {}", eventType);
            default -> LOGGER.info("Ignoring unsupported backend event on dedicated server: {}", eventType);
        }
    }

    private static void broadcastDedicatedRestoreUnsupported() {
        if (serverInstance == null) {
            return;
        }
        serverInstance.execute(() -> {
            serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("deathrewind.message.restore.unsupported_dedicated"), false);
            serverInstance.getPlayerList().broadcastSystemMessage(buildPluginLinkMessage(), false);
        });
    }

    private static Component buildPluginLinkMessage() {
        return Component.translatable("deathrewind.message.plugin_link_prefix")
                .append(Component.literal(PLUGIN_GUIDE_URL).withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, PLUGIN_GUIDE_URL))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("deathrewind.message.plugin_link_hover")))
                        .withUnderlined(true)));
    }

    private static void handleHandshake(Map<String, String> eventData) {
        String mainVersion = eventData.get("version");
        String minModVersion = eventData.get("min_mod_version");
        String displayMainVersion = mainVersion != null ? mainVersion : "?";

        handshakeProbeSucceeded = true;
        handshakeProbePending = false;

        HotRestoreState.mainProgramVersion = mainVersion;
        HotRestoreState.handshakeCompleted = true;
        HotRestoreState.requiredMinModVersion = minModVersion;
        HotRestoreState.versionCompatible = isVersionCompatible(MOD_VERSION, minModVersion);

        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "HANDSHAKE_RESPONSE " + MOD_VERSION);

        if (!isVersionCompatible(mainVersion, MIN_MAIN_PROGRAM_VERSION)) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("deathrewind.message.handshake.main_version_incompatible",
                            displayMainVersion, MIN_MAIN_PROGRAM_VERSION),
                    false
            ));
            return;
        }

        if (!HotRestoreState.versionCompatible) {
            serverInstance.execute(() -> serverInstance.getPlayerList().broadcastSystemMessage(
                    Component.translatable("deathrewind.message.handshake.version_incompatible",
                            MOD_VERSION, minModVersion != null ? minModVersion : "?"),
                    false
            ));
        }
    }

    private static void handlePreHotBackup() {
        serverInstance.execute(() -> {
            LocalSaveCoordinator.saveForLocalRestore(serverInstance);
            freezeAutoSave(serverInstance);
            OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVED");
        });
    }

    private static void handleBackupFinished(boolean success) {
        serverInstance.execute(() -> {
            if (saveFrozen) {
                unfreezeAutoSave(serverInstance);
            }

            if (autoBackupInFlight) {
                autoBackupInFlight = false;
                autoBackupSentAtMs = 0L;
                notifyAutoBackupResult(success);
            }
        });
    }

    private static void handlePreHotRestore(Map<String, String> eventData) {
        serverInstance.execute(() -> {
            if (HotRestoreState.isRestoring) {
                LOGGER.warn("Ignored duplicate pre_hot_restore signal while restore is still running.");
                return;
            }

            if (serverInstance.isDedicatedServer()) {
                broadcastDedicatedRestoreUnsupported();
                return;
            }

            serverInstance.getPlayerList().broadcastSystemMessage(Component.translatable("deathrewind.message.restore.preparing"), false);
            HotRestoreState.isRestoring = true;
            HotRestoreState.waitingForServerStopAck = true;
            restoreRequestInFlight = false;

            String levelId = resolveRejoinLevelId(serverInstance, eventData.get("world"));
            DeathRewindClient.setWorldToRejoin(levelId);
            HotRestoreState.levelIdToRejoin = levelId;
            LocalSaveCoordinator.saveForLocalRestore(serverInstance);

            Component kickMessage = Component.translatable("deathrewind.message.restore.kick");
            disconnectPlayersForRestore(kickMessage, true);
            startIntegratedRestoreAckWatcher(serverInstance);
        });
    }

    private static void handleRestoreFinished(Map<String, String> eventData, String eventType) {
        String status = "restore_success".equals(eventType) ? "success" : eventData.getOrDefault("status", "success");
        if (!"success".equals(status)) {
            DeathRewindClient.clearReadyToRejoin();
            DeathRewindClient.resetRestoreState();
            onClientRejoinCancelled();
            return;
        }

        String worldFromEvent = eventData.get("world");
        if (isValidLevelId(worldFromEvent)) {
            String fallbackLevelId = worldFromEvent.trim();
            if (!isValidLevelId(HotRestoreState.levelIdToRejoin)) {
                HotRestoreState.levelIdToRejoin = fallbackLevelId;
            }
            if (!isValidLevelId(DeathRewindClient.getWorldToRejoin())) {
                DeathRewindClient.setWorldToRejoin(fallbackLevelId);
            }
        }

        DeathRewindClient.clearReadyToRejoin();
        if (HotRestoreState.levelIdToRejoin != null && DeathRewindClient.getWorldToRejoin() == null) {
            DeathRewindClient.setWorldToRejoin(HotRestoreState.levelIdToRejoin);
        }

        HotRestoreState.waitingForServerStopAck = false;
        DeathRewindClient.showRestoreSuccessOverlay();
    }

    private static void handleRejoinWorld(Map<String, String> eventData) {
        String worldFromEvent = eventData.get("world");
        if (isValidLevelId(worldFromEvent)) {
            String fallbackLevelId = worldFromEvent.trim();
            if (!isValidLevelId(HotRestoreState.levelIdToRejoin)) {
                HotRestoreState.levelIdToRejoin = fallbackLevelId;
            }
            if (!isValidLevelId(DeathRewindClient.getWorldToRejoin())) {
                DeathRewindClient.setWorldToRejoin(fallbackLevelId);
            }
        }

        if (HotRestoreState.levelIdToRejoin != null && DeathRewindClient.getWorldToRejoin() == null) {
            DeathRewindClient.setWorldToRejoin(HotRestoreState.levelIdToRejoin);
        }

        if (!DeathRewindClient.isReadyToRejoin()) {
            if (isValidLevelId(DeathRewindClient.getWorldToRejoin())) {
                DeathRewindClient.markReadyToRejoin();
            } else {
                OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "REJOIN_RESULT failure invalid_level_id");
                onClientRejoinCancelled();
            }
        }

        HotRestoreState.waitingForServerStopAck = false;
    }

    private static void disconnectPlayersForRestore(Component kickMessage, boolean hostLast) {
        ServerPlayer[] players = serverInstance.getPlayerList().getPlayers().toArray(new ServerPlayer[0]);
        if (!hostLast) {
            for (ServerPlayer player : players) {
                disconnectPlayer(player, kickMessage);
            }
            return;
        }

        for (ServerPlayer player : players) {
            if (!isSingleplayerHost(player)) {
                disconnectPlayer(player, kickMessage);
            }
        }
        for (ServerPlayer player : players) {
            if (isSingleplayerHost(player)) {
                disconnectPlayer(player, kickMessage);
            }
        }
    }

    private static void disconnectPlayer(ServerPlayer player, Component kickMessage) {
        try {
            player.connection.disconnect(kickMessage);
        } catch (Exception e) {
            LOGGER.warn("Failed to disconnect player '{}': {}", player.getGameProfile().getName(), e.getMessage());
        }
    }

    private static boolean isSingleplayerHost(ServerPlayer player) {
        if (player == null || serverInstance == null) {
            return false;
        }
        try {
            GameProfile profile = player.getGameProfile();
            return profile != null && serverInstance.isSingleplayerOwner(profile);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void startIntegratedRestoreAckWatcher(MinecraftServer restoreServer) {
        Thread watcher = new Thread(() -> {
            long startAt = System.currentTimeMillis();
            while (true) {
                if (isIntegratedRestoreReadyForAck(restoreServer)) {
                    sendWorldSaveAndExitAck();
                    return;
                }

                long elapsed = System.currentTimeMillis() - startAt;
                if (elapsed >= INTEGRATED_RESTORE_ACK_TIMEOUT_MS) {
                    LOGGER.warn("Timed out waiting integrated server release for restore ACK after {}ms.", elapsed);
                    sendWorldSaveAndExitAck();
                    return;
                }

                sleepQuietly(INTEGRATED_RESTORE_ACK_POLL_MS);
            }
        }, "DeathRewind-RestoreAckWaiter");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static boolean isIntegratedRestoreReadyForAck(MinecraftServer restoreServer) {
        if (restoreServer == null) {
            return true;
        }

        try {
            if (!restoreServer.getPlayerList().getPlayers().isEmpty()) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        return canAcquireSessionLock(restoreServer);
    }

    private static boolean canAcquireSessionLock(MinecraftServer restoreServer) {
        try {
            Path root = restoreServer.getWorldPath(LevelResource.ROOT);
            if (root == null) {
                return true;
            }

            Path lockPath = root.resolve("session.lock");
            if (!Files.exists(lockPath)) {
                return true;
            }

            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
                try (FileLock lock = channel.tryLock()) {
                    return lock != null;
                } catch (OverlappingFileLockException e) {
                    return false;
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void sendWorldSaveAndExitAck() {
        OpenSocketQuerier.query(QUERIER_APP_ID, QUERIER_SOCKET_ID, "WORLD_SAVE_AND_EXIT_COMPLETE");
    }

    private static void checkFreezeTimeout() {
        if (!saveFrozen || serverInstance == null || !FreezeWatchdog.hasTimedOut(freezeTimestamp)) {
            return;
        }

        long elapsed = FreezeWatchdog.elapsedSince(freezeTimestamp);
        LOGGER.error("Auto-save freeze timed out after {}ms, forcing resume.", elapsed);
        unfreezeAutoSave(serverInstance);
    }

    private static String resolveRejoinLevelId(MinecraftServer server, String eventWorld) {
        String resolved = resolveLevelFolder(server);
        if (isValidLevelId(resolved)) {
            return resolved;
        }
        if (isValidLevelId(eventWorld)) {
            return eventWorld.trim();
        }
        return "world";
    }

    private static String resolveLevelFolder(MinecraftServer server) {
        String levelIdFromPath = null;
        try {
            Path root = server.getWorldPath(LevelResource.ROOT);
            levelIdFromPath = resolveLevelIdFromPath(root);
        } catch (Exception ignored) {
        }

        if (isValidLevelId(levelIdFromPath)) {
            return levelIdFromPath;
        }

        String levelName = server.getWorldData().getLevelName();
        if (isValidLevelId(levelName)) {
            return levelName;
        }
        return "world";
    }

    private static String resolveLevelIdFromPath(Path path) {
        if (path == null) {
            return null;
        }

        Path cursor = path;
        for (int i = 0; i < 6 && cursor != null; i++) {
            if (Files.exists(cursor.resolve("level.dat"))) {
                Path fileName = cursor.getFileName();
                if (fileName != null) {
                    String levelId = fileName.toString();
                    if (isValidLevelId(levelId)) {
                        return levelId;
                    }
                }
            }
            cursor = cursor.getParent();
        }

        Path fileName = path.getFileName();
        if (fileName == null) {
            return null;
        }

        String levelId = fileName.toString();
        return isValidLevelId(levelId) ? levelId : null;
    }

    private static boolean isValidLevelId(String levelId) {
        if (levelId == null) {
            return false;
        }

        String normalized = levelId.trim();
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)) {
            return false;
        }
        return !normalized.contains("/") && !normalized.contains("\\");
    }

    private static boolean isRestoreRequestOnCooldown() {
        if (lastRestoreRequestAtMs <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - lastRestoreRequestAtMs < RESTORE_REQUEST_COOLDOWN_MS;
    }

    private static int[] parseVersionParts(String version) {
        String[] parts = version.split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
