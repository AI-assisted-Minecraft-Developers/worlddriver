package net.magicterra.worlddriver.bot.auto;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.util.BotUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

/**
 * Contact-damage escape reflex (death #14, live 2026-07-20). A {@code goto y=16}
 * across desert hugged a cactus cluster; the executor's body drift overlapped the
 * neighbouring cactus and contact damage re-landed every ~10 ticks — HP 11→0 in
 * ~20 s. The LLM controller reacted "correctly" (cancel at HP 5, run_away at 2)
 * and still lost: its 1–6 s turn latency can never beat a 2 Hz damage tick. And
 * no existing reflex covers this: hurt-entry retreat/combat ride ENTITY damage
 * attribution (gap#55/#65), AntiSuffocate rides {@code inWall}, DrownEscape rides
 * air — a damaging BLOCK touching the hull had nothing.
 *
 * <p>Mechanics: sibling of {@link AntiSuffocate} — an unconditional client-tick
 * reflex run after the scheduler, NOT a movement chain. While
 * {@link ContactEscapeGate#shouldTrigger} is hot (fresh damage from a
 * step-away-able block type), face directly away from the nearest hazard block
 * touching the hull and hold forward (jump on collision, vanilla step-up), then
 * linger a few ticks to get fully clear and hand control straight back. Overrides
 * an active walker's keys only during the episode — same "override while dying,
 * then hand back" contract as AntiSuffocate.
 *
 * <p>Idle-passivity: this moves the bot horizontally with no command, which the
 * idle-passive contract normally forbids. Same carve-out P1 drew for hurt-entry
 * retreat and gap#70 drew for the drowning float: REACTING TO TAKING DAMAGE is a
 * survival reflex, not autonomous wandering. (An idle bot standing against a
 * cactus after a cancel() was exactly the second half of death #14.)
 */
public final class ContactDamageEscape {
    private ContactDamageEscape() {}

    /** Ticks to keep walking after the damage gate clears — bridges the ≤10-tick
     *  hurtTime gap between contact hits and puts a full block between hull and
     *  hazard before handing back. */
    private static final int LINGER_TICKS = 15;
    /** Hard episode cap. Contact damage that survives this long means we are
     *  wedged (pit walled by cacti); give the channel back rather than grind —
     *  the gate re-triggers on the next hit, so a live episode restarts at worst
     *  10 ticks later, and the planner/critic see the hurt telemetry meanwhile. */
    private static final int MAX_EPISODE_TICKS = 100;

    private static boolean active;
    private static int linger;
    private static int episodeTicks;
    private static BlockPos lastHazard;

    /** @return true if it drove the escape this tick (keys are ours). */
    public static boolean tick(Minecraft mc, LocalPlayer p) {
        if (mc.level == null || p == null) { reset(mc, "level-gone"); return false; }
        DamageSource src = p.getLastDamageSource();
        String msgId = src != null ? src.getMsgId() : null;
        boolean hot = ContactEscapeGate.shouldTrigger(
                msgId, p.hurtTime, BotConfig.contactDamageEscape);

        if (!active) {
            if (!hot) return false;
            active = true;
            episodeTicks = 0;
            lastHazard = null;
            LOG.info("[contactEscape] {} damage (hp={}) at {},{},{} → stepping out of contact",
                    msgId, String.format("%.1f", p.getHealth()),
                    (int) p.getX(), (int) p.getY(), (int) p.getZ());
        }
        if (hot) linger = LINGER_TICKS;
        else if (--linger <= 0) { reset(mc, "clear"); return false; }
        if (++episodeTicks > MAX_EPISODE_TICKS) { reset(mc, "episode-cap"); return false; }

        BlockPos hazard = nearestHazard(mc.level, p);
        if (hazard != null) lastHazard = hazard;
        Vec3 me = p.position();
        double dx, dz;
        if (lastHazard != null) {
            dx = me.x - (lastHazard.getX() + 0.5);
            dz = me.z - (lastHazard.getZ() + 0.5);
        } else {
            dx = 0; dz = 0;   // desynced client: no hazard visible — cardinal fallback
        }
        if (Math.hypot(dx, dz) < 0.35) {
            // Standing IN the hazard (berry bush / fire) or it is directly BELOW
            // (magma floor): away-vector is degenerate — pick a clear cardinal.
            Direction d = pickClearCardinal(mc.level, p);
            if (d != null) { dx = d.getStepX(); dz = d.getStepZ(); }
            else { dx = 1; dz = 0; }  // fully ringed: any push beats standing still
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYRot(yaw);
        p.setXRot(0f);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.horizontalCollision);
        return true;
    }

    /** Nearest step-away-able hazard block the hull could be touching: the foot
     *  cell and the cell below it (magma / fire carpet), plus the 8 horizontal
     *  neighbours at foot and head height (a 0.6-wide hull can graze a diagonal
     *  cactus at a hugged corner). */
    private static BlockPos nearestHazard(Level lvl, LocalPlayer p) {
        BlockPos foot = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos cand : candidates(foot)) {
            if (!isContactHazard(lvl, cand)) continue;
            double d = cand.distToCenterSqr(p.getX(), p.getY(), p.getZ());
            if (d < bestD) { bestD = d; best = cand; }
        }
        return best;
    }

    private static Iterable<BlockPos> candidates(BlockPos foot) {
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>(18);
        out.add(foot);
        out.add(foot.below());
        BlockPos head = foot.above();
        for (int ox = -1; ox <= 1; ox++)
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                out.add(foot.offset(ox, 0, oz));
                out.add(head.offset(ox, 0, oz));
            }
        return out;
    }

    private static boolean isContactHazard(Level lvl, BlockPos pos) {
        BlockState s = lvl.getBlockState(pos);
        return BotUtil.HAZARD_BLOCKS.contains(s.getBlock()) || s.is(BlockTags.FIRE);
    }

    /** First horizontal direction whose foot+head cells are passable and
     *  hazard-free; prefer one that is also standable (solid floor) so the
     *  escape step does not walk off a ledge. */
    private static Direction pickClearCardinal(Level lvl, LocalPlayer p) {
        BlockPos foot = p.blockPosition();
        Direction firstClear = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos f = foot.relative(d);
            if (isContactHazard(lvl, f) || isContactHazard(lvl, f.above())) continue;
            if (!passable(lvl, f) || !passable(lvl, f.above())) continue;
            if (firstClear == null) firstClear = d;
            if (!passable(lvl, f.below())) return d;   // solid floor → best
        }
        return firstClear;
    }

    private static boolean passable(Level lvl, BlockPos pos) {
        return lvl.getBlockState(pos).getCollisionShape(lvl, pos).isEmpty();
    }

    private static void reset(Minecraft mc, String outcome) {
        if (active) {
            LOG.info("[contactEscape] episode end ({}) after {}t — last hazard {}",
                    outcome, episodeTicks,
                    lastHazard == null ? "unseen" : lastHazard.toShortString());
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
        }
        active = false;
        linger = 0;
        episodeTicks = 0;
        lastHazard = null;
    }
}
