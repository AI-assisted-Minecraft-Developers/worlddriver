package net.magicterra.worlddriver.bot.control.controller;

import static net.magicterra.worlddriver.config.Mod.LOG;

import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.control.route.PathSmoothing;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.BreakFeasibility;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.BotUtil;
import net.magicterra.worlddriver.game.observe.CellText;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The navigator's one dig door — every navigator dig routes through {@link #avatarDig}; aim at the cell it returns —
 * and the gate that refuses a dig that cannot finish ({@link #breathInfeasibleDig}).
 */
public final class Digging {

    /**
     * Yaw off the body's heading past which a pre-dig would turn the jump rather than tilt the head. Diagonal stair
     * cells sit 27-45° off (live 1388,92,-527 and 1388,100,-534), and air control is too weak for that turn to move the
     * landing off its cell.
     */
    private static final float PRE_DIG_MAX_YAW = 50f;

    /**
     * TTL for a breath-infeasible break cell in {@link ClientWorldView}'s poison set (~60 s): long enough that repeated
     * repaths within the episode route around it, short enough that a later revisit with tools / from dry ground
     * reprices it.
     */
    static final long BREATH_POISON_TTL_MS = 60_000;

    /**
     * Effort ceiling for a single executor dig (~30 s of continuous mining): past this a visible detour always wins,
     * and in water the drift-release + progress zeroing make the true cost effectively unbounded (live: bare-hand
     * floating stone ≈3750t estimated, 0.05 progress per 65-155t drift-released lap). Chosen so a floating bare-hand
     * DIRT climb-out (≈375t) stays allowed while any bare-hand stone-family dig in/over water (≥750t) is refused.
     */
    private static final int HOPELESS_DIG_TICKS = 600;

    private Digging() {}

    /**
     * Break {@code cell}: hold the key AND drive the destroy directly, never one alone. On a client avatar
     * {@link BlockInteraction#breakHold} only rides vanilla's continueAttack pipeline, which a driven client never
     * reaches because the mouse is never grabbed — so the key by itself breaks nothing. Server avatars break on the key
     * and take the destroy as an inherited no-op, which is why every wd.server* dig scene passed for as long as the
     * navigator drove the key alone.
     *
     * <p>THE ONE DOOR. Every navigator dig routes through here, and here is where the cell is decided: the caller's
     * cell is a <i>request</i>, the returned cell is what was actually driven. Vanilla's {@code MultiPlayerGameMode}
     * tracks exactly ONE destroy target, so a phase that drives a second cell does not merely wait its turn — it runs
     * {@code startDestroyBlock} and throws the other phase's accumulated {@code destroyProgress} away. Seven call sites
     * each held their own opinion about whether to claim the slot, whether to claim before or after digging, and which
     * cell to hand the avatar; three of them dug a cell nobody had claimed. Aim at the RETURNED cell.
     *
     * @param selectTool pick the best tool first — only for the sites that did so before the door existed; a site that
     *     never swapped tools must not start now.
     */
    public static BlockPos avatarDig(Navigator nav, Body a, BlockPos cell, boolean selectTool) {
        BlockPos target = cell;
        if (nav != null && cell != null && ControlConfig.navigatorDigAimPriority) {
            nav.prelude.stickyDig.engage(cell);
            if (nav.prelude.stickyDig.pos != null) {
                target = nav.prelude.stickyDig.pos;
            }
        }
        // Tool and crosshair go on the cell actually being driven, never on the cell that was merely
        // requested: vanilla's sameDestroyTarget compares the HELD ITEM as well as the position, so
        // swapping the tool mid-dig throws the progress away exactly the way switching cells does.
        if (selectTool) {
            nav.equipment.selectTool(target);
        }
        a.aimAtBlock(target);
        nav.blocks.breakHold(true);
        nav.blocks.continueDestroy(target);
        if (nav != null) {
            nav.drive.digDrivenThisTick = true;
            nav.drive.digKeyOwned = true;
            nav.tallies.dig(target);
        }
        return target;
    }

    /**
     * Start the next stairUpBreak's dig while still airborne on the step before it. Every finished break leaves
     * vanilla's 5-tick {@code destroyDelay} behind and only a {@code continueDestroyBlock} call counts it down, so a
     * stair cut seconds after the previous one stood still for it before any progress — a player holding the attack key
     * up a cliff burns it on the way. Live R1 cliff: 9 ticks per cut of a 3-tick grass block, five cuts a run.
     * Unclaimed on purpose: the landing tick's actuator claims the same cell, and if the hold lapses first the abort
     * zeroes the progress but not the burned delay.
     */
    public static void preDigNextBreak(Navigator nav, Body a, WorldView world) {
        if (!ControlConfig.navigatorPreDigNextBreak || nav.route.path() == null || nav.prelude.stickyDig.pos != null) {
            return;
        }
        LivingEntity p = a.entity();
        if (p == null || p.onGround() || p.isInWater() || nav.blocks.destroyProgress() < 0) {
            return;
        }
        if (nav.route.step() + 1 >= nav.route.path().size()) {
            return;
        }
        Move.Edge cur = nav.route.edgeAt(nav.route.step());
        Move.Edge next = nav.route.edgeAt(nav.route.step() + 1);
        if (next == null || !"stairUpBreak".equals(next.move) || !next.toPlace.isEmpty()) {
            return;
        }
        if (cur != null && PathSmoothing.hasPendingEdge(world, cur)) {
            return;
        }
        BlockPos b = null;
        for (BlockPos c : next.toBreak) {
            if (world.isSolid(c)) {
                b = c;
                break;
            }
        }
        // Only the stair cell above the landing: one level with it sat beside and behind the body,
        // and turning down to it mid-jump (live 1388,86,-521, pitch 50) missed the landing, 17 ticks.
        if (b == null
                || b.getY() <= nav.route.path().get(nav.route.step()).getY()
                || !BotUtil.eyeWithin(p, b, BotUtil.blockReachToCentre(p))) {
            return;
        }
        double yaw = Math.toDegrees(Math.atan2(-(b.getX() + 0.5 - p.getX()), b.getZ() + 0.5 - p.getZ()));
        if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) > PRE_DIG_MAX_YAW) {
            return;
        }
        nav.equipment.selectTool(b);
        a.aimAtBlock(b);
        nav.blocks.breakHold(true);
        nav.blocks.continueDestroy(b);
        nav.drive.digDrivenThisTick = true;
        nav.drive.digKeyOwned = true;
    }

    /** {@link #avatarDig} for a dig that must not queue: suffocation. Takes the slot, then digs. */
    static BlockPos avatarDigPreempt(Navigator nav, Body a, BlockPos cell, boolean selectTool) {
        if (nav != null) {
            nav.prelude.stickyDig.revoke();
        }
        return avatarDig(nav, a, cell, selectTool);
    }

    /**
     * The breath gate on a break cell, and what to do when it fails: drop the hold, poison the cell so the next search
     * routes around it instead of re-proposing it, and drop the path so the tick ends in a repath. Two dig sites had
     * these eight lines written out verbatim.
     *
     * @return true when the caller must return {@link TickStep#WALKING} at once.
     */
    static boolean bailOnBreathInfeasibleDig(Navigator nav, Body a, LivingEntity p, BlockPos b) {
        if (!breathInfeasibleDig(p, b)) {
            return false;
        }
        nav.blocks.breakHold(false);
        BreakFeasibility.poison(b, BREATH_POISON_TTL_MS);
        if (GameConfig.navigatorDebug) {
            LOG.info("[navigator] breath-infeasible dig {} — poisoned {}s, repathing", b, BREATH_POISON_TTL_MS / 1000);
        }
        nav.error = "breath-infeasible dig at " + CellText.of(b);
        nav.route.drop(nav.error);
        return true;
    }

    /**
     * HOPELESS-DIG GATE (2026-07-21 live, flooded Mountains channel): estimate the dig with vanilla's own
     * {@code getDestroyProgress} (already includes eyes-in-water ÷5, off-ground ÷5, and the currently held tool) and
     * refuse to start digs that provably cannot pay off. Two prongs:
     *
     * <ol>
     *   <li><b>Effort ceiling</b> (any stance): past {@link #HOPELESS_DIG_TICKS} a single block costs more than any
     *       visible detour (bare-hand floating stone ≈3750t; live: prog 0.05 per 65-155t lap, drift-released and zeroed
     *       every lap — an unbounded sink). Mirrors the planner's wrong-tool aversion on the executor side, where
     *       stub/escape adoptions used to sneak past pricing.
     *   <li><b>Breath box</b> (DEEP water only — water well above the head, same test as AutoSwim's deep-ascent):
     *       vanilla zeroes destroyProgress on any interruption and a fully-submerged dig gets one breath of
     *       uninterrupted work (maxAir − drownEscape floor − reserve ≈ 180t); longer digs can NEVER complete.
     *       Surface-bobbing digs refill air at the bob peaks, so they are judged by the effort ceiling alone.
     * </ol>
     *
     * Callers poison the cell (TTL) so the very next search routes around.
     *
     * <p>CLIENT-SIDE ONLY: the estimate models the vanilla client mining loop (per-tick destroyProgress, zeroed on
     * interruption). Server avatars break through their own simplified fast path, so gating them on the vanilla
     * estimate refused digs their executor completes easily (t0 wd.buoyantWall regression: bare-hand +5 stone wall the
     * avatar mounts fine).
     */
    static boolean breathInfeasibleDig(LivingEntity p, BlockPos b) {
        // getDestroyProgress prices the dig for a Player's tool and stance; the client-only
        // guard above already restricts this to the local player, so a non-player falls
        // through to the same "not estimated" answer a server-side player gets.
        if (!p.level().isClientSide() || !(p instanceof Player pl)) {
            return false;
        }
        float dmg = p.level().getBlockState(b).getDestroyProgress(pl, p.level(), b);
        if (dmg >= 1f) {
            return false; // instant-mine — always fits
        }
        if (dmg <= 0f) {
            return true; // unbreakable from here
            // STANCE NORMALIZATION (2026-07-21 live, shoreline poison storm): the live
            // estimate bakes in THIS tick's stance — a bobbing or jumping player eats vanilla's
            // off-ground ÷5 and eyes-in-water ÷5 — but the poison it justifies lasts 60 s
            // and outlives the stance. Live: the same stone bank cell is 150t dug grounded
            // ashore yet 750-3750t sampled mid-bob, so the gate carpet-poisoned the whole
            // climb-out shoreline while floating and the freshly-landed bot then had no
            // priced route into the hill — it stopped digging and rammed the bank instead.
            // Hopelessness must be judged from the best REACHABLE digging stance:
            //  - off-ground ÷5 always undone (the bot can always ground — ashore or on
            //    the basin floor);
            //  - eyes-in-water ÷5 undone when the target sits ABOVE the waterline (a dry
            //    stance beside it is reachable; if the player actually has aqua affinity
            //    this overcorrects toward permissive, which is harmless).
        }
        float norm = 1f;
        if (!p.onGround()) {
            norm *= 5f;
        }
        boolean targetDry = !p.level().getFluidState(b.above()).is(net.minecraft.tags.FluidTags.WATER);
        if (targetDry && p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
            norm *= 5f;
        }
        if (Math.ceil(1f / (dmg * norm)) > HOPELESS_DIG_TICKS) {
            return true; // never worth it from ANY stance
        }
        if (!p.isUnderWater()) {
            return false;
        }
        // Breath box uses the LIVE estimate: it asks "can THIS submerged dig finish
        // on this breath", and down here the wet stance is the real one.
        BlockPos foot = p.blockPosition();
        boolean deep = p.level().getFluidState(foot.above(2)).is(net.minecraft.tags.FluidTags.WATER);
        return deep && Math.ceil(1f / dmg) > p.getMaxAirSupply() - ControlConfig.drownEscapeAirThreshold - 20;
    }
}
