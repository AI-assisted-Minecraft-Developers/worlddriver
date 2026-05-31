package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

import java.util.List;

/**
 * Parkour-place — Baritone's {@code allowParkourPlace}: a 2-block cardinal
 * sprint-jump (same Y) onto a landing block placed <em>mid-air</em> during
 * the leap, when the landing cell has no floor of its own. Distinct from:
 * <ul>
 *   <li>{@link Parkour2} — that needs a solid landing already there;</li>
 *   <li>{@link BridgePlace} — the slow sneak-walk bridge that chains over open
 *       void one block at a time off its own placements.</li>
 * </ul>
 * The win is speed: where a gap to nearby structure would otherwise take two
 * sneak-BridgePlaces (cost ~60), one parkour-place crosses it in a single leap
 * (cost 42). It only fires when the landing floor has a <em>pre-existing</em>
 * solid neighbour to place against ({@link Move#hasPlaceSupport}) — you can't
 * place a floating block over open void, and Baritone gates on the same
 * {@code canPlaceAgainst}. The placement is an actuator concern keyed off the
 * {@code parkourPlace*} move name (the Walker leaps with run-up preserved and
 * synthetic-hits the support mid-air), so {@code toPlace} carries the landing
 * floor for the place loop's bookkeeping while the leap motion stays a parkour.
 */
public final class ParkourPlace extends Move {
    public ParkourPlace(int dx, int dz) { super(dx * 2, 0, dz * 2, 22 + (int) Move.PLACE_COST); }
    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }
    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!w.canParkourPlace()) return null;
        if (!Move.hasRunway(w, from)) return null;                  // need solid ground to push off
        if (!w.isPassable(from.offset(0, 2, 0))) return null;       // jump clearance over launch head
        BlockPos to = apply(from);
        // Destination foot + head must be open (we land + stand there).
        if (!w.isPassable(to) || w.isHazard(to)) return null;
        if (!w.isPassable(to.offset(0, 1, 0)) || w.isHazard(to.offset(0, 1, 0))) return null;
        // Landing floor must be empty — a solid floor is a plain Parkour2.
        BlockPos floor = to.offset(0, -1, 0);
        if (w.isSolid(floor) || !w.isPassable(floor) || w.isHazard(floor)) return null;
        // Mid cell: a real gap at foot+head with no stand-able floor, or a
        // cheaper Walk/StepDown chain would reach it (matches Parkour2).
        BlockPos midFoot = from.offset(dx / 2, 0, dz / 2);
        BlockPos midHead = midFoot.offset(0, 1, 0);
        if (!w.isPassable(midFoot) || w.isHazard(midFoot)) return null;
        if (!w.isPassable(midHead) || w.isHazard(midHead)) return null;
        if (w.canStandAt(midFoot)) return null;                     // not a real gap → cheaper moves win
        // Must be physically placeable: a pre-existing solid neighbour of the
        // landing floor to click against during the leap.
        if (!Move.hasPlaceSupport(w, floor)) return null;
        return new Edge(to, cost, List.of(), List.of(floor), name());
    }
    public String name() { return "parkourPlace2"; }
}
