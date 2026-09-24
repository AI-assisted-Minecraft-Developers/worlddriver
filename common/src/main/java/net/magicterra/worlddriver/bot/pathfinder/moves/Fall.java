package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Step off a ledge and fall {@code drop} blocks. Each block adds a tick of
 * fall time + a damage check. Drops above {@link BotConfig#pathfinderMaxDryFall}
 * are gated off.
 *
 * <p><b>That cap ships at 4, not at the no-damage 3.</b> Baritone's conservative
 * dry-fall behaviour is what the knob's LOWEST legal value (3) buys; the shipping
 * default deliberately spends damage, so {@code Fall(4)} is LIVE on every default
 * run and the planner takes a real 1 HP hit rather than build a dirt
 * staircase down a steep jungle slope — the smooth-descent lever. Only
 * {@code Fall(5)} is inert by default. Vanilla charges {@code ceil(distance - 3)}
 * HP, so fall4 = 1 HP = 0.5♥ and fall5 = 2 HP = 1♥.
 *
 * <p>Landing spot must be a safe stand position; the air column between must be empty.
 */
public final class Fall extends Move {
    private final int drop;
    public Fall(int dx, int dz, int drop) {
        super(dx, -drop, dz, 10 + 5 * drop);
        this.drop = drop;
    }
    public boolean valid(WorldView w, BlockPos from) {
        // Live config gate (mirrors WaterBucketFall/FallIntoWater). At the shipping
        // cap of 4 this admits fall2..fall4 and rejects only Fall(5) — it is NOT the
        // no-fall-damage gate it was written as: fall4 costs 1 HP every time it
        // fires. Lower the knob to 3 to get damage-free routing back.
        if (drop > BotConfig.pathfinderMaxDryFall) return false;
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        // Buoyancy: a fall into SUBMERGED water (more water directly above the
        // landing) doesn't rest there — autoSwim floats the bot up to the
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
