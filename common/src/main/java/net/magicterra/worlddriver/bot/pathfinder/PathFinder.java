package net.magicterra.worlddriver.bot.pathfinder;

import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import net.magicterra.worlddriver.bot.BotConfig;
import java.util.Arrays;

/**
 * A* over BlockPos with the {@link Move} catalog as the neighbor function.
 *
 * Single-threaded, single-shot: {@link #findPath} runs to completion. Caller
 * is responsible for off-thread invocation if the path may be large.
 * Termination conditions, in order:
 *   1. Goal reached → return optimal path.
 *   2. Node budget exhausted → return a best-effort segment (see below).
 *   3. Wall-clock budget exhausted → same as (2).
 *   4. Open set empty (unreachable) → same as (2), or empty if boxed in.
 *
 * Best-effort fallback matters because Minecraft worlds are mostly unreachable
 * in some direction (caves, walls, water) and we'd rather make progress than
 * stand still. The fallback uses Baritone's <b>incremental cost backoff</b>
 * rather than naively returning the single lowest-h node: a node that shaves a
 * hair off the heuristic by wandering far away is a poor segment to commit to,
 * so we track the best node under several g-vs-h weightings (COEFFICIENTS) and
 * commit to the most conservative one that still travels at least
 * {@link #MIN_DIST_PATH} blocks from the start. Repeated {@code findPath} calls
 * from successive segment ends give Baritone-style long-distance splicing for
 * free.
 */
public final class PathFinder {

    public static final int DEFAULT_MAX_NODES = 100_000;
    public static final long DEFAULT_MAX_MS = 1500;

