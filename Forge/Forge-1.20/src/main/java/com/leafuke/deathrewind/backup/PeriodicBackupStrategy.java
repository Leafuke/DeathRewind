package com.leafuke.deathrewind.backup;

import com.leafuke.deathrewind.config.DeathRewindConfig;
import com.leafuke.minebackup.api.v2.BackupRequest;
import com.leafuke.minebackup.api.v2.BackupResult;
import com.leafuke.minebackup.api.v2.MineBackupApi;
import com.leafuke.minebackup.api.v2.OperationHandle;
import com.leafuke.minebackup.api.v2.OperationPresentation;

import java.util.Map;

public final class PeriodicBackupStrategy {
    private static final String CALLER_ID = "deathrewind:periodic";
    private static final String COMMENT = "Death Rewind periodic checkpoint";

    private final DeathRewindConfig.Backup config;

    public PeriodicBackupStrategy(DeathRewindConfig.Backup config) {
        this.config = config;
    }

    public OperationHandle<BackupResult> submit() {
        var request = BackupRequest.create(CALLER_ID, COMMENT)
                .withParameters(Map.of(
                        "backup_mode", config.mode,
                        "compression_method", config.compressionMethod,
                        "compression_level", Integer.toString(config.compressionLevel)))
                .withPresentation(OperationPresentation.callerManaged());
        return MineBackupApi.getInstance().backupCurrent(request);
    }
}
