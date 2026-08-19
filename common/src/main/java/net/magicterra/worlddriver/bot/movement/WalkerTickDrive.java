package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.debug.BotLevelHolder;
import net.magicterra.worlddriver.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
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

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.movement.PathSmoothing.*;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 4182–5290): final key assembly: step-up / freezes / brakes / swim gates -> forward, sprint, jump, sneak, strafe.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 *
 * <h2>Why the lethal-edge sprint exemption is {@code parkourEdge}, not {@code parkourAscend}</h2>
 *
 * Two gates in {@link #run} carry a {@code parkourEdge} term whose reason is one measurement, so it
 * is written here once rather than twice inline (and here rather than inline at all, because
 * {@code run} is on the source-budget grandfather list and may only shrink):
 *
 * <ul>
 *   <li>the sprint expression's {@code (!lethalNear || parkourEdge)} term, and</li>
 *   <li>the dynamic brake block's {@code && !parkourEdge} exclusion.</li>
 * </ul>
 *
 * <p>{@code lethalNear} asks {@code lethalDropAdjacent(world, p, foot)} — does ANY of the FOOT
 * cell's eight horizontal neighbours drop further than {@code survivableFall}. On the rim of a pad
 * over void that is true on the lip cell and false one cell back, so the sprint channel closes or
 * opens purely on <b>which cell the body happens to launch from</b>. Two scenes with identical
 * geometry and different run-up lengths measured exactly that split:
 *
 * <pre>
 * wd.parkourVoidShortRunway  takeoff.x=223905.02  (lip=223904 ⇒ launched from BEHIND the lip)
 *     t+0 sprinting=true h=0.1232 → t+1 sprinting=true  h=0.2475    (+0.124: the impulse fired)
 * wd.parkourVoidLongRunway   takeoff.x=224416.93  (lip=224416 ⇒ launched ON the lip)
 *     t+0 sprinting=true h=0.1563 → t+1 sprinting=false h=0.1400    (x0.896: plain air decay, no impulse)
 * </pre>
 *
 * The only difference between the arms is {@code sprinting} on the takeoff tick, and the long arm
 * fell into the gap. A fuller run-up makes the body MORE likely to stand on the lip, so under the
 * old gate a run-up was actively harmful — the exemption has to key on the leap, not on the cell.
 *
 * <p>It keys on {@code parkourEdge} and not on {@code parkourAscend} ({@code = parkourEdge &&
 * wp.y > foot.y}) because a FLAT leap is identically {@code false} there: the old term said "a jump
 * onto a shallow ledge needs the impulse, a jump over an abyss does not", and it is only over an
 * abyss that {@code lethalNear} is true at all. Narrowed to {@code parkourEdge} rather than
 * loosened to a blanket {@code !lethalNear} drop, because the non-parkour half of {@code lethalNear}
 * is what {@code wd.bridgeLethalGapStop} guards — a walk-off lip must still lose its sprint.
 *
 * <p>The brake exclusion is the same launch tick seen from the other side: {@code edgeBrake} on the
 * lip makes {@code bridgeBrake} hold sneak through the takeoff, and
 * {@code ServerPlayerAvatar} (the {@code pendingSneak ? 0.3f : 1f} steering multiplier) then serves
 * the leap 30% of its control input. Sprint alone does not clear the gap while sneak is throttling
 * it. Sibling {@code parkourEdge} exclusions already exist in this file on the lane-keep strafe and
 * on {@code descentAirborneDriftClamp}; the brake block was the one that was missing.
 */
final class WalkerTickDrive {
    private WalkerTickDrive() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Avatar a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        Player p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        boolean breakingEdge = cx.stall.breakingEdge;
        Move.Edge edge = cx.edges.edge;
        BlockPos wp = cx.edges.wp;
        boolean parkourEdge = cx.edges.parkourEdge;
        boolean bridging = cx.edges.bridging;
        boolean placingEdge = cx.edges.placingEdge;
        boolean steppingOffFall = cx.edges.steppingOffFall;
        boolean steppingOffWaterFall = cx.edges.steppingOffWaterFall;
        boolean aimAtWaypoint = cx.aim.aimAtWaypoint;
        boolean reCentre = cx.aim.reCentre;
        String aimSrc = cx.aim.aimSrc;
        float descentNodeYaw = cx.aim.descentNodeYaw;
        boolean dryDescent = cx.aim.dryDescent;
        boolean flatWaterTrend = cx.aim.flatWaterTrend;
        boolean trendCam = cx.aim.trendCam;
        float aimYaw = cx.aim.aimYaw;
        boolean diveUnderCap = cx.aim.diveUnderCap;
        boolean diving = cx.aim.diving;
        double stepColDx = cx.aim.stepColDx;
        double stepColDz = cx.aim.stepColDz;
        boolean stepUpFreeze = cx.aim.stepUpFreeze;
        boolean pivotForStepUp = cx.aim.pivotForStepUp;
        boolean descendBrake = cx.aim.descendBrake;
        // ---- original body (byte-identical modulo member prefixes) ----
        // Lateral lane-keeping STRAFE: on a flat cardinal walk, hold the cross-axis
        // at the lane centre with a sideways strafe so the body clears a flush 1-wide
        // channel wall WITHOUT turning off the forward heading. Pure yaw steering
        // can't do both (turning to centre kills forward progress, so the bot only
        // creeps and grinds the wall); strafing centres while forward still drives
        // it down the lane. Projects the cross-axis error onto the player's right
        // vector to pick the key. Skipped during leaps/brakes/bridging.
        boolean strafeL = false, strafeR = false;
        // Baritone MovementAscend jump-timing for a DRY cardinal +1 step. Baritone
        // does NOT sprint an ascend and does NOT hold jump continuously — it walks
        // toward the step squaring up, then presses jump ONLY when CLOSE
        // (flatDist≤1.2 along the move axis), ALIGNED (sideDist≤0.2 on the cross
        // axis), and not drifting sideways (|lateralMotion|≤0.1). Our old "sprint +
        // hold jump for any wp.y>foot.y" bonked the step face off-axis and slid back
        // down → the 633-tick steep-mountain wedge. Mirror Baritone: centre on the
        // cross axis (strafe gate below now includes dryStepUp), drop sprint, and
        // gate the jump on alignment+proximity. Scoped to a single cardinal +1 step
        // on dry land — diagUp / +2 / water / parkour keep their own handling.
        // Attribute-driven step/jump (read the controlled entity — the vehicle when
        // mounted). On foot: step 0 (a +1 needs a jump), jump reaches +1. A horse:
        // step 1.0 (walks a full block up, no jump), jump up to +2.
        int upDy = wp.getY() - foot.getY();
        int maxStepUp = world.maxStepUpBlocks();
        int maxJumpUp = world.maxJumpUpBlocks();
        boolean cardinalUp = upDy >= 1 && ((wp.getX() == foot.getX()) ^ (wp.getZ() == foot.getZ()));
        // A DIAGONAL +1 step (BOTH axes change AND rising). The lane-keep strafe below
        // only centres cardinal lanes / waterClimb, so a diagUp got NO lateral
        // correction: the bot drifts off the diagonal, ends aligned on one axis but
        // ~0.8 off on the other, rams the perpendicular riser — and because the dry-land
        // aim points at the CLOSE waypoint (whose bearing swings on any sub-block drift)
        // stepHeadingErr never clears, so pivotForStepUp cuts forward FOREVER and the bot
        // FREEZES at the riser (live 2026-06-15: cur2=0.640 constant 300+ ticks ≈ 19 s,
        // hCol=true, until an anti-stuck burst routed around). Centre it on the target
        // column on BOTH axes (the strafe still runs while pivot cuts forward), so its
        // cross-axis component pulls the body back onto the diagonal line, the bearing
        // steadies, pivotForStepUp releases, and forward + stepUpJump mounts the corner.
        boolean diagUp = upDy >= 1 && wp.getX() != foot.getX() && wp.getZ() != foot.getZ()
                && !parkourEdge && !p.isInWater();
        // The DESCENDING mirror of diagUp: a diagonal step DOWN (both axes change AND falling).
        // The lane-keep strafe below centres waterClimb/diagUp/cardinal lanes but NOT diagDown,
        // so a diagDown got NO lateral correction — the bot drifts off the diagonal descend line,
        // ends ~0.8 off on one axis, and RAMS the perpendicular corner block (hCol=true, |yawErr|
        // large because descentNodeYaw points at the close node whose bearing swings on drift):
        // the close diagDown-ram wedge (live -671, REGRESSION.md §26 — anchor-back re-aim made it
        // OSCILLATE worse, totStuck 6009). A lateral centre (NOT an aim change) pulls the body back
        // onto the diagonal line so the corner clears and forward drives through. Mirror of 4262.
        boolean diagDown = upDy <= -1 && wp.getX() != foot.getX() && wp.getZ() != foot.getZ()
                && !parkourEdge && !p.isInWater();
        // A jump is needed only to rise BEYOND the auto-step height.
        boolean needJumpForStep = upDy >= 1 && upDy > maxStepUp;
        // A step taller than we could clear even WITH a jump — we slid back below a
        // +1 start so it now reads +2, or terrain demands a height we can't make.
        // Don't bob against it: accelerate the stuck timer so the search blacklists
        // the node and reroutes (the steep-climb wedge), instead of jumping forever.
        boolean overJump = needJumpForStep && upDy > maxJumpUp && p.onGround() && !p.isInWater()
                && edge != null && !"pillarUp".equals(edge.move) && !parkourEdge;
        if (overJump) wk.stuckTicks += 3;
        // Vertical recovery (execution hardening): the bot is BELOW the next node by more
        // than it can jump — it slid/fell below the committed climb path, and a plain stepUp
        // here just RAMS the wall (jump is suppressed for an unreachable height) → the
        // "被面前的高方块挡住不动" stall. If we carry blocks, PILLAR UP in place to regain
        // the height instead of ramming: look down, jump off the ground, and place a support
        // in the feet cell once risen clear of it — the same actuation as a planned pillarUp.
        // Latched across the jump's airborne phase (overJump needs onGround, so one tick
        // can't both jump and place) and re-armed each grounded tick while still too low;
        // ends when back within jump reach (overJump clears) or blocks run out, after which
        // the normal step logic resumes. Reuses ensureHolding + clientUseItemOn.
        // holdPillarBlock (not world.canPlace): pillar-recover places on the solid rung below the
        // grounded foot, so supported sand/gravel work here (see fellBelowRoute note above).
        // SLOW DIAGUP → PILLAR-UP (steep-diagonal stutter): a planned +1 diagUp that bob-rams the
        // riser past DIAGUP_PILLAR_TICKS (the √2 jump-traverse ~47 ticks/step sawtooth) escalates to
        // a deterministic pillar — place a support under the foot, jump +1, walk the now-level node —
        // via the SAME actuator as overJump/fellBelowRoute (no new placement code). Scoped hard: a
        // true +1 diagUp by planner intent AND geometry, grounded, dry, blocks in hand, slower than
        // the freeze-breaker's first attempt; dy==1 keeps it disjoint from the ≥2 ascentRamSlide.
        boolean slowDiagUpPillar = "diagUp".equals(edge != null ? edge.move : null)
                && diagUp && upDy == 1 && p.onGround() && !p.isInWater()
                && wk.stepProg.noStepProgressTicks > DIAGUP_PILLAR_TICKS;
        if ((overJump || slowDiagUpPillar) && BotConfig.allowPlace && a.holdPillarBlock()) {
            wk.pillarRecover.latch = PILLAR_RECOVER_TICKS;
            wk.pillarRecover.cell = foot;                 // grounded feet cell = the rung we fill
        }
        if (wk.pillarRecover.latch > 0 && wk.pillarRecover.cell != null) {
            wk.pillarRecover.latch--;
            Walker.avatarForward(a, false);
            p.setSprinting(false);
            Walker.avatarSneak(a, false);
            p.setShiftKeyDown(false);
            // Clear the ceiling FIRST: a block where the head rises (tree-canopy leaves at
            // rung+2, a dirt overhang) makes the recovery jump RAM it — the bot can't gain
            // height, can't place, and wedges ("树下 pillar-up 撞树叶卡死", bug#1). The planned
            // pillarUp actuator clears its toBreak the same way; this recovery path had none.
            // Only with allowBreak, and only the single head cell, so it digs no more than the
            // one block needed to rise this rung (re-checked each rung as pillarRecover.cell rises).
            // DELIBERATELY on the global allowBreak switch, NOT mayBreak(): this is the
            // anti-suffocation safety dig, and it is EXEMPT from per-goto forbidDig — suffocation is
            // death, forbidDig is only a navigation preference, so safety wins over the constraint.
            BlockPos recCeiling = wk.pillarRecover.cell.offset(0, 2, 0);
            if (BotConfig.allowBreak && world.isSolid(recCeiling)) {
                wk.avatarJump(a, false);
                a.selectTool(recCeiling);
                a.aimAtBlock(recCeiling);
                Walker.avatarDig(a, recCeiling);
                return Walker.Step.WALKING;
            }
            a.breakHold(false);
            p.setXRot(89.5f);                         // look straight down to aim the support
            if (p.onGround()) {
                wk.jumpTag = "pillarRecoverRung";
                wk.avatarJump(a, true);     // jump off the current rung
            } else {
                wk.avatarJump(a, false);
                // Place into the feet cell once risen clear of it (vanilla rejects the place
                // while the player AABB still overlaps the target cell — gate on real height).
                if (p.getY() >= wk.pillarRecover.cell.getY() + 1.0) {
                    a.placeOn(wk.pillarRecover.cell.offset(0, -1, 0), Direction.UP);
                    wk.exAlarms.notePlace(wk.pillarRecover.cell);
                }
            }
            return Walker.Step.WALKING;
        }
        // task#82 per-move ASCENT machine (plan B1 2026-07-16). Flag FIRST so OFF short-circuits with
        // no allocation. isMigratedAscent is a cheap string check; !isInWater keeps water ascents on
        // legacy dig-recovery. B1 weave: the machine NEVER early-returns — PREP/RUNNING/SUCCESS fall
        // through so the legacy jump-timing + drive below stays the single actuation source (an early
        // return here skips the shared drive and forces a re-implementation — the disproven Option-A
        // trap). The machine owns only the per-edge episode + dead-zone watchdog; step advancement
        // stays with the legacy advance loop (~line 2265) so there is no double-advance race.
        if (BotConfig.walkerAscendMovement && edge != null && Walker.isMigratedAscent(edge.move) && !p.isInWater()) {
            MovementContext ctx = new MovementContext(
                    p, world, a, edge, foot, wk.path.get(wk.step),
                    world.maxJumpUpBlocks(), world.maxStepUpBlocks(),
                    wk.step >= 1 ? wk.path.get(wk.step - 1) : null,
                    wk.step >= 2 ? wk.path.get(wk.step - 2) : null, breakingEdge);
            switch (wk.ascendMovement.updateState(ctx)) {
                case UNREACHABLE, FAILED -> {                          // fold into the existing re-route (consumed next tick at line 1042)
                    Walker.Step give = noteDeadZone(wk, p, foot, edge);
                    if (give != null) return give;
                }
                case PREP, RUNNING, SUCCESS -> { }                     // fall through — legacy drive actuates this tick
            }
        }
        // Baritone MovementAscend jump-timing applies whenever we actually JUMP a
        // cardinal step within reach (+1, or +2 for a horse/jump-boost) — align +
        // approach before the jump. A horse auto-walk-up needs no jump; water/parkour
        // differ; an over-jump (handled above) is excluded.
        boolean dryStepUp = needJumpForStep && cardinalUp && upDy <= maxJumpUp
                && !p.isInWater() && !parkourEdge;
        boolean ascendJumpReady = true;
        boolean sprintAscend = false;
        if (dryStepUp) {
            int xA = wp.getX() != foot.getX() ? 1 : 0;
            int zA = wp.getZ() != foot.getZ() ? 1 : 0;
            double flatDist = xA * Math.abs((wp.getX() + 0.5) - p.getX()) + zA * Math.abs((wp.getZ() + 0.5) - p.getZ());
            double sideDist = zA * Math.abs((wp.getX() + 0.5) - p.getX()) + xA * Math.abs((wp.getZ() + 0.5) - p.getZ());
            Vec3 vel = p.getDeltaMovement();
            double lateralMotion = xA * vel.z + zA * vel.x;
            // SPRINT-BUNNY-HOP a cardinal +1 step (丝滑 stair climb). The deterministic
            // ascentSpeedArena shows a gentle staircase costs ~40% speed: each step drops
            // sprint + the in-place jump rams the riser + the body re-accelerates from ~0.
            // Two levers were each A/B-disproven IN ISOLATION (2026-06-06): sprint + a LATE
            // jump (flatDist≤1.2) rams the riser HARDER; an early jump (≤1.7) WITHOUT sprint
            // lands short and re-approaches. TOGETHER they compose — the sprint forward-boost
            // carries the EARLY launch up and OVER the riser onto the step with momentum
            // intact, exactly how a vanilla player sprint-jumps stairs. Gate HARD on cross-
            // axis alignment (square-up sideDist≤0.2, not drifting |lateralMotion|≤0.1) so the
            // boosted launch flies straight at the step instead of bonking a corner; when
            // aligned, jump EARLY (flatDist≤1.7) AND keep sprint (sprintAscend → sprint gate
            // below). Misaligned → don't jump yet (the pivot/strafe centres first), matching
            // the old square-up-before-jump discipline. The arena A/B (not noisy live terrain,
            // which the prior disproofs used) is the gate for this.
            // §92 chain-mount (#15 steep-climb lane): on CONSECUTIVE same-direction +1
            // steps the strict square-up gate is the slide-back window itself — the bot
            // lands each mount with residual lateral motion, fails `aligned`, and stalls
            // to re-centre on a slope that sheds it backward (slow-map: y sawtooth
            // 53→54→56→54, 220s/300s burned in three climb zones). A human sprint-jumps
            // the whole staircase riding the landing momentum. When the JUST-MOUNTED edge
            // and the NEXT edge are both +1 in the same horizontal direction, loosen the
            // gate (sideDist 0.45, lateral 0.25, launch 2.0) so the chain keeps rolling;
            // direction changes keep the strict gate (a loose corner launch bonks).
            boolean chainAscend = false;
            if (BotConfig.walkerChainMount && wk.step >= 2) {
                BlockPos cmP = wk.path.get(wk.step - 1), cmP2 = wk.path.get(wk.step - 2);
                chainAscend = cmP.getY() - cmP2.getY() == 1
                        && wp.getY() - cmP.getY() == 1
                        && Integer.signum(wp.getX() - cmP.getX()) == Integer.signum(cmP.getX() - cmP2.getX())
                        && Integer.signum(wp.getZ() - cmP.getZ()) == Integer.signum(cmP.getZ() - cmP2.getZ());
            }
            boolean aligned = chainAscend
                    ? (Math.abs(lateralMotion) <= 0.25 && sideDist <= 0.45)
                    : (Math.abs(lateralMotion) <= 0.1 && sideDist <= 0.2);
            sprintAscend = aligned;
            ascendJumpReady = aligned && flatDist <= (chainAscend ? 2.0 : 1.7);
            // STEPUP BACKOFF-RETRY trigger (walkerStepUpBackoffRetry, default OFF): the early
            // jump above assumes sprint momentum from the approach, but a slid-back mount
            // retries from a STANDING start pressed against the riser — a near-vertical hop
            // that grazes the lip and slides back forever (live node(14,58,102): ~1400 grind
            // ticks, hSpd 0.05, hCol=false). Grounded + stuck + pressed-close + momentum-less
            // → arm an 8-tick straight-back drive (executed early in tick()) to open runway,
            // then the normal approach re-launches WITH momentum. Cooldown guards a genuinely
            // unmountable riser from oscillating retreat (other recoveries take over).
            // Two grind signatures qualify: (a) GROUNDED standing-start press (onGround +
            // momentum-less), (b) the airborne RIM-GRAZE bob — the bot hangs against the
            // riser face (hCol=true every tick, onGround NEVER true, y bobbing ~0.5 wide;
            // replay-0016: p=(14.50,57.0-57.25,103.30) for 500+ ticks). Both need the same
            // cure: back off the face, ground, and re-approach with momentum.
            boolean grindPress = p.onGround() && Math.sqrt(vel.x * vel.x + vel.z * vel.z) < 0.1;
            boolean grindGraze = p.horizontalCollision && wk.stuckTicks > 30;
            if (BotConfig.walkerStepUpBackoffRetry && wk.stepUpBackoff.cooldown == 0
                    && wk.stuckTicks > 15 && flatDist < 1.1 && (grindPress || grindGraze)) {
                wk.stepUpBackoff.yaw = (float) (Math.toDegrees(Math.atan2(
                        -((wp.getX() + 0.5) - p.getX()), (wp.getZ() + 0.5) - p.getZ())) + 180.0);
                wk.stepUpBackoff.ticks = 12;
                wk.stepUpBackoff.cooldown = 60;
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] STEPUP-BACKOFF armed ({}): wp={},{},{} flatDist={} → back off 12t for runway",
                            grindPress ? "press" : "graze", wp.getX(), wp.getY(), wp.getZ(),
                            String.format(Locale.ROOT, "%.2f", flatDist));
            }
        }
        // NOTE: extending the cardinal sprint-bunny-hop to DIAGONAL step-ups was tried + A/B-
        // DISPROVEN here (2026-06-20) via diagonalAscentSpeedArena: forcing sprint on a diagonal
        // climb RAMS the riser CORNER (two perpendicular faces) — sprint% 48→94 dropped diagBps
        // 3.04→2.71 and raised hcol 7→12; jumping √2-earlier was worse still (2.95, hcol 22, and
        // it broke tallBankDigClimb's diagonal dig-staircase). The partial-sprint 3.04 b/s (≈71%
        // of walk) baseline is the achievable limit under the corner-ram constraint; the camera
        // (yaw) is already steadied by the STAIR_TREND_LOOKAHEAD path-trend aim. Left cardinalUp-
        // only on purpose. The diagonalAscentSpeedArena guards that 3.04 baseline from regressing.
        // Climbing a +1 ledge OUT OF a water film: buoyancy drifts the body off the
        // target column so the cardinal stepUp approach goes diagonal and forward
        // just grinds the ledge side (trace: inW, foot drifted to a diagonal of the
        // stepUp node, cur2 stuck at 1.27, bobbed 797 ticks → budget expiry, "never
        // left spawn"). The lane-keep strafe below is gated to flat walks
        // (wp.y==foot.y), so a vertical climb gets NO lateral correction. Treat a
        // water climb-out like a lane-keep but centre on BOTH axes toward the target
        // column so the body sits under the ledge; the existing forward+jump then
        // mounts it (the aligned cardinal climb that already works on dry land).
        boolean waterClimb = p.isInWater() && wp.getY() > foot.getY();
        // Lateral-bank-follow active once the floating-water-ram has been sustained past the freeze
        // bar (2× STEPUP_FREEZE_TICKS, same threshold as the ascending freeze). Drives a perpendicular
        // slide along the bank regardless of node-Y (catches the walk-ram facet too).
        // YIELD to apw: a DRY diagUp limit cycle (live replay-0006 -818) is a large vertical oscillation that dips into
        // a water cell at its bottom, transiently tripping atWaterBank → bankFollow HIJACKS the apw/FBA recovery and
        // drags the bot down a non-existent "bank exit" (FBA+apw 1530 → +FloatingBankFollow 2198). When apw is the
        // active stall owner (its arcProgStall has fired), defer to it — apw's repath resolves the cycle, and a GENUINE
        // water bank with apw OFF is unaffected (the && walkerArcProgressWedge guard).
        boolean bankFollow = BotConfig.walkerFloatingBankFollow && wk.ramFold.bankFollowRamTicks > 2 * STEPUP_FREEZE_TICKS
                && !(BotConfig.walkerArcProgressWedge && wk.arc.progStall);
        if (!descendBrake && !parkourEdge && !steppingOffFall && (wp.getY() == foot.getY() || waterClimb || cardinalUp || diagUp || bankFollow)) {
            int ddx = wp.getX() - foot.getX();
            int ddz = wp.getZ() - foot.getZ();
            double latX = 0, latZ = 0;
            if (bankFollow) {
                // Geometry-aware CONVERGENT slide (v2): the chaotic alternating sweep (v1) sometimes slid
                // PAST the goal and churned (live A/B: ON#2 ran to -654, worse than OFF). Instead, SCAN
                // along the bank for the nearest cell the bot can actually mount — a canStandAt lip at
                // foot.y (flat exit) or foot.y+1 (a +1 step) — and steer deterministically toward it. The
                // bank NORMAL ≈ the dominant cardinal toward the node (the face being rammed); the bank
                // runs perpendicular, so probe ±BANK_FOLLOW_SCAN cells along that perpendicular. Nearest
                // mountable lip wins; forward drive (untouched) mounts it the moment the body lines up.
                // No mountable lip in range → leave lat 0 so the existing bank-dig/repath recovery runs.
                int ndx = wp.getX() - foot.getX(), ndz = wp.getZ() - foot.getZ();
                if (Math.abs(ndx) >= Math.abs(ndz)) { ndx = Integer.signum(ndx); ndz = 0; }
                else { ndz = Integer.signum(ndz); ndx = 0; }
                int pdx = -ndz, pdz = ndx;                 // along-bank perpendicular unit
                int bestK = 0;
                for (int k = 1; k <= BANK_FOLLOW_SCAN && bestK == 0; k++) {
                    for (int s = -1; s <= 1 && bestK == 0; s += 2) {
                        int kk = k * s;
                        BlockPos flat = foot.offset(ndx + pdx * kk, 0, ndz + pdz * kk);  // same-level exit
                        BlockPos lip = foot.offset(ndx + pdx * kk, 1, ndz + pdz * kk);   // +1 step lip
                        if (world.canStandAt(flat) || world.canStandAt(lip)) bestK = kk;
                    }
                }
                if (bestK != 0) {
                    int dir = Integer.signum(bestK);
                    latX = pdx * dir; latZ = pdz * dir;    // steer along the bank toward the mountable lip
                }
                if (BotConfig.walkerDebug && wk.ramFold.bankFollowRamTicks % 20 == 0)
                    LOG.info("[walker] bank-follow scan foot={} normal=({},{}) bestK={} ramT={} → {}",
                            foot, ndx, ndz, bestK, wk.ramFold.bankFollowRamTicks,
                            bestK != 0 ? "STEER to lip" : "NO LIP in range (dig/repath)");
            }
            else if (waterClimb) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // centre on the target column
            else if (diagUp) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // centre on the diagonal toward the step corner
            else if (BotConfig.walkerDiagDownCenter && diagDown) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // §26: mirror diagUp — centre on the diagonal descend line so the body doesn't ram the perpendicular corner
            else if (ddx == 0 && ddz != 0) latX = (wp.getX() + 0.5) - p.getX();        // N/S lane → hold X
            else if (ddz == 0 && ddx != 0) latZ = (wp.getZ() + 0.5) - p.getZ();   // E/W lane → hold Z
            // Anti-drift in a current: a flowing-water cell pushes the body
            // downstream, so steer upstream (subtract the flow vector from the
            // lateral target). The strafe is far stronger than the push, so this
            // holds the crossing on the planned line PROACTIVELY instead of letting
            // the lane-keep only react after the bot has already drifted. Only the
            // cross-lane component survives the right-vector projection below; the
            // along-lane part is ignored (handled by the upstream COST, not steering).
            if (BotConfig.waterFlowPenalty > 0 && p.isInWater()) {
                Vec3 flow = world.waterFlow(foot);
                latX -= flow.x; latZ -= flow.z;
            }
            if (Math.abs(latX) > 0.06 || Math.abs(latZ) > 0.06) {
                double yr = Math.toRadians(p.getYRot());
                double fx = -Math.sin(yr), fz = Math.cos(yr);   // forward unit (x,z)
                double rx = -fz, ry = fx;                       // player's right = forward rot +90°
                double dotR = latX * rx + latZ * ry;
                if (dotR > 0.04) strafeR = true;
                else if (dotR < -0.04) strafeL = true;
            }
        }
        // Camera-decoupled drive (see AvatarInput). Rotate the body-frame movement intent
        // (forward + lane-keep strafe) from the desired travel heading (aimYaw) into the
        // camera frame by Δ = aimYaw − cameraYaw, so vanilla travel()'s rotate-by-yaw moves
        // the body ALONG the heading even while the camera is still slewing toward it — the
        // body no longer rams a wall waiting for the look to catch up (动态纠偏). At Δ=0
        // (camera caught up) the impulse equals the old keyed (dL,dF), so steady-state walking
        // is byte-identical; only the slew transient changes. Special branches above return
        // before here, so they keep their own key-based actuation (AvatarInput falls back to
        // keys uncommanded).
        //
        // The DRIVE always targets aimYaw — even while the camera is frozen by the anti-wind
        // spinFreeze. The freeze exists ONLY to stop the visual 转圈 (camera chasing a ~180°-
        // flipping target winds one way); it must NOT also freeze the body's travel. The old
        // `spinFreeze ? 0` here drove the body along the STALE frozen camera heading — in a
        // badlands water basin that heading pointed straight at the bank, so the body rammed it
        // for 500+ ticks while a steady bearing pointed ~150° off (the 18-min near-deadlock).
        // Decoupling them lets the body crab toward the waypoint (Δ = aimYaw − cameraYaw) and
        // make net progress, which resets repathsNoProgress and releases the freeze on its own,
        // so the vicious cycle (frozen drive → no progress → freeze stays frozen) can't form.
        // AIRBORNE forward-drift clamp (task#36, 2026-07-12, repurposed walkerDescentStepSkipBrake,
        // isolable backup toggle to the sprint-kill above). When the steep-descent latch is armed
        // (set on the grounded tick before the launch, ~line 4443) and the body is now airborne over
        // the descent, zero the forward drive so it drops onto the near tread instead of sailing off
        // the lip on driveF=1. Reads the latch FIELD (its previous-tick value — correct, the latch's
        // whole purpose is to persist across the airborne arc). Airborne-only: a grounded descent
        // keeps driveF=1 and gravity still drops the body, so it can never stall/deadlock the descent
        // (the vanilla-sneak edge-pin the old grounded variant used COULD stall; this cannot). Sprint
        // is killed independently by steepDescentNear, so with this OFF the fix is arming-only.
        // EXCLUDE parkourEdge: descendBrake already zeros driveF for parkourDescend* leaps, but a
        // plain parkour* move that lands lower is parkourEdge yet NOT descendLeap — clamping its
        // takeoff drive would land it short into the gap (a NEW fall death inside this fix's blast
        // radius). The validated RED launches were plain walk-offs, never parkourEdge, so guarding
        // here cannot weaken the fix; it only spares deliberate gap-crossing leaps.
        // No wp-vs-foot term here: it flickers during the arc (see the latch release above) and
        // every flicker tick was one tick of full impulse at a bearing that swings ±90° when the
        // node is nearly underfoot. The latch alone decides; driveF=0 makes the bearing moot.
        boolean descentAirborneDriftClamp = BotConfig.walkerDescentStepSkipBrake
                && !p.onGround() && wk.driveLatch.steepDescentLatch > 0 && !parkourEdge;
        double driveF = (!descendBrake && !pivotForStepUp && !descentAirborneDriftClamp) ? 1.0 : 0.0;
        double driveL = strafeL ? 1.0 : (strafeR ? -1.0 : 0.0);
        // Drive heading: normally aimYaw (decoupled from the slewing camera). EXCEPTION —
        // a BUOYANT slope-mount. A floating bot at a +1/+2 bank top bobs UP to the bank
        // height but, with forward zeroed by pivotForStepUp (aim not yet aligned) and the
        // drive pointed at a CHURNING aimYaw, never translates ONTO the ledge — it falls
        // back and bob-stalls (live 2026-06-20 sand slope: py peaked 64.17 at the y64 bank
        // with z frozen at 3572.30, then the dig/anti-stuck burst kicked in, ~30 s + 镜头甩).
        // The whole shoreline is a deep-water-base slope (planner can't route around it), so
        // the mount MUST be actuated: press forward HARD and STEADILY straight at the target
        // column (not the oscillating aimYaw), so vanilla's swim-step carries the bob peak up
        // the +1. Gated tight — floating, climbing (wp above foot), laterally ON the column —
        // so flat water travel and dry steps are byte-unchanged. (Special climb-out takeovers
        // — pillar / dig — return before here, so this only drives the plain-stepUp bob.)
        // Trend camera (dry descent OR flat water swim): the camera faces the far trend heading
        // (above) but the body must still drive the IMMEDIATE node so it follows the path step by
        // step — keep movement on the captured node heading. In WATER this decouple is essential, not
        // just polish: coupling the drive to the far trend made the body swim straight at the centroid
        // THROUGH a divider and ram it (waterFarAimBankCorner regressed); driving the immediate node
        // rounds the corner while the camera trend still kills the head-shake.
        // Phase-2 (walkerTangentAim): drive the body along the smoothed path tangent (aimYaw, which Edit A
        // above set to the tangent) in ALL cases — including water, where the legacy smoothWaterDriveYaw EMA
        // is a separate node-following heading that the tangent supersedes. The flip-rejection block below is
        // skipped when this is on (the tangent already never reverses, so there is no back-hop to reject).
        // NOTE (bridge battery r30/r31): do NOT swap the tangent-mode drive source off aimYaw. Driving the
        // captured pre-centroid heading (descentNodeYaw) was tried twice against the dogleg corner-cut —
        // unconditionally (r30: 9 regressions — slide-back pair, whole stop family, selfShaftDigUp,
        // waterFarAimBankCorner) and guard-pin-scoped (r31: stop family drifted off the spine at the lip
        // and spiralled into futile-FAILED@t137) — the EMA'd aimYaw drive is the suite's tuned
        // equilibrium. Corner-cut recovery is fixed on the AIM side instead: recoverySnagAim (Aim) drops
        // the trendCam centroid while a wall-pin OR guard-pin recovery owns the tree, so the corridor
        // bearing flows through the same EMA into this unchanged drive.
        float driveTargetYaw = BotConfig.walkerTangentAim ? aimYaw
                : flatWaterTrend ? wk.aimSmooth.smoothWaterDriveYaw
                : trendCam ? descentNodeYaw : aimYaw;
        // Dry diagDown SLOPE back-hop damping (the visible "雪山横跳" jitter). On a continuous
        // diagonal descent the decoupled drive rides the IMMEDIATE node (descentNodeYaw); each time
        // the foot overshoots that node it sits BEHIND the body and the bearing reverses ~180°, so
        // the drive hops backward for a few ticks until `passed` advances the pointer. The discrete
        // overshoot-advance above is gated OFF for diagDown (eager-advancing a slope regressed
        // descentYawArena backSteps 42→71), and a plain heading-override was reverted because it had
        // no escape and self-amplified into a wedge. Port the WATER drive's flip-rejection+ESCAPE:
        // when the node bearing points sharply behind the steady trend (aimYaw, the look-ahead
        // centroid that already aims down-path), hold the trend so the body keeps descending forward,
        // but SNAP back to the real node after WATER_DRIVE_MAX_REJECT ticks so node-following always
        // re-syncs (a true switchback leg sits ~±50° off trend < the 120° gate, so only a real
        // overshoot trips this). dryDescent-only; water/discrete keep their own handling.
        if (!BotConfig.walkerTangentAim && dryDescent && !flatWaterTrend && edge != null && edge.move != null
                // parkourDescend is a diagonal descender too: a parkourDescend staircase suffers the
                // SAME overshoot back-hop (live 2026-06-24 combined journey at -813,71: a
                // parkourDescend2d1 chain, yaw/driveYaw flipped +33→-103 on each overshoot → ~65 t
                // bob-jump zigzag). trendCam/aimYaw already give it the centroid trend (dryDescent==true);
                // only this flip-rejection gate excluded it, so the drive kept chasing the flipping node.
                // ...and fall / stepDown edges off a ledge: same overshoot back-hop. Live 2026-06-24
                // journey4 at -857,70 (move=fall4, bot overshot a stepUp up to y75 then the path falls
                // back to y71): FREEZE-DIAG showed dTgtYaw flip -148↔+32 with fwdComp +1↔-1 (the drive
                // ran FORWARD then BACKWARD = the "moving-backward jump"/略微后退), while camYaw held
                // steady at -148 — i.e. the camera trend was fine, only the drive chased the flipping
                // below-node bearing. Holding the trend lets it commit forward off the edge and drop.
                && (edge.move.startsWith("diagDown") || edge.move.startsWith("parkourDescend")
                    || edge.move.startsWith("fall") || edge.move.startsWith("stepDown"))
                && Math.abs(angleDiff(aimYaw, descentNodeYaw)) > WATER_DRIVE_MAX_TURN) {
            // DISCRETE drop (fall/stepDown): the backward escape pushes a bot that landed 1-above /
            // short-of the node AWAY from it (cur2 grows, the visible 180° back-hop). Hold the trend
            // continuously so it keeps closing on the node; the position-based pure-pursuit advance +
            // safetyRepath cover the strand the escape guarded against. A continuous slope keeps the
            // bounded escape (it rides the immediate node for trend smoothing and could strand). */
            boolean discreteDrop = BotConfig.walkerDescentFlipHold
                    && (edge.move.startsWith("fall") || edge.move.startsWith("stepDown"));
            if (discreteDrop || ++wk.driveLatch.descentDriveRejectStreak <= WATER_DRIVE_MAX_REJECT) {
                driveTargetYaw = aimYaw;
            } else {
                wk.driveLatch.descentDriveRejectStreak = 0;   // escape: drive the real node this tick to re-sync
            }
        } else {
            wk.driveLatch.descentDriveRejectStreak = 0;
        }
        boolean rawClimbPress = p.isInWater() && !p.onGround() && !parkourEdge
                && wp.getY() > foot.getY()
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 2.5;
        // Debounce the surface bob (see CLIMB_PRESS_DEBOUNCE): only a PERSISTENT wp-above-foot is a
        // real bank mount; a 1-tick down-bob under a same-level surface node is not.
        wk.driveLatch.climbPressConsec = rawClimbPress ? wk.driveLatch.climbPressConsec + 1 : 0;
        boolean buoyantClimbPress = rawClimbPress && wk.driveLatch.climbPressConsec >= CLIMB_PRESS_DEBOUNCE;
        if (buoyantClimbPress) {
            driveF = 1.0;
            driveL = 0.0;
            driveTargetYaw = (float) Math.toDegrees(Math.atan2(-stepColDx, stepColDz));
        }
        // Near-goal DISK CLOSURE. On the final node of a radius>0 disk goal with the foot still
        // OUTSIDE the disk, the immediate drive bearing is the very-close final node — for a floating
        // bot that bearing collapses/bobs and the body freezes a block short of the radius (live
        // 2026-06-23 XZ -1700,900 r6: pinned at foot dist 7, never closed). The disk CENTRE is still
        // 6-7 blocks away, so its bearing is STABLE (no collapse). Drive straight at the centre to
        // close the last fraction INTO the disk, where the goal.reached check at the top of step()
        // fires for real. radius>0 only (exact-cell / radius-0 water arrivals unchanged).
        if (wk.step + 1 >= wk.path.size() && !wk.goal.reached(foot)) {
            int gcx = Integer.MIN_VALUE, gcz = 0;
            if (wk.goal instanceof Goal.XZ xz && xz.radius() > 0) { gcx = xz.x(); gcz = xz.z(); }
            else if (wk.goal instanceof Goal.Near nr && nr.radius() > 0) {
                gcx = nr.target().getX(); gcz = nr.target().getZ();
            }
            if (gcx != Integer.MIN_VALUE) {
                double gdx = (gcx + 0.5) - p.getX(), gdz = (gcz + 0.5) - p.getZ();
                if (gdx * gdx + gdz * gdz > 0.5) {            // not already essentially on centre
                    driveTargetYaw = (float) Math.toDegrees(Math.atan2(-gdx, gdz));
                    driveF = 1.0;
                    driveL = 0.0;
                }
            }
        }
        double driveDelta = Math.toRadians(angleDiff(p.getYRot(), driveTargetYaw));
        double driveCos = Math.cos(driveDelta), driveSin = Math.sin(driveDelta);
        double cmdStrafe = driveL * driveCos - driveF * driveSin;
        double cmdFwd = driveL * driveSin + driveF * driveCos;
        // CORNER-CLEARANCE repulsion (walkerCornerClearance): pure-pursuit cuts corners by
        // design, and the 0.6-wide body then GRAZES a solid corner the carrot line passes
        // within half-width of — the flat §39 wedge (bridge bypass trio: side-step lane
        // past a barrier, body wedged at the barrier's west face corner z=0.91, churn
        // escapes floor-gated on the narrow deck → FAILED). PREVENT the graze instead of
        // escaping the wedge: for each solid body-height cell whose closest face point is
        // within BODY half-width + a grazing pad of the body centre, blend a small push
        // away. Self-limiting: cell-centred walking beside a wall sits at ≥0.5 (no push),
        // a 1-wide corridor pushes cancel, and the nudge is capped well under the drive
        // impulse so it bends the line rather than steering it.
        if (BotConfig.walkerCornerClearance && !p.isInWater() && (driveF != 0 || driveL != 0)) {
            double rpx = 0, rpz = 0;
            for (int cdx = -1; cdx <= 1; cdx++)
                for (int cdz = -1; cdz <= 1; cdz++) {
                    if (cdx == 0 && cdz == 0) continue;
                    BlockPos n = foot.offset(cdx, 0, cdz);
                    if (!world.isSolid(n) && !world.isSolid(n.above())) continue;
                    double ncx = Math.max(n.getX(), Math.min(p.getX(), n.getX() + 1.0));
                    double ncz = Math.max(n.getZ(), Math.min(p.getZ(), n.getZ() + 1.0));
                    double ox = p.getX() - ncx, oz = p.getZ() - ncz;
                    double d = Math.sqrt(ox * ox + oz * oz);
                    if (d >= 0.45 || d < 1e-6) continue;
                    double push = (0.45 - d) / 0.45;
                    rpx += ox / d * push;
                    rpz += oz / d * push;
                }
            double rMag = Math.sqrt(rpx * rpx + rpz * rpz);
            if (rMag > 1e-6) {
                double scale = Math.min(0.5, rMag) / rMag;   // cap the nudge at half impulse
                // world → camera frame: forward = (−sinθ, cosθ), left = (cosθ, sinθ)
                double cam = Math.toRadians(p.getYRot());
                double camSin = Math.sin(cam), camCos = Math.cos(cam);
                cmdStrafe += (rpx * camCos + rpz * camSin) * scale;
                cmdFwd += (rpz * camCos - rpx * camSin) * scale;
            }
        }
        wk.driveTag = String.format(Locale.ROOT, "y%.0f F%.2f L%.2f s%.2f f%.2f",
                driveTargetYaw, driveF, driveL, cmdStrafe, cmdFwd);
        a.commandMove((float) cmdStrafe, (float) cmdFwd);
        // TEMP FREEZE-DIAG (approach-freeze / pit-cascade phase-3 investigation): capture the exact
        // drive state when a dryDescent stalls (stuckTicks high) — which steering branch + driveF +
        // forward component + brakes — so the cur2-frozen hSpd~0 stall mechanism is identified, not guessed.
        if (BotConfig.walkerDebug && dryDescent && wk.stuckTicks > 18) {
            double fwdComp = driveL * driveSin + driveF * driveCos;
            LOG.info("[walker] FREEZE-DIAG stuckT={} driveF={} fwdComp={} ddeg={} dTgtYaw={} camYaw={} aimAtWp={} reCentre={} pivotSU={} descBrake={} stepUpFreeze={} sneak={} sprint={} wpY-footY={} stepCol2={}",
                    wk.stuckTicks, String.format(Locale.ROOT, "%.2f", driveF),
                    String.format(Locale.ROOT, "%.2f", fwdComp),
                    String.format(Locale.ROOT, "%.0f", Math.toDegrees(driveDelta)),
                    String.format(Locale.ROOT, "%.0f", driveTargetYaw),
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    aimAtWaypoint, reCentre, pivotForStepUp, descendBrake, stepUpFreeze,
                    a.dbgSneak(), p.isSprinting(), (wp.getY() - foot.getY()),
                    String.format(Locale.ROOT, "%.2f", (stepColDx * stepColDx + stepColDz * stepColDz)));
        }
        // Bridging a chasm one placed block at a time: sneak (so a sprint
        // overshoot can't carry the bot off the fresh 1-wide block into the
        // gap ahead) and don't sprint. Triggered when the edge we're walking
        // onto OR the next one is a place-bridge — Baritone sneaks for the
        // same reason. Released the moment we're back on real ground.
        // (`bridging` is computed once, hoisted up near the travel-aim pitch.)
        // Lethal-edge sneak-brake (DEATH #8 fix): if a fatal drop is one step ahead
        // in the heading, hold sneak so vanilla's ledge-guard pins the body at the
        // block edge — the controller can no longer drift off a cliff while fleeing
        // or walking a lip. Lethal-only, so it never blocks a legitimate planned
        // step-down (those are capped at survivableFall by the PathFinder).
        // A lethal drop borders the foot — but only PIN with sneak when we're NOT
        // making a planned step-DOWN. Vanilla's ledge-guard refuses to walk off ANY
        // edge, so if the brake stays on while the path needs to descend (a lethal
        // drop in some OTHER direction, e.g. a mountainside, while the safe planned
        // step is down-and-across), the bot DEADLOCKS — sneak on, hCol=false, creeping
        // at ~0 b/s on a ridge forever (the "速度陡降 / blocked" stall). The PathFinder
        // caps every planned step-down at survivableFall, so releasing sneak for the
        // intended descent is safe. Same-level lip-walking keeps the full pin (death #8),
        // and sprint stays OFF whenever a lethal edge is near (see sprint below) so the
        // released descent has no drift/overshoot momentum off the lip.
        boolean lethalNear = BotConfig.lethalEdgeBrake && p.onGround()
                && lethalDropAdjacent(world, p, foot);
        boolean plannedDescent = wp.getY() < foot.getY();
        // Low-HP care (survival DEATH #3, 2026-07-11): releasing the pin for a
        // planned descent bets that the body lands exactly on the planned cell.
        // At critical health that bet is fatal — survivableFall shrinks to 3-4
        // blocks, so ordinary walk/jump drift past the lip onto a deeper drop
        // kills. Below the threshold keep the sneak pin even across a planned
        // step-down: a pinned descent stalls and repaths (recoverable); a fall
        // at 1-6 HP is not. Sprint is also suppressed below (same gate).
        boolean lowHpCareful = BotConfig.lowHealthCareful > 0
                && p.getHealth() <= BotConfig.lowHealthCareful;
        boolean edgeBrake = lethalNear && (!plannedDescent || lowHpCareful);
        // Steep-descent SPRINT brake (NOT a sneak-pin): a survivable-but-deep drop in an
        // edge-neighbour while descending is invisible to lethalNear (its threshold is
        // survivableFall ≈ 23 blk, even higher with resist buffs), so the bot SPRINTS a
        // diagDown/step-down and the momentum drifts it OFF the lip off-path into a 13-19
        // blk fall + 80 t fellOffPath deadlock (live 2026-06-24 random journey at -679,83:
        // sprinted a diagDown, drifted -680→-677 off a 12-drop). Drop sprint when a drop
        // deeper than a normal step (>4 blk) sits adjacent during a planned descent so the
        // body decelerates onto the node instead of overshooting the lip; sneak stays
        // released (a survivable drop needn't pin the body, so the step-down still proceeds).
        // LATCH the sprint-drop across the airborne sub-arcs of the step-down (task#36, DRY sibling
        // of the deepWaterDriftLatch below): the raw brake is gated onGround, so on the airborne
        // half of each step sprint RE-ARMS and the accumulated FORWARD momentum walks the body off
        // a survivable-deep (>4, <survivableFall) lip into a fatal cumulative fall — live 2026-07-11
        // Mountains massif telemetry: grounded sprint=false at the lip, but onG=false→sprint=true on
        // every fall tick, forward z crept the body over a 19-block lip to death. Hold the brake
        // STEEP_DESCENT_DRIFT_LATCH ticks after each grounded fire so sprint stays off through the
        // whole descent; it re-arms on each grounded step and decays once the descent flattens (wp
        // flush/ascending) or a long free-fall outruns it (harmless — nothing to brake mid-air).
        // Off entirely when the flag is off (byte-identical: raw already requires onGround, and the
        // release clause clears the latch on the next non-raw tick). A gentle ≤4 staircase never
        // trips the raw, so normal descents keep full sprint.
        // CUMULATIVE-descent arm (task#36, 2026-07-12, gated walkerSteepDescentLatch): the single-
        // edge dropAdjacentExceeds only fires on a >maxDryFall CLIFF neighbour. A mountain descent
        // the planner routes as a run of individually-legal ≤maxDryFall steps (live Mountains
        // y79→77→73→72) has no such neighbour at ANY grounded tick, so steepDescentRaw never armed,
        // the latch never engaged, and the body sprint-sailed off the slope (hSpd rising 0.21→0.23,
        // sprint=T, 6+ block continuous fall to death). Sum the drop the planner actually laid over
        // the next few nodes (foot.Y − min node.Y); arm when it exceeds a single legal step. Uses
        // the PATH (authoritative — exactly maxStepDrop≤4 per node) not a hand-rolled world probe.
        int steepDescentPathDrop = 0;
        if (wk.path != null && !wk.path.isEmpty()) {
            int loY = foot.getY();
            for (int k = wk.step; k < Math.min(wk.path.size(), wk.step + STEEP_DESCENT_LOOKAHEAD_NODES); k++) {
                loY = Math.min(loY, wk.path.get(k).getY());
            }
            steepDescentPathDrop = foot.getY() - loY;
        }
        boolean steepDescentPathAhead = BotConfig.walkerSteepDescentLatch && p.onGround()
                && steepDescentPathDrop > BotConfig.pathfinderMaxDryFall;
        boolean steepDescentRaw = p.onGround()
                && ((plannedDescent && dropAdjacentExceeds(world, foot, 4))   // local >4 cliff neighbour
                    || steepDescentPathAhead);                                // NEW: cumulative slope ahead
        if (steepDescentRaw) wk.driveLatch.steepDescentLatch = STEEP_DESCENT_DRIFT_LATCH;
        // Release ONLY when GROUNDED at/above the node. The old instantaneous wp-vs-foot check
        // flickered true MID-ARC (gap #51 trace t=43: foot 233.7 falls past wp 234 for one tick)
        // and zeroed the latch in the air — re-enabling full drive at a swinging bearing and
        // re-arming sprint mid-fall, which is exactly the sideways kick that walked the body off
        // the 1-wide stair (landed x=301 on a corner, slid off, fell to -60). A latch armed for
        // an airborne descent arc must survive the whole arc; landing is the only sane release.
        else if (wk.driveLatch.steepDescentLatch > 0
                && (!BotConfig.walkerSteepDescentLatch || (p.onGround() && wp.getY() >= foot.getY()))) wk.driveLatch.steepDescentLatch = 0;
        else if (wk.driveLatch.steepDescentLatch > 0) wk.driveLatch.steepDescentLatch--;
        boolean steepDescentNear = steepDescentRaw || wk.driveLatch.steepDescentLatch > 0;
        if (steepDescentPathAhead && BotConfig.walkerDebug)
            LOG.info("[walker] STEEP-DESCENT path-arm: foot y={} pathDrop={}>{} over {} nodes → latch {} (noSprint{})",
                    foot.getY(), steepDescentPathDrop, BotConfig.pathfinderMaxDryFall,
                    STEEP_DESCENT_LOOKAHEAD_NODES, STEEP_DESCENT_DRIFT_LATCH,
                    BotConfig.walkerDescentStepSkipBrake ? "+airborneDriveFClamp" : "");
        // DESCENT STEP-SKIP grounded sneak-brake — RETIRED (task#36, 2026-07-12). Its gate
        // (onGround && foot.Y-wp.Y > maxDryFall) was proven a literal NO-OP by live trace: the
        // far-below wp only appears while AIRBORNE (grounded max(foot.Y-wp.Y) is exactly 4.0,
        // never >4), so this never fired. The flag walkerDescentStepSkipBrake is now repurposed as
        // the AIRBORNE forward-drift clamp on driveF (see ~line 4259). Kept as `false` here so the
        // brakeSneak/sprint terms below stay byte-identical without editing them.
        boolean descentStepSkip = false;
        // DEEP-WATER drift SPRINT brake (mechanism b, sibling of steepDescentNear for a water
        // hazard): a dry descending/flush staircase that runs ALONG a deep floating-water pocket
        // is invisible to the dry-drop brakes (dropAdjacentExceeds skips water as a splash), so the
        // bot sprints the stepDown/diagDown and the momentum overshoots laterally off the dry edge
        // into the pocket — where a buoyant body bob-stalls on the un-climbable-out surface for
        // tens of seconds (live replay-0008 -870: foot drifted x-870.5→-866.3 off the staircase,
        // 800+ frozen pocket ticks). Drop sprint (NOT sneak — a sneak-pin would deadlock the
        // step-down, see the long edgeBrake note) when a deep-water cell borders the foot during a
        // descent or flat edge-walk, so the body decelerates onto the node instead of overshooting.
        // Never fires when the path DELIBERATELY enters the water (planned next node is itself deep
        // water — a river/lake crossing WANTS the bot in): then there is no dry line to hold and the
        // brake would only slow a legitimate entry. Ascents keep sprint (parkour/swim-approach need
        // the momentum; an ascent isn't drifting DOWN into the pocket).
        // radius 2, maxDrop 7: a deep pocket sits against a TALL bank with a stepped face, so
        // while the bot is still GROUNDED on the upper steps the open water is ~2 cells away and
        // ~6 below (live -870: grounded foot x-871/y68, floating water begins at x-869/-868,y62 —
        // a 1-neighbour/4-deep scan never reached it and the brake stayed inert; the bot went
        // airborne, dropping the onGround precondition, before the water became an immediate
        // neighbour). A dry fall column self-terminates at its first solid/shallow floor, so a
        // wider/deeper scan only adds reach toward genuinely deep water, never a dry-cliff false
        // positive. 2 covers the lateral overshoot a sprint adds; 7 the 6-deep drift with margin.
        boolean deepWaterEdgeRaw = BotConfig.walkerDeepWaterDriftBrake && p.onGround()
                && wp.getY() <= foot.getY()                       // descending or flush walk along the edge (not an ascent)
                && !world.isFloatingWater(wp)                     // not a deliberate deep-water entry (the path means to swim in)
                && deepWaterDropAdjacent(world, foot, 2, 7);
        // LATCH the brake across the airborne sub-arcs of a step-down descent: a staircase is a
        // chain of small drops, so the bot is airborne (onGround false) for ~half the ticks, and
        // without the latch sprint re-arms on every airborne tick and the accumulated forward
        // momentum still carries the body off the dry line into the pocket (live -870 bottom node:
        // grounded sprint=false, but the overshoot to x-866 / inW happened across the un-braked
        // airborne ticks between steps). Hold the brake DEEP_WATER_DRIFT_LATCH ticks after each
        // grounded fire so sprint stays off through the whole descent past the edge; it decays
        // once the bot stops re-triggering (moved away from the deep edge / finished the descent).
        // Released the instant the path turns to deliberately ENTER the water (wp is deep water),
        // so a real crossing isn't slowed. Off entirely when the flag is off (byte-identical).
        if (deepWaterEdgeRaw) wk.driveLatch.deepWaterDriftLatch = DEEP_WATER_DRIFT_LATCH;
        else if (wk.driveLatch.deepWaterDriftLatch > 0
                && (!BotConfig.walkerDeepWaterDriftBrake || world.isFloatingWater(wp))) wk.driveLatch.deepWaterDriftLatch = 0;
        else if (wk.driveLatch.deepWaterDriftLatch > 0) wk.driveLatch.deepWaterDriftLatch--;
        boolean deepWaterDriftNear = deepWaterEdgeRaw || wk.driveLatch.deepWaterDriftLatch > 0;
        // DYNAMIC fall-correction while bridging (user: 动态纠偏防止跌落,而不是一直蹲着牺
        // 牲速度). A 1-wide place-bridge has void on BOTH sides, so the lethal-edge pin
        // (edgeBrake) AND the old blanket bridge-sneak BOTH held shift for the ENTIRE
        // span — crouch-walking the whole bridge at a ~0.9 b/s crawl. Instead, walk the
        // already-placed blocks at full speed and brake (sneak — its ledge-guard pins the
        // body so it can't step off) ONLY on the ticks with an ACTUAL fall risk:
        //   • gapAhead — no solid footing ~0.6 blocks ahead toward wp (about to overshoot
        //     off the front edge into the not-yet-placed frontier), or
        //   • offCentre — drifted >0.3 off the foot→wp centreline (side fall on the 1-wide
        //     span; the camera-decoupled impulse already re-aims at wp's centre, so this
        //     only fires on real drift).
        // Planned step-downs still release entirely (a descending bridge deadlocks under
        // any pin — see the long edgeBrake note above). Sprint is OFF during bridging
        // (below), so a non-braked tick can't build enough momentum to overshoot a whole
        // block before the next look-ahead check brakes it.
        // Generalised beyond bridging (出桥 2 格 crawl): right after a bridge — or on
        // any cliff-lip walk — `edgeBrake` re-pinned the body the moment `bridging`
        // dropped, crouch-crawling until the void left the 8-neighbourhood. The SAME
        // dynamic gate is the cert'd fix for the same risk on a 1-wide span (the
        // strictly harder case), so apply it to every lethal-edge walk: full speed on
        // safe ticks, brake only on gapAhead/off-centre drift. Sprint stays OFF near
        // a lethal edge (below), bounding per-tick travel just like on the bridge.
        // EXECUTION-TIME path hazard re-check (devil-bench iron ep-014 death #27):
        // the plan was hazard-free, then LAVA FLOWED into the planned corridor at
        // y8 — a breached pocket's flow front chased the tunnel — and the actuator
        // walked the body into the flow (the hazardAhead brake below only SLOWS;
        // it exists for lava-BESIDE-path passages). A hazard IN the path is not a
        // creep-past case: when the waypoint column itself — or, on a diagonal
        // step, either corner column the body sweeps — has BECOME hazardous, stop
        // and replan around the new flow. A* never plans through hazard cells, so
        // this can only fire on world change; static scenes never see it.
        {
            boolean wpHazard = world.isHazard(wp) || world.isHazard(wp.above());
            if (!wpHazard && wp.getX() != foot.getX() && wp.getZ() != foot.getZ()) {
                BlockPos ca = new BlockPos(wp.getX(), wp.getY(), foot.getZ());
                BlockPos cb = new BlockPos(foot.getX(), wp.getY(), wp.getZ());
                wpHazard = world.isHazard(ca) || world.isHazard(ca.above())
                        || world.isHazard(cb) || world.isHazard(cb.above());
            }
            // FALL-EDGE flow-front margin (iron ep-025 death #29): a ≥2-drop
            // commit is irreversible — once airborne, the brake below fires
            // into gravity (live: fall2 adopted at t=0, lava flowed into the
            // landing during the 8-tick arc, brake fired at t=8 mid-air, dead
            // one tick later). While still GROUNDED before such a drop, treat
            // lava beside the landing column as hazardous too: a flow front
            // one cell away arrives within ~1.5s, faster than the fall+walk.
            if (!wpHazard && p.onGround() && wp.getY() <= foot.getY() - 2) {
                BlockPos land = wp;
                for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST,
                                                   Direction.SOUTH, Direction.WEST}) {
                    BlockPos n1 = land.relative(d);
                    if (world.isHazard(n1) || world.isHazard(n1.below())) {
                        wpHazard = true;
                        break;
                    }
                }
            }
            if (wpHazard) {
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] path-hazard brake: waypoint {} column now hazardous "
                            + "(flowed in after plan) → forceRepath", wp.toShortString());
                a.releaseInputs();
                wk.forceRepath();
                return Walker.Step.WALKING;
            }
        }
        // HAZARD-AHEAD brake (lava ×4 live in two rounds): the danger ring only
        // PRICES a lava-hugging route — when no detour exists A* still commits
        // one, and the 0.6-wide body drifts into the neighbouring lava cell
        // mid-stroke (fire res masked what kills a naked bot). Hard guard at the
        // actuator: if the cell ~0.8 ahead along the heading is a hazard at foot
        // or head level, sneak-brake this tick (ledge-guard semantics keep the
        // body out of the cell; sneak still creeps ~0.9 b/s so a mandatory
        // lava-side passage stays passable, just slow — exactly right there).
        boolean hazardAhead = false;
        {
            double hax = (wp.getX() + 0.5) - p.getX();
            double haz = (wp.getZ() + 0.5) - p.getZ();
            double hal = Math.sqrt(hax * hax + haz * haz);
            if (hal > 1e-3) {
                BlockPos aheadCell = BlockPos.containing(
                        p.getX() + hax / hal * 0.8, p.getY(), p.getZ() + haz / hal * 0.8);
                hazardAhead = world.isHazard(aheadCell) || world.isHazard(aheadCell.above());
            }
        }
        boolean bridgeBrake = false;
        // Descending-place lip anchor (task#4, replay-0013): the !plannedDescent gate
        // below exists because a sneak pin across a planned step-down deadlocks — but
        // when the descent runs over a bridgePlace whose SUPPORT IS NOT PLACED YET,
        // "refuse the edge" is exactly right: there is nothing to step down ONTO.
        // Re-admit the dynamic brake for that one case (approach momentum control);
        // the pending check self-releases the moment the support block lands, so the
        // real step-down still proceeds pin-free. The actuator's own sneak hold
        // (WalkerTickClimb place loop) covers the aim ticks; this covers the approach.
        boolean descentPlacePending = false;
        if (BotConfig.walkerBridgeDescentPlaceAnchor && bridging && plannedDescent) {
            Move.Edge nextE = wk.edgeAt(wk.step + 1);
            outer:
            for (Move.Edge e : new Move.Edge[]{edge, nextE}) {
                if (e == null) continue;
                for (BlockPos b : e.toPlace)
                    if (b.getY() < foot.getY() && !world.isSolid(b)) {
                        descentPlacePending = true;
                        break outer;
                    }
            }
        }
        if ((bridging || edgeBrake) && (!plannedDescent || descentPlacePending) && !parkourEdge) {   // !parkourEdge: sneak on the takeoff tick costs the leap 70% of its steering (ServerPlayerAvatar's pendingSneak?0.3f:1f) — same lip, same measurement as the sprint term; see this class's javadoc. Sibling exclusions: the lane-keep strafe and descentAirborneDriftClamp above
            double bdx = (wp.getX() + 0.5) - p.getX();
            double bdz = (wp.getZ() + 0.5) - p.getZ();
            double blen = Math.sqrt(bdx * bdx + bdz * bdz);
            if (blen > 1e-3) {
                double ax = p.getX() + bdx / blen * 0.6;
                double az = p.getZ() + bdz / blen * 0.6;
                BlockPos aheadFoot = new BlockPos((int) Math.floor(ax), foot.getY(), (int) Math.floor(az));
                boolean gapAhead = !world.isSolid(aheadFoot.below()) && !world.isSolid(aheadFoot);
                double fx = foot.getX() + 0.5, fz = foot.getZ() + 0.5;
                double offCentre = Math.abs((p.getX() - fx) * bdz - (p.getZ() - fz) * bdx) / blen;
                bridgeBrake = gapAhead || offCentre > 0.30;
            }
        }
        // The dynamic brake REPLACES the constant lethal-edge pin everywhere (bridge
        // AND cliff-lip): the pin's job — don't drift off a lethal edge — is done by
        // the per-tick gapAhead/offCentre gate at full walking speed.
        boolean lavaBrake = hazardAhead && !p.isInWater();   // land-only: a surface swimmer sneaking beside lava would DIVE (active sink), not stop
        // sneak in water = vanilla active SINK (buoyancy never sinks a surface swimmer on its
        // own) — so the dry-land safety brakes (bridge / cliff-descend) must NOT sneak in water,
        // exactly like lavaBrake above: a brake's job is to STOP, but shift in water DIVES the bot
        // to the floor. Live 2026-06-24 deep-pool crossing: a descending parkour edge fired
        // descendBrake → shift → the buoyant bot sank y62→58 and churned ~100 t clawing back to the
        // surface, then re-planned a parkour-onto-water it cannot execute. Only a real dive (diving)
        // sneaks in water; buoyancy alone already keeps a surface swimmer off any lethal edge.
        boolean brakeSneak = (bridgeBrake || descendBrake || descentStepSkip) && !p.isInWater();
        // A5 UNDERWATER HORIZONTAL DEPTH-HOLD (dive → traverse). After a dive the path
        // continues through SUBMERGED horizontal edges — the planner emits plain walk/diag
        // nodes mid-water (they are not named swimDown*, so none of the dive gates hold
        // them), and without a depth-hold the swim-up bob below (swimUp / swimColumn /
        // deepWaterRise) ratchets the bot back to the surface where it pins (live
        // 2026-07-04 underwater-base run: dove to -57, walk edges at -59 through a 2-tall
        // doorway, bot ratcheted to -50.8 and drowned at the surface). Gate — as narrow as
        // the dive gates, keyed on the DIVE opt-in plus WATER STATE, not move name:
        //   • the profile explicitly opted into DIVE (empty optIn → byte-identical:
        //     a non-dive bot submerged over a same-level/1-below waypoint keeps the
        //     pre-A5 buoyant swim-up rise), AND
        //   • the bot is SUBMERGED (water two above the feet — WorldView#isSubmergedFoot;
        //     a surface floater NEVER reads true, so the surface machinery — carrot-drive,
        //     bob-latch, flatWaterWalk sprint, float-over crossing, climb-out — is
        //     completely untouched), AND
        //   • the current step target is itself UNDERWATER (its head cell is water too;
        //     a climb-out / bank node reads false), AND
        //   • the node is at-or-below the feet (wp above foot = a planned ascent → gate
        //     off, the normal swim-up rise applies).
        // Action: feet ABOVE the node level → sneak (the vanilla shift-sink, the exact
        // actuator a swimDown* dive rides) until back at level; AT level → no sneak, the
        // plain forward drive traverses (and the swim-up jump stays suppressed so the
        // level HOLDS); feet BELOW → gate is off by the third clause.
        boolean underwaterDepthHold = wk.profile.capability().allowsOptIn(Capability.DIVE)
                && p.isInWater() && world.isSubmergedFoot(foot)
                && world.isWater(wp) && world.isWater(wp.above())
                && wp.getY() <= foot.getY();
        boolean underwaterSink = underwaterDepthHold && wp.getY() < foot.getY();
        Walker.avatarSneak(a, brakeSneak || diving || lavaBrake || underwaterSink);
        p.setShiftKeyDown(brakeSneak || lavaBrake);
        // Jump for a real upward step, a parkour-leap edge (by move type, not
        // raw distance — string-pulling makes plain walk waypoints far apart
        // too), or a brief stuck-wiggle.
        // The stuck-wiggle jump unsticks a bot wedged on a corner, but on a
        // 1-wide bridge it would hop the bot clean off into the gap — so
        // never wiggle-jump while bridging. Stop pressing jump once braking
        // (we're descending onto the block — no more lift wanted).
        // Stay afloat while pathing through water. autoSwim is gated OFF during
        // goto/follow/explore/runAway (it would fight the Walker's jump), so the
        // Walker itself must swim up — otherwise the bot sinks in deep water and
        // drowns mid-path (the "swam into the ocean and died" failure). Hold jump
        // whenever the eyes are submerged, unless we're deliberately diving a
        // swimDown edge (then let it descend).
        // (diving is computed earlier, beside diveUnderCap, so the pitch/sneak
        // actuators can drive the ACTIVE sink — see that block for the rationale.)
        // DEBOUNCED (≥3 ticks submerged): on a surface cruise the eye line bobs in
        // and out of the waterline every few strokes, and each 1-2-tick dip pulsed
        // the swim-up jump — a +1y overshoot tooth every ~2-3 s with a synchronized
        // speed dip (round43b pathChart: regular sawtooth across the whole sea leg).
        // A genuine sink keeps the eyes under for many consecutive ticks, so a
        // 3-tick (150 ms) gate costs real buoyancy nothing; swimColumn (water at
        // head height) still rises a true column un-debounced.
        wk.driveLatch.underwaterTicks = (p.isInWater() && p.isUnderWater()) ? wk.driveLatch.underwaterTicks + 1 : 0;
        boolean swimUp = wk.driveLatch.underwaterTicks >= 3 && !diving;
        // The stuck-wiggle hop unsticks a corner on DRY land, but in shallow water
        // on a flat walk it just bobs the bot off the floor into the buoyant drift
        // (it floats off its cell and slides — the very stall it's meant to break).
        // Suppress it there; treading + steady forward threads the channel instead.
        // A floating body rides ONE block above the in-water path nodes (A* places
        // water nodes at the cell whose head breaks the surface; the buoyant foot
        // is in the cell above), so a same-level open-water crossing shows up as
        // wp.y == foot.y − 1 with a WATER wp — treat that as flat too, or the
        // whole crossing loses the prone sprint-swim and treads at ~1.5 blk/s
        // (live 2026-06-09: sprint=false across a lake, hSpd 0.077, each 200-tick
        // repath advanced only ~8 blocks → zig-zag + stall feel). A real swimDown
        // edge keeps its own handling (diving), and climb-outs (wp above foot)
        // stay non-sprint as before.
        boolean flatWaterWalk = p.isInWater() && !diving
                && (wp.getY() == foot.getY()
                    || (foot.getY() - wp.getY() == 1 && world.isWater(wp)));
        // Floor-gated (walkerRecoveryHopFloorGate): the "while bridging" carve-out above
        // encodes edge TYPE, but the fatal case is footing GEOMETRY — walking an EXISTING
        // 1-wide strip is not `bridging`, and the wiggle there launched a sprint-jump arc
        // along a mid-slew heading clean over the deck (bridge battery, every shed).
        // Hop-range (Chebyshev ≤2) because the arc travels ~3 blocks: the sheds launched
        // from a floored cell one stride INSIDE the rim, so foot-adjacent scans stay blind.
        boolean wiggle = wk.wiggleHop(world, p, foot, !bridging && !flatWaterWalk && !pivotForStepUp);
        // Climbing a +1 ledge out of a SHALLOW water film needs a BALLISTIC,
        // GROUNDED jump: |Δy|=0.8 exceeds the 0.6 auto-step, and a *held* jump in
        // water just swims the bot up to bob at the surface (y+0.2, onGround=false)
        // — it never clears the dry ledge (trace: stuck 797t at cur2≈1, |dY|=0.8).
        // So for a water climb-out, jump ONLY when grounded: releasing between
        // launches lets the body settle the 0.2 back onto the shaft floor, from
        // which a real jump (+1.25) tops the ledge while the strafe-centring above
        // holds it under the target column so forward carries it on. (swimUp below
        // still rescues a genuinely-submerged deep-water climb — undW there.)
        // Water climb-out jump rule covers BOTH depths:
        //  • SUBMERGED (deep water / flooded pit, undW): swim up — hold jump so the
        //    bot rises through the water column toward the surface (never sinks /
        //    drowns; this is the original swimUp behaviour).
        //  • AT THE SURFACE of a shallow film (inW, !undW): a held jump only bobs
        //    (y+0.2, onGround=false) and can't clear the 0.8 dry ledge, so jump
        //    ONLY when grounded — releasing lets the body settle the 0.2 onto the
        //    floor, from which a real jump (+1.25) tops the ledge (strafe-centring
        //    above holds it under the column so forward carries it on).
        // Still ASCENDING a water column when the head cell (or eyes) is water —
        // a deep/flooded pit is multiple blocks deep, so keep swimming up (hold
        // jump) until the head clears; checking only undW stops mid-column (eyes
        // pop out at y+0.5 while feet are still 2 below) and the bot sinks back,
        // oscillating at the pit bottom (trace: bobbed y60.0↔60.57, never rose).
        boolean swimColumn = p.isInWater() && (p.isUnderWater() || world.isWater(foot.above()));
        // Climb a +1 ledge OUT of water like a dry-land climb: hold jump CONTINUOUSLY
        // (vanilla "hold forward+jump to climb out of water"), not only when grounded.
        // The old `waterClimb ? (swimColumn || onGround)` fired jump ~never at a shallow
        // film — the buoyant body is onGround ≈1/10 ticks and swimColumn is false (no
        // water above the head), so it just treaded into the bank wall (trace: stuck
        // 814t at a lake bank, jump=false every tick). wp.getY()>foot.getY() is true for
        // ANY water climb-out, so this presses jump throughout it; swimColumn still
        // rises a deep submerged column and swimUp covers a fully-submerged climb.
        // For a dry cardinal +1 step, only jump once Baritone-aligned (ascendJumpReady):
        // close + squared-up + not drifting. Early/off-axis jumps bonk the step and
        // slide back (the steep-climb wedge); waiting to jump also keeps the body
        // moving smoothly instead of bobbing in place (no jittery camera on stream).
        // Jump a step only when a jump is actually needed (beyond auto-step) AND the
        // step is within reach (≤ maxJumpUp) — never bob-jump an unreachable height —
        // and, for the +1 cardinal case, only once Baritone-aligned.
        boolean stepUpJump = needJumpForStep && upDy <= maxJumpUp && (!dryStepUp || ascendJumpReady) && !pivotForStepUp;
        // Y-MISLABELED-RISER RAM (executor riser-detection). A* can emit an edge it labels a LEVEL
        // {@code walk} (the Move has dy=0) whose DESTINATION floor is actually +1 — a mislabeled
        // ridge step. The bot, told the ground is level, SPRINTS into it (a walk edge keeps sprint),
        // walks off the lower approach block, drops onto it ONE below the node, and now rams the +1
        // riser it never squared up for (live 2026-06-25 -731,80,301: approached at py=80.0 sprint=
        // true, dropped py80→79, then hCol=true hSpd 0.018-0.065 dY=1.00 ramming, jump=false). A
        // genuine stepUp edge would have squared-up + dropped sprint; the mislabel skips that, so the
        // bot bonks the corner. The normal recovery (stepUpFreeze / stepUpJump once pivot aligns) DOES
        // fire, but only after the off-axis drop + a several-tick stall. Detect the unambiguous
        // signature — a WALK-labeled edge whose node sits exactly +1 above a GROUNDED foot
        // (upDy==1: a true level walk is upDy==0, so +1 IS the mislabel) with a mountable riser dead
        // ahead and a confirmed ram — and fire the grounded jump up-and-over AT ONCE, before the
        // off-axis pivot/freeze wait. Forward already drives at the column (driveF=1 unless pivot cuts
        // it), so this adds only the JUMP. Gated HARD on the walk mislabel + grounded ram, so a
        // correctly-labeled stepUp (own gates), a clean level walk (no riser/no collision → never
        // fires, no bunny-hop), and any non-walk edge are all byte-identical INERT.
        boolean levelRiserRam = edge != null && "walk".equals(edge.move) && upDy == 1
                && p.onGround() && !p.isInWater() && !parkourEdge && !bridging && !placingEdge
                && !descendBrake && p.horizontalCollision && wk.ramFold.stepRamStuckTicks >= 2
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 2.6
                && forwardRiserMountable(world, foot, stepColDx, stepColDz);
        if (BotConfig.walkerDebug && levelRiserRam) {
            LOG.info("[walker] LEVEL-RISER-RAM wp={},{},{} foot={},{},{} upDy={} stepRam={} stepCol2={} act={}",
                    wp.getX(), wp.getY(), wp.getZ(), foot.getX(), foot.getY(), foot.getZ(), upDy,
                    wk.ramFold.stepRamStuckTicks,
                    String.format(Locale.ROOT, "%.2f", (stepColDx * stepColDx + stepColDz * stepColDz)),
                    BotConfig.walkerLevelRiserJump);
        }
        boolean levelRiserJump = levelRiserRam && BotConfig.walkerLevelRiserJump;
        // Don't hold the swim-up jump when a SOLID cell caps the head (foot+2) while
        // in water: the path threads a submerged / stone-overhung tunnel (water
        // surface capped by solid above), so bobbing up just RAMS that ceiling and
        // the body can't move horizontally under it — the stuck-bobbing trace at a
        // stone-capped water surface (hCol=true, hSpd=0, y oscillating into the
        // ceiling, pos frozen). Staying low lets forward thread the tunnel. Only the
        // water jumps are gated; a dry stepUp / parkour launch is never suppressed
        // (their own gates apply), and an OPEN water column (head not capped) still
        // swims up so a deep crossing can't drown.
        boolean cappedHead = p.isInWater() && world.isSolid(foot.offset(0, 2, 0));
        // A buoyant bot reaching the surface of DEEP water (water below its feet) on a level or
        // slightly-rising WATER node has NO swim-up and sinks to the floor: swimUp needs the eyes
        // already submerged ≥3t, and swimColumn needs water at foot+1 — but a body whose foot
        // BLOCK sits AT the surface cell has AIR at foot+1, so both miss, jump=false, and it drops
        // straight to the bottom and wedges in a crevice (live 2026-06-24 walk edge to -798,62,649:
        // y62 surface→y47 floor, jump=false the whole way down, then 117t wedged hCol at y47).
        // descendBrake is a parkourDescend-only brake (not this walk edge) and diving is false
        // (sneak=false), so nothing else holds it up. Hold the swim-up jump whenever DEEP water is
        // under the feet and the target is a WATER cell at/above us, so it stays afloat across the
        // crossing. Excludes dives (diving, or wp below the foot) and dry bank climb-outs
        // (!isWater(wp) → the grounded ballistic climb-out jump still applies); cappedHead below
        // still threads a stone-lipped submerged tunnel low.
        boolean deepWaterRise = p.isInWater() && !diving && world.isWater(foot.below())
                && wp.getY() >= foot.getY() && world.isWater(wp);
        // FELL-BELOW ALIGN BREAKER (walkerFellBelowAlign) — the diagUp limit-cycle ROOT, one layer below apw's repath.
        // The -815/-809 churn (live replay-0006) is a LARGE vertical limit cycle (y64-84) where the bot oscillates
        // ACROSS the path nodes; it is AIRBORNE ~97% (bob), so EVERY grounded/hCol-gated jump suppression is defeated
        // and the bot re-launches a futile jump every grounded tick, never settling. When arcProgStall (bob-immune:
        // <2.0 net arc-s over 40t) confirms the no-progress oscillation, SUPPRESS the jump at any VERTICAL node
        // (wp.y != foot.y, where a jump would otherwise relaunch the bob) so the bot SETTLES to ground and the existing
        // fellOffPath repath fires from a stable grounded pose (mirrors the pillarUp off-column align). Validated
        // isolated: replay-0006 apw-alone 657 → FBA+apw 515 (the -809 spot drops to ~ts17); replay-0005 escapes the
        // -818 cycle apw-alone gets stuck on (1485 → 627). EXCLUDE a water-edge / shallow climb-out (replay-0005 -890
        // lakeside start, bot at y62): there the bot is legitimately climbing out and suppressing the jump only blocks
        // it (regressed the start 790 → 1176); the diagUp cycle is a DRY steep oscillation, so require dry ground under
        // the foot. Level walks (wp.y==foot.y) keep their jump (none fires there anyway). Default OFF (byte-identical).
        // NOTE: end-to-end maxStuck is still dominated by OTHER domains (downstream water stalls) and the movement
        // flags INTERACT badly (FBA+apw+water-stack froze the -818 cycle at 3577) — a silky combination needs the
        // systematic replay-corpus flag search, not hand-tuning. This fix is validated for the diagUp domain only.
        boolean fellBelowNearWater = world.isWater(foot) || world.isWater(foot.below())
                || world.isWater(foot.offset(0, -2, 0));
        // DEPTH GATE (corpus gate caught this, 2026-06-28): the original `wp.y != foot.y` fired at ANY vertical
        // node, so a NORMAL +1 diagUp/stepUp the bot could simply jump up (transiently arcProgStall) got its jump
        // suppressed too → regressed corpus-dry-627 (194 → 280; added a diagUp churn). A single jump clears ~1.25,
        // so only a node ≥2 ABOVE the foot is genuinely UNreachable by one jump = a real fell-below worth settling
        // for. Require that depth; a +1 climb keeps its jump. (wp-below-foot descents are handled by below-node-ram.)
        boolean fellBelowMisaligned = BotConfig.walkerFellBelowAlign && wk.arc.progStall
                && (wp.getY() - foot.getY()) >= 2 && !fellBelowNearWater;
        if (BotConfig.walkerDebug && fellBelowMisaligned) {
            LOG.info("[walker] FELL-BELOW-ALIGN wp={},{},{} foot={},{},{} dY={} arcProgStall=true → suppress jump, settle to ground",
                    wp.getX(), wp.getY(), wp.getZ(), foot.getX(), foot.getY(), foot.getZ(), wp.getY() - foot.getY());
        }
        boolean jump = !descendBrake && !fellBelowMisaligned
                // Below-node ram (the +1 desync-ram wedge, see descentRamStuck): the bot drifted 1 above the
                // route and rams an overhang at a node BELOW it. Every jump term here is futile going DOWN (no
                // riser to mount), and the bob-jump (y+1.25) just bonks the overhang AND lifts the foot off the
                // ground — STARVING the grounded-gated descentRamStuck recovery, so the wedge bob-drags ~46t
                // instead of resolving at ~24t (live 2026-06-24 V2 climb replay: y85<->86.25 bob, cur2 frozen
                // 0.640, reroute re-wedged once). Suppress the jump while grounded + ramming + node-below so the
                // body stays planted and descentRamStuck fires on time. A real descent never rams (clean drop),
                // and a jump-to-a-lower-node is a parkour/fall edge (own gates) not this walk-drive — so this
                // kills ONLY the futile overhang bob-jump.
                && !(p.horizontalCollision && p.onGround() && wp.getY() < foot.getY())
                && (stepUpJump || parkourEdge
                    // Freeze-breaker: force a GROUNDED jump straight up the step once a
                    // stepUp/diagUp has rammed the riser past STEPUP_FREEZE_TICKS — the
                    // normal stepUpJump gate (ascendJumpReady) can stay false there (the
                    // bot is a touch off the cross-axis), so without this it bobs forever.
                    || (stepUpFreeze && p.onGround())
                    // Level-riser breaker (y-mislabeled-riser RAM): a WALK-labeled edge whose node is
                    // actually +1 above the grounded foot (the mislabel) — the bot sprinted in level,
                    // dropped onto it, and rams the +1; fire the grounded jump up-and-over AT ONCE
                    // instead of waiting out the off-axis pivot/freeze. Flag-gated; ram confirmed above.
                    || levelRiserJump
                    // !diving: swimColumn is true for any submerged body, so during an
                    // ACTIVE dive the held jump cancelled the sneak-sink exactly —
                    // !underwaterDepthHold: same cancellation for the SUBMERGED HORIZONTAL
                    // traverse after a dive (walk/diag edges through deep water — see the
                    // depth-hold gate beside brakeSneak): the swim-up bob is exactly the
                    // ratchet that hauled the bot back to the surface mid-traverse. A
                    // planned ascent (wp above foot) reads false there, so a real rise
                    // and every surface behavior keep their jump. —
                    // the bot hovered at constant depth (hSpd 0.02, jump+sneak both
                    // down) while the burst storm wound yaw 4.5 turns (mangrove live).
                    || ((swimUp || swimColumn || deepWaterRise) && !cappedHead && !diving && !underwaterDepthHold) || wiggle);
        // TEMP-DIAG (sunken-start deadlock): dump every jump term while submerged & stuck
        if (BotConfig.walkerDebug && p.isInWater() && p.isUnderWater() && wk.stuckTicks > 20 && wk.stuckTicks % 20 == 1) {
            LOG.info("[walker] JUMP-DIAG jump={} swimUp={} swimCol={} dwRise={} capped={} diving={} descBrake={} fbMis={} belowRam={} stepUpJump={} wiggle={} uwT={} wp={},{},{} foot={},{},{}",
                    jump, swimUp, swimColumn, deepWaterRise, cappedHead, diving, descendBrake, fellBelowMisaligned,
                    (p.horizontalCollision && p.onGround() && wp.getY() < foot.getY()), stepUpJump, wiggle, wk.driveLatch.underwaterTicks,
                    wp.getX(), wp.getY(), wp.getZ(), foot.getX(), foot.getY(), foot.getZ());
        }
        if (jump && vetoJumpOnAGraze(wk, world, p)) jump = false;
        if (jump) {
            wk.jumpTag = stepUpJump ? "stepUp" : parkourEdge ? "parkour"     // 其它 = this chain is stale, see Walker#avatarJump
                    : stepUpFreeze && p.onGround() ? "stepUpFreeze" : levelRiserJump ? "levelRiser"
                    : wiggle ? "wiggle" : swimUp ? "swimUp" : swimColumn ? "swimColumn" : deepWaterRise ? "deepWaterRise" : "其它";
        }
        if (parkourEdge) wk.noteParkourTakeoff(world, a, p, jump, foot);  wk.avatarJump(a, jump);  // latch: Walker.parkourTakeoff()
        // Sprint in water ONLY on a FLAT crossing (flatWaterWalk: wp.y==foot.y). The
        // prone swim pose that sprint+forward forces is exactly what a wide open-ocean
        // crossing needs (vanilla's fast swim) — WITHOUT it the bot treads upright in
        // place and STALLS (full-HP naked bot stuck at the spawn bay edge, 0-1 blk over
        // 40s; the deepWaterMax routing fix made the path exist but the controller
        // couldn't execute it). Pitch is held ~horizontal (setXRot→0 above) so the prone
        // swim hugs the surface and doesn't dive; swimUp/swimColumn still hold jump if it
        // dips under, so it can't drown over a long crossing.
        // KEEP no-sprint for CLIMB-OUT nodes (wp.y>foot.y → !flatWaterWalk): there the
        // prone pose can't rise a bank — ROOT CAUSE of the old "stuck bobbing, can't climb
        // out" trace (sprint=true, |dY|≈2.4, bobbing y61 under a y64 node). Treading + jump
        // lets vanilla auto-climb the 1-block ledge out of the water.
        // EXCEPTION — diveUnderCap: a submerged tunnel capped by solid (water under a
        // stone lip) is only ~2 cells tall, and an UPRIGHT body (1.8) rams the ceiling
        // (the lethal stuck: hCol=true, hSpd=0, bobbing into the y+1 stone). Sprinting
        // in water forces the PRONE swim pose (~0.6 tall) which fits under the lip, and
        // with the dive pitch (above) + jump suppressed (cappedHead) it threads the
        // tunnel along the floor instead of bobbing into the cap. So force sprint here
        // even though it's a (downward) vertical node.
        // A parkour ASCEND leap (parkourEdge rising) is the ONE jumped ascend that MUST
        // sprint: a +2 (or gap) parkour clears only with the run-up momentum (Baritone's
        // MovementParkour sprints). The blanket no-sprint-on-needJumpForStep below was tuned
        // for a +1 CARDINAL stepUp (sprint rams the riser there), but it wrongly starved the
        // parkour leap of momentum → it jumped +1 in place and bob-stalled against the step
        // (live: parkourAscend2 frozen, hSpd=0, bobbing y82↔83). Let a parkour ascend sprint.
        boolean parkourAscend = parkourEdge && wp.getY() > foot.getY();
        // Sprint the climb-out APPROACH (swimming toward a bank while still >~1.6 blk lateral
        // from it), not just flat crossings. The no-sprint-on-climb-out rule (water term below)
        // was tuned for the CLOSE press where the prone pose can't rise the bank — but it also
        // killed sprint on the long swim UP TO the bank, so the bot tread-bobbed in at hSpd ~0.06
        // for 2-3 blocks before even reaching it (live 2026-06-24 -597 / -671 water edges, slow
        // swim throughout = the shared root behind the water-edge churn fix I didn't cover).
        // rawClimbPress (latDist²<2.5) still drops sprint for the final rise, so the "stuck
        // bobbing, can't climb out" trace stays fixed; this only speeds the approach swim.
        boolean climbApproach = p.isInWater() && !p.onGround() && wp.getY() > foot.getY()
                && (stepColDx * stepColDx + stepColDz * stepColDz) >= 2.5;
        // LATERAL DRIFT on steep diagonal climbs (live 2026-06-25 replay-0004 -787 ridge): sprint is
        // already dropped on the diagonal STEP-UP itself (sprintAscend is cardinal-only), but the bot
        // still sprints the flat run-up BETWEEN diagonal steps, and that momentum — carried through the
        // next jump arc — pushes it laterally OFF the narrow √2 staircase line. It drifts 1-2 blocks
        // sideways, then EITHER slides 2-3 below an ascent node (→ ascentRamSlide pillar-spam: one climb
        // burned 56 cobble / 292 pillarUp) OR descends a crest early and rams the ridge block sideways
        // (x frozen at -786.70, hCol, bobbing, ~2 s grind per node, totStuck 928). Dropping sprint while
        // the NEXT node is a dry diagonal ascent keeps the body on the line so it tracks the staircase
        // instead of overshooting the corner. Parkour leaps + water climb-approaches still sprint (they
        // need the momentum). Distinct from the A/B-disproven diagonal sprint-bunny-hop (that flipped
        // sprint ON to JUMP a diagonal; this drops it to stop drift). A/B vs the 292-pillar baseline.
        boolean diagAscent = !parkourEdge && !p.isInWater()
                && wp.getX() != foot.getX() && wp.getZ() != foot.getZ() && wp.getY() > foot.getY();
        boolean sprint = !bridging && !steppingOffFall && !steppingOffWaterFall && !diagAscent
                && !lowHpCareful  // low-HP care: sprint is the drift amplifier behind every unplanned fall — at ≤lowHealthCareful HP walk everything (DEATH #3)
                && !hazardAhead   // never carry sprint momentum INTO a lava/hazard cell — in water too (no sneak there, but dropping sprint kills the drift that pushed the swimmer in)
                && !descendBrake && (!lethalNear || parkourEdge) && !steepDescentNear && !deepWaterDriftNear && !descentStepSkip && (!needJumpForStep || parkourAscend || sprintAscend)   // parkourEdge (was parkourAscend): a FLAT leap over an abyss is exactly the case parkourAscend excludes, and lethalNear is only ever true over an abyss — measured 0.1563→0.1400 (no impulse, fell in) vs 0.1232→0.2475 one cell back; narrowed to parkourEdge, NOT loosened to a blanket !lethalNear, so wd.bridgeLethalGapStop's walk-off lip still loses its sprint. Full evidence: this class's javadoc. !descentStepSkip: pointer ran ahead down the staircase (wp >maxDryFall below the grounded foot) — kill sprint so no residual momentum launches the body off the stair edge while sneak (brakeSneak) edge-guards it down. !lethalNear (not !edgeBrake): never sprint NEAR a lethal edge — incl. a planned descent past it — so no drift/overshoot momentum off the lip while sneak is released for the step-down. !deepWaterDriftNear: same, for a deep-water pocket bordering a descent/edge-walk (drift-in bob-stall). Baritone doesn't sprint a jumped CARDINAL ascend (overshoots/bonks) but DOES sprint a parkour leap; a horse auto-walk-up keeps sprint
                // A/B-DISPROVEN (2026-06-06): re-enabling sprint on an aligned ascend (sprintableAscend)
                // regressed hCol 13%→36% / mean hSpd .112→.082 — because the jump fires CLOSE to the riser
                // (ascendJumpReady flatDist≤1.2), the sprint forward-boost rams the riser face HARDER instead
                // of arcing over it. A sprint-jump only clears a step if launched EARLY (before the riser);
                // closing that gap needs an early-jump-timing change, not just flipping sprint on. Kept no-sprint.
                && (!p.isInWater() || flatWaterWalk || diveUnderCap || climbApproach);
        p.setSprinting(sprint);
        // Lily pads sit ON the water plane with a real collision box; the planner
        // deliberately treats the thin shape as passable (a fast prone swim slides
        // under), but the moment the swim slows, the upright treading body rams the
        // pad — hCol=true, hSpd→0 — and the swamp current + anti-stuck bursts spin
        // the bot in place (round28: 7×7 of open water + pads, yaw wound to -994°).
        // Pads are instabreak: punch the one ahead (or overhead) and keep swimming.
        // mayBreak(): a pad is a WALK-edge traversal (the planner treats it passable, so NoBreak
        // does NOT prune it) — this is the ONE fallback a forbidDig bot can reach with a full plan,
        // and it historically checked neither allowBreak NOR NoBreak. Gate it so forbidDig (and the
        // global switch) are both honored — an executor recovery must never out-mutate the planner.
        if (p.isInWater() && p.horizontalCollision && wk.mayBreak()) {
            double bdx = (wp.getX() + 0.5) - p.getX(), bdz = (wp.getZ() + 0.5) - p.getZ();
            double bl = Math.sqrt(bdx * bdx + bdz * bdz);
            BlockPos surf = BlockPos.containing(p.getX(), p.getY() + 1.0, p.getZ());
            BlockPos aheadPad = bl > 1e-3
                    ? BlockPos.containing(p.getX() + bdx / bl, p.getY() + 1.0, p.getZ() + bdz / bl)
                    : surf;
            BlockPos pad = world.isBreakableObstruction(aheadPad) ? aheadPad
                    : world.isBreakableObstruction(surf) ? surf : null;
            // LATERAL-pad rescue: the head-on scan above samples ONLY the cell toward the
            // waypoint at the eye plane, so a pad in an ADJACENT column the BODY overlaps
            // (BlockPos.containing floors the body centre into a different cell) is missed —
            // attack stays false and the floating bot bobs against the pad until a repath
            // (~13.5 s; live -771: frozen at (-770.10,..,317.76), pad at -770,63,318 toward the
            // bank, head-on scan looking at the -771 water cell). When the bot is CONFIRMED
            // rammed (noStepProgressTicks past the gate) and nothing was found ahead, scan the
            // four cells the body's AABB (half-width 0.3) overlaps at the surface (head) cell and
            // break the nearest breakable pad. Flag-gated OFF → byte-identical; isBreakableObstruction
            // is instabreak-by-hand only (pad/surface plant, destroySpeed 0) so a real wall is never
            // touched, and !isInWater / pad-free crossings never reach here.
            if (pad == null && BotConfig.walkerPadRamBreak
                    && wk.stepProg.noStepProgressTicks > PAD_RAM_STALL_TICKS) {
                pad = nearestBodyPad(world, p);
                if (pad != null && BotConfig.walkerDebug)
                    LOG.info("[walker] lateral pad-ram break: pad={},{},{} pos={},{},{} wp={},{},{} noStepProg={}",
                            pad.getX(), pad.getY(), pad.getZ(),
                            String.format(Locale.ROOT, "%.2f", p.getX()),
                            String.format(Locale.ROOT, "%.2f", p.getY()),
                            String.format(Locale.ROOT, "%.2f", p.getZ()),
                            wp.getX(), wp.getY(), wp.getZ(), wk.stepProg.noStepProgressTicks);
            }
            if (pad != null) {
                a.aimAtBlock(pad);
                Walker.avatarDig(a, pad);
            }
        }
        // walkerWallDigFallback (§71, C49): the purest "recovery fires but does nothing"
        // form — a downhill node behind a 1-block dirt wall pins the bot hCol with the
        // stall clock climbing correctly (132+ after the EPS fix) while safetyRepath
        // returns the SAME route and nothing ever tries to DIG the wall (attack=false
        // for the whole 90s churn, pickaxe in hand, allowBreak on). When dry, grounded,
        // wall-pinned and confirmed stalled, punch the waypoint-facing block at head
        // then feet height; the digAimPriority latch (armed below) keeps the crosshair
        // on it through subsequent travel ticks. Default OFF.
        // C55-J3 (§73): the jump-ram loop keeps the bot AIRBORNE (jump->wall-bonk->land->
        // instantly jump again), so an onGround precondition here starves the dig for the
        // whole 393-tick stall — the wall is reachable mid-air; drop the ground gate and
        // key on the collision itself.
        // Survival-run death#1 family: this fallback used to fire regardless of
        // allowBreak — with breaking globally OFF the planner emits walk-only paths,
        // and every wall-pin stall then silently punched through terrain anyway
        // (bare-hand stone, 7.5s+/block: looked like "the bot chose to tunnel").
        // An executor recovery must never exceed the world-mutation authority the
        // planner was given.
        if (BotConfig.walkerWallDigFallback && wk.mayBreak() && !p.isInWater()   // mayBreak(): honor per-goto forbidDig (day6 tunnel), not just the global switch
                && p.horizontalCollision
                && (wk.stuckTicks > 40
                    || (BotConfig.walkerPhysicalStallClock && wk.physStall.stallTicks > 60))
                && !a.breakHeld()) {
            double fdx = (wp.getX() + 0.5) - p.getX(), fdz = (wp.getZ() + 0.5) - p.getZ();
            double fl = Math.sqrt(fdx * fdx + fdz * fdz);
            if (fl > 1e-3) {
                BlockPos headCell = BlockPos.containing(p.getX() + fdx / fl, p.getY() + 1.4, p.getZ() + fdz / fl);
                BlockPos feetCell = BlockPos.containing(p.getX() + fdx / fl, p.getY() + 0.4, p.getZ() + fdz / fl);
                BlockPos tgt = world.isSolid(headCell) ? headCell : world.isSolid(feetCell) ? feetCell : null;
                // §85: the wp-facing probe goes EMPTY-HANDED when the pinning wall's normal
                // is not the wp direction (ultra#2: wall south, wp east; C98-J2 canopy pin:
                // wp a stepDown below, hCol from a side trunk) — hCol says "a wall touches
                // the box" but not WHERE. Fall back to the drive heading, then sweep the
                // four neighbours at head/feet height and punch the first solid. Still
                // gated on the confirmed stall, so open-field travel never reaches this.
                if (tgt == null) {
                    double ryaw = Math.toRadians(p.getYRot());
                    double ddx = -Math.sin(ryaw), ddz = Math.cos(ryaw);
                    BlockPos dh = BlockPos.containing(p.getX() + ddx, p.getY() + 1.4, p.getZ() + ddz);
                    BlockPos df = BlockPos.containing(p.getX() + ddx, p.getY() + 0.4, p.getZ() + ddz);
                    tgt = world.isSolid(dh) ? dh : world.isSolid(df) ? df : null;
                }
                if (tgt == null) {
                    for (int[] nb : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                        BlockPos nh = BlockPos.containing(p.getX() + nb[0], p.getY() + 1.4, p.getZ() + nb[1]);
                        BlockPos nf = BlockPos.containing(p.getX() + nb[0], p.getY() + 0.4, p.getZ() + nb[1]);
                        if (world.isSolid(nh)) { tgt = nh; break; }
                        if (world.isSolid(nf)) { tgt = nf; break; }
                    }
                }
                if (tgt != null) {
                    a.selectTool(tgt);
                    a.aimAtBlock(tgt);
                    Walker.avatarDig(a, tgt);
                    if (BotConfig.walkerDigAimPriority) wk.stickyDig.engage(tgt);
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] wall-dig FALLBACK {},{},{} stuckT={}",
                                tgt.getX(), tgt.getY(), tgt.getZ(), wk.stuckTicks);
                }
            }
        }
        // digAimReassert (walkerDigAimPriority): the travel tick has fully run — drive,
        // recovery, repath all had their say. Now re-hold ONLY the crosshair + attack on
        // the committed dig cell so the interleaved travel tick can't zero vanilla mining
        // progress (the C36-J1 cave-dig replay slowdown). Movement keys stay whatever the
        // travel logic chose: a human holding W+LMB against the wall being dug.
        if (BotConfig.walkerDigAimPriority && wk.stickyDig.pos != null && world.isSolid(wk.stickyDig.pos)) {
            a.selectTool(wk.stickyDig.pos);
            a.aimAtBlock(wk.stickyDig.pos);
            Walker.avatarDig(a, wk.stickyDig.pos);   // prelude's repeat of this cell is dropped
        }
        if (BotConfig.walkerDebug) {
            // [dbgcollide] hard physics evidence for the hill speed-sawtooth: is the
            // bot actually COLLIDING (hitbox snagging a trunk/step face) or just
            // turning? hSpd = horizontal velocity magnitude (sprint≈0.28 b/tick);
            // hCol/minorCol = vanilla collision flags; pos = real feet so we can see
            // dwell (pos frozen while wp/yaw change = stuck, not moving).
            Vec3 dm = p.getDeltaMovement();
            double hSpd = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
            LOG.info("[walker] walk-keys yaw={} wp={},{},{} up={} jump={} sprint={} sneak={} hCol={} minorCol={} hSpd={} pos={},{},{} onG={} attack={} dryDesc={} driveYaw={} aim={} stuckT={}",
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    wp.getX(), wp.getY(), wp.getZ(),
                    a.dbgForwardImpulse(), a.dbgJumping(), p.isSprinting(),
                    a.dbgSneak(),
                    p.horizontalCollision, p.minorHorizontalCollision,
                    String.format(Locale.ROOT, "%.3f", hSpd),
                    String.format(Locale.ROOT, "%.2f", p.getX()),
                    String.format(Locale.ROOT, "%.2f", p.getY()),
                    String.format(Locale.ROOT, "%.2f", p.getZ()),
                    p.onGround(), a.breakHeld(),
                    dryDescent, String.format(Locale.ROOT, "%.0f", driveTargetYaw),
                    aimSrc, wk.stuckTicks);
        }
        return Walker.Step.WALKING;
    }

    /**
     * Count an ascent dead-zone, and give up the leg once re-routing provably cannot help.
     *
     * <p>{@code UNREACHABLE} folds into a re-route, which is right when the next plan can differ.
     * Measured on journey rung 20 (2026-08-18) it could not: a body perched on a 0.16 sole beside the
     * void re-routed for 2400 ticks — the leg's entire budget — and every single re-route returned
     * the identical {@code diagUp} to the identical node, while the footing guard, the stride
     * floor-guard and the recovery hop each correctly declined to move it. Four right answers and no
     * legal move, in total silence, ending as a plain timeout with {@code end=null}.
     *
     * <p>{@link BotConfig#walkerFutileSearchCap} structurally cannot cover it: that counter is gated
     * on the search NOT reaching the goal, and here the search reaches it every time — the plan is
     * fine and the body cannot perform it. That is what the message says, because「no route
     * progress」would be a lie about which half failed.
     *
     * <p>The counter advances only while BOTH the foot cell and the target node are unchanged, so a
     * body that genuinely shifts keeps its full allowance and a transient dead-zone still re-routes.
     *
     * @return a terminal {@code FAILED} step to return from {@code run()}, or null to carry on
     */
    /**
     * LAST WORD on any jump: a body on a graze beside the void does not leave the ground.
     *
     * <p>Every gate above rules on the PLAN; this one rules on the BODY, which is why closing all
     * seven planner leap moves and all three diagonals still left rung 20 falling — the jump that
     * did it was the executor's own step-up: 「跳标=stepUpFreeze 身体=-42.70,102.00,5.30 速度h=0.528
     * 脚底=0.000」. Called after the whole decision chain so no branch can route around it, and it
     * tags the refusal rather than vetoing silently: a jump that does not happen and a jump that was
     * never considered look identical in a log, and this run has already paid for that confusion.
     *
     * <p>A helper rather than five lines inline because {@code run()} is grandfathered at 1262 lines
     * and may shrink, not grow.
     */
    private static boolean vetoJumpOnAGraze(Walker wk, WorldView world, Player p) {
        if (!Walker.grazingBesideTheVoid(world, p)) return false;
        wk.jumpTag = "被虚空脚感否决";
        return true;
    }

    private static Walker.Step noteDeadZone(Walker wk, Player p, BlockPos foot, Move.Edge edge) {
        wk.forceFellOffPath = true;
        BlockPos node = wk.path.get(wk.step);
        if (foot.equals(wk.searchGov.deadZoneFoot) && node.equals(wk.searchGov.deadZoneNode)) {
            wk.searchGov.deadZoneRepeats++;
        } else {
            wk.searchGov.deadZoneFoot = foot;
            wk.searchGov.deadZoneNode = node;
            wk.searchGov.deadZoneRepeats = 1;
        }
        LOG.info("[walker] ascend dead-zone UNREACHABLE move={} node={} foot={} pos=({},{},{}) 连续={} → re-route (task#82)",
                edge.move, node, foot, p.getX(), p.getY(), p.getZ(), wk.searchGov.deadZoneRepeats);
        if (BotConfig.walkerAscendDeadZoneCap <= 0
                || wk.searchGov.deadZoneRepeats < BotConfig.walkerAscendDeadZoneCap) return null;
        wk.lastError = "ascent dead-zone " + wk.searchGov.deadZoneRepeats
                + " times from the same cell — the search keeps returning " + edge.move
                + " to " + node + " and the executor keeps refusing it (foot=" + foot
                + "); the plan is fine and the body cannot perform it";
        return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.NO_PATH,
                wk.lastError, "failed:" + wk.lastError, p.blockPosition());
    }

}
