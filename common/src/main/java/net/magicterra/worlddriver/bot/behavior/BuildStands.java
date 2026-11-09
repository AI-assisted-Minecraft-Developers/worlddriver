package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.game.body.BotUtil.standingEye;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.magicterra.worlddriver.game.body.BlockSight;
import net.magicterra.worlddriver.game.observe.BuildBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a build stands to work, and which cell it places next from where it stands.
 *
 * <p>A person building does not walk to each block. They stand where much of the structure is in reach, place
 * everything they can from there, bottom-up so each block has something under or beside it to grow from, and only then
 * step on. A block grows off the face under the crosshair, so a cell can be placed from where the eye can see a face of
 * a solid neighbour that points at it. The face on top of a wall is seen only from above its top, which is why a person
 * hops to place the top course of a wall as tall as they are: a jump lifts the eye a block and a quarter.
 *
 * <p>Stands are floor cells the bot can walk to now without breaking or placing ({@link StandWalk}), never a cell the
 * build is about to fill, and a stand on a block of the build costs extra: a person walks along a wall top only when
 * the floor reaches nothing. They are ranked by how many of the remaining cells could be placed from there, in an order
 * where each placement may lean on the ones before it, less a step of walk each. The ranking reads faces by geometry
 * alone, the eye on the outer side of the face and the face in reach; whether a ray gets there is asked only of the
 * cell placed next, from the eye the bot really has.
 */
final class BuildStands {
    /**
     * Reach used to rank stands, from the eye to the nearest point of a support face; the placement itself gets the
     * game's full reach. Shorter than {@link ClearStands#PLAN_REACH} because the ranking reads no rays: a face near the
     * end of reach is seen at a grazing angle, past the blocks placed before it. On the building course the doorway,
     * which the full reach rated as good as the hut's middle, could not see the far corners once the near walls stood.
     */
    static final double PLAN_REACH = 3.6;

    /**
     * How far a jump lifts the eye, for planning and for deciding to hop. Vanilla's jump peaks 1.25 above the floor; a
     * block of it is there for long enough to place one or two blocks between use delays.
     */
    static final double JUMP_RISE = 1.0;

    /** What a step of walk costs, counted in cells placeable, as for {@link ClearStands}. */
    private static final double STEP_COST = 1.0;

    /** What standing on a block of the build costs, in cells: it is a wall top, not the floor. */
    private static final double BUILT_FLOOR_COST = 4.0;

    private final BlockPos min;

    private final BlockPos max;

    private final Set<BlockPos> blueprint;

    /** A stand the bot picked, the cells it reckons placeable from there, and the steps to it. */
    record Stand(BlockPos foot, int placeable, int steps) {}

    /** A placement: the cell to fill, the block it grows off, and that block's face toward the cell. */
    record Placement(BlockPos cell, BlockPos support, Direction face) {}

    /** A build whose cells are {@code blueprint}, inside the box {@code min}..{@code max}, corners ordered. */
    BuildStands(BlockPos min, BlockPos max, Set<BlockPos> blueprint) {
        this.min = min;
        this.max = max;
        this.blueprint = blueprint;
    }

    /**
     * The best stand to walk to from {@code foot} for the cells {@code remaining}, or null when no stand the walk
     * reaches could place any of them, or the region is too large to search. Never a stand in {@code barren}.
     */
    Stand choose(Entity viewer, BlockPos foot, List<BlockPos> remaining, Set<BlockPos> barren) {
        if (remaining.isEmpty()) {
            return null;
        }
        Level lvl = viewer.level();
        StandWalk walk = StandWalk.from(lvl, foot, min, max);
        if (walk == null) {
            return null;
        }
        Set<BlockPos> toFill = new HashSet<>(remaining);
        List<Stand> scored = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < walk.reached().size(); i++) {
            BlockPos s = walk.reached().get(i);
            if (barren.contains(s) || toFill.contains(s) || toFill.contains(s.above())) {
                continue;
            }
            int n = placeableFrom(lvl, s, remaining);
            if (n > 0) {
                scored.add(new Stand(s, n, walk.steps(i)));
                scores.add(n - STEP_COST * walk.steps(i) - (blueprint.contains(s.below()) ? BUILT_FLOOR_COST : 0));
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        // The ranking is by geometry; the stand taken is the best one from which a ray really reaches a support face
        // now, standing or at the top of a hop, so a stand that would place nothing is not walked to.
        for (int i : order) {
            Stand st = scored.get(i);
            Vec3 eye = standingEye(st.foot());
            AABB body = bodyAt(st.foot());
            if (next(viewer, eye, body, remaining, false) != null
                    || next(viewer, eye.add(0, JUMP_RISE, 0), body.move(0, JUMP_RISE, 0), remaining, true) != null) {
                return st;
            }
        }
        return null;
    }

    /** The box of a standing player at the centre of {@code foot}. */
    private static AABB bodyAt(BlockPos foot) {
        double x = foot.getX() + 0.5;
        double z = foot.getZ() + 0.5;
        return new AABB(x - 0.3, foot.getY(), z - 0.3, x + 0.3, foot.getY() + 1.8, z + 0.3);
    }

    /**
     * How many of {@code remaining} could be placed from a stand at {@code stand}, standing or at the top of a hop,
     * each leaning on the world's solid blocks or on cells placed before it. Faces are read by geometry only.
     */
    private static int placeableFrom(Level lvl, BlockPos stand, List<BlockPos> remaining) {
        Vec3 eye = standingEye(stand);
        Vec3 hop = eye.add(0, JUMP_RISE, 0);
        Set<BlockPos> placed = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (BlockPos c : remaining) {
                if (placed.contains(c) || c.equals(stand) || c.equals(stand.above())) {
                    continue;
                }
                if (faceInView(lvl, c, eye, placed) || faceInView(lvl, c, hop, placed)) {
                    placed.add(c);
                    grew = true;
                }
            }
        }
        return placed.size();
    }

