package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.SurvivalMath;
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
    /** Per-step ticks of bob-stalling before the water climb-out actuator places
     *  a foothold to ground a floating bot against a too-high bank. Keyed off the
     *  per-step no-progress timer ({@code totalTicks}, which a bob can't reset —
     *  unlike {@code stuckTicks}, which the oscillating foot-Y clears each cycle).
     *  Well under {@code walkerTotalTickBudget}, comfortably past a normal flush /
     *  staircase climb-out (grounds in <10 ticks, never stalls). */
    private static final int WATER_CLIMB_STALL = 30;
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
    private int waterClimbStall;      // ticks bob-stalled (no NET height gain) climbing out of water
    private double waterClimbBestY = Double.NEGATIVE_INFINITY; // best Y this water-climb; a real rise resets the stall
    private boolean descending;       // ending creative flight; wait to land before pathing
    private PathFinder.Search activeSearch;  // in-flight time-sliced A* (null = none)
    private double bestDistToGoal = Double.POSITIVE_INFINITY;
    private BlockPos lastPlayerBlock;
    private int dbgPrevStep = -1;     // walkerDebug: detect step changes for per-step timing
    private int dbgTicksOnStep = 0;   // walkerDebug: ticks spent on the current step
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
        this.waterClimbStall = 0;
        this.waterClimbBestY = Double.NEGATIVE_INFINITY;
        this.descending = false;
        this.activeSearch = null;
        this.bestDistToGoal = Double.POSITIVE_INFINITY;
        this.lastPlayerBlock = null;
        this.lastError = null;
    }

    /** Drop the cached path (keep the goal) so the next {@link #tick} recomputes
     *  from the current position. Used when a movement process is resumed after
     *  being preempted by a higher-priority chain — during the suspension the bot
     *  may have been knocked back or the terrain changed, so reusing the stale
     *  path would walk into a wall. See ProcessScheduler / UserTaskChain.onResume. */
    public void forceRepath() {
        this.path = null;
        this.edges = null;
        this.step = 0;
        this.ticksSinceRepath = 0;
        this.stuckTicks = 0;
        this.actionTicks = 0;
        this.pillarStep = -1;
        this.pillarSinceJump = -1;
        this.activeSearch = null;
        this.lastPlayerBlock = null;
        // Clear the water-climb-out / descent state too (setGoal resets these): a
        // resume mid water-climb otherwise carries a stale waterClimbBestY/Stall, so
        // the next tick either fires a spurious foothold-place takeover (stall already
        // past threshold) or suppresses a legitimate stall (stale-high best-Y).
        this.waterClimbStall = 0;
        this.waterClimbBestY = Double.NEGATIVE_INFINITY;
        this.descending = false;
    }

    public int pathLen() { return path == null ? 0 : path.size(); }
    public int pathStep() { return step; }

    public Step tick(Minecraft mc, WorldView world) {
        LocalPlayer p = mc.player;
        if (p == null) { lastError = "player vanished"; return terminal(Step.FAILED, PathTrace.Outcome.ERROR, lastError); }

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
        sampleTick(p);
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
            if (!midAirEdge) return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
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
            return terminal(Step.FAILED, PathTrace.Outcome.STUCK, lastError);
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
            PathTraceHolder.SINK.onSearchResult(res.path(), res.edges(), res.goalReached(),
                    res.expanded(), res.ms(), res.finalCost());
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
                return terminal(Step.FAILED, PathTrace.Outcome.NO_PATH, lastError);
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
            // Buoyant pillar (bunker / flooded-pit self-exit): the bot rises through
            // a water-filled shaft. Detect it from the WORLD — the cell being risen
            // FROM is water — not p.isInWater(), which flickers false at the bob peak
            // when the head clears the surface (that flicker stalled the climb).
            // A FLOODED shaft (destination cell is itself water) lets the bot float
            // straight up; a buoyant pillar more broadly is any pillarUp begun from
            // water (cell below is water / bot in water) — including the dry-air
            // chimney where the bot must place a support to climb out.
            boolean shaftFlooded = se != null && "pillarUp".equals(se.move) && world.isWater(path.get(step));
            boolean waterPillar = se != null && "pillarUp".equals(se.move)
                    && (shaftFlooded || p.isInWater() || world.isWater(path.get(step).offset(0, -1, 0)));
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
                    && !((p.onGround() || shaftFlooded) && p.getY() >= path.get(step).getY() - 0.1)) break;
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
            boolean floatOverSubmerged = p.isInWater() && !p.isUnderWater()
                    && dyNode < -0.5 && !diveEdge;
            boolean within = cur2 < REACH_DIST_SQ
                    && (Math.abs(dyNode) < 1.2 || floatOverSubmerged)
                    && !(p.isInWater() && dyNode > 0.5);
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
                passed = (ndx * ndx + ndz * ndz) < cur2
                        && Math.abs(w.getY() - p.getY()) < 1.5
                        && Math.abs(nx.getY() - p.getY()) < 1.2;
            }
            if (within || passed) step++;
            else break;
        }
        if (step >= path.size()) return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);

        Move.Edge edge = edgeAt(step);

        // === Comprehensive per-tick Walker trace (walkerDebug) ===
        // The single source of truth for "why is the bot stuck": for the current
        // step it prints the exact advance-decision inputs (horizontal dist² vs the
        // REACH_DIST_SQ gate, the |Δy| vs the 1.2 gate that together decide `within`),
        // how many ticks we've been stuck on THIS step, the move type + its
        // break/place needs, and the live body state. Read this trace top-to-bottom
        // to see precisely which condition fails tick after tick — no guessing.
        if (BotConfig.walkerDebug) {
            if (step != dbgPrevStep) { dbgPrevStep = step; dbgTicksOnStep = 0; }
            dbgTicksOnStep++;
            BlockPos nd = path.get(step);
            double ddx = (nd.getX() + 0.5) - p.getX();
            double ddz = (nd.getZ() + 0.5) - p.getZ();
            double cur2 = ddx * ddx + ddz * ddz;
            double dY = nd.getY() - p.getY();
            boolean within = cur2 < REACH_DIST_SQ && Math.abs(dY) < 1.2;
            BlockPos br0 = (edge != null && !edge.toBreak.isEmpty()) ? edge.toBreak.get(0) : null;
            LOG.info("[walker] t={} step={}/{} move={} node={},{},{} p=({},{},{}) cur2={} (gate {}) |dY|={} (gate 1.2) within={} onG={} inW={} undW={} stuck={} totStuck={} pend={} break0={}{}",
                    dbgTicksOnStep, step, path.size(), edge != null ? edge.move : "-",
                    nd.getX(), nd.getY(), nd.getZ(),
                    String.format(Locale.ROOT, "%.2f", p.getX()), String.format(Locale.ROOT, "%.2f", p.getY()), String.format(Locale.ROOT, "%.2f", p.getZ()),
                    String.format(Locale.ROOT, "%.3f", cur2), REACH_DIST_SQ,
                    String.format(Locale.ROOT, "%.2f", Math.abs(dY)), within,
                    p.onGround(), p.isInWater(), p.isUnderWater(),
                    stuckTicks, totalTicks, edge != null && hasPendingEdge(world, edge),
                    br0, br0 != null ? (world.isSolid(br0) ? " (solid)" : " (clear)") : "");
        }

        // === Water climb-out foothold (place to get grounded) ===
        // Runs BEFORE the break/place actuators so it catches a bob-stall no matter
        // how A* labelled the climb (plain StepUp, StairUpBreak into the bank, …).
        // A floating bot can't gain height onto a bank whose top sits ABOVE the
        // water surface: swim-up tops out AT the surface (~0.6 short of the step-up
        // grab) and a break/pillar can't actuate from deep water either — so it
        // bob-cycles forever (live trace: y6.2 peak → sinks to y4.7, no NET rise).
        // Fix: once stalled with no vertical progress, at a bob peak (feet clear of
        // the water, surface right beneath) place ONE throwaway block to fill that
        // top water cell → a flush foothold the bot rests on, GROUNDED; from solid
        // ground the ordinary climb (step-up / break-carve / pillar) finishes the
        // +1/+2 (verified live). The counter only advances while height is NOT
        // rising, so a working pillar / swim-up that DOES gain height never trips
        // it. Default-ON escape permission + a placeable in hand required; reads
        // only here, so the pathfinder is byte-for-byte unchanged.
        {
            BlockPos cwp = path.get(step);
            boolean waterClimbing = edge != null && cwp.getY() > foot.getY() && !p.onGround()
                    && (p.isInWater() || world.isWater(foot) || world.isWater(foot.below()));
            if (!waterClimbing) {
                waterClimbStall = 0;
                waterClimbBestY = p.getY();
            } else if (p.getY() > waterClimbBestY + 0.3) {
                waterClimbBestY = p.getY();        // real rise → reset the stall (don't fight a working climb)
                waterClimbStall = 0;
            } else {
                waterClimbStall++;
            }
            if (waterClimbing && waterClimbStall > WATER_CLIMB_STALL
                    && BotConfig.allowSwimEscapePlace && ensureHoldingPlaceableAny(mc)) {
                // Locate the top water cell in the bot's column (the foothold to
                // fill) and the surface above it — independent of the bob phase.
                BlockPos topWater = world.isWater(foot) ? foot : foot.below();
                while (world.isWater(topWater.above())) topWater = topWater.above();
                int surfaceY = topWater.getY() + 1;                          // first air above the column
                if (world.isWater(topWater) && Move.hasPlaceSupport(world, topWater)) {
                    if (BotConfig.walkerDebug && waterClimbStall == WATER_CLIMB_STALL + 1)
                        LOG.info("[walker] water climb-out: takeover engaged (bob-stalled), swimming up to foothold {},{},{} surfaceY={}",
                                topWater.getX(), topWater.getY(), topWater.getZ(), surfaceY);
                    // TAKE OVER the keys: a clean swim straight up toward the bank.
                    // The break / walk actuators below otherwise pin the bot low
                    // against the wall (aiming at the break) so it never clears the
                    // place cell. Face the climb node so 'forward' holds the body
                    // against the bank, hold jump to surface, look down to aim.
                    double ax = (cwp.getX() + 0.5) - p.getX();
                    double az = (cwp.getZ() + 0.5) - p.getZ();
                    if (ax * ax + az * az > 1e-4) {
                        float yaw = (float) Math.toDegrees(Math.atan2(-ax, az));
                        p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw;
                    }
                    p.setXRot(40f);
                    mc.options.keyUp.setDown(true);
                    mc.options.keyDown.setDown(false);
                    mc.options.keyLeft.setDown(false);
                    mc.options.keyRight.setDown(false);
                    mc.options.keySprint.setDown(false);
                    p.setSprinting(false);
                    mc.options.keyJump.setDown(true);
                    if (p.getY() >= surfaceY) {                              // feet cleared the place cell
                        walkerPlace(mc, p, world, topWater);                 // fill it → flush, grounded foothold
                        // Re-plan from the (now grounded) surface. Unconditional —
                        // the client place is same-tick, so next tick topWater reads
                        // solid and the outer isWater guard blocks any re-place; the
                        // bot then climbs the bank from solid ground.
                        path = null;
                        stuckTicks = 0;
                        totalTicks = 0;
                        waterClimbStall = 0;
                        waterClimbBestY = p.getY();
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] water climb-out: foothold placed at {},{},{} → repath from grounded",
                                    topWater.getX(), topWater.getY(), topWater.getZ());
                    }
                    return Step.WALKING;
                }
            }
        }

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
            // Buoyant pillar — the bot is rising out of water. Two sub-cases:
            //   (a) FLOODED shaft (the destination cell is itself water): just hold
            //       jump and FLOAT up through it; water follows up so the next
            //       ceiling break repeats until the bot surfaces. No place — a block
            //       would only dam the float.
            //   (b) DRY air above (a partly-mined bunker / 1-deep pocket with an open
            //       chimney): the swim-bob alone never gains permanent height, so on
            //       the crest — when the feet clear the place cell — PLACE a support
            //       there to stand on, lifting the bot one block; after that first
            //       lift it is grounded and the normal dry pillar climbs the rest.
            // Detect water from the world (cell below is water), not p.isInWater(),
            // which flickers false at the bob peak and would drop the jump.
            boolean shaftFlooded = world.isWater(path.get(step));
            if (shaftFlooded || p.isInWater() || world.isWater(path.get(step).offset(0, -1, 0))) {
                mc.options.keyJump.setDown(true);
                if (!shaftFlooded && ensureHoldingPlaceableAny(mc)) {
                    BlockPos wp = edge.toPlace.get(0);
                    p.setXRot(89.5f);                       // look down to aim the support
                    if (p.getY() >= wp.getY() + 0.9) {      // bobbed clear of the place cell
                        clientUseItemOn(mc, p, wp.offset(0, -1, 0), Direction.UP);
                    }
                }
                return Step.WALKING;
            }
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
            // Water-escape break (swimAshore / swimTraverseBreak): while breaking the
            // bank, a FLOATING bot drifts off its foot cell and the edge invalidates
            // before the block breaks — it bobs and never climbs out. We anchor by
            // pressing INTO the bank, but ONLY when the head is at the surface
            // (!isUnderWater). The earlier unconditional keyUp drowned the bot:
            // forward input while SUBMERGED drops it into the prone swim pose and it
            // sinks. Gating on surface means forward can't trigger swim-pose, so it
            // presses the body against the bank + jumps to mount, holding position
            // long enough to finish the dig. (#9 autoSwim still surfaces it each tick.)
            boolean swimEscapeBreak = edge.move != null
                    && (edge.move.startsWith("swimAshore") || edge.move.startsWith("swimTraverseBreak"));
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    selectBestToolFor(mc, b);
                    aimAtBlockSnap(p, b);
                    mc.options.keyAttack.setDown(true);
                    if (swimEscapeBreak && p.isInWater() && !p.isUnderWater()) {
                        mc.options.keyUp.setDown(true);     // press into the aimed bank (surface only)
                        if (edge.move.startsWith("swimAshore"))
                            mc.options.keyJump.setDown(true);   // rise to mount the +1
                    }
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
        if (!aimAtWaypoint && stuckTicks > 5 && step > 0) {
            BlockPos sp = path.get(step - 1);
            boolean onSpine = foot.getX() == sp.getX() && foot.getZ() == sp.getZ();
            if (!onSpine && losWalkable(world, foot, sp)) {
                recX = (sp.getX() + 0.5) - p.getX();
                recZ = (sp.getZ() + 0.5) - p.getZ();
                reCentre = true;
            }
        }
        double adx, adz;
        if (aimAtWaypoint) {
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
        } else if (reCentre) {
            adx = recX; adz = recZ;
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
        // Lateral lane-keeping STRAFE: on a flat cardinal walk, hold the cross-axis
        // at the lane centre with a sideways strafe so the body clears a flush 1-wide
        // channel wall WITHOUT turning off the forward heading. Pure yaw steering
        // can't do both (turning to centre kills forward progress, so the bot only
        // creeps and grinds the wall); strafing centres while forward still drives
        // it down the lane. Projects the cross-axis error onto the player's right
        // vector to pick the key. Skipped during leaps/brakes/bridging.
        boolean strafeL = false, strafeR = false;
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
        if (!descendBrake && !parkourEdge && !steppingOffFall && (wp.getY() == foot.getY() || waterClimb)) {
            int ddx = wp.getX() - foot.getX();
            int ddz = wp.getZ() - foot.getZ();
            double latX = 0, latZ = 0;
            if (waterClimb) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // centre on the target column
            else if (ddx == 0 && ddz != 0) latX = (wp.getX() + 0.5) - p.getX();        // N/S lane → hold X
            else if (ddz == 0 && ddx != 0) latZ = (wp.getZ() + 0.5) - p.getZ();   // E/W lane → hold Z
            if (Math.abs(latX) > 0.06 || Math.abs(latZ) > 0.06) {
                double yr = Math.toRadians(p.getYRot());
                double fx = -Math.sin(yr), fz = Math.cos(yr);   // forward unit (x,z)
                double rx = -fz, ry = fx;                       // player's right = forward rot +90°
                double dotR = latX * rx + latZ * ry;
                if (dotR > 0.04) strafeR = true;
                else if (dotR < -0.04) strafeL = true;
            }
        }
        mc.options.keyLeft.setDown(strafeL);
        mc.options.keyRight.setDown(strafeR);
        // Bridging a chasm one placed block at a time: sneak (so a sprint
        // overshoot can't carry the bot off the fresh 1-wide block into the
        // gap ahead) and don't sprint. Triggered when the edge we're walking
        // onto OR the next one is a place-bridge — Baritone sneaks for the
        // same reason. Released the moment we're back on real ground.
        boolean bridging = (edge != null && "bridgePlace".equals(edge.move))
                || (nextEdge != null && "bridgePlace".equals(nextEdge.move));
        // Lethal-edge sneak-brake (DEATH #8 fix): if a fatal drop is one step ahead
        // in the heading, hold sneak so vanilla's ledge-guard pins the body at the
        // block edge — the controller can no longer drift off a cliff while fleeing
        // or walking a lip. Lethal-only, so it never blocks a legitimate planned
        // step-down (those are capped at survivableFall by the PathFinder).
        boolean edgeBrake = BotConfig.lethalEdgeBrake && p.onGround()
                && lethalDropAdjacent(world, p, foot);
        mc.options.keyShift.setDown(bridging || descendBrake || edgeBrake);
        p.setShiftKeyDown(bridging || descendBrake || edgeBrake);
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
        boolean diving = edge != null && edge.move != null && edge.move.startsWith("swimDown");
        boolean swimUp = p.isInWater() && p.isUnderWater() && !diving;
        // The stuck-wiggle hop unsticks a corner on DRY land, but in shallow water
        // on a flat walk it just bobs the bot off the floor into the buoyant drift
        // (it floats off its cell and slides — the very stall it's meant to break).
        // Suppress it there; treading + steady forward threads the channel instead.
        boolean flatWaterWalk = wp.getY() == foot.getY() && p.isInWater();
        boolean wiggle = !bridging && !flatWaterWalk && stuckTicks > 10 && stuckTicks < 18;
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
        boolean jump = !descendBrake
                && (wp.getY() > foot.getY() || parkourEdge || swimUp || wiggle || swimColumn);
        mc.options.keyJump.setDown(jump);
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
        boolean sprint = !bridging && !steppingOffFall && !steppingOffWaterFall
                && !descendBrake && !edgeBrake && (!p.isInWater() || flatWaterWalk);
        mc.options.keySprint.setDown(sprint);
        p.setSprinting(sprint);
        if (BotConfig.walkerDebug)
            LOG.info("[walker] walk-keys yaw={} wp={},{},{} up={} jump={} sprint={} sneak={} attack={} swimUp={} descBrake={}",
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    wp.getX(), wp.getY(), wp.getZ(),
                    mc.options.keyUp.isDown(), mc.options.keyJump.isDown(), mc.options.keySprint.isDown(),
                    mc.options.keyShift.isDown(), mc.options.keyAttack.isDown(), swimUp, descendBrake);
        return Step.WALKING;
    }

    /** Fire onTerminal and return the step verdict in one place, so every terminal
     *  return site stays a one-liner. */
    private Step terminal(Step s, PathTrace.Outcome outcome, String reason) {
        PathTraceHolder.SINK.onTerminal(outcome, reason);
        return s;
    }

    /** Emit a per-tick execution sample. Pure reads; cheap; gated to NOOP in release. */
    private void sampleTick(LocalPlayer p) {
        // Cheap gate: skip the per-tick WalkerSample allocation entirely unless capture is on.
        // Keeps the hot path free in normal play and in a stripped (NOOP) release build.
        if (!BotConfig.pathDebug) return;
        double tx = Double.NaN, tz = Double.NaN;
        String mv = null;
        if (path != null && step >= 0 && step < path.size()) {
            BlockPos t = path.get(step);
            tx = t.getX() + 0.5;
            tz = t.getZ() + 0.5;
            Move.Edge e = edgeAt(step);
            mv = (e != null) ? e.move : null;
        }
        PathTraceHolder.SINK.onWalkerTick(new PathTrace.WalkerSample(
                p.tickCount, p.getX(), p.getY(), p.getZ(), p.getYRot(),
                tx, tz, step, mv, p.onGround(), p.isInWater()));
    }

    private static float angleDiff(float a, float b) { return ((b - a) % 360f + 540f) % 360f - 180f; }

    /** Horizontal neighbour offsets (4 cardinals + 4 diagonals) of the foot cell. */
    private static final int[][] EDGE_NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** True if a LETHAL drop borders the cell the bot is standing on — any horizontal
     *  neighbour that is an open foot-cell with no floor, falling to the next solid/
     *  water surface deeper than {@link SurvivalMath#survivableFall} at the bot's HP.
     *  Checking ALL neighbours (not just the heading) catches lateral/momentum drift
     *  off a lip while walking ALONG it — the actual DEATH #8 mode. Vanilla sneak then
     *  pins the body to this block in every direction. Lethal-only, so it never blocks
     *  a legitimate planned step-down (those land within survivable, or in water). */
    private static boolean lethalDropAdjacent(WorldView world, LocalPlayer p, BlockPos foot) {
        int survivable = SurvivalMath.survivableFall(p.getHealth());
        for (int[] o : EDGE_NEIGHBOURS) {
            BlockPos n = foot.offset(o[0], 0, o[1]);
            // A drop needs the foot-cell AND the cell below it both open (no floor).
            // A present floor = flat walk or a safe 1-block step-down; water = a splash.
            if (world.isSolid(n) || world.isWater(n)) continue;
            BlockPos below = n.below();
            if (world.isSolid(below) || world.isWater(below)) continue;
            int fall = 1;
            BlockPos pr = below.below();
            while (fall <= survivable + 2 && !world.isSolid(pr) && !world.isWater(pr)) {
                fall++;
                pr = pr.below();
            }
            if (fall > survivable) return true;
        }
        return false;
    }

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
