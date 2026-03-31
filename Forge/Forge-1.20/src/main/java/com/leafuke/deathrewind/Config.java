package com.leafuke.deathrewind;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Config {
    private static final String CONFIG_FILE = "deathrewind.properties";
    private static final int DEFAULT_INTERVAL_MINUTES = 5;
    private static final boolean DEFAULT_SHOW_BACKUP_INFO = true;
    private static final boolean DEFAULT_FORCE_DEATH_REWIND = false;

    private static int intervalMinutes = DEFAULT_INTERVAL_MINUTES;
    private static boolean showBackupInfo = DEFAULT_SHOW_BACKUP_INFO;
    private static boolean forceDeathRewind = DEFAULT_FORCE_DEATH_REWIND;

    private Config() {
    }

    public static synchronized void load() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            save();
            return;
        }

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            props.load(reader);
            intervalMinutes = sanitizeInterval(parseInt(props.getProperty("intervalMinutes"), DEFAULT_INTERVAL_MINUTES));
            showBackupInfo = parseBoolean(props.getProperty("showBackupInfo"), DEFAULT_SHOW_BACKUP_INFO);
            forceDeathRewind = parseBoolean(props.getProperty("forceDeathRewind"), DEFAULT_FORCE_DEATH_REWIND);
        } catch (IOException e) {
            DeathRewind.LOGGER.error("Failed to load DeathRewind config", e);
            resetToDefaults();
        }
    }

    public static synchronized void save() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        Properties props = new Properties();
        props.setProperty("intervalMinutes", String.valueOf(intervalMinutes));
        props.setProperty("showBackupInfo", String.valueOf(showBackupInfo));
        props.setProperty("forceDeathRewind", String.valueOf(forceDeathRewind));

        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            props.store(writer, "Death Rewind Config");
        } catch (IOException e) {
            DeathRewind.LOGGER.error("Failed to save DeathRewind config", e);
        }
    }

    public static synchronized int getIntervalMinutes() {
        return intervalMinutes;
    }

    public static synchronized boolean isShowBackupInfo() {
        return showBackupInfo;
    }

    public static synchronized boolean isForceDeathRewind() {
        return forceDeathRewind;
    }

    private static void resetToDefaults() {
        intervalMinutes = DEFAULT_INTERVAL_MINUTES;
        showBackupInfo = DEFAULT_SHOW_BACKUP_INFO;
        forceDeathRewind = DEFAULT_FORCE_DEATH_REWIND;
    }

    private static int sanitizeInterval(int value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 1440);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }
}

