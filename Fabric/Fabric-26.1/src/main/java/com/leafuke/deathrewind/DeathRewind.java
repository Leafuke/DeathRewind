package com.leafuke.deathrewind;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeathRewind implements ModInitializer {
    public static final String MOD_ID = "deathrewind";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Death Rewind 2.0 initialized.");
    }
}
