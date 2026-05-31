package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

/** Swim straight down in water. */
public final class SwimDown extends Move {
    public SwimDown() { super(0, -1, 0, 25); }
    public boolean valid(WorldView w, BlockPos from) {
        return w.isWater(from) && w.isWater(apply(from));
    }
    public String name() { return "swimDown"; }
}
