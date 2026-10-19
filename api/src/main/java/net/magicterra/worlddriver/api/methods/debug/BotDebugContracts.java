package net.magicterra.worlddriver.api.methods.debug;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/**
 * What a server-side bot's insides read right now: {@code mc.debug.navigator}, {@code mc.debug.body} and
 * {@code mc.debug.cells}. For a program checking the driver itself, a test above all; an agent steers on
 * {@code mc.bot.status}, which is why these are hidden. The counter and trace names are the driver's own and
 * change with it.
 */
public final class BotDebugContracts {
    private BotDebugContracts() {}

    // ------------------------------------------------------------------ mc.debug.navigator

    /** {@code newLeg} starts a new leg on the bot's telemetry before it is read, as a fresh walk would. */
    @Named("NavigatorParams")
    public record NavigatorParams(
            @BotRef String bot,
            @Doc("Start a new leg on the telemetry before reading it.") @Default("false") boolean newLeg) {
        public static NavigatorParams of(String bot) { return new NavigatorParams(bot, false); }
    }

    /** Where the plan steers now: the node at {@code step}, the move entering it, and which drive branch ran. */
    @Named("PlanCursor")
    public record PlanCursor(Optional<BlockPosition> node, Optional<String> move, int step, int len,
                             Optional<String> parkourTakeoff, Optional<String> driveTag, Optional<String> jumpTag,
                             Optional<String> aimTag) {}

    /** The last deep search any navigator on this body finished. */
    @Named("LastSearch")
    public record LastSearch(
            @Range(min = 0) int expanded,
            @Range(min = 0) long ms,
            boolean goalReached,
            double finalCost,
            @Range(min = 0) int pathLen,
            boolean sightBudgetExhausted,
            boolean snapshotTruncated) {}

