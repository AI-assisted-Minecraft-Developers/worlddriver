package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.movement.PathSmoothing.*;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 1213–1402): safetyRepath / bridge-hold / periodic re-search kickoff + anti-stuck displacement arming.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickRepath {
    private WalkerTickRepath() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Avatar a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        Player p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        BlockPos searchFoot = cx.frame.searchFoot;
        boolean offPath = cx.stall.offPath;
        boolean breakingEdge = cx.stall.breakingEdge;
        boolean wedged = cx.stall.wedged;
        boolean fellOffPath = cx.stall.fellOffPath;
        boolean fellBelowRoute = cx.stall.fellBelowRoute;
        // ---- original body (byte-identical modulo member prefixes) ----
        boolean safetyRepath = (wk.path == null) || (wk.stuckTicks > STUCK_TICKS) || wedged
                || ((offPath || fellOffPath) && !fellBelowRoute);
        // walkerBridgeHoldRepath (§82): a mid-bridge PERIODIC repath swaps the committed
        // bridgePlace chain for a fresh plan whose first node sits elsewhere (arena live:
        // new node at y+8), and the drive steers off the END of the placed deck into air —
        // the "搭桥中途掉下" signature. Deck length decides the fate: a 19-block deck
        // finishes in 8-9s (inside one 10s repath period, 3/3 clean) while diagonal
        // zig-zag and 40-block decks straddle the period and fell every run. While the
        // current or next edge is a bridgePlace, hold the ROUTINE repath; safety repaths
        // (stuck/wedged/off-path) stay live.
        boolean bridgingNow = false;
        if (BotConfig.walkerBridgeHoldRepath) {
            Move.Edge cbE = wk.edgeAt(wk.step), nbE = wk.edgeAt(wk.step + 1);
            bridgingNow = (cbE != null && "bridgePlace".equals(cbE.move))
                    || (nbE != null && "bridgePlace".equals(nbE.move));
        }
        boolean fullPeriodic = !wk.seg.pathBestEffort && wk.path != null && !bridgingNow
                && wk.ticksSinceRepath > BotConfig.walkerRepathEveryTicks;
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
        if (safetyRepath && !breakingEdge && !(wk.path == null && wk.seg.activeSearch != null)) {
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
            if (wk.unstuck.lastWedgeFoot != null && foot.distSqr(wk.unstuck.lastWedgeFoot) <= 4) {
                // Count EVENTS, not ticks (40-tick cooldown): a trivial search
                // completing same-tick makes every tick a safety repath while
                // the bot is still accelerating from standstill — three such
                // ticks (150 ms, 0.2 blocks of motion) are NOT three stuck
                // loops. A genuinely wedged bot stays put well past 6 s.
                if (wk.unstuck.countCooldown <= 0) {
                    wk.unstuck.countCooldown = 40;
                    if (++wk.unstuck.wedgeRepathsHere >= 3) {
                        wk.unstuck.burstTicks = 14;
                        // Burst AWAY from the waypoint, not yaw+150°: in a concave
                        // pocket (three walls + water) a fixed rotation just grinds
                        // the next wall — live: yaw wound 11 full turns at a mud-
                        // cliff notch, every burst re-entering the same seam.
                        // Backing straight off the wp is the one heading that's
                        // guaranteed open (the bot came from there).
                        BlockPos bwp = (wk.path != null && wk.step < wk.path.size()) ? wk.path.get(wk.step) : null;
                        double bdx = bwp != null ? p.getX() - (bwp.getX() + 0.5) : 0;
                        double bdz = bwp != null ? p.getZ() - (bwp.getZ() + 0.5) : 0;
                        wk.unstuck.burstYaw = (bdx * bdx + bdz * bdz) > 0.01
                                ? (float) Math.toDegrees(Math.atan2(-bdx, bdz))
                                : p.getYRot() + 150f;
                        wk.unstuck.dropWedgeAnchor();   // displaced → next wedge re-anchors fresh
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] anti-stuck: repeated safety repaths at {} → forced displacement burst", foot);
                    }
                }
            } else {
                // First wedge here, or the bot has TRAVELLED >2 blocks off the old
                // anchor (genuine progress) — (re)anchor at the current foot and
                // restart the count. This is the only place lastWedgeFoot is set to an
                // ANCHOR; Unstuck.resetForNewGoal() and Unstuck.dropWedgeAnchor() both
                // clear it to null, so a grep for writes returns three sites, not one.
                wk.unstuck.wedgeRepathsHere = 0;
                wk.unstuck.lastWedgeFoot = foot;
            }
        }
        // Futile-cycle backoff (gap #49-③): while cooling down after a futile search,
        // don't kick off another one — the churn loop otherwise relaunches a full-budget
        // A* every tick from the same foot toward the same unreachable goal.
        if (wk.searchGov.searchBackoffTicks > 0) wk.searchGov.searchBackoffTicks--;
        if (!wk.replayMode && (safetyRepath || fullPeriodic) && wk.seg.activeSearch == null
                && wk.searchGov.searchBackoffTicks == 0 && !wk.searchGov.futileLatched(foot)) {
            // Stuck too long on a move the Walker can't execute (a steep stepUp it
            // slides off, a pillar it can't ground)? Blacklist that node so this
            // re-search routes AROUND the wedge instead of re-planning into it —
            // otherwise A* keeps returning the same unclimbable spot and the bot
            // bobs there until an unrelated repath happens to diverge (a 600+-tick
            // stall observed on a steep mountain). Soft + decaying, so a sole route
            // is still taken eventually.
            penalizeWedgeNodes(wk, p, world, foot, wedged, fellOffPath);
            wk.seg.activeSearch = wk.newPathFinder(world).newSearch(searchFoot, wk.goal);
            wk.seg.searchFromEnd = false;
            wk.seg.searchSuppressedPlace = false;    // normal search: placing allowed; budget re-checked on result
            wk.seg.pendingSegment = null;            // a foot-search supersedes any stashed continuation
            wk.ticksSinceRepath = 0;
        } else if (!wk.replayMode && wk.seg.pathBestEffort && wk.seg.commitEnd != null
                && wk.seg.activeSearch == null && wk.seg.pendingSegment == null
                && wk.searchGov.searchBackoffTicks == 0 && !wk.searchGov.futileLatched(foot)) {
            // Eagerly precompute the next best-effort segment from the committed end.
            wk.seg.activeSearch = wk.newPathFinder(world).newSearch(wk.seg.commitEnd, wk.goal);
            wk.seg.searchFromEnd = true;
            wk.seg.searchSuppressedPlace = false;
            wk.ticksSinceRepath = 0;
        }
        // ANTI-STUCK displacement burst: drive a fixed turned heading + jump for a
        // few ticks so the body physically leaves the wedge cell. Path/edges stay
        // as-is; once the burst ends the normal logic sees a NEW foot (offPath or
        // the in-flight re-search lands) and plans from genuinely new ground.
        // STEPUP BACKOFF-RETRY drive (walkerStepUpBackoffRetry): armed by the dryStepUp
        // grind detector below — drive straight BACK from the riser (camera-frame, no yaw
        // slam, no jump) for a few ticks to open sprint runway, then let the normal
        // approach re-launch the early jump WITH momentum. Mirrors the anti-stuck burst's
        // commandMove decoupling one block above.
        if (wk.stepUpBackoff.ticks > 0) {
            wk.stepUpBackoff.ticks--;
            double sbd = Math.toRadians(angleDiff(p.getYRot(), wk.stepUpBackoff.yaw));
            a.commandMove((float) -Math.sin(sbd), (float) Math.cos(sbd));
            wk.avatarJump(a, false);
            p.setSprinting(false);
            return Walker.Step.WALKING;
        }
        if (wk.stepUpBackoff.cooldown > 0) wk.stepUpBackoff.cooldown--;
        if (wk.unstuck.burstTicks > 0) {
            wk.unstuck.burstTicks--;
            // Drive the displacement in the CAMERA frame instead of slamming yaw:
            // p.setYRot here wound the camera 8+ full turns in a water-cave wedge
            // cluster (bursts every ~6 s, each with a different escape bearing,
            // every one yanking the view — raw yaw hit -3109°). commandMove pushes
            // the body along the escape bearing with ZERO camera motion: AvatarInput
            // pre-rotates the impulse by Δ = bearing − cameraYaw and vanilla
            // travel() rotates it back, so the net push is along unstuck.burstYaw exactly
            // as before — the same decoupling the main walk branch already uses.
            double bd = Math.toRadians(angleDiff(p.getYRot(), wk.unstuck.burstYaw));
            a.commandMove((float) -Math.sin(bd), (float) Math.cos(bd));
            // Floor-gated hop (walkerRecoveryHopFloorGate): the burst's jump is an UNAIMED
            // ballistic arc — on narrow footing beside a lethal drop it clears the deck
            // where the stride floor-guard (grounded-velocity only) can no longer help.
            // The grounded displacement is kept: drift toward a lip stays covered by the
            // guard's sneak-pin; only the airborne launch is unguarded, so only it is cut.
            boolean burstJump = !(BotConfig.walkerRecoveryHopFloorGate
                    && lethalDropWithinHopRange(world, p, foot));
            if (burstJump) wk.jumpTag = "unstuckBurst";
            wk.avatarJump(a, burstJump);
            p.setSprinting(false);
            return Walker.Step.WALKING;
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        return null;
    }

    /**
     * Price out the cells a wedged body keeps re-planning into, so the re-search that follows
     * routes AROUND the obstruction instead of straight back at it.
     *
     * <p><b>Where the cut is, and why here.</b> Lifted verbatim out of {@link #run}, which had
     * drifted two lines over its budget. This is the one block in that method where nothing flows
     * back out: it reads {@code wk}/{@code world}/{@code foot}, calls only
     * {@code world.penalizeStuckNode}, declares no local that the rest of the tick reads, and
     * contains no {@code return} of its own — so the guard became an early return and the call
     * site became one line. Nothing else in {@code run} separates that cheaply; every other block
     * either returns a {@link Walker.Step} that ends the tick, or writes a local the next block
     * consumes. Bodies are unchanged, which is the standing rule for this file.
     *
     * <p><b>The parameter that had to be checked before moving anything.</b> {@code p} is already
     * declared {@code Player} at the call site ({@code cx.frame.p}), so this signature is NOT a
     * widening. Hard rule 12 exists because handing a client type to a wider formal from a
     * dual-loaded class kills the dedicated server at class-load time, and "extract a method" is
     * exactly the tidy-up that disguise hides in — {@code bot/movement/**} is dual-loaded, so this
     * is safe only because the local was never the client type. Re-check it, do not assume it.
     */
    private static void penalizeWedgeNodes(Walker wk, Player p, WorldView world, BlockPos foot,
                                           boolean wedged, boolean fellOffPath) {
        if (!((wk.stuckTicks > STUCK_TICKS || wedged || fellOffPath)
                && wk.path != null && wk.step < wk.path.size())) return;
        // fellOffPath included: the step node the bot FELL AWAY from is a
        // demonstrably fragile traverse (mountain high route) — charge it
        // so the immediate re-search doesn't commit the same brittle line.
        world.penalizeStuckNode(wk.path.get(wk.step));
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
        // round76 NE leg at 2307,62,2579). The land-churn escape already
        // ESCALATES its priced-out radius each repeat; the safety-repath
        // nose charge did not, the asymmetry IS the deep-water latency.
        // When the SAME foot wedges repeatedly IN WATER, widen the charge
        // with the existing same-foot repath counter so the bowl is priced
        // out in a few cycles, not a dozen. Land/first-wedge keep radius 0
        // (loop runs once at the nose) — behaviour there is unchanged.
        // Soft+decaying → a sole route is still taken; gated to repeated
        // water wedges so it can't misfire on legitimate slow progress
        // (the foot must stay put across repaths to grow the counter).
        int chargeR = world.isWater(foot) ? Math.min(wk.unstuck.wedgeRepathsHere, 2) : 0;
        for (int dx = -chargeR; dx <= chargeR; dx++)
            for (int dz = -chargeR; dz <= chargeR; dz++) {
                BlockPos c = nose.offset(dx, 0, dz);
                world.penalizeStuckNode(c);
                world.penalizeStuckNode(c.above());
            }
    }
}
