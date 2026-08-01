package net.magicterra.worlddriver.bot.auto;

import net.magicterra.worlddriver.bot.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

/**
 * Lava-front escape reflex (devil-bench iron ep-014/#27, ep-025/#29, ep-026/#30
 * — three lava deaths in one day, each a different last metre of the same
 * disease: a breached pocket's FLOW FRONT advances ~1 cell / 1.5 s while every
 * existing layer assumes static hazards).
 *
 * <p>The walker's path-hazard brake (ep-014 fix) stops the bot from WALKING
 * into flowed lava, and the fall-edge margin (ep-025 fix) refuses drops beside
 * a front — but ep-026 died STANDING STILL: the brake vetoed every forward
 * move, the repath loop kept it in place, and the front arrived. What was
 * missing is the reflex a human plays by feel: lava creeping next to your feet
 * means you WALK AWAY NOW and think afterwards.
 *
 * <p>Mechanics: sibling of {@link ContactDamageEscape} — an unconditional
 * client-tick reflex run after the scheduler, not a movement chain. Trigger:
 * the bot is IN lava, or a FLOWING (non-source) lava cell sits in the foot
 * 8-neighbourhood (or one below it — the front eats the floor first). Static
 * source pools do NOT trigger: mining beside a calm lava lake is normal iron-
 * country work and the planner already prices it. Drive: face directly away
 * from the nearest triggering cell and hold forward (jump on collision),
 * preferring a hazard-free cardinal when the away-vector is degenerate or
 * itself blocked; linger a few ticks past the last sighting, hard-capped per
 * episode so a fully-ringed bot hands the channel back to the planner.
 */
public final class LavaProximityEscape {
    private LavaProximityEscape() {}

    private static final int LINGER_TICKS = 12;
    private static final int MAX_EPISODE_TICKS = 80;

    private static boolean active;
    private static int linger;
    private static int episodeTicks;
    private static BlockPos lastLava;

    /** @return true if it drove the escape this tick (keys are ours). */
    public static boolean tick(Minecraft mc, LocalPlayer p) {
        if (mc.level == null || p == null || !BotConfig.lavaProximityEscape) {
            reset("off");
            return false;
        }
        BlockPos threat = nearestThreat(mc.level, p);
        boolean hot = threat != null || p.isInLava();
        if (!active) {
            if (!hot) return false;
            active = true;
            episodeTicks = 0;
            lastLava = null;
            LOG.info("[lavaEscape] {} at {},{},{} (hp={}) → walking away",
                    p.isInLava() ? "IN lava" : "flow front adjacent " + threat.toShortString(),
                    (int) p.getX(), (int) p.getY(), (int) p.getZ(),
                    String.format("%.1f", p.getHealth()));
        }
        if (hot) linger = LINGER_TICKS;
        else if (--linger <= 0) { reset("clear"); return false; }
        if (++episodeTicks > MAX_EPISODE_TICKS) { reset("episode-cap"); return false; }

        if (threat != null) lastLava = threat;
        Vec3 me = p.position();
        double dx = 0, dz = 0;
        if (lastLava != null) {
            dx = me.x - (lastLava.getX() + 0.5);
            dz = me.z - (lastLava.getZ() + 0.5);
        }
        // Degenerate (standing in it / directly below), the away cell is
        // itself lava, or it has NO SOLID FLOOR (death #31: the escape run
        // walked off a ledge into the pool below — trading a front for a
        // fall): fall back to the first clear cardinal, which prefers floors.
        BlockPos awayCell = BlockPos.containing(me.x + Math.signum(dx),
                                                me.y, me.z + Math.signum(dz));
        boolean badVector = Math.hypot(dx, dz) < 0.35
                || isLava(mc.level, awayCell)
                || isLava(mc.level, awayCell.below())
                || !mc.level.getBlockState(awayCell.below()).blocksMotion();
        if (badVector) {
            Direction d = pickClearCardinal(mc.level, p);
            if (d != null) { dx = d.getStepX(); dz = d.getStepZ(); }
            else { dx = 1; dz = 0; }   // fully ringed: any push beats standing still
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYRot(yaw);
        p.setXRot(0f);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.horizontalCollision || p.isInLava());
        return true;
    }

    /** Nearest FLOWING lava cell in the foot 8-neighbourhood or the ring one
     *  below it (the front eats the floor level first). The own/below cells
     *  count regardless of source-ness — standing on or in any lava is hot. */
    private static BlockPos nearestThreat(Level lvl, LocalPlayer p) {
        BlockPos foot = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++)
            for (int oz = -1; oz <= 1; oz++)
                for (int oy = -1; oy <= 0; oy++) {
                    BlockPos cand = foot.offset(ox, oy, oz);
                    boolean own = ox == 0 && oz == 0;
                    FluidState fs = lvl.getFluidState(cand);
                    if (!fs.is(FluidTags.LAVA)) continue;
                    if (!own && fs.isSource()) continue;   // calm pool: not a front
                    double d = cand.distToCenterSqr(p.getX(), p.getY(), p.getZ());
                    if (d < bestD) { bestD = d; best = cand; }
                }
        return best;
    }

    private static boolean isLava(Level lvl, BlockPos pos) {
        return lvl.getFluidState(pos).is(FluidTags.LAVA);
    }

    /** First horizontal direction whose foot+head are passable and lava-free
     *  with a standable floor — prefer not trading lava for a ledge. */
    private static Direction pickClearCardinal(Level lvl, LocalPlayer p) {
        BlockPos foot = p.blockPosition();
        Direction firstClear = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos f = foot.relative(d);
            if (isLava(lvl, f) || isLava(lvl, f.above()) || isLava(lvl, f.below())) continue;
            if (lvl.getBlockState(f).blocksMotion()
                    || lvl.getBlockState(f.above()).blocksMotion()) continue;
            if (firstClear == null) firstClear = d;
            if (lvl.getBlockState(f.below()).blocksMotion()) return d;   // solid floor
        }
        return firstClear;
    }

    private static void reset(String why) {
        if (active) LOG.info("[lavaEscape] handing back ({}), {} ticks", why, episodeTicks);
        active = false;
        linger = 0;
        episodeTicks = 0;
        lastLava = null;
    }
}
