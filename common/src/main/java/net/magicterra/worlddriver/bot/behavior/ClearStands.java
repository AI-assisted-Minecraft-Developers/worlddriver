package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.game.body.BotUtil.canStandHereStatic;
import static net.magicterra.worlddriver.game.body.BotUtil.standingEye;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.BlockSight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Where a clear stands to work, and which cell it breaks next from where it stands.
 *
 * <p>A person clearing a box does not walk to each block: they stand where much of the box is in reach, break
 * everything they can see from there, nearest first so what was behind comes into view, and only then step on. A 5 × 3
 * × 5 box is in reach of its own centre whole, and four of its five rows are in reach of the floor in front of it. So
 * the choice is of a stand, and the cell order falls out of what the stand sees.
 *
 * <p>Stands are cells the bot can walk to now without breaking or placing anything, found by a search over the box and
 * a ring around it; standing on a block of the box is allowed, which is how a person gets at the top of a box taller
 * than their reach. The world is read as it is on every call, so a cell that refills (gravel falling into a cleared
 * cell) is cleared again.
 */
final class ClearStands {
    /**
     * Reach used to plan a stand, from the centre of the cell. Vanilla's block reach is 4.5 from the eye to the point
     * hit; the bot arrives near the centre of a stand, not on it, and the half block of margin keeps a planned cell in
     * reach of where it actually stops.
     */
    static final double PLAN_REACH = 4.2;

    /** How far round the box the stand search looks, in blocks. */
    private static final int RING = 3;

    /** The largest side of the search region; a box or a start further off than this is left to the cell order. */
    private static final int MAX_SIDE = 48;

    /** How far a walk may drop to its next cell: three blocks, the most that does no fall damage. */
    private static final int MAX_DROP = 3;

    /**
     * What a step of walk costs, counted in cells in reach. Every cell has to be broken from some stand, so what a
     * stand that reaches more saves is a later walk, not digging; a cell a step makes a further stand worth it only
     * when it reaches clearly more.
     */
    private static final double STEP_COST = 1.0;

    private final BlockPos min;

    private final BlockPos max;

    /** A stand the bot picked, the cells in its reach that it scored, and a cell it sees now. */
    record Stand(BlockPos foot, int inReach, int steps, BlockPos firstCell) {}

    /** A clear of the box {@code min}..{@code max}, corners ordered. */
    ClearStands(BlockPos min, BlockPos max) {
        this.min = min;
        this.max = max;
    }

    /** Whether {@code bs} at {@code pos} is something the clear has to break. */
    static boolean hasWork(Level lvl, BlockPos pos, BlockState bs) {
        return !bs.isAir() && bs.getFluidState().isEmpty() && bs.getDestroySpeed(lvl, pos) >= 0;
    }

    /** Every cell of the box that still has work, less {@code skip}. */
    List<BlockPos> remaining(Level lvl, Set<BlockPos> skip) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : BlockPos.betweenClosed(min, max)) {
            if (!skip.contains(c) && hasWork(lvl, c, lvl.getBlockState(c))) {
                out.add(c.immutable());
            }
        }
        return out;
    }

    /**
     * The best stand to walk to: the one whose uncleared cells in reach, less {@link #STEP_COST} a step of walk, score
     * highest, among those from which some cell shows now. Never a stand in {@code barren}, nor one whose floor is a
     * cell to clear. Null when no walk-reachable stand sees a cell, or the region is too large to search.
     */
    Stand choose(Level lvl, Entity viewer, BlockPos foot, Set<BlockPos> skip, Set<BlockPos> barren) {
        List<BlockPos> cells = remaining(lvl, skip);
        if (cells.isEmpty()) {
            return null;
        }
        Walk walk = Walk.from(lvl, foot, min, max);
        if (walk == null) {
            return null;
        }
        List<Stand> scored = new ArrayList<>();
        for (int i = 0; i < walk.reached.size(); i++) {
            BlockPos s = walk.reached.get(i);
            if (barren.contains(s)) {
                continue;
            }
            int inReach = inReach(standingEye(s), s, cells).size();
            if (inReach > 0) {
                scored.add(new Stand(s, inReach, walk.steps.get(i), null));
            }
        }
        scored.sort(Comparator.comparingDouble(st -> -(st.inReach() - STEP_COST * st.steps())));
        for (Stand st : scored) {
            BlockPos seen = firstVisible(viewer, st.foot(), cells);
            if (seen != null) {
                return new Stand(st.foot(), st.inReach(), st.steps(), seen);
            }
        }
        return null;
    }

    /**
     * The next cell to break from where the bot stands now: of those {@code blocks} can break from here, the nearest
     * to the eye, the higher of two at the same distance. Never the cell under the feet, nor one in {@code skip}.
     */
    BlockPos nextInPlace(Level lvl, BlockInteraction blocks, Vec3 eye, BlockPos foot, Set<BlockPos> skip) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos floor = foot.below();
        for (BlockPos c : remaining(lvl, skip)) {
            if (c.equals(floor) || nearestDistance(eye, c) > PLAN_REACH + 0.3 || !blocks.canBreak(c)) {
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(c)) - c.getY() * 1.0e-3;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** The cells of {@code cells} whose nearest point is within {@link #PLAN_REACH} of {@code eye}, less the floor. */
    private static List<BlockPos> inReach(Vec3 eye, BlockPos stand, List<BlockPos> cells) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos floor = stand.below();
        for (BlockPos c : cells) {
            if (!c.equals(floor) && nearestDistance(eye, c) <= PLAN_REACH) {
                out.add(c);
            }
        }
        return out;
    }

    /** The nearest cell of {@code cells} in reach that an eye at {@code stand} sees some point of now, or null. */
    private static BlockPos firstVisible(Entity viewer, BlockPos stand, List<BlockPos> cells) {
        Vec3 eye = standingEye(stand);
        List<BlockPos> near = new ArrayList<>(inReach(eye, stand, cells));
        near.sort(Comparator.comparingDouble(c -> eye.distanceToSqr(Vec3.atCenterOf(c))));
        for (BlockPos c : near) {
            if (BlockSight.visiblePointFrom(viewer, eye, c, PLAN_REACH) != null) {
                return c;
            }
        }
        return null;
    }

    /** Distance from {@code eye} to the nearest point of the unit cube at {@code c}. */
    static double nearestDistance(Vec3 eye, BlockPos c) {
        double dx = Math.max(Math.max(c.getX() - eye.x, 0), eye.x - (c.getX() + 1));
        double dy = Math.max(Math.max(c.getY() - eye.y, 0), eye.y - (c.getY() + 1));
        double dz = Math.max(Math.max(c.getZ() - eye.z, 0), eye.z - (c.getZ() + 1));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The cells a body can walk to from {@code foot} without breaking or placing, with the steps to each: a step to a
     * side neighbour on the same level, one up (a jump, with head room above the start) or down to {@link #MAX_DROP}
     * (with the column it falls through open).
     */
    private static final class Walk {
        private final List<BlockPos> reached = new ArrayList<>();

        private final List<Integer> steps = new ArrayList<>();

        private int x0;

        private int y0;

        private int z0;

        private int sx;

        private int sy;

        private int sz;

        /** The walk from {@code foot} over the box and its ring, or null when that region is too large. */
        static Walk from(Level lvl, BlockPos foot, BlockPos min, BlockPos max) {
            Walk w = new Walk();
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
            ArrayDeque<BlockPos> open = new ArrayDeque<>();
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
            if (lvl.getBlockState(side).blocksMotion() || lvl.getBlockState(side.above()).blocksMotion()) {
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
}
