package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

/**
 * Step off a ledge and fall {@code drop} blocks. Each block adds a tick of
 * fall time + a damage check. Drops > 3 forbidden (fall damage). Landing
 * spot must be a safe stand position; the air column between must be empty.
 */
public final class Fall extends Move {
    private final int drop;
    public Fall(int dx, int dz, int drop) {
        super(dx, -drop, dz, 10 + 5 * drop);
        this.drop = drop;
    }
    public boolean valid(WorldView w, BlockPos from) {
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
