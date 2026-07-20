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
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 838–1212): tick budget, divergent best-effort, wedge + fell-off-path family, boxed-pocket churn, wall-corner ram.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickStallDetect {
    private WalkerTickStallDetect() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Avatar a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        Player p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        // ---- original body (byte-identical modulo member prefixes) ----

        // Hard tick budget: prevents infinite walking when A* returns a partial path
        // for an unreachable goal (best-effort fallback path > 1 node satisfies ok()).
        // Reset on real progress (closer to goal) so legitimate long walks aren't killed.
        double d = wk.goal.estimate(foot);
        if (d < wk.bestDistToGoal - 0.5) {
            wk.bestDistToGoal = d;
            wk.totalTicks = 0;
        } else if (a.breakHeld() || wk.waterClimbDigging) {
            // Actively mining a block — a planned break edge swinging (breakHeld) OR the
            // block-less climb-out dig from last tick (waterClimbDigging, still set; reset
            // below at line ~755). The bot IS progressing: slowly breaking a riser, not
            // translating, so goal-distance stays flat for the whole ~750-tick hand-mine of
            // a stone block. HOLD the no-progress give-up here, or it aborts a legitimate
            // slow climb-out mid-break (faithful-stone arena: committed 843 ticks to break
            // one stone, reached y207.85, then FAILED at tick 1221 = walkerTotalTickBudget).
            // True deadlocks are still caught by the per-riser dig-commit cap, the
            // breakTimeoutTicks wedge watchdog, and the fact that each completed break lifts
            // Y toward the goal → bestDistToGoal drops → totalTicks resets on the next step.
        } else if (++wk.totalTicks > BotConfig.walkerTotalTickBudget) {
            wk.lastError = "no progress for " + BotConfig.walkerTotalTickBudget + " ticks (best dist=" + Math.round(wk.bestDistToGoal) + ")";
            return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.STUCK, wk.lastError, "failed:" + wk.lastError, p.blockPosition());
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
        boolean offPath = wk.path != null && wk.step < wk.path.size() && wk.path.get(wk.step).distSqr(foot) > 9;
        // Jitter-immune wedge: stuck on a node the Walker can't complete (e.g. a
        // ground-blocked fallN). An edge that's legitimately BREAKING blocks gets a
        // far longer leash: bare-handed stone takes ~150 ticks/block — well past
        // WEDGE_TICKS — so the plain threshold interrupted every mid-dig, penalized
        // the dig node (the only exit), and after a few cycles the accumulating
        // stuck-penalties walled off a small cave pocket entirely → 60k-node
        // pathLen=0 → goto FAILED (live 2026-06-09, mountain cave). The break
        // actuator has its own breakTimeoutTicks watchdog, so a dig that's truly
        // stuck (no line of sight, unreachable cell) still escapes after the sum.
        Move.Edge wedgeEdge = (wk.path != null && wk.step < wk.path.size()) ? wk.edgeAt(wk.step) : null;
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
        boolean breakingEdge = (wedgeEdge != null && !wedgeEdge.toBreak.isEmpty() && a.breakHeld())
                || wk.waterClimbDigging;
        wk.waterClimbDigging = false;   // re-armed below only if the block-less dig actuator runs this tick
        int wedgeLimit = breakingEdge ? WEDGE_TICKS + BotConfig.breakTimeoutTicks : WEDGE_TICKS;
        boolean wedged = wk.noStepProgressTicks > wedgeLimit;
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
        boolean ascentRamSlide = wk.path != null && wk.step < wk.path.size() && p.onGround()
                && wedgeEdge != null && !"pillarUp".equals(wedgeEdge.move)
                && (wedgeEdge.move == null || !wedgeEdge.move.startsWith("parkour"))
                && wk.path.get(wk.step).getY() - foot.getY() >= 2
                && wk.noStepProgressTicks > ASCENT_SLIDE_RECOVER_TICKS;
        // JITTER-IMMUNE +2/+3 ascent-ram recovery (the steep-climb-failure deep fix): ascentRamSlide above
        // hangs on noStepProgressTicks, which a slide-back's re-approach / vertical bob zeroes on every 3D
        // new-low — so on a WIDE steep ram the gate never fills and the bot bob-rams 150+ ticks before the
        // slow WEDGE_TICKS(100) burst (live J3b -877,75,241: diagUp node 3 above the grounded/airborne foot,
        // cur2 3.2-4.1, 反复横跳, 156 t / 7.8 s). Mirror crestOrbitTicks: a stepUp/diagUp edge (a PLANNED +1
        // the bot has slid 2-3 BELOW, so its node now sits >=2 above the foot) that has DWELT rawStepDwellTicks
        // past the bar — bob-immune, and NO onGround requirement so it ALSO catches the airborne-bob ram the
        // grounded ascentRamSlide misses — folds into fellOffPath so the fresh foot-search blacklists the
        // unreachable node and re-routes from HERE now. The move MUST be stepUp/diagUp: a broad "any node >=2
        // above" test MISFIRES on climbUp (a vine ascent legitimately spans 3-8 blocks in one edge — the
        // planner's intended route up, NOT a ram; live regression: a climbUp folded 85× → repath storm,
        // totStuck 908), and on pillarUp/swimUp/parkour multi-block climbs. DRY only (the water bank-climb-out
        // owns its own dig recovery). LATCHED to ONE fold per ram episode (ramRecoverLatchedStep): without it
        // the gate re-fires every tick from dwell 31 up until the step changes, folding into fellOffPath
        // 10-85× → a repath cascade that inflates churn even on a legit ram. A clean climb advances via
        // within/passed in 1-3 t (step changes, rawStepDwellTicks + the latch reset) and never trips it.
        boolean ascentRamSlideJitterImmune = BotConfig.walkerAscentRamJitterImmune
                && wk.path != null && wk.step < wk.path.size() && !p.isInWater()
                && wedgeEdge != null && wedgeEdge.move != null
                && (wedgeEdge.move.equals("stepUp") || wedgeEdge.move.equals("diagUp"))
                && wk.path.get(wk.step).getY() - foot.getY() >= 2
                && wk.rawStepDwellTicks > RAM_JITTER_RECOVER_TICKS
                && (wk.ramRecoverLastFireDwell < 0 || wk.rawStepDwellTicks - wk.ramRecoverLastFireDwell >= RAM_RECOVER_DEBOUNCE);
        if (ascentRamSlideJitterImmune) {
            wk.ramRecoverLastFireDwell = wk.rawStepDwellTicks;   // next fold ≥RAM_RECOVER_DEBOUNCE later; the line-1640 step/path reset clears it
            if (BotConfig.walkerDebug)
                LOG.info("[walker] ascent-ram-jitter RECOVER step={}/{} node={} move={} nodeDy={} dwell={} noStep={}",
                        wk.step, wk.path.size(), wk.path.get(wk.step), wedgeEdge.move,
                        wk.path.get(wk.step).getY() - foot.getY(), wk.rawStepDwellTicks, wk.noStepProgressTicks);
        }
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
        boolean descentRamStuck = wk.path != null && wk.step < wk.path.size() && p.onGround()
                && wedgeEdge != null && !"pillarUp".equals(wedgeEdge.move)
                && (wedgeEdge.move == null || !wedgeEdge.move.startsWith("parkour"))
                && foot.getY() - wk.path.get(wk.step).getY() == 1
                && p.horizontalCollision
                && wk.noStepProgressTicks > STEPUP_FREEZE_TICKS;
        // VERTICAL step-pointer re-sync dead-zone (the descent-OVERSHOOT blind spot; mirror also
        // covers an ascent SLIDE-BACK). The bot is GROUNDED but its step-pointer node sits ≥2
        // blocks off VERTICALLY (|foot.y − node.y| ≥ 2, either sign) while the foot is in the
        // horizontal step-advance dead-zone — cur2 ∈ (REACH_DIST_SQ, OVERSHOOT_RESYNC_SQ): too FAR
        // for `within` (cur2<0.45) yet too NEAR for the horizontal overshoot re-sync (cur2>4) — and
        // there is NO horizontalCollision (so it is NOT a ram: descentRamStuck/ascentRamSlide own
        // those). The classic case: the bot crosses a steep crest/shoulder with un-braked momentum,
        // free-falls 2 PAST a stepDown/fall node, and grounds on the terrace ~1.6 b shy of it — node
        // 2 BELOW, cur2≈2.5, hCol=false (live -1037, totStuck oscillating ~80 t until a WEDGE_TICKS
        // burst yanks it BACKWARD). Every step-advance gate then misses by a hair: `within` fails
        // (|Δy|=2>1.2); `passed`/`crossedDescendNode` are blocked by their |nx.y−p.y|<1.2 / forward-
        // projection reachability gates (the next node is also ~2 below); `fellOffPath`'s 3D-fall
        // test is one block short (|Δy|=2 ≤ maxJumpUp+2=3); ascentRamSlide needs ≥2 ABOVE and
        // descentRamStuck needs EXACTLY 1 below + hCol — a genuine hole. Fold the confirmed dead-zone
        // stall into fellOffPath so the SAME proven recovery (continuation-cancel → fresh foot-search
        // that blacklists the un-advanceable node → re-route from the actual position) fires NOW
        // instead of after the ~5 s burst. STRICT extension: the STEPUP_FREEZE_TICKS gate + the tight
        // cur2 band + the no-hCol + grounded conditions are all FALSE on a normal descent (lands clean
        // and advances via within/passed in 1-3 t) and on every other stall (the general wedge timer
        // owns those), so outside this exact state the bot behaves identically. pillarUp/parkour
        // incoming edges keep their own handling. (The ≥2-ABOVE branch is normally caught FIRST by
        // ascentRamSlide at its earlier tick-10 gate; this generic clause is the mirror that also
        // covers a no-hCol slide-back, and the disjoint descent-overshoot the family was missing.)
        boolean verticalResync = BotConfig.walkerVerticalResync           // flag FIRST → OFF is a true zero-cost no-op (nothing below runs)
                && wk.path != null && wk.step < wk.path.size() && p.onGround() && !p.horizontalCollision
                && wedgeEdge != null && !"pillarUp".equals(wedgeEdge.move)
                && (wedgeEdge.move == null || !wedgeEdge.move.startsWith("parkour"))
                && Math.abs(foot.getY() - wk.path.get(wk.step).getY()) >= 2
                && wk.noStepProgressTicks > STEPUP_FREEZE_TICKS
                && deadZoneCur2(wk.path.get(wk.step), p);   // horizontal cur2 ∈ (REACH_DIST_SQ, OVERSHOOT_RESYNC_SQ)
        // Phase-3 bob/jitter-immune ram wedge: the arc-length s has not advanced for ARC_WEDGE_TICKS while
        // horizontalCollision (the count lives in arcShadowTick). This is the STRUCTURAL replacement for the
        // descentRamStuck/ascentRamSlide family above — they hang on noStepProgressTicks, which the ram-jitter's
        // sub-block 3D-new-lows zero so the gate never fills (live -672,94: driveF=1, aim=4°, hCol, node 1 below,
        // descentRamStuck's exact case, yet it bobbed >34 t with no recovery). s is immune to that vertical noise,
        // so it folds into the SAME proven recovery (fresh foot-search blacklists the un-advanceable node + re-
        // routes from here) at ~1.5 s instead of the 5 s wedge burst. Default-OFF behind walkerArcLengthWedge.
        boolean arcWedge = BotConfig.walkerArcLengthWedge && wk.arcWedgeTicks > Walker.ARC_WEDGE_TICKS
                && wk.path != null && wk.step < wk.path.size();
        // Phase-3b: an OSCILLATING limit cycle (net arc-s ≈ 0 over a window) the per-tick ram wedge + anti-churn miss.
        boolean arcProgWedge = BotConfig.walkerArcProgressWedge && wk.arcProgStall
                && wk.path != null && wk.step < wk.path.size();
        if (arcProgWedge) wk.arcProgStall = false;   // consume once so the recovery isn't re-fired before the next window
        // walkerAboveNodeStallRecover: the CLIMBED-PAST-THE-NODE blind spot (C26-J3
        // 2026-07-02, the steep-mountain churn's core): the bot grinds UP a slope past
        // its committed stepUp node and ends grounded 2-3 blocks ABOVE it (dY exactly
        // 3.00 sits just outside the `> maxJumpUp+2` fell-off test — the mirror image of
        // the 2026-06-24 ascent-ram slide-back gap), pinned against a wall with the node
        // unreachable below (within/passed both starved, noStepProg 300+, yaw locked
        // 173° off). A plain FALL edge legitimately has nodes 3+ below while airborne,
        // so gate on GROUNDED + dry + sustained no-progress: only the stuck form folds
        // into fellOffPath (foot-search re-route from the real, higher position).
        boolean aboveNodeStall = BotConfig.walkerAboveNodeStallRecover
                && wk.path != null && wk.step < wk.path.size() && p.onGround() && !p.isInWater()
                && (foot.getY() - wk.path.get(wk.step).getY()) >= 2
                && (foot.getY() - wk.path.get(wk.step).getY()) <= world.maxJumpUpBlocks() + 2
                && wk.noStepProgressTicks > 90;
        if (aboveNodeStall && BotConfig.walkerDebug)
            LOG.info("[walker] above-node-stall RECOVER step={}/{} node={} dyAbove={} noStepProg={}",
                    wk.step, wk.path.size(), wk.path.get(wk.step), foot.getY() - wk.path.get(wk.step).getY(), wk.noStepProgressTicks);
        boolean fellOffPath = wk.forceFellOffPath || arcWedge || arcProgWedge || ascentRamSlide || ascentRamSlideJitterImmune || descentRamStuck || verticalResync || aboveNodeStall || (wk.path != null && wk.step < wk.path.size()
                && Math.abs(wk.path.get(wk.step).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2);
        wk.forceFellOffPath = false;   // task#82: consume — one fold per UNREACHABLE/FAILED from the delegated tick
        if (arcWedge && BotConfig.walkerDebug)
            LOG.info("[walker] arc-wedge RECOVER step={}/{} node={} nodeDy={} wedgeT={} (bob-immune ram → fellOffPath)",
                    wk.step, wk.path.size(), wk.path.get(wk.step), wk.path.get(wk.step).getY() - foot.getY(), wk.arcWedgeTicks);
        if (fellOffPath && wk.activeSearch != null && wk.searchFromEnd) {
            wk.activeSearch = null;
            wk.searchFromEnd = false;
        }
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
        boolean fellBelowRoute = fellOffPath
                && wk.path.get(wk.step).getY() > foot.getY()
                && wk.path.get(wk.step).getY() - foot.getY() <= PILLAR_RECOVER_MAX_DY
                && wk.pillarRecoverStallTicks <= PILLAR_NORISE_GIVEUP   // a no-rise pillar-trap (canopy/overhang) gives up → foot-search re-routes
                && BotConfig.allowPlace && a.holdPillarBlock();
        // DEEP-PIT ESCAPE (last resort, see DEEP_PIT_ESCAPE_TICKS): a sheer pit DEEPER than the
        // recover cap leaves fellBelowRoute false (gap > cap) so only the foot-search runs, and in
        // a 1-wide sheer pit it loops on the un-climbable rim route for 15-21 s. Once that loop is
        // CONFIRMED (no step progress past DEEP_PIT_ESCAPE_TICKS), drive the SAME pillar-recover
        // actuator up the open shaft; each grounded tick re-arms it until the gap closes to the cap
        // and fellBelowRoute carries it the rest. Hard-gated on the long stall + grounded +
        // node-above + blocks-in-hand → inert in all normal motion (a working bot never stalls this
        // long: any step advance resets noStepProgressTicks).
        boolean deepPitEscape = fellOffPath && p.onGround()
                && wk.path.get(wk.step).getY() > foot.getY()
                && wk.path.get(wk.step).getY() - foot.getY() > PILLAR_RECOVER_MAX_DY
                && wk.noStepProgressTicks > DEEP_PIT_ESCAPE_TICKS
                && BotConfig.allowPlace && a.holdPillarBlock();
        if ((fellBelowRoute || deepPitEscape) && p.onGround()) {
            if (wk.pillarRecoverLatch <= 0) { wk.pillarRecoverPeakY = foot.getY(); wk.pillarRecoverStallTicks = 0; }   // new recovery → fresh peak
            else if (foot.getY() > wk.pillarRecoverPeakY) { wk.pillarRecoverPeakY = foot.getY(); wk.pillarRecoverStallTicks = 0; } // rose a rung → reset stall
            else wk.pillarRecoverStallTicks++;                          // bobbing under a ceiling, no height gain
            wk.pillarRecoverLatch = PILLAR_RECOVER_TICKS;
            wk.pillarRecoverCell = foot;
        } else if (!fellOffPath) {
            wk.pillarRecoverStallTicks = 0;                             // back on the route → clear the give-up for the next genuine recovery
        }
        // fellOffPath (when NOT recoverable) forces a fresh foot-search: with only
        // the continuation cancelled, the stale path's far carrot kept driving the
        // bot against the wall until the wedge timer fired — up to 15 s with the
        // breaking-edge leash. offPath is folded in: a 4-block fall puts the 3D
        // distSqr over its gate too, and that re-search would steal the recovery.
        if (wk.unstuck.countCooldown > 0) wk.unstuck.countCooldown--;
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
        if (p.horizontalCollision) wk.hColRamTicks++; else wk.hColRamTicks = 0;
        int effChurnWindow = BotConfig.walkerFasterChurnRepath ? 240 : CHURN_WINDOW;
        // A sustained ram shortens the net-displacement window so the existing blacklist+escalate
        // (below) fires in ~8s instead of 20s — but ONLY while genuinely wall-pinned, so legitimate
        // slow-but-moving terrain keeps the full window (no false-fire). Default OFF.
        if (BotConfig.walkerWallCornerFastChurn && wk.hColRamTicks >= HCOL_RAM_TICKS)
            effChurnWindow = Math.min(effChurnWindow, WALL_CHURN_WINDOW);
        if (wk.churnBase == null) { wk.churnBase = foot; wk.churnWindowTicks = 0; }
        else if (++wk.churnWindowTicks >= effChurnWindow) {
            int cdx = foot.getX() - wk.churnBase.getX(), cdz = foot.getZ() - wk.churnBase.getZ();
            int cdy = foot.getY() - wk.churnBase.getY();
            // Fire on a best-effort churn (existing cases — all net ≈0 Y, unchanged) OR on a
            // GOAL-REACHING limit-cycle that gained no altitude (steep-mountain base / cave),
            // never on a genuine upward climb (cdy > CHURN_MIN_Y is real vertical progress).
            // ...but NEVER while actively MINING (breakingEdge): a slow climb-out stone dig makes
            // zero XZ progress for ~750 ticks BY DESIGN, tripping this window — and the burst
            // below turns the camera (unstuck.burstYaw) + shoves the bot OFF the riser, RESETTING the
            // vanilla break progress. That is the live climb-out's core failure the user watched:
            // "almost broke it, then suddenly gave up, turned the view, moved 2 steps, progress
            // reset, all wasted" — and the shove toward deep water is what then sank the bot. A
            // dig in progress IS progress; let it finish (the per-riser commit cap bounds a truly
            // stuck dig).
            if ((cdx * cdx + cdz * cdz) < CHURN_MIN_MOVE_SQ && (wk.pathBestEffort || cdy <= CHURN_MIN_Y)
                    && !breakingEdge) {
                wk.churnEscapes++;
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
                wk.boxedEscalateUntilTick = wk.pfTickCounter + BOXED_ESCALATE_STICKY_TICKS;
                // Widen the priced-out zone each repeat. In WATER a boxed pocket is far
                // costlier to sit in — a buoyant bot can't even hold position, it bob-
                // churns and burns minutes (live z1864: the slow r=2→3→4 land ramp took
                // ~3 min to finally route A* out). Detection already requires a CONFIRMED
                // churn (net <8 blocks / 20 s, which a healthy swim never trips), so it's
                // safe to jump straight to a wide blacklist there: grow by 2 per window
                // (cap 5) so one or two 20 s windows price the pocket out.
                int r = p.isInWater()
                        ? Math.min(2 + 2 * wk.churnEscapes, 5)
                        : Math.min(1 + wk.churnEscapes, 4);     // widen the priced-out zone each repeat
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos c = foot.offset(dx, 0, dz);
                        world.penalizeStuckNode(c);
                        world.penalizeStuckNode(c.above());
                    }
                BlockPos wp = (wk.path != null && wk.step < wk.path.size()) ? wk.path.get(wk.step) : null;
                if (wp != null) {
                    double bdx = p.getX() - (wp.getX() + 0.5), bdz = p.getZ() - (wp.getZ() + 0.5);
                    if (bdx * bdx + bdz * bdz > 0.01)
                        wk.unstuck.burstYaw = (float) Math.toDegrees(Math.atan2(-bdx, bdz));   // away from the goal-ward wp
                    wk.unstuck.burstTicks = 16;
                }
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] anti-churn({}): net XZ move <{} blocks in {} ticks at {} (escapes={}) → charge r={} pocket + back off",
                            p.isInWater() ? "water" : "land",
                            (int) Math.sqrt(CHURN_MIN_MOVE_SQ), CHURN_WINDOW, foot, wk.churnEscapes, r);
            } else {
                wk.churnEscapes = 0;
            }
            wk.churnBase = foot;
            wk.churnWindowTicks = 0;
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
        if (!wk.replayMode && wedged && wk.path != null && wk.step < wk.path.size() && !breakingEdge
                && wk.activeSearch != null && world.isWater(foot)
                && foot.distSqr(wk.path.get(wk.step)) > BEELINE_OVERSHOOT_SQ) {
            wk.path = null;
            wk.edges = null;
            wk.step = 0;
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        cx.stall.d = d;
        cx.stall.offPath = offPath;
        cx.stall.breakingEdge = breakingEdge;
        cx.stall.wedged = wedged;
        cx.stall.fellOffPath = fellOffPath;
        cx.stall.fellBelowRoute = fellBelowRoute;
        return null;
    }
}
