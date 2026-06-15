package net.magicterra.agent.bot.debug;

import net.magicterra.agent.bot.world.SurvivalMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure per-node physics facts for the path archive/replay/analysis.
 *
 * <p>Uses the real {@link Level#noCollision(AABB)} with the 1.8 / 1.5 / 0.6-tall
 * pose bounding boxes so every result matches Minecraft's own pose-selection and
 * suffocation rules exactly.  No mutable state; all methods are pure given the level.</p>
 *
 * <p>Usage: call {@link #compute(Level, BlockPos, BlockPos, BlockPos)} once per
 * archived node to obtain a {@link Facts} record that the analysis layer can
 * inspect without re-querying the world.</p>
 */
public final class NodePhysics {
    private NodePhysics() {}

    // Player pose bounding boxes (width × height); see EntityType.PLAYER dimensions.
    private static final EntityDimensions STAND  = EntityDimensions.scalable(0.6f, 1.8f);
    private static final EntityDimensions CROUCH = EntityDimensions.scalable(0.6f, 1.5f);
    private static final EntityDimensions CRAWL  = EntityDimensions.scalable(0.6f, 0.6f);

    /** Largest vertical step the player can walk up without jumping. */
    private static final double MAX_UP_STEP = 0.6;
    /** Approximate peak height of a vanilla jump (blocks above foot). */
    private static final double JUMP_APEX   = 1.25;

    /**
     * Immutable snapshot of physics facts for one planned path node.
     *
     * @param fitStand         true when the 1.8-tall standing box fits at {@code foot}.
     * @param fitCrouch        true when the 1.5-tall crouching box fits.
     * @param fitCrawl         true when the 0.6-tall crawling box fits.
     * @param collidesStanding true when the standing box collides (≡ !fitStand).
     * @param ceilingForces    pose the ceiling forces: "none", "crouch", "crawl", or "suffocate".
     * @param inWaterFoot      true when the foot cell contains water.
     * @param submergedEye     true when the cell above the foot contains water (eye submerged for a crouching bot).
     * @param underfootSolid   true when the block directly below {@code foot} has a sturdy top face.
     * @param footHazard       hazard string ("lava", "magma", "cactus", "campfire", "fire",
     *                         "sweet_berry", "wither_rose", "powder_snow") or {@code null} for none.
     * @param fallFromPrev     drop in blocks from the previous node (0 if {@code prev} is null or higher).
     * @param fallSurvivable   true when {@code fallFromPrev} is within the survivable threshold at full HP.
     * @param jumpNeeded       true when the transition to {@code next} requires a jump (dy &gt; step).
     * @param jumpFeasible     true when the jump to {@code next} is geometrically feasible (apex + clearance).
     */
    public record Facts(
            boolean fitStand,
            boolean fitCrouch,
            boolean fitCrawl,
            boolean collidesStanding,
            String  ceilingForces,
            boolean inWaterFoot,
            boolean submergedEye,
            boolean underfootSolid,
            String  footHazard,
            double  fallFromPrev,
            boolean fallSurvivable,
            boolean jumpNeeded,
            boolean jumpFeasible
    ) {}

    /**
     * Computes physics facts for {@code foot} in the given {@code level}.
     *
     * @param level the server (or client) level — used for collision and block queries.
     * @param foot  foot position of the node being analysed.
     * @param prev  previous planned node (for fall-height computation), or {@code null}.
     * @param next  next planned node (for jump need/feasibility), or {@code null}.
     * @return an immutable {@link Facts} snapshot.
     */
    public static Facts compute(Level level, BlockPos foot, BlockPos prev, BlockPos next) {
        return compute(level, foot, prev, next, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Edit-aware overload: the pose-fit and jump-clearance collision checks treat the
     * cells in {@code toBreak} as AIR and the cells in {@code toPlace} as a full solid
     * cube — i.e. the world AS IT WILL BE once the entering edge's planned dig/place
     * has run. Without this a {@code stairUpBreak} node (which breaks the head cell it
     * then climbs through) is wrongly flagged SUFFOCATE/COLLIDE against the un-broken
     * terrain, swamping the analysis with false positives. Pass the entering edge's
     * {@code toBreak}/{@code toPlace}; empty collections reproduce the raw-terrain
     * behaviour exactly (and take the fast {@code noCollision} path).
     *
     * @param toBreak cells the entering edge breaks before standing here (treated as air).
     * @param toPlace cells the entering edge places (treated as a full solid cube).
     */
    public static Facts compute(Level level, BlockPos foot, BlockPos prev, BlockPos next,
                                Collection<BlockPos> toBreak, Collection<BlockPos> toPlace) {
        Set<Long> brk = toLongKeys(toBreak);
        Set<Long> plc = toLongKeys(toPlace);
        // ---- Pose-fit ----
        boolean fitStand  = fits(level, STAND,  foot, brk, plc);
        boolean fitCrouch = fits(level, CROUCH, foot, brk, plc);
        boolean fitCrawl  = fits(level, CRAWL,  foot, brk, plc);
        String forces = fitStand ? "none"
                      : fitCrouch ? "crouch"
                      : fitCrawl  ? "crawl"
                      : "suffocate";

        // ---- Fluid ----
        boolean inWaterFoot  = level.getFluidState(foot).is(FluidTags.WATER);
        // Eye level for crouching is roughly foot+1; for standing it is foot+1.62.
        // Using foot.above() is a conservative "is the head cell wet?" check.
        boolean submergedEye = level.getFluidState(foot.above()).is(FluidTags.WATER);

        // ---- Underfoot ----
        BlockState below = level.getBlockState(foot.below());
        boolean underfootSolid = below.isFaceSturdy(level, foot.below(), Direction.UP);

        // ---- Hazard ----
        String hazard = detectHazard(level, foot);

        // ---- Fall from previous node ----
        double fall = (prev != null) ? (double)(prev.getY() - foot.getY()) : 0.0;
        if (fall < 0.0) fall = 0.0;   // going UP from prev → no fall
        boolean fallOk = fall <= survivableFall();

        // ---- Jump to next node ----
        boolean jumpNeeded   = false;
        boolean jumpFeasible = true;
        if (next != null) {
            double dy = (double)(next.getY() - foot.getY());
            jumpNeeded = dy > MAX_UP_STEP;
            if (jumpNeeded) {
                // Feasible when the apex fits, we have overhead clearance, and the target cell
                // can be occupied in some pose.
                jumpFeasible = dy <= JUMP_APEX
                        && fits(level, CRAWL, foot.above(), brk, plc)
                        && (fits(level, STAND, next, brk, plc) || fits(level, CROUCH, next, brk, plc) || fits(level, CRAWL, next, brk, plc));
            }
        }

        return new Facts(
                fitStand, fitCrouch, fitCrawl,
                !fitStand, forces,
                inWaterFoot, submergedEye,
                underfootSolid, hazard,
                fall, fallOk,
                jumpNeeded, jumpFeasible
        );
    }

    // ---- private helpers ----

    /** Pack a cell collection into a {@code Set<Long>} of {@link BlockPos#asLong} keys
     *  for O(1) membership tests during the edit-aware collision scan. */
    private static Set<Long> toLongKeys(Collection<BlockPos> cells) {
        if (cells == null || cells.isEmpty()) return Collections.emptySet();
        Set<Long> s = new HashSet<>(cells.size() * 2);
        for (BlockPos p : cells) s.add(p.asLong());
        return s;
    }

    /**
     * Returns true when the pose bounding box centred on {@code foot} (bottom of box)
     * has no block collisions — with {@code brk} cells treated as AIR and {@code plc}
     * cells as a full solid cube. When both edit sets are empty this is exactly
     * {@link Level#noCollision(AABB)} (fast path); otherwise it scans the cells the box
     * overlaps and tests each post-edit collision shape against the box.
     */
    private static boolean fits(Level level, EntityDimensions dim, BlockPos foot,
                                Set<Long> brk, Set<Long> plc) {
        AABB box = dim.makeBoundingBox(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5)
                      .deflate(1.0E-7);
        if (brk.isEmpty() && plc.isEmpty()) return level.noCollision(box);
        VoxelShape boxShape = Shapes.create(box);
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
                for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                    mp.set(x, y, z);
                    long key = mp.asLong();
                    if (brk.contains(key)) continue;            // planned break → air
                    VoxelShape shape = plc.contains(key)
                            ? Shapes.block()                    // planned place → full cube
                            : level.getBlockState(mp).getCollisionShape(level, mp);
                    if (shape.isEmpty()) continue;
                    if (Shapes.joinIsNotEmpty(boxShape, shape.move(x, y, z), BooleanOp.AND))
                        return false;
                }
            }
        }
        return true;
    }

    /**
     * Maximum fall distance (blocks) survivable at full health (20 HP).
     * Delegates to {@link SurvivalMath} so the threshold is consistent with the
     * planner and executor.
     */
    private static double survivableFall() {
        return SurvivalMath.survivableFall(20f);
    }

    /**
     * Scans the foot cell and the block directly below for known contact-damage /
     * instant-death hazards.  Returns a short string tag, or {@code null} if clean.
     * The foot cell is checked first (e.g. standing IN lava) then the block below
     * (standing ON magma).
     */
    private static String detectHazard(Level level, BlockPos foot) {
        for (BlockPos p : new BlockPos[]{ foot, foot.below() }) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.LAVA))               return "lava";
            if (s.is(Blocks.MAGMA_BLOCK))        return "magma";
            if (s.is(Blocks.CACTUS))             return "cactus";
            if (s.is(Blocks.CAMPFIRE)
             || s.is(Blocks.SOUL_CAMPFIRE))      return "campfire";
            if (s.is(Blocks.FIRE)
             || s.is(Blocks.SOUL_FIRE))          return "fire";
            if (s.is(Blocks.SWEET_BERRY_BUSH))   return "sweet_berry";
            if (s.is(Blocks.WITHER_ROSE))        return "wither_rose";
            if (s.is(Blocks.POWDER_SNOW))        return "powder_snow";
            // Also catch flowing lava via FluidTags (FluidState.is(Fluid) misses FLOWING_LAVA).
            if (level.getFluidState(p).is(FluidTags.LAVA)) return "lava";
        }
        return null;
    }
}
