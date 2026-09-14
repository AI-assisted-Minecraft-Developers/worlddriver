package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.movement.PathSmoothing.*;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 3505–4181): re-centre, carrot aim, target-yaw EMA + trend camera, anti-spin freeze, dive + pitch.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickAim {
    private WalkerTickAim() {}

    /**
     * The tangent aim never applies on the LAST node. Every earlier node is spent by crossing its
     * plane, so a tangent that carries the body past it is fine; the last one is spent only by
     * CLOSING to within ~0.67 of its centre, and a tangent is by construction the direction that
     * does not close — it is the previous segment's heading. wd.clientGotoStartsMidAir measured
     * it: a diagonal approach at sprint, yaw 91° off the node bearing, nearest pass 0.8, then 400
     * ticks of unstuck bursts and repaths around a goal the body had already reached.
     */
    /**
     * The EMA rate for the target heading: the slow trend-camera alpha, or — under
     * {@code walkerOrbitBreaksAimLag} — the cruise alpha once the body has spent {@code ORBIT_TICKS}
     * moving with a mid-range heading error. Under tangent drive the body follows this EMA, and an
     * error that neither closes nor flips while the body moves is the body circling its node one cell
     * out: the slow alpha can never catch a bearing that rotates at its own convergence rate. Dry
     * only — water has its own drive heading.
     */
    private static float smoothingAlpha(Walker wk, LivingEntity p, boolean trendCam, float targetYaw) {
        float alpha = trendCam ? YAW_SMOOTH_ALPHA_DESCENT : YAW_SMOOTH_ALPHA;
        AimSmoothing a = wk.aimSmooth;
        float turn = Float.isNaN(a.orbitLastYaw) ? 0f : angleDiff(a.orbitLastYaw, p.getYRot());
        a.orbitLastYaw = p.getYRot();
        if (!BotConfig.walkerOrbitBreaksAimLag || !trendCam || p.isInWater()) {
            a.orbitTicks = 0;
            a.orbitWinding = 0;
            a.orbitBreaking = false;
            return alpha;
        }
        float err = Math.abs(angleDiff(a.smoothTargetYaw, targetYaw));
        boolean moving = p.getDeltaMovement().horizontalDistanceSqr() > ORBIT_MOVE_SQ;
        // A detour turns at its corners and then walks straight; a circling body turns the same
        // way every tick. Winding in one direction is the signature: a direction change or a
        // standstill ends it, a tick whose error happens to dip low (the raw bearing sweeps as the
        // body passes the node) merely does not add to it.
        if (!moving || turn * a.orbitWinding < 0) {
            a.orbitTicks = 0;
            a.orbitWinding = 0;
            a.orbitBreaking = false;
            return alpha;
        }
        if (err > ORBIT_ERR_MIN_DEG && err < ORBIT_ERR_MAX_DEG) {
            a.orbitWinding += turn;
            a.orbitTicks++;
        }
        if (a.orbitTicks <= ORBIT_TICKS || Math.abs(a.orbitWinding) < ORBIT_WINDING_DEG) return alpha;
        if (BotConfig.walkerDebug && !a.orbitBreaking)
            LOG.info("[walker] orbit-break: heading error {}° over {} ticks, wound {}° — cruise alpha",
                    String.format("%.0f", err), a.orbitTicks, String.format("%.0f", a.orbitWinding));
        a.orbitBreaking = true;
        return YAW_SMOOTH_ALPHA;
    }

    private static boolean onLastNode(Walker wk) {
        return BotConfig.walkerFinalNodeDirectAim && wk.path != null && wk.step == wk.path.size() - 1;
    }

    /**
     * walkerTangentPursuit: the bare tangent carries no cross-track term, so a body that is off the
     * path (a smoothed route whose first hop is a diagonal off the start, a shove, a corner cut)
     * walks PARALLEL to it and never rejoins — measured in {@code wd.routeStaysOutOfSkeletonSight}:
     * perp 1.4 held for 20 cells, the body in the open while the route it was given ran in the
     * wall's shadow. Past {@code PURSUIT_PERP} aim at the path's point ahead instead; on the path
     * the two bearings coincide, so the tuned tangent cruise is unchanged there.
     */
    private static float tangentOrPursuit(Walker wk, LivingEntity p, boolean launch, BlockPos foot) {
        return offPathPursuit(wk, p, launch, foot) ? wk.arc.proj.pursuitYaw : wk.arc.proj.tangentYaw;
    }

    /**
     * The case {@link #tangentOrPursuit} rejoins in, also what drops the trend camera: on a dry flat
     * walk {@code trendCam} is always on, and under tangent mode its centroid overwrite IS the drive
     * (driveTargetYaw = aimYaw = EMA(targetYaw)), so the pursuit bearing never reached the body —
     * the centroid of nodes step+2.. is no more a rejoin heading than the tangent is (measured:
     * driveYaw −93 = the far centroid, perp 1.4 held for 20 cells). Off the path, drop the trend
     * camera the way {@code recoverySnagAim} does, so the pursuit flows through the same EMA at the
     * fast cruise alpha.
     *
     * <p>Scoped to a LEVEL stretch with a segment ahead: dry, the current and the next node at the
     * foot's Y, not the last node. A step down or a plan's last node has its own tuned handling
     * (walkerDescentNodeHold, the descent decouple), and the first cut of this — perp alone —
     * walked the body west and off the doorway in {@code wd.serverStepsDownAPlanItSpentInOneTick}
     * and lost an ore in {@code wd.serverMineHarvestBuried}; both green with the flag off, both
     * green again with this scope. A buoyant body rides off its nodes legitimately.
     */
    private static boolean offPathPursuit(Walker wk, LivingEntity p, boolean launch, BlockPos foot) {
        if (!BotConfig.walkerTangentAim || !BotConfig.walkerTangentPursuit || launch || p.isInWater()) return false;
        if (wk.path == null || wk.step + 1 >= wk.path.size()) return false;
        if (wk.path.get(wk.step).getY() != foot.getY() || wk.path.get(wk.step + 1).getY() != foot.getY()) return false;
        return wk.arc.proj.perp > PURSUIT_PERP;
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        Move.Edge edge = cx.edges.edge;
        BlockPos wp = cx.edges.wp;
        boolean parkourEdge = cx.edges.parkourEdge;
        boolean bridging = cx.edges.bridging;
        boolean placingEdge = cx.edges.placingEdge;
        boolean steppingOffFall = cx.edges.steppingOffFall;
        boolean steppingOffWaterFall = cx.edges.steppingOffWaterFall;
        // ---- original body (byte-identical modulo member prefixes) ----

        // Aim at a line-of-sight carrot further along the (now string-pulled)
        // path so the heading stays steady — no left-right wobble. For a
        // vertical move or a real parkour leap, face the actual waypoint so
        // the jump goes the right way.
        // A buoyant bot BOBS ±0.5 around its swim level, so foot.getY()=floor(p.getY())
        // flickers between the node's Y and Y-1 even on a dead-flat swim. With the raw
        // `wp.getY() != foot.getY()` test that toggled aimAtWaypoint every bob tick,
        // snapping the aim between the stable look-ahead carrot and the CLOSE waypoint
        // and swinging the yaw left-right (live 2026-06-15 deep-water crossing: camera
        // "高频左右摆", pathChart maxYawErr 172°). In water, judge the vertical maneuver
        // by the CONTINUOUS Y gap to the waypoint with a bob-proof threshold (a flat or
        // gently-graded swim stays on the carrot; only a genuine dive / bank-climb ≥~1.5
        // aims at the block). On land the integer test is exact and unchanged.
        double wpAimDy = (wp.getY() + 0.5) - p.getY();
        boolean aimAtWaypoint = (p.isInWater() ? Math.abs(wpAimDy) > 1.5 : wp.getY() != foot.getY())
                || parkourEdge;
        // Re-centre recovery on a stuck flat walk: a 1-wide channel needs the
        // body centred on the lane axis or the off-centre hitbox snags a corner
        // and wedges (the look-ahead carrot aims diagonally, so it never centres
        // and the bot grinds the boundary). When genuinely stuck, steer to the
        // centre of the last confirmed on-spine node (the cell we came from):
        // that pulls the body straight onto the lane axis, after which the carrot
        // — aimed at the next node, same axis — is a clean straight push. Only
        // fires when stuck (normal open walking never is), so it can't reverse a
        // healthy run.
        // Cross-axis re-centre on a stuck flat walk: a 1-wide channel flush against
        // a wall needs the body held on the lane axis or the off-centre hitbox
        // grazes the wall and can't slide forward. When stuck, steer PURELY along
        // the cross axis of the immediate cardinal move (correct X for a N/S lane,
        // Z for an E/W lane) — never along the lane itself, so it can't cancel the
        // forward carrot by pulling backward (the bug that pinned the bot mid-
        // channel). Once centred (≤0.1) it releases and the carrot's straight push
        // resumes; the two alternate but only ever add forward + lateral, so the
        // bot threads the channel instead of grinding the wall.
        // Off-spine recovery: if we've drifted to a cell from which the immediate
        // waypoint is diagonal (slid back across a corner), the carrot would aim
        // diagonally and re-snag the wall — instead steer back to the previous
        // on-spine node's centre to regain the lane. Lateral lane-CENTRING on the
        // spine is handled by the strafe below, so this only handles the gross
        // drift-off case (foot no longer in the spine cell).
        boolean reCentre = false;
        double recX = 0, recZ = 0;
        // Guard-pin stall clock: while the stride floor-guard pins the body at an unplanned
        // void edge, its fire ticks DECREMENT stuckTicks by design (the pin is a hold, not a
        // stall, and recovery bursts at a lip have killed — Walker wrapper) — so every
        // stuck-gated recovery branch below starves and the pin livelocks (r29 StepTwo:
        // st0/117 at the dogleg edge, 1300 ticks). The guard's own pinStreak IS the stall
        // clock for that state: it accumulates across fire/hold ticks by design. Use it as
        // an alternate trigger while the pin is latched — the branches it enables (reCentre /
        // nodeAim) steer along the corridor, which is exactly what releases the pin.
        int guardPinClock = wk.guardSneakLatch ? wk.guardPinStreak : 0;
        if (!aimAtWaypoint && (wk.stuckTicks > 5 || guardPinClock > 5) && wk.step > 0) {
            BlockPos sp = wk.path.get(wk.step - 1);
            boolean onSpine = foot.getX() == sp.getX() && foot.getZ() == sp.getZ();
            if (!onSpine && losWalkable(world, foot, sp)) {
                double rcx = (sp.getX() + 0.5) - p.getX();
                double rcz = (sp.getZ() + 0.5) - p.getZ();
                // Only re-centre toward the previous node when it is NOT behind us:
                // once the bot has progressed past sp, aiming at its centre points
                // backward and steers the body the wrong way (a stuck spot then drags
                // the heading ~180° around — the residual backward episode the slew
                // clamp turned from a flip into a slow reversal). Gate on the dot
                // product with the forward (toward-waypoint) direction so reCentre only
                // fires for genuine lateral drift, never to reverse. Backward case
                // falls through to the carrot, which keeps pushing forward.
                double fwx = (wp.getX() + 0.5) - p.getX();
                double fwz = (wp.getZ() + 0.5) - p.getZ();
                // NORMALIZED backward gate: the raw dot's >=0 cut also rejected the
                // near-PERPENDICULAR re-centre — which is exactly the corner-resnag this
                // branch exists for (bridge bypass trio: body pinned on the barrier's
                // face at (11.7,0.91), spine node one lane over at (11.5,1.5), dot -0.01
                // → rejected → 1200-tick wedge). Reject only a clearly BACKWARD steer
                // (beyond ~107° off the waypoint direction); lateral regains stay in.
                double rcm = Math.sqrt(rcx * rcx + rcz * rcz) * Math.sqrt(fwx * fwx + fwz * fwz);
                if (rcm > 1e-6 && (rcx * fwx + rcz * fwz) / rcm >= -0.3) {
                    recX = rcx;
                    recZ = rcz;
                    reCentre = true;
                }
            }
        }
        double adx, adz;
        // aimSrc: which branch of the aim decision tree owns the heading this tick —
        // printed in walk-keys so a pinned-heading autopsy (yaw refusing to turn toward
        // the bearing, the T-1 54s dry tear) reads the controlling channel directly
        // instead of re-deriving it from the tree's inputs.
        String aimSrc;
        if (aimAtWaypoint) {
            aimSrc = "wp";
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
            // Dry +1 staircase camera-spin fix: once the bob carries the body
            // horizontally ON TOP of the close +1 step node, that node's bearing flips
            // ±180° each tick (it's now beside/behind the foot) and a multi-step stair
            // accumulates a full 360°+ camera swing (live 2026-06-15 z1864 yawRange 405°)
            // even though the climb keeps progressing. When within ~1 block of the step
            // node horizontally AND a further node exists, aim at the NEXT node instead —
            // a genuine forward-up heading (no freeze, so it can't pin a wrong direction
            // like a held yaw does, and the buoyant water-exit mount whose next node is a
            // forward ledge walk only steadies). Land + gentle +1 only; parkour leaps
            // (precise launch aim) and steeper jumps keep the exact-waypoint aim.
            // NOTE: extending this trend-average to DESCENTS (the live 2026-06-21 "下山转圈":
            // close-node carrot-swing winds the yaw 600°+ down a steep slope) was A/B-DISPROVEN on
            // the deterministic descentYawArena — every variant was WORSE than no fix (off=1050°
            // thrash/242tk; descent-trend=1559°/569tk i.e. 2.3× SLOWER; parkour-inclusive=1529° @
            // 6.4°/tick). A steep descent routes through fall/parkour-descend nodes the trend
            // excludes, and where it engages it flickers between trend- and exact-aim, ADDING
            // thrash. The descent spin needs a different mechanism (damp the swinging target
            // bearing itself / cut switchback node density), not this aim swap. Up-steps only.
            if (!p.isInWater() && !parkourEdge && (wp.getY() - foot.getY()) == 1
                    && wk.step + 1 < wk.path.size()) {
                // Path-trend averaging (the "路径趋势平均" true fix). Sum the UNIT direction of
                // each upcoming segment while they stay consistent (<60° turn), so a diagonal
                // staircase's ±30° per-step alternation collapses to the steady diagonal and the
                // heading holds inside the pivot tolerance (no per-step drive cut → no sawtooth).
                // Stopping at a real bend keeps it from aiming across a corner (the single-far-node
                // aim that did was reverted). Also subsumes the old close-node camera-spin swap:
                // once the bob carries the body onto the close +1 node, the trend still points up
                // the stair instead of flipping ±180°. Forward-only (dot>0) never reverses.
                double tx = 0, tz = 0;
                BlockPos prev = foot;
                int last = Math.min(wk.step + STAIR_TREND_LOOKAHEAD, wk.path.size() - 1);
                for (int k = wk.step; k <= last; k++) {
                    BlockPos nd = wk.path.get(k);
                    double sx = nd.getX() - prev.getX(), sz = nd.getZ() - prev.getZ();
                    double sl = Math.sqrt(sx * sx + sz * sz);
                    if (sl < 1e-6) { prev = nd; continue; }
                    sx /= sl; sz /= sl;
                    if ((tx != 0 || tz != 0) && (sx * tx + sz * tz) / Math.sqrt(tx * tx + tz * tz) < 0.5)
                        break;                                  // segment turns >60° from the trend → stop
                    tx += sx; tz += sz;
                    prev = nd;
                }
                // Only a GENUINE diagonal run (≥2 consistent horizontal nodes accumulated) may
                // override the aim — a near-vertical climb (pillar-up / a tight dig-staircase
                // mount) sums to ~0 horizontal, so its noisy trend is rejected and the precise
                // immediate-node aim is kept (else the summit pillar + bank-dig plateau mount
                // mis-aim). Forward-only (dot>0) never reverses.
                if (Math.sqrt(tx * tx + tz * tz) >= 2.0 && (tx * adx + tz * adz) > 0) { adx = tx; adz = tz; aimSrc = "trend"; }
            }
        } else if (reCentre) {
            aimSrc = "recentre";
            adx = recX; adz = recZ;
        // Pin-clock threshold 5, not APPROACH_NODE_AIM_TICKS: guardPinStreak zeroes on every
        // unpinned tick between pin cycles, so a 13-consecutive bar loses the race against
        // same-cell plug arming (cell-sticky, survives cycles) — r32 spent 2 dirt before
        // nodeAim ever engaged. 6 pinned ticks is already a held pin (hysteresis alone is 8).
        // losWalkable seatbelt on the pin leg (r33: bypass trio + detourCheap all wedged at
        // the platform's east reconvergence corner, knife-edged at (16.3,2.7) to FAILED):
        // nodeAim is a straight-line aim, blind to void — engaged at a corner pin it aims
        // diagonally across the missing corner cells and re-pins forever. Only engage when
        // the straight line to the node is walkable (same check reCentre has always had);
        // otherwise fall through to the carrot, which follows the path cell-by-cell. The
        // plain stuckTicks leg keeps its historical unguarded form.
        } else if (wk.stuckTicks > APPROACH_NODE_AIM_TICKS
                || (guardPinClock > 5 && losWalkable(world, foot, wp))) {
            aimSrc = "nodeAim";
            // FLAT-node carrot-orbit fallback (see APPROACH_NODE_AIM_TICKS). On a flat walk the body
            // follows the look-ahead carrot; at a turn/corner node the carrot points ~60° off the close
            // node and the body orbits it at ~0.75 b without ever closing the within-gate (live FREEZE-
            // DIAG: aimAtWp=false, driveF=1, fwdComp>0, hSpd~0.07, cur2 frozen, 26-80 ticks). reCentre
            // (previous node) and the strafe (cross-axis) don't pull onto the IMMEDIATE node, so the
            // orbit only breaks on the slow wedge timer. Aim straight at the fixed node centre so the
            // body closes onto it (within / clean crossing → step advances) instead of circling the carrot.
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
        } else {
            aimSrc = "carrot";
            double[] c = wk.carrotPoint(world, foot, p.getX(), p.getZ());
            adx = c[0] - p.getX();
            adz = c[1] - p.getZ();
            // Carrot-swing thrash fix (the residual deep-water stall cluster). When a bank
            // blocks LOS to the next node, carrotPoint collapses the carrot to a CLOSE node
            // (~1 block); a buoyant bot drifting ±0.5 then makes that short aim vector rotate
            // fast and the bearing sweeps 96-176° — thrust cancels (0.4-0.9 b/s) and the
            // camera swings (maxYawErr≈180°). The dead-zone can't catch it (it fires only when
            // aim2 is small, but here aim2 > the wide dead-zone). Detect the oscillation
            // behaviourally (raw carrot bearing reversing repeatedly), and when it's genuine
            // AND no precise mount is imminent, aim at a STABLE far path node instead — a long
            // aim vector whose bearing barely moves as the bot drifts. A climb approach turns
            // monotonically (no reversals) AND is suppressed by the look-ahead, so the +1-exit
            // mount keeps its exact carrot (waterClimbOutRouteArena).
            float carrotBearing = (float) Math.toDegrees(Math.atan2(-adx, adz));
            if (p.isInWater() && !Float.isNaN(wk.aimSmooth.lastCarrotBearing)) {
                float db = angleDiff(wk.aimSmooth.lastCarrotBearing, carrotBearing);
                if (Math.abs(db) > 10f) {
                    int sign = db > 0 ? 1 : -1;
                    if (wk.aimSmooth.lastCarrotBearingSign != 0 && sign != wk.aimSmooth.lastCarrotBearingSign)
                        wk.aimSmooth.yawThrashTicks = Math.min(wk.aimSmooth.yawThrashTicks + 4, 12);
                    wk.aimSmooth.lastCarrotBearingSign = sign;
                }
            } else {
                wk.aimSmooth.lastCarrotBearingSign = 0;
            }
            wk.aimSmooth.lastCarrotBearing = carrotBearing;
            if (wk.aimSmooth.yawThrashTicks > 0) wk.aimSmooth.yawThrashTicks--;
            boolean climbAhead = false;
            for (int q = wk.step; q < Math.min(wk.path.size(), wk.step + WATER_FAR_AIM_LOOKAHEAD + 1); q++)
                if (wk.path.get(q).getY() > foot.getY()) { climbAhead = true; break; }
            if (p.isInWater() && wk.aimSmooth.yawThrashTicks >= WATER_YAW_THRASH_SCORE && !climbAhead) {
                BlockPos far = wk.path.get(Math.min(wk.step + WATER_FAR_AIM_LOOKAHEAD, wk.path.size() - 1));
                adx = (far.getX() + 0.5) - p.getX();
                adz = (far.getZ() + 0.5) - p.getZ();
                aimSrc = "farNode";
            }
        }

        // Hold heading when the horizontal aim vector is tiny (within the dead-zone) so
        // atan2 on sub-block noise can't snap the yaw each tick — see YAW_DEADZONE_SQ. Two
        // cases get the WIDER dead-zone (CLIMB_AIM_DEADZONE_SQ, ~2 blocks):
        //  (a) a water bank-climb node OVERHEAD — the floating bot can't translate onto it, so
        //      without this it orbits the column and the bearing sweeps 360° (the deep-water
        //      spin-in-place stall); holding the approach heading presses the bank for the
        //      climb-out actuator. (Original 2a587d2 behaviour — kept verbatim.)
        //  (b) any in-water aim once the bot is WEDGED/oscillating (noStepProgressTicks past a
        //      threshold). A buoyant bot can't stop precisely on a water carrot/node; within
        //      ~1 block the aim vector rotates fast as it drifts and the bearing sweeps —
        //      measured 96-176° yaw swings that collapse forward thrust to 0.4-0.9 b/s (vs
        //      4.6 b/s on the same path with a steady heading) AND drive the maxYawErr≈180°
        //      camera-swing anomaly. Gating on no-progress keeps a precisely-advancing
        //      approach on the TIGHT dead-zone, so the climb-out mount stays accurate
        //      (deepWaterClimboutNoBlockArena regressed when this widened unconditionally).
        // walkerOvershootReaim: a dry walk-node OVERSHOOT at a cliff base wedges hard — the foot blew PAST
        // the node (cur2 > OVERSHOOT_RESYNC_SQ) but the NEXT node is the climb (>1 up) so `passed` can't
        // advance onto it, and the carrot/tangent aim points the body the wrong way (backward into a wall)
        // so it never re-centres — a ram-frozen wedge (journey 2026-06-29 -558,82: yaw -179 / yawErr -120 /
        // hCol / cur2 6.28 / 260 ticks; even safetyRepath at stuck>60 re-commits the same path). When wedged
        // there, aim BACK at the overshot node so the body walks onto it (the 略微后退 it should do) and
        // relaunches the climb from the aligned base. Dry + grounded + next-too-high + long stuck only.
        // hCol-gate refinement REVERTED 2026-06-29: requiring horizontalCollision made it WORSE (rev-897, a
        // STABLE archive, regressed 161→380 — the hCol subset is where backing up HURTS, i.e. ram-and-push-
        // through, not ram-and-retreat; gating to it dropped the beneficial non-ram firings). Keep the
        // unconditional grounded-overshoot trigger that was NET-POSITIVE (corpus 4811→4104, long-540 −81%).
        if (BotConfig.walkerOvershootReaim && wk.path != null && wk.step < wk.path.size()
                && wk.step + 1 < wk.path.size() && !p.isInWater() && p.onGround()
                && wk.stuckTicks > OVERSHOOT_REAIM_STUCK) {
            BlockPos ow = wk.path.get(wk.step);
            double ocx = (ow.getX() + 0.5) - p.getX(), ocz = (ow.getZ() + 0.5) - p.getZ();
            if (ocx * ocx + ocz * ocz > OVERSHOOT_RESYNC_SQ
                    && wk.path.get(wk.step + 1).getY() - foot.getY() > 1) {
                adx = ocx;
                adz = ocz;   // walk BACK onto the overshot cliff-base node, then climb cleanly
                aimSrc = "overshoot";
            }
        }
        // walkerDryReanchor: the dry repath-rechurn breaker (REGRESSION.md §21). When a move has failed and
        // the foot is FAR off the current node (dist² > DRY_REANCHOR_OFFPATH_SQ) with a sustained stall
        // (stuckTicks > DRY_REANCHOR_STUCK) on dry land, override the carrot/tangent/node aim with a FIXED
        // aim at the last cleanly-passed node centre (path[step-1]). That fixed point's bearing barely moves
        // as the body closes, so the node-orbit yaw-thrash that paces the repath churn collapses and the bot
        // walks deterministically back ONTO the path before resuming — breaking the amplifier common to every
        // heterogeneous wedge. Excludes water (the in-water anti-spin machinery owns that) and the very first
        // node (no prior anchor). Independent of walkerOvershootReaim (this is the general off-path case; that
        // is the specific cliff-base-overshoot case) — both may compute, the later assignment wins.
        if (BotConfig.walkerDryReanchor && !p.isInWater() && wk.step > 0 && wk.step < wk.path.size()
                && wk.stuckTicks > DRY_REANCHOR_STUCK) {
            BlockPos cn = wk.path.get(wk.step);
            double cnx = (cn.getX() + 0.5) - p.getX(), cnz = (cn.getZ() + 0.5) - p.getZ();
            // ram-while-facing-wrong extension REVERTED 2026-06-29 (§25): adding an `|| (hCol && |yawErr|>90)`
            // trigger made the live -671 diagDown stall MUCH worse (oscillating limit cycle, totStuck 6009 vs
            // 509 slow-recover) — anchoring BACK to step-1 on a CLOSE ram just bounces the body back and forth
            // (pull to step-1 → re-approach → re-ram → anchor), the same oscillation that killed WallCornerNodeAim
            // (§13) and the hCol-gate (§16). A close ram is NOT fixable by aim-back. Keep the FAR-off-path-only
            // trigger (corpus-validated net-positive, §22-24); the close diagDown-ram is a separate open problem.
            if (cnx * cnx + cnz * cnz > DRY_REANCHOR_OFFPATH_SQ) {
                BlockPos anchor = wk.path.get(wk.step - 1);
                adx = (anchor.getX() + 0.5) - p.getX();
                adz = (anchor.getZ() + 0.5) - p.getZ();
            }
        }
        double aim2 = adx * adx + adz * adz;
        boolean climbAim = aimAtWaypoint && p.isInWater() && wpAimDy > 0.5;
        boolean waterThrash = p.isInWater() && wk.stepProg.noStepProgressTicks > WATER_YAW_HOLD_STALL;
        double aimDeadzone = (climbAim || waterThrash) ? CLIMB_AIM_DEADZONE_SQ : YAW_DEADZONE_SQ;
        float targetYaw = (aim2 < aimDeadzone)
                ? p.getYRot()   // essentially on the aim column — hold heading, don't thrash atan2
                : (float) Math.toDegrees(Math.atan2(-adx, adz));
        // A launch into a leap (parkour or MLG fall) must SNAP the heading even when
        // smoothLook is on — you can't course-correct mid-air, so a lagged launch sends
        // the bot off at an angle (drifts off a narrow landing). Launches bypass both the
        // EMA and the slew cap; everything else is smoothed + capped.
        boolean launch = parkourEdge || steppingOffFall || steppingOffWaterFall;
        // Phase-2 (walkerTangentAim): replace the immediate-node bearing with the bob-immune path TANGENT
        // ahead of the projection. The node bearing reverses ~180° on a node overshoot — the backward-jump /
        // 反复横跳 / facing-the-wall dead-corner stall; the tangent never flips, so the camera (via
        // smoothTargetYaw/aimYaw below) and the captured descentNodeYaw both follow the path smoothly. Skipped
        // for launches (a leap snaps at its landing node) and inside the aim dead-zone (hold heading). The
        // projector ran this tick (call site gates on the same flag).
        // EXCEPTION — an ASCENT to the immediate node (a dry +1 stepUp/diagUp riser). A step-up mounts by
        // driving STRAIGHT at the riser node + auto/stepUpJump; the tangent points along the path PAST the
        // riser (often across a terrain corner), which steers the body off-axis so it bonks the riser edge
        // and never mounts (live P2: a +1 riser at -750 churned with perp drifting to 4+). For an above-foot
        // immediate node, keep the legacy node bearing so pivotForStepUp/stepUpJump align onto the block —
        // the same reason the buoyant water-mount (buoyantClimbPress) keeps its column bearing, not the trend.
        // ...and never while a RECOVERY branch owns the aim AND the body is PINNED — by a
        // wall (horizontalCollision) or by the stride floor-guard's void-edge sneak-pin
        // (guardSneakLatch). The tangent presumes the body is ON the lane; a pinned
        // recovery fires exactly because it is not. Before this guard the tangent
        // overwrote the reCentre bearing right after the tree chose it (bridge bypass
        // trio r26: 1200-tick barrier-face pin with as=recentre and the drive still
        // pressing the tangent into the wall); r34 repeated the identical clobber at a
        // VOID pin — as=nodeAim/-130° at the platform's east corner, drive frozen at the
        // tangent's -90 east, hc=false so the old reCentre-only gate never yielded. The
        // pin gate is load-bearing: an UNCONDITIONAL yield let transient stuck-wobbles
        // divert the drive mid-ridge and r22's suite hung on a bot that walked off the
        // ridge to bedrock. Plain stuckTicks-triggered nodeAim (no pin) keeps the
        // historical tangent override.
        boolean pinnedRecovery = (p.horizontalCollision || wk.guardSneakLatch)
                && (reCentre || "nodeAim".equals(aimSrc));
        if (BotConfig.walkerTangentAim && !launch && !pinnedRecovery && !onLastNode(wk)
                && aim2 >= aimDeadzone
                && wk.path != null && wk.step < wk.path.size()
                && wk.path.get(wk.step).getY() <= foot.getY()) {
            targetYaw = tangentOrPursuit(wk, p, launch, foot);
            // walkerWallCornerNodeAim: the tangent steers along the path TREND, but at a CORNER where the
            // immediate node sits well off the tangent AND a wall is on the tangent heading, the body RAMS
            // the wall (horizontalCollision) instead of turning the corner toward the node — it then only
            // creeps across as drift sweeps the geometry (live dry-627 start: yaw frozen 91° / node bearing
            // 122° / hCol=true / 350-tick churn, the "贴墙卡住" signature). When ramming with the node well
            // off the tangent, yield back to the DIRECT node bearing so the body turns off the wall onto the
            // node. Gated on hCol so a clean trend-cruise (no wall) keeps the bob-immune tangent unchanged.
            if (BotConfig.walkerWallCornerNodeAim && p.horizontalCollision) {
                BlockPos wn3 = wk.path.get(wk.step);
                float nodeBear = (float) Math.toDegrees(Math.atan2(
                        -((wn3.getX() + 0.5) - p.getX()), (wn3.getZ() + 0.5) - p.getZ()));
                if (Math.abs(angleDiff(wk.arc.proj.tangentYaw, nodeBear)) > WALL_CORNER_AIM_DEG)
                    targetYaw = nodeBear;
            }
        }
        // walkerRamNodeAimRelease (§80): dry wall-pin with the drive heading >60° off the
        // current-node bearing — the deadzone hold-heading band (A: aim2 inside the deadzone
        // holds a stale yaw that a stepDown node's within-gate never accepts) or a reversed
        // switchback tangent (B: tangent -180° vs node bearing 14°, corner corrector
        // default-dead per §13) steers the body INTO a wall while the node sits elsewhere.
        // Under the confirmed-stall gate, snap targetYaw back to the current-node bearing so
        // both the drive (descentNodeYaw capture below) and the camera follow. Aims at the
        // CURRENT node under a collision gate — not the §25 step-1 reanchor that bounced.
        boolean ramReleaseAim = false;
        if (BotConfig.walkerRamNodeAimRelease && !p.isInWater()
                && p.horizontalCollision
                && (wk.stuckTicks > 40
                    || (BotConfig.walkerPhysicalStallClock && wk.physStall.stallTicks > 60))
                && wk.path != null && wk.step < wk.path.size()) {
            BlockPos rn = wk.path.get(wk.step);
            double rndx = (rn.getX() + 0.5) - p.getX(), rndz = (rn.getZ() + 0.5) - p.getZ();
            if (rndx * rndx + rndz * rndz > 1e-6) {
                float rnb = (float) Math.toDegrees(Math.atan2(-rndx, rndz));
                if (Math.abs(angleDiff(p.getYRot(), rnb)) > 60) {
                    targetYaw = rnb;
                    ramReleaseAim = true;
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] ram-release: node-aim {} (was yaw={}) node={},{},{} stuckT={}",
                                String.format(Locale.ROOT, "%.0f", rnb),
                                String.format(Locale.ROOT, "%.0f", p.getYRot()),
                                rn.getX(), rn.getY(), rn.getZ(), wk.stuckTicks);
                } else if (reCentre) {
                    // CORNER-SNAG leg: the node bearing is within 60° of the pressed yaw —
                    // i.e. the node sits BEHIND the same wall face and aiming at it keeps
                    // ramming (bridge bypass trio: yaw −89 vs node bearing −72 into the
                    // barrier's west face, every release valve gated out by the small
                    // angle, 1200-tick pin). The spine-rejoin bearing (reCentre) is the
                    // one direction that clears the corner — under THIS confirmed-stall
                    // gate it is persistent, so the aim EMA actually converges on it
                    // (the tree's own intermittent reCentre ticks were smoothed away).
                    targetYaw = (float) Math.toDegrees(Math.atan2(-recX, recZ));
                    ramReleaseAim = true;
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] ram-release: corner-snag spine-aim {} (was yaw={}) stuckT={}",
                                String.format(Locale.ROOT, "%.0f", targetYaw),
                                String.format(Locale.ROOT, "%.0f", p.getYRot()), wk.stuckTicks);
                }
            }
        }
        // ── Overland camera/movement decouple (anti-spin) — see DESCENT_CAM_FAR_DIST ─────────
        // Point the CAMERA at a stable trend heading (no spin) while the MOVEMENT keeps driving the
        // immediate node (driveTargetYaw, below). Capture the immediate-node heading (the carrot/node
        // bearing) BEFORE re-aiming the camera at the trend — that captured heading drives the body.
        float descentNodeYaw = targetYaw;
        // GENERALISED from descents to FLAT + descending dry overland travel: a live 2026-06-21
        // winding-by-bucket diagnosis showed the trend camera had ALREADY smoothed descending nodes
        // (dryDesc=true: 2.5 turns over the journey) but 90% of the residual spin sat in the dry
        // NON-descending nodes (flat + ascending steps on the same hill, 7.8 turns) where the decouple
        // wasn't engaging — a switchback's flat legs swing the per-node bearing exactly like its down
        // legs. Driving the body off the captured node heading keeps navigation byte-identical; only
        // the camera is trend-averaged. ASCENDING (wp.y > foot.y) is EXCLUDED: a dig+climb-mount or
        // pillar-up needs the exact target heading, and trend-aiming it regressed tallbankdigclimb
        // (the bot couldn't mount the bank).
        // Launches ride descentDecoupleLaunches (ascending leaps slew safely via commandMove).
        boolean dryDescent = BotConfig.descentCameraDecouple && !p.isInWater() && !steppingOffWaterFall
                && ( (!launch && wp.getY() <= foot.getY())                 // flat or descending walk
                   || (launch && BotConfig.descentDecoupleLaunches) );     // any dry launch
        // Water 摇头 (head-shake) fix: extend the trend camera to FLAT water swimming. A buoyant bot
        // crossing open water swims slowly (~1.4 b/s); near a waypoint the immediate-node bearing flips
        // ±170°/tick and the camera — coupled to it in water — swings, the visible head-shake (live
        // 2026-06-21 crossing: camera mean|dyaw|=3°/tick with ±170° driveYaw flips in the slow bursts).
        // Averaging the look-ahead window into a steady trend kills it, exactly as on dry land. EXCLUDED:
        // climb-out (wp above foot — the bank mount needs the exact column heading) and swimDown dives
        // (they aim precisely), so the water-climb / dive arenas stay byte-unchanged. Unlike dry land the
        // body stays COUPLED to the camera trend (driveTargetYaw=aimYaw in water, below): on an open
        // crossing swimming toward the trend is correct, whereas driving the raw flipping node would
        // re-introduce a swim-back-and-forth.
        // The Y gate is bob-tolerant (+1): a buoyant body bobs foot y±1 against a surface node, so a
        // strict wp.y <= foot.y FLICKERS the trend on/off each bob and the camera still snaps to the
        // flipping node on the off-ticks (live: yaw==driveYaw on alternating ticks). +1 keeps the trend
        // latched across the bob while still excluding a real climb-OUT (+2 bank); a +1 node it might
        // include is harmless — buoyantClimbPress (below) drives that mount off the column regardless.
        // isInWater is LATCHED across the surface bob: at the bob apex the foot lifts out of the
        // fluid for ~1 tick (isInWater false) though the bot is still swimming the open crossing.
        // Hold "in water" for WATER_SURFACE_LATCH_TICKS after the blink so the trend (and the
        // smoothed water drive) survives it instead of snapping to aimYaw for one tick.
        if (p.isInWater()) wk.surfaceWaterLatch = WATER_SURFACE_LATCH_TICKS;
        else if (wk.surfaceWaterLatch > 0) wk.surfaceWaterLatch--;
        boolean inWaterLatched = p.isInWater() || wk.surfaceWaterLatch > 0;
        boolean flatWaterTrend = BotConfig.descentCameraDecouple && inWaterLatched && !launch
                && wp.getY() <= foot.getY() + 1
                && !(edge != null && edge.move != null && edge.move.startsWith("swimDown"));
        // WALL-PINNED RECOVERY OWNS THE AIM END-TO-END (bridge bypass trio, stage 3 of the same
        // disease). The tree's reCentre bearing (and §80's ram-release) write targetYaw ABOVE, but
        // on a dry FLAT walk trendCam is always true, and its centroid overwrite below replaced
        // targetYaw every tick — r26 pin-window: 10 consecutive as=recentre ticks (spine bearing
        // +19°) with the drive frozen at y-90, which is EXACTLY the lookahead centroid's bearing
        // (nodes (13,1),(14,0),(26,0) → centroid (18.2,0.8), atan2 = −90.7°). Under tangent mode
        // driveTargetYaw=aimYaw=EMA(targetYaw), so the centroid — not the recovery — drove the body
        // into the barrier face for 1200 ticks; every aim-layer fix upstream was label-only, the
        // same way the tangent override was before its reCentre guard. While a wall-pinned recovery
        // owns the aim, drop trendCam entirely: the centroid overwrite yields AND the EMA switches
        // from the slow descent alpha (0.08 — a 109° recovery swing would lag ~2 s) to the fast
        // cruise alpha (0.5). Scoped to hCol exactly like the tangent guard (r22: an unconditional
        // yield diverted transient wobbles and walked a ridge bot off to bedrock).
        // Dry-only: a floating bank-ram has its own recovery set (bankFollow / floatingBankBob),
        // so the water trend stays byte-identical. Two pin flavours:
        //  - WALL-pin (hCol): reCentre / §80 own the aim (bridge bypass trio, r26).
        //  - VOID-pin (guardSneakLatch, hc=false): the guard holds the body at an unplanned
        //    lip the centroid keeps steering it over (StepTwo dogleg r29: centroid (15.75,3.0)
        //    dead east, plan detours north; with allowPlace the bot causeway-plugged its own
        //    shortcut — 6 dirt). The pin-clock-enabled reCentre/nodeAim branches above give
        //    the corridor bearing; dropping trendCam here lets it reach the drive via the EMA.
        //    (Swapping the DRIVE source instead was r30/r31-DISPROVEN — see Drive.)
        boolean recoverySnagAim = !p.isInWater()
                && ((p.horizontalCollision && (reCentre || ramReleaseAim))
                    || (wk.guardSneakLatch && (reCentre || "nodeAim".equals(aimSrc))));
        boolean trendCam = (dryDescent || flatWaterTrend) && !recoverySnagAim && !offPathPursuit(wk, p, launch, foot);
        // Smoothed water DRIVE: the raw immediate-node bearing flips ±180° when the slow buoyant body
        // overshoots a node, so driving it raw makes the body swim-wobble (live: 52% path efficiency,
        // "突然转身背离目标"). A light EMA damps the per-tick flip while still tracking the node. WATER
        // ONLY: extending this EMA to dry descent was A/B-DISPROVEN in descentYawArena (raw backSteps=42
        // winding=211° → ema backSteps=54 winding=370° — the dry body has traction and needs the precise
        // node bearing; lagging it makes it overshoot/correct MORE). Dry back-hop's real fix is a
        // step-pointer advance, not drive-smoothing (override was also disproven — both wedge/worsen).
        if (flatWaterTrend) {
            // DRIVE the path-following CARROT (CARROT_DIST ahead), not the raw immediate-node bearing.
            // A slow buoyant body drifts off-axis, and the immediate node's bearing rotates faster the
            // closer it gets — within ~2 blocks it sweeps and flips ±180° as the body crosses it, so
            // the EMA still swings ±55°/tick and the swim wobbles/crawls (live: open-water hSpd
            // collapses 0.078→0.02 at every node, the "绕node打转" churn). The carrot is a STABLE far
            // heading (small angular sensitivity) that still ROUNDS corners (it walks the path) and
            // STOPS at a wall (carrotPoint's losWalkable break) — so it can't ram a divider the way
            // driving the far CENTROID did (waterFarAimBankCorner).
            // Bob-stable LOS foot: an UP-bob lifts foot.y into the air block ABOVE the water surface,
            // where carrotPoint's per-cell losWalkable (needs floor-solid / water / climbable) fails on
            // every sample and collapses the carrot onto the body — the drive then loses its forward
            // heading and the swim stalls (live -1676/-1678: carrot dead, driveYaw frozen, totStuck
            // 200+). Clamp the ray's foot DOWN to the current node's surface level so it samples water,
            // not the bob's air gap.
            BlockPos losFoot = foot.getY() > wp.getY()
                    ? new BlockPos(foot.getX(), wp.getY(), foot.getZ()) : foot;
            double[] cp = wk.carrotPoint(world, losFoot, p.getX(), p.getZ());
            double cdx = cp[0] - p.getX(), cdz = cp[1] - p.getZ();
            if (BotConfig.walkerDebug && cdx * cdx + cdz * cdz <= 1.0)
                LOG.info("[walker] carrot-collapse foot={} wp.y={} posY={} losRaw={} losClamp={}",
                        foot.getY(), wp.getY(), String.format("%.2f", p.getY()),
                        losWalkable(world, foot, wp), losWalkable(world, losFoot, wp));
            // Drive source: the carrot ahead, or — when it has collapsed onto the body (a wall / path
            // end) — the immediate node bearing as a fallback.
            float driveSrcYaw = (cdx * cdx + cdz * cdz > 1.0)
                    ? (float) Math.toDegrees(Math.atan2(-cdx, cdz)) : descentNodeYaw;
            if (Float.isNaN(wk.aimSmooth.smoothWaterDriveYaw)) {
                wk.aimSmooth.smoothWaterDriveYaw = driveSrcYaw;
                wk.aimSmooth.waterDriveRejectStreak = 0;
            } else {
                float turn = angleDiff(wk.aimSmooth.smoothWaterDriveYaw, driveSrcYaw);
                if (Math.abs(turn) <= WATER_DRIVE_MAX_TURN) {
                    // Gradual (real) turn — track it; the EMA damps the carrot's re-plan / wall jumps.
                    wk.aimSmooth.smoothWaterDriveYaw = angleDiff(0f, wk.aimSmooth.smoothWaterDriveYaw + WATER_DRIVE_ALPHA * turn);
                    wk.aimSmooth.waterDriveRejectStreak = 0;
                } else if (++wk.aimSmooth.waterDriveRejectStreak > WATER_DRIVE_MAX_REJECT) {
                    // Persistent reversal — not a transient flip. Snap so the swim can't strand itself
                    // pointing the wrong way (see WATER_DRIVE_MAX_REJECT).
                    wk.aimSmooth.smoothWaterDriveYaw = driveSrcYaw;
                    wk.aimSmooth.waterDriveRejectStreak = 0;
                }
                // else: reject this tick's flip — HOLD the forward heading.
            }
        } else {
            wk.aimSmooth.smoothWaterDriveYaw = Float.NaN;
        }
        if (trendCam) {
            // Aim the CAMERA at the CENTROID of the lookahead window (see DESCENT_CAM_LOOKAHEAD):
            // a switchback staircase's alternating cardinal legs average to the steady down-slope
            // trend, so the heading holds instead of chasing the ±50° per-step zigzag (the spin).
            // MOVEMENT stays on the immediate node via driveTargetYaw=descentNodeYaw below.
            // CENTROID of the look-ahead window: averaging EVERY node in the window cancels a
            // switchback's alternating legs into the steady down-slope trend. (A chord/far-node
            // samples only an endpoint, which itself lands on alternating legs and swings; the
            // full average does not.) MOVEMENT stays on the immediate node via driveTargetYaw below.
            double sumX = 0, sumZ = 0; int cnt = 0;
            int lastNode = Math.min(wk.step + DESCENT_CAM_LOOKAHEAD, wk.path.size() - 1);
            int firstNode = (lastNode - wk.step >= 4) ? wk.step + 2 : wk.step;   // skip the at-foot nodes
            for (int k = firstNode; k <= lastNode; k++) { sumX += wk.path.get(k).getX() + 0.5; sumZ += wk.path.get(k).getZ() + 0.5; cnt++; }
            if (cnt > 0) {
                double mdx = sumX / cnt - p.getX(), mdz = sumZ / cnt - p.getZ();
                if (mdx * mdx + mdz * mdz > 1.0)
                    targetYaw = (float) Math.toDegrees(Math.atan2(-mdx, mdz));
            }
        }
        // Low-pass the TARGET heading (EMA on the shortest angle, kept in [-180,180]) so a
        // grid staircase on a diagonal — whose immediate-waypoint bearing alternates ±~30°
        // around the true diagonal each step — averages to a steady bearing instead of a
        // bounded sawtooth (see YAW_SMOOTH_ALPHA). Resync on launch / first use — EXCEPT a
        // decoupled descending launch (dryDescent), which must NOT resync/snap: the leap is
        // drive-decoupled (driveTargetYaw=node), so the camera can keep its continuous EMA and
        // SLEW smoothly through the turn over the airborne ticks instead of snapping ~180° (the
        // 下落转圈). Snapping stays for ascending leaps / water-falls where the leap aims via yaw.
        boolean snapLaunch = launch && !dryDescent;
        if (Float.isNaN(wk.aimSmooth.smoothTargetYaw) || snapLaunch) {
            wk.aimSmooth.smoothTargetYaw = targetYaw;
            wk.aimSmooth.reversalStreak = 0;
        } else {
            float alpha = smoothingAlpha(wk, p, trendCam, targetYaw);
            // (An antipode EMA-snap here — snap after 6 consecutive >170° ticks —
            // was tried and REVERTED: it regressed wd.vineOverWaterClimb and
            // wd.bridgeStepTwoBypassNoPlace, where per-repath ±180° target flips
            // are NORMAL and must stay damped. The frozen-press deadlock is
            // released by the physical-stall valve at the spinFreeze site
            // instead, which keys on the deadlock's true signature: zero body
            // displacement while frozen.)
            wk.aimSmooth.smoothTargetYaw = angleDiff(0f, wk.aimSmooth.smoothTargetYaw + alpha * angleDiff(wk.aimSmooth.smoothTargetYaw, targetYaw));
        }
        float aimYaw = snapLaunch ? targetYaw : wk.aimSmooth.smoothTargetYaw;
        // ── 原地后跳 (in-place backward hop) fix ───────────────────────────────────────────────
        // The decoupled descent drive rides the IMMEDIATE node (descentNodeYaw). When the bot
        // OVERSHOOTS that node on a fall landing or a step (lands a hair past it), the node is now
        // BEHIND the body, so its bearing flips ~180° and the drive reverses — the body hops
        // backward INTO the node, overshoots again, and oscillates. (Live 2026-06-21 telemetry: at a
        // fall2 landing descentNodeYaw flipped 177°↔-5° while the camera held steady at -5°, so the
        // body hopped back/forth in place — the spin used to MASK this, but the now-steady trend
        // camera exposes it as a visible backward jump.) When the captured node lies sharply behind
        // the steady trend heading (aimYaw = the look-ahead centroid, which already points along the
        // path), drive ALONG the trend instead of reversing: the body keeps moving forward through
        // the overshot node, and the step pointer advances via the normal overshoot re-sync. Only
        // a >120° gap counts as an overshoot — a switchback's legs sit ~±50° off the trend, so
        // (A drive-override here — descentNodeYaw = aimYaw on a behind+close node — was tried and
        // REVERTED: overriding the heading breaks node-following and self-amplifies into a descent
        // wedge (descentYawArena onSlope 226→700, reached=false, under every gate incl. a stall gate,
        // because firing increases noStepProgressTicks which keeps it firing). The correct fix is a
        // step-pointer OVERSHOOT-ADVANCE in the re-sync block, which keeps the drive on REAL nodes.)
        // ANTI-SPIN camera freeze: while churning in water (consecutive repaths with no
        // goal-progress — a failed climb-out whose best-effort segments keep flipping
        // the waypoint behind the bot), HOLD the heading instead of chasing the
        // flipping target. A ~180° flip resolves the same rotational way each time, so
        // chasing it winds the camera one direction (raw yaw past -900 ≈ 2.5 turns in
        // the trace — the water "转圈"). Freezing stops the wind AND, since MC movement
        // follows body yaw, steadies the bot pressing one direction toward the climb-
        // out rather than U-turning. Launches still snap. Cleared as soon as progress
        // resumes (repathsNoProgress resets). */
        // Target-stability gate: the freeze must catch a FLIPPING target (chasing a ~180°
        // per-repath swing winds the camera) but NOT a STABLE one (the capped slew converges
        // to it once and stops — no wind). Freezing a stable-but-wrong heading is the
        // badlands-basin deadlock: heldYaw frozen pressing a bank for 500+ ticks while a
        // steady bearing pointed ~150° off and the foot never moved. Count ticks the smoothed
        // aim barely moved; a flip resets it, so the freeze re-arms instantly on the next
        // swing yet releases once the target has been steady ~0.5 s, letting the bot turn.
        if (!Float.isNaN(wk.aimSmooth.lastAimYaw) && Math.abs(angleDiff(aimYaw, wk.aimSmooth.lastAimYaw)) < AIM_STABLE_DEG)
            wk.aimSmooth.aimStableTicks = Math.min(wk.aimSmooth.aimStableTicks + 1, AIM_STABLE_TICKS + 1);
        else
            wk.aimSmooth.aimStableTicks = 0;
        wk.aimSmooth.lastAimYaw = aimYaw;
        // RAW-target stability (antipode-proof twin of the gate above): the EMA
        // output can be perpetually "unstable" while the RAW bearing has been
        // rock-steady for hundreds of ticks (the ±180° oscillation described at
        // the reversal fix). The freeze must release on EITHER signal being
        // stable — the raw one is what actually proves "the world wants a fixed
        // direction and it is not this one".
        if (!Float.isNaN(wk.aimSmooth.rawLastTargetYaw)
                && Math.abs(angleDiff(targetYaw, wk.aimSmooth.rawLastTargetYaw)) < AIM_STABLE_DEG)
            wk.aimSmooth.rawStableTicks = Math.min(wk.aimSmooth.rawStableTicks + 1, AIM_STABLE_TICKS + 1);
        else
            wk.aimSmooth.rawStableTicks = 0;
        wk.aimSmooth.rawLastTargetYaw = targetYaw;
        // NOTE: rawStableTicks deliberately does NOT release the freeze — an
        // A/B (t0) run with `|| rawStable` REGRESSED wd.vineOverWaterClimb
        // (pocket wedge 49t): in a climb-out pocket the freeze legitimately
        // holds a pressing heading against a steady-but-flipped node bearing.
        // The antipode EMA snap above is the sufficient release path: once the
        // smooth heading snaps to the persistent live target, the EXISTING
        // stability gate sees it steady and releases. Raw tracking stays for
        // telemetry (freeze forensics need "was the raw target stable?").
        boolean targetFlipping = wk.aimSmooth.aimStableTicks < AIM_STABLE_TICKS;
        // The anti-spin freeze stays WATER-gated: a dry-land extension (to catch the dry-churn
        // cliff-stall spin) spuriously engaged during a slow dry pillar-up — the goal-XZ barely
        // moves while pillaring, so repathsNoProgress climbs and the placement aim flips, tripping
        // the freeze and pinning the heading off the column (regressed summitarena). The dry-churn
        // spin is rarer (only on an actual nav stall) and better fixed at the stall itself than by
        // freezing the camera here, so keep the overWater scope.
        boolean overWater = p.isInWater() || world.isWater(foot.offset(0, -1, 0));
        boolean spinFreeze = !launch && overWater && wk.goalSpin.repathsNoProgress > CHURN_REPATH_CAP && targetFlipping;
        // FROZEN-PRESS DEADLOCK VALVE (Mountains notch live, stuckT 720): the freeze
        // exists to steady the bot PRESSING toward a climb-out — pressing implies the
        // body moves (or bobs while the climb machinery works, as in the vine pocket).
        // When the frozen heading points into a wall, the body is PINNED (horizontal
        // displacement ~0 for seconds), repaths keep failing, and the freeze's own
        // conditions self-sustain: raw target steady 180° away, EMA oscillating at the
        // antipode, stability gate never releasing. Key the release on the deadlock's
        // unique signature — zero displacement WHILE frozen — which no legitimate
        // freeze use shows (vine-pocket / climb-out bodies keep moving). On trip:
        // hard-snap the smooth heading to the live target and drop the freeze.
        if (spinFreeze) {
            double fdx = p.getX() - wk.aimSmooth.freezeAnchorX, fdz = p.getZ() - wk.aimSmooth.freezeAnchorZ;
            if (wk.aimSmooth.frozenStallTicks == 0 || fdx * fdx + fdz * fdz > FREEZE_PRESS_MOVE_SQ) {
                wk.aimSmooth.freezeAnchorX = p.getX();
                wk.aimSmooth.freezeAnchorZ = p.getZ();
                wk.aimSmooth.frozenStallTicks = 1;
            } else if (++wk.aimSmooth.frozenStallTicks > FREEZE_PRESS_STALL_TICKS) {
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] spin-freeze PRESS-DEADLOCK release: pinned {} ticks at ({},{}) — snap {} → {}",
                            wk.aimSmooth.frozenStallTicks, String.format("%.1f", p.getX()), String.format("%.1f", p.getZ()),
                            String.format("%.0f", wk.aimSmooth.smoothTargetYaw), String.format("%.0f", targetYaw));
                wk.aimSmooth.smoothTargetYaw = targetYaw;
                aimYaw = targetYaw;
                wk.aimSmooth.frozenStallTicks = 0;
                spinFreeze = false;
            }
        } else {
            wk.aimSmooth.frozenStallTicks = 0;
        }
        if (!spinFreeze && Math.abs(angleDiff(p.getYRot(), aimYaw)) > BotConfig.walkerYawHysteresisDeg) {
            float ny;
            if (snapLaunch) {
                ny = aimYaw;   // launches must snap — no mid-air course correction (decoupled
                LookController.requestSnap();   // descending leaps slew instead — see snapLaunch)
            } else {
                // Cap the per-tick turn (see WALKER_MAX_YAW_SLEW_DEG) so even a transient
                // bad aim vector can only nudge the heading, never reverse it in one tick.
                float want = smoothAngle(p.getYRot(), aimYaw);
                float dyaw = angleDiff(p.getYRot(), want);
                if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                // PIVOT FAST-TURN (stop-go sawtooth): a step-up pivot cuts forward
                // drive entirely until the heading is inside the 40° gate, so every
                // zigzag staircase corner stalls ~0.5 s at the 8°/tick cruise slew —
                // the speed square-wave the path charts show. While pivoting the body
                // is STATIONARY (drive already zero), so a faster pan is a quick
                // turn-in-place, not a moving-view jerk: turn at 24°/tick and the
                // stall drops to ~0.2 s. Cruise turns keep the gentle cruise slew.
                float pivotErr = angleDiff(p.getYRot(), aimYaw);
                if (wp.getY() > foot.getY() && !parkourEdge && !p.isInWater()
                        && Math.abs(pivotErr) > STEPUP_AIM_TOLERANCE_DEG) {
                    dyaw = Math.abs(pivotErr) > 24f ? Math.copySign(24f, pivotErr) : pivotErr;
                }
                ny = p.getYRot() + dyaw;
            }
            p.setYRot(ny);
            p.yHeadRot = ny;
            p.yBodyRot = ny;
        }
        // DIVE to follow a submerged node under a ceiling. In water the prone swim
        // travels along the LOOK vector, so a level pitch pins the body at the surface
        // — when the next node is BELOW and the bot is HORIZONTALLY BLOCKED (it rams a
        // low overhang lip: a submerged tunnel whose stone ceiling sits at the
        // DESTINATION cell's foot+1, so a fixed foot+2 check at the bot's own cell
        // misses it — the lethal stuck: hCol=true, hSpd=0, bobbing y62↔63 into the
        // lip), the bot must DIVE: pitch down + the prone-swim sprint (below) sink the
        // ~0.6-tall body to the tunnel floor where it fits under the lip and threads on.
        // Triggered by the real symptom — a blocked submerged descent — and LATCHED a
        // few ticks so the dive holds through the sink even as the collision flickers
        // off mid-descent (else it flip-flops upright and bobs back into the lip). An
        // OPEN descent (no collision) never triggers, so it keeps a level camera there.
        // Only a CAPPED submerged descent — a real overhang LIP, solid at the bot's
        // head+1 OR (as the comment above notes) the DESTINATION cell's foot+1 — needs
        // this dive. An OPEN pool surface where wp.y==foot.y-1 over water is just a
        // buoyant FLAT crossing (the foot rides one above the water node) with AIR
        // above: pitching down there sabotages the flat swim and sinks the bot to the
        // pool floor instead of crossing to the node. Without the cap test, a bot that
        // bumps a pocket wall while reversing toward a 1-SW surface node arms dive.latch
        // → pitch 50 → dives → bobs forever (live 2026-06-15 (2358,1863) canyon-pocket
        // north tip: N/E walls, exit SW over open water, totStuck 1400+, ~3 min hard
        // deadlock). wp.offset(0,1,0) is exactly the lip the original tunnel fix targets.
        boolean cappedDescent = world.isSolid(foot.offset(0, 2, 0)) || world.isSolid(wp.offset(0, 1, 0));
        if (p.isInWater() && wp.getY() < foot.getY() && p.horizontalCollision && cappedDescent) wk.dive.latch = 12;
        else if (wk.dive.latch > 0) wk.dive.latch--;
        boolean diveUnderCap = p.isInWater() && wp.getY() < foot.getY() && wk.dive.latch > 0;
        // ACTIVE dive for a submerged target ≥2 below a floating body (computed here,
        // before the pitch/sneak actuators that need it). Merely releasing the float
        // (the old "diving" = no jump) NEVER sinks a surface swimmer — buoyancy +
        // forward stroke hold y constant (mangrove swamp live: A* commits a riverbed
        // route y62→54 because the surface columns are walled by roots; the bot rode
        // y62 forever, offPath kept safetyRepath true every tick → 6 s burst storm).
        // A real descent needs sneak (vanilla water-sink) + a downward pitch.
        boolean diveTarget = (edge != null && edge.move != null && edge.move.startsWith("swimDown"))
                || (p.isInWater() && wp.getY() <= foot.getY() - 2 && world.isWater(wp));
        // LATCH the dive across repaths (round45 water-well live): the sink takes
        // many ticks, and a mid-sink repath/quick-start re-plans from the bot's
        // buoyancy point — its first hop is then dy=1, which alone never re-arms
        // the dy≥2 trigger above, so jump/swimUp popped the bot straight back to
        // the surface and the well column looped forever (20 anti-stuck bursts).
        // Once armed, hold the dive while the bot is still in water with the
        // waypoint below its feet; surfacing routes (wp at/above foot) clear it.
        if (diveTarget) wk.dive.hold = 30;
        else if (wk.dive.hold > 0 && p.isInWater() && wp.getY() < foot.getY()) wk.dive.hold--;
        else wk.dive.hold = 0;
        boolean diving = diveTarget || (wk.dive.hold > 0 && p.isInWater());
        // CAMERA-THRASH fix (bridge "镜头上下剧烈跳变"): while bridging, each place tick
        // does aimAtBlockSnap → requestSnap, instantly pitching the camera DOWN onto the
        // block being placed; pulling pitch back to the horizon (0) on every non-place
        // tick made the view saw violently between "look down at feet" and "look at sky".
        // Hold pitch where the place-snap left it across the whole bridge so the camera
        // sits in a steady downward gaze (one entry dip, no per-block sawtooth). Pitch
        // does not affect movement, so freezing it here is motion-neutral.
        if (!bridging && !placingEdge)
            p.setXRot(smoothAngle(p.getXRot(), (diveUnderCap || diving) ? 50f : 0f));
        // STEP-UP HEADING GATE (卡碰撞箱 fix): if a +1 step is still badly mis-aimed,
        // pivot in place instead of ramming the riser. The jump gate (ascendJumpReady
        // below) already checks POSITION alignment, but neither it nor the forward key
        // checked HEADING — so a drift-off-column / sharp-turn arrival bob-jammed the
        // step. Gate both forward (keyUp) and the step jump on this. Excludes parkour
        // and water (own handling; launches snap heading so the error is ≈0 anyway).
        float stepHeadingErr = Math.abs(angleDiff(p.getYRot(), aimYaw));
        // STEP-UP FREEZE BREAKER (cur2≈0.64 ram): a dry stepUp/diagUp that has dwelt
        // past STEPUP_FREEZE_TICKS without closing on its node, while laterally CLOSE to
        // the step column, is ramming the riser — the close-node bearing swings on every
        // sub-block bob so stepHeadingErr never clears, pivotForStepUp cuts forward
        // forever, and the jump (gated on that pivot) never clears the riser (live
        // 2026-06-15: a river-bank diagUp AND a dirt/stone-notch cardinal stepUp each
        // held cur2=0.640 constant 300+ ticks ≈ 19 s until an anti-stuck burst yanked
        // the bot BACKWARD off the step). When that happens, STOP pivoting (so forward
        // drives at the column) and force a grounded jump below — pushing up-and-over
        // mounts the step instead of bobbing against it. Lateral-close gate (column
        // dist² < 1.6) keeps it from firing on a far / mis-routed node.
        double stepColDx = (wp.getX() + 0.5) - p.getX();
        double stepColDz = (wp.getZ() + 0.5) - p.getZ();
        // Shallow-water bank variant: a bot GROUNDED on a submerged ledge (onGround
        // AND in water — never the deep-water float, which stays !onGround) bobbing
        // against a +1/+2 bank never advances the lateral step. cappedHead (the bank
        // top caps foot+2) suppresses the swim-up jump and the dry !isInWater gate
        // locks it out of this freeze-breaker, so only the anti-stuck burst (~12 s)
        // frees it (live 2026-06-15 (2366,62,1875): x frozen at the bank, y bobbing
        // 62↔64, 212 ticks onG=true inW=true). Grounded-in-water IS the shallow bank
        // case — admit it so the grounded jump + forward mounts the step, like dry.
        boolean shallowBankStep = p.onGround() && p.isInWater();
        // Bob-immune ram counter (live #47 R2: -706,85 dry stepUp + -723,63 shallow-water bank, both
        // ~16-24s creep): the noStepProgressTicks gate below is reset by the vertical bob toward an
        // above-node (line ~1506, wd2 includes wdy), so a misaligned/shallow step creeps for seconds
        // before stepUpFreeze ever fires. This counter ticks ONLY on a GROUNDED horizontal collision
        // against an above-node (the actual ram) → immune to the bob, so the freeze-breaker engages
        // on time for BOTH dry and shallow-water banks (the dryStepUp ascendJumpReady path is water-
        // excluded, and waterClimbing's pillar takeover needs !onGround so it misses the shallow bank).
        if (p.horizontalCollision && p.onGround() && wp.getY() > foot.getY()) wk.ramFold.stepRamStuckTicks++;
        else wk.ramFold.stepRamStuckTicks = 0;
        // Bob-immune ascent-ram (walkerAscentRamBobBreak): the steep-bank +1 mount that jumps off the diagonal
        // corner & slides back keeps the foot >0.3 BELOW the node while laterally close, but its airborne apex
        // zeroes BOTH stepRamStuckTicks (onGround-gated) and noStepProgressTicks (3D new-low). Tick a counter
        // that ignores onGround so stepUpFreeze engages on time; a clean climb tops out before the bar.
        // NOTE: this gate is bob-RESETTABLE by design (a big-bob apex crossing the node Y zeroes it) — that is
        // a SAFETY feature: on a genuinely-unwinnable +2 mount the bob keeps it under the bar so stepUpFreeze
        // does NOT pin the bot (a bob-IMMUNE v2 variant over-pinned ~157t on unwinnable mounts in live A/B — reverted).
        boolean ascentNotTopped = wp.getY() > foot.getY() && p.getY() < wp.getY() - 0.3
                && !parkourEdge && !p.isInWater()
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        if (BotConfig.walkerAscentRamBobBreak && ascentNotTopped) wk.ramFold.ascentRamBobTicks++;
        else wk.ramFold.ascentRamBobTicks = 0;
        // FLOATING water-bank bob (walkerFloatingBankBobFreeze): a buoyant bot at a +1..+3 water bank bobs
        // y(water)↔(air) every 2-3 t with onGround NEVER true, alternating stepUp/climbUp at the riser but
        // frozen in XZ. All other freeze counters miss it: stepRamStuck/shallowBank need onGround (floating
        // has none), ascentRamBob's !isInWater zeroes on each water-dip, and noStepProgress is zeroed by the
        // 3D bob. Use a STABLE water-bank indicator (water at the foot or just below — true through the WHOLE
        // bob so the down-phase does NOT reset the counter) + !onGround + a lateral riser-ram + node-above
        // (cap +3). Restricted to a WATER bank (isWater) so a DRY unwinnable +2 mount is never pinned (the
        // ascentRam-v2 over-pin regression); an unwinnable water bank simply repaths. Fed into stepUpFreeze
        // past a 2× bar below.
        boolean atWaterBank = world.isWater(foot) || world.isWater(foot.below());
        boolean floatingBankNotTopped = atWaterBank && !p.onGround()
                && wp.getY() > foot.getY() && (wp.getY() - foot.getY()) <= 3
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        if (BotConfig.walkerFloatingBankBobFreeze && floatingBankNotTopped) wk.ramFold.floatingBankBobTicks++;
        else wk.ramFold.floatingBankBobTicks = 0;
        // Lateral-bank-follow (walkerFloatingBankFollow): a FLOATING bot ramming a water bank at
        // ANY node-Y — including the walk-ram facet the ascending freeze counter above misses (node
        // AT/BELOW the foot across a 1-block lip). The dominant residual is NON-DETERMINISTIC: the
        // same route lands the buoyant approach on a mountable spot (~0s, steps up) OR a dig-required
        // spot (~30s underwater dig, replay-proven 0s/0s/33s). Count sustained floating-water-rams to
        // drive a SLIDE ALONG the bank (perpendicular strafe, see the lane-keep block) so the body
        // sweeps to the nearest mountable exit instead of grinding/digging the dead spot. Self-
        // terminating: any climb-out progress drops onGround/hCol → counter resets → normal mount.
        boolean bankFollowRam = atWaterBank && !p.onGround() && p.horizontalCollision;
        if (BotConfig.walkerFloatingBankFollow && bankFollowRam) wk.ramFold.bankFollowRamTicks++;
        else wk.ramFold.bankFollowRamTicks = 0;
        boolean stepUpFreeze = wp.getY() > foot.getY() && !parkourEdge
                && (!p.isInWater() || shallowBankStep
                    || (BotConfig.walkerFloatingBankBobFreeze && wk.ramFold.floatingBankBobTicks > 2 * STEPUP_FREEZE_TICKS))
                && (wk.stepProg.noStepProgressTicks > STEPUP_FREEZE_TICKS || wk.ramFold.stepRamStuckTicks > STEPUP_FREEZE_TICKS
                    || wk.ramFold.ascentRamBobTicks > STEPUP_FREEZE_TICKS
                    || wk.ramFold.floatingBankBobTicks > 2 * STEPUP_FREEZE_TICKS)
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        boolean pivotForStepUp = wp.getY() > foot.getY() && !parkourEdge && !p.isInWater()
                && stepHeadingErr > STEPUP_AIM_TOLERANCE_DEG && !stepUpFreeze;
        // Parkour DESCEND landing brake: a leap onto a LOWER 1-wide block
        // touches down with more horizontal momentum than a flat leap (extra
        // airtime accelerating forward), so a sprint launch slides the bot off
        // the far edge into the void (observed: a parkourDescend2d1 landed but
        // overshot the 1-wide pad and fell). Once we're airborne AND already
        // horizontally over (or nearly over) the landing column — i.e. the gap
        // is cleared — cut forward+sprint and hold sneak so the bot coasts to a
        // stop ON the block instead of past it (same trick as the parkour-place
        // SETTLE phase). We brake only when close to the landing center, so
        // cutting thrust can never drop the leap short into the gap.
        boolean descendLeap = edge != null && edge.move != null && edge.move.startsWith("parkourDescend");
        double landDx = (wp.getX() + 0.5) - p.getX();
        double landDz = (wp.getZ() + 0.5) - p.getZ();
        boolean descendBrake = descendLeap && !p.onGround()
                && (landDx * landDx + landDz * landDz) < 1.4;   // within ~1.2 block of landing center
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        cx.aim.aimAtWaypoint = aimAtWaypoint;
        cx.aim.reCentre = reCentre;
        cx.aim.aimSrc = aimSrc;
        wk.aimTag = aimSrc;
        cx.aim.descentNodeYaw = descentNodeYaw;
        cx.aim.dryDescent = dryDescent;
        cx.aim.flatWaterTrend = flatWaterTrend;
        cx.aim.trendCam = trendCam;
        cx.aim.aimYaw = aimYaw;
        cx.aim.diveUnderCap = diveUnderCap;
        cx.aim.diving = diving;
        cx.aim.stepColDx = stepColDx;
        cx.aim.stepColDz = stepColDz;
        cx.aim.stepUpFreeze = stepUpFreeze;
        cx.aim.pivotForStepUp = pivotForStepUp;
        cx.aim.descendBrake = descendBrake;
        return null;
    }
}
