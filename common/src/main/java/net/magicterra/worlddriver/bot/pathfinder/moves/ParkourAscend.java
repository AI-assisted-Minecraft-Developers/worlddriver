package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.magicterra.worlddriver.bot.BotConfig;

/**
 * Parkour ASCEND -- Baritone {@code MovementParkour} with a +1 landing: a
 * sprint-jump across a 2-3 block cardinal gap that lands one block HIGHER
 * than the launch. (A 1-block ascend is just a {@link StepUp}; ascends
 * taller than 3 are not reachable by vanilla sprint-jump physics.) The
 * rising player sweeps a taller corridor than a flat parkour, so each gap
 * column must be clear 3 tall (foot, head, head+1) -- a ceiling at y+2
 * clips the jump apex. There must also be no stand-able floor at launch
 * level in the gap, or a cheaper Walk/StepUp chain would win. Distance-2 is
 * a reliable vanilla leap; distance-3 (clearing 3 while rising 1) is at the
 * physics edge, so it is gated behind
 * {@link net.magicterra.worlddriver.bot.BotConfig#allowParkour4} like the other
 * marginal long leaps. Cost = flat-parkour + 5 (jump-up overhead, as in
 * {@link StepUp}). The {@code parkourAscend} name starts with "parkour" so
 * the Walker's existing parkour actuator (jump+sprint, yaw-snapped, aimed
 * at the destination) drives it unchanged.
 */
public final class ParkourAscend extends Move {
    private final int dist;
    public ParkourAscend(int dx, int dz, int dist) {
        super(dx * dist, 1, dz * dist, (dist == 2 ? 22 : 32) + 5);
        this.dist = dist;
    }
    @Override public boolean availableInSearch(WorldView w) { return dist < 3 || BotConfig.allowParkour4; }
    public boolean valid(WorldView w, BlockPos from) {
        if (dist >= 3 && !BotConfig.allowParkour4) return false;
        // Buoyancy TAKEOFF gate: a floating bot can't sprint-jump out of deep water (no floor to push off) —
        // the dominant live #47 J2 stall (parkourAscend2-from-water, totStuck 409). Mirrors StepUp/DiagUp/
        // PillarUp; complements pathfinderForbidParkourIntoDeepWater (landing side). See BotConfig doc.
        if (BotConfig.pathfinderForbidParkourFromFloatingWater && w.isFloatingWater(from)) return false;
        if (!Move.hasRunway(w, from)) return false;
        BlockPos to = apply(from);                 // (sx*dist, +1, sz*dist)
        // The void rule covers the WHOLE leap family, not the three members it was first written
        // against. An exemption narrower than the family it must cover is how this class of bug
        // leaks back: rung 20 kept leaving the world from cells the first three gates never saw.
        // See Move.overTheVoid — a rule and not a price, and only while a bridge is affordable.
        if (BotConfig.pathfinderForbidParkourOverTheVoid && BotConfig.allowPlace
                && Move.overTheVoid(w, from, to)) return false;
        if (!w.canStandAt(to)) return false;
        // Buoyancy: no parkour LANDING in submerged water — the bot sinks/stalls there
        // instead of leaping (mirrors Fall/StepDown's submerged gate; surface/solid OK).
        if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) return false;
        // ...and (opt-in) refuse a leap onto a DEEP pocket SURFACE (≥2 water below, head
        // air): buoyant bot floats there and can't climb out (#47 dead-end-pocket dive).
        // A rising leap rarely lands on a pocket surface, but mirror the family for parity.
        if (BotConfig.pathfinderForbidParkourIntoDeepWater && w.isDeepWaterSurfaceLanding(to)) return false;
        // Launch jump clearance (head+1 at the lip).
        if (!w.isPassable(from.offset(0, 2, 0)) || w.isHazard(from.offset(0, 2, 0))) return false;
        int sx = Integer.signum(dx), sz = Integer.signum(dz);
        // APPROACH-RUNWAY gate (pathfinderParkourAscendNeedRunway): a +1-up parkour needs sprint MOMENTUM at
        // launch, but a bot climbing OUT of a bank reaches the crest via a stepUp/diagUp that decelerates it
        // to ~0 b/s, then fires the leap from a standstill → it lands short and falls back down the staircase
        // (live #47 J2 -682,69,310: no runway behind the lip, 49/82/14 fall-back ticks, totStuck 579). Require
        // the cell directly BEHIND the launch (opposite the leap, same Y) to be standable — a flat run-up; a
        // thin-lip crest (staircase drops away behind) is forbidden so A* substitutes a makeable stepUp climb.
        if (BotConfig.pathfinderParkourAscendNeedRunway && !w.canStandAt(from.offset(-sx, 0, -sz))) return false;
        for (int i = 1; i < dist; i++) {
            // 3-tall clear corridor: the player rises from y to y+1 across the
            // gap, so foot/head/head+1 must all be open.
            for (int dyOff = 0; dyOff <= 2; dyOff++) {
                BlockPos c = from.offset(sx * i, dyOff, sz * i);
                if (!w.isPassable(c) || w.isHazard(c)) return false;
            }
            // Real gap at launch level (a mid floor -> cheaper Walk/StepUp).
            if (w.canStandAt(from.offset(sx * i, 0, sz * i))) return false;
            // Water-bottomed gap DROP-ZONE (pathfinderForbidParkourOverWaterGap): an UNDERSHOT rising leap
            // drops ~1 below the launch into the gap column; if that drop-zone is DEEP water (>=2) the buoyant
            // player cannot climb out and bob-stalls against the far wall (live -665,64: parkourAscend2 from a
            // y63 launch over an air gap with water below falls to y62 water, hCol, ~3.7 s repath-bounce — a
            // route-variance stall the executor layer structurally can't A/B-recover). A buoyant bot should
            // SWIM a water crossing, not sprint-jump it, so forbid the leap and let A* route around / swim.
            // Mirrors the forbid-parkour-from/into-water family (takeoff / landing); this guards the GAP DROP.
            if (BotConfig.pathfinderForbidParkourOverWaterGap) {
                BlockPos drop = from.offset(sx * i, -1, sz * i);
                if (w.isWater(drop) && w.isWater(drop.offset(0, -1, 0))) return false;
            }
        }
        return w.isPassable(to.offset(0, 1, 0));
    }
    @Override public Capability requiredCapability() { return Capability.PARKOUR; }
    public String name() { return "parkourAscend" + dist; }
}
