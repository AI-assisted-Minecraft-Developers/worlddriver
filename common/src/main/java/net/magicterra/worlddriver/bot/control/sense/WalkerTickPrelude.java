package net.magicterra.worlddriver.bot.control.sense;

import net.magicterra.worlddriver.bot.control.steer.DriveLatches;
import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.bot.control.controller.StickyDig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.*;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.bot.control.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.control.WalkerGeometry.*;
import net.magicterra.worlddriver.bot.control.WalkVerdict;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerTickCtx;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 646–837): flight-end / surface-float searchFoot / one-shot goal snap / sticky-dig + dig-aim latches.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
public final class WalkerTickPrelude {
    /** Sticky planned-break dig (task#96 step B7): held across ticks so an interleaved
     *  travel tick can't release attack (a single released tick resets vanilla mining
     *  progress to zero; C28-J1 DIG-slow @-258,81,338: 200t of dig ticks interleaved with
     *  attack=false travel ticks never completed one block). Engaged by Climb/Drive,
     *  released by WalkerTickPrelude when the planned break is gone; deliberately NO
     *  journey reset (release is world-state-driven). */
    public final StickyDig stickyDig = new StickyDig();

    public boolean descending;       // ending creative flight; wait to land before pathing

    /** Forget what this phase keeps for the walk, as far as {@code scope} reaches. */
    public void reset(ResetScope scope) {
        descending = false;
    }

    /** Progress-aware sticky-dig watchdog (see the release block): a dig may
     *  legitimately need 450-3750 CONSECUTIVE ticks under vanilla's stacked
     *  x5 water x5 airborne penalties, so time is bounded only by a generous
     *  backstop; the real release signal is destroyProgress stalling. */
    private static final int STICKY_DIG_STALL_TICKS = 60;
    private static final int STICKY_DIG_ABS_CAP_TICKS = 4000;

