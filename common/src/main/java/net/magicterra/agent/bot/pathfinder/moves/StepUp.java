package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

/** Walk + jump up one block. Foot+head must clear at both src head and dst head. */
public final class StepUp extends Move {
    public StepUp(int dx, int dz) { super(dx, 1, dz, 15); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        // Need air above current head (jump clearance, foot.y + 2).
        return w.isPassable(from.offset(0, 2, 0));
    }
    public String name() { return "stepUp"; }
}
