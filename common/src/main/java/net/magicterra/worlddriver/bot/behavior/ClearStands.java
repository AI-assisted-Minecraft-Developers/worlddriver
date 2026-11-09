package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.game.body.BotUtil.standingEye;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.BlockSight;
import net.minecraft.core.BlockPos;
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
        StandWalk walk = StandWalk.from(lvl, foot, min, max);
        if (walk == null) {
            return null;
        }
        List<Stand> scored = new ArrayList<>();
        for (int i = 0; i < walk.reached().size(); i++) {
            BlockPos s = walk.reached().get(i);
            if (barren.contains(s)) {
                continue;
            }
            int inReach = inReach(standingEye(s), s, cells).size();
            if (inReach > 0) {
                scored.add(new Stand(s, inReach, walk.steps(i), null));
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
     * The next cell to break from where the bot stands now: of those {@code blocks} can break from here, the nearest to
     * the eye, the higher of two at the same distance. Never the cell under the feet, nor one in {@code skip}.
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
}
