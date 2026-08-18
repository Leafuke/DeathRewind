package com.leafuke.deathrewind.config;

import java.util.Locale;

public final class DeathRewindConfig {
    public int schemaVersion = 1;
    public boolean enabled = true;
    public int intervalMinutes = 5;
    public boolean showBackupInfo = true;
    public boolean forceDeathRewind = false;
    public Backup backup = new Backup();

    public void validate() {
        require(schemaVersion == 1, "schemaVersion must be 1");
        requireRange(intervalMinutes, 1, 1440, "intervalMinutes");
        require(backup != null, "backup must be an object");
        backup.validate();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        require(value >= minimum && value <= maximum,
                field + " must be between " + minimum + " and " + maximum);
    }

    public static final class Backup {
        public String mode = "incremental";
        public String compressionMethod = "zstd";
        public int compressionLevel = 6;

        public void validate() {
            require(mode != null, "backup.mode must be a string");
            mode = mode.trim().toLowerCase(Locale.ROOT);
            require(mode.equals("full") || mode.equals("incremental"),
                    "backup.mode must be full or incremental");

            require(compressionMethod != null, "backup.compressionMethod must be a string");
            compressionMethod = normalizeCompressionMethod(compressionMethod);
            int minimum = switch (compressionMethod) {
                case "zstd", "BZip2" -> 1;
                default -> 0;
            };
            int maximum = compressionMethod.equals("zstd") ? 22 : 9;
            requireRange(compressionLevel, minimum, maximum, "backup.compressionLevel");
        }

        private static String normalizeCompressionMethod(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "lzma2" -> "LZMA2";
                case "deflate" -> "Deflate";
                case "bzip2" -> "BZip2";
                case "zstd" -> "zstd";
                default -> throw new IllegalArgumentException(
                        "backup.compressionMethod must be LZMA2, Deflate, BZip2, or zstd");
            };
        }
    }
}
