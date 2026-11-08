package net.magicterra.worlddriver.game.body;

import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where an eye can see a block: a point on it that a crosshair ray from that eye lands on first, inside the player's
 * reach. Vanilla breaks only the block under the crosshair, and the crosshair can be on any visible pixel of a block,
 * not only on its centre; a block whose centre is hidden behind a neighbour (an ore at the feet beside the stand, whose
 * centre line clips the block above it) is still breakable through the part of a face that shows.
 *
 * <p>The ray is the one vanilla's crosshair pick casts: {@link ClipContext.Block#OUTLINE} shapes, fluids ignored, from
 * the eye out to the full block reach, so a point counts only if the first block that ray meets is this one. Dist-
 * neutral: it reads the entity's own level, so the planner, a process and either body can ask it.
 */
public final class BlockSight {

    /**
     * Places across a face tried after its centre, as fractions of the face's width, nearest the centre first. Kept
     * off the edges so an aim rounded to a float yaw and pitch still lands inside the face.
     */
    private static final double[] FACE_STEPS = {0.5, 0.3, 0.7, 0.15, 0.85};

    /** Every (u, v) pair of {@link #FACE_STEPS}, nearest the face's centre first. */
    private static final double[][] FACE_POINTS = facePoints();

    private BlockSight() {}

    /**
     * A point on {@code block} that a ray from {@code viewer}'s eye reaches first within {@code reach}, or null when no
     * part of it shows. The centre comes back when it shows, so an unobstructed block is aimed at exactly as before;
     * otherwise the face points, faces turned most squarely to the eye first.
     */
    public static Vec3 visiblePoint(Entity viewer, BlockPos block, double reach) {
        Level level = viewer.level();
        BlockState state = level.getBlockState(block);
        if (state.isAir()) {
            return null;
        }
        VoxelShape shape = state.getShape(level, block, CollisionContext.of(viewer));
        if (shape.isEmpty()) {
            return null;
        }
        AABB box = shape.bounds().move(block);
        Vec3 eye = viewer.getEyePosition();
        Vec3 centre = box.getCenter();
        if (lands(viewer, eye, centre, block, reach)) {
            return centre;
        }
        Vec3 toEye = eye.subtract(centre);
        Direction[] faces = Arrays.stream(Direction.values())
                .filter(face -> facesEye(box, face, eye))
                .sorted(Comparator.comparingDouble(face -> -toEye.dot(Vec3.atLowerCornerOf(face.getNormal()))))
                .toArray(Direction[]::new);
        for (Direction face : faces) {
            for (double[] uv : FACE_POINTS) {
                Vec3 point = facePoint(box, face, uv[0], uv[1]);
                if (lands(viewer, eye, point, block, reach)) {
                    return point;
                }
            }
        }
        return null;
    }

    /** Whether the eye is on the outer side of {@code face}'s plane, the only side the face can be seen from. */
    private static boolean facesEye(AABB box, Direction face, Vec3 eye) {
        Direction.Axis axis = face.getAxis();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double plane = positive ? box.max(axis) : box.min(axis);
        double along = eye.get(axis) - plane;
        return positive ? along > 0 : along < 0;
    }

    /** The point at fractions {@code u}, {@code v} across {@code face} of {@code box}. */
    private static Vec3 facePoint(AABB box, Direction face, double u, double v) {
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        return switch (face.getAxis()) {
            case X -> new Vec3(
                    positive ? box.maxX : box.minX, Mth.lerp(u, box.minY, box.maxY), Mth.lerp(v, box.minZ, box.maxZ));
            case Y -> new Vec3(
                    Mth.lerp(u, box.minX, box.maxX), positive ? box.maxY : box.minY, Mth.lerp(v, box.minZ, box.maxZ));
            case Z -> new Vec3(
                    Mth.lerp(u, box.minX, box.maxX), Mth.lerp(v, box.minY, box.maxY), positive ? box.maxZ : box.minZ);
        };
    }

    /** Whether the crosshair ray from {@code eye} through {@code point}, cast over the full reach, meets {@code block}. */
    private static boolean lands(Entity viewer, Vec3 eye, Vec3 point, BlockPos block, double reach) {
        Vec3 dir = point.subtract(eye);
        double len = dir.length();
        if (len < 1.0e-6) {
            return true;
        }
        if (len > reach) {
            return false;
        }
        Vec3 end = eye.add(dir.scale(reach / len));
        BlockHitResult hit =
                viewer.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block);
    }

    private static double[][] facePoints() {
        double[][] points = new double[FACE_STEPS.length * FACE_STEPS.length][];
        int i = 0;
        for (double u : FACE_STEPS) {
            for (double v : FACE_STEPS) {
                points[i++] = new double[] {u, v};
            }
        }
        Arrays.sort(points, Comparator.comparingDouble(uv -> Math.hypot(uv[0] - 0.5, uv[1] - 0.5)));
        return points;
    }
}
