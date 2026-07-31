package com.leafuke.deathrewind.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DeathRewindConfigManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get()
            .resolve("death-rewind.json");

    private DeathRewindConfigManager() {
    }

    public static LoadResult load() {
        try {
            if (Files.notExists(CONFIG_PATH)) {
                var defaults = new DeathRewindConfig();
                defaults.validate();
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(
                        CONFIG_PATH,
                        GSON.toJson(defaults) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return LoadResult.success(defaults);
            }

            JsonElement root = JsonParser.parseString(
                    Files.readString(CONFIG_PATH, StandardCharsets.UTF_8));
            validateTypes(root);
            var config = GSON.fromJson(root, DeathRewindConfig.class);
            if (config == null) {
                return LoadResult.failure("configuration root must be a JSON object");
            }
            config.validate();
            return LoadResult.success(config);
        } catch (JsonParseException | IllegalArgumentException exception) {
            return LoadResult.failure(exception.getMessage());
        } catch (IOException exception) {
            return LoadResult.failure(
                    "could not read or create " + CONFIG_PATH + ": " + exception.getMessage());
        }
    }

    private static void validateTypes(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("configuration root must be a JSON object");
        }
        var object = root.getAsJsonObject();
        integer(object, "schemaVersion", "schemaVersion");
        bool(object, "enabled", "enabled");
        integer(object, "intervalMinutes", "intervalMinutes");
        bool(object, "showBackupInfo", "showBackupInfo");
        bool(object, "forceDeathRewind", "forceDeathRewind");

        var backup = object(object, "backup", "backup");
        if (backup != null) {
            string(backup, "mode", "backup.mode");
            string(backup, "compressionMethod", "backup.compressionMethod");
            integer(backup, "compressionLevel", "backup.compressionLevel");
        }
    }

    private static JsonObject object(JsonObject parent, String name, String path) {
        JsonElement value = parent.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void bool(JsonObject parent, String name, String path) {
        JsonElement value = parent.get(name);
        if (value != null
                && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())) {
            throw new IllegalArgumentException(path + " must be a boolean");
        }
    }

    private static void string(JsonObject parent, String name, String path) {
        JsonElement value = parent.get(name);
        if (value != null
                && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())) {
            throw new IllegalArgumentException(path + " must be a string");
        }
    }

    private static void integer(JsonObject parent, String name, String path) {
        JsonElement value = parent.get(name);
        if (value == null) {
            return;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        try {
            if (new BigDecimal(value.getAsString()).stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException(path + " must be an integer");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
    }

    public record LoadResult(DeathRewindConfig config, String error) {
        public static LoadResult success(DeathRewindConfig config) {
            return new LoadResult(config, "");
        }

        public static LoadResult failure(String error) {
            return new LoadResult(null, error == null || error.isBlank()
                    ? "unknown configuration error"
                    : error);
        }

        public boolean isSuccess() {
            return config != null;
        }
    }
}
