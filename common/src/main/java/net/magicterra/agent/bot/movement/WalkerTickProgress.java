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
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 1647–2347): progressive quick-start hold, stall clocks, step-advance loop, arc-length shadow.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickProgress {
    private WalkerTickProgress() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Avatar a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        Player p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        // ---- original body (byte-identical modulo member prefixes) ----
        // First path still computing (no path to follow yet). PROGRESSIVE
        // QUICK-START: instead of holding frozen for the whole background
        // search (the visible inter-segment stall), grab a tiny synchronous
        // stub segment toward the goal and start walking it this very tick;
        // the big result supersedes it on landing. Only hold if no useful
        // stub exists (boxed in — moving blind would jitter).
        if (wk.path == null) {
            // A5 DIVE-INTENT HOLD: while a DIVE-opt-in intent's search is pending and
            // the bot floats IN WATER, do NOT adopt a progressive stub — HOLD (tread
            // water) until the real plan lands. tryQuickStart is a genuine mini-A*
            // whose best-effort partial happily contains climb-OUT edges, so from a
            // water-surface start it beat the sliced big search every time and hauled
            // the bot ASHORE before the first plan existed — by then the search start
            // was on LAND and the dive route was gone (live 2026-07-06 rc-a5c: every
            // t=2 sample already climbing out at ~(601.7,-49.6) with no [walker]
            // repath line yet; at maxMs=8000 the land-start search burned 90k nodes
            // honestly-unreachable). tryWaterBeeline likewise marches the floater
            // AWAY along the surface, moving the next search's start off the dive
            // column. The swimDown*/depth-hold exemptions all key on the CURRENT
            // EDGE, which does not exist pre-path — this is the pre-path twin of
            // those gates. Planned climb-out EDGES from a real path still execute
            // (a dive route may legitimately end with a bank exit); dry-land legs
            // of a dive-intent journey keep their stubs (gate requires isInWater).
            // No DIVE opt-in → allowsOptIn false → byte-identical.
            boolean diveIntentWaterHold = p.isInWater()
                    && wk.profile.capability().allowsOptIn(Capability.DIVE);
            if (wk.replayMode || wk.activeSearch == null || diveIntentWaterHold
                    || (!wk.tryLandBeeline(world, foot, wk.goal)
                        && !wk.tryQuickStart(world, foot, wk.goal) && !wk.tryWaterBeeline(world, foot, wk.goal))) {
                // replayMode: a fixed plan was adopted at beginReplay, so path is
                // never null here in practice; if it somehow is, just hold (no
                // quick-start/beeline) — replay never re-plans.
                Walker.agentForward(a, false);
                Walker.agentJump(a, false);
                p.setSprinting(false);
                return Walker.Step.WALKING;
            }
            // stub adopted → fall through and walk it
        }
        wk.ticksSinceRepath++;
        // §84 physical stall clock — see field doc. Ticks on XZ displacement only,
        // survives every path/step/repath reset.
        // Euclidean 2.0 window (rig-metric parity): the first cut used |dx|+|dz|>1.5,
        // which a reCentre/carrot tug-of-war micro-orbit (1-2 block circles at a pinned
        // corner, C98-J2 replay) reset continuously — the clock never reached its gate.
        double sdxA = p.getX() - wk.physStall.anchorX, sdzA = p.getZ() - wk.physStall.anchorZ;
        if (Double.isNaN(wk.physStall.anchorX) || sdxA * sdxA + sdzA * sdzA > 4.0) {
            wk.physStall.anchorX = p.getX();
            wk.physStall.anchorZ = p.getZ();
            wk.physStall.stallTicks = 0;
        } else {
            wk.physStall.stallTicks++;
        }
        // "Stuck" = no PROGRESS toward the current node — NOT a foot block that has
        // not changed. A bot creeping forward (water ≈ 0.08 b/tick, a place-bridge
        // sneak ≈ 1 block/15 ticks) stays in the same foot block for many ticks while
        // genuinely advancing; the old block-change test mis-flagged that as stuck and
        // fired the reCentre recovery (which aims yaw at the PREVIOUS node) → a
        // forward/backward yaw limit-cycle (0↔180) at zero net speed. So measure
        // 3D distance² to the current step node and only count ticks that fail to
        // improve on the closest approach so far. The vertical (dy) term matters: a
        // stepUp/stairUpBreak climbs in place — horizontal distance is frozen while
        // the bot rises toward the node — so a horizontal-only metric would falsely
        // count the whole climb as stuck. Bridge edges still hard-zero the counter
        // (a sneak crawl barely moves the carrot, so even node-distance can stall
        // mid-place).
        Move.Edge curBridgeE = wk.edgeAt(wk.step), nxtBridgeE = wk.edgeAt(wk.step + 1);
        boolean onBridge = (curBridgeE != null && "bridgePlace".equals(curBridgeE.move))
                || (nxtBridgeE != null && "bridgePlace".equals(nxtBridgeE.move));
        // Jitter-immune WEDGE timer: counts raw ticks the bot stays on the SAME path
        // step, reset only when the step actually advances (or the path/segment ends).
        // Deliberately INDEPENDENT of the bridge/progress resets below — a bridgePlace
        // that can't place, or a fallN that can't drop, dwells on one step indefinitely
        // and IS a wedge; the progress-based stuckTicks (which a bob/creep keeps zeroing,
        // and which onBridge hard-zeroes) misses both, so without this the bot deadlocks.
        // walkerStuckStepMonotonic (2nd site): a step RETREAT (projection jitter, repath
        // oscillation) must not reset the wedge clock either — C25-J2's ADVANCE-deadzone
        // stream showed noStepProg pinned at exactly 61 (threshold+1): the counter reached
        // the gate and was immediately re-zeroed by the next flip, so every wedge-gated
        // recovery re-armed forever. Only an ADVANCE opens a fresh window; a retreat
        // re-bases the closest-approach reference on the CURRENT distance (not +INF, which
        // would fake a new-low next tick and zero the clock through the progress branch).
        boolean npStepFresh = (wk.path == null || wk.step >= wk.path.size()
                || (BotConfig.walkerStuckStepMonotonic ? wk.step > wk.stepProg.noProgressStep : wk.step != wk.stepProg.noProgressStep));
        if (!npStepFresh && BotConfig.walkerStuckStepMonotonic
                && wk.path != null && wk.step < wk.path.size() && wk.step < wk.stepProg.noProgressStep) {
            wk.stepProg.noProgressStep = wk.step;
            BlockPos rn = wk.path.get(wk.step);
            double rdx = (rn.getX() + 0.5) - p.getX();
            double rdy = rn.getY() - p.getY();
            double rdz = (rn.getZ() + 0.5) - p.getZ();
            wk.stepProg.noProgressBestD2 = rdx * rdx + rdy * rdy + rdz * rdz;
        }
        if (npStepFresh) {
            wk.stepProg.noProgressStep = wk.step;
            wk.stepProg.noStepProgressTicks = 0;
            wk.stepProg.noProgressBestD2 = Double.POSITIVE_INFINITY;
            wk.stepProg.rawStepDwellTicks = 0;
            wk.stepProg.ramRecoverLastFireDwell = -1;   // step/path changed → re-arm the ascent-ram-jitter recover
        } else {
            // Raw step dwell — this `else` is reached ONLY when the step is unchanged, so the counter
            // accumulates pure ticks-on-the-same-step and resets only via the step-change branch above,
            // NEVER on a 3D new-low. noStepProgressTicks (below) zeroes on every closest-approach
            // improvement, and a steep-ascent slide-back's re-approach / vertical bob manufactures those,
            // jitter-defeating the ram-recovery stall gates that hang on it (live J3b -877,75: diagUp node
            // 3 above the foot, 156 t / 7.8 s before recovery). This gives walkerAscentRamJitterImmune a
            // bob-immune wedge timer so it recovers on time.
            wk.stepProg.rawStepDwellTicks++;
            // Genuinely closing in on the current node is progress, not a wedge: a
            // water cruise crosses 20+-block string-pulled edges at ~1.5 b/s (260
            // ticks on ONE step — far over WEDGE_TICKS) and used to trip safety
            // repaths + anti-stuck bursts mid-lake. Track the closest-EVER approach
            // (monotonic, so a bob against a wall can't keep resetting) and only
            // count wedge ticks while that stops improving — a fall2 that can't
            // drop or a blocked bridgePlace never closes in and still trips.
            BlockPos wn = wk.path.get(wk.step);
            double wdx = (wn.getX() + 0.5) - p.getX();
            double wdy = wn.getY() - p.getY();
            double wdz = (wn.getZ() + 0.5) - p.getZ();
            // A buoyant body bobs ±1.5 vertically; folding wdy² into the closest-approach
            // progress test makes wd2 oscillate even while the bot creeps FORWARD toward a
            // water goal, so noStepProgressTicks falsely climbs → wedged → safetyRepath churn:
            // every bob tick a cheap goal-reaching search resets path+step and the aim chases
            // the fresh node-1 (live J5 z1743: 3 repaths/s, yaw 96-131° while still closing on
            // the goal). In water, measure progress HORIZONTALLY only — the bob can't fake a
            // forward creep, while a truly stuck bot (circling, or pinned in the water below an
            // unmountable bank without gaining XZ) still fails to close XZ and trips the wedge
            // exactly as before (so deep-water climb-out detection is unchanged). Dry land keeps
            // the full 3D test (a stepUp/pillar that gains height IS progress there).
            // walkerDryWedgeFootY: at an ABOVE node on dry land, the continuous p.getY() vertical term lets the
            // jump/buoyant bob (p.y oscillates ~0.1 toward the node) manufacture a wd2 new-low every tick →
            // resets the wedge timer → a stalled +1 climb (stepUp ram / stairUpBreak whose break never completes)
            // never trips recovery and churns. Quantize the vertical term to foot.getY() so only a REAL climb
            // (foot rises a whole block) counts; an in-place bob does not. See BotConfig.walkerDryWedgeFootY.
            double wdyEff = (BotConfig.walkerDryWedgeFootY && !p.isInWater() && wn.getY() > foot.getY())
                    ? (wn.getY() - foot.getY())
                    : wdy;
            double wd2 = p.isInWater()
                    ? wdx * wdx + wdz * wdz
                    : wdx * wdx + wdyEff * wdyEff + wdz * wdz;
            if (wd2 < wk.stepProg.noProgressBestD2 - 0.05) {   // any real new-low counts; 0.05 is float-noise margin (1.0 starved a 1.5 b/s approach inside ~7 blocks: 2·d·v < 1)
                wk.stepProg.noProgressBestD2 = wd2;
                wk.stepProg.noStepProgressTicks = 0;
            } else {
                wk.stepProg.noStepProgressTicks++;
            }
        }
        if (onBridge) {
            wk.stuckTicks = 0;
            wk.stepProg.bestStepDist = Double.POSITIVE_INFINITY;
            wk.stepProg.stuckStep = wk.step;
        } else if (wk.path != null && wk.step >= 0 && wk.step < wk.path.size()) {
            BlockPos sn = wk.path.get(wk.step);
            double sdx = (sn.getX() + 0.5) - p.getX();
            double sdy = sn.getY() - p.getY();
            double sdz = (sn.getZ() + 0.5) - p.getZ();
            double sd2 = sdx * sdx + sdy * sdy + sdz * sdz;
            // walkerStuckStepMonotonic: only a step ADVANCE opens a fresh progress window.
            // On an open-water straight path the buoyant drift makes the projection/within
            // machinery jitter the step pointer back and forth (wp 375↔387↔374 in one 44s
            // stall, A-4 2026-07-02); with the old `step != stuckStep` test every jitter
            // reset stuckTicks (observed pinned at 0-6, never reaching the nodeAim
            // fallback's 12), so ALL stuck-gated recovery starved while the yaw swept 660°
            // and thrust cancelled. A step RETREAT keeps the current window instead.
            // HIGH-WATER form (C33-J2 fix): the first cut compared against stuckStep and
            // the retreat re-base LOWERED it, so an oscillating pointer (5↔6 at a sand
            // pond lip, 1200t) still cleared the clock on every "advance" of the pair.
            // Only a step beyond the highest index EVER seen on this path is fresh.
            boolean stepWindowFresh = BotConfig.walkerStuckStepMonotonic
                    ? wk.step > wk.stepProg.stuckStepHigh : wk.step != wk.stepProg.stuckStep;
            if (BotConfig.walkerStuckStepMonotonic && !stepWindowFresh && wk.step != wk.stepProg.stuckStep) {
                // Retreat OR revisit inside the seen range: keep the stall clock running
                // but re-base the progress reference on the new node, else its
                // naturally-different sd2 would fake "real progress" through the side door.
                wk.stepProg.stuckStep = wk.step;
                wk.stepProg.bestStepDist = sd2;
            }
            if (stepWindowFresh) {                         // new node → fresh progress window
                wk.stepProg.stuckStep = wk.step;
                wk.stepProg.stuckStepHigh = wk.step;
                wk.stepProg.bestStepDist = sd2;
                wk.stuckTicks = 0;
            } else if (sd2 < wk.stepProg.bestStepDist
                    // Medium-split margin (§94): the dry 0.05 margin (C40-J1 wall-creep
                    // starvation fix) starves water's naturally slow rounding manoeuvres
                    // and the tripped recovery pins the bot on the obstacle corner.
                    - (p.isInWater() ? STUCK_PROGRESS_EPS_WATER : STUCK_PROGRESS_EPS)) {
                wk.stepProg.bestStepDist = sd2;                        // closer than ever to this node → real progress
                wk.stuckTicks = 0;
            } else {
                wk.stuckTicks++;                              // no closer this tick → maybe stalled
            }
        } else {
            wk.stuckTicks = 0;
        }

        // ===== Phase-0 SHADOW arc-length pursuit (walkerArcLengthShadow) — DRIVES NOTHING =====
        // Projects the continuous foot XZ onto the path polyline within a forward window and reports the
        // projected segment, cumulative arc-length s, horizontal perpendicular distance, and the tangent
        // heading at s+lookahead. The structural replacement (refactor plan §7) for the ~8 instantaneous
        // step-advance gates + the bob-immune dwell zoo, which exist ONLY because the buoyancy bob defeats
        // the per-tick cur2<0.45 / |dyNode|<1.2 gates. Logged only, so a replay can confirm s is monotonic
        // and the projected segment tracks the live `step` on clean runs BEFORE Phase 1 drives off it.
        if ((BotConfig.walkerArcLengthShadow || BotConfig.walkerArcLengthAdvance || BotConfig.walkerTangentAim
                || BotConfig.walkerArcLengthWedge || BotConfig.walkerArcProgressWedge || BotConfig.walkerFellBelowAlign)
                && wk.path != null && wk.step < wk.path.size() && foot != null) {
            wk.arcShadowTick(world, foot, p.getX(), p.getZ(), p.getYRot(), p.isInWater(), p.horizontalCollision);
        } else {
            wk.arc.wedgeTicks = 0;   // no projection this tick → don't carry a stale ram count into the next path
            wk.arc.progBaseS = Double.NaN; wk.arc.progWindowTicks = 0; wk.arc.progStall = false;   // reset the net-progress window too
        }

        while (wk.step < wk.path.size()) {
            Move.Edge se = wk.edgeAt(wk.step);
            // Buoyant pillar (bunker / flooded-pit self-exit): the bot rises through
            // a water-filled shaft. Detect it from the WORLD — the cell being risen
            // FROM is water — not p.isInWater(), which flickers false at the bob peak
            // when the head clears the surface (that flicker stalled the climb).
            // A FLOODED shaft (destination cell is itself water) lets the bot float
            // straight up; a buoyant pillar more broadly is any pillarUp begun from
            // water (cell below is water / bot in water) — including the dry-air
            // chimney where the bot must place a support to climb out.
            boolean shaftFlooded = se != null && "pillarUp".equals(se.move) && world.isWater(wk.path.get(wk.step));
            boolean waterPillar = se != null && "pillarUp".equals(se.move)
                    && (shaftFlooded || p.isInWater() || world.isWater(wk.path.get(wk.step).offset(0, -1, 0)));
            // Don't advance past a cell whose break/place actions are still
            // pending — otherwise a DownBreak (foot vertically aligned, < 1.2
            // away) would be skipped before we ever mine the floor.
            if (waterPillar) {
                // The buoyant climb never places the support via the dry path, so the
                // unfilled place cell is not genuinely pending; only a still-solid
                // ceiling is. (In the dry-air case the in-water actuator does place a
                // support, which makes the bot ground and falls through to the gate.)
                boolean ceilingPending = false;
                for (BlockPos b : se.toBreak) if (world.isSolid(b)) { ceilingPending = true; break; }
                if (ceilingPending) break;
            } else if (hasPendingEdge(world, se)) break;
            // A pillar step isn't "reached" until risen to height. A FLOODED-shaft
            // float has no landing → risen-height alone promotes it; every other
            // pillar (dry, or the water-exit place) must be grounded first so we
            // don't advance mid-jump / before the support lands.
            if (se != null && "pillarUp".equals(se.move)
                    && !((p.onGround() || shaftFlooded) && p.getY() >= wk.path.get(wk.step).getY() - 0.1)) break;
            // Don't advance past a parkour-place edge while airborne — keep the
            // settle phase owning the descent so it brakes the leap on landing.
            if (se != null && se.move != null && se.move.startsWith("parkourPlace")
                    && !p.onGround()) break;
            // Same for a parkour-descend leap: don't advance off it until we've
            // actually landed, so the descend landing-brake keeps owning the arc
            // (otherwise the next edge fires mid-air and the bot sails past).
            // EXEMPT in water: a buoyant body never grounds on a water-side landing
            // (A* exits a shore DOWN into water with a parkourDescend, or a leap
            // falls short into a water gap), so the !onGround hold would pin the
            // step on the parkour node forever while the bot bobs at the surface —
            // the parkour jump just bobs it, pure-pursuit can't advance past the
            // node it drifted beyond, and it deadlocks until a safety repath (live
            // 2026-06-15: shoreline parkourDescend2d1 over water, bot bobbed y62.5
            // <-> 63 ~19 s, stuck 385). When in water let the normal water step-
            // advance (within / floatOverSubmerged / pure-pursuit) carry it past
            // the node so flatWaterWalk swims it on to the next node. Ride out the
            // isInWater bob-blink with surfaceWaterLatch: at a water EDGE p.isInWater()
            // flickers false at the bob apex, and without the latch the exemption drops
            // out mid-bob and re-pins the step on the parkour node — the overshoot-resync
            // below never runs, so the bot drives BACKWARD to the stale node instead of
            // advancing to the climb-out it already overshot into (live 2026-06-24 -582
            // pool: parkour landed at -581,62 past node -582,62,536, ~15 s of backward bob).
            if (se != null && se.move != null && se.move.startsWith("parkourDescend")
                    && !p.onGround() && !p.isInWater() && wk.surfaceWaterLatch <= 0) break;
            // An ASCENDING parkour leap (parkourAscend / a rising parkour2d-3d that lands
            // higher than the launch) is the ONE jumped move WITHOUT a "don't advance until
            // executed" gate — pillarUp (above), parkourPlace and parkourDescend all have one,
            // but a rising parkour does not, so the within/passed re-sync CONSUMES the leap node
            // before the bot ever reaches the launch + jumps. The live -870 water climb-out
            // (2026-06-25 journey, 270-tick stall): the plan was
            //   …4 walk -870,62,379 · 5 stepUp -870,63,380 · 6 parkourAscend2 -872,64,380 · 7 walk -872,64,379
            // and at tick 1 `step` advanced 5→6→7 in ONE pass — node 5 `within` (foot at the
            // bank corner), node 6 `passed` (node 7 horizontally closer + both |Δy|<1.2 gates met)
            // — so the +2 climb-out leap was skipped and the bot was left pointed at node 7, a flat
            // `walk` node sitting +2 ABOVE the water. It then sank into the y62 pocket and bob-stalled
            // for ~14 s (a buoyant body can't WALK up a +2 bank; the toolless underwater bank-dig is
            // ~25× slow) until a repath happened to find a gentler exit. HOLD the step on the rising
            // parkour node until the feet have actually RISEN to it (grounded at its Y, ±0.5) so the
            // parkour actuator (sprint+jump, aimed at the destination — line ~3946) owns the launch and
            // a too-far approach can't skip straight onto the unreachable landing. Mirrors the pillarUp
            // height-gate exactly. NOT water-exempt (unlike parkourDescend): the failure here ENDS in
            // water, so the hold must persist there to keep the bot anchored at the climb-out node — if
            // the leap still can't complete, noStepProgressTicks climbs and the existing fellOffPath /
            // wedge-repath re-routes from the real position (the deterministic escape the original took,
            // just sooner and from the correct node). Strict extension: a rising parkour mid-leap is
            // ALREADY below its landing and not grounded, so this is inert on a clean leap (it was going
            // to hold via the airborne state anyway); only the premature drift-skip is suppressed.
            if (BotConfig.walkerParkourAscendHold
                    && se != null && se.move != null && se.move.startsWith("parkour")
                    && !se.move.startsWith("parkourDescend") && !se.move.startsWith("parkourPlace")
                    && wk.path.get(wk.step).getY() > foot.getY()
                    && !(p.onGround() && p.getY() >= wk.path.get(wk.step).getY() - 0.5)) break;
            BlockPos w = wk.path.get(wk.step);
            double dx = (w.getX() + 0.5) - p.getX();
            double dz = (w.getZ() + 0.5) - p.getZ();
            double cur2 = dx * dx + dz * dz;
            // A climb node counts as REACHED only once the feet are up at it. The
            // |Δy|<1.2 gate marks a +1 climb node "reached" from a full block below —
            // fine on dry land (mid-step), but in WATER the bot bobs at the surface 1
            // block under the node, so `within` fired early, advanced `step` to the
            // NEXT node, and left an impossible +2 climb (trace: stuck at y62 targeting
            // node y64). For an upward node while in water, don't advance until the
            // feet have actually risen to it.
            double dyNode = w.getY() - p.getY();
            // In WATER the buoyant body rides the surface; a path node BELOW it
            // (A* routed a wide crossing along the riverbed) can never be reached
            // by Y — the bot floats over it forever (trace: rode y61.78 above a y60
            // diagDown node, |dY|=1.78 > 1.2, never advanced → a ~50-block river was
            // uncrossable). Treat horizontal alignment ALONE as "reached" for such
            // a submerged below-node so the crossing advances node-by-node at the
            // surface. Excluded: a deliberate swimDown dive edge (we want that
            // descent); a climb-up node (dyNode>0) stays gated by the clause below
            // so a buoyant bob can't skip an intermediate +1 climb node.
            boolean diveEdge = se != null && se.move != null && se.move.startsWith("swimDown");
            // A buoyant body rides 1-2 blocks ABOVE the in-water below-nodes of a
            // riverbed crossing and can never close the Y gap — the flatWaterWalk
            // actuator already sprint-swims it flat at the surface, so horizontal
            // alignment ALONE must advance the step. The old gate also required
            // !p.isUnderWater(), which DISABLED the whole crossing the instant the
            // eyes bobbed under the waterline (isUnderWater flickers true on a
            // surface swimmer) — the bot then pinned at the node's XZ forever (live
            // 2026-06-15 deep-water arena: open-water cross, node 1.4 below,
            // undW=true bobbing, 2600+ ticks at step 1). Bounded to ≤2.5 below so a
            // genuine deep descent isn't skipped (A* places crossing nodes ~1 below
            // the floating foot, not at the riverbed). A deliberate swimDown dive is
            // still allowed to descend — UNLESS buoyancy has refused the sink past
            // WATER_DESCEND_GIVEUP (the same surface-pin failure for a dive edge),
            // in which case surface-cross past it too rather than deadlock.
            // Float over a NON-dive submerged node at ANY depth: A* routes a wide deep-water
            // crossing along the riverbed (live 2026-06-23: walk/parkour nodes placed at y58, FOUR
            // below the floating foot in 9-deep water), and the surface swimmer must cross
            // horizontally ABOVE them — never follow them down. The old -2.5 floor let the bot dive
            // to and bob at deep crossing nodes (totStuck 100+, the "潜底/挖墙" stall). A deliberate
            // swimDown dive (diveEdge) keeps the tight -2.5 + give-up so a real descent isn't skipped.
            double floatOverFloor = diveEdge ? -2.5 : -FLOATOVER_NONDIVE_MAX_DROP;
            boolean floatOverSubmerged = p.isInWater() && dyNode < -0.5 && dyNode > floatOverFloor
                    && (!diveEdge || wk.stepProg.noStepProgressTicks > WATER_DESCEND_GIVEUP);
            boolean within = cur2 < REACH_DIST_SQ
                    && (Math.abs(dyNode) < 1.2 || floatOverSubmerged)
                    && !(p.isInWater() && dyNode > 0.5);
            // Pure-pursuit re-sync: also advance past a node we've already gone
            // by — the next node being closer than this one means the player is
            // beyond it. Without this, sprinting toward a far carrot (or a
            // sliced repath that starts from a now-stale foot) leaves `step`
            // pointing at a node BEHIND the player, so the aim flips ~180°.
            boolean passed = false;
            if (!within && wk.step + 1 < wk.path.size()) {
                BlockPos nx = wk.path.get(wk.step + 1);
                double ndx = (nx.getX() + 0.5) - p.getX();
                double ndz = (nx.getZ() + 0.5) - p.getZ();
                // Skip the current node only if the player is genuinely beyond it
                // (next node STRICTLY horizontally closer) AND the next node is
                // itself vertically reachable from where the player actually IS.
                // Without the vertical clause, standing at the bottom of a 2-deep
                // pit the re-sync skips the intermediate +1 climb node and locks
                // onto a node +2 above — an impossible single jump → bot wedged
                // (bunker pit, totStuck>1000). The STRICT '<' matters when the next
                // node is stacked directly above the current one (a pillarUp: same
                // x,z → ndx²+ndz² EQUALS cur2): with '<=' the tie reads as "passed"
                // and the bot skips the walk-to-the-pillar-base node, then tries to
                // pillar in place wherever it happens to be standing (observed:
                // mining an offset trunk, bot jumped at x=16.7 chasing a pillar at
                // x=15, never placing). A real overshoot makes next STRICTLY closer,
                // so '<' still resyncs those. The 1.2 gate matches a jump's climb.
                // Same in-water climb gate as `within` above: the buoyant body bobs
                // y±0.9, so at the bob's crest |nx.y−p.y| can dip under 1.2 for a
                // node it never actually climbed to — passing skips the climb base
                // and leaves an impossible +2 target (stuck at y62 vs node y64).
                // OVERSHOOT relaxation: once the foot is clearly PAST node w
                // horizontally (cur2 > OVERSHOOT_RESYNC_SQ) the bot is no longer
                // approaching a climb base, so the two near-base guards must not
                // freeze the pointer. (a) The strict '<' tie-break — which protects
                // a pillarUp/swimUp node stacked directly above w (ndx²+ndz² == cur2)
                // — relaxes to '<=' so a stacked climb node can't pin a node the bot
                // has overshot. (b) The in-water "don't skip a buoyant +1 climb" gate
                // is dropped. The |Δy|<1.2 reachability gate on nx STAYS in both
                // cases, so the re-sync still can't lock onto an impossible +2 climb.
                boolean overshot = cur2 > OVERSHOOT_RESYNC_SQ;
                double nd2 = ndx * ndx + ndz * ndz;
                // Fell BELOW a descend node the bot has gone PAST: a steep crest /
                // shoulder where the bot crosses with forward momentum and free-falls
                // 2-3 blocks past the fall node, grounding on the terrace below it
                // (live 2026-06-15 reverse (2426,99,2153): foot y96, node y99, 1.8 b
                // past it in z, pinned against the far face → stuck 1341 / ~67 s). The
                // |w.y - p.y| < 1.5 guard — there to refuse "passing" a node we haven't
                // risen TO — also refuses this dropped-past node, freezing `step` on a
                // node 3 ABOVE the foot with the aim pointing back-UP at it (none of
                // within / fellOffPath catch it: |Δy|=3 misses within, and 3 ≤ maxJump+2
                // misses the re-search). Relax it when w is clearly ABOVE the foot AND
                // the route keeps DESCENDING through it (nx no higher than w): then w is
                // behind+above, a dropped-past descend node, not a climb target — and the
                // nx reachability gate below (|nx.y - p.y| < 1.2) still bars locking onto
                // an impossible climb.
                // Dry land only: a buoyant body in water legitimately rides above/below
                // its nodes (floatOverSubmerged / dive own that), so "foot below the node"
                // is normal there and must NOT skip a climb/parkour node — the live wedge
                // is a grounded terrace landing (inW=false, onG=true throughout).
                boolean droppedPastDescend = !p.isInWater()
                        && w.getY() - p.getY() >= 1.5
                        && nx.getY() <= w.getY();
                passed = (overshot ? nd2 <= cur2 : nd2 < cur2)
                        && (Math.abs(w.getY() - p.getY()) < 1.5 || droppedPastDescend)
                        && Math.abs(nx.getY() - p.getY()) < 1.2
                        && !(!overshot && p.isInWater() && nx.getY() - p.getY() > 0.5);
            }
            // TAIL overshoot: the foot blew past the FINAL node of a best-effort
            // (sliced / progressive quick-start-stub) segment. There is no next
            // node to re-sync onto, so the block above never runs and neither
            // `within` nor `passed` can fire — `step` froze on the tail while the
            // bot crabbed 100 blocks past it on anti-stuck bursts, waiting out the
            // slow superseding search (live 2026-06-15 wide-water crossing: step
            // 6/7, p 100 blk past a 6-node stub's last node, stuck 745, minutes of
            // burst-crab). A best-effort tail is a splice point, never the goal, so
            // a gross HORIZONTAL overshoot (cur2 gate, so a straight pillar-up to
            // the tail — same XZ, small cur2 — is excluded) means the segment is
            // spent: advance so the segment-end block below adopts the continuation
            // / repaths from HERE instead of pinning on the stale tail.
            // VERTICAL tail overshoot: the bot blew past the tail DOWNWARD (a fall
            // node on a cliff lip — it free-fell well below the tail and is grounding
            // at the bottom, horizontally still on the tail's XZ so the cur2 gate above
            // never trips). Same dead-zone as droppedPastDescend but with no next node
            // to re-sync onto (live 2026-06-15 reverse fall2 (2436,92,2180): foot fell
            // to y82, 10 below, |dy|=9.9, cur2 0.6 → stuck 128 / ~6.4 s waiting on the
            // fellOffPath re-search). Consume the spent descend tail so the segment-end
            // block repaths from HERE at once. Gated to a DESCEND incoming edge so a
            // pillarUp/climb tail (bot legitimately below it) is never aborted.
            boolean descendTail = se != null && se.move != null
                    && (se.move.startsWith("fall") || se.move.startsWith("diagDown")
                        || se.move.equals("stepDown"));
            boolean tailDroppedPast = !p.isInWater() && descendTail
                    && p.getY() < w.getY() - 2.0;
            boolean tailConsumed = !within && wk.step + 1 == wk.path.size() && wk.pathBestEffort
                    && (cur2 > OVERSHOOT_RESYNC_SQ || tailDroppedPast);
            // DESCENT OVERSHOOT-ADVANCE (the 原地后跳 back-hop fix the in-place-hop comment
            // points to): on a dry descent step the body drives the IMMEDIATE node
            // (driveTargetYaw = descentNodeYaw). The instant the foot crosses PAST that node
            // toward the next one, the node sits behind the body and the decoupled drive
            // reverses — the ~0.1-0.25/tick backward hop seen on every slope / stepDown
            // (descentYawArena backSteps=42, worstBack=-0.25). `passed` only advances at the
            // w→nx MIDPOINT (nd2<cur2), leaving a 2-3 tick window where the drive rides the
            // overshot node and hops back. Advance one tick earlier — the moment the foot is on
            // the FORWARD side of node w along the w→nx segment (projection of w→foot onto w→nx
            // positive) — so the drive never rides a node behind it. Dry + descend-edge gated
            // (water/climb keep their own gates); the |Δy|<1.2 bar keeps it off an impossible
            // climb, and the forward-projection test means a switchback leg (foot past w but NOT
            // toward nx) never trips it.
            // DISCRETE descents only (fall off a lip / a single stepDown): a continuous diagDown
            // SLOPE legitimately rides the immediate node for trend-camera smoothing, and advancing
            // eagerly there over-leans the descent into MORE back-correction (descentYawArena
            // backSteps 42→71). A discrete drop has one clean overshoot to consume.
            boolean discreteDescend = se != null && se.move != null
                    && (se.move.startsWith("fall") || se.move.equals("stepDown"));
            boolean crossedDescendNode = false;
            if (!within && !passed && discreteDescend && !p.isInWater() && wk.step + 1 < wk.path.size()) {
                BlockPos nxd = wk.path.get(wk.step + 1);
                double segx = nxd.getX() - w.getX(), segz = nxd.getZ() - w.getZ();
                double offx = p.getX() - (w.getX() + 0.5), offz = p.getZ() - (w.getZ() + 0.5);
                crossedDescendNode = (offx * segx + offz * segz) > 0
                        && Math.abs(nxd.getY() - p.getY()) < 1.2;
            }
            // WALK overshoot-advance — mirror of crossedDescendNode for a FLAT walk node the bot has
            // crossed and is now ORBITING in the step-advance dead-zone (cur2 ∈ [REACH_DIST_SQ,
            // OVERSHOOT_RESYNC_SQ]: too far for `within`, too near for the overshoot re-sync, and
            // circling so the next node never reads STRICTLY closer → `passed` never fires). Live V2
            // shore node -783,64,609: orbited z609±1 for 111 ticks ~5.5 s until the wedge-repath
            // rescued it. Gated on a CONFIRMED stuck (noStepProgressTicks) so a normally-approaching
            // walk advances via within/passed FIRST — this ONLY nudges an already-wedged orbit, never
            // cuts a live corner. walk-incoming-edge only (descents own crossedDescendNode and slopes
            // are deliberately excluded there; climbs/water keep their bases); |Δy|<1.2 bars a climb.
            boolean crossedWalkNode = false;
            if (!within && !passed && !crossedDescendNode
                    && se != null && se.move != null
                    && (se.move.equals("walk")
                        || (BotConfig.walkerTraverseBreakOvershootResync && se.move.equals("traverseBreak")))
                    && !p.isInWater() && wk.stepProg.noStepProgressTicks > WALK_OVERSHOOT_STUCK_TICKS
                    && wk.step + 1 < wk.path.size()) {
                BlockPos nxw = wk.path.get(wk.step + 1);
                double segx = nxw.getX() - w.getX(), segz = nxw.getZ() - w.getZ();
                double offx = p.getX() - (w.getX() + 0.5), offz = p.getZ() - (w.getZ() + 0.5);
                crossedWalkNode = (offx * segx + offz * segz) > 0
                        && Math.abs(nxw.getY() - p.getY()) < 1.2;
            }
            // WATER-SURFACE STEP-DOWN float-and-advance: a stepDown (or short discrete descent) whose
            // node is a SHALLOW water-surface foothold (water at the node, SOLID floor one below, head
            // not water) lands a buoyant body that grounds vertically AT the node (|dyNode|≈0) but pins
            // ~0.74 b short of centre (cur2≈0.55) — just over the tight REACH_DIST_SQ=0.45 — because
            // buoyancy + the water-climb jump keep lifting/ramming the foot and the prone swim can't
            // nudge the last 0.1 b in. `within` (cur2<0.45) never fires and the step freezes (live #47
            // -809,62,350: 12 s+ bob-ram). `floatOverSubmerged` misses it (node AT the foot, not below).
            // Treat arrival at the surface cell as reaching the node and advance at a RELAXED reach,
            // mirroring floatOverSubmerged/deepWaterRise — gated, like crossedWalkNode, on a CONFIRMED
            // stall so a clean approach advances via within/passed first and this only rescues the pin.
            // Strictly scoped: stepDown/fall/diagDown incoming edge + shallow water-surface node; a dry
            // step-down, a deep floating-water landing, a submerged node, and climb/walk/parkour edges
            // are all byte-identical inert (the gate never matches them).
            boolean waterStepDownFloat = false;
            if (BotConfig.walkerWaterStepDownFloat && !within && !passed
                    && !crossedDescendNode && !crossedWalkNode
                    && se != null && se.move != null
                    && (se.move.equals("stepDown") || se.move.startsWith("fall") || se.move.startsWith("diagDown"))
                    && (p.isInWater() || wk.surfaceWaterLatch > 0)
                    && wk.stepProg.noStepProgressTicks > WATER_STEPDOWN_STALL_TICKS
                    && cur2 < WATER_STEPDOWN_REACH_SQ
                    && Math.abs(dyNode) < 1.2
                    && world.isWater(w) && world.isSolid(w.below()) && !world.isWater(w.above())) {
                waterStepDownFloat = true;
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] water-stepdown-float ADVANCE step={}/{} node={} move={} cur2={} dyNode={} stall={}",
                            wk.step, wk.path.size(), w, se.move, String.format(Locale.ROOT, "%.3f", cur2),
                            String.format(Locale.ROOT, "%.2f", dyNode), wk.stepProg.noStepProgressTicks);
            }
            // STEPUP-CREST float-and-advance: a +1 stepUp/diagUp CREST node (a diagonal-staircase plateau
            // lip) that the buoyancy-free body has TOPPED OUT on but ORBITS — it reaches the node's Y at
            // the apex bob (|dyNode|≈0) yet the small lateral orbit (±0.5 b) keeps cur2 pinned at
            // ~0.49-1.2, just over the tight REACH_DIST_SQ=0.45, so `within` never fires, and while
            // circling the next node never reads STRICTLY closer so `passed` never fires either — the
            // step freezes ~25-51 ticks (live 2026-06-26 -633,80,318 diagonal-staircase crest: move=stepUp,
            // cur2 floor 0.492, py 79.0↔80.25 across node y80, pz 317.7↔318.7 orbit, onGround flickering,
            // ~1.25 s pin; worst documented 2.55 s). NO actuator catches it: ascentRamSlide needs the node
            // ≥2 ABOVE a GROUNDED foot (here +1 above a bobbing/airborne foot); descentRamStuck needs the
            // node 1 BELOW + hCol; stepUpFreeze needs a grounded riser-ram (the bot is airborne-orbiting,
            // no hCol). Mirror waterStepDownFloat/crossedWalkNode: once the bot has reached the crest node's
            // Y and STALLED there orbiting, treat the apex arrival as reaching the node and ADVANCE at a
            // RELAXED reach (STEPUP_CREST_REACH_SQ, well under OVERSHOOT_RESYNC_SQ=4 so it can't cut a live
            // corner). STRICTLY scoped: a stepUp/diagUp incoming edge + the foot ALREADY risen to the node
            // (|dyNode|<0.5 — a node still being climbed from a full block below has |dyNode|≥0.5, so it is
            // never skipped) + a CONFIRMED stall (a clean stepUp advances via within/passed in <12 t,
            // resetting noStepProgressTicks, and never trips the 16-tick gate) + DRY land (water ascents
            // are owned by floatOverSubmerged / the in-water climb gate). A non-ascent edge, a node not yet
            // reached vertically, a fast clean climb, and any in-water case are all byte-identical inert.
            // BOB-IMMUNE crest-orbit dwell: noStepProgressTicks (the stall gate below) resets on every new
            // 3D-low (Walker:1669), and a DRY topped-out crest's vertical bob folds wdy into wd2 and
            // manufactures a new low each bob cycle, so on a WIDE bob orbit the gate is never reached and the
            // crest never advances (live -677,80: cur2 0.5→8.6, ADVANCE never fired, 120-173 t orbit). Count
            // raw ticks the body dwells on the SAME dry stepUp/diagUp crest step without within/passed
            // advancing, reset ONLY on a step-advance/path-change — the bob can't zero it, so a genuine orbit
            // accumulates past the gate. The crest-reach's |dyNode|<0.5 topped-out guard still picks the firing
            // tick, so this never advances a node the foot hasn't risen to (no skip-node strand).
            boolean onAscentCrest = se != null && se.move != null
                    && (se.move.equals("stepUp") || se.move.equals("diagUp")) && !p.isInWater();
            if (wk.step != wk.stepProg.crestOrbitStep) {
                wk.stepProg.crestOrbitStep = wk.step;
                wk.stepProg.crestOrbitTicks = 0;
            } else if (onAscentCrest && !within && !passed) {
                wk.stepProg.crestOrbitTicks++;
            }
            boolean stepUpCrestReach = false;
            if (BotConfig.walkerStepUpCrestReach && !within && !passed
                    && !crossedDescendNode && !crossedWalkNode && !waterStepDownFloat
                    && se != null && se.move != null
                    && (se.move.equals("stepUp") || se.move.equals("diagUp"))
                    && !p.isInWater()
                    && wk.stepProg.crestOrbitTicks > STEPUP_CREST_STALL_TICKS
                    && cur2 < STEPUP_CREST_REACH_SQ
                    && Math.abs(dyNode) < 0.5) {
                stepUpCrestReach = true;
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] stepup-crest-reach ADVANCE step={}/{} node={} move={} cur2={} dyNode={} crestStall={} noStep={}",
                            wk.step, wk.path.size(), w, se.move, String.format(Locale.ROOT, "%.3f", cur2),
                            String.format(Locale.ROOT, "%.2f", dyNode), wk.stepProg.crestOrbitTicks, wk.stepProg.noStepProgressTicks);
            }
            // WATER-SURFACE WALK relaxed-advance (the turn / wall-corner FREEZE breaker): a flat `walk`
            // water-surface node the buoyant body sits ~0.67 b out from (cur2 floor ~0.455, just over
            // REACH_DIST_SQ=0.45) so `within` never closes. On a straight crossing forward momentum fires
            // `passed`, but at a TURN / terminal / WALL-CORNER node the bot isn't crossing toward the next node
            // so `passed` can't fire either — the flat water walk node then has NO relaxed-advance and the bot
            // orbits / freezes against the corner (live deep-water bay corner: cur2 1.142 FROZEN 321 ticks,
            // within=0, aim swinging 403° — the "贴墙/水里卡住" jank; attack=0, NOT digging). Mirror
            // waterStepDownFloat / stepUpCrestReach: on a CONFIRMED in-water stall at a flat walk water node
            // within a relaxed reach, advance so the segment continues / repaths from here. In water
            // noStepProgressTicks is HORIZONTAL-only (Walker:~1666) so the bob can't fake-reset it — only a
            // genuine non-closing freeze accumulates it past the gate; a clean crossing advances via `passed`
            // in 1-2 ticks (noStepProgress stays low) and never trips it. The |Δy|<1.2 gate on the next node
            // bars an impossible climb-skip; dry walk and every non-walk edge are byte-identical inert.
            boolean waterWalkReach = false;
            if (BotConfig.walkerWaterWalkReach && !within && !passed
                    && !crossedDescendNode && !crossedWalkNode && !waterStepDownFloat && !stepUpCrestReach
                    && se != null && se.move != null && se.move.equals("walk")
                    && p.isInWater()
                    && wk.stepProg.noStepProgressTicks > WATER_WALK_STALL_TICKS
                    && cur2 < WATER_WALK_REACH_SQ
                    && wk.step + 1 < wk.path.size()
                    && Math.abs(wk.path.get(wk.step + 1).getY() - p.getY()) < 1.2) {
                waterWalkReach = true;
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] water-walk-reach ADVANCE step={}/{} node={} cur2={} stall={}",
                            wk.step, wk.path.size(), w, String.format(Locale.ROOT, "%.3f", cur2), wk.stepProg.noStepProgressTicks);
            }
            // [STEP-ADV-DIAG temp — remove before commit] why a grounded grossly-overshot node won't
            // advance (-823 dimple churn): logs which advance fired + the descend-geometry sub-conditions.
            if (BotConfig.walkerDebug && !within && p.onGround() && cur2 > OVERSHOOT_RESYNC_SQ
                    && wk.stepProg.noStepProgressTicks > 6 && wk.step + 1 < wk.path.size()) {
                BlockPos dN = wk.path.get(wk.step + 1);
                double dsegx = dN.getX() - w.getX(), dsegz = dN.getZ() - w.getZ();
                double doffx = p.getX() - (w.getX() + 0.5), doffz = p.getZ() - (w.getZ() + 0.5);
                LOG.info("[walker] STEP-ADV-DIAG step={}/{} w={} cur2={} footNodeDy={} se={} disc={} | passed={} crossDesc={} crossWalk={} | nx={} nxY={} pY={} fwdDot={} nxYgate={}",
                        wk.step, wk.path.size(), w, String.format("%.2f", cur2),
                        foot.getY() - w.getY(), (se != null && se.move != null ? se.move : "null"),
                        (se != null && se.move != null && (se.move.startsWith("fall") || se.move.equals("stepDown"))),
                        passed, crossedDescendNode, crossedWalkNode,
                        dN, dN.getY(), String.format("%.2f", p.getY()),
                        String.format("%.2f", doffx * dsegx + doffz * dsegz),
                        Math.abs(dN.getY() - p.getY()) < 1.2);
            }
            // Phase-1 (walkerArcLengthAdvance): the bob-immune path projection ADDS a step-advance the legacy
            // gates miss — advance when the foot's forward projection has reached a later segment
            // (arcProj.segIdx > step). It is a SUPPLEMENT, not a replacement: the seven legacy gates
            // (passed/tailConsumed/crossedDescend/crossedWalk/waterStepDownFloat/stepUpCrestReach/waterWalkReach)
            // still fire because they advance in NEAR-reach cases the projection is too strict for — e.g. the bot
            // orbiting just below a crest stepUp (cur2~0.55, the foot hasn't projected past the node so segIdx is
            // pinned, but stepUpCrestReach legitimately advances it over the crest; stepUpCrestOrbitArena /
            // waterStepDownFloatArena assert exactly this). The union (legacy || projection) is strictly more
            // advancing than either alone, so it both kills the bob-defeated step-freeze (projection drives) AND
            // keeps the reach helpers (legacy drives). Edge-execution holds above still gate advancement.
            boolean legacyAdvance = within || passed || tailConsumed || crossedDescendNode || crossedWalkNode
                    || waterStepDownFloat || stepUpCrestReach || waterWalkReach;
            boolean doAdvance = legacyAdvance
                    || (BotConfig.walkerArcLengthAdvance && wk.arc.proj.segIdx > wk.step);
            if (doAdvance) {
                // Don't CONSUME the final node of a disk goal while it sits inside the goal
                // radius but the bot's FOOT cell is still one block short of it. The node-reach
                // gate (~0.67 blk) fires ~1 block out, so consuming here ends the path with
                // goal.reached(foot) still FALSE: the segment-end handler hits frontierHoldOrArrive,
                // which STOPS the drive and declares ARRIVED a block short — the bot freezes at
                // the radius edge and only autoSwim drift carries it in (the near-goal open-water
                // "stall"; live 2026-06-23: XZ -1700,900 r6 pinned the bot at foot -1693, dx=7,
                // OUTSIDE r6, then fake-ARRIVED). Hold on the last node so the normal pure-pursuit
                // walks the foot ONTO it and the goal.reached check at the top of step() fires for
                // real. Scoped to radius>0 disk goals (XZ/Near) so an exact-cell goal's
                // long-standing within-arrival — and a floating bot's radius-0 water arrival —
                // are unchanged.
                boolean diskGoal = (wk.goal instanceof Goal.XZ xz && xz.radius() > 0)
                        || (wk.goal instanceof Goal.Near nr && nr.radius() > 0);
                if (wk.step + 1 >= wk.path.size() && diskGoal
                        && !wk.goal.reached(foot) && wk.goal.reached(wk.path.get(wk.path.size() - 1)))
                    break;
                wk.step++;
            }
            else break;
        }
        if (wk.step >= wk.path.size()) {
            if (!wk.pathBestEffort || wk.goal.reached(foot)) {
                // A path that actually reaches the goal (or we ended up standing in
                // the goal cell): the journey is done.
                return wk.terminalReport(Walker.Step.ARRIVED, PathTrace.Outcome.SUCCESS, null,
                        Walker.classifyArrival(wk.pathBestEffort, wk.goal.reached(foot), wk.goalSnapped), foot);
            }
            // Best-effort segment consumed but the goal is still ahead. DON'T give
            // up — this is the long-distance splice point. Adopt the continuation
            // if its background search has landed; otherwise hold here (keys
            // released) until it does. The total-tick budget above still bounds a
            // goal we can never make real progress toward, so this can't hang.
            if (wk.pendingSegment != null) {
                PathFinder.Result next = wk.pendingSegment;
                wk.pendingSegment = null;
                if (next.hasPath() && next.path().size() > 1) {
                    if (!wk.adoptPath(next, world, foot)) {
                        // Mis-anchored continuation (the bot never made it to the
                        // commitEnd it was computed from) — drop it and force a
                        // fresh foot-search next tick instead of hanging on an
                        // unreachable step 1.
                        wk.path = null;
                        wk.edges = null;
                        wk.step = 0;
                        wk.commitEnd = null;
                        Walker.agentForward(a, false);
                        Walker.agentJump(a, false);
                        p.setSprinting(false);
                        return Walker.Step.WALKING;
                    }
                    // adopted: step→1 on the new segment; fall through to walk it
                } else {
                    // Stale eager continuation found nothing — at a chunk frontier the
                    // newly-loaded terrain may now reveal the next segment, so re-search
                    // fresh before giving up (bounded).
                    return wk.frontierHoldOrArrive(a, world, p);
                }
            } else {
                if (!wk.replayMode && wk.activeSearch == null && wk.commitEnd != null) {
                    wk.activeSearch = new PathFinder(world, wk.profile).withOwner(wk.owner).newSearch(wk.commitEnd, wk.goal);
                    wk.searchFromEnd = true;
                }
                // PROGRESSIVE QUICK-START at the splice gap: the continuation
                // search hasn't landed yet (eager precompute missed this one) —
                // walk a synchronous stub toward the goal instead of holding
                // at the segment end until it does.
                if (wk.replayMode || wk.activeSearch == null
                        || (!wk.tryLandBeeline(world, foot, wk.goal)
                            && !wk.tryQuickStart(world, foot, wk.goal) && !wk.tryWaterBeeline(world, foot, wk.goal))) {
                    Walker.agentForward(a, false);
                    Walker.agentJump(a, false);
                    p.setSprinting(false);
                    return Walker.Step.WALKING;
                }
                // stub adopted (path replaced, step=1) → fall through and walk it
            }
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        return null;
    }
}
