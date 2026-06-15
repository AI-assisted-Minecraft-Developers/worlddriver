package net.magicterra.agent.bot.debug;

import net.magicterra.agent.bot.world.SurvivalMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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
        // ---- Pose-fit ----
        boolean fitStand  = fits(level, STAND,  foot);
        boolean fitCrouch = fits(level, CROUCH, foot);
        boolean fitCrawl  = fits(level, CRAWL,  foot);
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
                        && fits(level, CRAWL, foot.above())
                        && (fits(level, STAND, next) || fits(level, CROUCH, next) || fits(level, CRAWL, next));
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

    /**
     * Returns true when the pose bounding box centred on {@code foot} (bottom of box)
     * has no block collisions in the level.
     */
    private static boolean fits(Level level, EntityDimensions dim, BlockPos foot) {
        AABB box = dim.makeBoundingBox(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5)
                      .deflate(1.0E-7);
        return level.noCollision(box);
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
