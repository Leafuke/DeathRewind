package com.leafuke.deathrewind;

import com.leafuke.deathrewind.runtime.DeathRewindRuntime;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(DeathRewind.MOD_ID)
public final class DeathRewind {
    public static final String MOD_ID = "deathrewind";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public DeathRewind() {
        DeathRewindRuntime.register();
        if (FMLLoader.getDist().isClient()) {
            com.leafuke.deathrewind.client.DeathRewindClient.initialize();
        }
    }
}
