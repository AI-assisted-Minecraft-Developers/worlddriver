package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Climb up a ladder/vine. Current and above must be climbable (or above can be stand). */
public final class ClimbUp extends Move {
    public ClimbUp() { super(0, 1, 0, 20); }
    public boolean valid(WorldView w, BlockPos from) {
        if (!w.isClimbable(from)) return false;
        BlockPos to = apply(from);
        return w.isPassable(to.offset(0, 1, 0)) && (w.isClimbable(to) || w.canStandAt(to));
    }
    public String name() { return "climbUp"; }
}
