package net.magicterra.worlddriver.bot.rules.moves;

import net.magicterra.worlddriver.bot.rules.Capability;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.RulesConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.minecraft.core.BlockPos;

/**
 * 4-block cardinal leap — Baritone {@code allowParkour4} analogue. At the edge of vanilla physics: needs sprint-jump
 * with no headwind, often requires jump-boost / Speed potion to land cleanly. Gated by
 * {@link net.magicterra.worlddriver.config.RulesConfig#allowParkour4} (default false) so the A* default never emits it.
 * Cost 42 = ~4×walk + leap. All three intermediate cells must be clear at foot+head with no stand-able floor below any
 * of them — Baritone enforces the same "real gap" rule.
 */
public final class Parkour4 extends Move {
    public Parkour4(int dx, int dz) {
        super(dx * 4, 0, dz * 4, 42);
    }

    @Override
    public boolean availableInSearch(WorldView w) {
        return RulesConfig.allowParkour4;
    }

    public boolean valid(WorldView w, BlockPos from) {
        if (!RulesConfig.allowParkour4) {
            return false;
        }
        // Buoyancy TAKEOFF gate: a floating bot can't sprint-jump out of deep water (no floor to push off).
        // Mirrors StepUp/DiagUp/PillarUp; complements pathfinderForbidParkourIntoDeepWater. See BotConfig doc.
        if (RulesConfig.pathfinderForbidParkourFromFloatingWater && w.isFloatingWater(from)) {
            return false;
        }
        if (!Move.hasRunway(w, from, dx, dz)) {
            return false;
        }
        BlockPos to = apply(from);
        // The void rule covers the WHOLE leap family, not the three members it was first written
        // against. An exemption narrower than the family it must cover is how this class of bug
        // leaks back: rung 20 kept leaving the world from cells the first three gates never saw.
        // See Move.overTheVoid — a rule and not a price, and only while a bridge is affordable.
        if (RulesConfig.pathfinderForbidParkourOverTheVoid && WorldConfig.allowPlace && Move.overTheVoid(w, from, to)) {
            return false;
        }
        if (!w.canStandAt(to)) {
            return false;
        }
        // Buoyancy: no parkour LANDING in submerged water — the bot sinks/stalls there
        // instead of leaping (mirrors Fall/StepDown's submerged gate; surface/solid OK).
        if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) {
            return false;
        }
        // ...and (opt-in) refuse a leap onto a DEEP pocket SURFACE (≥2 water below, head
        // air): buoyant bot floats there and can't climb out (#47 dead-end-pocket dive).
        if (RulesConfig.pathfinderForbidParkourIntoDeepWater && w.isDeepWaterSurfaceLanding(to)) {
            return false;
        }
        if (!w.isPassable(from.offset(0, 2, 0)) || w.isHazard(from.offset(0, 2, 0))) {
            return false;
        }
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);
        for (int i = 1; i <= 3; i++) {
            BlockPos mid = from.offset(sx * i, 0, sz * i);
            if (!w.isPassable(mid) || w.isHazard(mid)) {
                return false;
            }
            BlockPos midHead = mid.offset(0, 1, 0);
            if (!w.isPassable(midHead) || w.isHazard(midHead)) {
                return false;
            }
            if (w.canStandAt(mid)) {
                return false;
            }
        }
        return w.isPassable(to.offset(0, 1, 0));
    }

    @Override
    public Capability requiredCapability() {
        return Capability.PARKOUR;
    }

    public String name() {
        return "parkour4";
    }
}
