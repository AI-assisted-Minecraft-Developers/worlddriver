package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.movement.PathSmoothing.*;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 646–837): flight-end / surface-float searchFoot / one-shot goal snap / sticky-dig + dig-aim latches.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickPrelude {

    /** Progress-aware sticky-dig watchdog (see the release block): a dig may
     *  legitimately need 450-3750 CONSECUTIVE ticks under vanilla's stacked
     *  x5 water x5 airborne penalties, so time is bounded only by a generous
     *  backstop; the real release signal is destroyProgress stalling. */
    private static final int STICKY_DIG_STALL_TICKS = 60;
    private static final int STICKY_DIG_ABS_CAP_TICKS = 4000;
    private WalkerTickPrelude() {}

    /**
     * The dig claim's RELEASE POLICY, and the only place it lives.
     *
     * <p>{@code walkerStickyDig}: a planned break, once started, OWNS the tick until the block breaks
     * (or the watchdog/range check bails). Without it, ticks where the break-edge gate flickers
     * (buoyant bob off the within stance, projection jitter) fall through to the travel drive, which
     * releases attack for that tick — and ONE released tick resets vanilla mining progress to zero,
     * so an underwater 25×-slow dig interleaved with travel ticks NEVER completes (C28-J1 DIG-slow:
     * 200t held in aggregate, block still solid, walk-keys showed attack=false travel ticks threaded
     * through the dig). Exclusive aim+attack until done. Default OFF.
     *
     * <p>{@code walkerDigAimPriority} — the NON-exclusive successor (KILLED §66: owning the whole
     * tick starved travel/recovery when the latched block wasn't the way out). Same disease, opposite
     * temperament: the ENTIRE travel tick runs (drive, recovery, repath), then digAimReassert at the
     * end of walkTick re-holds only crosshair+attack — a human holding W+LMB against the wall being
     * dug. Here we only EXPIRE the claim; the re-assert happens after the tick body.
     *
     * <p><b>Release on a stall, not on a clock</b> — the lesson this branch's own predecessor
     * (walkerStickyDig, below) learned and wrote down, and that this successor never inherited. It
     * mattered because of which one runs: digAimPriority defaults ON and stickyDig defaults OFF, so
     * the progress-aware watchdog underneath was dead code and the fixed clock was the only release
     * anyone ever executed.
     *
     * <p>Measured 2026-08-22, real-client ladder, rung 3: a bare-handed bank dig while AFLOAT advances
     * vanilla's destroyProgress by 0.00333/tick — 300 ticks per block, because the ×5 (eye in water)
     * and ×5 (airborne) penalties multiply. The cap was {@code min(breakTimeoutTicks=200, 300)} = 200,
     * so the hold was released 100 ticks before the block could ever break, vanilla zeroed the
     * progress, the walker re-acquired the same cell, and the rung timed out after 8000 ticks having
     * broken nothing. Highest progress ever reached: 0.77. A constant cannot bound a quantity whose
     * scale the terrain decides.
     *
     * <p>The server avatar has no progress sensor ({@code destroyProgress() == -1}), so it keeps the
     * old time box byte for byte: stall-only there would release at 60 ticks and be TIGHTER than what
     * it has today, which is a regression dressed as a fix.
     */
    private static void expireDigClaim(Walker wk, Body a, WorldView world, LivingEntity p) {
        if (!BotConfig.walkerDigAimPriority || wk.stickyDig.pos == null) return;
        float prog = wk.hands.destroyProgress();
        boolean spent;
        if (prog < 0) {
            spent = ++wk.stickyDig.ticks > Math.min(BotConfig.breakTimeoutTicks, 300);
        } else {
            if (prog > wk.stickyDig.lastProgress + 1e-4f) {
                wk.stickyDig.lastProgress = prog;
                wk.stickyDig.stallTicks = 0;
            } else {
                if (prog < wk.stickyDig.lastProgress - 0.05f)
                    wk.stickyDig.lastProgress = prog;   // vanilla re-based it — follow
                wk.stickyDig.stallTicks++;
            }
            spent = wk.stickyDig.stallTicks > STICKY_DIG_STALL_TICKS
                    || ++wk.stickyDig.ticks > STICKY_DIG_ABS_CAP_TICKS;
        }
        // Out of mining reach — measured EYE to block centre against the range the game grants
        // this body, which is the same question ServerPlayerBody.canBreakFromHere asks before
        // it lets a dig happen at all. This used to be `distToCenterSqr(p.position()) > 20`,
        // i.e. from the FEET at a hardcoded ~4.47, and the two are different measurements: a
        // cell 5 below is 5.0 from the feet but 6.6 from the eye (so the latch outlived reach
        // going down — the C31-J1 badlands stall this line was tightened for), and a cell 5
        // above is 5.0 from the feet but only 3.4 from the eye (so it revoked the claim on a
        // block still well in reach, which is exactly the stand-under-and-mine-up case
        // MineProcess.findReachStand scans dy to −5 to create).
        if (!world.isSolid(wk.stickyDig.pos) || spent
                || !eyeWithin(p, wk.stickyDig.pos, blockReachToCentre(p))) {
            if (BotConfig.walkerDebug)
                LOG.info("[walker] dig-aim RELEASE {} solid={} ticks={} stall={} prog={}",
                        wk.stickyDig.pos, world.isSolid(wk.stickyDig.pos), wk.stickyDig.ticks,
                        wk.stickyDig.stallTicks, String.format("%.2f", wk.stickyDig.lastProgress));
            wk.stickyDig.revoke();
        }
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        LivingEntity p = a.entity();
        if (p == null) { wk.lastError = "player vanished"; return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.ERROR, wk.lastError, "failed:" + wk.lastError, null); }
        wk.guardParkourTick = false;
        wk.jumpTag = null;
        wk.aimTag = null;
        wk.driveTag = null;
        // AvatarInput install (client) is handled inside the Body implementation.

        // Steep-barrier planner escalation: a confirmed boxed churn (below) arms a sticky
        // timer; while it's live, route the planner's horizon/soft-commit/depth-penalty
        // reads through their escalated values so A* commits a climb-OVER route instead of
        // re-committing a cheap shallow/cave segment. Off (back to configured defaults)
        // once the timer lapses, so easy-terrain searches are never slowed.
        wk.escal.tick++;
        boolean wasEscalating = BotConfig.pathfinderBoxedEscalate;
        boolean escalating = wk.escal.armed();
        BotConfig.pathfinderBoxedEscalate = escalating;
        if (escalating && !wasEscalating && BotConfig.walkerDebug)
            LOG.info("[walker] steep-barrier escalation ARMED (boxed churn) → horizon=0 depthPenalty>=25 softCommit>=35000 for {} ticks",
                    wk.escal.untilTick - wk.escal.tick);

        // Expectation alarms read LAST tick's pressed state vs THIS tick's world response —
        // run before the per-tick input baseline below clears anything.
        if (BotConfig.walkerExpectAlarm) wk.exAlarms.tick(wk.hands, a, world, p, wk.path, wk.step, wk.stepProg.noStepProgressTicks);

        // Per-tick baseline for the jump/sneak channel: default to "not jumping / not
        // sneaking" so any path that returns without setting them can't leak a stale
        // value — branches below override as needed. (jump only matters on the ground,
        // so a default-false on an airborne tick is a no-op; see AvatarInput.)
        wk.avatarJump(a, false);
        Walker.avatarSneak(a, false);

        // Ground pathfinder: end creative flight so the player descends and
        // the walk/jump actuator (which relies on gravity + onGround) works.
        // While flying the player floats above the ground path, overshoots
        // waypoints frictionlessly, and spins in place re-aiming — then
        // times out hovering. Re-applied each tick so re-toggling flight
        // mid-path can't strand the bot.
        Player flyer = a.asPlayer();   // creative flight is a player's; a mob body has no abilities to end
        if (flyer != null && flyer.getAbilities().flying) {
            flyer.getAbilities().flying = false;
            flyer.onUpdateAbilities();
            if (!wk.descending && BotConfig.walkerDebug)
                LOG.info(
                        "[walker] creative flight detected → disabling, will descend (y={})", p.getY());
            wk.descending = true;
        }
        // Let the post-flight free-fall settle before we path — A* from a
        // mid-air foot finds no neighbours and reports "no path". Scoped to
        // this one-shot descent so the legitimately-airborne ticks of
        // jump / parkour / fall moves are untouched.
        if (wk.descending) {
            if (!p.onGround() && !world.isWater(new BlockPos(
                    (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())))) {
                Walker.avatarForward(a, false);
                wk.avatarJump(a, false);
                p.setSprinting(false);
                if (BotConfig.walkerDebug)
                    LOG.info(
                            "[walker] descending… y={} onGround={}", p.getY(), p.onGround());
                return Walker.Step.WALKING;
            }
            wk.descending = false;
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] landed at y={} → resume ground pathing", p.getY());
        }

        // Water-bucket (MLG) fall execution is owned by the always-on
        // CLUTCH (see BotApiImpl#clientTick): once armed — planned, via
        // CLUTCH.armPlanned below as we step off a fallBucket lip, OR
        // reactively, on any unplanned damaging fall — the clutch runs at the
        // top of clientTick, takes the keys for the whole descent, and returns
        // early so this Walker is suspended until the bot has landed and
        // scooped. So there's nothing to do here; the walk dispatch below only
        // ARMS the planned case and biases the step-off keys.

        BlockPos foot = blockPosOf(p);
        // Search start for a bot floating AT the water surface. foot=floor(p.y) DIPS underwater on a
        // down-bob (p.y 61.64↔62.02 → foot.y 61↔62), so a repath fired mid-down-bob starts A* one
        // cell UNDER the surface; A* prefixes the plan with submerged nodes the buoyant bot can't
        // descend to, those cells sit behind/below the bot, the waypoint never leaves them and the
        // climb-out dig aims BACKWARD (live -733→-540: yaw-locked west digging a wall while the goal
        // is east) → permanent churn (replay: 20 repaths, identical y61-prefix path). Anchor ONLY the
        // search start to the surface cell so the plan extends FORWARD from where the body floats.
        // Global `foot` (actuators/sampling) is untouched. Gated to surface-floating (eye above water)
        // so deep underwater navigation is unaffected.
        // Also for a body still UNDER the surface but within a couple of cells of it: it is on its
        // way up (buoyancy, or a fresh drop into the pool) and every plan from the submerged cell
        // starts with a dig it will never make — wd.clientFlushBankClimbOutEmptyHanded planned a
        // bare-hand break of the stone pool wall from one cell under, wedged three times on it,
        // and only then repathed from the surface to the plain stepUp. Deep water keeps its own
        // start: the lift is capped at two cells, so a diver's route is still planned from where
        // the diver is.
        BlockPos searchFoot = foot;
        if (BotConfig.walkerBuoyantSearchFromSurface && p.isInWater() && !p.onGround()) {
            int sy = foot.getY();
            while (world.isWater(new BlockPos(foot.getX(), sy, foot.getZ()))) sy++;
            // sy = first non-water cell above the column; the top water cell (sy-1) is where the body
            // floats. Clamp >= foot.y so this only ever LIFTS the start, never sinks it.
            int lifted = Math.max(foot.getY(), sy - 1);
            if (!p.isUnderWater() || lifted - foot.getY() <= SURFACE_SEARCH_LIFT_MAX)
                searchFoot = new BlockPos(foot.getX(), lifted, foot.getZ());
        }
        wk.sampleTick(p);
        // One-shot goal snap (needs a live WorldView, so here not in setGoal): a random
        // long-distance goto whose exact target block is UNSTANDABLE — buried in terrain,
        // a sub-stand 1-tall pocket, or under a ceiling — makes A* never accept any node
        // as the goal (goalReached stays false), so it exhausts its whole node budget every
        // repath (~5 s freeze) while the bot oscillates at the unreachable cell and drifts
        // into nearby water (live 2026-06-15: goal (2350,64,1820) was solid stone). Snap the
        // target to the nearest cell that passes the SAME world.canStandAt the pathfinder
        // uses, so planning AND arrival agree and the bot settles at the closest standable
        // spot. Only affects exact-cell Goal.Block whose target is genuinely unstandable;
        // standable targets (every mine/farm/combat stand cell) are left untouched.
        if (!wk.goalSnapChecked && wk.goal instanceof Goal.Block gb && world.isKnown(gb.target())) {
            // Gate on isKnown: at journey start a far goal sits in an UNLOADED chunk, where
            // canStandAt reads void (false) with nothing standable nearby — a one-shot check
            // there would no-op and, with the flag set, never retry once the chunk loads. So
            // defer the single snap until the goal cell's chunk is actually loaded (the bot
            // has come within render range), then evaluate it for real.
            wk.goalSnapChecked = true;
            wk.snapGoalToStandable(world, foot);
        }
        if (wk.goal.reached(foot)) {
            // While mid-pillar-jump the floored feet-Y can tick into the goal
            // cell at the apex before we've placed the block to stand on —
            // declaring ARRIVED there makes the bot abort the last placement
            // and fall back a block. Wait until grounded so it ends up
            // actually standing in the goal cell. Scoped to pillar edges so
            // swim/parkour arrivals (legitimately airborne) are unaffected.
            // A parkour-place leap can put the floored feet-Y into the goal
            // cell mid-arc — like the pillar case — before we've landed on the
            // placed block and braked the overshoot. Hold ARRIVED until grounded
            // so the settle phase can stop the bot ON the block, not past it.
            // A parkour-DESCEND leap likewise puts the floored feet-Y into the
            // lower goal cell mid-arc while still airborne and carrying forward
            // momentum; declaring ARRIVED there skips the landing brake and the
            // bot overshoots the 1-wide block into the void. Hold until grounded.
            Move.Edge ce = wk.edgeAt(wk.step);
            boolean midAirEdge = ce != null && ce.move != null && !p.onGround()
                    && ("pillarUp".equals(ce.move) || ce.move.startsWith("parkourPlace")
                        || ce.move.startsWith("parkourDescend"));
            if (!midAirEdge) return wk.terminalReport(Walker.Step.ARRIVED, PathTrace.Outcome.SUCCESS, null,
                    Walker.classifyArrival(wk.seg.pathBestEffort, wk.goal.reached(foot), wk.goalSnapped), foot);
        }

        expireDigClaim(wk, a, world, p);
        if (BotConfig.walkerStickyDig && !BotConfig.walkerDigAimPriority && wk.stickyDig.pos != null) {
            // Tightened after C31-J1 (-325,64,-47): the 25 (5-block) drift radius held the
            // latch on a cell 5 below the bot — OUTSIDE mining reach (~4.5) — so the latch
            // owned every tick swinging at an unreachable block until the full break
            // timeout while the travel drive was starved (the 85s badlands stall). Release
            // at reach (20 ≈ 4.5²).
            // gap#66: this legacy EXCLUSIVE latch must not run alongside its successor
            // walkerDigAimPriority — with both on, the two release checks each ran
            // ++stickyDig.ticks on the same counter, so the 150t watchdog fired at ~75
            // REAL ticks. A bare-hand stone dig needs 150 CONSECUTIVE held ticks
            // (vanilla zeroes progress on any released tick).
            //
            // PROGRESS-AWARE watchdog (2026-07-21 Mountains live, 23 RELEASE loops at
            // one bank cell): the old fixed 150t cap assumed any reachable block
            // completes inside it — but vanilla stacks x5 (eye in water) and x5
            // (airborne) dig penalties multiplicatively, so a swimAshoreClimb bank
            // dig needs 450t (dirt) to 3750t (stone/wrong tool). The cap released
            // at 151t with the block still solid; vanilla zeroed the progress and
            // the walker re-acquired the SAME cell forever — the exact
            // proxy-metric disease family (time as a proxy for progress) as the
            // descend/drive watchdogs fixed the same day. Now: hold while the
            // REAL vanilla destroyProgress climbs, release on a true stall
            // (STICKY_DIG_STALL_TICKS with no increase) or the absolute backstop
            // (a cyclically-resetting aim never stalls but must not own forever).
            // destroyProgress()==-1 (no reflection / server avatar) degrades to
            // the stall counter alone, which then equals the legacy cap behavior.
            float prog = wk.hands.destroyProgress();
            if (prog > wk.stickyDig.lastProgress + 1e-4f) {
                wk.stickyDig.lastProgress = prog;
                wk.stickyDig.stallTicks = 0;
            } else {
                if (prog >= 0 && prog < wk.stickyDig.lastProgress - 0.05f)
                    wk.stickyDig.lastProgress = prog;   // vanilla reset — re-baseline
                wk.stickyDig.stallTicks++;
            }
            // Same eye-to-centre reach test as expireDigClaim above — see the note there for why
            // the old feet-based `distToCenterSqr(p.position()) > 20` measured a different
            // quantity in both directions.
            if (!world.isSolid(wk.stickyDig.pos)
                    || wk.stickyDig.stallTicks > STICKY_DIG_STALL_TICKS
                    || ++wk.stickyDig.ticks > STICKY_DIG_ABS_CAP_TICKS
                    || !eyeWithin(p, wk.stickyDig.pos, blockReachToCentre(p))) {
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] sticky-dig RELEASE {} solid={} ticks={} stall={} prog={}",
                            wk.stickyDig.pos, world.isSolid(wk.stickyDig.pos), wk.stickyDig.ticks,
                            wk.stickyDig.stallTicks, String.format("%.2f", wk.stickyDig.lastProgress));
                wk.stickyDig.engage(null);
            } else {
                BotConfig.walkerDigActive = true;   // dig-priority: AutoSwim's backstop yields while this hold is live and air is healthy
                wk.hands.selectTool(wk.stickyDig.pos);
                a.aimAtBlock(wk.stickyDig.pos);
                // The drive advances the exact cell regardless of where the crosshair is — while
                // bobbing in water the eye raycast dips behind the bank lip on some ticks, and
                // vanilla's crosshair-driven continueAttack used to retarget and ZERO the progress
                // on those ticks. It cannot any more: the drive makes that pass stand aside (see
                // ClientIntents), so the raycast-miss latch this used to keep is gone with the key.
                wk.hands.breakHold(true);
                wk.hands.continueDestroy(wk.stickyDig.pos);
                wk.digDrivenThisTick = true;
                wk.digKeyOwned = true;
                return Walker.Step.WALKING;
            }
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        cx.frame.p = p;
        cx.frame.foot = foot;
        cx.frame.searchFoot = searchFoot;
        return null;
    }
}
