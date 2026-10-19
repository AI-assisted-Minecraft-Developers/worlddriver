package net.magicterra.worlddriver.api.methods.debug;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.WireName;

/**
 * What a server-side bot's insides read right now: {@code mc.debug.navigator}, {@code mc.debug.body} and
 * {@code mc.debug.cells}. For a program checking the driver itself, a test above all; an agent steers on
 * {@code mc.bot.status}, which is why these are hidden. The counter and trace names are the driver's own and
 * change with it.
 */
public final class BotDebugContracts {
    private BotDebugContracts() {}

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

    // ------------------------------------------------------------------ mc.debug.navigator

    /** {@code newLeg} starts a new leg on the bot's telemetry before it is read, as a fresh walk would. */
    public record NavigatorParams(String bot, boolean newLeg) {
        public static NavigatorParams of(String bot) { return new NavigatorParams(bot, false); }
    }

    /** Where the plan steers now: the node at {@code step}, the move entering it, and which drive branch ran. */
    public record PlanCursor(Optional<BlockPosition> node, Optional<String> move, int step, int len,
                             Optional<String> parkourTakeoff, Optional<String> driveTag, Optional<String> jumpTag,
                             Optional<String> aimTag) {}

    /** The last deep search any navigator on this body finished. */
    public record LastSearch(int expanded, long ms, boolean goalReached, double finalCost, int pathLen,
                             boolean sightBudgetExhausted, boolean snapshotTruncated) {}

    /**
     * {@code process}: the kind of the task whose navigator answered, the running one or else the last a caller
     * started; {@code running} whether it still runs. {@code plan} and the navigator's own counters are absent
     * when no such task owns a navigator. {@code counters}, {@code flags} and {@code traces} hold the
     * navigator's tallies, the body's telemetry and the driver-wide counters, by name.
     */
    public record NavigatorReading(String bot, Optional<String> process, boolean running, Optional<PlanCursor> plan,
                                   Optional<LastSearch> lastSearch, Map<String, Long> counters,
                                   Map<String, Boolean> flags, Map<String, String> traces) {
        public long counter(String name) {
            Long v = counters.get(name);
            if (v == null) throw new IllegalArgumentException("no counter " + name + " in " + counters.keySet());
            return v;
        }
        public boolean flag(String name) {
            Boolean v = flags.get(name);
            if (v == null) throw new IllegalArgumentException("no flag " + name + " in " + flags.keySet());
            return v;
        }
        public String trace(String name) {
            String v = traces.get(name);
            if (v == null) throw new IllegalArgumentException("no trace " + name + " in " + traces.keySet());
            return v;
        }
    }

    public static final RecordType<NavigatorParams> NAVIGATOR_PARAMS = ApiTypes.record(NavigatorParams.class, "NavigatorParams",
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(NavigatorParams::bot),
            ApiField.field("newLeg", ApiTypes.bool()).describe("Start a new leg on the telemetry before reading it.")
                    .defaulted(false).forGetter(NavigatorParams::newLeg),
            NavigatorParams::new);

    private static final RecordType<PlanCursor> PLAN_CURSOR = ApiTypes.record(PlanCursor.class, "PlanCursor",
            ApiField.field("node", CommonTypes.BLOCK_POSITION).optional().forGetter(PlanCursor::node),
            ApiField.field("move", ApiTypes.string()).optional().forGetter(PlanCursor::move),
            ApiField.field("step", ApiTypes.integer()).required().forGetter(PlanCursor::step),
            ApiField.field("len", ApiTypes.integer()).required().forGetter(PlanCursor::len),
            ApiField.field("parkourTakeoff", ApiTypes.string()).optional().forGetter(PlanCursor::parkourTakeoff),
            ApiField.field("driveTag", ApiTypes.string()).optional().forGetter(PlanCursor::driveTag),
            ApiField.field("jumpTag", ApiTypes.string()).optional().forGetter(PlanCursor::jumpTag),
            ApiField.field("aimTag", ApiTypes.string()).optional().forGetter(PlanCursor::aimTag),
            PlanCursor::new);

