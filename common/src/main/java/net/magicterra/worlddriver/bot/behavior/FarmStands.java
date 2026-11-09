package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.game.body.BotUtil.standingEye;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.magicterra.worlddriver.game.body.BlockSight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where a farm stands to work, and which crop it harvests or plants next from where it stands.
 *
 * <p>A person farming a plot does not walk beside each crop. They stand where much of the plot is in reach, sweep the
 * ripe crops with the attack held, and plant the seeds back with the use button, and only then step on. A seed is
 * planted on the farmland's top face, which shows only once the ripe crops between it and the eye are gone: a ripe crop
 * is as tall as a block, and the face lies more than a block and a half below the eye. So the work at a stand is
 * harvest first, nearest first, and plant each cell once its face shows.
 *
 * <p>Stands are cells the bot can walk to now without breaking or placing ({@link StandWalk}); a crop cell is one,
 * since crops do not block movement and walking does not trample farmland. At the start the farm plans the fewest
 * stands that reach every cell ({@link #plan}); what that leaves, cells a stand could not see or work, goes to the
 * stand with the most cells still to harvest or plant in reach, less a step of walk each ({@link #choose}).
 */
final class FarmStands {
    /**
     * Reach used to rank stands, from the eye at the stand's centre to the centre of a cell's farmland face. The game's
     * reach is 4.5 from the eye to the point clicked; the bot stops near the stand's centre, not on it, and the margin
     * keeps a planned cell in reach of where it stops. It takes a 7 × 7 square less its corners.
     */
    static final double PLAN_REACH = 4.0;

    /** Reach for choosing the next crop in place, from the eye the bot really has, to the same face centre. */
    static final double WORK_REACH = 4.3;

    /** What a step of walk costs, counted in cells in reach, as for {@link ClearStands}. */
    private static final double STEP_COST = 1.0;

    /** The most stands {@link #plan} tries to cover the work with before it leaves the choice to one at a time. */
    private static final int MAX_PLANNED_STANDS = 5;

    /** The most candidate stands {@link #plan} tries in sets; sets of five from forty are 658,008. */
    private static final int MAX_PLAN_CANDIDATES = 40;

    private final BlockPos min;

    private final BlockPos max;

    /** A stand the bot picked, the cells to work in its reach, and the steps to it. */
    record Stand(BlockPos foot, int inReach, int steps) {}

    /** A farm over the box {@code min}..{@code max}, corners ordered. */
    FarmStands(BlockPos min, BlockPos max) {
        this.min = min;
        this.max = max;
    }

    /** The centre of the top face of the farmland under the crop cell {@code crop}, as the eye aims at it. */
    static Vec3 faceCentre(BlockPos crop) {
        return new Vec3(crop.getX() + 0.5, crop.getY(), crop.getZ() + 0.5);
    }

    /** Whether the crop cell {@code crop} is in reach {@code reach} of {@code eye}, measured to its farmland face. */
    static boolean inReach(Vec3 eye, BlockPos crop, double reach) {
        return eye.distanceToSqr(faceCentre(crop)) <= reach * reach;
    }

    /**
     * The best stand to walk to from {@code foot}: the one with the most of {@code work} in reach, less
     * {@link #STEP_COST} a step of walk. Never a stand in {@code barren}. Null when no stand the walk reaches has work
     * in reach, or the region is too large to search.
     */
    Stand choose(Level lvl, BlockPos foot, Collection<BlockPos> work, Set<BlockPos> barren) {
        if (work.isEmpty()) {
            return null;
        }
        StandWalk walk = StandWalk.from(lvl, foot, min, max);
        if (walk == null) {
            return null;
        }
        Stand best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < walk.reached().size(); i++) {
            BlockPos s = walk.reached().get(i);
            if (barren.contains(s)) {
                continue;
            }
            int n = countInReach(s, work);
            double score = n - STEP_COST * walk.steps(i);
            if (n > 0 && score > bestScore) {
                bestScore = score;
                best = new Stand(s, n, walk.steps(i));
            }
        }
        return best;
    }

    /**
     * The fewest stands that between them reach every cell of {@code work}, in the order with the shortest walk from
     * {@code foot}; empty when no such set is found within {@link #MAX_PLANNED_STANDS} stands, or the search would be
     * too large, and the caller then chooses one stand at a time.
     *
     * <p>Choosing one stand at a time takes the one that reaches most, which on a square plot is its middle; what is
     * left is a ring of corners that takes four or five more stands of a few cells each. On the 9 × 9 farming course
     * that was six to nine stands and 22 to 32 steps, where four stands, one over each quarter, reach every cell in 16.
     * A stand only ever reaching a subset of what another reaches is never needed, which leaves a few dozen candidates
     * to try in sets.
     */
    List<BlockPos> plan(Level lvl, BlockPos foot, List<BlockPos> work) {
        StandWalk walk = work.isEmpty() ? null : StandWalk.from(lvl, foot, min, max);
        if (walk == null) {
            return List.of();
        }
        // One stand per set of cells reached, the nearest to the feet.
        Map<BitSet, Integer> byReach = new LinkedHashMap<>();
        for (int i = 0; i < walk.reached().size(); i++) {
            Vec3 eye = standingEye(walk.reached().get(i));
            BitSet reach = new BitSet(work.size());
            for (int c = 0; c < work.size(); c++) {
                if (inReach(eye, work.get(c), PLAN_REACH)) {
                    reach.set(c);
                }
            }
            if (!reach.isEmpty()) {
                byReach.putIfAbsent(reach, i);
            }
        }
        List<BitSet> reaches = new ArrayList<>();
        for (BitSet r : byReach.keySet()) {
            boolean dominated = false;
            for (BitSet o : byReach.keySet()) {
                if (o != r && o.cardinality() > r.cardinality() && covers(o, r)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                reaches.add(r);
            }
        }
        if (reaches.size() > MAX_PLAN_CANDIDATES) {
            return List.of();
        }
        List<BlockPos> stands = new ArrayList<>();
        int[] steps = new int[reaches.size()];
        for (int i = 0; i < reaches.size(); i++) {
            int at = byReach.get(reaches.get(i));
            stands.add(walk.reached().get(at));
            steps[i] = walk.steps(at);
        }
        CoverSearch search = new CoverSearch(reaches, stands, steps, work.size());
        for (int k = 1; k <= MAX_PLANNED_STANDS && search.best == null; k++) {
            search.choose(new int[k], 0, 0, new BitSet());
        }
        if (search.best == null) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        for (int i : search.best) {
            out.add(stands.get(i));
        }
        return out;
    }

    /** Whether {@code outer} holds every cell {@code inner} holds. */
    private static boolean covers(BitSet outer, BitSet inner) {
        BitSet rest = (BitSet) inner.clone();
        rest.andNot(outer);
        return rest.isEmpty();
    }

    /** The search behind {@link #plan}: sets of {@code k} candidates that reach every cell, and their best order. */
    private static final class CoverSearch {
        private final List<BitSet> reaches;

        private final List<BlockPos> stands;

        private final int[] steps;

        private final int cells;

        private int[] best;

        private int bestWalk = Integer.MAX_VALUE;

        CoverSearch(List<BitSet> reaches, List<BlockPos> stands, int[] steps, int cells) {
            this.reaches = reaches;
            this.stands = stands;
            this.steps = steps;
            this.cells = cells;
        }

        /** Fill {@code set} from position {@code n} on with candidates from {@code from} on, the union so far given. */
        void choose(int[] set, int n, int from, BitSet union) {
            if (n == set.length) {
                if (union.cardinality() == cells) {
                    order(set.clone(), 0);
                }
                return;
            }
            for (int i = from; i < reaches.size(); i++) {
                BitSet next = (BitSet) union.clone();
                next.or(reaches.get(i));
                set[n] = i;
                choose(set, n + 1, i + 1, next);
            }
        }

        /** Try every order of {@code set} from position {@code n} on, keeping the one with the shortest walk. */
        private void order(int[] set, int n) {
            if (n == set.length) {
                int w = steps[set[0]];
                for (int i = 1; i < set.length; i++) {
                    BlockPos a = stands.get(set[i - 1]);
                    BlockPos b = stands.get(set[i]);
                    w += Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
                }
                if (w < bestWalk) {
                    bestWalk = w;
                    best = set.clone();
                }
                return;
            }
            for (int i = n; i < set.length; i++) {
                swap(set, n, i);
                order(set, n + 1);
                swap(set, n, i);
            }
        }

        private static void swap(int[] a, int i, int j) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    /**
     * The ripe crop to break next from here: of {@code ripe} in {@link #WORK_REACH} that {@code breakable} accepts, the
     * nearest to the eye, so the crops hiding the farther faces go first. Null when none.
     */
    static BlockPos nextHarvest(Vec3 eye, Collection<BlockPos> ripe, Predicate<BlockPos> breakable) {
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos c : ripe) {
            if (inReach(eye, c, WORK_REACH)) {
                near.add(c);
            }
        }
        near.sort(Comparator.comparingDouble(c -> eye.distanceToSqr(Vec3.atCenterOf(c))));
        for (BlockPos c : near) {
            if (breakable.test(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * The harvested cell to plant next from here: of {@code bare} in {@link #WORK_REACH}, the farthest whose farmland
     * face shows to the eye, so a seed just planted never stands between the eye and a face still to plant. Null when
     * none shows.
     */
    static BlockPos nextPlant(Player viewer, Vec3 eye, Collection<BlockPos> bare) {
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos c : bare) {
            if (inReach(eye, c, WORK_REACH)) {
                near.add(c);
            }
        }
        near.sort(Comparator.comparingDouble(c -> -eye.distanceToSqr(faceCentre(c))));
        for (BlockPos c : near) {
            if (BlockSight.visibleFacePoint(viewer, eye, c.below(), Direction.UP, viewer.blockInteractionRange())
                    != null) {
                return c;
            }
        }
        return null;
    }

    /** How many of {@code work} are in {@link #PLAN_REACH} of the eye of a body standing at {@code stand}. */
    static int countInReach(BlockPos stand, Collection<BlockPos> work) {
        Vec3 eye = standingEye(stand);
        int n = 0;
        for (BlockPos c : work) {
            if (inReach(eye, c, PLAN_REACH)) {
                n++;
            }
        }
        return n;
    }

    /** Whether any of {@code bare} is in {@link #WORK_REACH} of {@code eye}, shown or not. */
    static boolean anyInReach(Vec3 eye, Collection<BlockPos> bare) {
        for (BlockPos c : bare) {
            if (inReach(eye, c, WORK_REACH)) {
                return true;
            }
        }
        return false;
    }
}
