package net.magicterra.worlddriver.bot.control.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.magicterra.worlddriver.bot.control.route.PathSmoothing.SmoothResult;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.minecraft.core.BlockPos;

import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.losWalkable;
import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.stringPull;
import static net.magicterra.worlddriver.bot.control.WalkerGeometry.isOpenWaterLine;
import static net.magicterra.worlddriver.config.Mod.LOG;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerInspector;

/**
 * Turning a search result into the walk's plan: anchor it to where the body is, straighten it, cut a
 * best-effort tail back to firm ground, skip the prefix already walked, and install it — or refuse it,
 * and say why. The answer is a {@link Route.Adoption} the caller has to look at: a refused result leaves
 * the old plan in place, which is a different situation from "adopted" for everything that comes after.
 */
public final class RouteAdoption {
    private RouteAdoption() {}

    /**
     * Adopt {@code res} as the plan, judged against {@code foot}; a null {@code foot} skips the anchor
     * gate and the fast-forward (a stub built at the feet, or a test that stages its own step).
     *
     * <p>Refused when its start is nowhere near the feet. A continuation is computed from the previous
     * segment's commitEnd; when the bot never actually made it there (fumbled the climb, fell off the
     * route) the spliced path STARTS in mid-air several blocks away — step 1 is unreachable,
     * fast-forward finds nothing near, and the bot just hangs against the wall until the wedge timer
     * fires (live 2026-06-09: cave pocket, step1 4 blocks up / 4.5 away, 15 s freeze). Refused too when
     * the world changed under its first edge while the search ran.
     */
    public static Route.Adoption adopt(Walker wk, PathFinder.Result res, WorldView world, BlockPos foot) {
        Route route = wk.route;
        if (foot != null && !res.path().isEmpty()) {
            String misanchored = misanchored(world, res, foot);
            if (misanchored != null) return Route.Adoption.refused(misanchored);
        }
        if ((res = PathSmoothing.dropStalePrefix(world, res)).path().size() < 2)
            return Route.Adoption.refused("stale: the world changed under its first edge while it was searched");
        // String-pull flat walk runs so the heading stays steady over the staircase (no left-right
        // camera wobble) and the bot walks straight; action/vertical/parkour nodes are preserved.
        wk.rawPlan = WalkerInspector.tallyOf(res.path(), res.edges());
        SmoothResult sm = stringPull(world, res.path(), res.edges(), wk.profile.bias());
        List<BlockPos> path = sm.path;
        List<Move.Edge> edges = sm.edges;
        boolean bestEffort = !res.goalReached();
        if (bestEffort) {
            int cut = commitTailCut(world, path);
            if (cut > 0) {
                path = List.copyOf(path.subList(0, cut + 1));
                edges = Collections.unmodifiableList(new ArrayList<>(edges.subList(0, cut + 1)));
            }
        }
        wk.search.gov.noPathWaitTicks = 0;            // a segment was found → the no-path wait starts over
        // A segment that ends FARTHER from the goal than it starts can only be
        // the last-resort ESCAPE (every other selector is strictly goal-ward):
        // its start is a PROVEN dead pocket. Charge it now so subsequent searches
        // stop folding back into it — without this the cave-funnel relapse loop
        // is fully deterministic (live 2026-06-09: escape→high route→fall→free
        // h-improving corridors all funnel back to the same pocket, ~3 min/lap,
        // and nothing on the lap ever calls penalizeStuckNode).
        if (wk.goal != null && path.size() > 1
                && wk.goal.estimate(path.get(path.size() - 1)) > wk.goal.estimate(path.get(0))) {
            world.penalizeStuckNode(path.get(0));
            world.penalizeStuckNode(path.get(0).above());
            if (GameConfig.walkerDebug)
                LOG.info("[walker] escape segment adopted → penalize dead pocket at {}", path.get(0));
        }
        int step = foot == null ? 1 : fastForward(world, path, edges, foot);
        route.install(path, edges, step, bestEffort);
        restartFollowing(wk, foot == null);
        if (GameConfig.walkerDebug) {
            StringBuilder sbp = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                Move.Edge e = i < edges.size() ? edges.get(i) : null;
                sbp.append(i).append(':').append(path.get(i).getX()).append(',').append(path.get(i).getY())
                   .append(',').append(path.get(i).getZ()).append('[').append(e != null ? e.move : "-").append("] ");
            }
            LOG.info("[walker] path = {}", sbp);
        }
        return Route.Adoption.ADOPTED;
    }

    /** Why {@code res} does not start near {@code foot}, or null when it does. */
    private static String misanchored(WorldView world, PathFinder.Result res, BlockPos foot) {
        // Anchor on the nearest node of the PREFIX, not just path[0]. While a
        // search runs (0.4-1.7 s) a SWIMMING bot keeps drifting 5-12 blocks
        // along its old segment — both routes head for the same goal, so the
        // fresh path's prefix IS the corridor the bot just swam. Judging only
        // path[0] turns that drift into a reject→re-search→drift-again
        // oscillation (live 2026-06-10: 6 rejects in 18 s, 35 min circling a
        // water-shore pocket, the 75-node highland exit killed twice). A
        // truly mis-anchored continuation (computed from a seg.commitEnd the bot
        // never reached) has its WHOLE prefix far/high, so it still rejects.
        List<BlockPos> raw = res.path();
        int anchor = 0;
        double anchorD = raw.get(0).distSqr(foot);
        int lim = Math.min(raw.size() - 1, 16);
        for (int i = 1; i <= lim && anchorD > 1.0; i++) {
            Move.Edge e = i < res.edges().size() ? res.edges().get(i) : null;
            if (e != null && (!e.toBreak.isEmpty() || !e.toPlace.isEmpty())) break;
            double d2 = raw.get(i).distSqr(foot);
            if (d2 < anchorD) { anchorD = d2; anchor = i; }
        }
        // In WATER the gate must absorb CURRENT drift: a river pushes the
        // bot ~1.5 b/s downstream while it holds path-less, so by the time
        // a 0.4-1.7 s search lands the feet are 5-7 blocks away — with the
        // dry 4-block gate every fresh segment got rejected, the hold let
        // the river push further, and the loop swept the bot 50 blocks
        // downstream (live 2026-06-10: 10 rejects in 30 s straight down
        // z=-561, each segment's nearest prefix node = the previous foot).
        // Swimming 8 blocks back onto the route is always executable; the
        // tight gate only exists for dry falls/jumps that aren't.
        double rejectGate = world.isWater(foot) ? 64 : 16;
        // DEEP-WATER-FLOAT BEE-LINE EXEMPTION (live #47 deterministic dead-stop): a bot floating in
        // deep open water commits a best-effort segment whose continuation (from seg.commitEnd) starts a
        // long clear-LOS open-water WALK node tens of blocks ahead — the only intermediate node is an
        // IN-PLACE swimUp (same XZ as the foot), so the anchor finds NO near forward node and rejects
        // (d2≈2300). The reject drops the path, the foot-search returns the SAME swimUp+far-walk
        // best-effort, and it re-pins → 13 repaths, no progress, hSpd=0, anti-spin ends best-effort
        // (~6 s dead-stop; telemetry "reject mis-anchored: -812,62 (d2=2305) vs foot -860,58"). But
        // that far node IS reachable: the buoyant bot can swim a STRAIGHT line to it over open water
        // (no wall, all water/air). Accepting the segment lets the carrot bee-line toward the far node
        // and make forward progress (the deep-water-float analog of the quick-start stub / water
        // bee-line). STRICTLY gated so it can NOT exempt a truly mis-anchored / walled / climb segment:
        //   • the foot must be FLOATING in water (the rejectGate's own water test);
        //   • the straight line foot→anchor must be a CLEAR OPEN-WATER bee-line (losWalkable AND every
        //     sampled cell water/air — a bank, ledge or dry walkway fails it);
        //   • the anchor node must sit within ±4 Y of the floating foot — a SURFACE crossing the buoyant
        //     bot swims up+across to (the live foot bobs y58↔61 under a y62 surface node, dy up to 4),
        //     never an impossible bank climb (a real climb-out has a solid bank IN the line → the
        //     open-water LOS already rejects it, so this band only bounds the swim-up reach).
        // A genuinely fumbled continuation (computed from a seg.commitEnd behind a wall / up a cliff the
        // bot never reached) fails the open-water LOS or the ±4 Y band, so it still rejects.
        // The exemption suppresses BOTH reject conditions (the d2 distance gate AND the |Δy| jump gate):
        // for an open-water bee-line the Y difference is a buoyant swim-UP to the surface node, not a dry
        // jump/fall, so the maxJumpUp gate (which exists to bar impossible dry climbs) must not veto it.
        boolean openWaterBeeline = ControlConfig.walkerDeepWaterFloatBeeline
                && world.isWater(foot)
                && Math.abs(raw.get(anchor).getY() - foot.getY()) <= 4
                && losWalkable(world, foot, raw.get(anchor))
                && isOpenWaterLine(world, foot, raw.get(anchor));
        if (!openWaterBeeline
                && (anchorD > rejectGate
                    || Math.abs(raw.get(anchor).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2)) {
            String why = String.format(java.util.Locale.ROOT,
                    "mis-anchored: nearest prefix node %d,%d,%d (d2=%d) vs foot %d,%d,%d",
                    raw.get(anchor).getX(), raw.get(anchor).getY(), raw.get(anchor).getZ(),
                    (int) anchorD, foot.getX(), foot.getY(), foot.getZ());
            if (GameConfig.walkerDebug) LOG.info("[walker] reject {}", why);
            return why;
        }
        if (openWaterBeeline && anchorD > rejectGate && GameConfig.walkerDebug)
            LOG.info("[walker] accept open-water bee-line segment: far node {},{},{} (d2={}) reachable by clear-LOS swim from foot {},{},{}",
                    raw.get(anchor).getX(), raw.get(anchor).getY(), raw.get(anchor).getZ(),
                    (int) anchorD, foot.getX(), foot.getY(), foot.getZ());
        return null;
    }

    /**
     * The node to head for first. Fast-forward past a prefix the bot has already covered. A quick-start
     * stub (progressive pathfinding) carries the bot a few blocks ahead of where the superseding big
     * search was LAUNCHED, so the fresh path can start behind the feet — without this the bot visibly
     * walks BACKWARD to rejoin at path[0]. Skip to the nearest on-path node in the first few steps, but
     * never across an edge that still needs to break/place blocks (skipping its action would desync the
     * build state). Only jump when a node is genuinely at the feet; a normal foot-search has
     * path[0] == foot and is untouched.
     */
    private static int fastForward(WorldView world, List<BlockPos> path, List<Move.Edge> edges, BlockPos foot) {
        int step = 1;
        if (path.size() <= 2) return step;
        // Loose rejoin ONLY when path[0] is already off the feet (the bot
        // drifted while the search ran — open water, quick-start carry).
        // A normally-anchored path keeps the strict gate: skipping to a
        // node 2-3 blocks out also skips the stair base / bridge lip
        // between here and there.
        boolean drifted = path.get(0).distSqr(foot) > 2.0;
        int window = drifted ? 16 : 8;
        // In water, accept a rejoin node as far as the reject gate lets a
        // segment in (river drift, see adopt-gate above): swimming back a
        // few blocks is always executable, and refusing leaves step=1
        // pointing far UPSTREAM of a drifted bot.
        double accept = drifted ? (world.isWater(foot) ? 64.0 : 9.0) : 2.0;
        int nearest = step;
        double nearestD = path.get(step).distSqr(foot);
        for (int i = step + 1; i < Math.min(path.size() - 1, step + window); i++) {
            Move.Edge e = i < edges.size() ? edges.get(i) : null;
            if (e != null && (!e.toBreak.isEmpty() || !e.toPlace.isEmpty())) break;
            // Never anchor onto a node beyond ±1 of the feet: a drifted bot
            // in water sits 2 below the bank nodes (3D distSqr still ranks
            // them "near", dy=2 → +4) — starting there is an impossible +2
            // climb (live: wedged at y62 vs a y64 diagUp for 100 ticks).
            // DOWN must be capped too: anchoring onto a node 5 BELOW a
            // cliff-top bot aims the walk at the column past the lip and it
            // just rams the wall (live: wp walk@y57 vs feet y62, hCol,
            // hSpd=0). ±1 keeps the rejoin on the bot's own walking layer.
            int ffDy = path.get(i).getY() - foot.getY();
            // A V-shaped underwater path (dive → ride the bed → climb out)
            // overlaps itself in XZ, so the climb-out branch ranks "nearest"
            // and skipping flattens the V: the bot anchors onto the node
            // ABOVE its head, the dive trigger never arms (wp not below),
            // and it pins against the well wall (live round46: wp=(3156,64)
            // vs feet y62, hCol, hSpd=0, 17 bursts in 2 min). A submerged
            // valley node is a hard rejoin barrier — stop the scan there:
            // everything beyond is only reachable THROUGH the dive.
            if (ffDy < -1 && world.isWater(path.get(i))) break;
            if (Math.abs(ffDy) > 1) continue;
            double d2 = path.get(i).distSqr(foot);
            if (d2 < nearestD) { nearestD = d2; nearest = i; }
        }
        return nearestD <= accept ? nearest : step;
    }

    /**
     * Restart what the follower measured against the old geometry. These counters belong to whoever
     * follows the plan, not to the plan, and they restart because the cells they were timing are gone.
     */
    private static void restartFollowing(Walker wk, boolean stub) {
        wk.stuckTicks = 0;
        wk.progress.stepProg.stuckStep = -1;        // new path geometry → restart the progress window
        wk.progress.stepProg.noStepProgressTicks = 0;   // new path → restart the wedge timer (else a same-index step re-triggers instantly)
        wk.progress.stepProg.noProgressStep = -1;
        // A freshly adopted PROGRESSIVE stub (quick-start / open-water bee-line —
        // the only adopts with foot==null) is a brand-new runway in a NEW
        // direction, so the wedge-burst counter accrued against the stale segment
        // is no longer valid: leaving it set fires a forced-displacement burst
        // ~1 s after adoption (the 40-tick count cooldown was already armed),
        // which yanks the bot BACKWARD off the runway it just got — live
        // 2026-06-15 wide-water run: bee-line adopted then burst 1 s later, on
        // repeat, crabbing the bot the WRONG way along the shore. Clear it so the
        // stub gets a clean ~6 s trial; if the bot genuinely can't follow it the
        // counter simply re-arms and bursts as before. The big-search continuation
        // (foot != null) keeps its burst intact — that's the deterministic
        // same-segment deadlock breaker and must not be reset away.
        if (stub) wk.recovery.dropWedgeAnchor();
        wk.progress.stepProg.bestStepDist = Double.POSITIVE_INFINITY;
        wk.progress.stepProg.stuckStepHigh = wk.route.step() - 1;   // new path, new index semantics: one fresh window on the first tick, then high-water applies
        wk.actionTicks = 0;
    }

    /** Where a best-effort segment's commit stops, which is where the continuation launches from; 0 to
     *  keep the whole segment.
     *  §93 platform retreat (#15 final lane): the tail is wherever the node budget ran out — often
     *  MID-SLOPE on complex steep terrain. The bot then climbs to a half-mounted ledge, the periodic
     *  repath re-plans from that awkward stance, and the climb-fall oscillation burns ~100s
     *  (slow-map zones; the SAME terrain runs clean in 8s standalone, §92b — the grind is planner
     *  state, not executor skill). When the tail node is not a platform (fewer than 2 same-Y
     *  standable cardinal neighbours), retreat up to 8 nodes to the nearest platform node so the
     *  segment ends on ground the executor can stand square on while the next search runs.
     *  Ridge retreat: see {@link PathSmoothing#sunkTailCut}. */
    private static int commitTailCut(WorldView world, List<BlockPos> path) {
        int cut = -1;
        if (ControlConfig.walkerCommitTailPlatform && path.size() > 4) {
            for (int k = path.size() - 1; k >= Math.max(2, path.size() - 8) && cut < 0; k--) {
                BlockPos n = path.get(k);
                int flat = 0;
                for (int[] d4 : new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                    if (world.canStandAt(n.offset(d4[0], 0, d4[1]))) flat++;
                if (flat >= 2) cut = k;
            }
        }
        int peak = ControlConfig.walkerCommitTailPeak ? PathSmoothing.sunkTailCut(path) : -1;
        if (peak > 0 && (cut < 0 || peak < cut)) cut = peak;
        if (cut <= 0 || cut >= path.size() - 1) return 0;
        if (GameConfig.walkerDebug)
            LOG.info("[walker] commit-tail retreat: {} -> {} ({} {},{},{})", path.size() - 1, cut,
                    cut == peak ? "ridge" : "platform", path.get(cut).getX(), path.get(cut).getY(), path.get(cut).getZ());
        return cut;
    }
}
