package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Walk off a 1-block ledge to a position 1 block lower. */
public final class StepDown extends Move {
    public StepDown(int dx, int dz) { super(dx, -1, dz, 10); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        // A buoyant bot can't follow a descend INTO submerged water (water at the
        // destination foot AND head): it floats over the below-node and oscillates
        // swimUp↔descend instead of sinking. Mirror Fall/FallIntoWater's submerged-
        // landing gate so A* never routes the unexecutable dive (live z2744 slot
        // canyon: swimUp 918 / diagDown 200, totStuck 1154, no net progress). A
        // descend to the water SURFACE (head air) stays valid — the bot floats there.
        if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) return false;
        // Foot is solid 1 below; we step into the air block above.
        if (!w.canStandAt(to)) return false;
        BlockPos passthrough = from.offset(dx, 0, dz);
        return w.isPassable(passthrough) && !w.isHazard(passthrough)
            && w.isPassable(passthrough.offset(0, 1, 0));
    }
    public String name() { return "stepDown"; }
}
