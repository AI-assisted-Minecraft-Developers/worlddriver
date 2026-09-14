package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.movement.PathSmoothing.*;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 1403–1646): in-flight A* advance, result adoption / splice, frontier + futile backoff.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickSearch {
    private WalkerTickSearch() {}

    /**
     * Tally WHY the futile-search gate is skipping this completed search, and say whether it is.
     * First match in the gate's OWN written order, so buckets 0-5 here plus 6-8 at the call site
     * partition every completed search and their sum is the search count.
     *
     * <p>Counting lives here rather than at the call site because it must happen BEFORE the
     * {@code if}, or the most-suspect exclusion ({@code goalReached}) can never be observed — a
     * census that cannot reach its own subject is the defect it exists to find. The predicates are
     * the gate's, in the gate's order, so nothing here changes what the gate does; the only reason
     * this is one method and not an expression is {@code run()}'s per-method budget.
     */
    private static boolean futileGateExcluded(PathFinder.Result res, Body a, Walker wk,
                                              WorldView world, BlockPos foot) {
        int bucket = -1;
        if (BotConfig.walkerFutileSearchCap <= 0) bucket = 0;
        else if (res.goalReached()) bucket = 1;
        else if (wk.hands.breakHeld()) bucket = 2;
        else if (wk.waterClimb.digging) bucket = 3;
        else if (world.isWater(foot)) bucket = 4;
        else if (!res.hasPath() && world.hasStuckPenalties()) bucket = 5;
        if (bucket < 0) return false;
        Walker.futileGateBuckets.incrementAndGet(bucket);
        return true;
    }

    /**
     * Unreachable-goal churn guard (gap #49-③): a best-effort result landing while the bot has
     * neither moved nor gotten any closer to the goal is a FUTILE cycle — repath → reject/no
     * progress → identical repath — and each cycle burns a full A* budget. The total-tick budget
     * does bound this, but it counts ticks while the cost is per-SEARCH (live probe: 129 searches,
     * ~36 s of A* CPU inside the 62 s wait). Count the searches themselves: after
     * {@code walkerFutileSearchCap} consecutive futile completions, end the journey with a reason
     * the agent can act on ("no route progress" = the goal is unreachable from here, re-plan; the
     * tick-budget's "no progress for N ticks" keeps meaning a transient stall).
     *
     * <p>Actively mining exempts (goal distance is legitimately flat mid-break), same as the tick
     * budget's breakHeld hold. Water is exempt: an afloat bot legitimately repaths many times while
     * stationary (bank climb-outs, bobbing), and that churn is owned by the existing in-water
     * anti-spin (repathsNoProgress) — two governors on one loop would race. This guard owns the DRY
     * unreachable churn. gap#66 leg C: a COMPLETELY empty result while stuck-penalties are live is
     * (likely) SELF-INFLICTED blindness — the wedge penalties walled the pocket, not the terrain
     * (live pit 2026-07-14: "waiting out decay (1/900)" then the 5th futile search fail-stopped the
     * goto 6 s in, 39 s before the penalties would have cleared). Don't count those; penalties decay
     * in 15-90 s and the counter resumes on the first clean-view failure. Partial results still
     * count — the penalties didn't blind the search enough to matter.
     *
     * @return non-null Step to end the tick, or null to fall through.
     */
    private static Walker.Step futileGateJudge(PathFinder.Result res, Body a, Walker wk,
                                               WorldView world, BlockPos foot, LivingEntity p) {
        if (futileGateExcluded(res, a, wk, world, foot)) return null;
        // An unseeded baseline is +INFINITY, which makes the first comparison after every reset
        // trivially "got closer" — a free zeroing donated by the reset itself. The first judged
        // search has nothing to compare against: it SEEDS, it does not judge.
        boolean seeded = wk.searchGov.futileGoal != null
                && wk.searchGov.futileBestDist != Double.POSITIVE_INFINITY;
        // Judge today's foot with YESTERDAY's goal. A pursuit re-goals as its quarry moves and every
        // re-goal resets goalSpin, so measured against the CURRENT goal the quarry drifting closer
        // reads as the body having earned ground. Against the goal that set the baseline it does
        // not — and this holds for every goal shape, with no per-shape arithmetic to get wrong.
        boolean gotCloser = seeded
                && wk.searchGov.futileGoal.estimate(foot) < wk.searchGov.futileBestDist - 0.5;
        boolean moved = wk.searchGov.futileFoot != null && wk.searchGov.futileFoot.distSqr(foot) > 4;
        Walker.futileGateBuckets.incrementAndGet(!seeded ? 9 : gotCloser ? 6 : moved ? 7 : 8);
        if (!seeded || gotCloser || moved) {
            if (seeded) wk.searchGov.futileSearches = 0;
            wk.searchGov.futileBestDist = wk.goalSpin.bestDistToGoal;
            wk.searchGov.futileFoot = foot;
            wk.searchGov.futileGoal = wk.goal;
            wk.searchGov.futileLatchFoot = null;
        } else if (++wk.searchGov.futileSearches >= BotConfig.walkerFutileSearchCap) {
            wk.lastError = "no route progress after " + wk.searchGov.futileSearches
                    + " consecutive searches — goal unreachable from here (best dist="
                    + Math.round(wk.goalSpin.bestDistToGoal) + ")";
            // Latch on the foot, not on the goal: this terminal costs a full A* every tick it is
            // re-reported, and a caller that ignores the return value (or re-goals at a moving
            // quarry) would otherwise pay it forever.
            wk.searchGov.futileLatchFoot = foot;
            return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.NO_PATH, wk.lastError,
                    "failed:" + wk.lastError, p.blockPosition());
        } else {
            // Cool down before the next kickoff so the wait between futile cycles stops burning
            // full search budgets (4→8→16→32-tick backoff).
            wk.searchGov.searchBackoffTicks = Math.min(40, 4 << Math.min(wk.searchGov.futileSearches, 3));
        }
        return null;
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        double d = cx.stall.d;
        // ---- original body (byte-identical modulo member prefixes) ----
        // Advance any in-flight search by one tick-slice so a big search never
        // blocks the render thread in a single tick (fixes the stutter).
        boolean searchDone = false;
        if (wk.seg.activeSearch != null) {
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
            searchDone = wk.seg.activeSearch.advance(slice);
            if (BotConfig.walkerDebug && !searchDone)
                LOG.info(
                        "[walker] search slice {}ms expanded={} (still running)",
                        (System.nanoTime() - sb) / 1_000_000, wk.seg.activeSearch.expanded());
        }
        if (searchDone) {
            PathFinder.Result res = wk.seg.activeSearch.result();
            wk.tallies.lastSearch = wk.seg.activeSearch;
            wk.tallies.lastResult = res;
            wk.seg.activeSearch = null;
            boolean wasFromEnd = wk.seg.searchFromEnd;
            wk.seg.searchFromEnd = false;
            Walker.lastStats = new Walker.PathStats(res.expanded(), res.ms(), res.goalReached(),
                    res.finalCost(), res.path().size(), res.sightBudgetExhausted(), res.snapshotTruncated());
            wk.tallies.searches++;
            if (res.sightBudgetExhausted())
                LOG.info("[walker] search dropped route.sight: ray budget spent, rerun without it (owner={})", wk.owner);
            PathTraceHolder.SINK.onSearchResult(res.path(), res.edges(), res.goalReached(),
                    res.expanded(), res.ms(), res.finalCost());
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] repath from {} → goalReached={} pathLen={} expanded={} ms={}",
                        wasFromEnd ? wk.seg.commitEnd : foot, res.goalReached(), res.path().size(), res.expanded(), res.ms());
            Walker.Step futile = futileGateJudge(res, a, wk, world, foot, p);
            if (futile != null) return futile;
            // FROM-END continuation that makes NO goal progress beyond the committed end
            // (walkerFromEndNoProgressDiscard): with the goal SEALED, the continuation's
            // best-effort degenerates to the escape-farthest fallback — a path walking
            // BACKWARD from commitEnd (bridge stop-family: the continuation from the land
            // stub's end (11,0) came back (11,0)→start pad; adopting it U-turned the bot
            // into a forward/backward ping-pong livelock at the pad, maxX 3.9 of a
            // reachable 11). An EMPTY continuation already means "no further progress" —
            // degrade this one to that, so the segment-end handler ends the journey
            // cleanly at the farthest reachable point. A genuine detour keeps at least
            // one node that beats commitEnd's estimate, so it is never discarded; water
            // is exempt (afloat churn is owned by the anti-spin governor).
            if (BotConfig.walkerFromEndNoProgressDiscard
                    && wasFromEnd && res.hasPath() && wk.seg.commitEnd != null && !world.isWater(foot)) {
                double endEst = wk.goal.estimate(wk.seg.commitEnd);
                double bestEst = Double.MAX_VALUE;
                for (BlockPos n : res.path()) bestEst = Math.min(bestEst, wk.goal.estimate(n));
                if (bestEst >= endEst - 0.5) {
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] from-end continuation makes no progress past {},{},{} (best {} vs end {}) → discard as no-onward-route",
                                wk.seg.commitEnd.getX(), wk.seg.commitEnd.getY(), wk.seg.commitEnd.getZ(),
                                String.format(Locale.ROOT, "%.1f", bestEst), String.format(Locale.ROOT, "%.1f", endEst));
                    res = new PathFinder.Result(java.util.List.of(), java.util.List.of(), false,
                            res.expanded(), res.ms(), res.finalCost());
                }
            }
            if (wasFromEnd && wk.path != null && wk.step < wk.path.size()) {
                // Continuation finished while we're still walking the current
                // segment — stash it and splice only once we reach the segment end
                // (see the step>=size handler), so its start coincides with the bot.
                // Stash even an empty (no-path) result: the segment-end handler
                // reads that as "no further progress" and ends cleanly, rather than
                // letting the kickoff re-launch the same boxed-in search forever.
                wk.seg.pendingSegment = res;
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
                if (d < wk.goalSpin.bestGoalDist - 5.0) { wk.goalSpin.bestGoalDist = d; wk.goalSpin.repathsNoProgress = 0; wk.goalSpin.churnResets = 0; }
                else wk.goalSpin.repathsNoProgress++;
                // Only a body actually AFLOAT counts as a water repath: the old
                // `|| isWater(foot.below())` arm also matched a bot standing on dry
                // ground beside a waterfall, so a canyon pacing loop was misread as
                // water churn and the goto ended in a FAKE ARRIVED at (420,-349)
                // (live 2026-06-09, "anti-spin: 13/15 water repaths" on dry land).
                boolean overWaterRepath = p.isInWater() && !p.onGround();
                if (overWaterRepath && !res.goalReached() && wk.goalSpin.repathsNoProgress > CHURN_GIVEUP_CAP) {
                    // A LEGITIMATE go-around also reads as "no progress" here: rounding a
                    // lava arm via the river pushed d above the pre-detour best for 13+
                    // repaths and the give-up fired mid-journey with 440 blocks to go
                    // (live 2026-06-09 — the land exemption in the note above was right,
                    // it just didn't cover WATER detours). So don't give up on the first
                    // stall: RE-BASELINE at the current distance and watch again — a real
                    // detour soon improves on the new baseline (and any true progress
                    // resets churnResets); only a churn that stalls through several fresh
                    // baselines is the genuine unwinnable spin.
                    if (wk.goalSpin.churnResets < CHURN_MAX_RESETS) {
                        wk.goalSpin.churnResets++;
                        wk.goalSpin.bestGoalDist = d;
                        wk.goalSpin.repathsNoProgress = 0;
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] anti-spin: stalled in water (d={}) → re-baseline {}/{}",
                                    String.format(Locale.ROOT, "%.0f", d), wk.goalSpin.churnResets, CHURN_MAX_RESETS);
                    } else {
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] anti-spin: {} water repaths w/o progress after {} re-baselines (d={}) → end best-effort",
                                    wk.goalSpin.repathsNoProgress, wk.goalSpin.churnResets, String.format(Locale.ROOT, "%.0f", d));
                        Walker.avatarForward(a, false);
                        wk.avatarJump(a, false);
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
                if (!wk.replayMode && !wk.seg.searchSuppressedPlace) {
                    int placesNeeded = countPlaceEdges(res.edges());
                    if (placesNeeded > world.placeableBlockCount()) {
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] path needs {} placed blocks, have {} → re-search place-off (dig/around)",
                                    placesNeeded, world.placeableBlockCount());
                        wk.seg.activeSearch = wk.newPathFinder(world).newSearch(foot, wk.goal, true);
                        wk.seg.searchFromEnd = false;
                        wk.seg.searchSuppressedPlace = true;
                        wk.seg.pendingSegment = null;
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
                    keepCurrent = BotConfig.walkerRouteHysteresis && uTurnLeg && wk.stepProg.noStepProgressTicks < 20;
                    if (keepCurrent && BotConfig.walkerDebug)
                        LOG.info("[walker] route-hysteresis: KEEP current path (new route U-turns behind a healthy walk, noStepProg={})",
                                wk.stepProg.noStepProgressTicks);
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
                        && wk.waterClimb.digRiser != null && wk.hands.breakHeld()) {
                    keepCurrent = true;
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] dig-commit-hold: KEEP current path (committed bank dig at {},{},{} in progress)",
                                wk.waterClimb.digRiser.getX(), wk.waterClimb.digRiser.getY(), wk.waterClimb.digRiser.getZ());
                }
                if (!keepCurrent) {
                    if (BotConfig.walkerExpectAlarm && uTurnLeg) {
                        wk.exAlarms.noteRepathFlip(wk.escal.tick, foot);
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
                    Walker.avatarForward(a, false);
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
                if (world.hasStuckPenalties() && wk.searchGov.noPathWaitTicks < NO_PATH_WAIT_CAP) {
                    wk.searchGov.noPathWaitTicks++;
                    if (BotConfig.walkerDebug && wk.searchGov.noPathWaitTicks % 100 == 1)
                        LOG.info("[walker] no path but stuck-penalties still live → waiting out decay ({}/{})",
                                wk.searchGov.noPathWaitTicks, NO_PATH_WAIT_CAP);
                    Walker.avatarForward(a, false);
                    wk.avatarJump(a, false);
                    p.setSprinting(false);
                    return Walker.Step.WALKING;
                }
                wk.lastError = "no path (expanded=" + res.expanded() + ")";
                return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.NO_PATH, wk.lastError, "failed:" + wk.lastError, p.blockPosition());
            }
            // else: search failed but we still have the previous path — keep it.
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        return null;
    }
}
