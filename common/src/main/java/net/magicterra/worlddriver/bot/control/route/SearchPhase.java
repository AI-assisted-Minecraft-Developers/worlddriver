package net.magicterra.worlddriver.bot.control.route;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.BOXED_ESCALATE_STICKY_TICKS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CHURN_GIVEUP_CAP;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CHURN_MAX_RESETS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.NO_PATH_WAIT_CAP;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.PINCH_MIN_PROGRESS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.SEGMENT_END_SLICE_NODES;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.countPlaceEdges;
import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.Locale;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorTelemetry;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.control.act.Keys;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import net.magicterra.worlddriver.bot.planning.PathTraceHolder;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Mechanical extraction of {@code Navigator#tickInner} (pre-split lines 1403–1646): in-flight A* advance, result
 * adoption / splice, frontier + futile backoff. One phase of the per-tick pipeline — see {@link Navigator#tickInner}
 * for the driver and {@link TickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the original tick body
 * except for the {@code w.}/{@code Navigator.} member prefixes; do not restructure here without live/testkit evidence
 * (this file is state-machine surgery).
 */
public final class SearchPhase {

    /**
     * Refusal lines printed per navigator before going quiet;
     * {@link net.magicterra.worlddriver.bot.control.NavigatorTallies#refusals} keeps counting.
     */
    private static final int REFUSALS_LOGGED = 8;

    /** The rations and latches that stop a search from repeating itself; see {@link SearchGovernors}. */
    public final SearchGovernors gov = new SearchGovernors();

    /**
     * PROACTIVE PINCH escalation — see
     * {@link net.magicterra.worlddriver.bot.control.NavigatorConstants#PINCH_MIN_PROGRESS}. When the big search commits
     * a best-effort segment that barely closed the goal distance, the planner is wedged at a pinch (deepwater→bank,
     * boxed canyon, steep barrier); arm the deep-search escalation on that FIRST struggling commit so the NEXT search
     * suppresses its horizon, raises the depth penalty and routes AROUND the obstacle — instead of waiting for the
     * executor to churn ~20 s until the reactive churn-window fires. Re-arms (extends) on each struggling commit while
     * the pinch persists; logs only on the transition. Gated to {@link ControlConfig#pathfinderProgressive} (default
     * OFF → behaviour unchanged).
     */
    public void maybeArmPinchEscalation(Navigator nav, BlockPos foot, PathFinder.Result res) {
        if (!ControlConfig.pathfinderProgressive) {
            return;
        }
        if (res.goalReached() || nav.route.seg.commitEnd == null) {
            return; // healthy full route → no escalation
        }
        double progress = nav.goal.estimate(foot) - nav.goal.estimate(nav.route.seg.commitEnd);
        if (progress >= PINCH_MIN_PROGRESS) {
            return; // real headway → not a pinch
        }
        boolean wasArmed = nav.stallDetect.escal.armed();
        nav.stallDetect.escal.arm(BOXED_ESCALATE_STICKY_TICKS);
        if (GameConfig.navigatorDebug && !wasArmed) {
            LOG.info(
                    "[navigator] proactive pinch escalation ARMED: best-effort commit gained only {} blocks toward goal → deepen next search",
                    String.format("%.1f", progress));
        }
    }

    /**
     * No onward route from the segment end: hold (keys released) and return WALKING while {@link Route#retryAtFrontier}
     * re-searches; ARRIVED when out of retries or the feature is off.
     */
    public TickStep frontierHoldOrArrive(Navigator nav, Body a, WorldView world, LivingEntity p) {
        if (nav.route.retryAtFrontier(nav, world)) {
            Keys.forward(a, false);
            nav.keys.jump(a, false);
            p.setSprinting(false);
            return TickStep.WALKING;
        }
        return nav.terminalReport(
                TickStep.ARRIVED, PathTrace.Outcome.SUCCESS, null, "frontier-giveup", p.blockPosition());
    }

    /** A replan only lifts the quick cooldown; a new goal starts every governor afresh. */
    public void reset(ResetScope scope) {
        if (scope == ResetScope.REPLAN) {
            gov.quickCooldown = 0;
        } else {
            gov.reset();
        }
    }

    /**
     * Tally WHY the futile-search gate is skipping this completed search, and say whether it is. First match in the
     * gate's OWN written order, so buckets 0-5 here plus 6-8 at the call site partition every completed search and
     * their sum is the search count.
     *
     * <p>Counting lives here rather than at the call site because it must happen BEFORE the {@code if}, or the
     * most-suspect exclusion ({@code goalReached}) can never be observed — a census that cannot reach its own subject
     * is the defect it exists to find. The predicates are the gate's, in the gate's order, so nothing here changes what
     * the gate does; the only reason this is one method and not an expression is {@code run()}'s per-method budget.
     */
    private boolean futileGateExcluded(
            PathFinder.Result res, Navigator nav, WorldView world, BlockPos foot, boolean dugLastTick) {
        int bucket = -1;
        if (ControlConfig.navigatorFutileSearchCap <= 0) {
            bucket = 0;
        } else if (res.goalReached()) {
            bucket = 1;
        } else if (nav.blocks.breakHeld()) {
            bucket = 2;
        } else if (dugLastTick) {
            bucket = 3;
        } else if (world.isWater(foot)) {
            bucket = 4;
        } else if (!res.hasPath() && world.hasStuckPenalties()) {
            bucket = 5;
        }
        if (bucket < 0) {
            return false;
        }
        nav.tel.futileGateBuckets.incrementAndGet(bucket);
        return true;
    }

    /**
     * Unreachable-goal churn guard (gap #49-③): a best-effort result landing while the bot has neither moved nor gotten
     * any closer to the goal is a FUTILE cycle — repath → reject/no progress → identical repath — and each cycle burns
     * a full A* budget. The total-tick budget does bound this, but it counts ticks while the cost is per-SEARCH (live
     * probe: 129 searches, ~36 s of A* CPU inside the 62 s wait). Count the searches themselves: after
     * {@code navigatorFutileSearchCap} consecutive futile completions, end the journey with a reason the agent can act
     * on ("no route progress" = the goal is unreachable from here, re-plan; the tick-budget's "no progress for N ticks"
     * keeps meaning a transient stall).
     *
     * <p>Actively mining exempts (goal distance is legitimately flat mid-break), same as the tick budget's breakHeld
     * hold. Water is exempt: an afloat bot legitimately repaths many times while stationary (bank climb-outs, bobbing),
     * and that churn is owned by the existing in-water anti-spin (repathsNoProgress) — two governors on one loop would
     * race. This guard owns the DRY unreachable churn. A COMPLETELY empty result while stuck-penalties are live is
     * (likely) SELF-INFLICTED blindness — the wedge penalties walled the pocket, not the terrain (live pit 2026-07-14:
     * "waiting out decay (1/900)" then the 5th futile search fail-stopped the goto 6 s in, 39 s before the penalties
     * would have cleared). Don't count those; penalties decay in 15-90 s and the counter resumes on the first
     * clean-view failure. Partial results still count — the penalties didn't blind the search enough to matter.
     *
     * @return non-null Step to end the tick, or null to fall through.
     */
    private TickStep futileGateJudge(
            PathFinder.Result res, Navigator nav, WorldView world, BlockPos foot, LivingEntity p, boolean dugLastTick) {
        if (futileGateExcluded(res, nav, world, foot, dugLastTick)) {
            return null;
        }
        // An unseeded baseline is +INFINITY, which makes the first comparison after every reset
        // trivially "got closer" — a free zeroing donated by the reset itself. The first judged
        // search has nothing to compare against: it SEEDS, it does not judge.
        boolean seeded = gov.futileGoal != null && gov.futileBestDist != Double.POSITIVE_INFINITY;
        // Judge today's foot with YESTERDAY's goal. A pursuit re-goals as its quarry moves and every
        // re-goal resets goalSpin, so measured against the CURRENT goal the quarry drifting closer
        // reads as the bot having earned ground. Against the goal that set the baseline it does
        // not — and this holds for every goal shape, with no per-shape arithmetic to get wrong.
        boolean gotCloser = seeded && gov.futileGoal.estimate(foot) < gov.futileBestDist - 0.5;
        boolean moved = gov.futileFoot != null && gov.futileFoot.distSqr(foot) > 4;
        nav.tel.futileGateBuckets.incrementAndGet(seeded ? (gotCloser ? 6 : moved ? 7 : 8) : 9);
        if (!seeded || gotCloser || moved) {
            if (seeded) {
                gov.futileSearches = 0;
            }
            gov.futileBestDist = nav.stallDetect.goalSpin.bestDistToGoal;
            gov.futileFoot = foot;
            gov.futileGoal = nav.goal;
            gov.futileLatchFoot = null;
        } else if (++gov.futileSearches >= ControlConfig.navigatorFutileSearchCap) {
            nav.error = "no route progress after " + gov.futileSearches
                    + " consecutive searches — goal unreachable from here (best dist="
                    + Math.round(nav.stallDetect.goalSpin.bestDistToGoal) + ")";
            // Latch on the foot, not on the goal: this terminal costs a full A* every tick it is
            // re-reported, and a caller that ignores the return value (or re-goals at a moving
            // quarry) would otherwise pay it forever.
            gov.futileLatchFoot = foot;
            return nav.terminalReport(
                    TickStep.FAILED, PathTrace.Outcome.NO_PATH, nav.error, "failed:" + nav.error, p.blockPosition());
        } else {
            // Cool down before the next kickoff so the wait between futile cycles stops burning
            // full search budgets (4→8→16→32-tick backoff).
            gov.searchBackoffTicks = Math.min(40, 4 << Math.min(gov.futileSearches, 3));
        }
        return null;
    }

    /**
     * A landed search the adoption refused: the old plan stays (or, with none, the next tick searches again from the
     * feet), so the only trace a refusal leaves is the one written here.
     */
    private void refused(Navigator nav, Route.Adoption adoption, BlockPos foot) {
        nav.tallies.refusals++;
        nav.tallies.lastRefusal = adoption.refusal();
        if (nav.tallies.refusals <= REFUSALS_LOGGED) {
            LOG.info(
                    "[navigator] search result refused at {} ({} so far, owner={}): {}{}",
                    foot.toShortString(),
                    nav.tallies.refusals,
                    nav.owner,
                    adoption.refusal(),
                    nav.route.path() == null ? "; no plan to keep, searching again" : "; keeping the current plan");
        }
    }

    /**
     * Runs the phase for one tick. Returns the step that ends the tick, which the driver propagates, or null to fall
     * through.
     */
    // The phase is one long run of consume, decide and produce steps that share the tick's locals, so it stays whole.
    @SuppressWarnings("checkstyle:javancss")
    public TickStep run(Navigator nav, TickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (TickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        double d = cx.stall.d;
        // ---- original body (byte-identical modulo member prefixes) ----
        // Advance any in-flight search by one tick-slice so a big search never
        // blocks the render thread in a single tick (fixes the stutter).
        boolean searchDone = false;
        if (nav.route.seg.activeSearch != null) {
            long sb = System.nanoTime();
            // IDLE-SLICE: if there's no walkable path this tick (segment consumed or
            // none yet), the bot is FROZEN until the search lands — so frame-smoothness
            // is moot and a thin 6 ms slice just stretches a 3 s search to ~27 s of
            // standing still. Spend a much bigger slice to finish it ~5× sooner; while
            // walking, keep the thin slice so frames stay smooth. A FOOT-search (not a
            // continuation from the segment end) counts as idle too: its result replaces the
            // path on landing, so what the bot walks meanwhile is a stale plan or a stub, and
            // the thin slice stretched a 19k-node cliff search to 11 s of walking the stub
            // into a pit (live 2026-09-28, 1385,71,-517). So does a continuation the bot is about
            // to run out of path for: thin-sliced, a 20k-node cliff continuation left it standing
            // ~4 s at its segment end (live 2026-09-28, 1385,75,-511).
            boolean idleNoPath = nav.route.path() == null
                    || nav.route.step() >= nav.route.path().size() - SEGMENT_END_SLICE_NODES
                    || !nav.route.seg.searchFromEnd;
            FrameSearchPump.offer(nav);
            long want = idleNoPath
                    ? Math.max(ControlConfig.pathfinderSliceMs, ControlConfig.pathfinderIdleSliceMs)
                    : ControlConfig.pathfinderSliceMs;
            long slice =
                    Math.max(1, want - FrameSearchPump.takeSpentMs()); // less what frames spent: see FrameSearchPump
            searchDone = nav.route.seg.activeSearch.advance(slice);
            if (GameConfig.navigatorDebug && !searchDone) {
                LOG.info(
                        "[navigator] search slice {}ms expanded={} (still running)",
                        (System.nanoTime() - sb) / 1_000_000,
                        nav.route.seg.activeSearch.expanded());
            }
        }
        if (searchDone) {
            nav.tallies.lastSearch = nav.route.seg.activeSearch;
            final boolean wasFromEnd = nav.route.seg.searchFromEnd;
            final boolean afterChange = nav.route.seg.afterBlockChange;
            PathFinder.Result res = nav.route.takeSearchResult();
            nav.tallies.lastResult = res;
            nav.tel.lastStats = new NavigatorTelemetry.PathStats(
                    res.expanded(),
                    res.ms(),
                    res.goalReached(),
                    res.finalCost(),
                    res.path().size(),
                    res.sightBudgetExhausted(),
                    res.snapshotTruncated());
            nav.tallies.searches++;
            if (res.sightBudgetExhausted()) {
                LOG.info(
                        "[navigator] search dropped route.sight: ray budget spent, rerun without it (owner={})",
                        nav.owner);
            }
            PathTraceHolder.SINK.onSearchResult(
                    res.path(), res.edges(), res.goalReached(), res.expanded(), res.ms(), res.finalCost());
            if (GameConfig.navigatorDebug) {
                LOG.info(
                        "[navigator] repath from {} → goalReached={} pathLen={} expanded={} ms={}",
                        wasFromEnd ? nav.route.seg.commitEnd : foot,
                        res.goalReached(),
                        res.path().size(),
                        res.expanded(),
                        res.ms());
            }
            TickStep futile = futileGateJudge(res, nav, world, foot, p, cx.stall.dugLastTick);
            if (futile != null) {
                return futile;
            }
            // FROM-END continuation that makes NO goal progress beyond the committed end
            // (navigatorFromEndNoProgressDiscard): with the goal SEALED, the continuation's
            // best-effort degenerates to the escape-farthest fallback — a path walking
            // BACKWARD from commitEnd (bridge stop-family: the continuation from the land
            // stub's end (11,0) came back (11,0)→start pad; adopting it U-turned the bot
            // into a forward/backward ping-pong livelock at the pad, maxX 3.9 of a
            // reachable 11). An EMPTY continuation already means "no further progress" —
            // degrade this one to that, so the segment-end handler ends the journey
            // cleanly at the farthest reachable point. A genuine detour keeps at least
            // one node that beats commitEnd's estimate, so it is never discarded; water
            // is exempt (afloat churn is owned by the anti-spin governor).
            if (ControlConfig.navigatorFromEndNoProgressDiscard
                    && wasFromEnd
                    && res.hasPath()
                    && nav.route.seg.commitEnd != null
                    && !world.isWater(foot)) {
                double endEst = nav.goal.estimate(nav.route.seg.commitEnd);
                double bestEst = Double.MAX_VALUE;
                for (BlockPos n : res.path()) {
                    bestEst = Math.min(bestEst, nav.goal.estimate(n));
                }
                if (bestEst >= endEst - 0.5) {
                    if (GameConfig.navigatorDebug) {
                        LOG.info(
                                "[navigator] from-end continuation makes no progress past {},{},{} (best {} vs end {}) → discard as no-onward-route",
                                nav.route.seg.commitEnd.getX(),
                                nav.route.seg.commitEnd.getY(),
                                nav.route.seg.commitEnd.getZ(),
                                String.format(Locale.ROOT, "%.1f", bestEst),
                                String.format(Locale.ROOT, "%.1f", endEst));
                    }
                    res = new PathFinder.Result(
                            java.util.List.of(), java.util.List.of(), false, res.expanded(), res.ms(), res.finalCost());
                }
            }
            if (wasFromEnd
                    && nav.route.path() != null
                    && nav.route.step() < nav.route.path().size()) {
                // Continuation finished while we're still walking the current
                // segment — stash it and splice only once we reach the segment end
                // (see the step>=size handler), so its start coincides with the bot.
                // Stash even an empty (no-path) result: the segment-end handler
                // reads that as "no further progress" and ends cleanly, rather than
                // letting the kickoff re-launch the same boxed-in search forever.
                nav.route.stashContinuation(res);
            } else if (wasFromEnd && !res.hasPath()) {
                // Already at/over the segment end and no onward route. With frontier
                // planning this may be a STALE continuation (computed before we
                // arrived & loaded the chunks beyond) — re-search fresh before giving
                // up; otherwise we've gone as far as the best effort allows.
                return frontierHoldOrArrive(nav, a, world, p);
            } else if (res.hasPath()) {
                // ANTI-SPIN (water repath-churn): a failed water climb-out (bot can't
                // mount the bank) makes every repath return a best-effort that swims
                // back / circles without the bot's ACTUAL position getting any closer
                // to the goal; following each one U-turns the bot and the repeated
                // U-turns wind the camera (the water spin). This is a TEMPORAL signal
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
                if (d < nav.stallDetect.goalSpin.bestGoalDist - 5.0) {
                    nav.stallDetect.goalSpin.bestGoalDist = d;
                    nav.stallDetect.goalSpin.repathsNoProgress = 0;
                    nav.stallDetect.goalSpin.churnResets = 0;
                } else {
                    nav.stallDetect.goalSpin.repathsNoProgress++;
                }
                // Only a bot actually AFLOAT counts as a water repath: the old
                // `|| isWater(foot.below())` arm also matched a bot standing on dry
                // ground beside a waterfall, so a canyon pacing loop was misread as
                // water churn and the goto ended in a FAKE ARRIVED at (420,-349)
                // (live 2026-06-09, "anti-spin: 13/15 water repaths" on dry land).
                boolean overWaterRepath = p.isInWater() && !p.onGround();
                if (overWaterRepath
                        && !res.goalReached()
                        && nav.stallDetect.goalSpin.repathsNoProgress > CHURN_GIVEUP_CAP) {
                    // A LEGITIMATE go-around also reads as "no progress" here: rounding a
                    // lava arm via the river pushed d above the pre-detour best for 13+
                    // repaths and the give-up fired mid-journey with 440 blocks to go
                    // (live 2026-06-09 — the land exemption in the note above was right,
                    // it just didn't cover WATER detours). So don't give up on the first
                    // stall: RE-BASELINE at the current distance and watch again — a real
                    // detour soon improves on the new baseline (and any true progress
                    // resets churnResets); only a churn that stalls through several fresh
                    // baselines is the genuine unwinnable spin.
                    if (nav.stallDetect.goalSpin.churnResets < CHURN_MAX_RESETS) {
                        nav.stallDetect.goalSpin.churnResets++;
                        nav.stallDetect.goalSpin.bestGoalDist = d;
                        nav.stallDetect.goalSpin.repathsNoProgress = 0;
                        if (GameConfig.navigatorDebug) {
                            LOG.info(
                                    "[navigator] anti-spin: stalled in water (d={}) → re-baseline {}/{}",
                                    String.format(Locale.ROOT, "%.0f", d),
                                    nav.stallDetect.goalSpin.churnResets,
                                    CHURN_MAX_RESETS);
                        }
                    } else {
                        if (GameConfig.navigatorDebug) {
                            LOG.info(
                                    "[navigator] anti-spin: {} water repaths w/o progress after {} re-baselines (d={}) → end best-effort",
                                    nav.stallDetect.goalSpin.repathsNoProgress,
                                    nav.stallDetect.goalSpin.churnResets,
                                    String.format(Locale.ROOT, "%.0f", d));
                        }
                        Keys.forward(a, false);
                        nav.keys.jump(a, false);
                        p.setSprinting(false);
                        return nav.terminalReport(
                                TickStep.ARRIVED, PathTrace.Outcome.SUCCESS, null, "churn-giveup", p.blockPosition());
                    }
                }
                // BLOCK-BUDGET (before bridging, check there are enough blocks, otherwise dig): if this path would
                // place
                // more blocks (bridge/pillar/parkour-place) than the bot carries, it
                // would bridge partway, burn its blocks and strand. Re-search with
                // placing OFF so A* digs through / routes around (break moves need no
                // blocks). Guard with searchSuppressedPlace so the place-off result is
                // adopted as-is (no second reroute / loop).
                if (!nav.route.replay() && !nav.route.seg.searchSuppressedPlace) {
                    int placesNeeded = countPlaceEdges(res.edges());
                    if (placesNeeded > world.placeableBlockCount()) {
                        if (GameConfig.navigatorDebug) {
                            LOG.info(
                                    "[navigator] path needs {} placed blocks, have {} → re-search place-off (dig/around)",
                                    placesNeeded,
                                    world.placeableBlockCount());
                        }
                        nav.route.searchFromFoot(nav.newPathFinder(world).newSearch(foot, nav.goal, true), true);
                        return TickStep.WALKING;
                    }
                }
                // Route hysteresis (navigatorRouteHysteresis): don't let a periodic repath
                // U-turn a healthy walk onto the alternate near-equal route (§55 oscillation).
                // The direction dot doubles as the REPATH-flip expectation alarm: adopting a
                // route whose near-term direction REVERSES the current one is exactly one half
                // of the planner oscillation loop — two adoptions with reversed directions inside
                // 200 ticks is the live signature of "A* alternates two near-equal routes"
                // (the C16 dry-land churn that took three autopsies to see from raw logs).
                boolean keepCurrent = false;
                boolean reversedLeg = false;
                if (nav.route.path() != null
                        && nav.route.step() < nav.route.path().size()
                        && res.path().size() > 3
                        && nav.route.path().size() - nav.route.step() > 3) {
                    BlockPos curAhead = nav.route
                            .path()
                            .get(Math.min(nav.route.step() + 3, nav.route.path().size() - 1));
                    BlockPos newAhead = res.path().get(3);
                    double cax = curAhead.getX() + 0.5 - p.getX();
                    double caz = curAhead.getZ() + 0.5 - p.getZ();
                    double nax = newAhead.getX() + 0.5 - p.getX();
                    double naz = newAhead.getZ() + 0.5 - p.getZ();
                    reversedLeg = (cax * nax + caz * naz) < 0;
                    // Not after a block change: the plan being kept is the one the change cut, and turning
                    // back may be exactly what the new shape asks for.
                    keepCurrent = ControlConfig.navigatorRouteHysteresis
                            && reversedLeg
                            && nav.progress.stepProg.noStepProgressTicks < 20
                            && !afterChange;
                    if (keepCurrent && GameConfig.navigatorDebug) {
                        LOG.info(
                                "[navigator] route-hysteresis: KEEP current path (new route U-turns behind a healthy walk, noStepProg={})",
                                nav.progress.stepProg.noStepProgressTicks);
                    }
                }
                // Dig-commit hold (navigatorDigCommitHoldRepath): an underwater bank dig takes
                // 100-200t (25x mining penalty) but the periodic repath re-routes faster than
                // that, and each adoption discards the held break — vanilla resets the block's
                // progress to zero, so the dig NEVER completes and the water-bank churn loops
                // (CLEAN-K5: dig 368,63,351 dropped at 19t by a route adoption, then DIG-slow
                // 200t grinding the re-chosen riser). While a committed bank dig's break is
                // actually held, keep the current path; the futile-dig release still abandons
                // a hopeless dig, which drops the hold and lets the next repath adopt normally.
                if (!keepCurrent
                        && ControlConfig.navigatorDigCommitHoldRepath
                        && nav.waterClimb.digRiser != null
                        && nav.blocks.breakHeld()) {
                    keepCurrent = true;
                    if (GameConfig.navigatorDebug) {
                        LOG.info(
                                "[navigator] dig-commit-hold: KEEP current path (committed bank dig at {},{},{} in progress)",
                                nav.waterClimb.digRiser.getX(),
                                nav.waterClimb.digRiser.getY(),
                                nav.waterClimb.digRiser.getZ());
                    }
                }
                if (!keepCurrent) {
                    if (ControlConfig.navigatorExpectAlarm && reversedLeg) {
                        nav.exAlarms.noteRepathFlip(nav.stallDetect.escal.tick, foot);
                    }
                    Route.Adoption adoption = RouteAdoption.adopt(nav, res, world, foot);
                    if (adoption.adopted()) {
                        maybeArmPinchEscalation(nav, foot, res);
                    } else {
                        refused(nav, adoption, foot);
                    }
                }
            } else if (nav.route.path() == null) {
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
                    Keys.forward(a, false);
                    p.setSprinting(false);
                    return TickStep.WALKING;
                }
                // SELF-INFLICTED no-path: repeated wedge penalties can wall the
                // bot into a small pocket (every exit charged 600-3600 within a
                // 2.5-block bump) so the search legitimately finds nothing — but
                // the blindness is OURS, not the terrain's (live 2026-06-09 cave
                // pocket: debug.plan with a clean view reached the goal in 12
                // segments from the same foot). Penalties decay in 15 s; hold and
                // let the regular path==null kickoff retry instead of failing the
                // whole goto. Bounded so a genuinely walled-in bot still fails.
                if (world.hasStuckPenalties() && gov.noPathWaitTicks < NO_PATH_WAIT_CAP) {
                    gov.noPathWaitTicks++;
                    if (GameConfig.navigatorDebug && gov.noPathWaitTicks % 100 == 1) {
                        LOG.info(
                                "[navigator] no path but stuck-penalties still live → waiting out decay ({}/{})",
                                gov.noPathWaitTicks,
                                NO_PATH_WAIT_CAP);
                    }
                    Keys.forward(a, false);
                    nav.keys.jump(a, false);
                    p.setSprinting(false);
                    return TickStep.WALKING;
                }
                nav.error = "no path (expanded=" + res.expanded() + ")";
                return nav.terminalReport(
                        TickStep.FAILED,
                        PathTrace.Outcome.NO_PATH,
                        nav.error,
                        "failed:" + nav.error,
                        p.blockPosition());
            }
            // else: search failed but we still have the previous path — keep it.
        }
        // ---- publish: write this phase's products for the downstream phases (TickCtx) ----
        return null;
    }
}
