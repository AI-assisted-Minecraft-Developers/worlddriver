package net.magicterra.worlddriver.api.methods.debug;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * The path-debugging methods: {@code mc.debug.pathChart}, {@code mc.debug.plan} and {@code mc.debug.replay}. They
 * belong to a strippable subsystem, so they are published as their own batch when it starts; a game without it answers
 * {@code method_not_found}. {@code plan} and {@code replay} read the client's world.
 */
public final class DebugContracts {

    public static final ApiType<ChartParams> CHART_PARAMS = ApiTypes.record(ChartParams.class);

    public static final ApiType<Chart> CHART = ApiTypes.record(Chart.class);

    public static final MethodSpec<ChartParams, Chart> PATH_CHART = MethodSpec.of(
                    "mc.debug.pathChart",
                    CHART_PARAMS,
                    CHART,
                    "Render the current goto's pathfinding chart to a PNG under config/worlddriver/debug/ and answer its "
                            + "path. Needs the pathDebug setting patched on BEFORE the goto, so the data is captured; "
                            + "callable mid-walk or after it ends.")
            .effects(MethodSpec.Effect.CONFIG_WRITE);

    public static final ApiType<PlanParams> PLAN_PARAMS = ApiTypes.record(PlanParams.class);

    public static final UnionType<PlanReply> PLAN_REPLY = ApiTypes.union(PlanReply.class);

    public static final MethodSpec<PlanParams, PlanReply> PLAN = MethodSpec.of(
                    "mc.debug.plan",
                    PLAN_PARAMS,
                    PLAN_REPLY,
                    "One read-only A* search from a fixed start to a goto target: no walking, no block edits. The measurement "
                            + "backbone for pathfinding A/B: patch pathfinder.maxNodes to a node budget and pathfinder.maxMs "
                            + "high so the search repeats exactly, then compare forward progress before and after a change. "
                            + "chain:true answers the whole segment chain (mode chain) instead of one search (mode single).")
            .execution(MethodSpec.ExecutionClass.BULK);

    public static final ApiType<WalkPlanParams> WALK_PLAN_PARAMS = ApiTypes.record(WalkPlanParams.class);

    public static final ApiType<WalkingPlan> WALKING_PLAN = ApiTypes.record(WalkingPlan.class);

