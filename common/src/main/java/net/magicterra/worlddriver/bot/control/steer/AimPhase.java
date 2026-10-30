package net.magicterra.worlddriver.bot.control.steer;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.*;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.*;
import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.*;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.control.route.PathProjection;
import net.magicterra.worlddriver.bot.control.route.PathSmoothing;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.LookController;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * Mechanical extraction of {@code Navigator#tickInner} (pre-split lines 3505–4181): re-centre, carrot aim, target-yaw
 * EMA + trend camera, anti-spin freeze, dive + pitch. One phase of the per-tick pipeline — see
 * {@link Navigator#tickInner} for the driver and {@link TickCtx} for the shared per-tick locals. Bodies are UNCHANGED
 * from the original tick body except for the {@code w.}/{@code Navigator.} member prefixes; do not restructure here
 * without live/testkit evidence (this file is state-machine surgery).
 */
public final class AimPhase {

    /** How far past a lone +1 step node the aim point sits, along the planned approach. */
    private static final double LOOK_THROUGH = 1.5;

    /** The player's half-width: a heading swept at it can only be walked flush. */
    private static final double BODY_HALF_WIDTH = 0.3;

    /** Squared distance inside which the body counts as standing on a node for {@link #nextNodeHeading}. */
    private static final double ON_NODE_SQ = 0.25;

    /** How far along the heading {@link #unplannedDrop} looks for a missing floor. */
    private static final double DROP_LOOK = 1.5;

    /** Sampling pitch along the path when {@link #reachableAim} walks back from the pursuit point. */
    private static final double REACH_BACK_STEP = 0.25;

    /** How far along the trend heading {@link #headingClear} sweeps the body. */
    private static final double HEADING_CLEAR_DIST = 2.0;

    /**
     * Submerged-descent dive latches (task#96 step B), owned by AimPhase; both cleared on every journey reset via
     * {@link DiveLatches#reset()}.
     */
    public final DiveLatches dive = new DiveLatches();

    /**
     * Riser-ram fold counters (task#96 step B7): the three stepUpFreeze feeders, counted by AimPhase. All self-zero on
     * their own disengage conditions — no journey reset ever touched them.
     */
    public final RamFold ramFold = new RamFold();

    public int surfaceWaterLatch =
            0; // ticks the open-water swim stays latched after a surface bob lifts the foot out of the fluid (rides out
    // the isInWater blink) — read across Aim/Climb/Progress; belongs to a future water-state family

    /** Aim/heading smoothing state — see {@link AimSmoothing}. */
    public final AimSmoothing aimSmooth = new AimSmoothing();
    /**
     * A continuously-sliding aim point {@code CARROT_DIST} blocks ahead along the path (interpolated between nodes),
     * capped by line-of-sight — the pure-pursuit "carrot". Because it's interpolated (not snapped to a discrete node),
     * the bearing to it changes smoothly as the player moves, so the heading doesn't wobble over the cardinal/diagonal
     * staircase. Stops at a wall/turn or an action cell so it never aims through one. Returns {x, z} world coords.
     */
    public double[] carrotPoint(Navigator nav, WorldView world, BlockPos foot, double px, double pz) {
        double remaining = CARROT_DIST;
        // No LOS even to the current node falls back to that node, not to the bot itself: a zero aim
        // vector holds the stale yaw, and a bot that walked off a pulled diagonal kept ramming the
        // corner it had drifted onto (live 2026-09-28, 1389.57,66,-478.70: 38 ticks, bearing to wp -131, yaw -178).
        double cx = px;
        double cz = pz;
        double tx = nav.route.step() < nav.route.path().size()
                ? nav.route.path().get(nav.route.step()).getX() + 0.5
                : px;
        double tz = nav.route.step() < nav.route.path().size()
                ? nav.route.path().get(nav.route.step()).getZ() + 0.5
                : pz;
        for (int i = nav.route.step(); i < nav.route.path().size() && i - nav.route.step() <= CARROT_MAX_NODES; i++) {
            BlockPos node = nav.route.path().get(i);
            double nx = node.getX() + 0.5;
            double nz = node.getZ() + 0.5;
            if (hasPendingEdge(world, nav.route.edgeAt(i))) {
                return new double[] {nx, nz}; // face the action cell
            }
            boolean losOk = ControlConfig.navigatorCarrotBodyLos
                    ? PathSmoothing.losWalkableBody(world, foot, node)
                    : losWalkable(world, foot, node);
            if (!losOk) {
                break; // don't aim past a wall
            }
            double seg = Math.hypot(nx - cx, nz - cz);
            if (seg < 1e-6) {
                tx = nx;
                tz = nz;
                continue;
            }
            if (remaining <= seg) {
                double t = remaining / seg;
                double ax = cx + (nx - cx) * t;
                double az = cz + (nz - cz) * t;
                // Past a corner the chord to the carrot is not the checked foot→node line: live
                // 1489,65,-399 was a pit beside the 1491,66,-400 corner that the chord crossed.
                // At the box's true half-width: with the margin, a body pressed on a trunk failed every chord.
                if (i > nav.route.step() && !PathSmoothing.losWalkableBody(world, px, pz, foot, ax, az, node.getY())) {
                    return new double[] {cx, cz};
                }
                return new double[] {ax, az};
            }
            // The LOS test ramps y from foot to node, so it passes a straight line up a stair flight that
            // takes one jump per riser: live 1543,64,-151 aimed 45° off a three-stepUp flight and fell
            // into the pit beside it (1541,62,-154), then pillared out (5 s).
            if (ControlConfig.navigatorCarrotStopAtRise && node.getY() > foot.getY()) {
                return new double[] {nx, nz};
            }
            remaining -= seg;
            cx = nx;
            cz = nz;
            tx = nx;
            tz = nz;
        }
        return new double[] {tx, tz};
    }

    /** Drop the dive latches and the heading smoothing; the ram folds and the surface latch self-zero. */
    public void reset(ResetScope scope) {
        dive.reset();
        aimSmooth.reset();
    }

    /**
     * The ram-release gate: pinned against a face under a stall clock, or grounded and scraping it for
     * {@code RAM_SCRAPE_TICKS}. Sliding along a face at ~0.05 b/t never trips the stall clocks while the bot presses a
     * heading >60° off its node (live 2026-09-28, 1515.36,75,-237.70: a stepDown node 80° to the side, stuck=0
     * throughout, 11+ ticks of scrape).
     */
    private boolean ramPinned(Navigator nav, LivingEntity p) {
        return p.horizontalCollision
                && (nav.stuckTicks > 40
                        || (p.onGround() && nav.stallDetect.churn.hColRamTicks >= RAM_SCRAPE_TICKS)
                        || (ControlConfig.navigatorPhysicalStallClock && nav.progress.physStall.stallTicks > 60));
    }

    /**
     * How far the body faces off a step-up's aim. The smoothed aim lags a sharp turn together with the body, so on the
     * turn's first tick it reads aligned: R1 1387,76,-510 jumped a diagUp 90° off its raw aim and flew off the notch it
     * stood in (2.5 s). {@code navigatorStepUpRawHeading} also asks the raw aim.
     */
    private static float stepHeadingErr(LivingEntity p, float aimYaw, float targetYaw) {
        float err = Math.abs(angleDiff(p.getYRot(), aimYaw));
        return ControlConfig.navigatorStepUpRawHeading
                ? Math.max(err, Math.abs(angleDiff(p.getYRot(), targetYaw)))
                : err;
    }

    /**
     * The EMA rate for the target heading: the slow trend-camera alpha, or — under {@code navigatorOrbitBreaksAimLag} —
     * the cruise alpha once the bot has spent {@code ORBIT_TICKS} moving with a mid-range heading error. Under tangent
     * drive the bot follows this EMA, and an error that neither closes nor flips while the bot moves is the bot
     * circling its node one cell out: the slow alpha can never catch a bearing that rotates at its own convergence
     * rate. Dry only — water has its own drive heading.
     */
    private float smoothingAlpha(Navigator nav, LivingEntity p, boolean trendCam, float targetYaw) {
        float alpha = trendCam ? (float) ControlConfig.navigatorDescentYawAlpha : YAW_SMOOTH_ALPHA;
        AimSmoothing a = aimSmooth;
        float turn = Float.isNaN(a.orbitLastYaw) ? 0f : angleDiff(a.orbitLastYaw, p.getYRot());
        a.orbitLastYaw = p.getYRot();
        if (!ControlConfig.navigatorOrbitBreaksAimLag || !trendCam || p.isInWater()) {
            a.orbitTicks = 0;
            a.orbitWinding = 0;
            a.orbitBreaking = false;
            return alpha;
        }
        float err = Math.abs(angleDiff(a.smoothTargetYaw, targetYaw));
        boolean moving = p.getDeltaMovement().horizontalDistanceSqr() > ORBIT_MOVE_SQ;
        // A detour turns at its corners and then walks straight; a circling bot turns the same
        // way every tick. Winding in one direction is the signature: a direction change or a
        // standstill ends it, a tick whose error happens to dip low (the raw bearing sweeps as the
        // bot passes the node) merely does not add to it.
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
        if (a.orbitTicks <= ORBIT_TICKS || Math.abs(a.orbitWinding) < ORBIT_WINDING_DEG) {
            return alpha;
        }
        if (GameConfig.navigatorDebug && !a.orbitBreaking) {
            LOG.info(
                    "[navigator] orbit-break: heading error {}° over {} ticks, wound {}° — cruise alpha",
                    String.format("%.0f", err),
                    a.orbitTicks,
                    String.format("%.0f", a.orbitWinding));
        }
        a.orbitBreaking = true;
        return YAW_SMOOTH_ALPHA;
    }

    /**
     * Aim vector at a point {@link #LOOK_THROUGH} past the step node {@code wp} along the direction the plan approaches
     * it from, or null when that direction is vertical or already behind the bot. A lone step (no staircase for the
     * trend to average) was aimed at the node centre itself, and the bearing to a point a block away swings 20-30° a
     * tick as the bot closes on it: the first hundred blocks of R1 turned 3400-5900° of heading against a human's
     * 2060°, most of it under this aim. A point past the node keeps the same line while it stays far enough not to
     * swing.
     */
    private double[] lookThrough(Navigator nav, BlockPos wp, double adx, double adz) {
        BlockPos prev = nav.route.path().get(nav.route.step() - 1);
        double ux = wp.getX() - prev.getX();
        double uz = wp.getZ() - prev.getZ();
        double ul = Math.hypot(ux, uz);
        if (ul < 1e-6) {
            return null;
        }
        ux /= ul;
        uz /= ul;
        double tx = adx + ux * LOOK_THROUGH;
        double tz = adz + uz * LOOK_THROUGH;
        return tx * ux + tz * uz > 0 ? new double[] {tx, tz} : null;
    }

    /**
     * Last node the trend centroid averages: under {@code navigatorCarrotStopAtRise}, the first node above the feet or
     * behind a pending break/place. A flat walk into a stair flight averaged the flight's far nodes and drove 40° off
     * the first riser (live 1543,64,-151, the twin of {@code Navigator.carrotPoint}'s stop); a wade averaged the leg
     * past an undug bank exit and waded 8 blocks along it (1431,62,-355).
     */
    private int centroidEnd(Navigator nav, WorldView world, BlockPos foot, int lastNode) {
        if (!ControlConfig.navigatorCarrotStopAtRise) {
            return lastNode;
        }
        for (int k = nav.route.step(); k < lastNode; k++) {
            if (nav.route.path().get(k).getY() > foot.getY() || hasPendingEdge(world, nav.route.edgeAt(k))) {
                return k;
            }
        }
        return lastNode;
    }

    /**
     * {@code yaw}, or the bearing to {@link #reachableAim}'s point when a trunk, wall or head-height block stands on
     * it. A +1 riser with head room never counts: it is jumped where it stands, and walking round lone steps cost the
     * R1 hills 0.35 s a run while walking round a staircase's cost the cliff 1.3 s. A heading it turns to drops the
     * trend camera in {@code run} the way a pinned recovery does, else the centroid overwrites it and drives on: into
     * the dark oak at R1 1389,112,-584 three times a run, the corner node's bearing in hand.
     */
    private float aroundWall(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, float yaw) {
        if (openHeading(nav, world, p, foot, yaw)) {
            return yaw;
        }
        double[] q = reachableAim(nav, world, foot, p.getX(), p.getZ());
        if (q != null) {
            return (float) Math.toDegrees(Math.atan2(-(q[0] - p.getX()), q[1] - p.getZ()));
        }
        return nextNodeHeading(nav, world, p, foot, yaw);
    }

    /**
     * Swept at the body's own half-width: a margin here stranded the bot in a one-wide doorway
     * (wd.serverStepsDownAPlanItSpentInOneTick) and on a water-side wall (wd.buoyantWall).
     */
    private boolean openHeading(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, float yaw) {
        return headingClear(world, p, foot, yaw, true, BODY_HALF_WIDTH, jumpedRises(nav, foot))
                && !unplannedDrop(nav, world, p, foot, yaw);
    }

    /**
     * The risers {@link #openHeading} may pass (null: every one). Under {@code navigatorAvoidLoneRisers}, off a climb
     * only those at or beside the node that starts one. Nothing jumps a +1 block that is not the path's own step, so a
     * level heading that meets one rams its face: R1 1527,74,-222 and 1526,74,-223 on a level diagonal, 1544,72,-163
     * with the climb four blocks on, every run. On a climb (the next two nodes rise) every riser still passes: walking
     * round the cliff staircase's side steps cost it 2 s a run.
     */
    private List<BlockPos> jumpedRises(Navigator nav, BlockPos foot) {
        if (!ControlConfig.navigatorAvoidLoneRisers) {
            return null;
        }
        List<BlockPos> rises = new ArrayList<>(1);
        for (int k = nav.route.step(); k < Math.min(nav.route.path().size(), nav.route.step() + 3); k++) {
            if (nav.route.path().get(k).getY() > foot.getY()) {
                if (k < nav.route.step() + 2) {
                    return null;
                }
                rises.add(nav.route.path().get(k));
            }
        }
        return rises;
    }

    /** Whether cell {@code c} is one of {@code rises} or next to one, a null list counting as everywhere. */
    private static boolean besideAny(BlockPos c, List<BlockPos> rises) {
        if (rises == null) {
            return true;
        }
        for (BlockPos r : rises) {
            if (Math.abs(r.getX() - c.getX()) <= 1 && Math.abs(r.getZ() - c.getZ()) <= 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bearing to the first of the current node (unless the body is on or past it: a node behind turned the camera a
     * full circle down the R1 steps at 1439,67,-352) and the two after it that {@link #openHeading} lets through, else
     * {@code yaw}. {@link #reachableAim}'s line test wants floor under the body's centre, so a line over a one-deep dip
     * or from a centre already past a ledge finds nothing: at the R1 cliff foot (1387,76,-510, the next node up and
     * east) the heading ran north over the edge into a two-deep pit and dug out for 3-9 s. The current node first: the
     * projection never runs behind it, so short of a corner the tangent already follows the next leg, and at
     * 1388,112,-583 that leg's parallel ran into a dark oak trunk three times a run.
     */
    private float nextNodeHeading(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, float yaw) {
        for (int k = nav.route.step();
                k <= Math.min(nav.route.step() + 2, nav.route.path().size() - 1);
                k++) {
            BlockPos n = nav.route.path().get(k);
            double dx = n.getX() + 0.5 - p.getX();
            double dz = n.getZ() + 0.5 - p.getZ();
            if (k == nav.route.step()
                    && (dx * dx + dz * dz < ON_NODE_SQ || PathSmoothing.beyondNode(nav.route.path(), k, p))) {
                continue;
            }
            float b = (float) Math.toDegrees(Math.atan2(-dx, dz));
            if (openHeading(nav, world, p, foot, b)) {
                return b;
            }
        }
        return yaw;
    }

    /**
     * Whether the heading carries the body to where no corner of it has floor, over a cell none of the next few nodes
     * stands in: a hole the path walks round, not a descent it takes. The tangent cut a terrace corner at R1's start
     * into the one-deep holes at 1572,64,-106 and 1570,63,-107 and jumped out of each for a second. Corners, not the
     * centre: the body stands while any corner has floor, and a centre test read the ledge a node sits on as a drop in
     * every direction.
     */
    private boolean unplannedDrop(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, float yaw) {
        double ux = -Math.sin(Math.toRadians(yaw));
        double uz = Math.cos(Math.toRadians(yaw));
        double hw = 0.3;
        for (double d = 0.5; d <= DROP_LOOK; d += 0.25) {
            double fx = p.getX() + ux * d;
            double fz = p.getZ() + uz * d;
            int x = Mth.floor(fx);
            int z = Mth.floor(fz);
            BlockPos floor = new BlockPos(x, foot.getY() - 1, z);
            if (world.isWater(floor) || world.isWater(floor.above())) {
                continue;
            }
            boolean held = false;
            for (int sx = -1; sx <= 1 && !held; sx += 2) {
                for (int sz = -1; sz <= 1 && !held; sz += 2) {
                    held = world.isSolid(
                            new BlockPos(Mth.floor(fx + sx * hw), foot.getY() - 1, Mth.floor(fz + sz * hw)));
                }
            }
            if (held) {
                continue;
            }
            boolean planned = false;
            for (int k = Math.max(0, nav.route.step() - 1);
                    k < Math.min(nav.route.path().size(), nav.route.step() + 4) && !planned;
                    k++) {
                planned = nav.route.path().get(k).getX() == x
                        && nav.route.path().get(k).getZ() == z;
            }
            if (!planned) {
                return true;
            }
        }
        return false;
    }

    /**
     * The pursuit point if the body at {@code (px, pz)} reaches it in a straight line, else the farthest point back
     * along the path, down to the node behind the bot, that it reaches and that still lies ahead of it; null when none
     * does. The tangent runs parallel to the pulled line, so a bot half a block off that line (a diagonal climb cuts
     * its corner) walked the parallel into a trunk the line itself clears: R1 1483,66,-312, 17 contacts in six runs.
     * Aiming back at the line first walks around it.
     */
    private double[] reachableAim(Navigator nav, WorldView world, BlockPos foot, double px, double pz) {
        PathProjection pr = nav.progress.arc.proj;
        int i = Math.min(pr.aheadNode, nav.route.path().size() - 1);
        if (PathSmoothing.losWalkableBody(
                world,
                px,
                pz,
                foot,
                pr.aheadX,
                pr.aheadZ,
                nav.route.path().get(i).getY())) {
            return new double[] {pr.aheadX, pr.aheadZ};
        }
        double ex = pr.aheadX;
        double ez = pr.aheadZ;
        for (int k = i; k >= Math.max(1, nav.route.step()); k--) {
            BlockPos a = nav.route.path().get(k - 1);
            BlockPos b = nav.route.path().get(k);
            double sx = a.getX() + 0.5;
            double sz = a.getZ() + 0.5;
            double dx = ex - sx;
            double dz = ez - sz;
            double len = Math.hypot(dx, dz);
            for (double d = k == i ? len - REACH_BACK_STEP : len; d > 0; d -= REACH_BACK_STEP) {
                double qx = sx + dx * d / len;
                double qz = sz + dz * d / len;
                if ((qx - px) * dx + (qz - pz) * dz <= 0) {
                    return null; // behind the bot
                }
                if (PathSmoothing.losWalkableBody(world, px, pz, foot, qx, qz, b.getY())) {
                    return new double[] {qx, qz};
                }
            }
            ex = sx;
            ez = sz;
        }
        return null;
    }

    /**
     * The dry trend heading, or the bearing to the node when only the latter has room for the body (under
     * {@code navigatorTrendNeedsRoom}). The centroid averages nodes past a corner and its line can cut it: live
     * 1545,72,-159 drove 162° with the node at 141° and slid along a raised bank for 10 ticks. The node, not the
     * tangent: at a cliff foot the tangent sat on the next leg and drove the bot away from its stepDown for 4 s. Only
     * where nothing in the window rises: before a riser the line always meets it, and swapping the trend for the node
     * there cost 2-8 s per run on the R1 cliff.
     */
    private static float trendWithRoom(WorldView world, LivingEntity p, BlockPos foot, BlockPos wp, float centroidYaw) {
        if (!ControlConfig.navigatorTrendNeedsRoom || headingClear(world, p, foot, centroidYaw)) {
            return centroidYaw;
        }
        float nodeYaw = (float) Math.toDegrees(Math.atan2(-(wp.getX() + 0.5 - p.getX()), wp.getZ() + 0.5 - p.getZ()));
        return headingClear(world, p, foot, nodeYaw) ? nodeYaw : centroidYaw;
    }

    /**
     * Whether the body box widened to {@code navigatorHeadingClearHalfWidth}, carried {@link #HEADING_CLEAR_DIST} along
     * {@code yaw} at the foot's level, meets no solid cell. At the body's own 0.3 a heading with the body's side flush
     * against a trunk passes.
     */
    private static boolean headingClear(WorldView world, LivingEntity p, BlockPos foot, float yaw) {
        return headingClear(
                world,
                p,
                foot,
                yaw,
                false,
                Math.max(BODY_HALF_WIDTH, ControlConfig.navigatorHeadingClearHalfWidth),
                null);
    }

    /**
     * {@link #headingClear}, optionally letting through what a jump takes: a riser, or the second step of a flight
     * behind it, with head room above. What stays in the way is a block at head height (a leaf over the lane) or one
     * that goes on up (a trunk, a wall). Given {@code rises}, only a riser beside one of those nodes passes.
     */
    private static boolean headingClear(
            WorldView world,
            LivingEntity p,
            BlockPos foot,
            float yaw,
            boolean risersPass,
            double hw,
            List<BlockPos> rises) {
        double ux = -Math.sin(Math.toRadians(yaw));
        double uz = Math.cos(Math.toRadians(yaw));
        for (double d = 0.25; d <= HEADING_CLEAR_DIST; d += 0.25) {
            double fx = p.getX() + ux * d;
            double fz = p.getZ() + uz * d;
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    BlockPos c =
                            new BlockPos((int) Math.floor(fx + sx * hw), foot.getY(), (int) Math.floor(fz + sz * hw));
                    if (world.isPassable(c) && world.isPassable(c.above())) {
                        continue;
                    }
                    if (!(risersPass && !world.isPassable(c) && world.isPassable(c.above(2)) && besideAny(c, rises))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The tangent aim never applies on the LAST node. Every earlier node is spent by crossing its plane, so a tangent
     * that carries the bot past it is fine; the last one is spent only by CLOSING to within ~0.67 of its centre, and a
     * tangent is by construction the direction that does not close — it is the previous segment's heading.
     * wd.clientGotoStartsMidAir measured it: a diagonal approach at sprint, yaw 91° off the node bearing, nearest pass
     * 0.8, then 400 ticks of unstuck bursts and repaths around a goal the bot had already reached.
     */
    private boolean onLastNode(Navigator nav) {
        return ControlConfig.navigatorFinalNodeDirectAim
                && nav.route.path() != null
                && nav.route.step() == nav.route.path().size() - 1;
    }

    /**
     * navigatorTangentPursuit: the bare tangent carries no cross-track term, so a bot that is off the path (a smoothed
     * route whose first hop is a diagonal off the start, a shove, a corner cut) walks PARALLEL to it and never rejoins
     * — measured in {@code wd.routeStaysOutOfSkeletonSight}: perp 1.4 held for 20 cells, the bot in the open while the
     * route it was given ran in the wall's shadow. Past {@code PURSUIT_PERP} aim at the path's point ahead instead; on
     * the path the two bearings coincide, so the tuned tangent cruise is unchanged there.
     */
    private float tangentOrPursuit(Navigator nav, LivingEntity p, boolean launch, BlockPos foot) {
        return offPathPursuit(nav, p, launch, foot)
                ? nav.progress.arc.proj.pursuitYaw
                : nav.progress.arc.proj.tangentYaw;
    }

    /**
     * The case {@link #tangentOrPursuit} rejoins in, also what drops the trend camera: on a dry flat walk
     * {@code trendCam} is always on, and under tangent mode its centroid overwrite IS the drive (driveTargetYaw =
     * aimYaw = EMA(targetYaw)), so the pursuit bearing never reached the bot — the centroid of nodes step+2.. is no
     * more a rejoin heading than the tangent is (measured: driveYaw −93 = the far centroid, perp 1.4 held for 20
     * cells). Off the path, drop the trend camera the way {@code recoverySnagAim} does, so the pursuit flows through
     * the same EMA at the fast cruise alpha.
     *
     * <p>Scoped to a LEVEL stretch with a segment ahead: dry, the current and the next node at the foot's Y, not the
     * last node. A step down or a plan's last node has its own tuned handling (navigatorDescentNodeHold, the descent
     * decouple), and the first cut of this — perp alone — walked the bot west and off the doorway in
     * {@code wd.serverStepsDownAPlanItSpentInOneTick} and lost an ore in {@code wd.serverMineHarvestBuried}; both green
     * with the flag off, both green again with this scope. A buoyant bot rides off its nodes legitimately.
     */
    private boolean offPathPursuit(Navigator nav, LivingEntity p, boolean launch, BlockPos foot) {
        if (!ControlConfig.navigatorTangentAim || !ControlConfig.navigatorTangentPursuit || launch || p.isInWater()) {
            return false;
        }
        if (nav.route.path() == null || nav.route.step() + 1 >= nav.route.path().size()) {
            return false;
        }
        if (nav.route.path().get(nav.route.step()).getY() != foot.getY()
                || nav.route.path().get(nav.route.step() + 1).getY() != foot.getY()) {
            return false;
        }
        return nav.progress.arc.proj.perp > PURSUIT_PERP;
    }

    /**
     * The foot the aim judges by. At a +1 jump's apex the foot already reads the node's level, so every level-walk aim
     * (carrot, tangent, trend centroid) took over mid-air and steered past the step: live 1389,92,-526.7 drove at the
     * next leg's 180° with the node at 74° and rammed a wall 30 ticks. Until it lands, an ascent's aim is judged from
     * its take-off level (under {@code navigatorCarrotStopAtRise}). The aim only: {@code run} goes back to the real
     * foot once the aim is chosen, or the step-up pivot found the node still above at the jump's apex and cut forward
     * drive mid-air.
     */
    private BlockPos ascentAimFoot(Navigator nav, LivingEntity p, BlockPos foot, BlockPos wp) {
        if (!ControlConfig.navigatorCarrotStopAtRise
                || p.onGround()
                || p.isInWater()
                || p.onClimbable()
                || nav.route.path() == null
                || nav.route.step() <= 0
                || nav.route.step() >= nav.route.path().size()
                || wp == null) {
            return foot;
        }
        return wp.getY() == foot.getY()
                        && nav.route.path().get(nav.route.step() - 1).getY() < wp.getY()
                ? foot.below()
                : foot;
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    public TickStep run(Navigator nav, TickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (TickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = ascentAimFoot(nav, p, cx.frame.foot, cx.edges.wp);
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
        // swinging rapidly left and right, pathChart maxYawErr 172°). In water, judge the vertical maneuver
        // by the CONTINUOUS Y gap to the waypoint with a bob-proof threshold (a flat or
        // gently-graded swim stays on the carrot; only a genuine dive / bank-climb ≥~1.5
        // aims at the block). On land the integer test is exact and unchanged.
        double wpAimDy = (wp.getY() + 0.5) - p.getY();
        boolean aimAtWaypoint = (p.isInWater() ? Math.abs(wpAimDy) > 1.5 : wp.getY() != foot.getY()) || parkourEdge;
        // Re-centre recovery on a stuck flat walk: a 1-wide channel needs the
        // player centred on the lane axis or the off-centre hitbox snags a corner
        // and wedges (the look-ahead carrot aims diagonally, so it never centres
        // and the bot grinds the boundary). When genuinely stuck, steer to the
        // centre of the last confirmed on-spine node (the cell we came from):
        // that pulls the bot straight onto the lane axis, after which the carrot
        // — aimed at the next node, same axis — is a clean straight push. Only
        // fires when stuck (normal open walking never is), so it can't reverse a
        // healthy run.
        // Cross-axis re-centre on a stuck flat walk: a 1-wide channel flush against
        // a wall needs the bot held on the lane axis or the off-centre hitbox
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
        double recX = 0;
        double recZ = 0;
        // Guard-pin stall clock: while the stride floor-guard pins the bot at an unplanned
        // void edge, its fire ticks DECREMENT stuckTicks by design (the pin is a hold, not a
        // stall, and recovery bursts at a lip have killed — Navigator wrapper) — so every
        // stuck-gated recovery branch below starves and the pin livelocks (r29 StepTwo:
        // st0/117 at the dogleg edge, 1300 ticks). The guard's own pinStreak IS the stall
        // clock for that state: it accumulates across fire/hold ticks by design. Use it as
        // an alternate trigger while the pin is latched — the branches it enables (reCentre /
        // nodeAim) steer along the corridor, which is exactly what releases the pin.
        int guardPinClock = nav.safety.guardSneakLatch ? nav.safety.guardPinStreak : 0;
        if (!aimAtWaypoint && (nav.stuckTicks > 5 || guardPinClock > 5) && nav.route.step() > 0) {
            BlockPos sp = nav.route.path().get(nav.route.step() - 1);
            boolean onSpine = foot.getX() == sp.getX() && foot.getZ() == sp.getZ();
            if (!onSpine && losWalkable(world, foot, sp)) {
                double rcx = (sp.getX() + 0.5) - p.getX();
                double rcz = (sp.getZ() + 0.5) - p.getZ();
                // Only re-centre toward the previous node when it is NOT behind us:
                // once the bot has progressed past sp, aiming at its centre points
                // backward and steers the bot the wrong way (a stuck spot then drags
                // the heading ~180° around — the residual backward episode the slew
                // clamp turned from a flip into a slow reversal). Gate on the dot
                // product with the forward (toward-waypoint) direction so reCentre only
                // fires for genuine lateral drift, never to reverse. Backward case
                // falls through to the carrot, which keeps pushing forward.
                double fwx = (wp.getX() + 0.5) - p.getX();
                double fwz = (wp.getZ() + 0.5) - p.getZ();
                // NORMALIZED backward gate: the raw dot's >=0 cut also rejected the
                // near-PERPENDICULAR re-centre — which is exactly the corner-resnag this
                // branch exists for (bridge bypass trio: the bot pinned on the barrier's
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
        double adx;
        double adz;
        // aimSrc: which branch of the aim decision tree owns the heading this tick —
        // printed in walk-keys so a pinned-heading autopsy (yaw refusing to turn toward
        // the bearing, the T-1 54s dry tear) reads the controlling channel directly
        // instead of re-deriving it from the tree's inputs.
        String aimSrc;
        if (aimAtWaypoint) {
            aimSrc = "wp";
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
            // Dry +1 staircase camera-spin fix: once the bob carries the bot
            // horizontally ON TOP of the close +1 step node, that node's bearing flips
            // ±180° each tick (it's now beside/behind the foot) and a multi-step stair
            // accumulates a full 360°+ camera swing (live 2026-06-15 z1864 yawRange 405°)
            // even though the climb keeps progressing. When within ~1 block of the step
            // node horizontally AND a further node exists, aim at the NEXT node instead —
            // a genuine forward-up heading (no freeze, so it can't pin a wrong direction
            // like a held yaw does, and the buoyant water-exit mount whose next node is a
            // forward ledge walk only steadies). Land + gentle +1 only; parkour leaps
            // (precise launch aim) and steeper jumps keep the exact-waypoint aim.
            // NOTE: extending this trend-average to DESCENTS (the live 2026-06-21 camera spin while descending a slope:
            // close-node carrot-swing winds the yaw 600°+ down a steep slope) was A/B-DISPROVEN on
            // the deterministic descentYawArena — every variant was WORSE than no fix (off=1050°
            // thrash/242tk; descent-trend=1559°/569tk i.e. 2.3× SLOWER; parkour-inclusive=1529° @
            // 6.4°/tick). A steep descent routes through fall/parkour-descend nodes the trend
            // excludes, and where it engages it flickers between trend- and exact-aim, ADDING thrash.
            // The descent spin needs a different mechanism, not this aim swap. Up-steps only.
            if (!p.isInWater()
                    && !parkourEdge
                    && (wp.getY() - foot.getY()) == 1
                    && nav.route.step() + 1 < nav.route.path().size()) {
                // Path-trend averaging (the root-cause fix). Sum the UNIT direction of
                // each upcoming segment while they stay consistent (<60° turn), so a diagonal
                // staircase's ±30° per-step alternation collapses to the steady diagonal and the
                // heading holds inside the pivot tolerance (no per-step drive cut → no sawtooth).
                // Stopping at a real bend keeps it from aiming across a corner (the single-far-node
                // aim that did was reverted). Also subsumes the old close-node camera-spin swap:
                // once the bob carries the bot onto the close +1 node, the trend still points up
                // the stair instead of flipping ±180°. Forward-only (dot>0) never reverses.
                double tx = 0;
                double tz = 0;
                BlockPos prev = foot;
                int last = Math.min(
                        nav.route.step() + STAIR_TREND_LOOKAHEAD,
                        nav.route.path().size() - 1);
                for (int k = nav.route.step(); k <= last; k++) {
                    BlockPos nd = nav.route.path().get(k);
                    double sx = nd.getX() - prev.getX();
                    double sz = nd.getZ() - prev.getZ();
                    double sl = Math.sqrt(sx * sx + sz * sz);
                    if (sl < 1e-6) {
                        prev = nd;
                        continue;
                    }
                    sx /= sl;
                    sz /= sl;
                    if ((tx != 0 || tz != 0) && (sx * tx + sz * tz) / Math.sqrt(tx * tx + tz * tz) < 0.5) {
                        break; // segment turns >60° from the trend → stop
                    }
                    tx += sx;
                    tz += sz;
                    prev = nd;
                }
                // Only a GENUINE diagonal run (≥2 consistent horizontal nodes accumulated) may
                // override the aim — a near-vertical climb (pillar-up / a tight dig-staircase
                // mount) sums to ~0 horizontal, so its noisy trend is rejected and the precise
                // immediate-node aim is kept (else the summit pillar + bank-dig plateau mount
                // mis-aim). Forward-only (dot>0) never reverses.
                if (Math.sqrt(tx * tx + tz * tz) >= 2.0 && (tx * adx + tz * adz) > 0) {
                    adx = tx;
                    adz = tz;
                    aimSrc = "trend";
                } else if (nav.route.step() > 0) {
                    double[] lt = lookThrough(nav, wp, adx, adz);
                    if (lt != null) {
                        adx = lt[0];
                        adz = lt[1];
                        aimSrc = "lookThrough";
                    }
                }
            }
        } else if (reCentre) {
            aimSrc = "recentre";
            adx = recX;
            adz = recZ;
            // Pin-clock threshold 5, not APPROACH_NODE_AIM_TICKS: guardPinStreak zeroes on every
            // unpinned tick between pin cycles, so a 13-consecutive bar loses the race against
            // same-cell plug arming (cell-sticky, survives cycles) — r32 spent 2 dirt before
            // nodeAim ever engaged. 6 pinned ticks is already a held pin (hysteresis alone is 8).
            // losWalkable seatbelt on the pin clause (r33: bypass trio + detourCheap all wedged at
            // the platform's east reconvergence corner, knife-edged at (16.3,2.7) to FAILED):
            // nodeAim is a straight-line aim, blind to void — engaged at a corner pin it aims
            // diagonally across the missing corner cells and re-pins forever. Only engage when
            // the straight line to the node is walkable (same check reCentre has always had);
            // otherwise fall through to the carrot, which follows the path cell-by-cell. The
            // plain stuckTicks clause keeps its historical unguarded form.
        } else if (nav.stuckTicks > APPROACH_NODE_AIM_TICKS || (guardPinClock > 5 && losWalkable(world, foot, wp))) {
            aimSrc = "nodeAim";
            // FLAT-node carrot-orbit fallback (see APPROACH_NODE_AIM_TICKS). On a flat walk the bot
            // follows the look-ahead carrot; at a turn/corner node the carrot points ~60° off the close
            // node and the bot orbits it at ~0.75 b without ever closing the within-gate (live FREEZE-
            // DIAG: aimAtWp=false, driveF=1, fwdComp>0, hSpd~0.07, cur2 frozen, 26-80 ticks). reCentre
            // (previous node) and the strafe (cross-axis) don't pull onto the IMMEDIATE node, so the
            // orbit only breaks on the slow wedge timer. Aim straight at the fixed node centre so the
            // bot closes onto it (within / clean crossing → step advances) instead of circling the carrot.
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
        } else {
            aimSrc = "carrot";
            double[] c = carrotPoint(nav, world, foot, p.getX(), p.getZ());
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
            if (p.isInWater() && !Float.isNaN(aimSmooth.lastCarrotBearing)) {
                float db = angleDiff(aimSmooth.lastCarrotBearing, carrotBearing);
                if (Math.abs(db) > 10f) {
                    int sign = db > 0 ? 1 : -1;
                    if (aimSmooth.lastCarrotBearingSign != 0 && sign != aimSmooth.lastCarrotBearingSign) {
                        aimSmooth.yawThrashTicks = Math.min(aimSmooth.yawThrashTicks + 4, 12);
                    }
                    aimSmooth.lastCarrotBearingSign = sign;
                }
            } else {
                aimSmooth.lastCarrotBearingSign = 0;
            }
            aimSmooth.lastCarrotBearing = carrotBearing;
            if (aimSmooth.yawThrashTicks > 0) {
                aimSmooth.yawThrashTicks--;
            }
            boolean climbAhead = false;
            for (int q = nav.route.step();
                    q < Math.min(nav.route.path().size(), nav.route.step() + WATER_FAR_AIM_LOOKAHEAD + 1);
                    q++) {
                if (nav.route.path().get(q).getY() > foot.getY()) {
                    climbAhead = true;
                    break;
                }
            }
            if (p.isInWater() && aimSmooth.yawThrashTicks >= WATER_YAW_THRASH_SCORE && !climbAhead) {
                BlockPos far = nav.route
                        .path()
                        .get(Math.min(
                                nav.route.step() + WATER_FAR_AIM_LOOKAHEAD,
                                nav.route.path().size() - 1));
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
        // navigatorDryReanchor: the dry repath-rechurn breaker (REGRESSION.md §21). When a move has failed and
        // the foot is FAR off the current node (dist² > DRY_REANCHOR_OFFPATH_SQ) with a sustained stall
        // (stuckTicks > DRY_REANCHOR_STUCK) on dry land, override the carrot/tangent/node aim with a FIXED
        // aim at the last cleanly-passed node centre (path[step-1]). That fixed point's bearing barely moves
        // as the bot closes, so the node-orbit yaw-thrash that paces the repath churn collapses and the bot
        // walks deterministically back ONTO the path before resuming — breaking the amplifier common to every
        // heterogeneous wedge. Excludes water (the in-water anti-spin machinery owns that) and the very first
        // node (no prior anchor).
        if (ControlConfig.navigatorDryReanchor
                && !p.isInWater()
                && nav.route.step() > 0
                && nav.route.step() < nav.route.path().size()
                && nav.stuckTicks > DRY_REANCHOR_STUCK) {
            BlockPos cn = nav.route.path().get(nav.route.step());
            double cnx = (cn.getX() + 0.5) - p.getX();
            double cnz = (cn.getZ() + 0.5) - p.getZ();
            // ram-while-facing-wrong extension REVERTED 2026-06-29 (§25): adding an `|| (hCol && |yawErr|>90)`
            // trigger made the live -671 diagDown stall MUCH worse (oscillating limit cycle, totStuck 6009 vs
            // 509 slow-recover) — anchoring BACK to step-1 on a CLOSE ram just bounces the bot back and forth
            // (pull to step-1 → re-approach → re-ram → anchor), the same oscillation that killed the wall-corner node
            // aim (§13) and the hCol-gate (§16). A close ram is NOT fixable by aim-back. Keep the FAR-off-path-only
            // trigger (corpus-validated net-positive, §22-24); the close diagDown-ram is a separate open problem.
            if (cnx * cnx + cnz * cnz > DRY_REANCHOR_OFFPATH_SQ) {
                BlockPos anchor = nav.route.path().get(nav.route.step() - 1);
                adx = (anchor.getX() + 0.5) - p.getX();
                adz = (anchor.getZ() + 0.5) - p.getZ();
            }
        }
        double aim2 = adx * adx + adz * adz;
        boolean climbAim = aimAtWaypoint && p.isInWater() && wpAimDy > 0.5;
        boolean waterThrash = p.isInWater() && nav.progress.stepProg.noStepProgressTicks > WATER_YAW_HOLD_STALL;
        double aimDeadzone = climbAim || waterThrash ? CLIMB_AIM_DEADZONE_SQ : YAW_DEADZONE_SQ;
        float targetYaw = aim2 < aimDeadzone
                ? p.getYRot() // essentially on the aim column — hold heading, don't thrash atan2
                : (float) Math.toDegrees(Math.atan2(-adx, adz));
        // A launch into a leap (parkour or MLG fall) must SNAP the heading even when
        // smoothLook is on — you can't course-correct mid-air, so a lagged launch sends
        // the bot off at an angle (drifts off a narrow landing). Launches bypass both the
        // EMA and the slew cap; everything else is smoothed + capped.
        boolean launch = parkourEdge || steppingOffFall || steppingOffWaterFall;
        // Phase-2 (navigatorTangentAim): replace the immediate-node bearing with the bob-immune path TANGENT
        // ahead of the projection. The node bearing reverses ~180° on a node overshoot — the backward-jump /
        // repeated side-to-side hopping / facing-the-wall dead-corner stall; the tangent never flips, so the camera
        // (via
        // smoothTargetYaw/aimYaw below) and the captured descentNodeYaw both follow the path smoothly. Skipped
        // for launches (a leap snaps at its landing node) and inside the aim dead-zone (hold heading). The
        // projector ran this tick (call site gates on the same flag).
        // EXCEPTION — an ASCENT to the immediate node (a dry +1 stepUp/diagUp riser). A step-up mounts by
        // driving STRAIGHT at the riser node + auto/stepUpJump; the tangent points along the path PAST the
        // riser (often across a terrain corner), which steers the bot off-axis so it bonks the riser edge
        // and never mounts (live P2: a +1 riser at -750 churned with perp drifting to 4+). For an above-foot
        // immediate node, keep the legacy node bearing so pivotForStepUp/stepUpJump align onto the block —
        // the same reason the buoyant water-mount (buoyantClimbPress) keeps its column bearing, not the trend.
        // ...and never while a RECOVERY branch owns the aim AND the bot is PINNED — by a
        // wall (horizontalCollision) or by the stride floor-guard's void-edge sneak-pin
        // (guardSneakLatch). The tangent presumes the bot is ON the lane; a pinned
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
        boolean pinnedRecovery =
                (p.horizontalCollision || nav.safety.guardSneakLatch) && (reCentre || "nodeAim".equals(aimSrc));
        boolean walkedRound = false;
        if (ControlConfig.navigatorTangentAim
                && !launch
                && !pinnedRecovery
                && !onLastNode(nav)
                && aim2 >= aimDeadzone
                && nav.route.path() != null
                && nav.route.step() < nav.route.path().size()
                && nav.route.path().get(nav.route.step()).getY() <= foot.getY()) {
            float tangent = tangentOrPursuit(nav, p, launch, foot);
            walkedRound = (targetYaw = aroundWall(nav, world, p, foot, tangent)) != tangent;
        }
        // navigatorRamNodeAimRelease (§80): dry wall-pin with the drive heading >60° off the
        // current-node bearing — the deadzone hold-heading band (A: aim2 inside the deadzone
        // holds a stale yaw that a stepDown node's within-gate never accepts) or a reversed
        // switchback tangent (B: tangent -180° vs node bearing 14°) steers the bot INTO a wall while the node sits
        // elsewhere.
        // Under the confirmed-stall gate, snap targetYaw back to the current-node bearing so
        // both the drive (descentNodeYaw capture below) and the camera follow. Aims at the
        // CURRENT node under a collision gate — not the §25 step-1 reanchor that bounced.
        boolean ramReleaseAim = false;
        if (ControlConfig.navigatorRamNodeAimRelease
                && !p.isInWater()
                && ramPinned(nav, p)
                && nav.route.path() != null
                && nav.route.step() < nav.route.path().size()) {
            BlockPos rn = nav.route.path().get(nav.route.step());
            double rndx = (rn.getX() + 0.5) - p.getX();
            double rndz = (rn.getZ() + 0.5) - p.getZ();
            if (rndx * rndx + rndz * rndz > 1e-6) {
                float rnb = (float) Math.toDegrees(Math.atan2(-rndx, rndz));
                if (Math.abs(angleDiff(p.getYRot(), rnb)) > 60) {
                    targetYaw = rnb;
                    ramReleaseAim = true;
                    if (GameConfig.navigatorDebug) {
                        LOG.info(
                                "[navigator] ram-release: node-aim {} (was yaw={}) node={},{},{} stuckT={}",
                                String.format(Locale.ROOT, "%.0f", rnb),
                                String.format(Locale.ROOT, "%.0f", p.getYRot()),
                                rn.getX(),
                                rn.getY(),
                                rn.getZ(),
                                nav.stuckTicks);
                    }
                } else if (reCentre) {
                    // CORNER-SNAG branch: the node bearing is within 60° of the pressed yaw —
                    // i.e. the node sits BEHIND the same wall face and aiming at it keeps
                    // ramming (bridge bypass trio: yaw −89 vs node bearing −72 into the
                    // barrier's west face, every release valve gated out by the small
                    // angle, 1200-tick pin). The spine-rejoin bearing (reCentre) is the
                    // one direction that clears the corner — under THIS confirmed-stall
                    // gate it is persistent, so the aim EMA actually converges on it
                    // (the tree's own intermittent reCentre ticks were smoothed away).
                    targetYaw = (float) Math.toDegrees(Math.atan2(-recX, recZ));
                    ramReleaseAim = true;
                    if (GameConfig.navigatorDebug) {
                        LOG.info(
                                "[navigator] ram-release: corner-snag spine-aim {} (was yaw={}) stuckT={}",
                                String.format(Locale.ROOT, "%.0f", targetYaw),
                                String.format(Locale.ROOT, "%.0f", p.getYRot()),
                                nav.stuckTicks);
                    }
                }
            }
        }
        // ── Overland camera/movement decouple (anti-spin) — see DESCENT_CAM_FAR_DIST ─────────
        // Point the CAMERA at a stable trend heading (no spin) while the MOVEMENT keeps driving the
        // immediate node (driveTargetYaw, below). Capture the immediate-node heading (the carrot/node
        // bearing) BEFORE re-aiming the camera at the trend — that captured heading drives the bot.
        float descentNodeYaw = targetYaw;
        // GENERALISED from descents to FLAT + descending dry overland travel: a live 2026-06-21
        // winding-by-bucket diagnosis showed the trend camera had ALREADY smoothed descending nodes
        // (dryDesc=true: 2.5 turns over the journey) but 90% of the residual spin sat in the dry
        // NON-descending nodes (flat + ascending steps on the same hill, 7.8 turns) where the decouple
        // wasn't engaging — a switchback's flat segments swing the per-node bearing exactly like its
        // descending segments. Driving the bot off the captured node heading keeps navigation
        // byte-identical; only the camera is trend-averaged. ASCENDING (wp.y > foot.y) is EXCLUDED:
        // a dig+climb-mount or pillar-up needs the exact target heading, and trend-aiming it regressed tallbankdigclimb
        // (the bot couldn't mount the bank).
        // Launches ride descentDecoupleLaunches (ascending leaps slew safely via commandMove).
        boolean dryDescent = ControlConfig.descentCameraDecouple
                && !p.isInWater()
                && !steppingOffWaterFall
                && ((!launch && wp.getY() <= foot.getY()) // flat or descending walk
                        || (launch && ControlConfig.descentDecoupleLaunches)); // any dry launch
        // Water head-shake fix: extend the trend camera to FLAT water swimming. A buoyant bot
        // crossing open water swims slowly (~1.4 b/s); near a waypoint the immediate-node bearing flips
        // ±170°/tick and the camera — coupled to it in water — swings, the visible head-shake (live
        // 2026-06-21 crossing: camera mean|dyaw|=3°/tick with ±170° driveYaw flips in the slow bursts).
        // Averaging the look-ahead window into a steady trend kills it, exactly as on dry land. EXCLUDED:
        // climb-out (wp above foot — the bank mount needs the exact column heading) and swimDown dives
        // (they aim precisely), so the water-climb / dive arenas stay byte-unchanged. Unlike dry land the
        // bot's movement stays COUPLED to the camera trend (driveTargetYaw=aimYaw in water, below): on an open
        // crossing swimming toward the trend is correct, whereas driving the raw flipping node would
        // re-introduce a swim-back-and-forth.
        // The Y gate is bob-tolerant (+1): a buoyant bot bobs foot y±1 against a surface node, so a
        // strict wp.y <= foot.y FLICKERS the trend on/off each bob and the camera still snaps to the
        // flipping node on the off-ticks (live: yaw==driveYaw on alternating ticks). +1 keeps the trend
        // latched across the bob while still excluding a real climb-OUT (+2 bank); a +1 node it might
        // include is harmless — buoyantClimbPress (below) drives that mount off the column regardless.
        // isInWater is LATCHED across the surface bob: at the bob apex the foot lifts out of the
        // fluid for ~1 tick (isInWater false) though the bot is still swimming the open crossing.
        // Hold "in water" for WATER_SURFACE_LATCH_TICKS after the blink so the trend (and the
        // smoothed water drive) survives it instead of snapping to aimYaw for one tick.
        if (p.isInWater()) {
            surfaceWaterLatch = WATER_SURFACE_LATCH_TICKS;
        } else if (surfaceWaterLatch > 0) {
            surfaceWaterLatch--;
        }
        boolean inWaterLatched = p.isInWater() || surfaceWaterLatch > 0;
        boolean flatWaterTrend = ControlConfig.descentCameraDecouple
                && inWaterLatched
                && !launch
                && wp.getY() <= foot.getY() + 1
                && !(edge != null && edge.move != null && edge.move.startsWith("swimDown"));
        // WALL-PINNED RECOVERY OWNS THE AIM END-TO-END (bridge bypass trio, stage 3 of the same
        // disease). The tree's reCentre bearing (and §80's ram-release) write targetYaw ABOVE, but
        // on a dry FLAT walk trendCam is always true, and its centroid overwrite below replaced
        // targetYaw every tick — r26 pin-window: 10 consecutive as=recentre ticks (spine bearing
        // +19°) with the drive frozen at y-90, which is EXACTLY the lookahead centroid's bearing
        // (nodes (13,1),(14,0),(26,0) → centroid (18.2,0.8), atan2 = −90.7°). Under tangent mode
        // driveTargetYaw=aimYaw=EMA(targetYaw), so the centroid — not the recovery — drove the bot
        // into the barrier face for 1200 ticks; every aim-layer fix upstream was label-only, the
        // same way the tangent override was before its reCentre guard. While a wall-pinned recovery
        // owns the aim, drop trendCam entirely: the centroid overwrite yields AND the EMA switches
        // from the slow descent alpha (0.08 — a 109° recovery swing would lag ~2 s) to the fast
        // cruise alpha (0.5). Scoped to hCol exactly like the tangent guard (r22: an unconditional
        // yield diverted transient wobbles and walked a ridge bot off to bedrock).
        // Dry-only: a floating bank-ram has its own recovery set (bankFollow / floatingBankBob),
        // so the water trend stays byte-identical. Two pin flavours:
        //  - WALL-pin (hCol): reCentre / §80 own the aim (bridge bypass trio, r26).
        //  - VOID-pin (guardSneakLatch, hc=false): the guard holds the bot at an unplanned
        //    lip the centroid keeps steering it over (StepTwo dogleg r29: centroid (15.75,3.0)
        //    dead east, plan detours north; with allowPlace the bot causeway-plugged its own
        //    shortcut — 6 dirt). The pin-clock-enabled reCentre/nodeAim branches above give
        //    the corridor bearing; dropping trendCam here lets it reach the drive via the EMA.
        //    (Swapping the DRIVE source instead was r30/r31-DISPROVEN — see Drive.)
        boolean recoverySnagAim = !p.isInWater()
                && ((p.horizontalCollision && (reCentre || ramReleaseAim))
                        || (nav.safety.guardSneakLatch && (reCentre || "nodeAim".equals(aimSrc))));
        boolean trendCam = (dryDescent || flatWaterTrend)
                && !recoverySnagAim
                && !walkedRound
                && !offPathPursuit(nav, p, launch, foot);
        // Smoothed water DRIVE: the raw immediate-node bearing flips ±180° when the slow buoyant bot
        // overshoots a node, so driving it raw makes the bot swim-wobble (live: 52% path efficiency,
        // and the bot suddenly turning away from the target). A light EMA damps the per-tick flip while still tracking
        // the node. WATER
        // ONLY: extending this EMA to dry descent was A/B-DISPROVEN in descentYawArena (raw backSteps=42
        // winding=211° → ema backSteps=54 winding=370° — the dry bot has traction and needs the precise
        // node bearing; lagging it makes it overshoot/correct MORE). Dry back-hop's real fix is a
        // step-pointer advance, not drive-smoothing (override was also disproven — both wedge/worsen).
        if (flatWaterTrend) {
            // DRIVE the path-following CARROT (CARROT_DIST ahead), not the raw immediate-node bearing.
            // A slow buoyant bot drifts off-axis, and the immediate node's bearing rotates faster the
            // closer it gets — within ~2 blocks it sweeps and flips ±180° as the bot crosses it, so
            // the EMA still swings ±55°/tick and the swim wobbles/crawls (live: open-water hSpd
            // collapses 0.078→0.02 at every node, the circling-around-a-node churn). The carrot is a STABLE far
            // heading (small angular sensitivity) that still ROUNDS corners (it walks the path) and
            // STOPS at a wall (carrotPoint's losWalkable break) — so it can't ram a divider the way
            // driving the far CENTROID did (waterFarAimBankCorner).
            // Bob-stable LOS foot: an UP-bob lifts foot.y into the air block ABOVE the water surface,
            // where carrotPoint's per-cell losWalkable (needs floor-solid / water / climbable) fails on
            // every sample and collapses the carrot onto the bot — the drive then loses its forward
            // heading and the swim stalls (live -1676/-1678: carrot dead, driveYaw frozen, totStuck
            // 200+). Clamp the ray's foot DOWN to the current node's surface level so it samples water,
            // not the bob's air gap.
            BlockPos losFoot = foot.getY() > wp.getY() ? new BlockPos(foot.getX(), wp.getY(), foot.getZ()) : foot;
            double[] cp = carrotPoint(nav, world, losFoot, p.getX(), p.getZ());
            double cdx = cp[0] - p.getX();
            double cdz = cp[1] - p.getZ();
            if (GameConfig.navigatorDebug && cdx * cdx + cdz * cdz <= 1.0) {
                LOG.info(
                        "[navigator] carrot-collapse foot={} wp.y={} posY={} losRaw={} losClamp={}",
                        foot.getY(),
                        wp.getY(),
                        String.format("%.2f", p.getY()),
                        losWalkable(world, foot, wp),
                        losWalkable(world, losFoot, wp));
            }
            // Drive source: the carrot ahead, or — when it has collapsed onto the bot (a wall / path
            // end) — the immediate node bearing as a fallback.
            float driveSrcYaw =
                    cdx * cdx + cdz * cdz > 1.0 ? (float) Math.toDegrees(Math.atan2(-cdx, cdz)) : descentNodeYaw;
            if (Float.isNaN(aimSmooth.smoothWaterDriveYaw)) {
                aimSmooth.smoothWaterDriveYaw = driveSrcYaw;
                aimSmooth.waterDriveRejectStreak = 0;
            } else {
                float turn = angleDiff(aimSmooth.smoothWaterDriveYaw, driveSrcYaw);
                if (Math.abs(turn) <= WATER_DRIVE_MAX_TURN) {
                    // Gradual (real) turn — track it; the EMA damps the carrot's re-plan / wall jumps.
                    aimSmooth.smoothWaterDriveYaw =
                            angleDiff(0f, aimSmooth.smoothWaterDriveYaw + WATER_DRIVE_ALPHA * turn);
                    aimSmooth.waterDriveRejectStreak = 0;
                } else if (++aimSmooth.waterDriveRejectStreak > WATER_DRIVE_MAX_REJECT) {
                    // Persistent reversal — not a transient flip. Snap so the swim can't strand itself
                    // pointing the wrong way (see WATER_DRIVE_MAX_REJECT).
                    aimSmooth.smoothWaterDriveYaw = driveSrcYaw;
                    aimSmooth.waterDriveRejectStreak = 0;
                }
                // else: reject this tick's flip — HOLD the forward heading.
            }
        } else {
            aimSmooth.smoothWaterDriveYaw = Float.NaN;
        }
        if (trendCam) {
            // Aim the CAMERA at the CENTROID of the lookahead window (see DESCENT_CAM_LOOKAHEAD):
            // a switchback staircase's alternating cardinal segments average to the steady down-slope
            // trend, so the heading holds instead of chasing the ±50° per-step zigzag (the spin).
            // MOVEMENT stays on the immediate node via driveTargetYaw=descentNodeYaw below.
            // CENTROID of the look-ahead window: averaging EVERY node in the window cancels a
            // switchback's alternating segments into the steady down-slope trend. (A chord/far-node
            // samples only an endpoint, which itself lands on alternating segments and swings; the
            // full average does not.) MOVEMENT stays on the immediate node via driveTargetYaw below.
            double sumX = 0;
            double sumZ = 0;
            int cnt = 0;
            int lastNode = centroidEnd(
                    nav,
                    world,
                    foot,
                    Math.min(
                            nav.route.step() + DESCENT_CAM_LOOKAHEAD,
                            nav.route.path().size() - 1));
            int firstNode = lastNode - nav.route.step() >= 4
                    ? nav.route.step() + 2
                    : nav.route.step(); // skip the at-foot nodes
            for (int k = firstNode; k <= lastNode; k++) {
                sumX += nav.route.path().get(k).getX() + 0.5;
                sumZ += nav.route.path().get(k).getZ() + 0.5;
                cnt++;
            }
            if (cnt > 0) {
                double mdx = sumX / cnt - p.getX();
                double mdz = sumZ / cnt - p.getZ();
                float centroidYaw = (float) Math.toDegrees(Math.atan2(-mdx, mdz));
                if (mdx * mdx + mdz * mdz > 1.0) {
                    targetYaw = dryDescent && nav.route.path().get(lastNode).getY() <= foot.getY()
                            ? trendWithRoom(world, p, foot, wp, centroidYaw)
                            : centroidYaw;
                }
            }
        }
        // Low-pass the TARGET heading (EMA on the shortest angle, kept in [-180,180]) so a
        // grid staircase on a diagonal — whose immediate-waypoint bearing alternates ±~30°
        // around the true diagonal each step — averages to a steady bearing instead of a
        // bounded sawtooth (see YAW_SMOOTH_ALPHA). Resync on launch / first use — EXCEPT a
        // decoupled descending launch (dryDescent), which must NOT resync/snap: the leap is
        // drive-decoupled (driveTargetYaw=node), so the camera can keep its continuous EMA and
        // SLEW smoothly through the turn over the airborne ticks instead of snapping ~180° (the
        // spin while falling). Snapping stays for ascending leaps / water-falls where the leap aims via yaw.
        boolean snapLaunch = launch && !dryDescent;
        if (Float.isNaN(aimSmooth.smoothTargetYaw) || snapLaunch) {
            aimSmooth.smoothTargetYaw = targetYaw;
            aimSmooth.reversalStreak = 0;
        } else {
            float alpha = smoothingAlpha(nav, p, trendCam, targetYaw);
            // (An antipode EMA-snap here — snap after 6 consecutive >170° ticks —
            // was tried and REVERTED: it regressed wd.vineOverWaterClimb and
            // wd.bridgeStepTwoBypassNoPlace, where per-repath ±180° target flips
            // are NORMAL and must stay damped. The frozen-press deadlock is
            // released by the physical-stall valve at the spinFreeze site
            // instead, which keys on the deadlock's true signature: zero player
            // displacement while frozen.)
            aimSmooth.smoothTargetYaw =
                    angleDiff(0f, aimSmooth.smoothTargetYaw + alpha * angleDiff(aimSmooth.smoothTargetYaw, targetYaw));
        }
        float aimYaw = snapLaunch ? targetYaw : aimSmooth.smoothTargetYaw;
        foot = cx.frame.foot; // see ascentAimFoot
        // ── In-place backward hop fix ───────────────────────────────────────────────
        // The decoupled descent drive rides the IMMEDIATE node (descentNodeYaw). When the bot
        // OVERSHOOTS that node on a fall landing or a step (lands a hair past it), the node is now
        // BEHIND the bot, so its bearing flips ~180° and the drive reverses — the bot hops
        // backward INTO the node, overshoots again, and oscillates. (Live 2026-06-21 telemetry: at a
        // fall2 landing descentNodeYaw flipped 177°↔-5° while the camera held steady at -5°, so the
        // bot hopped back and forth in place — the spin used to MASK this, but the now-steady trend
        // camera exposes it as a visible backward jump.) When the captured node lies sharply behind
        // the steady trend heading (aimYaw = the look-ahead centroid, which already points along the
        // path), drive ALONG the trend instead of reversing: the bot keeps moving forward through
        // the overshot node, and the step pointer advances via the normal overshoot re-sync. Only
        // a >120° gap counts as an overshoot — a switchback's segments sit ~±50° off the trend, so
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
        // the trace — the water spin). Freezing stops the wind AND, since MC movement
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
        if (!Float.isNaN(aimSmooth.lastAimYaw) && Math.abs(angleDiff(aimYaw, aimSmooth.lastAimYaw)) < AIM_STABLE_DEG) {
            aimSmooth.aimStableTicks = Math.min(aimSmooth.aimStableTicks + 1, AIM_STABLE_TICKS + 1);
        } else {
            aimSmooth.aimStableTicks = 0;
        }
        aimSmooth.lastAimYaw = aimYaw;
        // RAW-target stability (antipode-proof twin of the gate above): the EMA
        // output can be perpetually "unstable" while the RAW bearing has been
        // rock-steady for hundreds of ticks (the ±180° oscillation described at
        // the reversal fix). The freeze must release on EITHER signal being
        // stable — the raw one is what actually proves "the world wants a fixed
        // direction and it is not this one".
        if (!Float.isNaN(aimSmooth.rawLastTargetYaw)
                && Math.abs(angleDiff(targetYaw, aimSmooth.rawLastTargetYaw)) < AIM_STABLE_DEG) {
            aimSmooth.rawStableTicks = Math.min(aimSmooth.rawStableTicks + 1, AIM_STABLE_TICKS + 1);
        } else {
            aimSmooth.rawStableTicks = 0;
        }
        aimSmooth.rawLastTargetYaw = targetYaw;
        // NOTE: rawStableTicks deliberately does NOT release the freeze — an
        // A/B (t0) run with `|| rawStable` REGRESSED wd.vineOverWaterClimb
        // (pocket wedge 49t): in a climb-out pocket the freeze legitimately
        // holds a pressing heading against a steady-but-flipped node bearing.
        // The antipode EMA snap above is the sufficient release path: once the
        // smooth heading snaps to the persistent live target, the EXISTING
        // stability gate sees it steady and releases. Raw tracking stays for
        // telemetry (freeze forensics need "was the raw target stable?").
        boolean targetFlipping = aimSmooth.aimStableTicks < AIM_STABLE_TICKS;
        // The anti-spin freeze stays WATER-gated: a dry-land extension (to catch the dry-churn
        // cliff-stall spin) spuriously engaged during a slow dry pillar-up — the goal-XZ barely
        // moves while pillaring, so repathsNoProgress climbs and the placement aim flips, tripping
        // the freeze and pinning the heading off the column (regressed summitarena). The dry-churn
        // spin is rarer (only on an actual nav stall) and better fixed at the stall itself than by
        // freezing the camera here, so keep the overWater scope.
        boolean overWater = p.isInWater() || world.isWater(foot.offset(0, -1, 0));
        boolean spinFreeze =
                !launch && overWater && nav.stallDetect.goalSpin.repathsNoProgress > CHURN_REPATH_CAP && targetFlipping;
        // FROZEN-PRESS DEADLOCK VALVE (Mountains notch live, stuckT 720): the freeze
        // exists to steady the bot PRESSING toward a climb-out — pressing implies the
        // bot moves (or bobs while the climb machinery works, as in the vine pocket).
        // When the frozen heading points into a wall, the bot is PINNED (horizontal
        // displacement ~0 for seconds), repaths keep failing, and the freeze's own
        // conditions self-sustain: raw target steady 180° away, EMA oscillating at the
        // antipode, stability gate never releasing. Key the release on the deadlock's
        // unique signature — zero displacement WHILE frozen — which no legitimate
        // freeze use shows (in a vine pocket or a climb-out the bot keeps moving). On trip:
        // hard-snap the smooth heading to the live target and drop the freeze.
        if (spinFreeze) {
            double fdx = p.getX() - aimSmooth.freezeAnchorX;
            double fdz = p.getZ() - aimSmooth.freezeAnchorZ;
            if (aimSmooth.frozenStallTicks == 0 || fdx * fdx + fdz * fdz > FREEZE_PRESS_MOVE_SQ) {
                aimSmooth.freezeAnchorX = p.getX();
                aimSmooth.freezeAnchorZ = p.getZ();
                aimSmooth.frozenStallTicks = 1;
            } else if (++aimSmooth.frozenStallTicks > FREEZE_PRESS_STALL_TICKS) {
                if (GameConfig.navigatorDebug) {
                    LOG.info(
                            "[navigator] spin-freeze PRESS-DEADLOCK release: pinned {} ticks at ({},{}) — snap {} → {}",
                            aimSmooth.frozenStallTicks,
                            String.format("%.1f", p.getX()),
                            String.format("%.1f", p.getZ()),
                            String.format("%.0f", aimSmooth.smoothTargetYaw),
                            String.format("%.0f", targetYaw));
                }
                aimSmooth.smoothTargetYaw = targetYaw;
                aimYaw = targetYaw;
                aimSmooth.frozenStallTicks = 0;
                spinFreeze = false;
            }
        } else {
            aimSmooth.frozenStallTicks = 0;
        }
        if (!spinFreeze && Math.abs(angleDiff(p.getYRot(), aimYaw)) > ControlConfig.navigatorYawHysteresisDeg) {
            float ny;
            if (snapLaunch) {
                ny = aimYaw; // launches must snap — no mid-air course correction (decoupled
                LookController.requestSnap(); // descending leaps slew instead — see snapLaunch)
            } else {
                // Cap the per-tick turn (see MAX_YAW_SLEW_DEG) so even a transient
                // bad aim vector can only nudge the heading, never reverse it in one tick.
                float want = smoothAngle(p.getYRot(), aimYaw);
                float dyaw = angleDiff(p.getYRot(), want);
                if (Math.abs(dyaw) > MAX_YAW_SLEW_DEG) {
                    dyaw = Math.copySign(MAX_YAW_SLEW_DEG, dyaw);
                }
                // PIVOT FAST-TURN (stop-go sawtooth): a step-up pivot cuts forward
                // drive entirely until the heading is inside the 40° gate, so every
                // zigzag staircase corner stalls ~0.5 s at the 8°/tick cruise slew —
                // the speed square-wave the path charts show. While pivoting the bot
                // is STATIONARY (drive already zero), so a faster pan is a quick
                // turn-in-place, not a moving-view jerk: turn at 24°/tick and the
                // stall drops to ~0.2 s. Cruise turns keep the gentle cruise slew.
                float pivotErr = angleDiff(p.getYRot(), aimYaw);
                if (wp.getY() > foot.getY()
                        && !parkourEdge
                        && !p.isInWater()
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
        // travels along the LOOK vector, so a level pitch pins the bot at the surface
        // — when the next node is BELOW and the bot is HORIZONTALLY BLOCKED (it rams a
        // low overhang lip: a submerged tunnel whose stone ceiling sits at the
        // DESTINATION cell's foot+1, so a fixed foot+2 check at the bot's own cell
        // misses it — the lethal stuck: hCol=true, hSpd=0, bobbing y62↔63 into the
        // lip), the bot must DIVE: pitch down + the prone-swim sprint (below) sink the
        // ~0.6-tall swimming player to the tunnel floor where it fits under the lip and threads on.
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
        if (p.isInWater() && wp.getY() < foot.getY() && p.horizontalCollision && cappedDescent) {
            dive.latch = 12;
        } else if (dive.latch > 0) {
            dive.latch--;
        }
        boolean diveUnderCap = p.isInWater() && wp.getY() < foot.getY() && dive.latch > 0;
        // ACTIVE dive for a submerged target ≥2 below a floating bot (computed here,
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
        if (diveTarget) {
            dive.hold = 30;
        } else if (dive.hold > 0 && p.isInWater() && wp.getY() < foot.getY()) {
            dive.hold--;
        } else {
            dive.hold = 0;
        }
        boolean diving = diveTarget || (dive.hold > 0 && p.isInWater());
        // CAMERA-THRASH fix (the camera jumping violently up and down while bridging): while bridging, each place tick
        // does aimAtBlockSnap → requestSnap, instantly pitching the camera DOWN onto the
        // block being placed; pulling pitch back to the horizon (0) on every non-place
        // tick made the view saw violently between "look down at feet" and "look at sky".
        // Hold pitch where the place-snap left it across the whole bridge so the camera
        // sits in a steady downward gaze (one entry dip, no per-block sawtooth). Pitch
        // does not affect movement, so freezing it here is motion-neutral.
        if (!bridging && !placingEdge) {
            p.setXRot(smoothAngle(p.getXRot(), diveUnderCap || diving ? 50f : 0f));
        }
        // STEP-UP HEADING GATE (fix for the bot catching on the riser's collision box): if a +1 step is still badly
        // mis-aimed,
        // pivot in place instead of ramming the riser. The jump gate (ascendJumpReady
        // below) already checks POSITION alignment, but neither it nor the forward key
        // checked HEADING — so a drift-off-column / sharp-turn arrival bob-jammed the
        // step. Gate both forward (keyUp) and the step jump on this. Excludes parkour
        // and water (own handling; launches snap heading so the error is ≈0 anyway).
        float stepHeadingErr = stepHeadingErr(p, aimYaw, targetYaw);
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
        if (p.horizontalCollision && p.onGround() && wp.getY() > foot.getY()) {
            ramFold.stepRamStuckTicks++;
        } else {
            ramFold.stepRamStuckTicks = 0;
        }
        // Bob-immune ascent-ram (navigatorAscentRamBobBreak): the steep-bank +1 mount that jumps off the diagonal
        // corner & slides back keeps the foot >0.3 BELOW the node while laterally close, but its airborne apex
        // zeroes BOTH stepRamStuckTicks (onGround-gated) and noStepProgressTicks (3D new-low). Tick a counter
        // that ignores onGround so stepUpFreeze engages on time; a clean climb tops out before the bar.
        // NOTE: this gate is bob-RESETTABLE by design (a big-bob apex crossing the node Y zeroes it) — that is
        // a SAFETY feature: on a genuinely-unwinnable +2 mount the bob keeps it under the bar so stepUpFreeze
        // does NOT pin the bot (a bob-IMMUNE v2 variant over-pinned ~157t on unwinnable mounts in live A/B — reverted).
        boolean ascentNotTopped = wp.getY() > foot.getY()
                && p.getY() < wp.getY() - 0.3
                && !parkourEdge
                && !p.isInWater()
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        if (ControlConfig.navigatorAscentRamBobBreak && ascentNotTopped) {
            ramFold.ascentRamBobTicks++;
        } else {
            ramFold.ascentRamBobTicks = 0;
        }
        // FLOATING water-bank bob (navigatorFloatingBankBobFreeze): a buoyant bot at a +1..+3 water bank bobs
        // y(water)↔(air) every 2-3 t with onGround NEVER true, alternating stepUp/climbUp at the riser but
        // frozen in XZ. All other freeze counters miss it: stepRamStuck/shallowBank need onGround (floating
        // has none), ascentRamBob's !isInWater zeroes on each water-dip, and noStepProgress is zeroed by the
        // 3D bob. Use a STABLE water-bank indicator (water at the foot or just below — true through the WHOLE
        // bob so the down-phase does NOT reset the counter) + !onGround + a lateral riser-ram + node-above
        // (cap +3). Restricted to a WATER bank (isWater) so a DRY unwinnable +2 mount is never pinned (the
        // ascentRam-v2 over-pin regression); an unwinnable water bank simply repaths. Fed into stepUpFreeze
        // past a 2× bar below.
        boolean atWaterBank = world.isWater(foot) || world.isWater(foot.below());
        boolean floatingBankNotTopped = atWaterBank
                && !p.onGround()
                && wp.getY() > foot.getY()
                && (wp.getY() - foot.getY()) <= 3
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        if (ControlConfig.navigatorFloatingBankBobFreeze && floatingBankNotTopped) {
            ramFold.floatingBankBobTicks++;
        } else {
            ramFold.floatingBankBobTicks = 0;
        }
        boolean stepUpFreeze = wp.getY() > foot.getY()
                && !parkourEdge
                && (!p.isInWater()
                        || shallowBankStep
                        || (ControlConfig.navigatorFloatingBankBobFreeze
                                && ramFold.floatingBankBobTicks > 2 * STEPUP_FREEZE_TICKS))
                && (nav.progress.stepProg.noStepProgressTicks > STEPUP_FREEZE_TICKS
                        || ramFold.stepRamStuckTicks > STEPUP_FREEZE_TICKS
                        || ramFold.ascentRamBobTicks > STEPUP_FREEZE_TICKS
                        || ramFold.floatingBankBobTicks > 2 * STEPUP_FREEZE_TICKS)
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        boolean pivotForStepUp = wp.getY() > foot.getY()
                && !parkourEdge
                && !p.isInWater()
                && stepHeadingErr > STEPUP_AIM_TOLERANCE_DEG
                && !stepUpFreeze;
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
        boolean descendBrake = descendLeap
                && !p.onGround()
                && (landDx * landDx + landDz * landDz) < 1.4; // within ~1.2 block of landing center
        // ---- publish: write this phase's products for the downstream phases (TickCtx) ----
        cx.aim.aimAtWaypoint = aimAtWaypoint;
        cx.aim.reCentre = reCentre;
        cx.aim.aimSrc = aimSrc;
        nav.aimTag = aimSrc;
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
