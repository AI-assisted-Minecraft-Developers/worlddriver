package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.BotConfig;
import net.minecraft.core.BlockPos;

/**
 * 2-block 45° diagonal leap — bridges the inside corner of an L-gap.
 * The intermediate cell is the shared diagonal midpoint; both adjacent
 * cardinal sides must also be passable at foot+head (player physically
 * cuts across the corner). No stand-able floor at the mid cell, or a
 * Walk+Diagonal pair would be cheaper. Cost ≈ 33 ≈ sqrt(8)*10 + jump.
 */
public final class Parkour2Diagonal extends Move {
    /** Extra cost when the corner swept by the leap has water under it: a short diagonal leap lands
     *  in the gap often enough (4 of 17 on the R1 route, at 1567,-109 and 1548,-141) that the swim
     *  back out, ~3 s, belongs in its price. */
    static final double WATER_GAP_TAX = 30;

    public Parkour2Diagonal(int dx, int dz) { super(dx * 2, 0, dz * 2, 33); }

    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!valid(w, from)) return null;
        int sx = Integer.signum(dx), sz = Integer.signum(dz);
        boolean wet = wetUnder(w, from.offset(sx, 0, sz)) || wetUnder(w, from.offset(sx, 0, 0))
                || wetUnder(w, from.offset(0, 0, sz));
        return new Edge(apply(from), cost + (wet ? WATER_GAP_TAX : 0), java.util.List.of(), java.util.List.of(), name());
    }

    /** Whether the first non-air cell under the gap column {@code c} is water — at 1549,-140 the
     *  surface sits two below the leap's feet with air between. */
    private static boolean wetUnder(WorldView w, BlockPos c) {
        for (int d = 1; d <= 3; d++) {
            BlockPos b = c.below(d);
            if (w.isWater(b)) return true;
            if (!w.isPassable(b)) return false;
        }
        return false;
    }
    public boolean valid(WorldView w, BlockPos from) {
        // Buoyancy TAKEOFF gate: a floating bot can't sprint-jump out of deep water (no floor to push off).
        // Mirrors StepUp/DiagUp/PillarUp; complements pathfinderForbidParkourIntoDeepWater. See BotConfig doc.
        if (BotConfig.pathfinderForbidParkourFromFloatingWater && w.isFloatingWater(from)) return false;
        if (!Move.hasRunway(w, from)) return false;
        BlockPos to = apply(from);
        // Never leap a bottomless gap while a bridge is affordable: see Move.overTheVoid for why
        // this is a rule and not a price. When placement is off there is nothing better to do, so
        // the leap stays available rather than leaving the bot with no move at all.
        if (BotConfig.pathfinderForbidParkourOverTheVoid && BotConfig.allowPlace
                && Move.overTheVoid(w, from, to)) return false;
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
        // all be clear at foot+head — the player sweeps that whole corner.
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
    @Override public Capability requiredCapability() { return Capability.PARKOUR; }
    public String name() { return "parkour2d"; }
}