    private static final RecordType<LastSearch> LAST_SEARCH = ApiTypes.record(LastSearch.class, "LastSearch",
            ApiField.field("expanded", COUNT).required().forGetter(LastSearch::expanded),
            ApiField.field("ms", ApiTypes.int64(0, Long.MAX_VALUE)).required().forGetter(LastSearch::ms),
            ApiField.field("goalReached", ApiTypes.bool()).required().forGetter(LastSearch::goalReached),
            ApiField.field("finalCost", ApiTypes.number()).required().forGetter(LastSearch::finalCost),
            ApiField.field("pathLen", COUNT).required().forGetter(LastSearch::pathLen),
            ApiField.field("sightBudgetExhausted", ApiTypes.bool()).required().forGetter(LastSearch::sightBudgetExhausted),
            ApiField.field("snapshotTruncated", ApiTypes.bool()).required().forGetter(LastSearch::snapshotTruncated),
            LastSearch::new);

    public static final RecordType<NavigatorReading> NAVIGATOR_READING = ApiTypes.record(NavigatorReading.class, "NavigatorReading",
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(NavigatorReading::bot),
            ApiField.field("process", ApiTypes.string()).optional().forGetter(NavigatorReading::process),
            ApiField.field("running", ApiTypes.bool()).required().forGetter(NavigatorReading::running),
            ApiField.field("plan", PLAN_CURSOR).optional().forGetter(NavigatorReading::plan),
            ApiField.field("lastSearch", LAST_SEARCH).optional().forGetter(NavigatorReading::lastSearch),
            ApiField.field("counters", ApiTypes.map(ApiTypes.int64(Long.MIN_VALUE, Long.MAX_VALUE), 256))
                    .required().forGetter(NavigatorReading::counters),
            ApiField.field("flags", ApiTypes.map(ApiTypes.bool(), 64)).required().forGetter(NavigatorReading::flags),
            ApiField.field("traces", ApiTypes.map(ApiTypes.string(), 64)).required().forGetter(NavigatorReading::traces),
            NavigatorReading::new);

    public static final MethodSpec<NavigatorParams, NavigatorReading> NAVIGATOR = MethodSpec.of("mc.debug.navigator",
                    NAVIGATOR_PARAMS, NAVIGATOR_READING,
                    "A server-side bot's navigator as it stands: the plan cursor, the last search, and every tally, "
                            + "telemetry counter and trace by name. Read on the server thread.")
            .hidden();

    // ------------------------------------------------------------------ mc.debug.body

    /**
     * {@code reach}: also answer whether the bot could break that block from where it stands. {@code blastFrom}:
     * also answer whether a swing at that entity would be refused for the blast, and the stands that would not be.
     */
    public record BodyParams(String bot, Optional<BlockPosition> reach, Optional<Integer> blastFrom) {
        public static BodyParams of(String bot) { return new BodyParams(bot, Optional.empty(), Optional.empty()); }
    }

    /**
     * What the bot stands on as the navigator's guards see it: the solid area under the sole (of
     * {@code footingMin} needed), the row of cells under it, its foot cell and whether a lethal drop is beside it.
     */
    public record Footing(boolean onGround, double soleOnSolid, double footingMin, String soleRow, BlockPosition foot,
                          boolean lethalDropAdjacent) {}

    /** The hands: the dig held and on which cell, the placements so far, and what the planner may place. */
    public record Hands(boolean breakHeld, Optional<BlockPosition> lookingAt, String placeTally, int placeableBlocks,
                        boolean canPlace, Optional<String> lastAttackRefusal) {}

    /** Whether the block is breakable from here: a face open to air and its centre within reach of the eye. */
    public record Reach(BlockPosition pos, boolean canBreak, boolean exposedFace, double eyeToCentre, double reach) {}

    /** {@code refusal}: why a swing now would be refused for the blast, absent when it would not be. */
    public record Blast(int entityId, Optional<String> refusal, double need, List<BlockPosition> stands) {}

    /** {@code dimension}: of the bot's world view, which follows the bot across a portal one tick late. */
    public record BodyReading(String bot, String dimension, Footing footing, Hands hands, boolean logWaiver,
                              Optional<Reach> reach, Optional<Blast> blast) {}

    public static final RecordType<BodyParams> BODY_PARAMS = ApiTypes.record(BodyParams.class, "BodyParams",
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(BodyParams::bot),
            ApiField.field("reach", CommonTypes.BLOCK_POSITION).optional().forGetter(BodyParams::reach),
            ApiField.field("blastFrom", ApiTypes.integer()).describe("An entity id.").optional().forGetter(BodyParams::blastFrom),
            BodyParams::new);

