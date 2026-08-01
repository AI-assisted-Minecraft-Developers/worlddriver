package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Walk one block in a cardinal direction (same Y). */
public final class Walk extends Move {
    public Walk(int dx, int dz) { super(dx, 0, dz, 10); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        return w.canStandAt(to);
    }
    public String name() { return "walk"; }
}