    /**
     * {@code process}: the kind of the task whose navigator answered, the running one or else the last a caller
     * started; {@code running} whether it still runs. {@code plan} and the navigator's own counters are absent
     * when no such task owns a navigator. {@code counters}, {@code flags} and {@code traces} hold the
     * navigator's tallies, the body's telemetry and the driver-wide counters, by name.
     */
    @Named("NavigatorReading")
    public record NavigatorReading(
            @BotRef String bot,
            Optional<String> process,
            boolean running,
            Optional<PlanCursor> plan,
            Optional<LastSearch> lastSearch,
            @Length(max = 256) Map<String, Long> counters,
            @Length(max = 64) Map<String, Boolean> flags,
            @Length(max = 64) Map<String, String> traces) {
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

    public static final RecordType<NavigatorParams> NAVIGATOR_PARAMS = ApiTypes.record(NavigatorParams.class);

    public static final RecordType<NavigatorReading> NAVIGATOR_READING = ApiTypes.record(NavigatorReading.class);

    public static final MethodSpec<NavigatorParams, NavigatorReading> NAVIGATOR = MethodSpec.of("mc.debug.navigator",
                    NAVIGATOR_PARAMS, NAVIGATOR_READING,
                    "A server-side bot's navigator as it stands: the plan cursor, the last search, and every tally, "
                            + "telemetry counter and trace by name. Read on the server thread.")
            .hidden();

    // ------------------------------------------------------------------ mc.debug.body

    /**
     * {@code reach}: also answer whether the bot could break that block from where it stands. {@code blastFrom}:
     * also answer whether a swing at that entity would be refused for the blast, and the stands that would not be.
     * {@code rise}: also answer which cells stop the bot's own box from rising that many blocks.
     */
    @Named("BodyParams")
    public record BodyParams(
            @BotRef String bot,
            Optional<BlockPosition> reach,
            @Doc("An entity id.") Optional<Integer> blastFrom,
            @Doc("Blocks to sweep the bot's box up by.") Optional<@Range(min = 0, max = 16) Double> rise) {
        public BodyParams(String bot, Optional<BlockPosition> reach, Optional<Integer> blastFrom) {
            this(bot, reach, blastFrom, Optional.empty());
        }
        public static BodyParams of(String bot) { return new BodyParams(bot, Optional.empty(), Optional.empty()); }
        public static BodyParams rise(String bot, double blocks) {
            return new BodyParams(bot, Optional.empty(), Optional.empty(), Optional.of(blocks));
        }
    }

    /**
     * What the bot stands on as the navigator's guards see it: the solid area under the sole (of
     * {@code footingMin} needed), the row of cells under it, its foot cell and whether a lethal drop is beside it.
     */
    @Named("Footing")
    public record Footing(boolean onGround, double soleOnSolid, double footingMin, String soleRow, BlockPosition foot,
                          boolean lethalDropAdjacent) {}

    /** The hands: the dig held and on which cell, the placements so far, and what the planner may place. */
    @Named("Hands")
    public record Hands(
            boolean breakHeld,
            Optional<BlockPosition> lookingAt,
            String placeTally,
            @Range(min = 0) int placeableBlocks,
            boolean canPlace,
            Optional<String> lastAttackRefusal) {}

    /** Whether the block is breakable from here: a face open to air and its centre within reach of the eye. */
    @Named("Reach")
    public record Reach(BlockPosition pos, boolean canBreak, boolean exposedFace, double eyeToCentre, double reach) {}

    /**
     * {@code refusal}: why a swing now would be refused for the blast, absent when it would not be. {@code stands}:
     * the cells within {@code surveyRadius} of the foot whose footing withstands it.
     */
    @Named("Blast")
    public record Blast(
            int entityId,
            Optional<String> refusal,
            double need,
            @Length(max = 1024) List<BlockPosition> stands,
            @Range(min = 0) int surveyRadius) {}

    /**
     * {@code dimension}: of the bot's world view, which follows the bot across a portal one tick late.
     * {@code riseBlockers}: the cells in the way of {@code rise}, lowest first, empty when the rise is clear; an
     * entity that blocks has no cell, so an empty list is also what a rise blocked only by an entity answers.
     */
    @Named("BodyReading")
    public record BodyReading(
            @BotRef String bot,
            String dimension,
            Footing footing,
            Hands hands,
            @Doc("The mine's log-cost waiver is held.") boolean logWaiver,
            Optional<Reach> reach,
            Optional<Blast> blast,
            Optional<@Length(max = 64) List<BlockPosition>> riseBlockers) {}

    public static final RecordType<BodyParams> BODY_PARAMS = ApiTypes.record(BodyParams.class);

    public static final RecordType<BodyReading> BODY_READING = ApiTypes.record(BodyReading.class);

    public static final MethodSpec<BodyParams, BodyReading> BODY = MethodSpec.of("mc.debug.body", BODY_PARAMS, BODY_READING,
                    "A bot's body as the driver judges it: its footing, its hands, its world view's dimension, and on "
                            + "request whether a block is in reach, a swing is blast-safe or its box can rise. A "
                            + "server-side bot's on the server thread; self's on the client thread, from its own body.")
            .hidden();

    // ------------------------------------------------------------------ mc.debug.cells

    /**
     * {@code node}: also answer the pose and fall facts of standing at that cell. {@code side}, with {@code bot}
     * self only: whose view of self's player classifies the cells.
     */
    @Named("CellsParams")
    public record CellsParams(
            @Doc("Omitted: the overworld as the server level reads it.") Optional<@BotRef String> bot,
            @Doc("With bot self: client (omitted), the view self plans with; server, the view of the server a client "
                    + "here hosts, over the same player.")
            Optional<Side> side,
            @Default("[]") @Length(max = 4096) List<BlockPosition> cells,
            Optional<NodeQuery> node) implements SelfChecked {
        /** {@code bot}'s own view. */
        public CellsParams(Optional<String> bot, List<BlockPosition> cells, Optional<NodeQuery> node) {
            this(bot, Optional.empty(), cells, node);
        }

        @Override public void check(DecodeContext at) {
            if (side.isPresent() && !bot.map("self"::equals).orElse(false)) {
                at.field("side").fail(ApiIssue.Code.INVALID_VALUE, "a side is self's, and bot does not name self");
            }
        }
    }

    /** self's two views: the client's, which self plans with, and the server's of the same player. */
    @Named("CellSide")
    public enum Side implements WireName {
        CLIENT, SERVER;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** A node between {@code prev} and {@code next}, with the entering edge's digs and placements already done. */
    @Named("NodeQuery")
    public record NodeQuery(
            BlockPosition foot,
            Optional<BlockPosition> prev,
            Optional<BlockPosition> next,
            @Default("[]") @Length(max = 64) List<BlockPosition> toBreak,
            @Default("[]") @Length(max = 64) List<BlockPosition> toPlace) {}

    /**
     * One cell as the world view classifies it. {@code breakCost}: what digging it costs a search; absent when it
     * cannot be. {@code buildBlock}, {@code valuable} and {@code throwaway} are about the block standing there as
     * something to place: footing the bot may build with, a gathered resource it does not spend as filler, and an
     * item it spends as filler first. {@code suffocates}: a head in this cell suffocates, the test the
     * anti-suffocation reflex picks the block it breaks by.
     */
    @Named("DebugCell")
    public record Cell(
            BlockPosition pos,
            boolean known,
            boolean solid,
            boolean passable,
            boolean hazard,
            boolean water,
            boolean climbable,
            boolean leaves,
            boolean fallingBlock,
            boolean breakableObstruction,
            boolean standOn,
            boolean standAt,
            Optional<Double> breakCost,
            @Doc("The block here may be placed as footing.") boolean buildBlock,
            @Doc("The block here is a gathered resource, never spent as filler.") boolean valuable,
            @Doc("The block's item is filler the bot spends first.") boolean throwaway,
            @Doc("A head in this cell suffocates.") boolean suffocates) {}

    @Named("NodeFacts")
    public record NodeFacts(boolean fitStand, boolean fitCrouch, boolean fitCrawl, String ceilingForces,
                            boolean inWaterFoot, boolean submergedEye, boolean underfootSolid, String footHazard,
                            double fallFromPrev, boolean fallSurvivable, boolean jumpNeeded, boolean jumpFeasible) {}

    /** {@code view}: whose view classified the cells, the bot's or the server level's. */
    @Named("CellsReading")
    public record CellsReading(
            String view,
            String dimension,
            @Length(max = 4096) List<Cell> cells,
            Optional<NodeFacts> node) {}

    public static final RecordType<CellsParams> CELLS_PARAMS = ApiTypes.record(CellsParams.class);

    public static final RecordType<CellsReading> CELLS_READING = ApiTypes.record(CellsReading.class);

    public static final MethodSpec<CellsParams, CellsReading> CELLS = MethodSpec.of("mc.debug.cells", CELLS_PARAMS, CELLS_READING,
                    "Cells as the planner's world view classifies them, a bot's or the server level's, and on request "
                            + "the pose and fall facts of standing at one. Read on the server thread; self's client view "
                            + "on the client thread.")
            .hidden();
}
