package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.agent.bot.pathfinder.Capability;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.pathfinder.SearchProfile;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.constraints.NoBreak;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.SurvivalMath;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.movement.PathSmoothing.*;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.movement.WalkerConstants.*;
import static net.magicterra.agent.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 646–837): flight-end / surface-float searchFoot / one-shot goal snap / sticky-dig + dig-aim latches.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickPrelude {
    private WalkerTickPrelude() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Avatar a, WorldView world) {
        Player p = a.player();
        if (p == null) { wk.lastError = "player vanished"; return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.ERROR, wk.lastError, "failed:" + wk.lastError, null); }
        wk.guardParkourTick = false;
        // AgentInput install (client) is handled inside the Avatar implementation.

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
        if (BotConfig.walkerExpectAlarm) wk.exAlarms.tick(a, world, p, wk.path, wk.step, wk.stepProg.noStepProgressTicks);

        // Per-tick baseline for the jump/sneak channel: default to "not jumping / not
        // sneaking" so any path that returns without setting them can't leak a stale
        // value — branches below override as needed. (jump only matters on the ground,
        // so a default-false on an airborne tick is a no-op; see AgentInput.)
        Walker.agentJump(a, false);
        Walker.agentSneak(a, false);

        // Ground pathfinder: end creative flight so the player descends and
        // the walk/jump actuator (which relies on gravity + onGround) works.
        // While flying the player floats above the ground path, overshoots
        // waypoints frictionlessly, and spins in place re-aiming — then
        // times out hovering. Re-applied each tick so re-toggling flight
        // mid-path can't strand the bot.
        if (p.getAbilities().flying) {
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
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
                Walker.agentForward(a, false);
                Walker.agentJump(a, false);
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

        BlockPos foot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        // Search start for a bot floating AT the water surface. foot=floor(p.y) DIPS underwater on a
        // down-bob (p.y 61.64↔62.02 → foot.y 61↔62), so a repath fired mid-down-bob starts A* one
        // cell UNDER the surface; A* prefixes the plan with submerged nodes the buoyant bot can't
        // descend to, those cells sit behind/below the bot, the waypoint never leaves them and the
        // climb-out dig aims BACKWARD (live -733→-540: yaw-locked west digging a wall while the goal
        // is east) → permanent churn (replay: 20 repaths, identical y61-prefix path). Anchor ONLY the
        // search start to the surface cell so the plan extends FORWARD from where the body floats.
        // Global `foot` (actuators/sampling) is untouched. Gated to surface-floating (eye above water)
        // so deep underwater navigation is unaffected.
        BlockPos searchFoot = foot;
        if (BotConfig.walkerBuoyantSearchFromSurface
                && p.isInWater() && !p.onGround() && !p.isUnderWater()) {
            int sy = foot.getY();
            while (world.isWater(new BlockPos(foot.getX(), sy, foot.getZ()))) sy++;
            // sy = first non-water cell above the column; the top water cell (sy-1) is where the body
            // floats. Clamp >= foot.y so this only ever LIFTS the start, never sinks it.
            searchFoot = new BlockPos(foot.getX(), Math.max(foot.getY(), sy - 1), foot.getZ());
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
                    Walker.classifyArrival(wk.pathBestEffort, wk.goal.reached(foot), wk.goalSnapped), foot);
        }

        // walkerStickyDig: a planned break, once started, OWNS the tick until the block
        // breaks (or the watchdog/range check bails). Without this, ticks where the
        // break-edge gate flickers (buoyant bob off the within stance, projection jitter)
        // fall through to the travel drive, which releases attack for that tick — and ONE
        // released tick resets vanilla mining progress to zero, so an underwater 25×-slow
        // dig interleaved with travel ticks NEVER completes (C28-J1 DIG-slow: 200t held
        // in aggregate, block still solid, walk-keys showed attack=false travel ticks
        // threaded through the dig). Exclusive aim+attack until done; solid-gone or
        // timeout or drifted-away clears it. Default OFF.
        // walkerDigAimPriority — the NON-exclusive successor to stickyDig (KILLED §66:
        // owning the whole tick starved travel/recovery when the latched block wasn't
        // the way out). Same disease, opposite temperament: the ENTIRE travel tick runs
        // (drive, recovery, repath), then digAimReassert at the end of walkTick re-holds
        // ONLY crosshair+attack — a human holding W+LMB against the wall being dug.
        // Here we just expire the latch; the re-assert happens after the tick body.
        if (BotConfig.walkerDigAimPriority && wk.stickyDig.pos != null
                && (!world.isSolid(wk.stickyDig.pos)
                    || ++wk.stickyDig.ticks > Math.min(BotConfig.breakTimeoutTicks, 300)
                    || wk.stickyDig.pos.distToCenterSqr(p.position()) > 20)) {
            if (BotConfig.walkerDebug)
                LOG.info("[walker] dig-aim RELEASE {} solid={} ticks={}",
                        wk.stickyDig.pos, world.isSolid(wk.stickyDig.pos), wk.stickyDig.ticks);
            wk.stickyDig.pos = null;
            wk.stickyDig.ticks = 0;
        }
        if (BotConfig.walkerStickyDig && !BotConfig.walkerDigAimPriority && wk.stickyDig.pos != null) {
            // Tightened after C31-J1 (-325,64,-47): the 25 (5-block) drift radius held the
            // latch on a cell 5 below the bot — OUTSIDE mining reach (~4.5) — so the latch
            // owned every tick swinging at an unreachable block until the full break
            // timeout while the travel drive was starved (the 85s badlands stall). Release
            // at reach (20 ≈ 4.5²) and cap the watchdog at 150t: with the latch holding
            // attack every tick, any REACHABLE block (worst realistic case ~25×-slow
            // underwater dirt with a tool) completes well inside that.
            // gap#66: this legacy EXCLUSIVE latch must not run alongside its successor
            // walkerDigAimPriority — with both on, the two release checks each ran
            // ++stickyDig.ticks on the same counter, so the 150t watchdog fired at ~75
            // REAL ticks. A bare-hand stone dig needs 150 CONSECUTIVE held ticks
            // (vanilla zeroes progress on any released tick), so every wall-dig
            // fallback swing was dropped mid-dig ("DIG-dropped after 76t") and the
            // stuck recovery piling up behind the starved actuator shoved the bot off
            // its own stairs. digAimPriority alone re-holds crosshair+attack at the
            // end of every travel tick — the dig survives without owning the tick.
            if (!world.isSolid(wk.stickyDig.pos)
                    || ++wk.stickyDig.ticks > Math.min(BotConfig.breakTimeoutTicks, 150)
                    || wk.stickyDig.pos.distToCenterSqr(p.position()) > 20) {
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] sticky-dig RELEASE {} solid={} ticks={}",
                            wk.stickyDig.pos, world.isSolid(wk.stickyDig.pos), wk.stickyDig.ticks);
                wk.stickyDig.pos = null;
                wk.stickyDig.ticks = 0;
            } else {
                a.selectTool(wk.stickyDig.pos);
                a.aimAtBlock(wk.stickyDig.pos);
                a.breakHold(true);
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