    private static final RecordType<Footing> FOOTING = ApiTypes.record(Footing.class, "Footing",
            ApiField.field("onGround", ApiTypes.bool()).required().forGetter(Footing::onGround),
            ApiField.field("soleOnSolid", ApiTypes.number()).required().forGetter(Footing::soleOnSolid),
            ApiField.field("footingMin", ApiTypes.number()).required().forGetter(Footing::footingMin),
            ApiField.field("soleRow", ApiTypes.string()).required().forGetter(Footing::soleRow),
            ApiField.field("foot", CommonTypes.BLOCK_POSITION).required().forGetter(Footing::foot),
            ApiField.field("lethalDropAdjacent", ApiTypes.bool()).required().forGetter(Footing::lethalDropAdjacent),
            Footing::new);

    private static final RecordType<Hands> HANDS = ApiTypes.record(Hands.class, "Hands",
            ApiField.field("breakHeld", ApiTypes.bool()).required().forGetter(Hands::breakHeld),
            ApiField.field("lookingAt", CommonTypes.BLOCK_POSITION).optional().forGetter(Hands::lookingAt),
            ApiField.field("placeTally", ApiTypes.string()).required().forGetter(Hands::placeTally),
            ApiField.field("placeableBlocks", COUNT).required().forGetter(Hands::placeableBlocks),
            ApiField.field("canPlace", ApiTypes.bool()).required().forGetter(Hands::canPlace),
            ApiField.field("lastAttackRefusal", ApiTypes.string()).optional().forGetter(Hands::lastAttackRefusal),
            Hands::new);

