package net.magicterra.agent.bot.world;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/** Serializable scene summary built from a HazardField (+ optional live facts). */
public final class SceneModel {
    public final BlockPos center;
    public final int radius;
    public final HazardField hazard;
    public final int lethalCount;
    public final boolean cornered;
    public final int[] safeFleeStep; // {dx,dz} or null

    public SceneModel(BlockPos center, int radius, HazardField hazard,
                      int lethalCount, boolean cornered, int[] safeFleeStep) {
        this.center = center;
        this.radius = radius;
        this.hazard = hazard;
        this.lethalCount = lethalCount;
        this.cornered = cornered;
        this.safeFleeStep = safeFleeStep;
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lethalCount", lethalCount);
        m.put("cornered", cornered);
        if (safeFleeStep != null) {
            m.put("safeFleeStep", Map.of("dx", safeFleeStep[0], "dz", safeFleeStep[1]));
        }
        return m;
    }
}
