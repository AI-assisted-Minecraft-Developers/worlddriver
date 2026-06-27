package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.BotConfig;
import net.minecraft.core.BlockPos;

/**
 * 2-block 45° diagonal leap — bridges the inside corner of an L-gap.
 * The intermediate cell is the shared diagonal midpoint; both adjacent
 * cardinal sides must also be passable at foot+head (player physically
 * cuts across the corner). No stand-able floor at the mid cell, or a
 * Walk+Diagonal pair would be cheaper. Cost ≈ 33 ≈ sqrt(8)*10 + jump.
 */
public final class Parkour2Diagonal extends Move {
    public Parkour2Diagonal(int dx, int dz) { super(dx * 2, 0, dz * 2, 33); }
    public boolean valid(WorldView w, BlockPos from) {
        // Buoyancy TAKEOFF gate: a floating bot can't sprint-jump out of deep water (no floor to push off).
        // Mirrors StepUp/DiagUp/PillarUp; complements pathfinderForbidParkourIntoDeepWater. See BotConfig doc.
        if (BotConfig.pathfinderForbidParkourFromFloatingWater && w.isFloatingWater(from)) return false;
        if (!Move.hasRunway(w, from)) return false;
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        // Buoyancy: no parkour LANDING in submerged water — the bot sinks/stalls there
        // instead of leaping (mirrors Fall/StepDown's submerged gate; surface/solid OK).
        if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) return false;
        // ...and (opt-in) refuse a leap onto a DEEP pocket SURFACE (≥2 water below, head
        // air): buoyant bot floats there and can't climb out (#47 dead-end-pocket dive).
        if (BotConfig.pathfinderForbidParkourIntoDeepWater && w.isDeepWaterSurfaceLanding(to)) return false;
        if (!w.isPassable(from.offset(0, 2, 0))) return false;
        int sx = Integer.signum(dx), sz = Integer.signum(dz);
        // Diagonal midpoint (1,1) and the two cardinal half-steps must
        // all be clear at foot+head — body sweeps that whole corner.
        BlockPos[] mids = {
            from.offset(sx, 0, sz),
            from.offset(sx, 0, 0),
            from.offset(0, 0, sz),
        };
        for (BlockPos mid : mids) {
            if (!w.isPassable(mid) || w.isHazard(mid)) return false;
            BlockPos midHead = mid.offset(0, 1, 0);
            if (!w.isPassable(midHead) || w.isHazard(midHead)) return false;
        }
        if (w.canStandAt(from.offset(sx, 0, sz))) return false; // mid floor → cheaper Walk+Diagonal
        return w.isPassable(to.offset(0, 1, 0));
    }
    public String name() { return "parkour2d"; }
}
