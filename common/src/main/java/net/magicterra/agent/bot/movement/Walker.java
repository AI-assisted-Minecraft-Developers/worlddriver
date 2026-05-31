package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.movement.PathSmoothing.*;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.agent.AgentDriverCommon.LOG;

public final class Walker {
    private static final int CARROT_MAX_NODES = 10;
    private static final double CARROT_DIST = 4.0;
    public enum Step { WALKING, ARRIVED, FAILED }
    private static final double REACH_DIST_SQ = 0.45;
    private static final int STUCK_TICKS = 60;
    /** Ticks after the jump press before placing the pillar block beneath —
     *  by then the player has cleared the old feet cell (matches TowerProcess). */
    private static final int PILLAR_PLACE_DELAY = 3;
    // Water-bucket (MLG) fall handling lives in the always-on {@link
    // ClutchController} (BotApiImpl#CLUTCH), so an unplanned fall self-rescues
    // whether or not a Walker is driving. The Walker only ARMS a planned fall
    // (CLUTCH.armPlanned) as it steps off a fallBucket lip and biases the
    // step-off keys (walk-speed, no sprint) so the body drops near-vertically.
    // Other tuning lives in BotConfig (mutable via mc.bot.setting).

    /** Snapshot of the most recent {@code findPath()} result across ALL
     *  Walkers (volatile so cross-thread reads in {@code mc.bot.status} are
     *  consistent). Surfaced under {@code status.lastPath} for debugging
     *  pathing failures — Baritone exposes the same via {@code path}/{@code stats}. */
    public record PathStats(int expanded, long ms, boolean goalReached, double finalCost, int pathLen) {}
    public static volatile PathStats lastStats;

    private Goal goal;
    private List<BlockPos> path;
    private List<Move.Edge> edges;   // aligned with path; edges.get(i) enters path.get(i)
    private int step;
    private int ticksSinceRepath;
    private int stuckTicks;
    private int totalTicks;
    private int actionTicks;          // ticks spent on the current break/place edge
    private int pillarStep = -1;      // path index of the pillar edge in progress
    private int pillarSinceJump = -1; // ticks since the pillar jump press (-1 = grounded)
    private boolean descending;       // ending creative flight; wait to land before pathing
    private PathFinder.Search activeSearch;  // in-flight time-sliced A* (null = none)
    private double bestDistToGoal = Double.POSITIVE_INFINITY;
    private BlockPos lastPlayerBlock;
    public String lastError;

    public void setGoal(Goal g) {
        this.goal = g;
        this.path = null;
        this.edges = null;
        this.step = 0;
        this.ticksSinceRepath = 0;
        this.stuckTicks = 0;
        this.totalTicks = 0;
        this.actionTicks = 0;
        this.pillarStep = -1;
        this.pillarSinceJump = -1;
        this.descending = false;
        this.activeSearch = null;
        this.bestDistToGoal = Double.POSITIVE_INFINITY;
        this.lastPlayerBlock = null;
        this.lastError = null;
    }

    public int pathLen() { return path == null ? 0 : path.size(); }
    public int pathStep() { return step; }

    public Step tick(Minecraft mc, WorldView world) {
        LocalPlayer p = mc.player;
        if (p == null) { lastError = "player vanished"; return Step.FAILED; }

        // Ground pathfinder: end creative flight so the player descends and
        // the walk/jump actuator (which relies on gravity + onGround) works.
        // While flying the player floats above the ground path, overshoots
        // waypoints frictionlessly, and spins in place re-aiming — then
        // times out hovering. Re-applied each tick so re-toggling flight
        // mid-path can't strand the bot.
        if (p.getAbilities().flying) {
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            if (!descending && BotConfig.walkerDebug)
                LOG.info(
                        "[walker] creative flight detected → disabling, will descend (y={})", p.getY());
            descending = true;
        }
        // Let the post-flight free-fall settle before we path — A* from a
        // mid-air foot finds no neighbours and reports "no path". Scoped to
        // this one-shot descent so the legitimately-airborne ticks of
        // jump / parkour / fall moves are untouched.
        if (descending) {
            if (!p.onGround() && !world.isWater(new BlockPos(
                    (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())))) {
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(false);
                mc.options.keyLeft.setDown(false);
                mc.options.keyRight.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keySprint.setDown(false);
                p.setSprinting(false);
                if (BotConfig.walkerDebug)
                    LOG.info(
                            "[walker] descending… y={} onGround={}", p.getY(), p.onGround());
                return Step.WALKING;
            }
            descending = false;
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] landed at y={} → resume ground pathing", p.getY());
        }

