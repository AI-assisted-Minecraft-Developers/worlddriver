package net.magicterra.worlddriver.bot.control.route;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.BEELINE_MAX_STEPS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.BEELINE_MIN_STEPS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.QUICK_MAX_MS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.QUICK_RETRY_TICKS;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.isDryWalkable;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.isOpenSurfaceWater;
import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorFinders;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.minecraft.core.BlockPos;

/**
 * Short plans walked while the real search is still running: a quick bounded search, or a greedy march over open water
 * or flat ground. Each goes through {@link RouteAdoption#adopt} like any other result and reports what that said — a
 * stub the adoption refused is no stub, and a caller that walked on as if it had one dereferenced a plan that was never
 * installed.
 */
final class RouteStubs {
    private RouteStubs() {}

    /**
     * PROGRESSIVE QUICK-START (progressive pathfinding): the big re-plan is still slicing in the background and the bot
     * has nothing to walk — that gap is the visible inter-segment freeze (several seconds standing still at every hard
     * obstacle). Spend a tiny SYNCHRONOUS node budget on a short best-effort segment toward the goal and start walking
     * it right now; the big result supersedes the stub when it lands through the normal adoption path. Frame cost is
     * irrelevant here: with no path the bot is frozen anyway (same argument as the idle-slice). Only a stub that
     * actually nears the goal is adopted — a boxed-in micro-search returns pacing scraps that would jitter, so those
     * back off for {@link NavigatorConstants#QUICK_RETRY_TICKS}.
     *
     * @return true if a stub segment was adopted (path != null, step = 1).
     */
    // The search runs inside the loop's condition: each call advances it until the node cap or the time slice ends it.
    @SuppressWarnings("PMD.EmptyControlStatement")
    static boolean quickStart(Navigator nav, WorldView world, BlockPos foot, Goal goal) {
        if (ControlConfig.pathfinderQuickNodes <= 0) {
            return false;
        }
        if (nav.search.gov.quickCooldown > 0) {
            nav.search.gov.quickCooldown--;
            return false;
        }
        PathFinder.Search q = NavigatorFinders.quick(nav, world, QUICK_MAX_MS).newSearch(foot, goal);
        while (!q.advance(QUICK_MAX_MS)) {
            /* bounded by the node cap / QUICK_MAX_MS */
        }
        PathFinder.Result res = q.result();
        boolean useful = res.hasPath()
                && res.path().size() > 1
                && goal.estimate(foot) - goal.estimate(res.path().get(res.path().size() - 1)) >= 2.0;
        if (!useful) {
            nav.search.gov.quickCooldown = QUICK_RETRY_TICKS;
            return false;
        }
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[navigator] quick-start stub adopted: len={} expanded={} ms={} (big search still running)",
                    res.path().size(),
                    res.expanded(),
                    res.ms());
        }
        return RouteAdoption.adopt(nav, res, world, null).adopted(); // stub starts at the foot — no fast-forward needed
    }

    /**
     * PROGRESSIVE OPEN-WATER BEE-LINE stub: over a wide deep-water crossing the sliced A* re-plan is expensive — it
     * expands the whole 3-D water volume (canStandAt accepts every depth), thousands of nodes over several seconds, and
     * even a bounded {@link #quickStart} can't progress 2 blocks. So the bot consumes its short committed segment, has
     * no fresh forward path, wedges, and anti-stuck bursts crab it across jerkily (live 2026-06-15 wide-water run:
     * burst every ~6 s, the bot 30+ blocks past a stale segment, max step-stuck 106). A flat open-water crossing needs
     * no search: greedily march toward the goal along the SURFACE — at each cell take the 8-neighbour that most reduces
     * goal.estimate and is open surface water — and adopt that straight run as a long stub so the bot keeps swimming
     * smoothly while the big search lands and supersedes it. A greedy local minimum is harmless: the stub is a stopgap,
     * replaced the instant the real path arrives; it only ever drives the bot over open water it could swim anyway.
     * Gated to a body of water under the feet. Tried BEFORE {@link #quickStart} in water: under the surface water model
     * the quick search succeeds there, and its first node follows the bob (a swimUp when the foot is sunk, a stepDown
     * when it rides high), so each adoption re-aimed the jump/sneak and the crossing became a piston. The march starts
     * from the SURFACE cell of the foot's column, whichever way the bob has the foot at this tick. @return true if a
     * bee-line was adopted.
     */
    static boolean waterBeeline(Navigator nav, WorldView world, BlockPos foot, Goal goal) {
        if (!world.isWater(foot)) {
            return false;
        }
        BlockPos surface = isOpenSurfaceWater(world, foot)
                ? foot
                : isOpenSurfaceWater(world, foot.above())
                        ? foot.above()
                        : isOpenSurfaceWater(world, foot.below()) ? foot.below() : foot;
        List<BlockPos> path = march(surface, goal, n -> isOpenSurfaceWater(world, n));
        if (path.isEmpty()) {
            return false;
        }
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[navigator] open-water bee-line stub adopted: len={} toward goal (big search still running)",
                    path.size());
        }
        return RouteAdoption.adopt(nav, walkOnly(path), world, null).adopted();
    }

    /**
     * PROGRESSIVE LAND COARSE-DIRECTION stub: the dry-land twin of {@link #waterBeeline}. Over open / gently-sloped
     * land the big sliced A* re-plan can burn thousands of nodes before it commits, leaving the bot frozen at the
     * start-of-segment gap (the visible startup / inter-segment churn). No search is needed to START moving the right
     * way: greedily march toward the goal — at each cell take the 8-neighbour, at the SAME Y, that most reduces
     * {@code goal.estimate} and is safely STANDABLE on dry ground — and adopt that run as a coarse stub the bot walks
     * immediately while the big search runs and supersedes it. A greedy local minimum (a wall, a slope, a cliff, water)
     * is harmless: the march simply stops there and the real path replaces the stub the instant it lands; the stub only
     * ever drives the bot over flat ground it could walk anyway. Same-Y only (like the water bee-line) so it never
     * steps off a ledge or rams a riser — slopes hand back to A*. Gated to {@link ControlConfig#pathfinderProgressive}
     * + dry land under the feet (water is {@link #waterBeeline}'s job). @return true if a land run was adopted.
     */
    static boolean landBeeline(Navigator nav, WorldView world, BlockPos foot, Goal goal) {
        if (!ControlConfig.pathfinderProgressive) {
            return false;
        }
        if (world.isWater(foot)) {
            return false; // water is waterBeeline's job
        }
        List<BlockPos> path = march(foot, goal, n -> isDryWalkable(world, n));
        if (path.isEmpty()) {
            return false;
        }
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[navigator] land coarse-direction stub adopted: len={} toward goal (big search still running)",
                    path.size());
        }
        return RouteAdoption.adopt(nav, walkOnly(path), world, null).adopted();
    }

    /**
     * Greedy same-level march from {@code from} toward {@code goal} over cells {@code ok} accepts; an empty list when
     * it is too short to be worth a stub.
     */
    private static List<BlockPos> march(BlockPos from, Goal goal, java.util.function.Predicate<BlockPos> ok) {
        List<BlockPos> path = new ArrayList<>();
        path.add(from);
        BlockPos cur = from;
        double curEst = goal.estimate(from);
        for (int i = 0; i < BEELINE_MAX_STEPS; i++) {
            BlockPos best = null;
            double bestEst = curEst;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos n = cur.offset(dx, 0, dz);
                    if (!ok.test(n)) {
                        continue;
                    }
                    double e = goal.estimate(n);
                    if (e < bestEst) {
                        bestEst = e;
                        best = n;
                    }
                }
            }
            if (best == null) {
                break; // no goal-ward step (boxed / a bank / slope / wall / cliff)
            }
            path.add(best);
            cur = best;
            curEst = bestEst;
        }
        return path.size() <= BEELINE_MIN_STEPS ? List.of() : path; // too short to be worth a stub
    }

    /** A best-effort result walking {@code path} node to node, the first node carrying no edge. */
    private static PathFinder.Result walkOnly(List<BlockPos> path) {
        List<Move.Edge> edges = new ArrayList<>();
        edges.add(null); // start node carries no inbound edge
        for (int i = 1; i < path.size(); i++) {
            edges.add(new Move.Edge(path.get(i), 10, List.of(), List.of(), "walk"));
        }
        return new PathFinder.Result(path, edges, false, 0, 0L, 0.0);
    }
}
