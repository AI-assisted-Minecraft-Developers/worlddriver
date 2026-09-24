package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Out of the water by placing a foothold: from the surface water cell to the air cell above it.
 * The edge the walker's water climb-out takeover really performs — a ground jump and a fill in
 * one-deep water, a side rung on a neighbouring floor, or a press against a bank while the fill
 * cell is placed under the risen feet — and legal only in those three shapes, with a block in
 * hand. Replaces the legacy {@code swimUp} "into the air" edge, which A* could plan over any
 * open column and which the bot could only bob against. Same price as that edge so the two
 * models differ in legality, not in taste; the water climb-out tax still applies on top.
 *
 * <p>No {@code toPlace}: the takeover chooses its own fill cell (the latched column's top water
 * cell or a side rung), so a pending-place hold on a fixed cell would wait for a block that may
 * legitimately land elsewhere.
 */
public final class SurfaceClimbOut extends Move {
    public SurfaceClimbOut() { super(0, 1, 0, 25); }

    public boolean valid(WorldView w, BlockPos from) {
        if (!w.surfaceWaterNodes()) return false;
        if (!w.isWater(from) || w.isWater(from.above())) return false;      // surface cell only
        if (!w.canPlace()) return false;
        BlockPos to = apply(from);
        if (!w.isPassable(to) || w.isHazard(to)) return false;
        BlockPos head = to.above();
        if (!w.isPassable(head) || w.isHazard(head)) return false;
        return w.canStandOn(from.below()) || bankBeside(w, to) || sideFoothold(w, from);
    }

    /** A solid beside the risen feet: the player presses into it and vanilla's swim boost lifts it
     *  clear of the fill cell. */
    private static boolean bankBeside(WorldView w, BlockPos to) {
        return w.isSolid(to.east()) || w.isSolid(to.west()) || w.isSolid(to.north()) || w.isSolid(to.south());
    }

    /** An open neighbour at foot level with a floor under it and head room above: the rung the
     *  takeover places beside the bot instead of under it. */
    private static boolean sideFoothold(WorldView w, BlockPos from) {
        for (BlockPos n : new BlockPos[] { from.east(), from.west(), from.north(), from.south() }) {
            if (w.isSolid(n) || w.isHazard(n)) continue;
            if (!w.isSolid(n.below())) continue;
            if (w.isPassable(n.above()) && w.isPassable(n.above(2))) return true;
        }
        return false;
    }

    public String name() { return "climbOutPlace"; }
}
