package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Step off a ledge and fall {@code drop} blocks. Each block adds a tick of
 * fall time + a damage check. Drops above {@link BotConfig#pathfinderMaxDryFall}
 * (default 3, the no-damage cap) are gated off so the search keeps Baritone's
 * conservative dry-fall behaviour by default. Raising the knob lets the planner
 * take a small-damage drop (4-5 blocks ≈ 1.5-2 hearts) instead of building a
 * dirt "天梯" staircase down a steep jungle slope — the smooth-descent lever.
 * Landing spot must be a safe stand position; the air column between must be empty.
 */
public final class Fall extends Move {
    private final int drop;
    public Fall(int dx, int dz, int drop) {
        super(dx, -drop, dz, 10 + 5 * drop);
        this.drop = drop;
    }
    public boolean valid(WorldView w, BlockPos from) {
        // Live config gate (mirrors WaterBucketFall/FallIntoWater): Fall(4)/Fall(5)
        // are always in the catalog but inert unless the dry-fall cap is raised, so
        // the default (3) preserves the no-fall-damage routing exactly.
        if (drop > BotConfig.pathfinderMaxDryFall) return false;
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        // Buoyancy: a fall into SUBMERGED water (more water directly above the
        // landing) doesn't rest there — autoSwim floats the body up to the
        // surface cell. canStandAt accepts ANY water cell as a floor, so without
        // this A* routes the floating bot DOWN to a riverbed node it can never
        // reach and wedges (live 2026-06-15 deep-water crossing: fall3 to a y59
        // bed cell under a 4-deep column, bot floating at y62 pinned 1000+ ticks
        // on anti-stuck bursts). Only a fall that lands ON the water SURFACE
        // (air above) — or on solid ground (handled by canStandAt's floor) — is
        // a real resting node the buoyant executor can actually hold.
        if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) return false;
        // Verify both foot AND head clearance through the falling column. Foot
        // checks at dyOff, head one block above (dyOff+1) — an overhang above
        // the launch lip or any intermediate level would wedge the player even
        // when the foot column is clear.
        return clearFallColumn(w, from, dx, dz, drop);
    }
    public String name() { return "fall" + drop; }
}
