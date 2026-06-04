package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Diagonal walk: both adjacent cardinals must be passable too, or the
 * player physically wedges on the corner. Cost 14 ≈ sqrt(2) * 10.
 */
public final class Diagonal extends Move {
    public Diagonal(int dx, int dz) { super(dx, 0, dz, 14); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        BlockPos sideA = from.offset(dx, 0, 0);
        BlockPos sideB = from.offset(0, 0, dz);
        // BOTH corner columns must be clear. The bot walks a diagonal by aiming
        // straight at the destination centre (no active corner-navigation), so a
        // single solid corner snags its hitbox and it wedges in place forever
        // (observed: stuck bobbing against a sandstone corner, cur2 never closing).
        // Requiring both sides clear matches this class's doc intent and forces A*
        // to route a blocked corner as two cardinal walks through the open cell —
        // which the Walker executes cleanly. Costs at most one extra step per
        // corner; in exchange no diagonal ever wedges.
        return clearColumn(w, sideA) && clearColumn(w, sideB);
    }
    private static boolean clearColumn(WorldView w, BlockPos p) {
        return w.isPassable(p) && w.isPassable(p.offset(0, 1, 0))
            && !w.isHazard(p) && !w.isHazard(p.offset(0, 1, 0));
    }
    public String name() { return "diag"; }
}
