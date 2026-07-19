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
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 1403–1646): in-flight A* advance, result adoption / splice, frontier + futile backoff.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickSearch {
    private WalkerTickSearch() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Avatar a, WorldView world) {
        // ---- rehydrate shared per-tick locals (mechanical; see WalkerTickCtx) ----
        Player p = cx.p;
        BlockPos foot = cx.foot;
        double d = cx.d;
        // ---- original body (byte-identical modulo member prefixes) ----
        // Advance any in-flight search by one tick-slice so a big search never
        // blocks the render thread in a single tick (fixes the stutter).
        boolean searchDone = false;
        if (wk.activeSearch != null) {
            long sb = System.nanoTime();
            // IDLE-SLICE: if there's no walkable path this tick (segment consumed or
            // none yet), the bot is FROZEN until the search lands — so frame-smoothness
            // is moot and a thin 6 ms slice just stretches a 3 s search to ~27 s of
            // standing still. Spend a much bigger slice to finish it ~5× sooner; while
            // walking, keep the thin slice so frames stay smooth.
            boolean idleNoPath = wk.path == null || wk.step >= wk.path.size();
            long slice = idleNoPath
                    ? Math.max(BotConfig.pathfinderSliceMs, BotConfig.pathfinderIdleSliceMs)
                    : BotConfig.pathfinderSliceMs;
            searchDone = wk.activeSearch.advance(slice);
            if (BotConfig.walkerDebug && !searchDone)
                LOG.info(
                        "[walker] search slice {}ms expanded={} (still running)",
                        (System.nanoTime() - sb) / 1_000_000, wk.activeSearch.expanded());
        }
        if (searchDone) {
            PathFinder.Result res = wk.activeSearch.result();
            wk.activeSearch = null;
            boolean wasFromEnd = wk.searchFromEnd;
            wk.searchFromEnd = false;
            Walker.lastStats = new Walker.PathStats(res.expanded(), res.ms(), res.goalReached(),
                    res.finalCost(), res.path().size());
            PathTraceHolder.SINK.onSearchResult(res.path(), res.edges(), res.goalReached(),
                    res.expanded(), res.ms(), res.finalCost());
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] repath from {} → goalReached={} pathLen={} expanded={} ms={}",
                        wasFromEnd ? wk.commitEnd : foot, res.goalReached(), res.path().size(), res.expanded(), res.ms());
            // Unreachable-goal churn guard (gap #49-③): a best-effort result landing while
            // the bot has neither moved nor gotten any closer to the goal is a FUTILE cycle
            // — repath → reject/no progress → identical repath — and each cycle burns a full
            // A* budget. The total-tick budget below does bound this, but it counts ticks
            // while the cost is per-SEARCH (live probe: 129 searches, ~36 s of A* CPU inside
            // the 62 s wait). Count the searches themselves: after walkerFutileSearchCap
            // consecutive futile completions, end the journey with a reason the agent can
            // act on ("no route progress" = the goal is unreachable from here, re-plan; the
            // tick-budget's "no progress for N ticks" keeps meaning a transient stall).
            // Actively mining exempts (goal distance is legitimately flat mid-break), same
            // as the tick budget's breakHeld hold below.
            // Water is exempt: an afloat bot legitimately repaths many times while
            // stationary (bank climb-outs, bobbing), and that churn is owned by the
            // existing in-water anti-spin (repathsNoProgress) — two governors on one
            // loop would race. This guard owns the DRY unreachable churn.
            // gap#66 leg C: a COMPLETELY empty result while stuck-penalties are live is
            // (likely) SELF-INFLICTED blindness — the wedge penalties walled the pocket, not
            // the terrain (same rationale as the path==null decay-wait below, which this cap
            // was racing: live pit 2026-07-14, "waiting out decay (1/900)" then the 5th
            // futile search fail-stopped the goto 6 s in, 39 s before the penalties would
            // have cleared). Don't count those; penalties decay in 15-90 s and the counter
            // resumes on the first clean-view failure. Partial results still count — the
            // penalties didn't blind the search enough to matter.
            if (BotConfig.walkerFutileSearchCap > 0 && !res.goalReached()
                    && !a.breakHeld() && !wk.waterClimbDigging && !world.isWater(foot)
                    && !(!res.hasPath() && world.hasStuckPenalties())) {
                boolean gotCloser = wk.bestDistToGoal < wk.futileBestDist - 0.5;
                boolean moved = wk.futileFoot == null || wk.futileFoot.distSqr(foot) > 4;
                if (gotCloser || moved) {
                    wk.futileSearches = 0;
                    wk.futileBestDist = wk.bestDistToGoal;
                    wk.futileFoot = foot;
                } else if (++wk.futileSearches >= BotConfig.walkerFutileSearchCap) {
                    wk.lastError = "no route progress after " + wk.futileSearches
                            + " consecutive searches — goal unreachable from here (best dist="
                            + Math.round(wk.bestDistToGoal) + ")";
                    return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.NO_PATH, wk.lastError, "failed:" + wk.lastError, p.blockPosition());
                } else {
                    // Cool down before the next kickoff so the wait between futile cycles
                    // stops burning full search budgets (4→8→16→32-tick backoff).
                    wk.searchBackoffTicks = Math.min(40, 4 << Math.min(wk.futileSearches, 3));
                }
            }
            if (wasFromEnd && wk.path != null && wk.step < wk.path.size()) {
                // Continuation finished while we're still walking the current
                // segment — stash it and splice only once we reach the segment end
                // (see the step>=size handler), so its start coincides with the bot.
                // Stash even an empty (no-path) result: the segment-end handler
                // reads that as "no further progress" and ends cleanly, rather than
                // letting the kickoff re-launch the same boxed-in search forever.
                wk.pendingSegment = res;
            } else if (wasFromEnd && !res.hasPath()) {
                // Already at/over the segment end and no onward route. With frontier
                // planning this may be a STALE continuation (computed before we
                // arrived & loaded the chunks beyond) — re-search fresh before giving
                // up; otherwise we've gone as far as the best effort allows.
                return wk.frontierHoldOrArrive(a, world, p);
            } else if (res.hasPath()) {
                // ANTI-SPIN (water repath-churn): a failed water climb-out (bot can't
                // mount the bank) makes every repath return a best-effort that swims
                // back / circles without the bot's ACTUAL position getting any closer
                // to the goal; following each one U-turns the bot and the repeated
                // U-turns wind the camera (the water "转圈"). This is a TEMPORAL signal
                // (no net progress across repaths), not a single-path property — the
                // churn segment can even END on dry land (the unreachable climb-out
                // target). Track the bot's best goal-distance: when several consecutive
                // repaths IN WATER fail to improve it, end best-effort instead of
                // churning. A real journey keeps improving (counter stays 0); land is
                // exempt (go-arounds may step away from the goal there).
                // (d = goal.estimate(foot), computed above for the hard tick budget)
                // Count consecutive in-water repaths that don't get the bot closer to
                // the goal (the 5-unit margin ignores micro-lunges). This drives the
                // anti-spin camera FREEZE in the heading block (repathsNoProgress >
                // CHURN_REPATH_CAP) so the churn stops winding the camera while the bot
                // keeps pressing toward the climb-out; only after a long stall with no
                // progress at all do we give up best-effort instead of pressing forever.
                if (d < wk.bestGoalDist - 5.0) { wk.bestGoalDist = d; wk.repathsNoProgress = 0; wk.churnResets = 0; }
                else wk.repathsNoProgress++;
                // Only a body actually AFLOAT counts as a water repath: the old
                // `|| isWater(foot.below())` arm also matched a bot standing on dry
                // ground beside a waterfall, so a canyon pacing loop was misread as
                // water churn and the goto ended in a FAKE ARRIVED at (420,-349)
                // (live 2026-06-09, "anti-spin: 13/15 water repaths" on dry land).
                boolean overWaterRepath = p.isInWater() && !p.onGround();
                if (overWaterRepath && !res.goalReached() && wk.repathsNoProgress > CHURN_GIVEUP_CAP) {
                    // A LEGITIMATE go-around also reads as "no progress" here: rounding a
                    // lava arm via the river pushed d above the pre-detour best for 13+
                    // repaths and the give-up fired mid-journey with 440 blocks to go
                    // (live 2026-06-09 — the land exemption in the note above was right,
                    // it just didn't cover WATER detours). So don't give up on the first
                    // stall: RE-BASELINE at the current distance and watch again — a real
                    // detour soon improves on the new baseline (and any true progress
                    // resets churnResets); only a churn that stalls through several fresh
                    // baselines is the genuine unwinnable spin.
                    if (wk.churnResets < CHURN_MAX_RESETS) {
                        wk.churnResets++;
                        wk.bestGoalDist = d;
                        wk.repathsNoProgress = 0;
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] anti-spin: stalled in water (d={}) → re-baseline {}/{}",
                                    String.format(Locale.ROOT, "%.0f", d), wk.churnResets, CHURN_MAX_RESETS);
                    } else {
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] anti-spin: {} water repaths w/o progress after {} re-baselines (d={}) → end best-effort",
                                    wk.repathsNoProgress, wk.churnResets, String.format(Locale.ROOT, "%.0f", d));
                        Walker.agentForward(a, false);
                        Walker.agentJump(a, false);
                        p.setSprinting(false);
                        return wk.terminalReport(Walker.Step.ARRIVED, PathTrace.Outcome.SUCCESS, null, "churn-giveup", p.blockPosition());
                    }
                }
                // BLOCK-BUDGET ("搭桥前算够不够，否则就挖"): if this path would place
                // more blocks (bridge/pillar/parkour-place) than the bot carries, it
                // would bridge partway, burn its blocks and strand. Re-search with
                // placing OFF so A* digs through / routes around (break moves need no
                // blocks). Guard with searchSuppressedPlace so the place-off result is
                // adopted as-is (no second reroute / loop).
                if (!wk.replayMode && !wk.searchSuppressedPlace) {
                    int placesNeeded = countPlaceEdges(res.edges());
                    if (placesNeeded > world.placeableBlockCount()) {
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] path needs {} placed blocks, have {} → re-search place-off (dig/around)",
                                    placesNeeded, world.placeableBlockCount());
                        wk.activeSearch = new PathFinder(world, wk.profile).withOwner(wk.owner).newSearch(foot, wk.goal, true);
                        wk.searchFromEnd = false;
                        wk.searchSuppressedPlace = true;
                        wk.pendingSegment = null;
                        return Walker.Step.WALKING;
                    }
                }
                // Route hysteresis (walkerRouteHysteresis): don't let a periodic repath
                // U-turn a healthy walk onto the alternate near-equal route (§55 oscillation).
                // The direction dot doubles as the REPATH-flip expectation alarm: adopting a
                // route whose near-term direction REVERSES the current one is exactly one leg
                // of the planner oscillation loop — two adoptions with reversed legs inside
                // 200 ticks is the live signature of "A* alternates two near-equal routes"
                // (the C16 dry-land churn that took three autopsies to see from raw logs).
                boolean keepCurrent = false;
                boolean uTurnLeg = false;
                if (wk.path != null && wk.step < wk.path.size()
                        && res.path().size() > 3 && wk.path.size() - wk.step > 3) {
                    BlockPos curAhead = wk.path.get(Math.min(wk.step + 3, wk.path.size() - 1));
                    BlockPos newAhead = res.path().get(3);
                    double cax = curAhead.getX() + 0.5 - p.getX(), caz = curAhead.getZ() + 0.5 - p.getZ();
                    double nax = newAhead.getX() + 0.5 - p.getX(), naz = newAhead.getZ() + 0.5 - p.getZ();
                    uTurnLeg = (cax * nax + caz * naz) < 0;
                    keepCurrent = BotConfig.walkerRouteHysteresis && uTurnLeg && wk.noStepProgressTicks < 20;
                    if (keepCurrent && BotConfig.walkerDebug)
                        LOG.info("[walker] route-hysteresis: KEEP current path (new route U-turns behind a healthy walk, noStepProg={})",
                                wk.noStepProgressTicks);
                }
                // Dig-commit hold (walkerDigCommitHoldRepath): an underwater bank dig takes
                // 100-200t (25x mining penalty) but the periodic repath re-routes faster than
                // that, and each adoption discards the held break — vanilla resets the block's
                // progress to zero, so the dig NEVER completes and the water-bank churn loops
                // (CLEAN-K5: dig 368,63,351 dropped at 19t by a route adoption, then DIG-slow
                // 200t grinding the re-chosen riser). While a committed bank dig's break is
                // actually held, keep the current path; the futile-dig release still abandons
                // a hopeless dig, which drops the hold and lets the next repath adopt normally.
                if (!keepCurrent && BotConfig.walkerDigCommitHoldRepath
                        && wk.waterClimbDigRiser != null && a.breakHeld()) {
                    keepCurrent = true;
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] dig-commit-hold: KEEP current path (committed bank dig at {},{},{} in progress)",
                                wk.waterClimbDigRiser.getX(), wk.waterClimbDigRiser.getY(), wk.waterClimbDigRiser.getZ());
                }
                if (!keepCurrent) {
                    if (BotConfig.walkerExpectAlarm && uTurnLeg) {
                        wk.exAlarms.noteRepathFlip(wk.pfTickCounter, foot);
                    }
                    wk.adoptPath(res, world, foot);
                    wk.maybeArmPinchEscalation(foot, res);
                }
            } else if (wk.path == null) {
                // No route and nothing to fall back on. But if we're airborne
                // — plummeting from an unplanned fall (knockback, the ground
                // broken out from under us, a tp) — a mid-air foot ALWAYS has
                // no walkable neighbours, so failing here ends the process and
                // stops ticking, and the reactive water-clutch + MLG latch at
                // the top of tick() never get to run. Hold instead so they keep
                // ticking; once we land, the next repath finds a route or fails
                // for real. (Bounded by the total-tick budget above, so a void
                // fall with no clutch can't hang the goto forever.)
                if (!p.onGround()) {
                    Walker.agentForward(a, false);
                    p.setSprinting(false);
                    return Walker.Step.WALKING;
                }
                // SELF-INFLICTED no-path: repeated wedge penalties can wall the
                // bot into a small pocket (every exit charged 600-3600 within a
                // 2.5-block bump) so the search legitimately finds nothing — but
                // the blindness is OURS, not the terrain's (live 2026-06-09 cave
                // pocket: debug.plan with a clean view reached the goal in 12
                // segments from the same foot). Penalties decay in 15 s; hold and
                // let the regular path==null kickoff retry instead of failing the
                // whole goto. Bounded so a genuinely walled-in bot still fails.
                if (world.hasStuckPenalties() && wk.noPathWaitTicks < NO_PATH_WAIT_CAP) {
                    wk.noPathWaitTicks++;
                    if (BotConfig.walkerDebug && wk.noPathWaitTicks % 100 == 1)
                        LOG.info("[walker] no path but stuck-penalties still live → waiting out decay ({}/{})",
                                wk.noPathWaitTicks, NO_PATH_WAIT_CAP);
                    Walker.agentForward(a, false);
                    Walker.agentJump(a, false);
                    p.setSprinting(false);
                    return Walker.Step.WALKING;
                }
                wk.lastError = "no path (expanded=" + res.expanded() + ")";
                return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.NO_PATH, wk.lastError, "failed:" + wk.lastError, p.blockPosition());
            }
            // else: search failed but we still have the previous path — keep it.
        }
        // ---- persist shared per-tick locals (mechanical; see WalkerTickCtx) ----
        cx.p = p;
        cx.foot = foot;
        return null;
    }
}
