package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

/**
 * Diagonal step DOWN one block (foot drops to {@code from + (dx, -1, dz)}) —
 * the diagonal analogue of {@link StepDown}. The Walker walks off the corner
 * (no jump, since the waypoint is lower) and aims at the waypoint because its
 * Y differs, so no actuator change is needed. Cost 14 (same as a flat
 * diagonal — the one-block drop is essentially free), cheaper than the
 * StepDown+Walk pair (10+10) so A* prefers cutting the descending corner.
 *
 * Both cardinal side columns must be clear at the launch band (foot+head) so
 * the body slides off the corner without wedging on the way down.
 */
public final class DiagonalDescend extends Move {
    public DiagonalDescend(int dx, int dz) { super(dx, -1, dz, 14); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        // Launch head clearance.
        if (!w.isPassable(from.offset(0, 1, 0)) || w.isHazard(from.offset(0, 1, 0))) return false;
        // Both corners open at the launch foot+head band.
        BlockPos sideA = from.offset(dx, 0, 0);
        BlockPos sideB = from.offset(0, 0, dz);
        return clearColumn(w, sideA) && clearColumn(w, sideB);
    }
    private static boolean clearColumn(WorldView w, BlockPos p) {
        return w.isPassable(p) && w.isPassable(p.offset(0, 1, 0))
            && !w.isHazard(p) && !w.isHazard(p.offset(0, 1, 0));
    }
    public String name() { return "diagDown"; }
}