        // Water-bucket (MLG) fall execution is owned by the always-on
        // CLUTCH (see BotApiImpl#clientTick): once armed — planned, via
        // CLUTCH.armPlanned below as we step off a fallBucket lip, OR
        // reactively, on any unplanned damaging fall — the clutch runs at the
        // top of clientTick, takes the keys for the whole descent, and returns
        // early so this Walker is suspended until the bot has landed and
        // scooped. So there's nothing to do here; the walk dispatch below only
        // ARMS the planned case and biases the step-off keys.

        BlockPos foot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        if (goal.reached(foot)) {
            // While mid-pillar-jump the floored feet-Y can tick into the goal
            // cell at the apex before we've placed the block to stand on —
            // declaring ARRIVED there makes the bot abort the last placement
            // and fall back a block. Wait until grounded so it ends up
            // actually standing in the goal cell. Scoped to pillar edges so
            // swim/parkour arrivals (legitimately airborne) are unaffected.
            // A parkour-place leap can put the floored feet-Y into the goal
            // cell mid-arc — like the pillar case — before we've landed on the
            // placed block and braked the overshoot. Hold ARRIVED until grounded
            // so the settle phase can stop the bot ON the block, not past it.
            // A parkour-DESCEND leap likewise puts the floored feet-Y into the
            // lower goal cell mid-arc while still airborne and carrying forward
            // momentum; declaring ARRIVED there skips the landing brake and the
            // bot overshoots the 1-wide block into the void. Hold until grounded.
            Move.Edge ce = edgeAt(step);
            boolean midAirEdge = ce != null && ce.move != null && !p.onGround()
                    && ("pillarUp".equals(ce.move) || ce.move.startsWith("parkourPlace")
                        || ce.move.startsWith("parkourDescend"));
            if (!midAirEdge) return Step.ARRIVED;
        }

        // Hard tick budget: prevents infinite walking when A* returns a partial path
        // for an unreachable goal (best-effort fallback path > 1 node satisfies ok()).
        // Reset on real progress (closer to goal) so legitimate long walks aren't killed.
        double d = goal.estimate(foot);
        if (d < bestDistToGoal - 0.5) {
            bestDistToGoal = d;
            totalTicks = 0;
        } else if (++totalTicks > BotConfig.walkerTotalTickBudget) {
            lastError = "no progress for " + BotConfig.walkerTotalTickBudget + " ticks (best dist=" + Math.round(bestDistToGoal) + ")";
            return Step.FAILED;
        }

