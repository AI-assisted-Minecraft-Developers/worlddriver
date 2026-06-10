package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
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
        // Verify both foot AND head clearance through the falling column. Foot
        // checks at dyOff, head one block above (dyOff+1) — an overhang above
        // the launch lip or any intermediate level would wedge the player even
        // when the foot column is clear.
        for (int dyOff = 0; dyOff > -drop; dyOff--) {
            BlockPos foot = from.offset(dx, dyOff, dz);
            if (!w.isPassable(foot) || w.isHazard(foot)) return false;
            BlockPos head = from.offset(dx, dyOff + 1, dz);
            if (!w.isPassable(head) || w.isHazard(head)) return false;
        }
        // Head clearance at the launch position.
        return w.isPassable(from.offset(0, 1, 0));
    }
    public String name() { return "fall" + drop; }
}
