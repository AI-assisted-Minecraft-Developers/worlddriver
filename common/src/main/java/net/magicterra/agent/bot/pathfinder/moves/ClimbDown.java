package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

/** Climb down a ladder/vine. */
public final class ClimbDown extends Move {
    public ClimbDown() { super(0, -1, 0, 15); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        return (w.isClimbable(from) || w.isClimbable(to)) && (w.isClimbable(to) || w.canStandAt(to));
    }
    public String name() { return "climbDown"; }
}