        boolean needRepath = (path == null) || (ticksSinceRepath > BotConfig.walkerRepathEveryTicks) || (stuckTicks > STUCK_TICKS);
        if (!needRepath && path != null && step < path.size() && path.get(step).distSqr(foot) > 9) needRepath = true;
        // Kick off a fresh time-sliced search when due (and not already running).
        if (needRepath && activeSearch == null) {
            activeSearch = new PathFinder(world).newSearch(foot, goal);
            ticksSinceRepath = 0;
        }
        // Advance any in-flight search by one tick-slice so a big search never
        // blocks the render thread in a single tick (fixes the stutter).
        boolean searchDone = false;
        if (activeSearch != null) {
            long sb = System.nanoTime();
            searchDone = activeSearch.advance(BotConfig.pathfinderSliceMs);
            if (BotConfig.walkerDebug && !searchDone)
                LOG.info(
                        "[walker] search slice {}ms expanded={} (still running)",
                        (System.nanoTime() - sb) / 1_000_000, activeSearch.expanded());
        }
        if (searchDone) {
            PathFinder.Result res = activeSearch.result();
            activeSearch = null;
            lastStats = new PathStats(res.expanded(), res.ms(), res.goalReached(),
                    res.finalCost(), res.path().size());
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] repath from {} → goalReached={} pathLen={} expanded={} ms={}",
                        foot, res.goalReached(), res.path().size(), res.expanded(), res.ms());
            if (res.hasPath()) {
                // String-pull flat walk runs so the heading stays steady over
                // the staircase (no left-right camera wobble) and the bot walks
                // straight; action/vertical/parkour nodes are preserved.
                SmoothResult sm = stringPull(world, res.path(), res.edges());
                path = sm.path;
                edges = sm.edges;
                step = 1;
                stuckTicks = 0;
                actionTicks = 0;
                if (BotConfig.walkerDebug) {
                    StringBuilder sbp = new StringBuilder();
                    for (int i = 0; i < path.size(); i++) {
                        Move.Edge e = i < edges.size() ? edges.get(i) : null;
                        sbp.append(i).append(':').append(path.get(i).getX()).append(',').append(path.get(i).getY())
                           .append(',').append(path.get(i).getZ()).append('[').append(e != null ? e.move : "-").append("] ");
                    }
                    LOG.info("[walker] path = {}", sbp);
                }
            } else if (path == null) {
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
                    mc.options.keyUp.setDown(false);
                    mc.options.keyDown.setDown(false);
                    mc.options.keyLeft.setDown(false);
                    mc.options.keyRight.setDown(false);
                    mc.options.keySprint.setDown(false);
                    p.setSprinting(false);
                    return Step.WALKING;
                }
                lastError = "no path (expanded=" + res.expanded() + ")";
                return Step.FAILED;
            }
            // else: search failed but we still have the previous path — keep it.
        }
        // First path still computing (no path to follow yet) → hold, don't spin.
        if (path == null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
            p.setSprinting(false);
            return Step.WALKING;
        }
        ticksSinceRepath++;
        // A place-bridge crawl is deliberately slow (sneak ≈ 1 block/15 ticks),
        // so the foot block stays put for many ticks while we're making real
        // progress. Counting that as "stuck" trips a needless repath (and used
        // to trip the wiggle-jump) right at the gap edge — so zero the counter
        // whenever the current/next edge is a bridge.
        Move.Edge curBridgeE = edgeAt(step), nxtBridgeE = edgeAt(step + 1);
        boolean onBridge = (curBridgeE != null && "bridgePlace".equals(curBridgeE.move))
                || (nxtBridgeE != null && "bridgePlace".equals(nxtBridgeE.move));
        if (onBridge) stuckTicks = 0;
        else if (lastPlayerBlock != null && lastPlayerBlock.equals(foot)) stuckTicks++; else stuckTicks = 0;
        lastPlayerBlock = foot;

        while (step < path.size()) {
            Move.Edge se = edgeAt(step);
            // Don't advance past a cell whose break/place actions are still
            // pending — otherwise a DownBreak (foot vertically aligned, < 1.2
            // away) would be skipped before we ever mine the floor.
            if (hasPendingEdge(world, se)) break;
            // A pillar step isn't "reached" until we've actually risen and
            // landed on the placed block — else we'd advance mid-jump and the
            // next edge would fire while airborne.
            if (se != null && "pillarUp".equals(se.move)
                    && !(p.onGround() && p.getY() >= path.get(step).getY() - 0.1)) break;
            // Don't advance past a parkour-place edge while airborne — keep the
            // settle phase owning the descent so it brakes the leap on landing.
            if (se != null && se.move != null && se.move.startsWith("parkourPlace")
                    && !p.onGround()) break;
            // Same for a parkour-descend leap: don't advance off it until we've
            // actually landed, so the descend landing-brake keeps owning the arc
            // (otherwise the next edge fires mid-air and the bot sails past).
            if (se != null && se.move != null && se.move.startsWith("parkourDescend")
                    && !p.onGround()) break;
            BlockPos w = path.get(step);
            double dx = (w.getX() + 0.5) - p.getX();
            double dz = (w.getZ() + 0.5) - p.getZ();
            double cur2 = dx * dx + dz * dz;
            boolean within = cur2 < REACH_DIST_SQ && Math.abs(w.getY() - p.getY()) < 1.2;
            // Pure-pursuit re-sync: also advance past a node we've already gone
            // by — the next node being closer than this one means the player is
            // beyond it. Without this, sprinting toward a far carrot (or a
            // sliced repath that starts from a now-stale foot) leaves `step`
            // pointing at a node BEHIND the player, so the aim flips ~180°.
            boolean passed = false;
            if (!within && step + 1 < path.size()) {
                BlockPos nx = path.get(step + 1);
                double ndx = (nx.getX() + 0.5) - p.getX();
                double ndz = (nx.getZ() + 0.5) - p.getZ();
                passed = (ndx * ndx + ndz * ndz) <= cur2 && Math.abs(w.getY() - p.getY()) < 1.5;
            }
            if (within || passed) step++;
            else break;
        }
        if (step >= path.size()) return Step.ARRIVED;

        Move.Edge edge = edgeAt(step);

        // Pillar-up actuator: clear the ceiling if one blocks the rise, then
        // jump and place the support block beneath at the apex. Distinct from
        // the generic place actuator because it owns the airborne timing
        // (you can't place a block in the cell you're standing in).
        if (edge != null && "pillarUp".equals(edge.move) && hasPendingEdge(world, edge)) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keySprint.setDown(false);
            p.setSprinting(false);
            totalTicks = 0;
            stuckTicks = 0;   // pillaring stays on one cell while placing — not "stuck"
            if (step != pillarStep) { pillarStep = step; pillarSinceJump = -1; }
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                mc.options.keyAttack.setDown(false);
                mc.options.keyJump.setDown(false);
                lastError = "pillar stalled at " + path.get(step);
                path = null;
                return Step.WALKING;
            }
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    mc.options.keyJump.setDown(false);
                    selectBestToolFor(mc, b);
                    aimAtBlockSnap(p, b);
                    mc.options.keyAttack.setDown(true);
                    return Step.WALKING;
                }
            }
            mc.options.keyAttack.setDown(false);
            if (!ensureHoldingPlaceableAny(mc)) {
                lastError = "pillar: no placeable block in hotbar";
                path = null;
                return Step.WALKING;
            }
            BlockPos place = edge.toPlace.get(0);                    // cell we fill (old feet cell)
            BlockPos support = place.offset(0, -1, 0);               // click its top face (block we stood on)
            p.setXRot(89.5f);                                        // look straight down (snap)
            if (p.onGround()) {
                mc.options.keyJump.setDown(true);
                pillarSinceJump = 0;
            } else {
                mc.options.keyJump.setDown(false);
                if (pillarSinceJump >= 0) pillarSinceJump++;
                // Place only once the feet have actually risen clear of the cell
                // being filled. The target IS the old feet cell, so vanilla's
                // entity-collision check (Level#isUnobstructed) silently rejects
                // the block while the player AABB still overlaps it — i.e. until
                // the feet (getY) reach that cell's top face, place.y + 1. A
                // vanilla jump (peak ~+1.25) only crosses +1.0 around tick 4, so
                // the old fixed 3-tick delay fired at ~+0.99 and the place
                // no-op'd against the player's own body. Gate on real height.
                if (pillarSinceJump >= PILLAR_PLACE_DELAY && p.getY() >= place.getY() + 1.0) {
                    clientUseItemOn(mc, p, support, Direction.UP);
                }
            }
            return Step.WALKING;
        }

        // Parkour-place actuator (Baritone allowParkourPlace): a two-phase leap
        // owned here so the generic place actuator below (which freezes all
        // motion to place) can't kill the arc's momentum.
        //   LEAP  (floor still air): forward + sprint + jump off the lip with
        //         run-up PRESERVED, then place the landing block mid-air the
        //         instant a support is in reach. The hit is synthetic
        //         (clientUseItemOn), so aiming down isn't needed — the
        //         crosshair stays on the destination for the whole arc.
        //   SETTLE(floor placed, still airborne): keep driving forward to clear
        //         the gap, but DROP sprint and hold sneak — sneak's ledge guard
        //         stops the bot on the fresh 1-wide block on touchdown instead
        //         of letting sprint momentum carry it off the far edge into a
        //         gap beyond (matters when the place-support is below/beside,
        //         not a walkable same-level wall).
        // Once placed AND grounded the guard falls through to the normal walk /
        // arrival check.
        if (edge != null && edge.move != null && edge.move.startsWith("parkourPlace")
                && (hasPendingEdge(world, edge) || !p.onGround())) {
            BlockPos dest = path.get(step);
            BlockPos floor = edge.toPlace.get(0);
            boolean placed = !hasPendingEdge(world, edge);   // landing block is down
            boolean grounded = p.onGround();
            totalTicks = 0;
            stuckTicks = 0;       // owns this cell while leaping — not "stuck"
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                lastError = "parkour-place stalled at " + dest;
                path = null;
                return Step.WALKING;
            }
            // Snap heading at the destination — can't course-correct mid-air.
            double adx = (dest.getX() + 0.5) - p.getX();
            double adz = (dest.getZ() + 0.5) - p.getZ();
            if (Math.abs(adx) > 1e-4 || Math.abs(adz) > 1e-4) {
                float yaw = (float) Math.toDegrees(Math.atan2(-adx, adz));
                p.setYRot(yaw);
                p.yHeadRot = yaw;
                p.yBodyRot = yaw;
            }
            p.setXRot(0f);
            mc.options.keyUp.setDown(true);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(!placed && grounded);   // jump off the lip once
            boolean sprint = !placed;                          // brake after the block is down
            mc.options.keySprint.setDown(sprint);
            p.setSprinting(sprint);
            mc.options.keyShift.setDown(placed);               // sneak-brake / ledge-guard on landing
            p.setShiftKeyDown(placed);
            if (!placed && !grounded && ensureHoldingPlaceableAny(mc)) {
                Vec3 eye = p.getEyePosition();
                double fdx = (floor.getX() + 0.5) - eye.x, fdy = (floor.getY() + 0.5) - eye.y, fdz = (floor.getZ() + 0.5) - eye.z;
                boolean inReach = fdx * fdx + fdy * fdy + fdz * fdz < 16;   // ~4 blocks of the eye
                if (inReach) {
                    walkerPlace(mc, p, world, floor);
                    if (BotConfig.walkerDebug)
                        LOG.info(
                                "[walker] parkour-place floor={},{},{} y={} dy={} solid={}",
                                floor.getX(), floor.getY(), floor.getZ(),
                                String.format(Locale.ROOT, "%.2f", p.getY()),
                                String.format(Locale.ROOT, "%.2f", p.getDeltaMovement().y),
                                world.isSolid(floor));
                }
            }
            return Step.WALKING;
        }

        // Break/place actuator: mine or place the blocks this edge needs
        // before walking into the cell. Functional aim SNAPS (same-tick),
        // independent of smoothLook. Returns each tick until the edge is
        // clear, then falls through to the normal walk below.
        if (edge != null && hasPendingEdge(world, edge)) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
            p.setSprinting(false);
            totalTicks = 0;                       // breaking/placing IS progress
            stuckTicks = 0;                       // foot stays put while placing — don't trip the wiggle-jump (it'd leap off a 1-wide bridge)
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                // Lag or an unexpected obstruction — drop the path and let
                // the next tick repath from the current position.
                mc.options.keyAttack.setDown(false);
                lastError = "break/place stalled at " + path.get(step);
                path = null;
                return Step.WALKING;
            }
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    selectBestToolFor(mc, b);
                    aimAtBlockSnap(p, b);
                    mc.options.keyAttack.setDown(true);
                    return Step.WALKING;
                }
            }
            mc.options.keyAttack.setDown(false);
            for (BlockPos b : edge.toPlace) {
                if (!world.isSolid(b)) {
                    if (BotConfig.walkerDebug)
                        LOG.info(
                                "[walker] place-act foot={},{},{} y={} step={} placing={},{},{} onGround={} edge={}",
                                foot.getX(), foot.getY(), foot.getZ(), String.format(Locale.ROOT, "%.2f", p.getY()),
                                step, b.getX(), b.getY(), b.getZ(), p.onGround(), edge.move);
                    aimAtBlockSnap(p, b);
                    walkerPlace(mc, p, world, b);
                    return Step.WALKING;
                }
            }
            return Step.WALKING;                  // settle a tick before walking on
        }
        mc.options.keyAttack.setDown(false);
        actionTicks = 0;

        // Pillar placed but the player is still rising onto it — hold (no
        // horizontal walk) until grounded at the new level, so we don't walk
        // off the fresh block mid-jump.
        if (edge != null && "pillarUp".equals(edge.move)
                && !(p.onGround() && p.getY() >= path.get(step).getY() - 0.1)) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
            p.setSprinting(false);
            return Step.WALKING;
        }

        BlockPos wp = path.get(step);
        Move.Edge nextEdge = edgeAt(step + 1);
        boolean parkourEdge = edge != null && edge.move != null && edge.move.startsWith("parkour");
        // Stepping off into an MLG fall: walk off at WALK speed (no sprint) so
        // the body drops near-vertically and lands in the water we place under
        // it — a sprint launch carries horizontal momentum that drifts the bot
        // off the placed source and it clips the edge (partial fall damage).
        // Do NOT sneak though: sneak is ledge-protection and would stop the bot
        // walking off the edge at all. Triggered when the edge we're walking
        // onto OR the next one is an MLG fall.
        boolean steppingOffFall = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                || (nextEdge != null && nextEdge.move != null && nextEdge.move.startsWith("fallBucket"));
        // A fall into EXISTING water (Move.FallIntoWater, "fallWater*") wants
        // the same near-vertical step-off — no sprint — so the longer airtime
        // of a tall drop doesn't carry the bot horizontally past the 1-wide
        // water column and onto dry land beyond. But there's no source to
        // place or scoop here, so it must NOT arm the mlg latch (that latch
        // drives water placement and would strand an empty hand mid-fall).
        boolean steppingOffWaterFall = (edge != null && edge.move != null && edge.move.startsWith("fallWater"))
                || (nextEdge != null && nextEdge.move != null && nextEdge.move.startsWith("fallWater"));
        // Commit to the MLG fall while still on the launch lip: the latch at
        // the top of tick() then owns the entire descent (placing/scooping the
        // water) independent of how A* relabels the edge as we near the ground.
        if (steppingOffFall) {
            // Commit to the MLG fall while still on the launch lip, recording
            // the planned landing column so the airborne clutch can damp drift
            // back toward it: a walk-off's residual horizontal momentum (zero
            // air friction) otherwise carries the body past a 1-wide landing
            // before it descends, and the water gets placed on whatever happens
            // to be below the drifted-into column instead.
            BlockPos land = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                    ? wp : path.get(step + 1);
            if (land != null) CLUTCH.armPlanned(land.getX(), land.getZ());
        }

        // Aim at a line-of-sight carrot further along the (now string-pulled)
        // path so the heading stays steady — no left-right wobble. For a
        // vertical move or a real parkour leap, face the actual waypoint so
        // the jump goes the right way.
        boolean aimAtWaypoint = wp.getY() != foot.getY() || parkourEdge;
        double adx, adz;
        if (aimAtWaypoint) {
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
        } else {
            double[] c = carrotPoint(world, foot, p.getX(), p.getZ());
            adx = c[0] - p.getX();
            adz = c[1] - p.getZ();
        }
        float targetYaw = (Math.abs(adx) < 1e-4 && Math.abs(adz) < 1e-4)
                ? p.getYRot()   // standing on the aim point — hold heading, don't thrash atan2
                : (float) Math.toDegrees(Math.atan2(-adx, adz));
        if (Math.abs(angleDiff(p.getYRot(), targetYaw)) > BotConfig.walkerYawHysteresisDeg) {
            // A launch into a leap (parkour or MLG fall) must SNAP the heading
            // even when smoothLook is on — you can't course-correct mid-air, so
            // a lagged smooth-pan launch sends the bot off at an angle (it
            // drifts sideways off a narrow landing and misses). Plain walking
            // still smooth-pans for the cinematic look.
            float ny = (parkourEdge || steppingOffFall || steppingOffWaterFall) ? targetYaw : smoothAngle(p.getYRot(), targetYaw);
            p.setYRot(ny);
            p.yHeadRot = ny;
            p.yBodyRot = ny;
        }
        p.setXRot(smoothAngle(p.getXRot(), 0f));
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
        mc.options.keyUp.setDown(!descendBrake);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        // Bridging a chasm one placed block at a time: sneak (so a sprint
        // overshoot can't carry the bot off the fresh 1-wide block into the
        // gap ahead) and don't sprint. Triggered when the edge we're walking
        // onto OR the next one is a place-bridge — Baritone sneaks for the
        // same reason. Released the moment we're back on real ground.
        boolean bridging = (edge != null && "bridgePlace".equals(edge.move))
                || (nextEdge != null && "bridgePlace".equals(nextEdge.move));
        mc.options.keyShift.setDown(bridging || descendBrake);
        p.setShiftKeyDown(bridging || descendBrake);
        // Jump for a real upward step, a parkour-leap edge (by move type, not
        // raw distance — string-pulling makes plain walk waypoints far apart
        // too), or a brief stuck-wiggle.
        // The stuck-wiggle jump unsticks a bot wedged on a corner, but on a
        // 1-wide bridge it would hop the bot clean off into the gap — so
        // never wiggle-jump while bridging. Stop pressing jump once braking
        // (we're descending onto the block — no more lift wanted).
        boolean jump = !descendBrake
                && (wp.getY() > foot.getY() || parkourEdge || (!bridging && stuckTicks > 10 && stuckTicks < 18));
        mc.options.keyJump.setDown(jump);
        boolean sprint = !bridging && !steppingOffFall && !steppingOffWaterFall && !descendBrake;
        mc.options.keySprint.setDown(sprint);
        p.setSprinting(sprint);
        if (BotConfig.walkerDebug && bridging)
            LOG.info(
                    "[walker] bridge-walk foot={},{},{} y={} step={} wp={},{},{} onGround={} jump={} sneak={} sprint={} stuck={} edge={}",
                    foot.getX(), foot.getY(), foot.getZ(), String.format(Locale.ROOT, "%.2f", p.getY()),
                    step, wp.getX(), wp.getY(), wp.getZ(), p.onGround(), jump, bridging, !bridging, stuckTicks,
                    edge != null ? edge.move : "-");
        return Step.WALKING;
    }

    private static float angleDiff(float a, float b) { return ((b - a) % 360f + 540f) % 360f - 180f; }

    private Move.Edge edgeAt(int i) {
        return (edges != null && i >= 0 && i < edges.size()) ? edges.get(i) : null;
    }

    /** A continuously-sliding aim point {@code CARROT_DIST} blocks ahead
     *  along the path (interpolated between nodes), capped by line-of-sight —
     *  the pure-pursuit "carrot". Because it's interpolated (not snapped to a
     *  discrete node), the bearing to it changes smoothly as the player moves,
     *  so the heading doesn't wobble over the cardinal/diagonal staircase.
     *  Stops at a wall/turn or an action cell so it never aims through one.
     *  Returns {x, z} world coords. */
    private double[] carrotPoint(WorldView world, BlockPos foot, double px, double pz) {
        double remaining = CARROT_DIST;
        double cx = px, cz = pz, tx = px, tz = pz;
        for (int i = step; i < path.size() && i - step <= CARROT_MAX_NODES; i++) {
            BlockPos node = path.get(i);
            double nx = node.getX() + 0.5, nz = node.getZ() + 0.5;
            if (hasPendingEdge(world, edgeAt(i))) return new double[]{nx, nz}; // face the action cell
            if (!losWalkable(world, foot, node)) break;                        // don't aim past a wall
            double seg = Math.hypot(nx - cx, nz - cz);
            if (seg < 1e-6) { tx = nx; tz = nz; continue; }
            if (remaining <= seg) {
                double t = remaining / seg;
                return new double[]{cx + (nx - cx) * t, cz + (nz - cz) * t};
            }
            remaining -= seg;
            cx = nx; cz = nz; tx = nx; tz = nz;
        }
        return new double[]{tx, tz};
    }
}
