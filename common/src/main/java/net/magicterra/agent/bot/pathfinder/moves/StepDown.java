package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Walk off a 1-block ledge to a position 1 block lower. */
public final class StepDown extends Move {
    public StepDown(int dx, int dz) { super(dx, -1, dz, 10); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        // Foot is solid 1 below; we step into the air block above.
        if (!w.canStandAt(to)) return false;
        BlockPos passthrough = from.offset(dx, 0, dz);
        return w.isPassable(passthrough) && !w.isHazard(passthrough)
            && w.isPassable(passthrough.offset(0, 1, 0));
    }
    public String name() { return "stepDown"; }
}
