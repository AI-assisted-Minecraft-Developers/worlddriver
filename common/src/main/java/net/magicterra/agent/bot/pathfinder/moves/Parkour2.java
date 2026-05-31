package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

/**
 * Baritone-style 2-block parkour leap (same Y, one cardinal axis). The
 * middle cell at foot+head must be air (passable, non-hazard) and the
 * cell below it must NOT be a valid stand position — otherwise A* should
 * just use two cheaper Walks and we'd be over-counting cost. Launch head
 * also needs clearance (foot.y+2) so the jump apex doesn't clip ceiling.
 * Cost ≈ 22 (~2× walk + jump overhead) so plain walking always wins
 * when both are valid.
 */
public final class Parkour2 extends Move {
    public Parkour2(int dx, int dz) { super(dx * 2, 0, dz * 2, 22); }
    public boolean valid(WorldView w, BlockPos from) {
        if (!Move.hasRunway(w, from)) return false;
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        // Launch clearance — need air at head+1 to jump.
        if (!w.isPassable(from.offset(0, 2, 0))) return false;
        // Middle column: foot + head must be passable (or water — Baritone
        // allows leaping over water/lava as long as foot column is clear).
        BlockPos midFoot = from.offset(dx / 2, 0, dz / 2);
        BlockPos midHead = midFoot.offset(0, 1, 0);
        if (!w.isPassable(midFoot) || w.isHazard(midFoot)) return false;
        if (!w.isPassable(midHead) || w.isHazard(midHead)) return false;
        // Forbid emitting if a simple Walk could reach mid — A* should
        // prefer the cheaper path. Equivalently: gap must be real (no
        // stand under midFoot).
        if (w.canStandAt(midFoot)) return false;
        // Destination head clearance.
        return w.isPassable(to.offset(0, 1, 0));
    }
    public String name() { return "parkour2"; }
}
