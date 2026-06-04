package net.magicterra.agent.bot.world;

/** Pure threat-avoidance path-cost field (Baritone {@code Avoidance} pattern):
 *  each nearby hostile contributes a distance-ramped penalty so A* routes AROUND
 *  mobs. Ranged mobs carry a larger per-mob radius (snapshotted by the caller) so
 *  the planner gives skeletons/witches a wider berth than melee mobs. Pure over a
 *  flat [x,y,z,r, ...] array → no Minecraft types, unit-testable. */
public final class ThreatAvoidance {
    private ThreatAvoidance() {}

    /**
     * Sum the avoidance penalty at a foot-cell centre from all snapshotted threats.
     * @param mobXyzr     flat [x0,y0,z0,r0, x1,y1,z1,r1, ...]; r = per-mob avoid radius
     * @param basePenalty peak penalty at a mob's centre (BotConfig.mobAvoidPenalty)
     */
    public static double cost(float[] mobXyzr, double basePenalty, double fx, double fy, double fz) {
        if (mobXyzr == null || basePenalty <= 0) return 0;
        double penalty = 0;
        for (int i = 0; i + 3 < mobXyzr.length; i += 4) {
            double r = mobXyzr[i + 3];
            if (r <= 0) continue;
            double dx = fx - mobXyzr[i], dy = fy - mobXyzr[i + 1], dz = fz - mobXyzr[i + 2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < r) penalty += basePenalty * (r - dist) / r;
        }
        return penalty;
    }
}
