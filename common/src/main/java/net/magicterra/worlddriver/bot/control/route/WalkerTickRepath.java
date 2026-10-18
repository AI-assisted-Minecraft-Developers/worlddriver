package net.magicterra.worlddriver.bot.control.route;

import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.*;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.bot.control.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.control.WalkerGeometry.*;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerTickCtx;
import net.magicterra.worlddriver.bot.control.controller.Recovery;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 1213–1402): safetyRepath / bridge-hold / periodic re-search kickoff + anti-stuck displacement arming.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
public final class WalkerTickRepath {
    private WalkerTickRepath() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    public static TickStep run(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        BlockPos searchFoot = cx.frame.searchFoot;
        boolean offPath = cx.stall.offPath;
        boolean breakingEdge = cx.stall.breakingEdge;
        boolean wedged = cx.stall.wedged;
        boolean fellOffPath = cx.stall.fellOffPath;
        boolean fellBelowRoute = cx.stall.fellBelowRoute;
        // ---- original body (byte-identical modulo member prefixes) ----
        boolean safetyRepath = (wk.route.path() == null) || (wk.stuckTicks > STUCK_TICKS) || wedged
                || ((offPath || fellOffPath) && !fellBelowRoute);
        // walkerBridgeHoldRepath (§82): a mid-bridge PERIODIC repath swaps the committed
        // bridgePlace chain for a fresh plan whose first node sits elsewhere (arena live:
        // new node at y+8), and the drive steers off the END of the placed deck into air —
        // the signature of falling off partway through a bridge. Deck length decides the fate: a 19-block deck
        // finishes in 8-9s (inside one 10s repath period, 3/3 clean) while diagonal
        // zig-zag and 40-block decks straddle the period and fell every run. While the
        // current or next edge is a bridgePlace, hold the ROUTINE repath; safety repaths
        // (stuck/wedged/off-path) stay live.
        boolean bridgingNow = false;
        if (ControlConfig.walkerBridgeHoldRepath) {
            Move.Edge cbE = wk.edgeAt(wk.route.step()), nbE = wk.edgeAt(wk.route.step() + 1);
            bridgingNow = (cbE != null && "bridgePlace".equals(cbE.move))
                    || (nbE != null && "bridgePlace".equals(nbE.move));
        }
        boolean fullPeriodic = !wk.route.seg.pathBestEffort && wk.route.path() != null && !bridgingNow
                && wk.ticksSinceRepath > ControlConfig.walkerRepathEveryTicks;
        // ANTI-STUCK (forced displacement, Baritone-UnstuckChain-style): a
        // re-search from the SAME foot is deterministic — when the blocker is
        // a gap the executor can't thread but the planner prices passable
        // (a sole route, so the soft penalty never reroutes it), every repath
        // returns the same segment and the bot stands forever (live
        // 2026-06-10: 155 s frozen at a flooded bank corner against a diagDown
        // it couldn't enter). Counted on EVERY safety-repath tick (cooldown-
        // debounced), NOT inside the search-kickoff branch: at a hard pocket
        // the big escape/continuation searches monopolise activeSearch for
        // 9+ s each, so kickoff ticks are minutes apart and a counter gated
        // there never reaches three (live: 110 s wedged in a waterfall-pool
        // corner, counter stuck at 1-2). Three counted events from (about)
        // the same spot → physically MOVE so the next search starts elsewhere.
        // breakingEdge exempt: hand-mining a stairUpBreak holds the bot in
        // place 5-10 s per block — stuckTicks sails past its gate and the
        // burst YANKS the half-mined block's miner away (live: 17 bursts up
        // one staircase, each restarting the dig). The break watchdog
        // (breakTimeoutTicks) already covers a dig that's truly stuck.
        // Waiting on an in-flight search is PLANNER latency, not an execution wedge:
        // path==null alone makes safetyRepath true every hold tick, so over water —
        // where big searches run 1-1.7 s back-to-back and the current drifts the bot
        // slowly (foot stays within the 4-blk cell) — the event counter filled in
        // exactly 3 cooldowns (6 s) and burst the bot away from the search origin,
        // chaining rejects (round29 swamp: bursts every 6 s while cruising at 1.5 b/s).
        if (safetyRepath && !breakingEdge && !(wk.route.path() == null && wk.route.seg.activeSearch != null)) {
            // lastWedgeFoot ANCHORS the spot where this wedge began — it must NOT be
            // re-stamped to `foot` every tick. A buoyant bot CRUISING across open
            // water bobs ±0.04 vertically and so keeps failing the tight node-reach
            // gate; noStepProgressTicks climbs, `wedged` (hence safetyRepath) goes
            // true, and the bot enters this block every tick WHILE still swimming
            // ~1.2 b/s toward the goal. If the anchor followed the foot tick-by-tick
            // the "<=4 of the anchor" test would compare against LAST tick's foot
            // (0.06 b away) → always true → the event counter filled every cooldown
            // and burst the cruising bot every ~6 s (live 2026-06-15: forward bursts
            // at x=2526,2534,2542,2596,2604 mid-ocean while net-progressing). Anchor
            // ONLY when (re)starting the count; a bot that travels >2 blocks off the
            // anchor lands in the else branch, resets, and re-anchors — so only a bot
            // that genuinely stays within 2 blocks for 3 cooldowns (6 s) ever bursts.
            if (wk.recovery.lastWedgeFoot != null && foot.distSqr(wk.recovery.lastWedgeFoot) <= 4) {
                // Count EVENTS, not ticks (40-tick cooldown): a trivial search
                // completing same-tick makes every tick a safety repath while
                // the bot is still accelerating from standstill — three such
                // ticks (150 ms, 0.2 blocks of motion) are NOT three stuck
                // loops. A genuinely wedged bot stays put well past 6 s.
                if (wk.recovery.countCooldown <= 0) {
                    wk.recovery.countCooldown = 40;
                    if (++wk.recovery.wedgeRepathsHere >= 3) {
                        BlockPos bwp = (wk.route.path() != null && wk.route.step() < wk.route.path().size()) ? wk.route.path().get(wk.route.step()) : null;
                        wk.recovery.burst(Recovery.WEDGE_BURST_TICKS, p, bwp);
                        wk.recovery.dropWedgeAnchor();   // displaced → next wedge re-anchors fresh
                        if (GameConfig.walkerDebug)
                            LOG.info("[walker] anti-stuck: repeated safety repaths at {} → forced displacement burst", foot);
                    }
                }
            } else {
                // First wedge here, or the bot has TRAVELLED >2 blocks off the old
                // anchor (genuine progress) — (re)anchor at the current foot and
                // restart the count. This is the only place lastWedgeFoot is set to an
                // ANCHOR; Recovery.reset(GOAL) and Recovery.dropWedgeAnchor() both
                // clear it to null, so a grep for writes returns three sites, not one.
                wk.recovery.wedgeRepathsHere = 0;
                wk.recovery.lastWedgeFoot = foot;
            }
        }
        // Futile-cycle backoff (gap #49-③): while cooling down after a futile search,
        // don't kick off another one — the churn loop otherwise relaunches a full-budget
        // A* every tick from the same foot toward the same unreachable goal.
        if (wk.searchGov.searchBackoffTicks > 0) wk.searchGov.searchBackoffTicks--;
        // A block on the plan ahead changed shape (someone built across it, broke the bridge, a door
        // shut). Whatever search is in flight was started on the old shape, so it goes too.
        boolean blockChanged = wk.route.blockChangeDue(p)
                && wk.searchGov.searchBackoffTicks == 0 && !wk.searchGov.futileLatched(foot);
        if (blockChanged) wk.route.abortSearch();
        if (!wk.route.replay() && (safetyRepath || fullPeriodic || blockChanged) && wk.route.seg.activeSearch == null
                && wk.searchGov.searchBackoffTicks == 0 && !wk.searchGov.futileLatched(foot)) {
            // Stuck too long on a move the Walker can't execute (a steep stepUp it
            // slides off, a pillar it can't ground)? Blacklist that node so this
            // re-search routes AROUND the wedge instead of re-planning into it —
            // otherwise A* keeps returning the same unclimbable spot and the bot
            // bobs there until an unrelated repath happens to diverge (a 600+-tick
            // stall observed on a steep mountain). Soft + decaying, so a sole route
            // is still taken eventually.
            kickoff(wk, p, world, foot, wedged, fellOffPath, offPath, fellBelowRoute, fullPeriodic);
            PathFinder.Search search = wk.newPathFinder(world).newSearch(searchFoot, wk.goal);
            if (blockChanged) noteBlockChange(wk, wk.route.searchAfterBlockChange(search), foot);
            else wk.route.searchFromFoot(search, false);   // placing allowed; budget re-checked on result
            wk.ticksSinceRepath = 0;
        } else if (!wk.route.replay() && wk.route.seg.pathBestEffort && wk.route.seg.commitEnd != null
                && wk.route.seg.activeSearch == null && wk.route.seg.pendingSegment == null
                && wk.searchGov.searchBackoffTicks == 0 && !wk.searchGov.futileLatched(foot)
                && continuationInLoadedReach(wk, p)) {
            // Eagerly precompute the next best-effort segment from the committed end.
            wk.route.searchFromEnd(wk, world);
            wk.ticksSinceRepath = 0;
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        return null;
    }

    /**
     * A continuation planned from a segment end far ahead of the body searches out past the loaded
     * chunks, and its commit lands on whichever edge row has loaded by then: R1 committed from
     * 1526,71,-211 to 1473,66,-319 or, with one chunk row fewer, to 1435,67,-303 — the west branch,
     * 8-10 s slower. Waiting until the body is within {@code walkerContinuationMaxLead} of the end
     * keeps end + horizon inside what the body has loaded.
     */
    private static boolean continuationInLoadedReach(Walker wk, LivingEntity p) {
        int lead = ControlConfig.walkerContinuationMaxLead;
        if (lead <= 0) return true;
        double dx = wk.route.seg.commitEnd.getX() + 0.5 - p.getX(), dz = wk.route.seg.commitEnd.getZ() + 0.5 - p.getZ();
        return dx * dx + dz * dz <= (double) lead * lead;
    }

    /** One line per block-change replan, the first few per walker; {@link WalkerTallies#blockReplans} counts all. */
    private static void noteBlockChange(Walker wk, String where, BlockPos foot) {
        if (++wk.tallies.blockReplans <= BLOCK_REPLANS_LOGGED)
            LOG.info("[walker] block changed at {} → replanning from {} (owner={})", where, foot.toShortString(), wk.owner);
    }

    private static final int BLOCK_REPLANS_LOGGED = 8;

    /**
     * Price out the cells a wedged bot keeps re-planning into, so the re-search that follows
     * routes AROUND the obstruction instead of straight back at it.
     *
     * <p><b>Where the cut is, and why here.</b> Lifted verbatim out of {@link #run}, which had
     * drifted two lines over its budget. This is the one block in that method where nothing flows
     * back out: it reads {@code wk}/{@code world}/{@code foot}, calls only
     * {@code world.penalizeStuckNode}, declares no local that the rest of the tick reads, and
     * contains no {@code return} of its own — so the guard became an early return and the call
     * site became one line. Nothing else in {@code run} separates that cheaply; every other block
     * either returns a {@link TickStep} that ends the tick, or writes a local the next block
     * consumes. Bodies are unchanged, which is the standing rule for this file.
     *
     * <p><b>The parameter that had to be checked before moving anything.</b> {@code p} is already
     * declared {@code Player} at the call site ({@code cx.frame.p}), so this signature is NOT a
     * widening. Hard rule 12 exists because handing a client type to a wider formal from a
     * dual-loaded class kills the dedicated server at class-load time, and "extract a method" is
     * exactly the tidy-up that disguise hides in — {@code bot/movement/**} is dual-loaded, so this
     * is safe only because the local was never the client type. Re-check it, do not assume it.
     */
    /** The foot-search kickoff's front door: names WHICH trigger fired (under walkerDebug), then the
     *  wedge penalty. A search that supersedes a route the walker had just adopted is otherwise
     *  invisible — the log shows the search, never the reason — and every trigger here looks the
     *  same from outside (client lane 2026-09-06: an adopted lane re-searched on its first tick). */
    private static void kickoff(Walker wk, LivingEntity p, WorldView world, BlockPos foot, boolean wedged,
                                boolean fellOffPath, boolean offPath, boolean fellBelowRoute, boolean periodic) {
        if (GameConfig.walkerDebug)
            LOG.info("[walker] foot-search kickoff: pathNull={} stuck={} wedged={} offPath={} fellOff={} belowRoute={} periodic={} step={}/{}",
                    wk.route.path() == null, wk.stuckTicks > STUCK_TICKS, wedged, offPath, fellOffPath, fellBelowRoute,
                    periodic, wk.route.step(), wk.route.path() == null ? 0 : wk.route.path().size());
        penalizeWedgeNodes(wk, p, world, foot, wedged, fellOffPath);
    }

    private static void penalizeWedgeNodes(Walker wk, LivingEntity p, WorldView world, BlockPos foot,
                                           boolean wedged, boolean fellOffPath) {
        if (!((wk.stuckTicks > STUCK_TICKS || wedged || fellOffPath)
                && wk.route.path() != null && wk.route.step() < wk.route.path().size())) return;
        // fellOffPath included: the step node the bot FELL AWAY from is a
        // demonstrably fragile traverse (mountain high route) — charge it
        // so the immediate re-search doesn't commit the same brittle line.
        world.penalizeStuckNode(wk.route.path().get(wk.route.step()));
        // Also penalize the cells at the bot's NOSE. After string-pulling
        // the current step node can sit many blocks past the actual
        // obstruction (live 2026-06-09: afloat in a 1-wide flooded crevice,
        // carrot 10 blocks south, zero displacement for ~50 s) — punishing
        // only that far carrot leaves the choke point itself cheap, so
        // every re-search threads the same impassable gap from the same
        // foot and returns the same segment. Charging the blocks directly
        // ahead makes the next search route AROUND the choke (other bank /
        // over the top) instead of back into it.
        BlockPos nose = foot.relative(p.getDirection());
        // In a deep-water bowl pocket a single-cell nose charge barely
        // shifts A*'s cost — an adjacent equally-cheap water cell funnels
        // the next search straight back in, so the pocket only prices out
        // after a dozen slow over-water repaths (~27 s observed live,
        // round76 NE walk at 2307,62,2579). The land-churn escape already
        // ESCALATES its priced-out radius each repeat; the safety-repath
        // nose charge did not, the asymmetry IS the deep-water latency.
        // When the SAME foot wedges repeatedly IN WATER, widen the charge
        // with the existing same-foot repath counter so the bowl is priced
        // out in a few cycles, not a dozen. Land/first-wedge keep radius 0
        // (loop runs once at the nose) — behaviour there is unchanged.
        // Soft+decaying → a sole route is still taken; gated to repeated
        // water wedges so it can't misfire on legitimate slow progress
        // (the foot must stay put across repaths to grow the counter).
        int chargeR = world.isWater(foot) ? Math.min(wk.recovery.wedgeRepathsHere, 2) : 0;
        for (int dx = -chargeR; dx <= chargeR; dx++)
            for (int dz = -chargeR; dz <= chargeR; dz++) {
                BlockPos c = nose.offset(dx, 0, dz);
                world.penalizeStuckNode(c);
                world.penalizeStuckNode(c.above());
            }
    }
}