    /**
     * Incremental cost backoff weightings (Baritone's {@code COEFFICIENTS}).
     * For coefficient {@code c} we track the node minimizing {@code h + g/c}:
     * a small c weights travelled cost heavily (prefer staying near the start,
     * a safe commit), a large c almost ignores it (prefer the node closest to
     * the goal, an aggressive commit). On early exit we pick the smallest-c
     * (most conservative) candidate that still made real progress.
     */
    private static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};

    /** Min straight-line distance (blocks) a best-effort segment must cover to
     *  be worth committing to — Baritone's {@code MIN_DIST_PATH}. Shorter
     *  candidates are treated as "no real progress" and rejected. */
    public static final double MIN_DIST_PATH = 5;

    /** Minimum heuristic reduction (cost units ≈ 5 blocks toward the goal) a
     *  loaded-chunk frontier node must offer over the start before a frontier
     *  segment is committed — so a sideways/backward chunk edge never pulls the
     *  bot off course (see {@link BotConfig#pathfinderFrontierCommit}). */
    private static final double MIN_FRONTIER_GAIN = 50;

    /** Min height (blocks) a vertical-escape segment must climb above the start to
     *  be committed when the search is BOXED (no horizontal forward progress) — the
     *  user-chosen "pillar-up / dig-up over the obstacle" escape from a local-minimum
     *  pinch (bot wedged at the foot of a tall cliff, conservative selectSegment
     *  returns null and it deadlocks). Pure up + forward, so no backtrack/oscillation;
     *  height is monotone across the re-plan chain. */
    private static final int MIN_CLIMB_ESCAPE = 2;

    /** g-cost ceiling for a water-start climb-out to count as CHEAP (dig-free):
     *  swimming runs ~10-30 cost/block so 3000 covers a ~100-block swim-and-walk
     *  to shore, while one submerged hard-stone dig alone prices ~7500 (5×
     *  underwater multiplier). Cheap ashore commits immediately; an expensive one
     *  defers to the surfacing bestClimb / relaxed tier (see chooseSegment). A
     *  couple of soft submerged digs (dirt ≈750 each) still pass as cheap — mud
     *  banks are common and fast to punch through. */
    private static final double ASHORE_CHEAP_G = 3000;

    /** A repropagated route must beat the incumbent g by more than this to be
     *  accepted — Baritone's minimum-improvement repropagation (0.01 ticks ≈
     *  0.1 cost units here). Re-opening a closed node to save a sliver of cost
     *  costs more CPU in repropagation than the path-time it buys. */
    private static final double MIN_IMPROVEMENT = 0.1;

    /** Max ground loss a last-resort escape node may carry over the start when the
     *  search stopped on BUDGET rather than exhausting the graph — a budget-stopped
     *  escape may move sideways around the pocket but not walk meaningfully AWAY
     *  from the goal (gap#63: raw farthest-node commits ran a deep-cave bot 75
     *  blocks the wrong way per repath, drift churn). DIST is in blocks against a
     *  concrete {@link Goal#targetPos} (straight-line — immune to the Chebyshev
     *  estimate's free lateral drift inside the goal's Y-span); H is the cost-unit
     *  fallback (~10/block) for open goals whose estimate is the ground metric. */
    private static final double ESCAPE_DIST_SLACK = 2;
    private static final double ESCAPE_H_SLACK = 20;

    /** How many node expansions between wall-clock checks in a time-sliced search.
     *  Small enough that a slice can't overshoot {@code sliceMs} by much even when
     *  each expansion is doing slow world/chunk access, large enough that the
     *  {@code nanoTime()} cost stays negligible (~30ns × this ÷ work). */
    private static final int TIME_CHECK_INTERVAL = 16;

    /** Hard wall-clock ceiling for ONE search, across all its slices — see the check in
     *  {@code advance}. Eight seconds: far above any search this mod makes on a machine that is
     *  keeping up (the worst measured healthy whole-fight walker tick is single-digit milliseconds,
     *  and a 100k-node search finishes well inside a minute even uncontended), and far below the
     *  60 s {@code max-tick-time} the server hang watchdog kills on. It exists so a pathological
     *  search FAILS instead of taking the JVM with it. */
    private static final long CEILING_MS = 8_000;

    /** How often one {@code advance()} call reports that it is still running — see the HEARTBEAT
     *  note in {@code advance}. One second sits two orders of magnitude above the worst iteration
     *  ever measured (21.4 ms) and far below the 60 s watchdog, so it is silent in every run that
     *  works and talkative for the whole minute of one that does not.
     *
     *  <p>Note what this number cannot do: {@link #CEILING_MS} says one search may not exceed
     *  8 000 ms, so a heartbeat series longer than eight beats is itself evidence that the ceiling
     *  did not fire — either the call is not expanding nodes (the caps sit below the
     *  already-closed {@code continue}) or more than one search is running in the tick. That
     *  arithmetic gap is why this exists; it is not yet closed. */
    private static final long HEARTBEAT_NANOS = 1_000L * 1_000_000L;

    /** Closes the arithmetic gap named just above: how much wall-clock EVERY search on this thread
     *  has spent inside the current game tick.
     *
     *  <p>The gate that keeps wedging dies with all four existing budgets silent — zero
     *  {@code SAFETY CEILING} lines, zero HEARTBEAT lines — while the server sits 67 s without a
     *  tick. That is only consistent with many individually-cheap searches: {@code sliceLimit} and
     *  the heartbeat reset every {@code advance()}, {@code maxMs} and {@link #CEILING_MS} reset
     *  every search, so 519 searches of 130 ms each are invisible to all of them and fatal
     *  together. The measured shape in one wedged scene was 519 {@code search-begin owner=combat}
     *  lines inside one tick against 646 in the healthy arm that finished in 2 s — the count is not
     *  the pathology, the per-search work is (max {@code expanded} 65 147 vs 0).
     *
     *  <p>Per THREAD, not global: the hang watchdog kills a thread that stopped ticking, so the
     *  server thread's own total is the quantity that matters, and a client-side search must not
     *  charge the server's account. Per-thread also means no lock on a hot path and no interleaving
     *  between the two.
     *
     *  <p>This round REPORTS ONLY — it must be calibrated against a healthy run before anything is
     *  allowed to fire on it. A cap picked from intuition could trip a scene that works today (one
     *  1-tick scene, {@code wd.serverCastsObsidian}, legitimately spends ~4.2 s), which is exactly
     *  the contract {@link #CEILING_MS} promises not to break. */
    private static final ThreadLocal<long[]> TICK_SPEND = ThreadLocal.withInitial(
            () -> new long[]{Long.MIN_VALUE, 0L, 0L, 0L});
    private static final int TS_MARKER = 0, TS_NANOS = 1, TS_SEARCHES = 2, TS_NEXT_REPORT = 3;

    /** First per-tick report, and the gap between reports after it. One second: below it there is
     *  nothing to see (a whole healthy fight tick is single-digit ms) and above it every line is a
     *  reading worth having. */
    private static final long TICK_REPORT_NANOS = 1_000L * 1_000_000L;

    /** Stops a wedged tick from writing a line per search for a whole minute. The series is the
     *  evidence, not any single line, and twenty beats already spans the interesting range. */
    private static final long TICK_REPORT_LIMIT = 20;

    private final WorldView world;
    private final int maxNodes;
    private final long maxMs;
    /** Per-intent inputs to every Search launched from this PathFinder (A2a: bundles
     *  the A4a bias channel with the capability gate and edge constraints). {@link
     *  SearchProfile#NONE} for a plain search — byte-identical to pre-A2a. */
    private final SearchProfile profile;
    /** gap#72-④: which chain/verb owns the goal every Search from this PathFinder
     *  serves (e.g. "mine", "retreat", "goto") — telemetry only, never read by the
     *  search itself. "?" = an untagged caller (direct tools/arenas/gametests). The
     *  Walker threads its own owner through {@link #withOwner}; goal producers tag
     *  the Walker, so the plumbing stays single-source (producer → Walker → here). */
    private String owner = "?";

    /** Where this finder's horizon / soft-commit / depth-penalty come from — read LIVE at each use,
     *  owned per body rather than shared through a process-global. See {@link PathTuning} for both
     *  halves of the story: why the global was wrong, and why capturing values instead of a source
     *  was also wrong. {@link PathTuning#GLOBAL} is the compatibility default for finders with no
     *  body behind them. */
    private PathTuning tuning = PathTuning.GLOBAL;

    /** Default ctor reads live tunables from {@link net.magicterra.worlddriver.bot.BotConfig}
     *  so {@code mc.bot.setting{pathfinder.maxNodes:...}} can resize the budget
     *  without restarting the JVM. */
    public PathFinder(WorldView world) {
        this(world, BotConfig.pathfinderMaxNodes, BotConfig.pathfinderMaxMs, SearchProfile.NONE);
    }
    public PathFinder(WorldView world, SearchProfile profile) {
        this(world, BotConfig.pathfinderMaxNodes, BotConfig.pathfinderMaxMs, profile);
    }
    /** Back-compat: bias-only convenience ctor for callers that just want a cost
     *  bias without a full profile (e.g. the {@code wd.*} Bias scenes in
     *  {@code WorldDriverBiasScenes}). */
    public PathFinder(WorldView world, List<CostModifier> bias) {
        this(world, BotConfig.pathfinderMaxNodes, BotConfig.pathfinderMaxMs,
                new SearchProfile(bias, CapabilityProfile.ALL, List.of()));
    }
    public PathFinder(WorldView world, int maxNodes, long maxMs) {
        this(world, maxNodes, maxMs, SearchProfile.NONE);
    }
    public PathFinder(WorldView world, int maxNodes, long maxMs, SearchProfile profile) {
        this.world = world;
        this.maxNodes = maxNodes;
        this.maxMs = maxMs;
        this.profile = (profile == null) ? SearchProfile.NONE : profile;
    }

    /**
     * Plan from a specific tuning source instead of the process-global one.
     *
     * <p>A body hands in {@link PathTuning#escalatedWhen} so its searches follow its OWN churn
     * clock; a scene measuring the planner hands in {@link PathTuning#fixed}. Either way the finder
     * stops reading a knob another body is writing.
     */
    public PathFinder withTuning(PathTuning tuning) {
        if (tuning != null) this.tuning = tuning;
        return this;
    }

    /** Fixed-value convenience for callers measuring the planner. */
    public PathFinder withTuning(int horizonBlocks, int softCommitNodes, double depthPenalty) {
        return withTuning(PathTuning.fixed(horizonBlocks, softCommitNodes, depthPenalty));
    }

    /** gap#72-④: tag the searches launched from this PathFinder with the owning
     *  chain/verb, so latest.log's search-begin lines are attributable. Fluent so
     *  call sites stay one expression; null/empty keeps the "?" default. */
    public PathFinder withOwner(String owner) {
        if (owner != null && !owner.isEmpty()) this.owner = owner;
        return this;
    }

    /** Run a search to completion in one call (synchronous). Kept for callers
     *  that don't need to time-slice; the Walker uses {@link #newSearch} +
     *  {@link Search#advance} to spread a big search across client ticks so it
     *  never blocks the render thread for more than one slice. */
    public Result findPath(BlockPos start, Goal goal) {
        Search s = newSearch(start, goal);
        s.advance(Long.MAX_VALUE);   // no per-slice cap → runs until done
        return s.result();
    }

    public Search newSearch(BlockPos start, Goal goal) {
        return new Search(start, goal, false);
    }

    /** Search variant that DROPS all block-placing moves (bridge/pillar/parkour-place)
     *  when {@code suppressPlace} is true — used by the Walker to re-plan a route the
     *  bot can afford (dig through / go around) after a normal search returned a path
     *  needing more placed blocks than the inventory holds. */
    public Search newSearch(BlockPos start, Goal goal, boolean suppressPlace) {
        return new Search(start, goal, suppressPlace);
    }

    /**
     * A resumable A* search. {@link #advance(long)} does as much work as fits in
     * a per-call wall-clock slice and returns whether the search is done; the
     * total work is still bounded by {@code maxNodes} / {@code maxMs} (cumulative
     * compute, summed across slices). The algorithm and result are identical to
     * a one-shot run — only the time is chunked — so paths and the best-effort
     * backoff segment are unchanged; only the render-thread hitch is removed.
     */
    public final class Search {
        private final BlockPos start;
        private final Goal goal;
        private final Map<BlockPos, Node> nodes = new HashMap<>();
        // Total-order comparator: equal-f ties broken by a STABLE key (packed block
        // position). Open water (and any flat region) produces vast plateaus of
        // equal-f nodes; comparing on f alone leaves their pop order to PriorityQueue
        // heap internals — perturbed by the lazy in-place f-mutation re-add below — so
        // the same start explored a different node count and committed a different
        // equal-cost path run-to-run (buoyantWallArena: expanded 175 vs 85, swimUp@z61
        // vs walk@z60 from an identical start → the flaky bobTicks). A deterministic
        // tie-break makes the whole search reproducible; the chosen path is identical
        // in cost, just canonical.
        private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(a.f, b.f);
            return c != 0 ? c : Long.compare(a.pos.asLong(), b.pos.asLong());
        });
        private final double[] bestHeuristic = new double[COEFFICIENTS.length];
        private final Node[] bestSoFar = new Node[COEFFICIENTS.length];
        /** Reachable node nearest the goal (min raw h) that borders an unloaded
         *  chunk — the goal-ward edge of known terrain. Drives segmented planning
         *  to the loaded-chunk frontier (see {@link BotConfig#pathfinderFrontierCommit}). */
        private Node bestFrontier;
        private double bestFrontierH = Double.POSITIVE_INFINITY;
        // Water-escape best-effort: when the search STARTS in water, track the
        // reachable dry-ground (ashore) node nearest the goal, so an unreachable
        // goal commits to climbing ASHORE rather than a fake in-water segment.
        private Node bestAshore;
        private double bestAshoreH = Double.POSITIVE_INFINITY;
        // Vertical-escape best-effort: when boxed at the foot of a tall obstacle, the
        // node that climbed ABOVE the start and got nearest the goal (pillar-up / dig-up).
        // Committed by chooseSegment only when selectSegment is null (no horizontal escape),
        // so the bot scales the obstacle instead of deadlocking — see MIN_CLIMB_ESCAPE.
        private Node bestClimb;
        private double bestClimbScore = Double.POSITIVE_INFINITY;
        // Last-resort escape: the expanded node FARTHEST from the start, regardless
        // of goal direction. Committed only when every other selector is null at the
        // hard cap — a pocket whose free exits all head AWAY from the goal (cave
        // network behind the bot, goal walled off) otherwise returns "no path"
        // even though the terrain is escapable (live 2026-06-09: 60k nodes spread
        // across a NE cave web, goal SW, hDelta=0 → goto FAILED in a cave pocket).
        private Node bestEscape;
        private double bestEscapeD2;
        // Ground-holding escape: farthest walkable node whose heuristic is no more than
        // ESCAPE_H_SLACK worse than the start's — "leave the pocket WITHOUT losing ground
        // toward the goal". This is the only escape a BUDGET-stopped search may commit
        // (gap#63): with open nodes left, "every reachable cell heads away" is an artifact
        // of the cap, not of the terrain, and committing the raw farthest node walked the
        // bot 75 blocks the wrong way per repath (drift churn). The unrestricted
        // bestEscape stays reserved for a genuinely exhausted graph (open set empty).
        private Node bestEscapeSafe;
        private double bestEscapeSafeD2;
        // Ground-holding ruler for bestEscapeSafe. The heuristic is the WRONG ruler
        // for "did this node lose ground": Goal.Block's Chebyshev makes any lateral
        // drift within the goal's Y-span h-free (dy=40 → ~40 blocks of sideways
        // wander reads as zero loss). When the goal has a concrete target cell,
        // measure true straight-line distance instead: a safe escape node may sit at
        // most ESCAPE_DIST_SLACK blocks farther from the target than the start.
        // Open goals (XZ / YLevel / RunAway — targetPos null) keep the h ruler,
        // where their own estimate IS the ground metric. -1 = use the h fallback.
        private final BlockPos escapeTarget;
        private final double escapeSafeMaxD2;
        private final boolean startInWater;
        private final Node startNode;
        /** Move-set pruned to the catalog entries that can fire under this search's
         *  world/config constants (see {@link Move#availableInSearch}). Built once
         *  here so the per-node neighbour loop skips, e.g., all ~68 WaterBucketFall
         *  variants for a bucketless bot and the Parkour4 tier when it's off —
         *  ~100/259 fewer dispatch-and-reject per expansion in the default config. */
        private final Move[] activeMoves;
        /** Per-intent move-type gate (A2a) — checked in the move-filter loop below,
         *  set early (before that loop runs) from {@code PathFinder.this.profile}. */
        private final CapabilityProfile capability;
        /** Per-intent hard edge prunes (A2a) — checked in the neighbor loop. Empty
         *  for a plain search (the whole prune block is then skipped). */
        private final List<Constraint> constraints;
        /** Obstacle-aware goal-distance field, or null when disabled / unusable
         *  (then the heuristic is the plain Euclidean {@link Goal#estimate}). */
        private final CoarseGoalField goalField;
        /** Ordered stack of per-edge cost taxes, summed in the neighbor loop.
         *  A0 seeds it with the eight legacy taxes IN THEIR ORIGINAL ORDER so
         *  the floating-point sum is bit-identical to the old inline expression;
         *  later phases add/remove modifiers per intent. */
        /** Tax attribution is a PER-SEARCH diagnostic, so it gets its own switch
         *  ({@code -Dworlddriver.pathfinderTaxLog=true}) rather than riding walkerDebug —
         *  which scenes flip off to silence the per-TICK walker spam. Eight of the
         *  ten WaterCross scenes do exactly that, i.e. the runs most likely to
         *  exercise the water taxes are the ones that would have silenced their own
         *  attribution. walkerDebug still enables it, so nothing that used to print
         *  stops printing. */
        private static final boolean TAX_LOG = Boolean.getBoolean("worlddriver.pathfinderTaxLog");

        private final List<CostModifier> costModifiers = new ArrayList<>();
        /** Display name per entry of {@link #costModifiers}, same index. Written only
         *  through {@link #tax}, so the two lists cannot drift apart. */
        private final List<String> costModifierNames = new ArrayList<>();
        /** Tax charged during EXPANSION, per modifier — only populated under TAX_LOG.
         *  Sized on first use, once the modifier list is complete. This is the number
         *  that answers "did this tax do anything": an avoidance tax that works
         *  correctly steers the route AWAY from the cells it prices, so it charges a
         *  lot during the search and nothing at all on the path that wins. Measuring
         *  only the final path (as the first cut of this instrument did) therefore
         *  reports a working avoidance tax as dead. */
        private double[] expandTax;
        private int[] expandHits;
        private int expanded;
        /** Every {@code open.poll()}, including the ones discarded as already-closed. Counted
         *  separately from {@link #expanded} because the two budgets that bound this loop sit on
         *  OPPOSITE SIDES of the {@code if (cur.closed) continue} — the slice deadline above it,
         *  the node/time/ceiling caps below it — so a poll that never becomes an expansion is
         *  charged to neither. {@code polled} far exceeding {@code expanded} is the signature of a
         *  search draining stale duplicates (nodes are re-opened and re-queued when improved), and
         *  it is the reading that tells a runaway apart from a merely large search. */
        private long polled;
        private long elapsedNanos;     // cumulative compute time across slices
        private Result result;         // null until done
        private String segmentReason = "none";   // A5 diagnostics: which chooseSegment branch fired
        /** A5: this search's intent opted into DIVE — water is a legitimate MEDIUM, so the
         *  water-avoidance taxes are skipped (ctor) and the water-start ASHORE-forcing
         *  segment policy ({@link #chooseSegment} / {@link #hasCommittableSegment}) is
         *  bypassed in favor of the standard goal-ward selection. */
        private final boolean diveRelief;

        private Search(BlockPos start, Goal goal, boolean suppressPlace) {
            this.start = start;
            this.startInWater = world.isWater(start);
            this.goal = goal;
            this.escapeTarget = goal.targetPos();
            double maxD = escapeTarget == null ? -1
                    : Math.sqrt(start.distSqr(escapeTarget)) + ESCAPE_DIST_SLACK;
            this.escapeSafeMaxD2 = maxD < 0 ? -1 : maxD * maxD;
            // A2a: pull the per-intent capability gate + edge constraints off the
            // enclosing PathFinder's profile BEFORE the move-filter loop below reads
            // `capability` — both are final fields, so ordering here is load-bearing.
            this.capability = PathFinder.this.profile.capability();
            this.constraints = PathFinder.this.profile.constraints();
            world.beginSearch();       // snapshot per-search state (e.g. nearby mobs)
            // Prune the move catalog to this search's relevant subset (after
            // beginSearch so bucket/flag snapshots are live). One pass over ALL.
            // suppressPlace additionally drops every block-placing move so an
            // out-of-blocks re-plan digs/routes around instead of bridging.
            List<Move> active = new ArrayList<>(Move.ALL.size());
            for (Move m : Move.ALL) {
                if (!m.availableInSearch(world)) continue;
                if (suppressPlace && m.placesBlock()) continue;
                if (!capability.allows(m.requiredCapability())) continue;   // A2a: per-intent move-type gate
                if (m.optInCapability() != Capability.NONE && !capability.allowsOptIn(m.optInCapability())) continue;   // A5: opt-in-only move-type gate
                active.add(m);
            }
            this.activeMoves = active.toArray(new Move[0]);
            // Build the obstacle-aware heuristic field once per search (before any
            // node is created so the start node gets the field estimate too).
            this.goalField = BotConfig.pathfinderGoalField
                    ? CoarseGoalField.build(world, goal, start) : null;
            this.startNode = new Node(start, null, null, 0, heuristic(start));
            nodes.put(start, startNode);
            open.add(startNode);
            PathTraceHolder.SINK.onSearchBegin(start, goal);
            Arrays.fill(bestHeuristic, Double.POSITIVE_INFINITY);
            // A0: seed the modifier stack with the legacy taxes IN THE EXACT
            // ORDER of the old inline sum (FP addition is not associative).
            // A5 dive water-tax relief: a DIVE opt-in declares water a legitimate
            // MEDIUM for this intent, so the three taxes whose sole purpose is
            // water-AVOIDANCE are skipped (descend / per-cell / submerged). The
            // diveGoal() exemption inside them was meant to do this, but it keys on
            // the target CELL being water — an air-pocket base goal is an AIR cell,
            // so diveGoal() read false and submergedTax (80/descending edge ≈ 8
            // walk-blocks each) inflated the dive route's g until thousands of land
            // nodes looked cheaper: every live search from the water surface burned
            // its full budget overland and best-effort'd into a dead-end against the
            // base wall (live 2026-07-06 rc-a5c: goalReached=false expanded≈16k
            // ms=1501 on EVERY repath). Relief is per-intent (goto dive:true only);
            // without the opt-in all taxes are added exactly as before, same order →
            // bit-identical sums. KEPT under relief: leaf/pad/vineOverWater/
            // padOverWater (hazard geometry pricing — bob-jam walls, not water
            // avoidance) and climbOutTax (prices tall bank EXITS, an executor
            // reality that holds for a dive intent too). Dropping a non-negative
            // g-side tax keeps every edge cost ≥ its base, so the heuristic (which
            // never counted taxes) stays an underestimate — admissibility holds.
            diveRelief = this.capability.allowsOptIn(Capability.DIVE);
            if (!diveRelief) tax("descend", (f, t, e, g, w) -> descendTax(f, t, e));
            if (!diveRelief) tax("waterCell", (f, t, e, g, w) -> waterCellTax(t));
            tax("leafCell", (f, t, e, g, w) -> leafCellTax(t));
            tax("padCell", (f, t, e, g, w) -> padCellTax(t));
            tax("vineOverWater", (f, t, e, g, w) -> vineOverWaterTax(t));
            tax("padOverWater", (f, t, e, g, w) -> padOverWaterTax(t));
            // task#97c learned stuck-risk tax — appended LAST so the legacy tax
            // sum order (FP-sensitive) is untouched; contributes exactly 0.0
            // when BotConfig.riskBias is OFF or the move isn't table-listed.
            tax("riskTable", (f, t, e, g, w)
                    -> net.magicterra.worlddriver.bot.pathfinder.modifiers.RiskCostTable.tax(f, e, w));
            tax("climbOut", (f, t, e, g, w) -> climbOutTax(f, t));
            if (!diveRelief) tax("submerged", (f, t, e, g, w) -> submergedTax(f, t));
            // A4a: append this search's per-intent bias AFTER the legacy taxes.
            // Empty for a plain search → byte-identical to the pre-A4a stack.
            for (CostModifier bias : PathFinder.this.profile.bias()) {
                tax(bias.getClass().getSimpleName(), bias);
            }
            expandTax = new double[costModifiers.size()];
            expandHits = new int[costModifiers.size()];
            // gap#72-④ (always-on telemetry): one compact line per SEARCH, tagged with
            // the chain/verb that owns the goal — the gap#72 live investigation spent a
            // whole section attributing "8 blocks of unlogged digging" because no
            // search in latest.log said WHO asked for it.
            LOG.info("[pathfinder] search-begin owner={} start={} goal={} maxNodes={} maxMs={}",
                    owner, start.toShortString(), goal, maxNodes, maxMs);
            // A5 dive diagnostics (walkerDebug-gated, one line per search): whether the
            // DIVE opt-in actually reached THIS search, whether the SurfaceDive move
            // survived the filter, and whether it (and SwimDown) yields an edge from
            // the start cell — the live rc-a5c/e/g mystery was invisible without this.
            if (BotConfig.walkerDebug) {
                Move surfDive = null, swimDown = null;
                for (Move m : activeMoves) {
                    if ("swimDownSurface".equals(m.name())) surfDive = m;
                    else if ("swimDown".equals(m.name())) swimDown = m;
                }
                Move.Edge sdE = surfDive == null ? null : surfDive.eval(world, start);
                Move.Edge swE = swimDown == null ? null : swimDown.eval(world, start);
                LOG.info("[pathfinder] search-begin start={} goal={} diveOptIn={} activeMoves={} surfaceDiveInSet={} "
                                + "surfaceDiveEdge@start={} swimDownEdge@start={} startInWater={} submergedFoot@start={} "
                                + "maxNodes={} maxMs={} softCommit={} boxedEscalate={} constraints={} bias={}",
                        start.toShortString(), goal, capability.allowsOptIn(Capability.DIVE), activeMoves.length,
                        surfDive != null,
                        sdE == null ? "null" : sdE.to.toShortString() + "/cost=" + sdE.cost,
                        swE == null ? "null" : swE.to.toShortString() + "/cost=" + swE.cost,
                        startInWater, world.isSubmergedFoot(start),
                        maxNodes, maxMs, tuning.softCommitNodes(), tuning,
                        constraints.size(), PathFinder.this.profile.bias().size());
            }
        }

        /** Single sink for the search result — every completion path goes through
         *  here so the tax attribution cannot be attached to some of them only. */
        private void finish(Result r) {
            this.result = r;
            if ((TAX_LOG || BotConfig.walkerDebug) && !r.path().isEmpty()) {
                LOG.info("[pathfinder] tax-breakdown owner={} reached={} steps={} finalCost={} onPath[{}] duringSearch[{}]",
                        owner, r.goalReached(), r.path().size() - 1,
                        String.format(Locale.ROOT, "%.1f", r.finalCost()),
                        explainTaxes(r), explainExpansion());
            }
        }

        public boolean done() { return result != null; }
        public Result result() { return result; }
        public int expanded() { return expanded; }

        /** A* heuristic for a node: the obstacle-aware goal-field estimate when
         *  available (max'd with the admissible Euclidean lower bound so it never
         *  drops below it), else the plain Euclidean estimate. Falls back per-cell,
         *  so an unbuilt or partial field simply yields today's behaviour. */
        private double heuristic(BlockPos p) {
            double h = goal.estimate(p);
            if (goalField != null) {
                double field = goalField.costToGoal(p);
                if (field != CoarseGoalField.UNKNOWN) h = Math.max(h, field);
            }
            // Anti-basin-dive: an XZ goal's estimate ignores Y, so descending reads
            // as free progress and the search dives into a dead-end low valley. Charge
            // descent below this search's start (asymmetric — climbing stays free).
            if (tuning.depthPenalty() > 0) {
                int below = start.getY() - BotConfig.pathfinderDepthSlack - p.getY();
                if (below > 0) h += tuning.depthPenalty() * below;
            }
            return h;
        }

        /** REAL edge cost (added to g, not h) for descending IN WATER or by BREAKING
         *  a block, below {@code startY − slack}. This is what actually makes a
         *  dive-and-tunnel path cost more than a climb-ashore one — a heuristic bias
         *  can only reorder the search, never change which reachable path is cheapest,
         *  so an XZ goal at a sheer-walled water pit still drilled underground / bobbed
         *  until the descent paid its true cost. GATED to watery-or-breaking descents:
         *  a dry stepped descent over solid ground (StepDown/Fall/DiagonalDescend, no
         *  block broken, not in water) is a legitimate downhill walk and pays nothing,
         *  so normal terrain pathing is byte-for-byte unchanged. Only the portion of
         *  the step below the slack threshold is charged, and only when going down. */
        /** True when the goal converges on an UNDERWATER target — a deliberate dive
         *  (seabed monument / shipwreck). The buoyancy water-taxes (descend / per-cell /
         *  submerged / climb-out) are suppressed for such a goal so the intended descent
         *  isn't fought. For EVERY other goal — XZ columns AND land-target Block/Near
         *  (a {@code goto pos} to dry land, where rivers/lakes are transient obstacles) —
         *  the taxes apply. The old {@code !ignoresY()} gate wrongly disabled them for
         *  ALL Y-aware goals, so a normal {@code goto pos} land journey got NO water
         *  modelling: A* freely routed the buoyant bot to walk/stepDown onto submerged
         *  riverbed cells it floats above and can't follow → the live deep-water churn
         *  (replay: 100% inWater stepDown stalls, 6-10 s each, 57 repaths). */
        private boolean diveGoal() {
            BlockPos t = goal.targetPos();
            return t != null && world.isWater(t);
        }

        /** Register one cost modifier under a name. The ONLY way to add one — the
         *  name list is what makes {@link #explainTaxes} possible, and pairing the
         *  two adds here means a new tax cannot be added without one. */
        private void tax(String name, CostModifier modifier) {
            costModifiers.add(modifier);
            costModifierNames.add(name);
        }

        /**
         * Per-tax attribution along a FOUND path: which modifiers actually charged,
         * and how much, summed over the path's edges.
         *
         * <p>Up to ten modifiers are summed into every edge, several of them pricing
         * overlapping situations — an XZ-goal descent into submerged water is charged
         * by {@code descend} (per block below the slack threshold), {@code waterCell}
         * (per water cell entered) and {@code submerged} (per descending edge) at once.
         * Whether that stack is deliberate defence-in-depth or accidental
         * double-charging is a real question, and until now it was unanswerable: the
         * search reported one opaque {@code finalCost} and nothing said which tax
         * produced it. Every one of these was added to fix a specific live incident
         * (the surrounding comments cite the replays), and their constants were tuned
         * with all of them present — so the honest first move is to make the stack
         * legible, not to start deleting charges.
         *
         * <p>Runs over the returned path only (tens of edges), never in the expansion
         * loop (tens of thousands of edges), and only under {@code walkerDebug}. The
         * hot loop and its arithmetic are untouched.
         */
        private String explainTaxes(Result r) {
            double[] totals = new double[costModifiers.size()];
            int[] hits = new int[costModifiers.size()];
            for (int i = 1; i < r.path().size(); i++) {
                BlockPos from = r.path().get(i - 1), to = r.path().get(i);
                Move.Edge e = r.edges().get(i);
                if (e == null) continue;
                for (int m = 0; m < costModifiers.size(); m++) {
                    double v = costModifiers.get(m).extraCost(from, to, e, goal, world);
                    if (v != 0) { totals[m] += v; hits[m]++; }
                }
            }
            StringBuilder sb = new StringBuilder();
            double sum = 0;
            for (int m = 0; m < totals.length; m++) {
                if (hits[m] == 0) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(costModifierNames.get(m)).append('=')
                  .append(String.format(Locale.ROOT, "%.1f", totals[m]))
                  .append('x').append(hits[m]);
                sum += totals[m];
            }
            if (sb.length() == 0) sb.append("none");
            return sb + " | taxTotal=" + String.format(Locale.ROOT, "%.1f", sum);
        }

        /** What each tax charged across the WHOLE search, not just the winning path.
         *  Compare with {@link #explainTaxes}: a tax with a large duringSearch total
         *  and zero onPath total is doing its job (it priced alternatives out); one
         *  with zero in both never applied to anything this search touched. */
        private String explainExpansion() {
            if (expandTax == null) return "off";
            StringBuilder sb = new StringBuilder();
            for (int m = 0; m < expandTax.length; m++) {
                if (expandHits[m] == 0) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(costModifierNames.get(m)).append('=')
                  .append(String.format(Locale.ROOT, "%.1f", expandTax[m]))
                  .append('x').append(expandHits[m]);
            }
            return sb.length() == 0 ? "none" : sb.toString();
        }

        private double descendTax(BlockPos from, BlockPos to, Move.Edge edge) {
            double per = BotConfig.pathfinderDescendCost;
            if (per <= 0 || to.getY() >= from.getY()) return 0;        // off, or not descending
            // ONLY for Y-agnostic (XZ) goals: those let descent read as free progress
            // (the root cause). A goal that knows its target Y (pos/block — a seabed
            // monument, shipwreck) guides a genuine dive correctly and must not be
            // taxed, so deep-water exploration / ocean-monument runs are unaffected.
            // (Land-target submerged routing is handled by submergedTax, not here, to
            // keep the deep-water climb-out arenas' descent behaviour unchanged.)
            if (!goal.ignoresY()) return 0;
            // Any water-involved descent (SwimDown into the depths OR a Fall/MLG INTO
            // water) or a block-breaking descent. Including isWater(to) is needed: at a
            // water bowl the cheapest dive uses fall-INTO-water rungs that an
            // isWater(from)-only test misses, leaving A* a tax-free dive-and-tunnel
            // back-door (verified: cost flat across descendCost 40→120, end stayed y50).
            // This does NOT over-tax ocean diving: the whole method is gated above to
            // Y-agnostic XZ goals, and a seabed dive uses a Y-aware pos/block goal.
            boolean watery = world.isWater(from) || world.isWater(to);
            boolean breaks = !edge.toBreak.isEmpty();
            if (!watery && !breaks) return 0;                          // dry stepped descent over solid ground — free
            // A search that STARTS in water gets NO slack: the bot floats at the
            // surface, so ANY planned descent fights buoyancy the executor cannot
            // deliver (live wedge 2026-06-09: from a river surface A* committed
            // fallWater4→seabed-walk under a sheer bank — slack let the first 4-block
            // dive go untaxed, the floating bot could never follow the path down, and
            // every wedge-repath recommitted the same dive → permanent pin). A DRY
            // start keeps the slack so a normal downhill into a stream stays free.
            int slack = world.isWater(start) ? 0 : BotConfig.pathfinderDepthSlack;
            int threshold = start.getY() - slack;
            int hiY = Math.min(from.getY(), threshold);
            int loY = Math.min(to.getY(), threshold);
            int belowDrop = hiY - loY;                                 // descent of THIS edge below threshold
            return belowDrop > 0 ? per * belowDrop : 0;
        }

        /** Per-water-cell tax for EVERY move entering a water cell, Y-agnostic (XZ)
         *  goals only. An XZ goal makes swimming at depth read as free progress, so A*
         *  threads long underwater corridors / dives back into water it just climbed
         *  out of, oscillating against the executor's climb-out (live round70/71). A
         *  PER-CELL tax (a one-time entry tax a bot already in the water never pays)
         *  makes a long water route cost ∝ its length, tipping A* onto an available
         *  LAND route even from a submerged start (verified via mc.debug.plan: from an
         *  in-water cave it routes UP to dry y84 land). A shorter / sole water crossing
         *  is still taken; Y-aware pos/block goals are exempt exactly like descendTax. */
        private double waterCellTax(BlockPos to) {
            double tax = BotConfig.pathfinderWaterCellCost;
            if (tax <= 0 || !goal.ignoresY() || !world.isWater(to)) return 0;
            // A SUBMERGED cell (water directly above → a surface bot must dive under to
            // thread it) costs extra, so A* keeps the route ON THE SURFACE instead of
            // dropping onto the seabed / a seagrass corridor it can't climb out of
            // (live round75 80 s "未能上浮" death-lock). A surface cell (air overhead)
            // pays only the base tax — an ordinary surface crossing is unchanged.
            // ...and so is a CAPPED cell: a surface water cell under a SOLID overhang
            // (rock/dirt ceiling at head+1, i.e. foot+2) is a submerged CHAMBER / tunnel
            // the bot can't cruise at an open top — it must thread under the shelf. With
            // only the water-above test, an under-shelf surface cell read as cheap open
            // water (base tax), so A* routed an XZ goal straight THROUGH the canyon
            // pocket (2358,1863) (water y58-62 under a y64 rock shelf) and the buoyant
            // bot churned / swam-ashore for minutes instead of routing AROUND it — the
            // one clean arrival went around the overhang via the NE bank. Price a capped
            // cell like submerged so A* prefers open water / a land detour. Open surface
            // water (sky above) and the Goal.Block water arenas (foot+2 clear) are
            // unchanged.
            boolean submerged = world.isWater(to.offset(0, 1, 0));
            boolean cappedChamber = !submerged && world.isSolid(to.offset(0, 2, 0));
            double overheadExtra = (BotConfig.pathfinderSubmergedWaterCost > 0
                    && (submerged || cappedChamber))
                    ? BotConfig.pathfinderSubmergedWaterCost : 0;
            return tax + overheadExtra;
        }

        /** Per-cell tax for a move that stands ON a leaf canopy or pushes the head INTO leaves
         *  (see BotConfig.pathfinderLeafCellCost). Leaves read as standable ground, so A* climbs the
         *  bot onto a tree canopy where it bobs/rams the dense head-height leaves; this softly prices
         *  canopy cells so a ground route around/under the tree wins. A sole canopy route is still
         *  taken. Y-agnostic and goal-type-neutral — leaves jank a buoy-free climb regardless. */
        private double leafCellTax(BlockPos to) {
            double tax = BotConfig.pathfinderLeafCellCost;
            if (tax <= 0) return 0;
            return (world.isLeaves(to.below()) || world.isLeaves(to.above())) ? tax : 0;
        }

        /** Per-cell tax on a SURFACE-WATER cell whose cell-above is a thin breakable obstruction —
         *  canonically a lily pad (see BotConfig.pathfinderLilyPadCellCost). The planner treats the pad's
         *  thin shape as passable, but a slowed surface swimmer rams the pad's head-height collision box
         *  (hCol, hSpd→0) and the break-actuator can't reliably punch the overhead pad → a multi-second
         *  bob-freeze. This softly prices pad-over-water cells so A* threads the adjacent clear water and
         *  swims around the pad. Gated like waterCellTax (XZ/swim goal + real water cell) so dry-land
         *  grass overhead is never taxed; a pad implies water below, so the water gate is exact. */
        private double padCellTax(BlockPos to) {
            double tax = BotConfig.pathfinderLilyPadCellCost;
            if (tax <= 0 || !goal.ignoresY() || !world.isWater(to)) return 0;
            return world.isBreakableObstruction(to.above()) ? tax : 0;
        }

        /** Per-cell tax on a SURFACE-WATER traversal cell whose BODY/HEAD column carries a hanging-VINE
         *  or LEAF obstruction over the water — a tree-canopy (oak_leaves + draped vines, often with lily
         *  pads) growing IN/over a lake/river (see BotConfig.pathfinderVineOverWaterTax). The planner
         *  reads the foot cell as ordinary surface water and threads a horizontal crossing node STRAIGHT
         *  THROUGH it, because none of the sibling taxes price this geometry: {@link #waterCellTax} /
         *  {@link #submergedTax} inspect only the water cell + its directly above/below (the body vine is
         *  neither); {@link #leafCellTax} checks {@code isLeaves(above)} but the body cell over water is a
         *  VINE (not in #minecraft:leaves) and the leaf canopy sits TWO up; {@link #padCellTax} needs a
         *  COLLIDING instabreak block (a vine has no collision shape, so it isn't an
         *  {@code isBreakableObstruction}). A floating bot pushed onto such a node rams the vine/leaf wall
         *  at body height (hCol, hSpd→0, X pins / Z creeps) — the live #47 ~-780,339 bob-jam. This softly
         *  prices the cell so A* threads the adjacent clear water and swims AROUND the tree.
         *  <p>Scoped TIGHT, mirroring padCellTax's exactness: the FOOT must be a real water cell (no
         *  dry-canopy / open-water false positives — dry leaf canopy is already {@link #leafCellTax}'d on
         *  land) AND the obstruction is in the BODY/HEAD cells ABOVE the foot ({@code foot+1} / {@code
         *  foot+2}). The foot cell itself is deliberately NOT tested, so a legitimate vine-CLIMB up out of
         *  the water — whose climbable vine starts AT the foot — is never penalised. A leaf cap at
         *  {@code foot+1} or {@code foot+2}, or a hanging vine (climbable) draping into the body column,
         *  trips it. Y-agnostic and goal-type-neutral — a vine/leaf wall janks a buoyant crossing
         *  regardless of goal Y (same neutrality as {@link #leafCellTax}). A TAX, never a forbid: a fully
         *  canopied channel with no clear alternative still threads through (the price decays into the
         *  move cost). Inert when the flag is OFF (byte-identical no-op) and on the headless/grid views
         *  (no leaves/climbable over water). */
        private double vineOverWaterTax(BlockPos to) {
            double tax = BotConfig.pathfinderLeafCellCost;
            if (!BotConfig.pathfinderVineOverWaterTax || tax <= 0 || !world.isWater(to)) return 0;
            BlockPos head = to.above();          // foot+1 — the body cell a hanging vine drapes into
            BlockPos over = to.offset(0, 2, 0);  // foot+2 — the head cell / low leaf canopy
            boolean obstructed = world.isLeaves(head) || world.isClimbable(head)
                    || world.isLeaves(over) || world.isClimbable(over);
            return obstructed ? tax : 0;
        }

        /** Per-cell tax on a SURFACE-WATER traversal cell whose FOOT+1 (body) cell holds a thin breakable
         *  obstruction — canonically a SINGLE SPARSE lily pad over deep OPEN water (see
         *  BotConfig.pathfinderPadOverWaterTax). This is the goal-type-NEUTRAL sibling of {@link #padCellTax}:
         *  that method prices the identical pad geometry but is gated to XZ goals ({@code goal.ignoresY()},
         *  mirroring {@link #waterCellTax}'s triple-gate — the dense-pool A/B that validated it crossed on a
         *  bare-column XZ swim goal). A real {@code mc.bot.goto x,y,z} is a Y-AWARE goal ({@code Goal.Block}/
         *  {@code Goal.Near}), for which {@code padCellTax} returns 0 — so an OPEN-water corridor dotted with
         *  SPARSE single pads is left unpriced and A* threads a crossing node STRAIGHT THROUGH each pad (a
         *  1-pad instabreak dig is cheaper than a 1-block detour). A floating bot then rams + hand-digs the
         *  pad in its body cell (hCol, hSpd→0, attack=true) — the live #47 ~-830,363 / -817,298 multi-second
         *  bob-jams. This is the SAME structural gap {@link #vineOverWaterTax} closes for vines/leaves (also
         *  goal-neutral), but a lily pad is neither {@code isLeaves} nor {@code isClimbable} (it has a thin
         *  floor collision shape → it IS an {@code isBreakableObstruction}), so the vine tax misses it.
         *  <p>Reuses {@code padCellTax}'s EXACT predicate ({@code isWater(foot) && isBreakableObstruction(
         *  foot+1)}) WITHOUT the {@code goal.ignoresY()} gate, so a sparse pad over a Y-aware-goal crossing is
         *  priced too. No cluster/pool requirement — a lone isolated pad trips it. The foot cell is never
         *  tested (a pad implies water below), and the obstruction is the BODY cell ({@code foot+1}) where a
         *  floating bot's collision lives. Y-agnostic and goal-type-neutral. A TAX, never a forbid: a fully
         *  pad-covered field with no clear lane still threads through (the price decays into the move cost,
         *  the break-actuator stays the fallback) — nothing becomes unreachable, so no stranding. Inert when
         *  the flag is OFF (byte-identical no-op) and on the headless/grid views (no pads over water). */
        private double padOverWaterTax(BlockPos to) {
            double tax = BotConfig.pathfinderLilyPadCellCost;
            if (!BotConfig.pathfinderPadOverWaterTax || tax <= 0 || !world.isWater(to)) return 0;
            if (!isPadOverWater(to)) return 0;
            // CLUSTER surcharge (BotConfig.pathfinderPadClusterTax). The flat per-pad tax above tips A*
            // around a LONE pad: a 1-block side-deflection (Diagonal +4 in, +4 out = +8) is cheaper than
            // the +20 dig, so the cheapest plan swims around it. But for an ADJACENT pad PAIR / cluster
            // the cheapest CLEAR lane sits ≥2 cells off the crossing line — the natural 1-cell deflection
            // lands on the sibling pad (which also costs +20) — so the wider full detour costs MORE than
            // digging ONE pad of the pair, and the flat tax lets A* pick the lesser evil: it threads (and
            // the floating bot rams+hand-digs) one pad of the pair (~4 s, attack=true; live #47 adjacent
            // pads -850/-851,323 / -750/-751,334 in the 23-pad scatter). Scaling the tax by the count of
            // pad-over-water cells in foot+1's 4-neighbourhood makes a clustered pad cost 20·(1+N) — so a
            // 2-cell-wider detour around the whole cluster beats digging through it, while a LONE pad
            // (N=0) stays at the flat 20 (single-pad routing is byte-identical). Still a TAX, never a
            // forbid: a fully pad-covered field with no clear lane has the same scaled tax on EVERY cell,
            // so A* still threads the shortest line through (no stranding — the break-actuator stays the
            // fallback). Inert when the cluster flag is OFF (the flat tax above is unchanged).
            if (BotConfig.pathfinderPadClusterTax) {
                int adj = 0;
                if (isPadOverWater(to.offset( 1, 0, 0))) adj++;
                if (isPadOverWater(to.offset(-1, 0, 0))) adj++;
                if (isPadOverWater(to.offset( 0, 0, 1))) adj++;
                if (isPadOverWater(to.offset( 0, 0, -1))) adj++;
                if (adj > 0) tax += adj * BotConfig.pathfinderLilyPadCellCost;
            }
            return tax;
        }

        /** True when {@code foot} is a real water cell whose BODY cell ({@code foot+1}) carries a thin
         *  breakable obstruction — the canonical lily-pad-over-water signature shared by {@link #padCellTax}
         *  and {@link #padOverWaterTax}. Used by the cluster surcharge to count adjacent pads in the
         *  4-neighbourhood. (A pad implies water below, so the foot-water test is exact — dry grass overhead
         *  is never counted.) */
        private boolean isPadOverWater(BlockPos foot) {
            return world.isWater(foot) && world.isBreakableObstruction(foot.above());
        }

        /** Per-RISE tax on an edge that CLIMBS OUT of water onto a higher bank
         *  ({@code from} in water, {@code to} dry and above). The entry/descend water
         *  taxes never see this edge (its {@code to} is dry and the move rises), so
         *  without it A* freely picks a TALL near-bank exit a buoyant bot can't step onto
         *  — every such exit forces the Walker's bob-stuttery bank-dig climb-out (live
         *  "卡在土墙 / 反复挖同一土块 / 横跳" windows). Pricing the exit ∝ its rise tips A* onto the
         *  LOWEST available exit (a surface-level bank = rise 0 = free Walk), without
         *  forbidding a tall one when that's all the shoreline offers. XZ goals ONLY: for a
         *  Y-aware land goal it BACKFIRED live (replay A/B) — pricing the climb-OUT makes A*
         *  keep the bot IN the water to dodge the tax, which REVIVES the submerged-descent
         *  churn {@link #submergedTax} just removed (z1957 cluster 0→303 ticks, total stall
         *  14s→37s). So land-goal water exits are governed by submergedTax (stay on the
         *  surface), not by taxing the exit. */
        private double climbOutTax(BlockPos from, BlockPos to) {
            double per = BotConfig.pathfinderWaterClimbOutCost;
            if (per <= 0 || !goal.ignoresY()) return 0;
            if (!world.isWater(from) || world.isWater(to)) return 0;   // only water → dry
            int rise = to.getY() - from.getY();
            if (rise <= 0) return 0;                                   // surface-level/down exits free
            double tax = per * rise;
            // Floating-SOURCE climb-out (water directly below the foot → buoyant bot, no solid
            // floor to push off): even a LOW +1..+3 exit can't be swim-jumped or sand-pillared —
            // it forces the slow bob-stutter underwater bank-DIG (live -705,67 bay exit rise-2 =
            // 299 dig ticks ≈15s; -711,67 pocket sink). The rise>3 surcharge below catches only
            // TALL exits and misses these low FLOATING ones, so price them up here so A* tips onto
            // a GROUNDED/shallow exit (solid floor under the foot → fast flush stepUp/walk) where
            // the shoreline offers one. isFloatingWater = water at foot AND foot.below().
            if (BotConfig.pathfinderFloatingClimbOutMult > 0 && world.isFloatingWater(from))
                tax += per * rise * BotConfig.pathfinderFloatingClimbOutMult;
            // Steep surcharge ABOVE a buoyant bot's smooth-mount reach (~+3). A tall exit
            // (+4..) can't be swim-jumped or sand-pillared from deep water (the column sinks
            // the falling block); it forces the bob-stuttery toolless bank-DIG — 25× underwater
            // mining ≈ 3-5 s per riser, the live "卡在土墙 / 反复挖同一土块 / 横跳" windows. Pricing
            // the tall exit well above a gentle multi-step one tips A* onto a LOW bank + a dry
            // walk-up where the shoreline offers it, WITHOUT forbidding the tall exit when it's
            // the only way out (single-exit climb-out arenas still find their path, just dearer).
            // Verified clean: R2J3 replay dig 301→80 ticks (+6 dig eliminated), drift arena's
            // ashoreTick=105 is PRE-EXISTING (identical with/without this surcharge).
            if (rise > 3) tax += per * (rise - 3) * 3.0;
            return tax;
        }

        /** Penalty for DESCENDING into a SUBMERGED water cell (water directly above) on the
         *  way to a LAND-target goal. A buoyant bot floats at ~surface+0.4 and can't follow
         *  a plan DOWN into deep water, but {@link WorldView#canStandAt} treats ANY water
         *  cell as a floor, so A* otherwise routes stepDown/diagDown nodes onto the
         *  submerged riverbed the floating bot can't reach → the live deep-water churn
         *  (replay of a single river crossing: 100% inWater, stepDown 173/221 ticks +
         *  diagDown, 6-10 s stalls, 57 repaths). Taxing the DESCENT into submerged water
         *  keeps the route ON THE SURFACE where the bot can swim. Restricted to a DESCENT
         *  ({@code to.y < from.y}) so a bot rising/traversing OUT of a deep pocket — e.g.
         *  the pillar/dig climb-out arenas, which start submerged and ascend — is untouched.
         *  Scoped to land-target Y-aware goals: XZ goals already price submerged cells via
         *  {@link #waterCellTax}; a deliberate dive to an UNDERWATER target ({@link #diveGoal})
         *  is exempt. Reuses {@link BotConfig#pathfinderSubmergedWaterCost}.
         *  <p>{@link BotConfig#pathfinderFloatingSurfaceCross} (default OFF) extends this to also price
         *  a HORIZONTAL/rising entry into a deep FLOATING-submerged cell for a Y-aware goal — the case
         *  where a bot ENTERS deep water already submerged and the descent clause never fires, so A*
         *  threads the whole crossing one below the surface (the live #47 deep-water bob-jam). */
        private double submergedTax(BlockPos from, BlockPos to) {
            double tax = BotConfig.pathfinderSubmergedWaterCost;
            // Applies to XZ goals TOO (not just Y-aware land goals): waterCellTax's flat per-cell
            // entry tax doesn't specifically price the DESCENT, so over a short underwater slope A*
            // still walked the buoyant bot DOWN to a submerged riverbed node (live 2026-06-23: a
            // mountain-edge entry into 9-deep water routed walk/step nodes down to y58, 4 below the
            // surface → dive-stall). Taxing the descent-into-submerged edge keeps the surface
            // crossing on top. A deliberate dive to an UNDERWATER target (diveGoal) stays exempt.
            if (tax <= 0 || diveGoal()) return 0;
            if (!world.isWater(to)) return 0;
            // DESCENT into a submerged cell (the original case): keeps the surface crossing on top.
            if (to.getY() < from.getY() && world.isWater(to.offset(0, 1, 0))) return tax;
            // HORIZONTAL / rising entry into a DEEP floating-submerged cell, for Y-AWARE goals only
            // ({@link BotConfig#pathfinderFloatingSurfaceCross}). Once a buoyant bot enters deep water
            // already submerged, the rest of the crossing is horizontal (never a fresh descent), so the
            // descent clause above never fires and A* threads the whole crossing one cell below the
            // surface — where the floating body can't follow (it bobs at the surface above the y-1
            // path, jams until a repath re-routes on top: live #47 R3 seg0 y61 run). Pricing every such
            // floating-submerged cell tips A* to swim ON THE SURFACE instead. FLOATING water only
            // (water below → no foothold; a shallow grounded splash is exempt) and submerged (water
            // above → a surface swimmer would have to dive under). XZ goals are excluded — they already
            // pay this via waterCellTax's submerged overhead, so taxing here would double-charge. A tax,
            // not a forbid: a roofed submerged tunnel with no surface route is still threaded (it stays
            // the cheapest available path). Inert when the flag is OFF (byte-identical no-op).
            if (BotConfig.pathfinderFloatingSurfaceCross && !goal.ignoresY()
                    && world.isFloatingWater(to) && world.isWater(to.offset(0, 1, 0))) return tax;
            return 0;
        }

        /** Expand nodes until {@code sliceMs} of wall-clock elapses this call (or
         *  the search finishes / hits its total budget). Returns true once done;
         *  the {@link Result} is then available from {@link #result()}. */
        /** How many runaway expansions one search may report before it stops repeating itself.
         *  Enough to see whether it is one bad cell or a whole region. */
        private static final int RUNAWAY_LOG_CAP = 8;
        private int runawayLogged;

        public boolean advance(long sliceMs) {
            if (result != null) return true;
            long sliceStart = System.nanoTime();
            long sliceLimit = (sliceMs >= Long.MAX_VALUE / 2) ? Long.MAX_VALUE : sliceMs * 1_000_000L;
            // Ten times the slice, floored at 100 ms so a thin slice does not cry wolf. Against a
            // measured 8-9 ms worst healthy expansion this only fires on something pathological.
            long runawayLimit = Math.max(100L * 1_000_000L,
                    sliceLimit == Long.MAX_VALUE ? 1_000L * 1_000_000L : sliceLimit * 10L);
            int sinceCheck = 0;
            // ENTRY BREADCRUMB — written BEFORE the work, which is the whole point.
            //
            // Every instrument this class had was computed AFTER the thing it measures: RUNAWAY
            // takes nodeCost once the node returns, and the scene pump's overrun WARN fires once
            // the iteration returns. The call that kills the server never returns, so by
            // construction neither of them can ever describe it — they can report a near miss and
            // nothing else. A line written on the way IN survives the death, and after a watchdog
            // kill the last one in the log names the state that never came back.
            //
            // Gated on the yield being DISABLED, because that is exactly the population the crash
            // lives in and it is a pre-hoc test, not a guess about how long this call will take:
            // scenes set BotConfig.pathfinderSliceMs to Long.MAX_VALUE/2 so a search is never
            // truncated mid-measurement, sliceLimit collapses to Long.MAX_VALUE, and the pause at
            // the top of the loop below can then never fire. Production (6 ms) keeps its yield and
            // logs nothing here. With the yield off a search runs to completion inside ONE call,
            // so this is one line per search rather than one per slice.
            if (sliceLimit == Long.MAX_VALUE) {
                LOG.info("[pathfinder] ENTER unbounded-slice owner={} start={} goal={} expanded={}"
                        + " polled={} open={} nodes={} elapsedMs={} drivers={}",
                        owner, start.toShortString(), goal, expanded, polled, open.size(),
                        nodes.size(), elapsedNanos / 1_000_000L,
                        net.magicterra.worlddriver.bot.sim.ServerAvatarManager.activeCount());
            }
            // HEARTBEAT — the only instrument here that can describe a call which never returns.
            // Rides the clock the loop already reads, so a healthy search pays one long compare.
            // First beat at HEARTBEAT_NANOS and one per interval after, so a runaway prints a
            // GROWING series: the counts in the last line say how far it got before the kill, and
            // the number of lines says how long the call had been running — which is what tells a
            // 55-second call apart from a 3-second call the watchdog blamed for a tick backlog.
            long nextBeat = HEARTBEAT_NANOS;
            int beatExpanded = expanded;    // counter values at the previous beat, for its deltas
            long beatPolled = polled;
            // Cache is LIVE only while this slice expands nodes (static-world memoise);
            // cleared off in finally so the Walker's between-slice reads stay fresh.
            world.cacheActive(true);
            String stopCause = "open-exhausted";   // A5 diagnostics: why the expand loop ended
            try {
                while (!open.isEmpty()) {
                    // Check the clock every TIME_CHECK_INTERVAL expansions, not every 128:
                    // each expansion evaluates all moves with world/chunk lookups (costly at
                    // high render distance), so a 128-expansion gap let a slice overshoot the
                    // cap by ~200ms (a visible hitch). nanoTime() is ~30ns so a 16-wide gap is
                    // still <0.2ms total over a 100k-node search while bounding overshoot ~8x
                    // tighter, keeping each slice near sliceMs.
                    if (++sinceCheck >= TIME_CHECK_INTERVAL) {
                        sinceCheck = 0;
                        long sliceNanos = System.nanoTime() - sliceStart;
                        if (sliceNanos >= sliceLimit) return false;  // pause, resume next call
                        // See the HEARTBEAT note above the loop. This branch is unreachable in a
                        // healthy search — the worst iteration ever recorded for the scene that
                        // keeps crashing is 21.4 ms (neoforge stagewright-results.jsonl,
                        // wd.serverFightsAFlyingBlaze open.worstIterMs), against a first beat at
                        // one second.
                        if (sliceNanos >= nextBeat) {
                            nextBeat += HEARTBEAT_NANOS;
                            // The DELTAS are the verdict, not the totals. A beat whose polled
                            // delta is large while its expanded delta is ~0 is a loop spinning on
                            // already-closed duplicates: it never reaches the maxNodes / maxMs /
                            // CEILING_MS checks, because those all sit BELOW the `if (cur.closed)
                            // continue` while the only test above it is the slice deadline these
                            // scenes have switched off. That single line then explains both the
                            // hang and why an 8 000 ms ceiling did not stop it. A beat where both
                            // deltas move together is the opposite finding — the caps are being
                            // reached and something else is wrong — so the reading falsifies as
                            // well as confirms, which a totals-only line could not do.
                            LOG.warn("[pathfinder] STILL RUNNING {} ms in ONE advance() —"
                                    + " owner={} start={} goal={} expanded={}(+{}) polled={}(+{})"
                                    + " open={} nodes={} sliceMs={} drivers={}. A polled delta with"
                                    + " a flat expanded delta = draining re-queued duplicates,"
                                    + " which neither the slice deadline nor the node/time/ceiling"
                                    + " caps can charge. More than 8 beats in one series means the"
                                    + " {} ms ceiling never fired.",
                                    sliceNanos / 1_000_000L, owner, start.toShortString(), goal,
                                    expanded, expanded - beatExpanded, polled, polled - beatPolled,
                                    open.size(), nodes.size(), sliceMs,
                                    net.magicterra.worlddriver.bot.sim.ServerAvatarManager.activeCount(),
                                    CEILING_MS);
                            beatExpanded = expanded;
                            beatPolled = polled;
                        }
                    }
                    Node cur = open.poll();
                    polled++;
                    if (cur.closed) continue;
                    cur.closed = true;
                    expanded++;
                    PathTraceHolder.SINK.onNodeExpanded(cur.pos, cur.g);

                    if (goal.reached(cur.pos)) {
                        finish(build(cur, true, expanded, totalMs(sliceStart), cur.g));
                        return true;
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double weighted = cur.h + cur.g / COEFFICIENTS[i];
                        if (weighted < bestHeuristic[i]) {
                            bestHeuristic[i] = weighted;
                            bestSoFar[i] = cur;
                        }
                    }
                    // Loaded-chunk frontier: a reachable node bordering an unloaded
                    // chunk is the edge of known terrain. Track the one nearest the
                    // goal (min raw h) so a horizon-truncated search can commit toward
                    // it (segmented planning, BotConfig.pathfinderFrontierCommit).
                    if (BotConfig.pathfinderFrontierCommit && !startInWater
                            && cur.h < bestFrontierH && bordersUnknown(cur.pos)) {
                        bestFrontierH = cur.h;
                        bestFrontier = cur;
                        // EARLY STOP: A* pops nodes by f, so the first expanded frontier
                        // node that makes real progress toward the goal IS the optimal path
                        // to the loaded-chunk edge. Commit it now and stop the search —
                        // "plan only as far as loaded chunks reach, walk there, load more,
                        // replan" — instead of burning the whole budget grinding toward an
                        // out-of-render goal it can never reach this search. Gated like the
                        // chooseSegment frontier branch (real goal-ward gain + min distance)
                        // so a sideways/backward chunk edge can't trigger it. (Water starts
                        // are excluded above so the bestAshore climb-out wins there.)
                        if (cur.h < startNode.h - MIN_FRONTIER_GAIN
                                && cur.pos.distSqr(start) > (long) MIN_DIST_PATH * MIN_DIST_PATH) {
                            finish(build(cur, false, expanded, totalMs(sliceStart), cur.g));
                            return true;
                        }
                    }
                    // Receding-horizon early-stop (BotConfig.pathfinderHorizonBlocks):
                    // generalises the loaded-chunk frontier commit above to ANY terrain.
                    // The frontier branch only fires at an UNLOADED-chunk edge, so a far
                    // goal in fully-loaded terrain grinds the whole node budget for a tiny
                    // best-effort segment (the long-haul freeze). Here, the instant A* pops
                    // a node that has advanced >= horizon blocks toward the goal (h dropped
                    // by horizon*10 cost units; A* pops by f so this node is ~optimal to the
                    // horizon), commit it and STOP — an unbounded far grind becomes a cheap
                    // fixed-length forward hop the bot walks while the next search runs.
                    // Self-disables near the goal (h can't drop that far → search reaches the
                    // real goal). Only real goal-ward progress, so a pinch/wall (no forward
                    // node) falls through to the unchanged best-effort backoff. Water starts
                    // excluded (bestAshore climb-out wins).
                    if (tuning.horizonBlocks() > 0
                            && cur.h < startNode.h - 10.0 * tuning.horizonBlocks()
                            && cur.pos.distSqr(start) > (long) MIN_DIST_PATH * MIN_DIST_PATH
                            // A water start may ALSO horizon-commit, but only onto dry
                            // land or a SURFACE cell (water foot, air head) — never a
                            // submerged node, so the climb-out triage below still owns
                            // those. Without this, an open-sea leg burned the full 60k
                            // nodes / ~22 s per repath for pathLen=0 (the shore lies
                            // beyond any budget, so bestAshore stays null) while 600-node
                            // quick-start micro-segments carried the actual swimming
                            // (round43: three consecutive 60k/22s/pathLen=0 repaths
                            // during a perfectly healthy 2 b/s surface cruise).
                            && (!startInWater
                                || isAshore(cur.pos)
                                || (world.isWater(cur.pos)
                                    && !world.isWater(cur.pos.offset(0, 1, 0))))) {
                        finish(build(cur, false, expanded, totalMs(sliceStart), cur.g));
                        return true;
                    }
                    // Water escape: track the reachable ASHORE node (dry ground) closest
                    // to the goal, for the best-effort commit when the goal isn't reached.
                    if (startInWater && cur.h < bestAshoreH && isAshore(cur.pos)) {
                        bestAshoreH = cur.h;
                        bestAshore = cur;
                    }
                    // Vertical escape: track the best node reached by climbing ABOVE the
                    // start (pillar-up / dig-up). Score = h biased slightly toward greater
                    // height, so it prefers a node that climbed AND advanced toward the goal
                    // (lower h); on an h-tie (a straight-up pillar leaves the XZ estimate
                    // unchanged) it prefers the highest rung, gaining the most vantage.
                    // Tracked for Y-agnostic XZ goals ("over the obstacle toward the column")
                    // AND for a concrete target meaningfully ABOVE the start (gap#63): a
                    // deep-cave bot under a surface goal has no goal-ward horizontal segment
                    // within budget, and the lateral safe-escape just wanders the cave — the
                    // convergent move is UP (height is monotone across the re-plan chain, so
                    // a climb commit per repath ratchets toward the goal instead of orbiting
                    // it). Y-aware goals BELOW or level keep their own 3D heuristic guidance.
                    // Inert unless the bot can place/break (else no climbed node exists), so
                    // the headless GameTest view (canPlace=false, breakCost=∞) never trips it.
                    // (Dry starts only for the Y-aware case: a water start already has its
                    // own climb-out triage — bestAshore first, bestClimb as the surfacing
                    // fallback — and feeding it Y-aware climb candidates let a 2-block
                    // bobbing "climb" preempt a reachable shore: riverSheerBankArena RED.)
                    boolean climbWanted = goal.ignoresY()
                            || (!startInWater && escapeTarget != null
                                && escapeTarget.getY() >= start.getY() + MIN_CLIMB_ESCAPE);
                    if (climbWanted && cur.pos.getY() > start.getY()) {
                        double climbScore = cur.h - 0.01 * (cur.pos.getY() - start.getY());
                        if (climbScore < bestClimbScore) {
                            bestClimbScore = climbScore;
                            bestClimb = cur;
                        }
                    }
                    // Track the farthest reachable node for the last-resort escape
                    // commit (see chooseSegment) — direction-agnostic on purpose, but
                    // WALKABLE-ONLY (no break edge anywhere on the path): "physically
                    // leave the dead pocket" means walking/swimming/pillaring out of a
                    // cave network. A node reached by MINING is not an escape vantage —
                    // and with breaks priced in, the farthest node under a budget cap in
                    // uniform rock is always the straight-DOWN drill (1 break/cell vs
                    // 2-3 lateral/up): gap#59's 69-block shaft under two UP-goals.
                    // Sealed in solid rock this leaves bestEscape null → "no path" →
                    // the futile-search backoff (#50) owns the failure, fail-stop.
                    if (!cur.dug) {
                        double escD2 = cur.pos.distSqr(start);
                        if (escD2 > bestEscapeD2) {
                            bestEscapeD2 = escD2;
                            bestEscape = cur;
                        }
                        // Ground-holding variant (gap#63): eligible for a BUDGET-stopped
                        // commit only if it hasn't lost more than ~2 blocks toward the goal
                        // (true distance for concrete targets, h for open goals — see
                        // the escapeSafeMaxD2 field note).
                        boolean holdsGround = escapeSafeMaxD2 >= 0
                                ? cur.pos.distSqr(escapeTarget) <= escapeSafeMaxD2
                                : cur.h <= startNode.h + ESCAPE_H_SLACK;
                        if (holdsGround && escD2 > bestEscapeSafeD2) {
                            bestEscapeSafeD2 = escD2;
                            bestEscapeSafe = cur;
                        }
                    }

                    if (expanded >= maxNodes) { stopCause = "maxNodes(" + maxNodes + ")"; break; }
                    long spentMs = totalMs(sliceStart);
                    if (spentMs > maxMs) { stopCause = "maxMs(" + maxMs + ")"; break; }
                    // SAFETY CEILING — a backstop against killing the JVM, not an opinion about
                    // how long a search may take.
                    //
                    // Scenes deliberately set maxMs to Long.MAX_VALUE/2 (58 sites across nine
                    // files) and bound their searches with maxNodes instead, and that is CORRECT
                    // test design rather than an oversight: maxNodes is deterministic, so an
                    // assertion on expanded/segments holds on any machine, while a wall-clock cap
                    // would make the same scene pass or fail depending on how busy the box is that
                    // day. Nothing here overrides that intent.
                    //
                    // What it does override is death. An uncapped search on the server thread is a
                    // latent hang: the same 100k-node search that finishes well inside a minute on
                    // a quiet machine crossed max-tick-time when two suites ran at once, and the
                    // hang watchdog killed the server mid-suite — taking every later scene's result
                    // with it. This ceiling sits far above any legitimate search and far below that
                    // watchdog, so it cannot fire in a run that works today, and in the run that was
                    // already doomed it turns a dead JVM into a search that RETURNS. A best-effort
                    // result that a scene can assert on beats no results file at all.
                    //
                    // Loudly, always. A clamp that changed behaviour without saying so would be the
                    // very shape of bug this ceiling exists because of — a knob nobody could see.
                    if (spentMs > CEILING_MS) {
                        stopCause = "ceiling(" + CEILING_MS + "ms)";
                        LOG.warn("[pathfinder] SAFETY CEILING hit after {} ms — owner={} expanded={}"
                                + " maxNodes={} maxMs={} goal={} start={}. The search is being cut"
                                + " short so it cannot reach the server hang watchdog; treat this"
                                + " as a real finding, not noise.",
                                spentMs, owner, expanded, maxNodes, maxMs, goal, start.toShortString());
                        break;
                    }
                    // Soft commit (BotConfig.pathfinderSoftCommitNodes): the horizon
                    // early-stop above only fires on real forward progress; when the bot is
                    // BOXED at an obstacle no such node appears and the search would grind the
                    // whole hard maxNodes budget (~3.4 s) for a short best-effort segment — a
                    // multi-second freeze at every cliff/wall. Once the soft budget is spent
                    // AND a committable best-effort segment already exists, stop and commit it
                    // now. The hard maxNodes still governs the "no segment yet" case (a deep
                    // pinch still hunting its first viable move / vertical escape), so hard
                    // reachability is unchanged.
                    if (tuning.softCommitNodes() > 0
                            && expanded >= tuning.softCommitNodes()
                            && hasCommittableSegment()) {
                        stopCause = "soft-commit(softNodes=" + tuning.softCommitNodes()
                                + (startInWater && expanded >= tuning.softCommitNodes() * 4L
                                        ? " water-relaxed-4x" : "") + ")";
                        break;
                    }

                    // RUNAWAY WATCH. The slice deadline above is checked every TIME_CHECK_INTERVAL
                    // EXPANSIONS, which bounds how many nodes may pass between clock reads and
                    // nothing at all about how long one of them takes. Every move below does world
                    // reads, and a world read that has to load or generate a chunk blocks the
                    // calling thread for as long as that takes — so the cap holds for thousands of
                    // expansions and then does not hold at all.
                    //
                    // Measured cost of this scene's healthy expansions: 8–9 ms for a whole 3 000-tick
                    // fight's worst single walker tick, on both loaders. Twice out of three, a
                    // NeoForge dedicated run instead spent SIXTY SECONDS inside one of these and was
                    // killed by the server hang watchdog, in `SwimBankClimbBreak.eval` one run and
                    // `Parkour3.valid` the next — both per-move world reads during expansion.
                    //
                    // This logs and carries on rather than bailing: bailing would change which paths
                    // are found and hide the thing being measured, and the run has to survive to
                    // produce the record. WARN so no filter drops it, and capped so one bad search
                    // cannot flood the log.
                    long nodeStart = System.nanoTime();
                    Move slow = null;
                    for (Move m : activeMoves) {
                        slow = m;
                        // eval() → null for an inadmissible move, else a concrete
                        // edge (dynamic cost + any break/place actions).
                        Move.Edge edge = m.eval(world, cur.pos);
                        if (edge == null) continue;
                        BlockPos npos = edge.to;
                        if (!constraints.isEmpty()) {
                            boolean pruned = false;
                            for (Constraint c : constraints) {
                                if (!c.allows(cur.pos, npos, edge, goal, world)) { pruned = true; break; }
                            }
                            if (pruned) continue;   // A2a: hard edge prune (successor never generated)
                        }
                        // Soft danger penalty per entered cell (Baritone avoidance);
                        // ≥ 0 so the heuristic stays admissible.
                        double ng = cur.g + edge.cost + world.dangerCost(npos)
                                + world.directionalCost(cur.pos, npos);
                        for (int mi = 0; mi < costModifiers.size(); mi++) {
                            double extra = costModifiers.get(mi).extraCost(cur.pos, npos, edge, goal, world);
                            ng += extra;
                            // TAX_LOG is a compile-time constant, so with it off the JIT
                            // folds this away entirely. Indexed iteration replaces the
                            // for-each: same list, same order, same doubles, same sum.
                            if (TAX_LOG && extra != 0) { expandTax[mi] += extra; expandHits[mi]++; }
                        }
                        Node existing = nodes.get(npos);
                        if (existing != null && ng > existing.g - MIN_IMPROVEMENT) continue;
                        if (existing == null) {
                            Node next = new Node(npos, cur, edge, ng, heuristic(npos));
                            nodes.put(npos, next);
                            open.add(next);
                        } else {
                            existing.parent = cur;
                            existing.edge = edge;
                            existing.g = ng;
                            existing.f = ng + BotConfig.pathfinderHeuristicWeight * existing.h;
                            existing.closed = false;
                            open.add(existing);
                        }
                    }
                    long nodeCost = System.nanoTime() - nodeStart;
                    if (nodeCost > runawayLimit && runawayLogged < RUNAWAY_LOG_CAP) {
                        runawayLogged++;
                        LOG.warn("[pathfinder] RUNAWAY expansion {} ms at {} (limit {} ms) move={} goal={}"
                                + " expanded={} open={} nodes={} owner={}",
                                nodeCost / 1_000_000L, cur.pos.toShortString(),
                                runawayLimit / 1_000_000L, slow == null ? "none" : slow.getClass().getSimpleName(),
                                goal, expanded, open.size(), nodes.size(), owner);
                    }
                }
                // open empty / node budget / time budget → commit best-effort segment
                Node segment = chooseSegment();
                if (BotConfig.walkerDebug) {
                    LOG.info("[pathfinder] STOP cause={} expanded={} ms={} segment={} segG={} bestAshore={} bestAshoreG={} bestClimbY={} openLeft={}",
                            stopCause, expanded, totalMs(sliceStart), segmentReason,
                            segment == null ? -1 : segment.g,
                            bestAshore == null ? "null" : bestAshore.pos.toShortString(),
                            bestAshore == null ? -1 : bestAshore.g,
                            bestClimb == null ? "null" : bestClimb.pos.getY(),
                            open.size());
                }
                finish((segment == null)
                        ? new Result(List.of(), List.of(), false, expanded, totalMs(sliceStart), startNode.h)
                        : build(segment, false, expanded, totalMs(sliceStart), segment.g));
                return true;
            } finally {
                world.cacheActive(false);
                long spent = System.nanoTime() - sliceStart;
                elapsedNanos += spent;
                chargeTick(spent);
            }
        }

        /**
         * Adds this slice's wall-clock to the current tick's account and reports when the total
         * crosses each {@link #TICK_REPORT_NANOS} boundary. See {@link #TICK_SPEND}.
         *
         * <p>Charged in the {@code finally} beside {@code elapsedNanos} deliberately: that is the
         * one place every exit from {@code advance()} passes through — the yield return, the
         * goal-reached return, the best-effort return and any throw alike. An instrument attached
         * to a normal-return path would under-report exactly the pathological searches, which is
         * the mistake the HEARTBEAT note above was written about.
         */
        private void chargeTick(long spentNanos) {
            long marker = world.tickMarker();
            if (marker == Long.MIN_VALUE) return;      // view has no clock; do not invent one
            long[] ts = TICK_SPEND.get();
            if (ts[TS_MARKER] != marker) {             // a real tick boundary: start a fresh account
                ts[TS_MARKER] = marker;
                ts[TS_NANOS] = 0L;
                ts[TS_SEARCHES] = 0L;
                ts[TS_NEXT_REPORT] = TICK_REPORT_NANOS;
            }
            ts[TS_NANOS] += spentNanos;
            ts[TS_SEARCHES]++;
            if (ts[TS_NANOS] < ts[TS_NEXT_REPORT]
                    || ts[TS_NEXT_REPORT] > TICK_REPORT_LIMIT * TICK_REPORT_NANOS) {
                return;
            }
            ts[TS_NEXT_REPORT] += TICK_REPORT_NANOS;
            LOG.warn("[pathfinder] TICK SPEND {} ms across {} advance() calls in ONE tick"
                    + " (gameTime={}) — owner={} thread={}. No existing budget can see this:"
                    + " sliceMs and the heartbeat reset every advance(), maxMs and the {} ms"
                    + " ceiling reset every search. If this series reaches the 60 s watchdog the"
                    + " server dies with every one of those budgets silent.",
                    ts[TS_NANOS] / 1_000_000L, ts[TS_SEARCHES], marker, owner,
                    Thread.currentThread().getName(), CEILING_MS);
        }

        /** True if any cardinal-horizontal neighbour of {@code p} sits in an
         *  unloaded chunk — i.e. {@code p} is on the edge of known terrain. */
        private boolean bordersUnknown(BlockPos p) {
            return !world.isKnown(p.offset(1, 0, 0)) || !world.isKnown(p.offset(-1, 0, 0))
                || !world.isKnown(p.offset(0, 0, 1)) || !world.isKnown(p.offset(0, 0, -1));
        }

        /** Pick the segment to commit when the goal wasn't reached. With frontier
         *  planning on, prefer walking to the goal-ward edge of known terrain (so the
         *  bot advances, loads new chunks, and the next search extends) — but only
         *  when that frontier is real progress toward the goal (else a backward chunk
         *  edge would pull the bot the wrong way). Otherwise fall back to Baritone's
         *  conservative best-effort backoff. */
        private Node chooseSegment() {
            // Water escape (highest priority): a search that STARTED in water and
            // didn't reach the goal may ONLY commit to a segment that climbs ASHORE
            // (feet on dry ground). Priorities fall out of move cost: a FLUSH/step-up
            // bank (Walk 10 / StepUp 15) beats a BREAK-climb (SwimBankClimbBreak 22 +
            // dig), so the cheapest reachable shore is chosen first and break-climb is
            // the fallback — and only when allowBreak is on (its moves are pruned
            // otherwise). If NO ashore node is reachable (allowBreak off + a sheer-walled
            // bowl, say), return null = "no path": the bot stays put rather than commit a
            // fake in-water segment or dive. Land searches keep the conservative backoff.
            // A5: a DIVE-opt-in search is EXEMPT from the water-escape policy below —
            // its goal is (typically) UNDERWATER, so "may ONLY commit a segment that
            // climbs ASHORE" is exactly backwards: the live rc-a5i first search
            // (walkerDebug diagnostics) burned to the relaxed 24000-node tier and
            // committed the tank-rim climb-out (bestAshoreG=40730) while goal-ward
            // submerged bestSoFar progress was structurally excluded, stranding the
            // bot overland at a dead-end. With the exemption a dive search falls
            // through to the standard goal-ward selection (selectSegment/bestSoFar),
            // so best-effort progress goes TOWARD the underwater goal instead of
            // ashore. Non-dive water starts keep the escape policy byte-identically.
            if (startInWater && !diveRelief) {
                // A CHEAP climb-out (pure swim+walk, no submerged dig priced in)
                // commits as before. An EXPENSIVE one means the only dry cell the
                // budget reached sits behind a 5×-priced underwater dig (round37b:
                // lake-bed cave — the sole 6k-node "ashore" was a deepslate tunnel
                // while an open water column to the surface stood ONE CELL east,
                // ignored because surfacing isn't ashore). Prefer SURFACING: commit
                // the monotone-up bestClimb through open water and re-plan from the
                // surface vantage where the real shore is reachable. The dig-ashore
                // stays as the genuine last resort (a fully roofed water pocket).
                if (bestAshore != null && bestAshore.g <= ASHORE_CHEAP_G) { segmentReason = "water-ashore-cheap"; return bestAshore; }
                if (bestClimb != null && bestClimb.pos.getY() - start.getY() >= MIN_CLIMB_ESCAPE) {
                    segmentReason = "water-climb-surface";
                    return bestClimb;
                }
                segmentReason = bestAshore == null ? "water-none" : "water-ashore";
                return bestAshore;
            }

            if (BotConfig.pathfinderFrontierCommit && bestFrontier != null
                    && bestFrontier.h < startNode.h - MIN_FRONTIER_GAIN
                    && bestFrontier.pos.distSqr(start) > MIN_DIST_PATH * MIN_DIST_PATH) {
                segmentReason = "frontier-forward";
                return bestFrontier;
            }
            Node seg = selectSegment(bestSoFar, start);
            if (seg != null) { segmentReason = "goalward-bestSoFar"; return seg; }
            // Walled-to-frontier exploration (progressive planning): no goal-WARD segment
            // (the direct line is walled) but the search reached the edge of KNOWN terrain.
            // The detour around the wall lies in UNLOADED chunks, so grinding the hard
            // maxNodes budget can't find it — it isn't in the graph yet. Commit the frontier
            // node CLOSEST to the goal (min raw h) so the bot heads toward the goal-tangent,
            // loads new chunks, and the NEXT search sees around the wall: the bot WALL-FOLLOWS
            // smoothly instead of pillaring blindly up the wall (bestClimb below) or grinding
            // to the hard cap for a bestEscape (a ~3.4 s freeze per segment). Prefer this over
            // bestClimb: walking around a far mountain beats a blind +30 pillar that tops out
            // under a canopy (live boxed-pocket pillar storm). Guard out a strongly-BACKWARD
            // frontier (h much worse than start → would walk away from the goal); leave that
            // pathological concave pocket to bestClimb / bestEscape. The min-h selection makes
            // the committed edge the known point nearest the goal — the tangent, not a detour
            // away. INERT wherever every chunk is loaded (GameTest/arena): no node borders
            // unknown terrain, so bestFrontier stays null and this branch never fires.
            if (BotConfig.pathfinderFrontierCommit && bestFrontier != null
                    && bestFrontier.pos.distSqr(start) > MIN_DIST_PATH * MIN_DIST_PATH
                    && bestFrontier.h < startNode.h + MIN_FRONTIER_GAIN) {
                segmentReason = "frontier-walled";
                return bestFrontier;
            }
            // Boxed: no horizontal segment made real progress (conservative selector
            // refuses a backward/lateral hop). Escape VERTICALLY over the obstacle if we
            // climbed meaningfully above the start — pillar-up / dig-up, pure up + forward,
            // so it never introduces the camera-jarring backtrack the selector guards
            // against. The re-plan chain runs from the higher vantage; height is monotone
            // so it can't oscillate, and it self-terminates when blocks run out (no
            // climbed node → null → today's "no path"). User-chosen vertical escape.
            if (bestClimb != null && bestClimb.pos.getY() - start.getY() >= MIN_CLIMB_ESCAPE) {
                segmentReason = "climb-escape";
                return bestClimb;
            }
            // LAST RESORT — boxed with no goal-ward, frontier or upward escape at
            // the hard cap: every reachable cell lies BEHIND the start (e.g. a cave
            // network whose only free exits head away from a walled-off goal — the
            // detour REQUIRES backtracking, which the conservative selector above
            // refuses by design). Returning null here fails the goto even though
            // the terrain is escapable; instead commit the farthest reachable node
            // so the bot physically leaves the dead pocket — the next search runs
            // from a new vantage (outside the cave / fresh chunks) and can find the
            // way around. Oscillation is bounded by the stuck-penalties + anti-spin.
            // Unreachable only via hard-cap exhaustion: hasCommittableSegment()
            // deliberately ignores bestEscape, so soft-commit can never fire on it.
            // gap#63 split: the ANY-DIRECTION escape is only trustworthy when the
            // graph is truly EXHAUSTED (open set empty — "away" is provably the only
            // move). A search that merely ran out of BUDGET (open nodes left) may
            // only commit the ground-holding variant: with nodes still unexplored,
            // "everything heads away" is an artifact of the cap, and committing the
            // raw farthest node ran the live bot 75 blocks the wrong way per repath
            // (deep-cave drift churn, deaths #6/#7 window). No safe escape either →
            // null → the #50 futile backoff owns the fail-stop and the policy layer
            // re-plans (staged hops proved out live).
            if (bestEscapeSafe != null && bestEscapeSafeD2 > (long) MIN_DIST_PATH * MIN_DIST_PATH) {
                segmentReason = "escape-farthest";
                return bestEscapeSafe;
            }
            if (open.isEmpty()
                    && bestEscape != null && bestEscapeD2 > (long) MIN_DIST_PATH * MIN_DIST_PATH) {
                segmentReason = "escape-farthest-exhausted";
                return bestEscape;
            }
            segmentReason = "none";
            return null;
        }

        /** Cheap check: does a committable best-effort segment already exist? Mirrors the
         *  branches of {@link #chooseSegment} (ashore climb-out in water; else a bestSoFar
         *  node past MIN_DIST_PATH, or a vertical-escape climb above the start) without
         *  building the path. Drives the soft-commit early-stop
         *  ({@link BotConfig#pathfinderSoftCommitNodes}). */
        private boolean hasCommittableSegment() {
            // Water start mirrors chooseSegment's tiering: a cheap (dig-free)
            // ashore or a surfacing climb may early-stop; an expensive dig-ashore
            // alone keeps the search burning toward the relaxed tier — stopping at
            // 6k nodes on a 5×-priced underwater tunnel is exactly the round37b
            // lake-bed trap.
            // A5: dive searches skip the water tiering (mirrors chooseSegment's
            // exemption) — an ashore node must never soft-stop a search whose goal
            // is underwater; the land tiering below (goal-ward gain) applies instead.
            if (startInWater && !diveRelief) {
                boolean relaxedW = tuning.softCommitNodes() > 0
                        && expanded >= tuning.softCommitNodes() * 4L;
                return (bestAshore != null && (relaxedW || bestAshore.g <= ASHORE_CHEAP_G))
                        || (bestClimb != null && bestClimb.pos.getY() - start.getY() >= MIN_CLIMB_ESCAPE);
            }
            double minSq = MIN_DIST_PATH * MIN_DIST_PATH;
            // TIERED soft-commit: past 4× the soft budget (~24k nodes — the measured
            // canyon-exit cost), stop demanding the quality gain and take the best
            // segment/climb on offer. Without this tier the quality gate rode all the
            // way to the hard 60k cap on the nastiest cliff descents — a ~12 s
            // stand-still the video flagged ("悬崖边缘原地停顿约14秒"). Tiers: open
            // terrain commits at the soft budget (gain clears instantly); boxed
            // terrain burns up to 4× hunting a worthwhile segment (canyon exits fit
            // here); only a truly walled-in search degrades to best-available at 4×,
            // capping the planning stall at ~a third of the hard budget.
            boolean relaxed = tuning.softCommitNodes() > 0
                    && expanded >= tuning.softCommitNodes() * 4L;
            // Walled-to-frontier (progressive): reaching the edge of KNOWN terrain with no
            // goal-ward gain is committable NOW — the detour around the wall is beyond loaded
            // chunks, so grinding the hard budget can't find it (~3.4 s freeze for nothing).
            // Mirrors chooseSegment's walled-frontier branch so the soft early-stop fires on
            // it and the bot wall-follows at speed. Inert when fully loaded (bestFrontier null).
            if (BotConfig.pathfinderFrontierCommit && bestFrontier != null
                    && bestFrontier.pos.distSqr(start) > minSq
                    && bestFrontier.h < startNode.h + MIN_FRONTIER_GAIN) return true;
            for (Node n : bestSoFar) {
                // Distance alone is NOT committable: boxed in a canyon every repath
                // found SOME 5-block sideways scrap at the 6000-node soft budget and
                // committed it, each pointing a different way — the bot paced a
                // ~15-block box for minutes (live 2026-06-09, canyon at (415,-362):
                // the real exit was a +30 climb the probe only found at ~24k nodes).
                // Demand a real heuristic gain (≥ SOFT_MIN_GAIN ≈ 6 blocks toward an
                // XZ goal) before the soft early-stop may fire; open terrain clears
                // that instantly, while a boxed search keeps burning toward the hard
                // maxNodes cap until a worthwhile segment (or vertical escape) shows.
                if (n != null && n.pos.distSqr(start) > minSq
                        && (relaxed || startNode.h - n.h >= softMinGain())) return true;
            }
            // bestClimb does NOT soft-stop at the base tier. Letting a 3-5-block climb
            // scrap satisfy the soft budget recommitted pillar shreds in every direction
            // at a canyon whose real exit was +30 (live: pathLen 4-8 dirt-ladder
            // segments, bot burned 13 dirt pacing). At the RELAXED tier (4× burned,
            // nothing better found) a climb escape is accepted — by then it is the best
            // escape a substantial search produced, not a 6k-node shred.
            return relaxed && bestClimb != null
                    && bestClimb.pos.getY() - start.getY() >= MIN_CLIMB_ESCAPE;
        }

        /** Minimum heuristic improvement a best-effort segment must show before the
         *  soft-commit early-stop fires: HALF the horizon distance (~blocks×10 toward
         *  an XZ goal; horizon 48 → gain 240 ≈ 24 blocks). 60 (6 blocks) proved too
         *  low live: a boxed canyon offered 8-block pseudo-forward scraps in every
         *  direction at the 6000-node budget, so each repath committed a different
         *  scrap and the bot paced a 15-block box — while the true exit (a +30 climb)
         *  needed ~24k nodes. Open terrain clears 24 blocks within the soft budget
         *  easily, keeping the early-stop (and its no-freeze feel) intact there. */
        private double softMinGain() {
            int hb = tuning.horizonBlocks();                       // this finder's horizon, not the global's
            return hb > 0 ? hb * 10 / 2.0 : 240;
        }

        /** True if {@code p} is a dry standing cell — feet on solid dry ground, not
         *  in water. The "climbed ashore" test for the water-escape best-effort. */
        private boolean isAshore(BlockPos p) {
            return !world.isWater(p) && world.canStandOn(p.offset(0, -1, 0));
        }

        private long totalMs(long sliceStart) {
            return (elapsedNanos + (System.nanoTime() - sliceStart)) / 1_000_000L;
        }
    }

    /**
     * Incremental cost backoff selection: walk the coefficients from most
     * conservative (smallest, weights travelled cost heavily) to most
     * aggressive, returning the first candidate that travelled at least
     * {@link #MIN_DIST_PATH} blocks from the start. Returns null when no
     * candidate made real progress (we're boxed in) — caller treats that as
     * "no path" rather than committing to a segment that goes nowhere.
     */
    private static Node selectSegment(Node[] bestSoFar, BlockPos start) {
        // (Anti-backtrack A/B-INCONCLUSIVE 2026-06-06: preferring the best-effort node that
        // REDUCES h — to stop the chain committing to backward/sideways hops — only helped
        // marginally and noisily. The real backtrack cause in dense jungle is the SEARCH
        // BUDGET: at a terrain pinch the search hits maxMs (~31k nodes) before finding the
        // forward route, so even pass-1 finds no h-reducing node and falls back to a backward
        // hop anyway. Reverted to keep the core pathfinder unchanged; the real fix is a more
        // efficient search / better heuristic / budget tuning, not the segment-selection rule.)
        double minSq = MIN_DIST_PATH * MIN_DIST_PATH;
        for (Node n : bestSoFar) {
            if (n == null) continue;
            if (n.pos.distSqr(start) > minSq) return n;
        }
        return null;
    }

    /**
     * Rebuild the foot-position sequence and the parallel per-step edge list by
     * walking parent pointers from the end node back to the start. {@code edges}
     * is aligned with {@code path}: {@code edges.get(i)} is the edge that enters
     * {@code path.get(i)} (so {@code edges.get(0)} is null — the start has no
     * incoming edge). The Walker consults each edge's break/place actions before
     * stepping into the cell.
     */
    private static Result build(Node end, boolean reached, int expanded, long ms, double cost) {
        List<BlockPos> pos = new ArrayList<>();
        List<Move.Edge> eds = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) { pos.add(n.pos); eds.add(n.edge); }
        Collections.reverse(pos);
        Collections.reverse(eds);
        return new Result(List.copyOf(pos), Collections.unmodifiableList(eds), reached, expanded, ms, cost);
    }

    /**
     * Result of a path search. {@code path} is the foot-position sequence from
     * start (inclusive) to the goal or to the best-effort fallback (inclusive).
     * {@code edges} is aligned with {@code path} — {@code edges.get(i)} carries
     * the cost and any blocks to break/place to enter {@code path.get(i)};
     * {@code edges.get(0)} is null. If unreachable, both are empty.
     */
    public record Result(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached, int expanded, long ms, double finalCost) {
        /**
         * True when the result contains a walkable path (which may be the
         * best-effort fallback, not a path that actually reaches the goal).
         * For "did we reach the goal" use {@link #goalReached()} directly.
         */
        public boolean hasPath() { return !path.isEmpty(); }
    }

    private static final class Node {
        final BlockPos pos;
        Node parent;
        Move.Edge edge;     // the edge used to reach this node (null for start)
        double g, h, f;
        boolean closed;
        /** True when ANY edge on the path to this node breaks a block. The last-resort
         *  escape-farthest commit only considers {@code !dug} nodes: "leave the dead
         *  pocket" means a WALKABLE (walk/swim/pillar) exit — with break moves priced
         *  in, the farthest-g node in uniform rock is always straight DOWN (1 break/cell
         *  vs 2-3 lateral/up), which is how gap#59 drilled a 69-block shaft under two
         *  UP-goals (live 2026-07-13). Note {@code parent.dug} is read at construction:
         *  a later cheaper re-parenting can in principle clear a parent's flag, but a
         *  stale {@code true} here only makes escape-farthest MORE conservative. */
        final boolean dug;
        Node(BlockPos pos, Node parent, Move.Edge edge, double g, double h) {
            this.pos = pos;
            this.parent = parent;
            this.edge = edge;
            this.dug = parent != null && (parent.dug || (edge != null && !edge.toBreak.isEmpty()));
            this.g = g;
            this.h = h;            // raw (admissible) heuristic — best-effort selection reads this
            // Weighted A*: ordering key inflates h by W so the frontier drives harder
            // toward the goal within the time budget (see BotConfig.pathfinderHeuristicWeight).
            this.f = g + BotConfig.pathfinderHeuristicWeight * h;
        }
    }
}
