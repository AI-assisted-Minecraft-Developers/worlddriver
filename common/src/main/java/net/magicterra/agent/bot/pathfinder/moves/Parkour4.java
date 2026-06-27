package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.magicterra.agent.bot.BotConfig;

/**
 * 4-block cardinal leap — Baritone {@code allowParkour4} analogue. At
 * the edge of vanilla physics: needs sprint-jump with no headwind, often
 * requires jump-boost / Speed potion to land cleanly. Gated by
 * {@link net.magicterra.agent.bot.BotConfig#allowParkour4} (default
 * false) so the A* default never emits it. Cost 42 = ~4×walk + leap.
 * All three intermediate cells must be clear at foot+head with no
 * stand-able floor below any of them — Baritone enforces the same
 * "real gap" rule.
 */
public final class Parkour4 extends Move {
    public Parkour4(int dx, int dz) { super(dx * 4, 0, dz * 4, 42); }
    @Override public boolean availableInSearch(WorldView w) { return BotConfig.allowParkour4; }
    public boolean valid(WorldView w, BlockPos from) {
        if (!BotConfig.allowParkour4) return false;
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
        for (int i = 1; i <= 3; i++) {
            BlockPos mid = from.offset(sx * i, 0, sz * i);
            if (!w.isPassable(mid) || w.isHazard(mid)) return false;
            BlockPos midHead = mid.offset(0, 1, 0);
            if (!w.isPassable(midHead) || w.isHazard(midHead)) return false;
            if (w.canStandAt(mid)) return false;
        }
        return w.isPassable(to.offset(0, 1, 0));
    }
    public String name() { return "parkour4"; }
}
