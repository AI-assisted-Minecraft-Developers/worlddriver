package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

/** Walk one block in a cardinal direction (same Y). */
public final class Walk extends Move {
    public Walk(int dx, int dz) { super(dx, 0, dz, 10); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        return w.canStandAt(to);
    }
    public String name() { return "walk"; }
}