    /**
     * The dig claim's RELEASE POLICY, and the only place it lives.
     *
     * <p>{@code walkerDigAimPriority}: ONE released tick resets vanilla mining progress to zero, so an
     * underwater 25×-slow dig interleaved with travel ticks never completes (C28-J1 DIG-slow: 200t held in
     * aggregate, block still solid). An earlier latch owned the whole tick until the block broke, and starved
     * travel and recovery when the latched block was not the way out. This one lets the entire travel tick
     * run, then re-holds only crosshair and attack at the end of Drive — a human holding W+LMB against the
     * wall being dug. Here we only EXPIRE the claim.
     *
     * <p><b>Release on a stall, not on a clock</b> — the lesson the exclusive latch learned and wrote down,
     * and that this successor did not inherit at first.
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
    private void expireDigClaim(Walker wk, Body a, WorldView world, LivingEntity p) {
        if (!ControlConfig.walkerDigAimPriority || stickyDig.pos == null) return;
        float prog = wk.blocks.destroyProgress();
        boolean spent;
        if (prog < 0) {
            spent = ++stickyDig.ticks > Math.min(ControlConfig.breakTimeoutTicks, 300);
        } else {
            if (prog > stickyDig.lastProgress + 1e-4f) {
                stickyDig.lastProgress = prog;
                stickyDig.stallTicks = 0;
            } else {
                if (prog < stickyDig.lastProgress - 0.05f)
                    stickyDig.lastProgress = prog;   // vanilla re-based it — follow
                stickyDig.stallTicks++;
            }
            spent = stickyDig.stallTicks > STICKY_DIG_STALL_TICKS
                    || ++stickyDig.ticks > STICKY_DIG_ABS_CAP_TICKS;
        }
        // Out of mining reach — measured EYE to block centre against the range the game grants
        // this bot, which is the same question ServerPlayerBody.canBreakFromHere asks before
        // it lets a dig happen at all. This used to be `distToCenterSqr(p.position()) > 20`,
        // i.e. from the FEET at a hardcoded ~4.47, and the two are different measurements: a
        // cell 5 below is 5.0 from the feet but 6.6 from the eye (so the latch outlived reach
        // going down — the C31-J1 badlands stall this line was tightened for), and a cell 5
        // above is 5.0 from the feet but only 3.4 from the eye (so it revoked the claim on a
        // block still well in reach, which is exactly the stand-under-and-mine-up case
        // MineProcess.findReachStand scans dy to −5 to create).
        if (!world.isSolid(stickyDig.pos) || spent
                || !eyeWithin(p, stickyDig.pos, blockReachToCentre(p))) {
            if (GameConfig.walkerDebug)
                LOG.info("[walker] dig-aim RELEASE {} solid={} ticks={} stall={} prog={}",
                        stickyDig.pos, world.isSolid(stickyDig.pos), stickyDig.ticks,
                        stickyDig.stallTicks, String.format("%.2f", stickyDig.lastProgress));
            stickyDig.revoke();
        }
    }

    /** The tick's foot during a cruise hop ({@code WalkerTickDrive.cruiseHop}): its apex lifts the feet 1.25 over
     *  the level it left, so floor(y) read a row up and every "node below the foot" branch (aim at the node, the
     *  descent hold) took the heading over mid-flight and swung it 40° (R1 1422,64,-403). Until it lands the hop is
     *  judged from its take-off level. The keying tick itself may still be grounded, so landing counts after it. */
    BlockPos hopFoot(Walker wk, LivingEntity p, BlockPos foot) {
        DriveLatches l = wk.drive.driveLatch;
        if (l.hopY == Integer.MIN_VALUE) return foot;
        if (p.isInWater() || foot.getY() < l.hopY || p.onGround() && p.tickCount > l.hopTick + 1) {
            l.hopY = Integer.MIN_VALUE;
            return foot;
        }
        return foot.getY() > l.hopY ? new BlockPos(foot.getX(), l.hopY, foot.getZ()) : foot;
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    public TickStep run(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        LivingEntity p = a.entity();
        if (p == null) { wk.error = "player vanished"; return wk.terminalReport(TickStep.FAILED, PathTrace.Outcome.ERROR, wk.error, "failed:" + wk.error, null); }
        wk.safety.guardParkourTick = false;
        wk.jumpTag = null;
        wk.aimTag = null;
        wk.driveTag = null;
        // AvatarInput install (client) is handled inside the {@code Body} implementation.

        // Steep-barrier planner escalation: a confirmed boxed churn (below) arms a sticky
        // timer; while it's live, this walker's finders read the escalated horizon/soft-commit/
        // depth-penalty (PathTuning.escalatedWhen) so A* commits a climb-OVER route instead of
        // re-committing a cheap shallow/cave segment. Off (back to configured defaults)
        // once the timer lapses, so easy-terrain searches are never slowed.
        wk.stallDetect.escal.tick++;
        boolean wasEscalating = wk.stallDetect.escal.wasArmed;
        boolean escalating = wk.stallDetect.escal.armed();
        wk.stallDetect.escal.wasArmed = escalating;
        if (escalating && !wasEscalating && GameConfig.walkerDebug)
            LOG.info("[walker] steep-barrier escalation ARMED (boxed churn) → horizon=0 depthPenalty>=25 softCommit>=35000 for {} ticks",
                    wk.stallDetect.escal.untilTick - wk.stallDetect.escal.tick);

        // Expectation alarms read LAST tick's pressed state vs THIS tick's world response —
        // run before the per-tick input baseline below clears anything.
        if (ControlConfig.walkerExpectAlarm) wk.exAlarms.tick(wk.blocks, a, world, p, wk.route.path(), wk.route.step(), wk.progress.stepProg.noStepProgressTicks);

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
        Player flyer = a.asPlayer();   // creative flight is a player's; a mob has no abilities to end
        if (flyer != null && flyer.getAbilities().flying) {
            flyer.getAbilities().flying = false;
            flyer.onUpdateAbilities();
            if (!descending && GameConfig.walkerDebug)
                LOG.info(
                        "[walker] creative flight detected → disabling, will descend (y={})", p.getY());
            descending = true;
        }
        // Let the post-flight free-fall settle before we path — A* from a
        // mid-air foot finds no neighbours and reports "no path". Scoped to
        // this one-shot descent so the legitimately-airborne ticks of
        // jump / parkour / fall moves are untouched.
        if (descending) {
            if (!p.onGround() && !world.isWater(new BlockPos(
                    (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())))) {
                Walker.avatarForward(a, false);
                wk.avatarJump(a, false);
                p.setSprinting(false);
                if (GameConfig.walkerDebug)
                    LOG.info(
                            "[walker] descending… y={} onGround={}", p.getY(), p.onGround());
                return TickStep.WALKING;
            }
            descending = false;
            if (GameConfig.walkerDebug)
                LOG.info(
                        "[walker] landed at y={} → resume ground pathing", p.getY());
        }

        // Water-bucket (MLG) fall execution is owned by the bot's always-on
        // ClutchController: once armed — planned, via the MoveSignals.planClutch
        // the edge guards send as we step off a fallBucket lip, OR reactively,
        // on any unplanned damaging fall — the clutch runs at the top of the
        // bot's tick, takes the keys for the whole descent, and returns
        // early so this Walker is suspended until the bot has landed and
        // scooped. So there's nothing to do here; the walk dispatch below only
        // SIGNALS the planned case and biases the step-off keys.

        BlockPos foot = hopFoot(wk, p, blockPosOf(p));
        // Search start for a bot floating AT the water surface. foot=floor(p.y) DIPS underwater on a
        // down-bob (p.y 61.64↔62.02 → foot.y 61↔62), so a repath fired mid-down-bob starts A* one
        // cell UNDER the surface; A* prefixes the plan with submerged nodes the buoyant bot can't
        // descend to, those cells sit behind/below the bot, the waypoint never leaves them and the
        // climb-out dig aims BACKWARD (live -733→-540: yaw-locked west digging a wall while the goal
        // is east) → permanent churn (replay: 20 repaths, identical y61-prefix path). Anchor ONLY the
        // search start to the surface cell so the plan extends FORWARD from where the bot floats.
        // Global `foot` (actuators/sampling) is untouched. Gated to surface-floating (eye above water)
        // so deep underwater navigation is unaffected.
        // Also for a bot still UNDER the surface but within a couple of cells of it: it is on its
        // way up (buoyancy, or a fresh drop into the pool) and every plan from the submerged cell
        // starts with a dig it will never make — wd.clientFlushBankClimbOutEmptyHanded planned a
        // bare-hand break of the stone pool wall from one cell under, wedged three times on it,
        // and only then repathed from the surface to the plain stepUp. Deep water keeps its own
        // start: the lift is capped at two cells, so a diver's route is still planned from where
        // the diver is.
        BlockPos searchFoot = foot;
        if (ControlConfig.walkerBuoyantSearchFromSurface && p.isInWater() && !p.onGround()) {
            int sy = foot.getY();
            while (world.isWater(new BlockPos(foot.getX(), sy, foot.getZ()))) sy++;
            // sy = first non-water cell above the column; the top water cell (sy-1) is where the bot
            // floats. Clamp >= foot.y so this only ever LIFTS the start, never sinks it.
            int lifted = Math.max(foot.getY(), sy - 1);
            if (!p.isUnderWater() || lifted - foot.getY() <= SURFACE_SEARCH_LIFT_MAX)
                searchFoot = new BlockPos(foot.getX(), lifted, foot.getZ());
        }
        // A search fired at a jump's apex starts one cell above the floor, and a plan opening with a
        // pillarUp from there places into the cell the body already fills: live 1446,79,-553 jumped
        // 4 s without placing once. The cell below is where the jump lands.
        if (ControlConfig.walkerSearchFromLanding && !p.onGround() && !p.isInWater() && !p.onClimbable()
                && !world.canStandAt(foot) && world.canStandAt(foot.below()))
            searchFoot = foot.below();
        wk.sampleTick(p);
        // One-shot goal snap (needs a live WorldView, so here not in start): a random
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
            Move.Edge ce = wk.edgeAt(wk.route.step());
            boolean midAirEdge = ce != null && ce.move != null && !p.onGround()
                    && ("pillarUp".equals(ce.move) || ce.move.startsWith("parkourPlace")
                        || ce.move.startsWith("parkourDescend"));
            if (!midAirEdge) return wk.terminalReport(TickStep.ARRIVED, PathTrace.Outcome.SUCCESS, null,
                    WalkVerdict.classifyArrival(wk.route.seg.pathBestEffort, wk.goal.reached(foot), wk.goalSnapped), foot);
        }

        expireDigClaim(wk, a, world, p);
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        cx.frame.p = p;
        cx.frame.foot = foot;
        cx.frame.searchFoot = searchFoot;
        return null;
    }
}
