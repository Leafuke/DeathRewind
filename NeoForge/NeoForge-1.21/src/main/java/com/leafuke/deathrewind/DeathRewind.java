package com.leafuke.deathrewind;

import com.leafuke.deathrewind.runtime.DeathRewindRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(DeathRewind.MOD_ID)
public final class DeathRewind {
    public static final String MOD_ID = "deathrewind";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public DeathRewind(IEventBus modBus, Dist dist) {
        DeathRewindRuntime.register(modBus);
        if (dist.isClient()) {
            com.leafuke.deathrewind.client.DeathRewindClient.initialize();
        }
    }
}