    private static final RecordType<Reach> REACH = ApiTypes.record(Reach.class, "Reach",
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Reach::pos),
            ApiField.field("canBreak", ApiTypes.bool()).required().forGetter(Reach::canBreak),
            ApiField.field("exposedFace", ApiTypes.bool()).required().forGetter(Reach::exposedFace),
            ApiField.field("eyeToCentre", ApiTypes.number()).required().forGetter(Reach::eyeToCentre),
            ApiField.field("reach", ApiTypes.number()).required().forGetter(Reach::reach),
            Reach::new);

    private static final RecordType<Blast> BLAST = ApiTypes.record(Blast.class, "Blast",
            ApiField.field("entityId", ApiTypes.integer()).required().forGetter(Blast::entityId),
            ApiField.field("refusal", ApiTypes.string()).optional().forGetter(Blast::refusal),
            ApiField.field("need", ApiTypes.number()).required().forGetter(Blast::need),
            ApiField.field("stands", ApiTypes.list(CommonTypes.BLOCK_POSITION, 1024)).required().forGetter(Blast::stands),
            Blast::new);

    public static final RecordType<BodyReading> BODY_READING = ApiTypes.record(BodyReading.class, "BodyReading",
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(BodyReading::bot),
            ApiField.field("dimension", ApiTypes.string()).required().forGetter(BodyReading::dimension),
            ApiField.field("footing", FOOTING).required().forGetter(BodyReading::footing),
            ApiField.field("hands", HANDS).required().forGetter(BodyReading::hands),
            ApiField.field("logWaiver", ApiTypes.bool()).describe("The mine's log-cost waiver is held.")
                    .required().forGetter(BodyReading::logWaiver),
            ApiField.field("reach", REACH).optional().forGetter(BodyReading::reach),
            ApiField.field("blast", BLAST).optional().forGetter(BodyReading::blast),
            BodyReading::new);

    public static final MethodSpec<BodyParams, BodyReading> BODY = MethodSpec.of("mc.debug.body", BODY_PARAMS, BODY_READING,
                    "A server-side bot's body as the driver judges it: its footing, its hands, its world view's dimension, "
                            + "and on request whether a block is in reach or a swing is blast-safe. Read on the server thread.")
            .hidden();

    // ------------------------------------------------------------------ mc.debug.cells

    /**
     * {@code node}: also answer the pose and fall facts of standing at that cell. {@code side}, with {@code bot}
     * self only: whose view of self's player classifies the cells.
     */
    public record CellsParams(Optional<String> bot, Optional<Side> side, List<BlockPosition> cells,
                              Optional<NodeQuery> node) {
        /** {@code bot}'s own view. */
        public CellsParams(Optional<String> bot, List<BlockPosition> cells, Optional<NodeQuery> node) {
            this(bot, Optional.empty(), cells, node);
        }
    }

    /** self's two views: the client's, which self plans with, and the server's of the same player. */
    public enum Side implements WireName {
        CLIENT, SERVER;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** A node between {@code prev} and {@code next}, with the entering edge's digs and placements already done. */
    public record NodeQuery(BlockPosition foot, Optional<BlockPosition> prev, Optional<BlockPosition> next,
                            List<BlockPosition> toBreak, List<BlockPosition> toPlace) {}

    /**
     * One cell as the world view classifies it. {@code breakCost}: what digging it costs a search; absent when it
     * cannot be. {@code buildBlock}, {@code valuable} and {@code throwaway} are about the block standing there as
     * something to place: footing the bot may build with, a gathered resource it does not spend as filler, and an
     * item it spends as filler first. {@code suffocates}: a head in this cell suffocates, the test the
     * anti-suffocation reflex picks the block it breaks by.
     */
    public record Cell(BlockPosition pos, boolean known, boolean solid, boolean passable, boolean hazard, boolean water,
                       boolean climbable, boolean leaves, boolean fallingBlock, boolean breakableObstruction,
                       boolean standOn, boolean standAt, Optional<Double> breakCost, boolean buildBlock, boolean valuable,
                       boolean throwaway, boolean suffocates) {}

    public record NodeFacts(boolean fitStand, boolean fitCrouch, boolean fitCrawl, String ceilingForces,
                            boolean inWaterFoot, boolean submergedEye, boolean underfootSolid, String footHazard,
                            double fallFromPrev, boolean fallSurvivable, boolean jumpNeeded, boolean jumpFeasible) {}

    /** {@code view}: whose view classified the cells, the bot's or the server level's. */
    public record CellsReading(String view, String dimension, List<Cell> cells, Optional<NodeFacts> node) {}

    private static final RecordType<NodeQuery> NODE_QUERY = ApiTypes.record(NodeQuery.class, "NodeQuery",
            ApiField.field("foot", CommonTypes.BLOCK_POSITION).required().forGetter(NodeQuery::foot),
            ApiField.field("prev", CommonTypes.BLOCK_POSITION).optional().forGetter(NodeQuery::prev),
            ApiField.field("next", CommonTypes.BLOCK_POSITION).optional().forGetter(NodeQuery::next),
            ApiField.field("toBreak", ApiTypes.list(CommonTypes.BLOCK_POSITION, 64)).defaulted(List.of()).forGetter(NodeQuery::toBreak),
            ApiField.field("toPlace", ApiTypes.list(CommonTypes.BLOCK_POSITION, 64)).defaulted(List.of()).forGetter(NodeQuery::toPlace),
            NodeQuery::new);

    public static final RecordType<CellsParams> CELLS_PARAMS = ApiTypes.record(CellsParams.class, "CellsParams",
            ApiField.field("bot", Responses.BOT_REF).describe("Omitted: the overworld as the server level reads it.")
                    .optional().forGetter(CellsParams::bot),
            ApiField.field("side", ApiTypes.enumOf("CellSide", Side.class))
                    .describe("With bot self: client (omitted), the view self plans with; server, the view of the server "
                            + "a client here hosts, over the same player.")
                    .optional().forGetter(CellsParams::side),
            ApiField.field("cells", ApiTypes.list(CommonTypes.BLOCK_POSITION, 4096)).defaulted(List.of())
                    .forGetter(CellsParams::cells),
            ApiField.field("node", NODE_QUERY).optional().forGetter(CellsParams::node),
            CellsParams::new)
            .checked((p, at) -> {
                if (p.side().isPresent() && !p.bot().map("self"::equals).orElse(false)) {
                    at.field("side").fail(ApiIssue.Code.INVALID_VALUE, "a side is self's, and bot does not name self");
                }
            });

    private static final RecordType<Cell> CELL = ApiTypes.record(Cell.class, "DebugCell",
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Cell::pos),
            ApiField.field("known", ApiTypes.bool()).required().forGetter(Cell::known),
            ApiField.field("solid", ApiTypes.bool()).required().forGetter(Cell::solid),
            ApiField.field("passable", ApiTypes.bool()).required().forGetter(Cell::passable),
            ApiField.field("hazard", ApiTypes.bool()).required().forGetter(Cell::hazard),
            ApiField.field("water", ApiTypes.bool()).required().forGetter(Cell::water),
            ApiField.field("climbable", ApiTypes.bool()).required().forGetter(Cell::climbable),
            ApiField.field("leaves", ApiTypes.bool()).required().forGetter(Cell::leaves),
            ApiField.field("fallingBlock", ApiTypes.bool()).required().forGetter(Cell::fallingBlock),
            ApiField.field("breakableObstruction", ApiTypes.bool()).required().forGetter(Cell::breakableObstruction),
            ApiField.field("standOn", ApiTypes.bool()).required().forGetter(Cell::standOn),
            ApiField.field("standAt", ApiTypes.bool()).required().forGetter(Cell::standAt),
            ApiField.field("breakCost", ApiTypes.number()).optional().forGetter(Cell::breakCost),
            ApiField.field("buildBlock", ApiTypes.bool()).describe("The block here may be placed as footing.")
                    .required().forGetter(Cell::buildBlock),
            ApiField.field("valuable", ApiTypes.bool()).describe("The block here is a gathered resource, never spent as filler.")
                    .required().forGetter(Cell::valuable),
            ApiField.field("throwaway", ApiTypes.bool()).describe("The block's item is filler the bot spends first.")
                    .required().forGetter(Cell::throwaway),
            ApiField.field("suffocates", ApiTypes.bool()).describe("A head in this cell suffocates.")
                    .required().forGetter(Cell::suffocates),
            Cell::new);

    private static final RecordType<NodeFacts> NODE_FACTS = ApiTypes.record(NodeFacts.class, "NodeFacts",
            ApiField.field("fitStand", ApiTypes.bool()).required().forGetter(NodeFacts::fitStand),
            ApiField.field("fitCrouch", ApiTypes.bool()).required().forGetter(NodeFacts::fitCrouch),
            ApiField.field("fitCrawl", ApiTypes.bool()).required().forGetter(NodeFacts::fitCrawl),
            ApiField.field("ceilingForces", ApiTypes.string()).required().forGetter(NodeFacts::ceilingForces),
            ApiField.field("inWaterFoot", ApiTypes.bool()).required().forGetter(NodeFacts::inWaterFoot),
            ApiField.field("submergedEye", ApiTypes.bool()).required().forGetter(NodeFacts::submergedEye),
            ApiField.field("underfootSolid", ApiTypes.bool()).required().forGetter(NodeFacts::underfootSolid),
            ApiField.field("footHazard", ApiTypes.string()).required().forGetter(NodeFacts::footHazard),
            ApiField.field("fallFromPrev", ApiTypes.number()).required().forGetter(NodeFacts::fallFromPrev),
            ApiField.field("fallSurvivable", ApiTypes.bool()).required().forGetter(NodeFacts::fallSurvivable),
            ApiField.field("jumpNeeded", ApiTypes.bool()).required().forGetter(NodeFacts::jumpNeeded),
            ApiField.field("jumpFeasible", ApiTypes.bool()).required().forGetter(NodeFacts::jumpFeasible),
            NodeFacts::new);

    public static final RecordType<CellsReading> CELLS_READING = ApiTypes.record(CellsReading.class, "CellsReading",
            ApiField.field("view", ApiTypes.string()).required().forGetter(CellsReading::view),
            ApiField.field("dimension", ApiTypes.string()).required().forGetter(CellsReading::dimension),
            ApiField.field("cells", ApiTypes.list(CELL, 4096)).required().forGetter(CellsReading::cells),
            ApiField.field("node", NODE_FACTS).optional().forGetter(CellsReading::node),
            CellsReading::new);

    public static final MethodSpec<CellsParams, CellsReading> CELLS = MethodSpec.of("mc.debug.cells", CELLS_PARAMS, CELLS_READING,
                    "Cells as the planner's world view classifies them, a bot's or the server level's, and on request "
                            + "the pose and fall facts of standing at one. Read on the server thread; self's client view "
                            + "on the client thread.")
            .hidden();
}
