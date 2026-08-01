package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.magicterra.worlddriver.bot.BotConfig;

/**
 * Parkour DESCEND -- Baritone {@code MovementParkour} with a lower landing: a
 * sprint-jump across a 2-3 block cardinal gap that lands {@code drop} blocks
 * BELOW the launch. (A distance-1 drop is a {@link Fall}/{@link StepDown}; a
 * same-level leap is {@link Parkour2}/{@link Parkour3}.) Drops stay <=3 so
 * there is never fall damage, and the extra airtime of falling while moving
 * forward makes this physically EASIER than a flat leap of the same distance
 * -- yet A* had no move for it, so a gap with a lower far side forced a long
 * detour. Each intermediate gap column is required clear over a generous box
 * (apex y+1 down to the landing level) so neither the jump apex nor the
 * descending arc clips, with no stand-able floor at launch level (else a
 * cheaper Walk/StepDown chain wins); the column above the landing must be
 * clear for the body to fall through. Distance-2 is always in the catalog;
 * distance-3 shares the {@link net.magicterra.worlddriver.bot.BotConfig#allowParkour4}
 * gate like the other long leaps. Cost = flat-parkour + 4/drop (falling is
 * cheap, as in {@link Fall}). The {@code parkourDescend} name starts with
 * "parkour" so the Walker's existing parkour actuator drives it unchanged.
 */
public final class ParkourDescend extends Move {
    private final int dist, drop;
    public ParkourDescend(int dx, int dz, int dist, int drop) {
        super(dx * dist, -drop, dz * dist, (dist == 2 ? 22 : 32) + 4 * drop);
        this.dist = dist;
        this.drop = drop;
    }
    @Override public boolean availableInSearch(WorldView w) {
        return !(dist >= 3 || drop >= 2) || BotConfig.allowParkour4;
    }
    public boolean valid(WorldView w, BlockPos from) {
        // Only the shallow drop-1 descend lands reliably on a 1-wide block:
        // the extra airtime of a deeper drop (or a longer dist-3 gap) carries
        // the bot horizontally past the narrow pad before it touches down (the
        // landing-brake can't recover a body still well above the pad), so those
        // ride the allowParkour4 "marginal physics" tier -- same contract as the
        // dist-3 ascend, which also needs a boost to land.
        if ((dist >= 3 || drop >= 2) && !BotConfig.allowParkour4) return false;
        // Buoyancy TAKEOFF gate: a floating bot can't sprint-jump out of deep water (no floor to push off).
        // Mirrors StepUp/DiagUp/PillarUp; complements pathfinderForbidParkourIntoDeepWater. See BotConfig doc.
        if (BotConfig.pathfinderForbidParkourFromFloatingWater && w.isFloatingWater(from)) return false;
        if (!Move.hasRunway(w, from)) return false;
        BlockPos to = apply(from);                 // (sx*dist, -drop, sz*dist)
        if (!w.canStandAt(to)) return false;
        // Buoyancy: no parkour LANDING in submerged water — the bot sinks/stalls there
        // instead of leaping (mirrors Fall/StepDown's submerged gate; surface/solid OK).
        if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) return false;
        // Buoyancy (#47 -870): also refuse a leap onto the SURFACE of a DEEP pocket
        // (≥2 of water below the feet, head air). The submerged gate above only bars a
        // head-underwater landing; a leap onto a deep-pocket surface passes it, but the
        // buoyant bot floats there and can't climb back out (grounded climb-outs gate
        // off floating water → ~25× underwater bank-dig / long shore-swim, the dive a
        // probe shows A* immediately reverses). Opt-in; a shallow 1-deep splash (floor
        // below) and a leap onto a dry far bank are unaffected.
        if (BotConfig.pathfinderForbidParkourIntoDeepWater && w.isDeepWaterSurfaceLanding(to)) return false;
        // Launch jump clearance (head+1 at the lip).
        if (!w.isPassable(from.offset(0, 2, 0)) || w.isHazard(from.offset(0, 2, 0))) return false;
        int sx = Integer.signum(dx), sz = Integer.signum(dz);
        for (int i = 1; i < dist; i++) {
            // Clear box from apex (+1) down to landing level across the gap.
            for (int dyOff = 1; dyOff >= -drop; dyOff--) {
                BlockPos c = from.offset(sx * i, dyOff, sz * i);
                if (!w.isPassable(c) || w.isHazard(c)) return false;
            }
            // Real gap at launch level (a mid floor -> cheaper Walk/StepDown).
            if (w.canStandAt(from.offset(sx * i, 0, sz * i))) return false;
        }
        // Column above the landing: the body falls through it from launch
        // level down to the landing head (canStandAt already cleared foot+head).
        for (int dyOff = 0; dyOff > -drop + 1; dyOff--) {
            BlockPos c = from.offset(sx * dist, dyOff, sz * dist);
            if (!w.isPassable(c) || w.isHazard(c)) return false;
        }
        return true;
    }
    @Override public Capability requiredCapability() { return Capability.PARKOUR; }
    public String name() { return "parkourDescend" + dist + "d" + drop; }
}
