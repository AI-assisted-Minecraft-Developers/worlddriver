package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

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

    /** Buoyant-wall bob fix (mini-project): a diagonal sprint-jump UP whose miss
     *  would drop the bot into WATER lands imprecisely on the placed staircase
     *  and bobs the bot back (the +5 buoyant-wall thrash). Penalise it heavily
     *  when water sits below within a survivable-fall depth, so A* climbs OUT of
     *  water with a stable vertical {@link PillarUp} (in-column — can't slide off
     *  the side) instead of a diagonal staircase over the water. Dry diagonals
     *  keep the base cost. */
    @Override
    public Move.Edge eval(WorldView w, BlockPos from) {
        if (!valid(w, from)) return null;
        double c = cost;
        if (waterBelow(w, from, 6)) c += 200;
        return new Move.Edge(apply(from), c, java.util.List.of(), java.util.List.of(), name());
    }

    private static boolean waterBelow(WorldView w, BlockPos from, int depth) {
        // Scan THROUGH solids: a placed diagonal staircase has a solid support
        // directly below each rung, but the water it bridges over sits a few
        // cells further down — stopping at the first solid would read the
        // over-water staircase as "dry" and never redirect it.
        for (int dy = 1; dy <= depth; dy++)
            if (w.isWater(from.offset(0, -dy, 0))) return true;
        return false;
    }

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
