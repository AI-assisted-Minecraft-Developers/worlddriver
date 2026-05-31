package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

/**
 * Diagonal step UP one block (foot rises to {@code from + (dx, 1, dz)}) —
 * the diagonal analogue of {@link StepUp}, Baritone's ascending
 * {@code MovementDiagonal}. Executed by the Walker exactly like a StepUp
 * (it jumps because the waypoint is higher, and aims straight at it), so no
 * actuator change is needed. Cost 19 ≈ diagonal (14) + jump premium (5), so
 * a flat Walk+StepUp pair (10+15=25) is dearer and A* prefers the cut.
 *
 * Clearance is stricter than a flat {@link Diagonal}: because the body
 * <em>rises</em> through the corner it can clip either side, so BOTH cardinal
 * side columns must be clear at the destination-foot band (and their heads).
 */
public final class DiagonalAscend extends Move {
    public DiagonalAscend(int dx, int dz) { super(dx, 1, dz, 19); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        // Jump clearance over the launch head (foot.y + 2).
        if (!w.isPassable(from.offset(0, 2, 0)) || w.isHazard(from.offset(0, 2, 0))) return false;
        // Both corners open at the destination-foot level (y+1) and its head
        // (y+2) — a rising body sweeps the whole corner, so one-side is not
        // enough here.
        BlockPos sideA = from.offset(dx, 1, 0);
        BlockPos sideB = from.offset(0, 1, dz);
        return clearColumn(w, sideA) && clearColumn(w, sideB);
    }
    private static boolean clearColumn(WorldView w, BlockPos p) {
        return w.isPassable(p) && w.isPassable(p.offset(0, 1, 0))
            && !w.isHazard(p) && !w.isHazard(p.offset(0, 1, 0));
    }
    public String name() { return "diagUp"; }
}
