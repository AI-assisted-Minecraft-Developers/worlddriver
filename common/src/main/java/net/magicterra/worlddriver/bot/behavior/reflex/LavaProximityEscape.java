package net.magicterra.worlddriver.bot.behavior.reflex;

import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.BotUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Lava-front escape reflex (devil-bench iron ep-014/#27, ep-025/#29, ep-026/#30 — three lava deaths in one day, each a
 * different last metre of the same disease: a breached pocket's FLOW FRONT advances ~1 cell / 1.5 s while every
 * existing layer assumes static hazards).
 *
 * <p>The navigator's path-hazard brake (ep-014 fix) stops the bot from WALKING into flowed lava, and the fall-edge
 * margin (ep-025 fix) refuses drops beside a front — but ep-026 died STANDING STILL: the brake vetoed every forward
 * move, the repath loop kept it in place, and the front arrived. What was missing is the reflex a human plays by feel:
 * lava creeping next to your feet means you WALK AWAY NOW and think afterwards.
 *
 * <p>Mechanics: sibling of {@link ContactDamageEscape} — an unconditional client-tick reflex run after the scheduler,
 * not a movement chain. Trigger: the bot is IN lava, or a FLOWING (non-source) lava cell sits in the foot
 * 8-neighbourhood (or one below it — the front eats the floor first). Static source pools do NOT trigger: mining beside
 * a calm lava lake is normal iron- country work and the planner already prices it. Drive: face directly away from the
 * nearest triggering cell and hold forward (jump on collision), preferring a hazard-free cardinal when the away-vector
 * is degenerate or itself blocked; linger a few ticks past the last sighting, hard-capped per episode so a fully-ringed
 * bot hands the channel back to the planner.
 *
 * <p>Leaving lava does not put a bot out: it burns for fifteen seconds more, and walking further from the lava does
 * nothing about that. So once clear, a bot still alight with water within {@link BehaviorConfig#lavaEscapeWaterRadius}
 * walks into it and stands there until the fire is out, and only then hands back; with no water in reach it hands back
 * burning.
 */
public final class LavaProximityEscape {

    private static final int LINGER_TICKS = 12;
    private static final int MAX_EPISODE_TICKS = 80;
    /** A walk to water within the largest radius takes about a hundred ticks; past this it is not getting there. */
    private static final int MAX_QUENCH_TICKS = 200;

    private boolean active;
    private int linger;
    private int episodeTicks;
    private BlockPos lastLava;
    /** Clear of the lava, still burning, walking into water ({@link #quench}). */
    private boolean quenching;

    private int quenchTicks;

    /** @return true if it drove the escape this tick (keys are ours). */
    public boolean tick(Body body) {
        // Everything read and set here is a LivingEntity's, so a mob walks away from lava too.
        LivingEntity p = body.entity();
        if (!BehaviorConfig.lavaProximityEscape) {
            reset("off");
            return false;
        }
        Level level = p.level();
        if (!p.isInLava() && workingLava(level, p)) {
            reset("working lava");
            return false;
        }
        BlockPos threat = nearestThreat(level, p);
        boolean hot = threat != null || p.isInLava();
        if (quenching) {
            if (!hot) {
                return quench(body, level, p);
            }
            quenching = false; // back among the lava: escape first
        }
        if (!active) {
            if (!hot) {
                return false;
            }
            active = true;
            episodeTicks = 0;
            lastLava = null;
            // blockPosition(), NOT (int) casts. `(int)` truncates toward zero, so at x=-9.3 it
            // printed -9 while every decision below used foot.getX() == -10 — and on the rehearsal
            // burn of 2026-08-23 that one digit was the whole question: a bot standing ON TOP of
            // a source pool read as merely "adjacent" to it. The two are not cosmetic variants of
            // each other here. `own` (ox==0 && oz==0) is what exempts a cell from the source
            // carve-out in nearestThreat, so own-vs-adjacent decides whether a calm pool can
            // trigger this reflex at all — and the log was printing the wrong one of the two.
            LOG.info(
                    "[lavaEscape] {} at {} (hp={}) → walking away",
                    p.isInLava() ? "IN lava" : "flow front adjacent " + threat.toShortString(),
                    p.blockPosition().toShortString(),
                    String.format("%.1f", p.getHealth()));
        }
        if (hot) {
            linger = LINGER_TICKS;
        } else if (p.isOnFire()
                && BehaviorConfig.lavaEscapeWaterRadius > 0
                && towardWater(level, p.blockPosition(), BehaviorConfig.lavaEscapeWaterRadius) != null) {
            // Clear of the lava and still alight: walking further from the lava does not put it out, water does.
            quenching = true;
            quenchTicks = 0;
            LOG.info(
                    "[lavaEscape] still burning at {} → walking into water",
                    p.blockPosition().toShortString());
            return quench(body, level, p);
        } else if (--linger <= 0) {
            reset("clear");
            return false;
        }
        if (++episodeTicks > MAX_EPISODE_TICKS) {
            reset("episode-cap");
            return false;
        }

        if (threat != null) {
            lastLava = threat;
        }
        Vec3 me = p.position();
        double dx = 0;
        double dz = 0;
        if (lastLava != null) {
            dx = me.x - (lastLava.getX() + 0.5);
            dz = me.z - (lastLava.getZ() + 0.5);
        }
        // Degenerate (standing in it / directly below), the away cell is
        // itself lava, or it has NO SOLID FLOOR (death #31: the escape run
        // walked off a ledge into the pool below — trading a front for a
        // fall): fall back to the first clear cardinal, which prefers floors.
        BlockPos awayCell = BlockPos.containing(me.x + Math.signum(dx), me.y, me.z + Math.signum(dz));
        boolean badVector = Math.hypot(dx, dz) < 0.35
                || isLava(level, awayCell)
                || isLava(level, awayCell.below())
                || !level.getBlockState(awayCell.below()).blocksMotion();
        if (badVector) {
            // `open` is this reflex's own notion of a cell the player fits through, and it is
            // LOOSER than the contact sibling's: `!blocksMotion()` steps over a carpet or a
            // pressure plate that `getCollisionShape().isEmpty()` refuses. The shared picker
            // takes the test as a parameter so merging the two moved neither.
            Direction d = BotUtil.stepAwayCardinal(
                    p.blockPosition(),
                    c -> isLava(level, c),
                    c -> !level.getBlockState(c).blocksMotion());
            if (d != null) {
                dx = d.getStepX();
                dz = d.getStepZ();
            } else {
                dx = 1;
                dz = 0;
            } // fully ringed: any push beats standing still
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYRot(yaw);
        p.setXRot(0f);
        // Forward along the yaw just set, on the channel that OUTRANKS the navigator's own per-tick
        // command. This reflex was written FOR the case where a process is pinned against an
        // advancing front (the ep-026 shape: the brake vetoes every forward move, the repath loop
        // holds position, the lava arrives) — and that is exactly the case where a movement
        // process is commanding every tick. On the SHARED keybind the steer never reached the
        // player at all; on commandForward it was discarded whenever the Navigator commanded a move.
        // Either way the reflex was inert precisely on the occasion it was built for.
        body.commandMove(0f, 1f);
        body.commandJump(p.horizontalCollision || p.isInLava());
        return true;
    }

    /**
     * Nearest FLOWING lava cell in the foot 8-neighbourhood or the ring one below it (the front eats the floor level
     * first). The own/below cells count regardless of source-ness — standing on or in any lava is hot.
     */
    private static BlockPos nearestThreat(Level lvl, LivingEntity p) {
        BlockPos foot = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int oy = -1; oy <= 0; oy++) {
                    BlockPos cand = foot.offset(ox, oy, oz);
                    boolean own = ox == 0 && oz == 0;
                    FluidState fs = lvl.getFluidState(cand);
                    if (!fs.is(FluidTags.LAVA)) {
                        continue;
                    }
                    if (!own && fs.isSource()) {
                        continue; // calm pool: not a front
                    }
                    double d = cand.distToCenterSqr(p.getX(), p.getY(), p.getZ());
                    if (d < bestD) {
                        bestD = d;
                        best = cand;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Whether the body is deliberately handling lava this tick: an empty bucket aimed at a lava source in reach (a
     * scoop), or a lava bucket aimed at a block in reach (a pour). The flow off a source being scooped is exactly what
     * triggers this reflex, and walking away with the aim reset spoiled every scoop — 0 of 10 casts in
     * {@code PortalTest.serverBuildsAndLightsAPortal}. The ray is the bucket's own
     * ({@code Item.getPlayerPOVHitResult}): eye along the look, sources only for the empty bucket.
     */
    private static boolean workingLava(Level lvl, LivingEntity p) {
        ItemStack held = p.getMainHandItem();
        boolean scoop = held.is(Items.BUCKET);
        if (!scoop && !held.is(Items.LAVA_BUCKET)) {
            return false;
        }
        double reach = p instanceof Player player ? player.blockInteractionRange() : 4.5;
        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = lvl.clip(new ClipContext(
                eye,
                eye.add(p.getViewVector(1f).scale(reach)),
                ClipContext.Block.OUTLINE,
                scoop ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE,
                p));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return !scoop || lvl.getFluidState(hit.getBlockPos()).is(FluidTags.LAVA);
    }

    private static boolean isLava(Level lvl, BlockPos pos) {
        return lvl.getFluidState(pos).is(FluidTags.LAVA);
    }

    /**
     * No key/command release here, unlike the sibling {@link ContactDamageEscape#reset}: the avatar commands this
     * reflex drives are per-tick, so an episode that stops re-asserting has already handed the channel back. (Under the
     * old keybinds that made this an actual latch leak — {@code keyUp.setDown(true)} stays down — which is why the
     * sibling had a release and this one's absence was a divergence, not a simplification.)
     */
    private void reset(String why) {
        if (active) {
            LOG.info("[lavaEscape] handing back ({}), {} ticks", why, episodeTicks + quenchTicks);
        }
        active = false;
        linger = 0;
        episodeTicks = 0;
        lastLava = null;
        quenching = false;
        quenchTicks = 0;
    }

    /**
     * One tick of the walk into water: hand back once the fire is out, stand still once in water (touching water puts
     * the fire out on the entity's next tick), otherwise step toward the nearest water cell {@link #towardWater} can
     * reach. Hands back burning when no water is reachable any more or the walk runs past {@link #MAX_QUENCH_TICKS}.
     */
    private boolean quench(Body body, Level level, LivingEntity p) {
        if (!p.isOnFire()) {
            reset("fire out");
            return false;
        }
        if (++quenchTicks > MAX_QUENCH_TICKS) {
            reset("quench-cap");
            return false;
        }
        if (p.isInWater()) {
            body.commandMove(0f, 0f);
            body.commandJump(false);
            return true;
        }
        BlockPos step = towardWater(level, p.blockPosition(), BehaviorConfig.lavaEscapeWaterRadius);
        if (step == null) {
            reset("no water");
            return false;
        }
        double dx = step.getX() + 0.5 - p.getX();
        double dz = step.getZ() + 0.5 - p.getZ();
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot(0f);
        body.commandMove(0f, 1f);
        body.commandJump(p.horizontalCollision);
        return true;
    }

    /**
     * The first cell to walk to on the shortest walk from {@code foot} to water within {@code radius} blocks
     * horizontally, or null when there is none. A walk moves one cardinal cell at a time onto a floor, up one block or
     * down at most two, never through or onto lava; it ends in a cell holding water or just above water. {@code foot}
     * itself when it already is one. Breadth first, so the walk found is a shortest one.
     */
    private static BlockPos towardWater(Level level, BlockPos foot, int radius) {
        if (watery(level, foot)) {
            return foot;
        }
        Map<BlockPos, BlockPos> firstStep = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        firstStep.put(foot, foot);
        open.add(foot);
        while (!open.isEmpty()) {
            BlockPos at = open.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = at.relative(d);
                if (Math.abs(side.getX() - foot.getX()) > radius || Math.abs(side.getZ() - foot.getZ()) > radius) {
                    continue;
                }
                BlockPos next = landing(level, at, side);
                if (next == null || firstStep.containsKey(next)) {
                    continue;
                }
                BlockPos first = at.equals(foot) ? next : firstStep.get(at);
                if (watery(level, next)) {
                    return first;
                }
                firstStep.put(next, first);
                open.add(next);
            }
        }
        return null;
    }

    /** Where a step from {@code at} toward the column {@code side} ends: level, up one, or down one or two. */
    private static BlockPos landing(Level level, BlockPos at, BlockPos side) {
        if (fits(level, side)) {
            for (int down = 0; down <= 2; down++) {
                BlockPos c = side.below(down);
                if (down > 0 && !fits(level, c)) {
                    return null;
                }
                if (watery(level, c) || standable(level, c)) {
                    return c;
                }
            }
            return null;
        }
        BlockPos up = side.above();
        return fits(level, up) && fits(level, at.above(2)) && standable(level, up) ? up : null;
    }

    /** The player's two cells at {@code feet} are free and free of lava. */
    private static boolean fits(Level level, BlockPos feet) {
        return !level.getBlockState(feet).blocksMotion()
                && !level.getBlockState(feet.above()).blocksMotion()
                && !isLava(level, feet)
                && !isLava(level, feet.above());
    }

    private static boolean standable(Level level, BlockPos feet) {
        return level.getBlockState(feet.below()).blocksMotion() && !isLava(level, feet.below());
    }

    private static boolean watery(Level level, BlockPos feet) {
        return level.getFluidState(feet).is(FluidTags.WATER)
                || level.getFluidState(feet.below()).is(FluidTags.WATER);
    }
}
