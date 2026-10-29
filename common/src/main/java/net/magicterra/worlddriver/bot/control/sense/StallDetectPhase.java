package net.magicterra.worlddriver.bot.control.sense;

import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.bot.control.route.ProgressPhase;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.*;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.*;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.*;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.control.controller.Recovery;


/**
 * Mechanical extraction of {@code Navigator#tickInner} (pre-split lines 838–1212): tick budget, divergent best-effort, wedge + fell-off-path family, boxed-pocket churn, wall-corner ram.
 * One phase of the per-tick pipeline — see {@link Navigator#tickInner} for the driver and
 * {@link TickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Navigator.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
public final class StallDetectPhase {
    public boolean forceFellOffPath;   // task#82: AscendMovement returned UNREACHABLE/FAILED last delegated tick → OR into the next tick's fellOffPath so the proven re-route fires

    /** Water anti-spin governor (task#96 step B9): consumed by the search/stall gates.
     *  {@link GoalSpin#resetForNewGoal()} (start only) clears the baselines;
     *  goalSpin.churnResets deliberately PERSISTS across goals (fresh-baseline grants are a
     *  per-bot budget, not per-journey — do NOT add it to the reset). */
    public final GoalSpin goalSpin = new GoalSpin();

    /** Land boxed-pocket churn window (task#96 step B4), owned by StallDetectPhase;
     *  the wedge timers and BoxedChurn read hColRamTicks.
     *  {@link BoxedChurn#resetForNewGoal()} runs in start only (replan never
     *  cleared this window); hColRamTicks has NO reset site anywhere — it self-zeroes
     *  on every collision-free tick. The water anti-spin's goalSpin.churnResets stays a loose
     *  field (cross-goal persistence — see its doc). */
    public final BoxedChurn churn = new BoxedChurn();

    /** Sticky steep-barrier escalation clock (task#96 step B4): tick is the monotonic
     *  per-navigator tick counter (incremented by PreludePhase, NEVER reset — the
     *  sticky timer compares against it); armed by the churn window (StallDetectPhase)
     *  and the proactive pinch check ({@link Navigator#maybeArmPinchEscalation}); read live by this
     *  navigator's finders through {@link NavigatorFinders}. Both journey resets
     *  {@link EscalationClock#disarm()} so the escalation never leaks into the next goto. */
    public final EscalationClock escal = new EscalationClock();

    /** Disarm the escalation on any reset; the anti-spin and churn baselines go only with the goal. */
    public void reset(ResetScope scope) {
        escal.disarm();
        if (scope == ResetScope.REPLAN) return;
        goalSpin.resetForNewGoal();
        churn.resetForNewGoal();
    }

    /** Blocks² from the churn window's base that count as having left it, and as being back. */
    private static final int CHURN_CYCLE_LEAVE_SQ = 16, CHURN_CYCLE_RETURN_SQ = 4;

    /**
     * Per tick: the wall-corner ram count, and whether the body has left this churn window's base
     * and come back to it on the same row. Net displacement alone reads a limit cycle wider than
     * {@code CHURN_MIN_MOVE_SQ} as progress whenever the window closes at its far end:
     * {@code wd.boxedChurnEscalate} cycled between the pocket's back wall and its mouth, ten blocks
     * apart, and every other window reset the escape count to zero. A cycle comes back to where it
     * stood a window ago, whatever its width. So can a route round a wall or an inlet, which is why a
     * window that got nearer the goal than ever before ({@code nearer}) is no cycle: a pocket's loop
     * stops doing that after its first lap.
     */
    private void trackWindow(Navigator nav, LivingEntity p, BlockPos foot, boolean nearer) {
        if (p.horizontalCollision) churn.hColRamTicks++; else churn.hColRamTicks = 0;
        BlockPos b = churn.base;
        if (b == null || churn.windowTicks == 0) { churn.cycle = 0; return; }
        if (nearer) { churn.cycle = -1; return; }   // sticks until the window closes
        int dx = foot.getX() - b.getX(), dz = foot.getZ() - b.getZ(), d2 = dx * dx + dz * dz;
        if (churn.cycle == 0 && d2 >= CHURN_CYCLE_LEAVE_SQ) churn.cycle = 1;
        else if (churn.cycle == 1 && d2 <= CHURN_CYCLE_RETURN_SQ && Math.abs(foot.getY() - b.getY()) <= 1) churn.cycle = 2;
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    public TickStep run(Navigator nav, TickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (TickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        // ---- original body (byte-identical modulo member prefixes) ----

        // Hard tick budget: prevents infinite walking when A* returns a partial path
        // for an unreachable goal (best-effort fallback path > 1 node satisfies ok()).
        // Reset on real progress (closer to goal) so legitimate long walks aren't killed.
        double d = nav.goal.estimate(foot); boolean nearer = d < goalSpin.bestDistToGoal - 0.5;
        if (nearer) {
            goalSpin.bestDistToGoal = d;
            nav.totalTicks = 0;
        } else if (nav.blocks.breakHeld() || nav.waterClimb.digging) {
            // Actively mining a block — a planned break edge swinging (breakHeld) OR the
            // block-less climb-out dig from last tick (waterClimbDigging, still set; reset
            // below at line ~755). The bot IS progressing: slowly breaking a riser, not
            // translating, so goal-distance stays flat for the whole ~750-tick hand-mine of
            // a stone block. HOLD the no-progress give-up here, or it aborts a legitimate
            // slow climb-out mid-break (faithful-stone arena: committed 843 ticks to break
            // one stone, reached y207.85, then FAILED at tick 1221 = navigatorTotalTickBudget).
            // True deadlocks are still caught by the per-riser dig-commit cap, the
            // breakTimeoutTicks wedge watchdog, and the fact that each completed break lifts
            // Y toward the goal → bestDistToGoal drops → totalTicks resets on the next step.
        } else if (++nav.totalTicks > ControlConfig.navigatorTotalTickBudget) {
            nav.error = "no progress for " + ControlConfig.navigatorTotalTickBudget + " ticks (best dist=" + Math.round(goalSpin.bestDistToGoal) + ")";
            return nav.terminalReport(TickStep.FAILED, PathTrace.Outcome.STUCK, nav.error, "failed:" + nav.error, p.blockPosition());
        }

        // Re-searching toward a FAR goal mid-segment returns a DIVERGENT best-effort
        // path that can point backward, so the bot zig-zags/backtracks. Baritone-style
        // segment commitment avoids that by committing to each best-effort segment and
        // only splicing the next one (computed from the segment's END) once the bot is
        // standing there. Two kinds of re-search, kept distinct so this stays
        // backtrack-free:
        //   • SAFETY / FULL — re-search from the CURRENT foot and splice the
        //     result immediately. Fires when we have no path, we're stuck, we've
        //     been knocked off the path, or (for a path that actually reaches the
        //     goal) on the periodic refresh interval.
        //   • CONTINUATION — for a committed best-effort partial: the moment a
        //     segment is adopted, pre-compute the NEXT segment in the background
        //     starting from THIS segment's END (commitEnd), so the search overlaps
        //     the walk and the result is ready to splice the instant we arrive.
        //     Adoption is DEFERRED to the segment end (see below) so the new path
        //     always starts where the bot will be — no backward yaw flip.
        boolean offPath = offPath(nav, foot, p);
        // Jitter-immune wedge: stuck on a node the Navigator can't complete (e.g. a
        // ground-blocked fallN). An edge that's legitimately BREAKING blocks gets a
        // far longer leash: bare-handed stone takes ~150 ticks/block — well past
        // WEDGE_TICKS — so the plain threshold interrupted every mid-dig, penalized
        // the dig node (the only exit), and after a few cycles the accumulating
        // stuck-penalties walled off a small cave pocket entirely → 60k-node
        // pathLen=0 → goto FAILED (live 2026-06-09, mountain cave). The break
        // actuator has its own breakTimeoutTicks watchdog, so a dig that's truly
        // stuck (no line of sight, unreachable cell) still escapes after the sum.
        Move.Edge wedgeEdge = (nav.route.path() != null && nav.route.step() < nav.route.path().size()) ? nav.route.edgeAt(nav.route.step()) : null;
        // "Actively mining" = the break-edge leash/exemption applies ONLY while the
        // pick is actually swinging (breakHold set by last tick's actuator). A
        // breaking edge whose APPROACH is wedged — hCol-pinned short of the dig
        // stance, attack never starts (round37: stairUpBreak under a lake, bot
        // buoyed 0.8 blocks off the within-gate for minutes) — must fall through
        // to the normal wedge timer + anti-stuck, or the exemption turns a
        // mis-positioned dig into a silent permanent deadlock.
        // The block-less bank-dig (deep-water +1 climb-out, no place block) hand-mines
        // a SYNTHESIZED riser that no path edge planned — its edge's toBreak is empty,
        // so the test above can't see it. Treat a dig swung last tick exactly like a
        // breaking edge: extend the wedge leash and exempt it from the anti-stuck burst,
        // or the burst yanks the buoyant bot off the riser mid-dig and it never tops out
        // (the live continuous-context churn the clean single-bank arena can't reproduce).
        cx.stall.dugLastTick = nav.waterClimb.digging;
        boolean breakingEdge = (wedgeEdge != null && !wedgeEdge.toBreak.isEmpty() && nav.blocks.breakHeld())
                || cx.stall.dugLastTick;
        nav.waterClimb.digging = false;   // re-armed below only if the block-less dig actuator runs this tick
        int wedgeLimit = breakingEdge ? WEDGE_TICKS + ControlConfig.breakTimeoutTicks : WEDGE_TICKS;
        boolean wedged = nav.progress.stepProg.noStepProgressTicks > wedgeLimit;
        // Fell off the committed climb path VERTICALLY (the current node is more than a jump
        // above/below the feet — a fumbled pillar/parkour dropped the bot below the route, or
        // it slid down). A CONTINUATION search (launched from commitEnd, not the foot) can
        // only return a path from where the bot ISN'T, so letting it finish just wastes ~6s of
        // bobbing toward a stale far carrot (the #3 far-climb-target stall). Preempt it so the
        // foot-search below restarts from the actual position NOW. Only cancels a continuation;
        // an in-flight foot-search is already from the right place and is left to finish.
        // +2/+3 ASCENT-RAM SLIDE-BACK (steep diagonal-staircase recovery gap): on a steep
        // mountain staircase the bot drifts off a √2 diagUp and slides 2-3 blocks BELOW the
        // ascent node, so that node becomes an effective +2/+3 cardinal ram — too tall for a
        // single-jump stepUp (≤+1), yet UNDER the fellOffPath >3 fall threshold, so it gets
        // NEITHER the stepUp actuator NOR the fall recovery and bob-rams the riser ~16 s until
        // the slow wedge burst yanks it BACKWARD off the step (live 2026-06-24 west mountain:
        // move=diagUp, node -1987,111 from grounded foot y109, cur2=0.640 constant, hCol=true,
        // 332 ticks ≈ 16.6 s on ONE node; pillar-recover never armed because the only blocks
        // left were falling sand/gravel that holdPlaceable rejects). Detect the grounded,
        // persisted ram (node ≥2 above the feet, no step progress for ASCENT_SLIDE_RECOVER_TICKS) and
        // fold it into fellOffPath so the SAME proven recovery fires NOW: pillar back up with
        // blocks (fellBelowRoute), else a fresh foot-search that blacklists the rammed node
        // (line ~1069) and re-routes from the actual lower position — both replace the futile
        // bob with a fast reachable climb. No PLANNED move places a node ≥2 above a grounded
        // foot (steps/parkour/pillar all rise +1), so ≥2 reliably means a slide-back; pillarUp
        // and parkour edges keep their own dedicated handling.
        boolean ascentRamSlide = nav.route.path() != null && nav.route.step() < nav.route.path().size() && p.onGround()
                && wedgeEdge != null && !"pillarUp".equals(wedgeEdge.move)
                && (wedgeEdge.move == null || !wedgeEdge.move.startsWith("parkour"))
                && nav.route.path().get(nav.route.step()).getY() - foot.getY() >= 2
                && nav.progress.stepProg.noStepProgressTicks > ASCENT_SLIDE_RECOVER_TICKS;
        // +1 DESCENT-RAM blind spot (mirror of ascentRamSlide above): the bot drifted 1 block ABOVE
        // the planned y-level onto a dead-end platform whose only forward exit is a step-down the
        // planner CORRECTLY rejected — StepDown's passthrough+1 / DiagonalDescend's to+2 head-clearance
        // bars the move when an overhang sits at the destination column's launch-head height, so A* never
        // routes THROUGH this cell; the bot reached it by execution drift. 1 below is UNDER the
        // fellOffPath >3 threshold AND not >=2 above (ascentRamSlide), so it lands in the mirror blind
        // spot and only the slow WEDGE_TICKS (100) burst rescues it (live 2026-06-24 V2 climb replay:
        // foot y85, node -771,84 one below, head-rams the -771,86 overhang, cur2=0.640 constant, ~66
        // ticks / 3.3 s on one node, ~50% of climb runs via replan). Detect grounded + node EXACTLY 1
        // below + horizontalCollision (the head/body ram) + persisted stall → fold into fellOffPath so
        // the fresh foot-search blacklists the unreachable node and re-routes from the actual position
        // NOW. The hCol + STEPUP_FREEZE_TICKS gate keeps it OFF a normal step-down (lands clean, no ram,
        // advances in 1-3 ticks) and off any non-ramming stall (the general wedge timer owns those).
        boolean descentRamStuck = nav.route.path() != null && nav.route.step() < nav.route.path().size() && p.onGround()
                && wedgeEdge != null && !"pillarUp".equals(wedgeEdge.move)
                && (wedgeEdge.move == null || !wedgeEdge.move.startsWith("parkour"))
                && foot.getY() - nav.route.path().get(nav.route.step()).getY() == 1
                && p.horizontalCollision
                && nav.progress.stepProg.noStepProgressTicks > STEPUP_FREEZE_TICKS;
        // Phase-3 bob/jitter-immune ram wedge: the arc-length s has not advanced for ARC_WEDGE_TICKS while
        // horizontalCollision (the count lives in arcShadowTick). This is the STRUCTURAL replacement for the
        // descentRamStuck/ascentRamSlide family above — they hang on noStepProgressTicks, which the ram-jitter's
        // sub-block 3D-new-lows zero so the gate never fills (live -672,94: driveF=1, aim=4°, hCol, node 1 below,
        // descentRamStuck's exact case, yet it bobbed >34 t with no recovery). s is immune to that vertical noise,
        // so it folds into the SAME proven recovery (fresh foot-search blacklists the un-advanceable node + re-
        // routes from here) at ~1.5 s instead of the 5 s wedge burst. Default-OFF behind navigatorArcLengthWedge.
        boolean arcWedge = ControlConfig.navigatorArcLengthWedge && nav.progress.arc.wedgeTicks > ProgressPhase.ARC_WEDGE_TICKS
                && nav.route.path() != null && nav.route.step() < nav.route.path().size();
        // navigatorAboveNodeStallRecover: the CLIMBED-PAST-THE-NODE blind spot (C26-J3
        // 2026-07-02, the steep-mountain churn's core): the bot grinds UP a slope past
        // its committed stepUp node and ends grounded 2-3 blocks ABOVE it (dY exactly
        // 3.00 sits just outside the `> maxJumpUp+2` fell-off test — the mirror image of
        // the 2026-06-24 ascent-ram slide-back gap), pinned against a wall with the node
        // unreachable below (within/passed both starved, noStepProg 300+, yaw locked
        // 173° off). A plain FALL edge legitimately has nodes 3+ below while airborne,
        // so gate on GROUNDED + dry + sustained no-progress: only the stuck form folds
        // into fellOffPath (foot-search re-route from the real, higher position).
        boolean aboveNodeStall = ControlConfig.navigatorAboveNodeStallRecover
                && nav.route.path() != null && nav.route.step() < nav.route.path().size() && p.onGround() && !p.isInWater()
                && (foot.getY() - nav.route.path().get(nav.route.step()).getY()) >= 2
                && (foot.getY() - nav.route.path().get(nav.route.step()).getY()) <= world.maxJumpUpBlocks() + 2
                && nav.progress.stepProg.noStepProgressTicks > 90;
        if (aboveNodeStall && GameConfig.navigatorDebug)
            LOG.info("[navigator] above-node-stall RECOVER step={}/{} node={} dyAbove={} noStepProg={}",
                    nav.route.step(), nav.route.path().size(), nav.route.path().get(nav.route.step()), foot.getY() - nav.route.path().get(nav.route.step()).getY(), nav.progress.stepProg.noStepProgressTicks);
        boolean fellOffPath = forceFellOffPath || arcWedge || ascentRamSlide || descentRamStuck || aboveNodeStall || (nav.route.path() != null && nav.route.step() < nav.route.path().size()
                && Math.abs(nav.route.path().get(nav.route.step()).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2);
        forceFellOffPath = false;   // task#82: consume — one fold per UNREACHABLE/FAILED from the delegated tick
        if (arcWedge && GameConfig.navigatorDebug)
            LOG.info("[navigator] arc-wedge RECOVER step={}/{} node={} nodeDy={} wedgeT={} (bob-immune ram → fellOffPath)",
                    nav.route.step(), nav.route.path().size(), nav.route.path().get(nav.route.step()), nav.route.path().get(nav.route.step()).getY() - foot.getY(), nav.progress.arc.wedgeTicks);
        if (fellOffPath && nav.route.seg.activeSearch != null && nav.route.seg.searchFromEnd) nav.route.abortSearch();
        // Fell BELOW a still-valid route with blocks in hand → PILLAR BACK UP to
        // it instead of re-searching. A fresh foot-search from down here (a cave
        // corridor under a mountain high route) only finds the free goal-ward
        // funnel back into the dead pocket — the deterministic relapse loop
        // (live 2026-06-10: escape→high route→fall y104→96→re-search→cave→pocket,
        // ~80 s/lap, every lap byte-identical). Climbing the few blocks back
        // re-joins the committed route; the pillar-recover actuator below drives
        // it (latch re-armed each grounded tick while still low, overJump takes
        // over inside jump reach). Re-search only for a real divergence.
        // Gate on holdPillarBlock (not world.canPlace) so a bot carrying ONLY sand/gravel can
        // still pillar back up: the recovery places on the SOLID rung directly below the grounded
        // foot (supported → a falling block won't drop), unlike a planned pillar over air/water
        // that world.canPlace() rightly rejects. Strict superset of the old gate (identical when
        // a non-falling support block is held).
        boolean fellBelowRoute = fellOffPath && onRoute(nav) && !isDescentEdge(wedgeEdge)
                && nav.route.path().get(nav.route.step()).getY() > foot.getY()
                && nav.route.path().get(nav.route.step()).getY() - foot.getY() <= PILLAR_RECOVER_MAX_DY
                && nav.pillar.recoverStallTicks <= PILLAR_NORISE_GIVEUP   // a no-rise pillar-trap (canopy/overhang) gives up → foot-search re-routes
                && WorldConfig.allowPlace && nav.equipment.holdPillarBlock();
        // DEEP-PIT ESCAPE (last resort, see DEEP_PIT_ESCAPE_TICKS): a sheer pit DEEPER than the
        // recover cap leaves fellBelowRoute false (gap > cap) so only the foot-search runs, and in
        // a 1-wide sheer pit it loops on the un-climbable rim route for 15-21 s. Once that loop is
        // CONFIRMED (no step progress past DEEP_PIT_ESCAPE_TICKS), drive the SAME pillar-recover
        // actuator up the open shaft; each grounded tick re-arms it until the gap closes to the cap
        // and fellBelowRoute carries it the rest. Hard-gated on the long stall + grounded +
        // node-above + blocks-in-hand → inert in all normal motion (a working bot never stalls this
        // long: any step advance resets noStepProgressTicks).
        boolean deepPitEscape = fellOffPath && onRoute(nav) && !isDescentEdge(wedgeEdge) && p.onGround()
                && nav.route.path().get(nav.route.step()).getY() > foot.getY()
                && nav.route.path().get(nav.route.step()).getY() - foot.getY() > PILLAR_RECOVER_MAX_DY
                && nav.progress.stepProg.noStepProgressTicks > DEEP_PIT_ESCAPE_TICKS
                && WorldConfig.allowPlace && nav.equipment.holdPillarBlock();
        if ((fellBelowRoute || deepPitEscape) && p.onGround()) {
            if (nav.pillar.recoverLatch <= 0) { nav.pillar.recoverPeakY = foot.getY(); nav.pillar.recoverStallTicks = 0; }   // new recovery → fresh peak
            else if (foot.getY() > nav.pillar.recoverPeakY) { nav.pillar.recoverPeakY = foot.getY(); nav.pillar.recoverStallTicks = 0; } // rose a rung → reset stall
            else nav.pillar.recoverStallTicks++;                          // bobbing under a ceiling, no height gain
            nav.pillar.recoverLatch = PILLAR_RECOVER_TICKS;
            nav.pillar.recoverCell = foot;
        } else if (!fellOffPath) {
            nav.pillar.recoverStallTicks = 0;                             // back on the route → clear the give-up for the next genuine recovery
        }
        // fellOffPath (when NOT recoverable) forces a fresh foot-search: with only
        // the continuation cancelled, the stale path's far carrot kept driving the
        // bot against the wall until the wedge timer fired — up to 15 s with the
        // breaking-edge leash. offPath is folded in: a 4-block fall puts the 3D
        // distSqr over its gate too, and that re-search would steal the recovery.
        if (nav.recovery.countCooldown > 0) nav.recovery.countCooldown--;
        // BOXED-POCKET churn escape (time-windowed net displacement). Catches a pocket
        // where the bot pillars a goal-ward dead-end wall and limit-cycles (round73:
        // 1416↔1454, pillaring to XZ-closer columns that RESET a goal-distance counter,
        // then falling back — net-zero ground travel for minutes; the existing wedge
        // BURST backs it off but it returns because the best-effort re-routes straight
        // back in). Measured over a fixed window so the oscillation can't mask it. On a
        // churned window, CHARGE the pocket with the executor's accumulating blacklist
        // (escalating radius) so the next search prices the dead-end out and routes
        // OUT / backtracks — the charge is the missing ingredient the plain back-off
        // burst lacks. Gated to best-effort (a goal-reaching path is real progress).
        // ALSO fires IN WATER (2026-06-15): a boxed submerged chamber under a rock
        // overhang (canyon pocket (2358,1863): water y58-62 under a y64+ shelf, walls
        // N/E/W) churns identically — carrots flip-flop into the E/W chamber walls,
        // yaw spins, totStuck 1800+ for minutes — but the open-water anti-spin only
        // damps the spin, it never PRICES THE POCKET OUT, so every best-effort repath
        // routes straight back in (the SW goal pulls through the chamber; run-1 only
        // arrived because it happened to route AROUND via the NE bank). The old
        // !isInWater gate excluded exactly this case. The penalty decays (~90 s) and a
        // healthy crossing nets ≫8 blocks / 20 s, so legit swims never trip it.
        // Wall-corner ram signature: count consecutive sustained-hCol ticks (§39). A clean walk brushes
        // a wall for a tick or two; only a genuine wall-corner stall pins hCol true for seconds.
        trackWindow(nav, p, foot, nearer);
        int effChurnWindow = ControlConfig.navigatorFasterChurnRepath ? 240 : CHURN_WINDOW;
        // A sustained ram shortens the net-displacement window so the existing blacklist+escalate
        // (below) fires in ~8s instead of 20s — but ONLY while genuinely wall-pinned, so legitimate
        // slow-but-moving terrain keeps the full window (no false-fire). Default OFF.
        if (ControlConfig.navigatorWallCornerFastChurn && churn.hColRamTicks >= HCOL_RAM_TICKS)
            effChurnWindow = Math.min(effChurnWindow, WALL_CHURN_WINDOW);
        if (churn.base == null) { churn.base = foot; churn.windowTicks = 0; }
        else if (++churn.windowTicks >= effChurnWindow) {
            int cdx = foot.getX() - churn.base.getX(), cdz = foot.getZ() - churn.base.getZ();
            int cdy = foot.getY() - churn.base.getY();
            // Fire on a best-effort churn (existing cases — all net ≈0 Y, unchanged) OR on a
            // GOAL-REACHING limit-cycle that gained no altitude (steep-mountain base / cave),
            // never on a genuine upward climb (cdy > CHURN_MIN_Y is real vertical progress).
            // ...but NEVER while actively MINING (breakingEdge): a slow climb-out stone dig makes
            // zero XZ progress for ~750 ticks BY DESIGN, tripping this window — and the burst
            // below turns the camera (the recovery burst) + shoves the bot OFF the riser, RESETTING the
            // vanilla break progress. That is the live climb-out's core failure the user watched:
            // "almost broke it, then suddenly gave up, turned the view, moved 2 steps, progress
            // reset, all wasted" — and the shove toward deep water is what then sank the bot. A
            // dig in progress IS progress; let it finish (the per-riser commit cap bounds a truly
            // stuck dig).
            if (((cdx * cdx + cdz * cdz) < CHURN_MIN_MOVE_SQ || churn.cycle == 2) && (nav.route.seg.pathBestEffort || cdy <= CHURN_MIN_Y)
                    && !breakingEdge) {
                churn.escapes++;
                // Arm the sticky steep-barrier planner escalation (see top of tick()): the
                // planner suppresses its receding horizon and grinds deeper so it can find a
                // climb-OVER route instead of re-committing the cheap shallow/cave segment
                // this churn is stuck on. Sticky window tolerates a few net-progress blips.
                // Fires IN WATER too (2026-06-19): the same escalation that climbs a land
                // barrier also crosses a deepwater→elevated-bank pinch. Its raised depth
                // penalty DISCOURAGES diving (it prices descent), and its deeper / horizon-
                // suppressed search finds the surface route around/over the bank instead of
                // the cheap shallow segment a low budget commits — which IS the coastal
                // dive-churn. The earlier "pushes the bot to dive" worry was the planner
                // committing swimDown→swimTraverseBreak (dive + dig a coastal sandbar); that
                // dig is now gated to the water surface (SwimTraverseBreak), so the dive
                // route is gone. LIVE end-to-end: a ~9-deep bay before a y64-74 bank was
                // crossed AT THE SURFACE (no dive) → climbed the far bank → ARRIVED.
                escal.arm(BOXED_ESCALATE_STICKY_TICKS);
                // Widen the priced-out zone each repeat. In WATER a boxed pocket is far
                // costlier to sit in — a buoyant bot can't even hold position, it bob-
                // churns and burns minutes (live z1864: the slow r=2→3→4 land ramp took
                // ~3 min to finally route A* out). Detection already requires a CONFIRMED
                // churn (net <8 blocks / 20 s, which a healthy swim never trips), so it's
                // safe to jump straight to a wide blacklist there: grow by 2 per window
                // (cap 5) so one or two 20 s windows price the pocket out.
                int r = p.isInWater()
                        ? Math.min(2 + 2 * churn.escapes, 5)
                        : Math.min(1 + churn.escapes, 4);     // widen the priced-out zone each repeat
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos c = foot.offset(dx, 0, dz);
                        world.penalizeStuckNode(c);
                        world.penalizeStuckNode(c.above());
                    }
                BlockPos wp = (nav.route.path() != null && nav.route.step() < nav.route.path().size()) ? nav.route.path().get(nav.route.step()) : null;
                if (wp != null) nav.recovery.burst(Recovery.CHURN_BURST_TICKS, p, wp);   // away from the goal-ward wp
                if (GameConfig.navigatorDebug)
                    LOG.info("[navigator] anti-churn({}): net XZ move <{} blocks in {} ticks at {} (escapes={}) → charge r={} pocket + back off",
                            p.isInWater() ? "water" : "land",
                            (int) Math.sqrt(CHURN_MIN_MOVE_SQ), CHURN_WINDOW, foot, churn.escapes, r);
            } else {
                churn.escapes = 0;
            }
            churn.base = foot;
            churn.windowTicks = 0;
        }
        // OPEN-WATER bee-line preempt: a wide deep-water crossing wedges MID-segment.
        // The bot sprint-swims PAST its short committed segment faster than the
        // expensive sliced search (the whole 3-D water volume expands) can re-commit,
        // so `step` lags 30+ blocks behind, the wedge timer fires, and anti-stuck
        // bursts crab it across in jerky 13-block shoves (live 2026-06-15: ~9 bursts /
        // crossing, max step-stuck 100+). When WEDGED over water with the foot well
        // past its current node and a search already in flight, drop the stale segment
        // so the no-path branch below adopts a fresh straight SURFACE bee-line toward
        // the goal — smooth runway that the big search supersedes on landing — and the
        // path==null burst guard skips the shove. Near a bank the bee-line march is
        // short (< MIN, not adopted) so this can't strand a real climb-out.
        if (!nav.route.replay() && wedged && nav.route.path() != null && nav.route.step() < nav.route.path().size() && !breakingEdge
                && nav.route.seg.activeSearch != null && world.isWater(foot)
                && foot.distSqr(nav.route.path().get(nav.route.step())) > BEELINE_OVERSHOOT_SQ) {
            nav.route.drop("wedged over open water past the segment, so an open-water bee-line takes over");
        }
        // ---- publish: write this phase's products for the downstream phases (TickCtx) ----
        cx.stall.d = d;
        cx.stall.offPath = offPath;
        cx.stall.breakingEdge = breakingEdge;
        cx.stall.wedged = wedged;
        cx.stall.fellOffPath = fellOffPath;
        cx.stall.fellBelowRoute = fellBelowRoute;
        return null;
    }

    /** A current node to be below: fellOffPath also folds in flags that stay set once the path is dropped, and
     *  wd.bridgeStepTwoBypassNoPlace read the node of a null path from the fell-below and deep-pit checks. */
    private boolean onRoute(Navigator nav) {
        return nav.route.path() != null && nav.route.step() < nav.route.path().size();
    }

    /** More than 3 cells from the edge being walked. A surface sprint-swim cruise rides 1-3 cells UNDER its
     *  surface nodes on purpose (DrivePhase.surfaceCruise), which the 3D distance read as off-path
     *  every few blocks; the replan then started with a swim-up chain from the sunk foot and broke the
     *  cruise. Under the cruise the distance is horizontal.
     *
     *  <p>The node, deliberately, not the edge {@code path[step-1] → path[step]}. {@code RouteAdoption.adopt}
     *  string-pulls a flat run into one long edge whose tracked node is its far end, so a bot that
     *  has just started a 12-cell lane reads as 12 cells off, and this fires a safety re-search from
     *  the foot every tick until the last three cells — the search string-pulls to the same edge, so
     *  the walk goes on, at a full A* per tick (client lane 2026-09-06: search-begin on each of
     *  t=2..20). Measuring to the edge instead removes those searches and is what a {@code planId}
     *  goto would want, and it was tried: four scenes then failed on the Fabric gate —
     *  {@code wd.entityLeash}, {@code wd.entityLeashLowY} (the hard leash no longer held the bot
     *  back) and the two {@code wd.pillarLedger*} (the water climb-out takeover never engaged) —
     *  i.e. the per-tick re-search on a long edge is load-bearing for the leash and the bank takeover
     *  in ways this file does not own. So the edge is the reference only where neither can be in
     *  play: a dry, level, action-free walk/diag edge under an unconstrained profile (a leash rides
     *  in as a profile constraint or bias). With diagonal string-pulling on, open terrain is mostly
     *  such edges, and the node reference re-searched 224 times in one 200 s route (live
     *  2026-09-28), each adoption a speed dip. */
    private boolean offPath(Navigator nav, BlockPos foot, LivingEntity p) {
        if (nav.route.path() == null || nav.route.step() >= nav.route.path().size()) return false;
        BlockPos node = nav.route.path().get(nav.route.step());
        if (nav.drive.driveLatch.cruiseOn) {
            int dx = node.getX() - foot.getX(), dz = node.getZ() - foot.getZ();
            return dx * dx + dz * dz > 9;
        }
        if (onPlainLane(nav, node, foot, p)) return laneDistSqr(nav.route.path().get(nav.route.step() - 1), node, foot) > 9;
        return node.distSqr(foot) > 9;
    }

    private boolean onPlainLane(Navigator nav, BlockPos node, BlockPos foot, LivingEntity p) {
        return nav.route.step() >= 1 && !p.isInWater() && plainFlatWalk(nav.route.edgeAt(nav.route.step()))
                && nav.route.path().get(nav.route.step() - 1).getY() == node.getY() && foot.getY() == node.getY()
                && nav.profile.bias().isEmpty() && nav.profile.constraints().isEmpty();
    }

    /** Horizontal squared distance from the foot cell's centre to the segment {@code a → b}. */
    private static double laneDistSqr(BlockPos a, BlockPos b, BlockPos foot) {
        double ax = a.getX(), az = a.getZ(), bx = b.getX() - ax, bz = b.getZ() - az;
        double px = foot.getX() - ax, pz = foot.getZ() - az;
        double len2 = bx * bx + bz * bz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + pz * bz) / len2));
        double dx = px - bx * t, dz = pz - bz * t;
        return dx * dx + dz * dz;
    }
}