    /** Whether a neighbour of {@code c} that holds, or is in {@code placed}, turns to it a face {@code eye} faces. */
    private static boolean faceInView(Level lvl, BlockPos c, Vec3 eye, Set<BlockPos> placed) {
        for (Direction d : Direction.values()) {
            BlockPos n = c.relative(d);
            if (!placed.contains(n) && !supports(lvl.getBlockState(n))) {
                continue;
            }
            Direction face = d.getOpposite();
            if (outside(n, face, eye) && faceDistance(n, face, eye) <= PLAN_REACH) {
                return true;
            }
        }
        return false;
    }

    /**
     * The next placement from where {@code viewer} is, with its eye at {@code eye}: of the cells in {@code remaining}
     * that are empty and clear of the body, the lowest one with a support face the eye sees in reach, the farthest of
     * those at one height. Null when none. With {@code preferUnseenFromFloor} a cell that could not be placed with the
     * eye {@link #JUMP_RISE} lower comes first among the lowest, so a hop is spent on what only a hop can place.
     */
    static Placement next(Entity viewer, Vec3 eye, AABB body, List<BlockPos> remaining, boolean preferUnseenFromFloor) {
        Level lvl = viewer.level();
        double reach = viewer instanceof Player pl ? pl.blockInteractionRange() : PLAN_REACH;
        List<BlockPos> order = new ArrayList<>(remaining);
        order.sort((a, b) -> {
            if (a.getY() != b.getY()) {
                return Integer.compare(a.getY(), b.getY());
            }
            if (preferUnseenFromFloor) {
                boolean fa = faceInView(lvl, a, eye.add(0, -JUMP_RISE, 0), Set.of());
                boolean fb = faceInView(lvl, b, eye.add(0, -JUMP_RISE, 0), Set.of());
                if (fa != fb) {
                    return fa ? 1 : -1;
                }
            }
            // Farthest first within a course: a near block placed first stands between the eye and the far supports.
            return Double.compare(eye.distanceToSqr(Vec3.atCenterOf(b)), eye.distanceToSqr(Vec3.atCenterOf(a)));
        });
        for (BlockPos c : order) {
            if (!lvl.getBlockState(c).canBeReplaced() || body.intersects(new AABB(c))) {
                continue;
            }
            if (ClearStands.nearestDistance(eye, c) > reach) {
                continue;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (!supports(lvl.getBlockState(n))) {
                    continue;
                }
                Direction face = d.getOpposite();
                if (BlockSight.visibleFacePoint(viewer, eye, n, face, reach) != null) {
                    return new Placement(c, n, face);
                }
            }
        }
        return null;
    }

    /** Whether a placement may grow off {@code s}: solid, and not a block whose use a right-click would open. */
    private static boolean supports(BlockState s) {
        return PlaceSupport.holds(s) && !BuildBlocks.isInteractiveBlock(s.getBlock());
    }

    /** Whether {@code eye} is on the outer side of {@code face} of the unit cell {@code n}. */
    private static boolean outside(BlockPos n, Direction face, Vec3 eye) {
        double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? n.get(face.getAxis()) + 1
                : n.get(face.getAxis());
        double along = eye.get(face.getAxis()) - plane;
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? along > 0 : along < 0;
    }

    /** Distance from {@code eye} to the nearest point of {@code face} of the unit cell {@code n}. */
    private static double faceDistance(BlockPos n, Direction face, Vec3 eye) {
        AABB box = new AABB(n);
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double coord = positive ? box.max(face.getAxis()) : box.min(face.getAxis());
        double dx = face.getAxis() == Direction.Axis.X
                ? coord - eye.x
                : Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = face.getAxis() == Direction.Axis.Y
                ? coord - eye.y
                : Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = face.getAxis() == Direction.Axis.Z
                ? coord - eye.z
                : Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
