package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.BotConfig;
import net.minecraft.core.BlockPos;

/**
 * 3-block cardinal leap (sprint-jump max distance). Same gap-requirement as
 * Parkour2: both intermediate cells must be air at foot+head with no
 * stand-able floor below either, or A* should chain cheaper moves instead.
 * Cost ≈ 32 (3×walk + jump+sprint overhead).
 */
public final class Parkour3 extends Move {
    public Parkour3(int dx, int dz) { super(dx * 3, 0, dz * 3, 32); }
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
        for (int i = 1; i <= 2; i++) {
            BlockPos mid = from.offset(sx * i, 0, sz * i);
            if (!w.isPassable(mid) || w.isHazard(mid)) return false;
            BlockPos midHead = mid.offset(0, 1, 0);
            if (!w.isPassable(midHead) || w.isHazard(midHead)) return false;
            if (w.canStandAt(mid)) return false;
        }
        return w.isPassable(to.offset(0, 1, 0));
    }
    public String name() { return "parkour3"; }
}