    public static final MethodSpec<WalkPlanParams, WalkingPlan> WALK_PLAN = MethodSpec.of(
                    "mc.debug.walkPlan",
                    WALK_PLAN_PARAMS,
                    WALKING_PLAN,
                    "Walk a given plan on a server-side bot as its task, through the same navigator a goto uses: verbatim "
                            + "(replay) or adopted with the live recovery on (follow). Answers once the task is started, on "
                            + "the server thread.")
            .hidden()
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE);

    public static final ApiType<ReplayParams> REPLAY_PARAMS = ApiTypes.record(ReplayParams.class);

    public static final UnionType<ReplayReply> REPLAY_REPLY = ApiTypes.union(ReplayReply.class);

    public static final MethodSpec<ReplayParams, ReplayReply> REPLAY = MethodSpec.of(
                    "mc.debug.replay",
                    REPLAY_PARAMS,
                    REPLAY_REPLY,
                    "Replay a recorded goto archive: restore its block envelope, put the bot at the "
                            + "recorded start and run again, writing a replay-run-*.json with the trajectory and its "
                            + "deviation per step. Answers once the run is installed; read its progress from mc.bot.status. "
                            + "An unknown or unreadable file is invalid_params at /file.")
            .effects(MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.BODY_TASK)
            .mayWait();

    private DebugContracts() {}

    // ------------------------------------------------------------------ mc.debug.pathChart

    @Named("ChartView")
    public enum ChartView implements net.magicterra.worlddriver.api.schema.WireName {
        DASHBOARD("dashboard"),
        THREE_VIEW("threeview");
        private final String wire;

        ChartView(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    @Named("ChartParams")
    public record ChartParams(
            @Doc("dashboard: A* candidates, every planned route and the walked trajectory on a top-down map, with an "
                    + "elevation profile and speed / heading series. threeview: front, side and top projections of "
                    + "planned against actual.")
            @Default("dashboard")
            ChartView view,

            @Default("1280") @Range(min = 256, max = 4096) int width,
            @Default("960") @Range(min = 256, max = 4096) int height,

            @Doc("Draw the expanded-node heat (dashboard only).") @Default("true")
            boolean includeCandidates,

            @Doc("false renders and answers the size without writing a file.") @Default("true")
            boolean save,

            @Doc("Omitted: pathchart-NNNN-<ms>.")
            Optional<@Pattern(value = "[A-Za-z0-9_.-]{1,64}", expected = "a file name without extension") String>
                    name) {}

    /** How the charted goto ended, or that it still walks. */
    @Named("ChartOutcome")
    public enum ChartOutcome implements WireName {
        RUNNING("running"),
        SUCCESS("success"),
        NO_PATH("no_path"),
        STUCK("stuck"),
        TIMEOUT("timeout"),
        CANCELLED("cancelled"),
        ERROR("error");

        private final String wire;

        ChartOutcome(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** {@code path} and {@code bytes} are present when the chart was saved. */
    @Named("Chart")
    public record Chart(
            ChartView view,

            @Doc("How the charted goto ended; running while it walks.")
            ChartOutcome outcome,

            @Range(min = 0) int plans,
            @Range(min = 0) int candidates,
            @Range(min = 0) int samples,
            @Range(min = 0) int width,
            @Range(min = 0) int height,
            @Doc("Absolute path of the PNG.") Optional<String> path,
            Optional<@Range(min = 0) Long> bytes) {}

    // ------------------------------------------------------------------ mc.debug.plan

    /**
     * {@code bot} absent or {@code self}: the client's player in the client's world; a named bot: that bot in the
     * server's world, with its own bag deciding what it may place and dig with. {@code route} absent plans as a bare
     * search does, with the settings' cost model; {@code maxNodes} and {@code maxMs} absent take the settings'.
     */
    @Named("PathPlanParams")
    public record PlanParams(
            @Doc("Omitted or self: the client's player and world. A named bot: that bot in the server's world.")
            Optional<@BotRef String> bot,

            GotoContracts.Target target,

            @Doc("The start foot. Omitted: where the bot stands.")
            Optional<BlockPosition> from,

            @Doc("Plan as mc.bot.goto would with this route. Omitted: a bare search with the settings' cost model.")
            Optional<RouteContracts.Route> route,

            @Doc("Feed each committed segment's end back in as the next start until the goal or no progress: the "
                    + "navigator's segment chain, simulated read-only.")
            @Default("false")
            boolean chain,

            @Doc("Chain cap.") @Default("40") @Range(min = 1, max = 200)
            int maxSegments,

            @Doc("Omitted: pathfinder.maxNodes.") Optional<@Range(min = 1, max = 10_000_000) Integer> maxNodes,
            // Half the long range, so a budget added to a clock reading cannot overflow.
            @Doc("Omitted: pathfinder.maxMs.") Optional<@Range(min = 1, max = 1L << 62) Long> maxMs) {
        public static PlanParams of(String bot, GotoContracts.Target target) {
            return new PlanParams(
                    Optional.of(bot),
                    target,
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    40,
                    Optional.empty(),
                    Optional.empty());
        }

        public PlanParams from(BlockPosition start) {
            return new PlanParams(bot, target, Optional.of(start), route, chain, maxSegments, maxNodes, maxMs);
        }

        public PlanParams route(RouteContracts.Route r) {
            return new PlanParams(bot, target, from, Optional.of(r), chain, maxSegments, maxNodes, maxMs);
        }

        public PlanParams budget(int nodes, long ms) {
            return new PlanParams(bot, target, from, route, chain, maxSegments, Optional.of(nodes), Optional.of(ms));
        }
    }

    @Tag("mode")
    @Named("PlanReply")
    public sealed interface PlanReply permits SinglePlan, ChainPlan {}

    @Named("MoveCost")
    public record MoveCost(@Range(min = 0) int n, long cost) {}

    /**
     * The edge entering a node: its move, its cost, the cells it digs and fills on the way, and what each cost modifier
     * added to the cost, by name (absent: none charged).
     */
    @Named("PlanEdge")
    public record PlanEdge(
            BlockPosition to,
            @Doc("Absent on the start node.") Optional<String> move,
            long cost,
            @Length(max = 64) List<BlockPosition> breaks,
            @Length(max = 64) List<BlockPosition> places,

            @Doc("Cost modifier name → what it added.") @Default("{}") @Length(max = 64)
            Map<String, Double> taxes) {
        public double tax(String name) {
            return taxes.getOrDefault(name, 0.0);
        }
    }

    @Named("PlanSearch")
    public record Search(
            @Range(min = 0) int expanded,
            @Range(min = 0) long computeMs,
            @Range(min = 0) long wallMs,
            long finalCost) {}

    /**
     * {@code taxes}: each cost modifier's total over the path. {@code prunedBy}: the edges each hard constraint
     * refused. {@code blockedBy}: the route's own constraint that refused the most, as {@code constraint:<name>}.
     * {@code sightBudgetExhausted}: the search spent its ray budget and was rerun without {@code route.sight}.
     * {@code snapshotTruncated}: the box scanned for entities was capped.
     */
    @Named("PlanConditions")
    public record Conditions(
            @Doc("Each cost modifier's total over the path.") @Length(max = 64)
            Map<String, Double> taxes,

            @Doc("Hard constraint name → edges it refused.") @Length(max = 64)
            Map<String, @Range(min = 0) Integer> prunedBy,

            @Doc("constraint:<name>: the route's own constraint that refused the most. Absent without a route or when "
                    + "none of its constraints refused anything.")
            Optional<String> blockedBy,

            @Doc("The ray budget ran out; the search was rerun without route.sight.")
            boolean sightBudgetExhausted,

            @Doc("The box scanned for entities was capped.") boolean snapshotTruncated) {
        public double tax(String name) {
            return taxes.getOrDefault(name, 0.0);
        }
    }

    /** The goal estimate at the start and at the committed end; {@code hDelta} below zero is progress. */
    @Named("PlanEstimate")
    public record Estimate(
            long hStart,
            long hEnd,
            long hDelta,

            @Doc("The committed end is nearer the goal than the start.")
            boolean forward) {}

    /**
     * One search. {@code maxStepDrop} is the largest single-node fall the planner routed; {@code moves} is each move's
     * count and summed cost; {@code conditions} what the cost model and the hard constraints did.
     */
    @Variant("single")
    public record SinglePlan(
            BlockPosition start,
            boolean goalReached,
            @Range(min = 0) int pathLen,
            BlockPosition end,
            Search search,
            Estimate estimate,
            int maxStepDrop,
            @Doc("Each node's y along the committed path.") List<Integer> yProfile,
            @Doc("\"x,y,z\" per node.") List<String> nodes,
            @Length(max = 256) Map<String, MoveCost> moves,
            @Range(min = 0) int breaks,
            @Range(min = 0) int places,

            @Doc("The edge entering each node, in path order.")
            List<PlanEdge> edges,

            Conditions conditions)
            implements PlanReply {
        /** The path's cells, start first. */
        public List<BlockPosition> cells() {
            return edges.stream().map(PlanEdge::to).toList();
        }
    }

    @Named("PlanSegment")
    public record Segment(
            BlockPosition end,
            long hEnd,
            long hDelta,
            @Range(min = 0) int expanded,
            boolean goalReached,

            @Doc("Where the committed path was cut back from its sunk tail.")
            Optional<BlockPosition> sunkTailCut) {}

    /** {@code maxRegression}: the worst overshoot back past the best progress so far (cost units; /10 ≈ blocks). */
    @Variant("chain")
    public record ChainPlan(
            BlockPosition start,
            boolean reached,
            @Range(min = 0) int segments,
            @Range(min = 0) int backwardSegments,
            long maxRegression,
            @Range(min = 0) long totalExpanded,
            long hStart,
            long hFinal,
            @Length(max = 200) List<Segment> trail)
            implements PlanReply {}

    // ------------------------------------------------------------------ mc.debug.walkPlan

    /** How a given plan is walked: verbatim with no search, or adopted with the live recovery and repath left on. */
    @Named("WalkMode")
    public enum WalkMode implements WireName {
        REPLAY,
        FOLLOW;

        @Override
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Walk {@code plan} on a server-side bot as its task: each edge enters its node, in path order, as
     * {@code mc.debug.plan} answers them (an edge with no {@code move} is a start node). {@code goal} absent: the last
     * node's block. {@code from}: the foot the plan is anchored to, absent the first node. {@code goalReached}: whether
     * the plan's last node is the goal; a best-effort plan is one the navigator may replace with its own.
     */
    @Named("WalkPlanParams")
    public record WalkPlanParams(
            @Doc("A server-side bot.") @BotRef String bot,

            @Doc("The nodes and the edge entering each, in path order.") @Length(max = 4096)
            List<PlanEdge> plan,

            @Doc("Omitted: the last node's block.") Optional<GotoContracts.Target> goal,

            @Doc("The foot the plan is anchored to. Omitted: the first node.")
            Optional<BlockPosition> from,

            @Doc("replay: verbatim, no search; whatever would drop the plan ends the walk. follow: adopted with the "
                    + "live recovery and repath on.")
            @Default("replay")
            WalkMode mode,

            @Doc("The last node is the goal, not a best-effort end.") @Default("true")
            boolean goalReached,

            @Doc("The route conditions the navigator walks under.")
            Optional<RouteContracts.Route> route) {
        public static WalkPlanParams replay(String bot, List<PlanEdge> plan) {
            return new WalkPlanParams(
                    bot, plan, Optional.empty(), Optional.empty(), WalkMode.REPLAY, true, Optional.empty());
        }

        public static WalkPlanParams follow(String bot, List<PlanEdge> plan, boolean goalReached) {
            return new WalkPlanParams(
                    bot, plan, Optional.empty(), Optional.empty(), WalkMode.FOLLOW, goalReached, Optional.empty());
        }

        public WalkPlanParams goal(GotoContracts.Target g) {
            return new WalkPlanParams(bot, plan, Optional.of(g), from, mode, goalReached, route);
        }

        public WalkPlanParams from(BlockPosition foot) {
            return new WalkPlanParams(bot, plan, goal, Optional.of(foot), mode, goalReached, route);
        }

        public WalkPlanParams route(RouteContracts.Route r) {
            return new WalkPlanParams(bot, plan, goal, from, mode, goalReached, Optional.of(r));
        }
    }

    /**
     * The task started; {@code nodes}: the plan's length. Its progress reads from {@code mc.bot.status} and
     * {@code mc.debug.navigator}.
     */
    @Named("WalkingPlan")
    public record WalkingPlan(
            @BotRef String bot, @Range(min = 0) int nodes) {}

    // ------------------------------------------------------------------ mc.debug.replay

    @Named("ReplayParams")
    public record ReplayParams(
            @Doc("self, this client's player, or a server-side bot.") @Default("self") @BotRef
            String bot,

            @Doc("Under config/worlddriver/replays/. Omitted: the newest plan archive.")
            Optional<@Pattern(value = "[A-Za-z0-9_.-]{1,128}\\.json", expected = "an archive file name") String> file,

            @Doc("Put the recorded block envelope back first.") @Default("true")
            boolean restoreBlocks,

            @Doc("true: re-issue the recorded goal with normal planning, which is deterministic in identical terrain, "
                    + "so the live run's repaths reproduce. false: walk the stored plan verbatim.")
            @Default("true")
            boolean replan) {
        /** Walk {@code file}'s stored plan verbatim on {@code bot}, in the terrain as it stands. */
        public static ReplayParams plan(String bot, String file) {
            return new ReplayParams(bot, Optional.of(file), false, false);
        }
    }

    @Tag("mode")
    @Named("ReplayReply")
    public sealed interface ReplayReply permits Replanned, Replayed {}

    /** {@code fidelity}: whether every restored cell had its full block state, or older cells fell back to defaults. */
    @Variant("replan")
    public record Replanned(
            String file,

            @Doc("For display only; its wording is not a format to parse.")
            String goal,

            @Range(min = 0) int envelopeCells,
            @Range(min = 0) int restoredBlocks,
            String fidelity)
            implements ReplayReply {}

    @Variant("plan")
    public record Replayed(
            String file,
            @Range(min = 0) int segments,
            @Range(min = 0) int plannedNodes,
            @Range(min = 0) int restoredBlocks,
            String fidelity)
            implements ReplayReply {}
}
