package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.game.body.BotUtil.canStandHereStatic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * The cells a body can walk to from {@code foot} without breaking or placing, with the steps to each: a step to a side
 * neighbour on the same level, one up (a jump, with head room above the start) or down to {@link #MAX_DROP} (with the
 * column it falls through open). The search covers a box and a ring round it, so a planner choosing where to stand to
 * work on the box ({@link ClearStands}, {@link BuildStands}) only ever picks a cell the walk can get to.
 */
final class StandWalk {
    /** How far round the box the search looks, in blocks. */
    static final int RING = 3;

    /** The largest side of the search region; a box or a start further off than this is left to the caller. */
    private static final int MAX_SIDE = 48;

    /** How far a walk may drop to its next cell: three blocks, the most that does no fall damage. */
    static final int MAX_DROP = 3;

    private final List<BlockPos> reached = new ArrayList<>();

    private final List<Integer> steps = new ArrayList<>();

    private int x0;

    private int y0;

    private int z0;

    private int sx;

    private int sy;

    private int sz;

    private StandWalk() {}

    /**
     * The walk from {@code foot} over the box {@code min}..{@code max} and its ring, or null when that is too large.
     */
    static StandWalk from(Level lvl, BlockPos foot, BlockPos min, BlockPos max) {
        StandWalk w = new StandWalk();
        w.x0 = Math.min(min.getX() - RING, foot.getX() - 1);
        w.y0 = Math.min(min.getY() - MAX_DROP, foot.getY() - 1);
        w.z0 = Math.min(min.getZ() - RING, foot.getZ() - 1);
        w.sx = Math.max(max.getX() + RING, foot.getX() + 1) - w.x0 + 1;
        w.sy = Math.max(max.getY() + 2, foot.getY() + 1) - w.y0 + 1;
        w.sz = Math.max(max.getZ() + RING, foot.getZ() + 1) - w.z0 + 1;
        if (w.sx > MAX_SIDE || w.sy > MAX_SIDE || w.sz > MAX_SIDE) {
            return null;
        }
        w.search(lvl, foot);
        return w;
    }

    /** The cells reached, nearest first by steps. */
    List<BlockPos> reached() {
        return reached;
    }

    /** The steps to {@code reached().get(i)}. */
    int steps(int i) {
        return steps.get(i);
    }

    private int index(BlockPos p) {
        int x = p.getX() - x0;
        int y = p.getY() - y0;
        int z = p.getZ() - z0;
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
            return -1;
        }
        return (y * sz + z) * sx + x;
    }

    private void search(Level lvl, BlockPos foot) {
        int[] seen = new int[sx * sy * sz];
        Deque<BlockPos> open = new ArrayDeque<>();
        seen[index(foot)] = 1;
        open.add(foot);
        while (!open.isEmpty()) {
            BlockPos at = open.poll();
            int d = seen[index(at)];
            reached.add(at);
            steps.add(d - 1);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = step(lvl, at, dir);
                if (next == null) {
                    continue;
                }
                int i = index(next);
                if (i >= 0 && seen[i] == 0) {
                    seen[i] = d + 1;
                    open.add(next);
                }
            }
        }
    }

    /** Where a step from {@code at} toward {@code dir} lands, or null when it cannot be taken. */
    private static BlockPos step(Level lvl, BlockPos at, Direction dir) {
        BlockPos side = at.relative(dir);
        if (canStandHereStatic(lvl, side)) {
            return side;
        }
        BlockPos up = side.above();
        if (canStandHereStatic(lvl, up) && !lvl.getBlockState(at.above(2)).blocksMotion()) {
            return up;
        }
        if (lvl.getBlockState(side).blocksMotion()
                || lvl.getBlockState(side.above()).blocksMotion()) {
            return null;
        }
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            BlockPos down = side.below(drop);
            if (canStandHereStatic(lvl, down)) {
                return down;
            }
            if (lvl.getBlockState(down).blocksMotion()) {
                return null;
            }
        }
        return null;
    }
}
