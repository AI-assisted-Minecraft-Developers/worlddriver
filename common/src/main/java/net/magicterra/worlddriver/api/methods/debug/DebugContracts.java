package net.magicterra.worlddriver.api.methods.debug;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;

/**
 * The path-debugging methods: {@code mc.debug.pathChart}, {@code mc.debug.plan} and {@code mc.debug.replay}.
 * They belong to a strippable subsystem, so they are published as their own batch when it starts; a game
 * without it answers {@code method_not_found}. {@code plan} and {@code replay} read the client's world.
 */
public final class DebugContracts {
    private DebugContracts() {}

    public record Column(int x, int z) {}

    public static final ApiType<Column> COLUMN = ApiTypes.record(Column.class, "Column",
            ApiField.field("x", ApiTypes.integer()).required().forGetter(Column::x),
            ApiField.field("z", ApiTypes.integer()).required().forGetter(Column::z),
            Column::new);

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);
    private static final ApiType<Long> LONG_COUNT = ApiTypes.int64(0, Long.MAX_VALUE);
    /** Search costs and goal estimates, rounded: tenths of a block's walking cost. */
    private static final ApiType<Long> COST = ApiTypes.int64(Long.MIN_VALUE, Long.MAX_VALUE);

    // ------------------------------------------------------------------ mc.debug.pathChart

    public enum ChartView implements net.magicterra.worlddriver.api.schema.WireName {
        DASHBOARD("dashboard"), THREE_VIEW("threeview");
        private final String wire;
        ChartView(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    public record ChartParams(ChartView view, int width, int height, boolean includeCandidates, boolean save,
                              Optional<String> name) {}

    /** How the charted goto ended, or that it still walks. */
    public enum ChartOutcome implements WireName {
        RUNNING("running"), SUCCESS("success"), NO_PATH("no_path"), STUCK("stuck"), TIMEOUT("timeout"),
        CANCELLED("cancelled"), ERROR("error");

        private final String wire;
        ChartOutcome(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    /** {@code path} and {@code bytes} are present when the chart was saved. */
    public record Chart(ChartView view, ChartOutcome outcome, int plans, int candidates, int samples, int width, int height,
                        Optional<String> path, Optional<Long> bytes) {}

    private static final ApiType<ChartView> CHART_VIEW = ApiTypes.enumOf("ChartView", ChartView.class);

    public static final ApiType<ChartParams> CHART_PARAMS = ApiTypes.record(ChartParams.class, "ChartParams",
            ApiField.field("view", CHART_VIEW)
                    .describe("dashboard: A* candidates, every planned route and the walked trajectory on a top-down "
                            + "map, with an elevation profile and speed / heading series. threeview: front, side and top "
                            + "projections of planned against actual.")
                    .defaulted(ChartView.DASHBOARD).forGetter(ChartParams::view),
            ApiField.field("width", ApiTypes.integer(256, 4096)).defaulted(1280).forGetter(ChartParams::width),
            ApiField.field("height", ApiTypes.integer(256, 4096)).defaulted(960).forGetter(ChartParams::height),
            ApiField.field("includeCandidates", ApiTypes.bool()).describe("Draw the expanded-node heat (dashboard only).")
                    .defaulted(true).forGetter(ChartParams::includeCandidates),
            ApiField.field("save", ApiTypes.bool()).describe("false renders and answers the size without writing a file.")
                    .defaulted(true).forGetter(ChartParams::save),
            ApiField.field("name", ApiTypes.pattern("[A-Za-z0-9_.-]{1,64}", "a file name without extension"))
                    .describe("Omitted: pathchart-NNNN-<ms>.").optional().forGetter(ChartParams::name),
            ChartParams::new);

    public static final ApiType<Chart> CHART = ApiTypes.record(Chart.class, "Chart",
            ApiField.field("view", CHART_VIEW).required().forGetter(Chart::view),
            ApiField.field("outcome", ApiTypes.enumOf("ChartOutcome", ChartOutcome.class))
                    .describe("How the charted goto ended; running while it walks.")
                    .required().forGetter(Chart::outcome),
            ApiField.field("plans", COUNT).required().forGetter(Chart::plans),
            ApiField.field("candidates", COUNT).required().forGetter(Chart::candidates),
            ApiField.field("samples", COUNT).required().forGetter(Chart::samples),
            ApiField.field("width", COUNT).required().forGetter(Chart::width),
            ApiField.field("height", COUNT).required().forGetter(Chart::height),
            ApiField.field("path", ApiTypes.string()).describe("Absolute path of the PNG.").optional().forGetter(Chart::path),
            ApiField.field("bytes", LONG_COUNT).optional().forGetter(Chart::bytes),
            Chart::new);

    public static final MethodSpec<ChartParams, Chart> PATH_CHART = MethodSpec.of("mc.debug.pathChart", CHART_PARAMS, CHART,
                    "Render the current goto's pathfinding chart to a PNG under config/worlddriver/debug/ and answer its "
                            + "path. Needs the pathDebug setting patched on BEFORE the goto, so the data is captured; "
                            + "callable mid-walk or after it ends.")
            .effects(MethodSpec.Effect.CONFIG_WRITE);

    // ------------------------------------------------------------------ mc.debug.plan

    public record PlanParams(Column goal, Optional<BlockPosition> from, boolean chain, int maxSegments) {}

    public sealed interface PlanReply permits SinglePlan, ChainPlan {}

    public record MoveCost(int n, long cost) {}

    public record Search(int expanded, long computeMs, long wallMs, long finalCost) {}

    /** The goal estimate at the start and at the committed end; {@code hDelta} below zero is progress. */
    public record Estimate(long hStart, long hEnd, long hDelta, boolean forward) {}

    /**
     * One search. {@code maxStepDrop} is the largest single-node fall the planner routed; {@code moves} is
     * each move's count and summed cost.
     */
    public record SinglePlan(BlockPosition start, Column goal, boolean goalReached, int pathLen, BlockPosition end,
                             Search search, Estimate estimate, int maxStepDrop, List<Integer> yProfile, List<String> nodes,
                             Map<String, MoveCost> moves, int breaks, int places) implements PlanReply {}

    public record Segment(BlockPosition end, long hEnd, long hDelta, int expanded, boolean goalReached,
                          Optional<BlockPosition> sunkTailCut) {}

    /** {@code maxRegression}: the worst overshoot back past the best progress so far (cost units; /10 ≈ blocks). */
    public record ChainPlan(BlockPosition start, Column goal, boolean reached, int segments, int backwardSegments,
                            long maxRegression, long totalExpanded, long hStart, long hFinal, List<Segment> trail)
            implements PlanReply {}

    public static final ApiType<PlanParams> PLAN_PARAMS = ApiTypes.record(PlanParams.class, "PathPlanParams",
            ApiField.field("goal", COLUMN).required().forGetter(PlanParams::goal),
            ApiField.field("from", CommonTypes.BLOCK_POSITION).describe("The start foot. Omitted: where the player stands.")
                    .optional().forGetter(PlanParams::from),
            ApiField.field("chain", ApiTypes.bool())
                    .describe("Feed each committed segment's end back in as the next start until the goal or no progress: "
                            + "the navigator's segment chain, simulated read-only.")
                    .defaulted(false).forGetter(PlanParams::chain),
            ApiField.field("maxSegments", ApiTypes.integer(1, 200)).describe("Chain cap.").defaulted(40)
                    .forGetter(PlanParams::maxSegments),
            PlanParams::new);

    private static final ApiType<MoveCost> MOVE_COST = ApiTypes.record(MoveCost.class, "MoveCost",
            ApiField.field("n", COUNT).required().forGetter(MoveCost::n),
            ApiField.field("cost", COST).required().forGetter(MoveCost::cost),
            MoveCost::new);

    private static final ApiType<Search> SEARCH = ApiTypes.record(Search.class, "PlanSearch",
            ApiField.field("expanded", COUNT).required().forGetter(Search::expanded),
            ApiField.field("computeMs", LONG_COUNT).required().forGetter(Search::computeMs),
            ApiField.field("wallMs", LONG_COUNT).required().forGetter(Search::wallMs),
            ApiField.field("finalCost", COST).required().forGetter(Search::finalCost),
            Search::new);

    private static final ApiType<Estimate> ESTIMATE = ApiTypes.record(Estimate.class, "PlanEstimate",
            ApiField.field("hStart", COST).required().forGetter(Estimate::hStart),
            ApiField.field("hEnd", COST).required().forGetter(Estimate::hEnd),
            ApiField.field("hDelta", COST).required().forGetter(Estimate::hDelta),
            ApiField.field("forward", ApiTypes.bool()).describe("The committed end is nearer the goal than the start.")
                    .required().forGetter(Estimate::forward),
            Estimate::new);

    private static final ApiType<Segment> SEGMENT =ApiTypes.record(Segment.class, "PlanSegment",
            ApiField.field("end", CommonTypes.BLOCK_POSITION).required().forGetter(Segment::end),
            ApiField.field("hEnd", COST).required().forGetter(Segment::hEnd),
            ApiField.field("hDelta", COST).required().forGetter(Segment::hDelta),
            ApiField.field("expanded", COUNT).required().forGetter(Segment::expanded),
            ApiField.field("goalReached", ApiTypes.bool()).required().forGetter(Segment::goalReached),
            ApiField.field("sunkTailCut", CommonTypes.BLOCK_POSITION)
                    .describe("Where the committed path was cut back from its sunk tail.").optional()
                    .forGetter(Segment::sunkTailCut),
            Segment::new);

    public static final UnionType<PlanReply> PLAN_REPLY = ApiTypes.taggedUnion("PlanReply", "mode",
            ApiTypes.variant("single", SinglePlan.class, ApiTypes.record(SinglePlan.class, null,
                    ApiField.field("start", CommonTypes.BLOCK_POSITION).required().forGetter(SinglePlan::start),
                    ApiField.field("goal", COLUMN).required().forGetter(SinglePlan::goal),
                    ApiField.field("goalReached", ApiTypes.bool()).required().forGetter(SinglePlan::goalReached),
                    ApiField.field("pathLen", COUNT).required().forGetter(SinglePlan::pathLen),
                    ApiField.field("end", CommonTypes.BLOCK_POSITION).required().forGetter(SinglePlan::end),
                    ApiField.field("search", SEARCH).required().forGetter(SinglePlan::search),
                    ApiField.field("estimate", ESTIMATE).required().forGetter(SinglePlan::estimate),
                    ApiField.field("maxStepDrop", ApiTypes.integer()).required().forGetter(SinglePlan::maxStepDrop),
                    ApiField.field("yProfile", ApiTypes.list(ApiTypes.integer(), Integer.MAX_VALUE))
                            .describe("Each node's y along the committed path.").required().forGetter(SinglePlan::yProfile),
                    ApiField.field("nodes", ApiTypes.list(ApiTypes.string(), Integer.MAX_VALUE)).describe("\"x,y,z\" per node.")
                            .required().forGetter(SinglePlan::nodes),
                    ApiField.field("moves", ApiTypes.map(MOVE_COST, 256)).required().forGetter(SinglePlan::moves),
                    ApiField.field("breaks", COUNT).required().forGetter(SinglePlan::breaks),
                    ApiField.field("places", COUNT).required().forGetter(SinglePlan::places),
                    SinglePlan::new)),
            ApiTypes.variant("chain", ChainPlan.class, ApiTypes.record(ChainPlan.class, null,
                    ApiField.field("start", CommonTypes.BLOCK_POSITION).required().forGetter(ChainPlan::start),
                    ApiField.field("goal", COLUMN).required().forGetter(ChainPlan::goal),
                    ApiField.field("reached", ApiTypes.bool()).required().forGetter(ChainPlan::reached),
                    ApiField.field("segments", COUNT).required().forGetter(ChainPlan::segments),
                    ApiField.field("backwardSegments", COUNT).required().forGetter(ChainPlan::backwardSegments),
                    ApiField.field("maxRegression", COST).required().forGetter(ChainPlan::maxRegression),
                    ApiField.field("totalExpanded", LONG_COUNT).required().forGetter(ChainPlan::totalExpanded),
                    ApiField.field("hStart", COST).required().forGetter(ChainPlan::hStart),
                    ApiField.field("hFinal", COST).required().forGetter(ChainPlan::hFinal),
                    ApiField.field("trail", ApiTypes.list(SEGMENT, 200)).required().forGetter(ChainPlan::trail),
                    ChainPlan::new)));

    public static final MethodSpec<PlanParams, PlanReply> PLAN = MethodSpec.of("mc.debug.plan", PLAN_PARAMS, PLAN_REPLY,
                    "One read-only A* search from a fixed start to an XZ goal: no walking, no block edits. The measurement "
                            + "backbone for pathfinding A/B: patch pathfinder.maxNodes to a node budget and pathfinder.maxMs "
                            + "high so the search repeats exactly, then compare forward progress before and after a change. "
                            + "chain:true answers the whole segment chain (mode chain) instead of one search (mode single).")
            .execution(MethodSpec.ExecutionClass.BULK);

    // ------------------------------------------------------------------ mc.debug.replay

    public record ReplayParams(Optional<String> file, boolean restoreBlocks, boolean replan) {}

    public sealed interface ReplayReply permits Replanned, Replayed {}

    /** {@code fidelity}: whether every restored cell had its full block state, or older cells fell back to defaults. */
    public record Replanned(String file, String goal, int envelopeCells, int restoredBlocks, String fidelity)
            implements ReplayReply {}

    public record Replayed(String file, int segments, int plannedNodes, int restoredBlocks, String fidelity)
            implements ReplayReply {}

    public static final ApiType<ReplayParams> REPLAY_PARAMS = ApiTypes.record(ReplayParams.class, "ReplayParams",
            ApiField.field("file", ApiTypes.pattern("[A-Za-z0-9_.-]{1,128}\\.json", "an archive file name"))
                    .describe("Under config/worlddriver/replays/. Omitted: the newest plan archive.")
                    .optional().forGetter(ReplayParams::file),
            ApiField.field("restoreBlocks", ApiTypes.bool()).describe("Put the recorded block envelope back first.")
                    .defaulted(true).forGetter(ReplayParams::restoreBlocks),
            ApiField.field("replan", ApiTypes.bool())
                    .describe("true: re-issue the recorded goal with normal planning, which is deterministic in identical "
                            + "terrain, so the live run's repaths reproduce. false: walk the stored plan verbatim.")
                    .defaulted(true).forGetter(ReplayParams::replan),
            ReplayParams::new);

    public static final UnionType<ReplayReply> REPLAY_REPLY = ApiTypes.taggedUnion("ReplayReply", "mode",
            ApiTypes.variant("replan", Replanned.class, ApiTypes.record(Replanned.class, null,
                    ApiField.field("file", ApiTypes.string()).required().forGetter(Replanned::file),
                    ApiField.field("goal", ApiTypes.string()).describe("For display only; its wording is not a format to parse.")
                            .required().forGetter(Replanned::goal),
                    ApiField.field("envelopeCells", COUNT).required().forGetter(Replanned::envelopeCells),
                    ApiField.field("restoredBlocks", COUNT).required().forGetter(Replanned::restoredBlocks),
                    ApiField.field("fidelity", ApiTypes.string()).required().forGetter(Replanned::fidelity),
                    Replanned::new)),
            ApiTypes.variant("plan", Replayed.class, ApiTypes.record(Replayed.class, null,
                    ApiField.field("file", ApiTypes.string()).required().forGetter(Replayed::file),
                    ApiField.field("segments", COUNT).required().forGetter(Replayed::segments),
                    ApiField.field("plannedNodes", COUNT).required().forGetter(Replayed::plannedNodes),
                    ApiField.field("restoredBlocks", COUNT).required().forGetter(Replayed::restoredBlocks),
                    ApiField.field("fidelity", ApiTypes.string()).required().forGetter(Replayed::fidelity),
                    Replayed::new)));

    public static final MethodSpec<ReplayParams, ReplayReply> REPLAY = MethodSpec.of("mc.debug.replay", REPLAY_PARAMS,
                    REPLAY_REPLY,
                    "Replay a recorded goto archive: restore its block envelope, teleport this client's player to the "
                            + "recorded start and run again, writing a replay-run-*.json with the trajectory and its "
                            + "deviation per step. Answers once the run is installed; read its progress from mc.bot.status. "
                            + "An unknown or unreadable file is invalid_params at /file.")
            .effects(MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.BODY_TASK)
            .mayWait();
}
