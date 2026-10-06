package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionResponse;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.RouteContract.Route;

/**
 * {@code mc.bot.goto}: walk (or fly) the body to a target under a route's conditions, preview that walk
 * without taking it, walk a previewed route by its {@code planId}, or price a line the caller drew.
 * Every variant but the last is an action; {@code plan: 'score'} answers at once with {@code scored}.
 */
public final class GotoContract {
    private GotoContract() {}

    public static final String METHOD = "mc.bot.goto";

    /** How a positional target is satisfied; ignored when {@code near} is above 0. */
    public enum GoalMode implements WireName {
        /** Stand on the cell. */
        IN,
        /** Stand inside it, at foot or eye level. */
        TWO,
        /** Stand beside, above or below it: a chest, a furnace. */
        ADJACENT;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** forward/back/left/right turn with the body's facing; the same words clearArea and construct take. */
    public enum Direction implements WireName {
        NORTH, SOUTH, EAST, WEST, UP, DOWN, FORWARD, BACK, LEFT, RIGHT;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
        public boolean vertical() { return this == UP || this == DOWN; }
    }

    public enum PlanMode implements WireName {
        /** Search the route without walking it or interrupting a walk; the action's result describes it. */
        PREVIEW,
        /** Price route.corridor.points as if walked, without a search; answered at once. */
        SCORE;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public sealed interface Target permits Position, Column, Level, BlockTarget, EntityType, EntityTarget,
            DirectionTarget, Heading, Waypoint, Axis {}

    public record Position(BlockPosition pos, int near, GoalMode goalMode) implements Target {}
    public record Column(int x, int z, int near) implements Target {}
    public record Level(int y) implements Target {}
    /** The nearest cell of {@code block} (an id or a {@code #tag}) within {@code radius} that can be stood beside. */
    public record BlockTarget(String block, int radius) implements Target {}
    /** The nearest entity of {@code type} the body can see. */
    public record EntityType(String type, int near, GoalMode goalMode) implements Target {}
    public record EntityTarget(int entityId, int near, GoalMode goalMode) implements Target {}
    public record DirectionTarget(Direction direction, int distance, int near) implements Target {}
    /** Keep going one horizontal way, with no endpoint. */
    public record Heading(Direction direction) implements Target {}
    /** A waypoint saved through {@code mc.bot.waypoint}; {@code self} only. */
    public record Waypoint(String name, int near, GoalMode goalMode) implements Target {}
    /** The nearest world axis or diagonal at the axisHeight setting's Y. */
    public record Axis() implements Target {}

    public record Params(String body, Optional<Target> target, boolean invert, Optional<Route> route,
                         Optional<PlanMode> plan, boolean includePath, Optional<String> planId, int awaitMs) {}

    /** How the trip is made. */
    public enum Travel implements WireName {
        WALK("walk"),
        FLY("fly"),
        /** Asked to fly with no usable elytra on. */
        WALK_WITHOUT_ELYTRA("walkWithoutElytra");

        private final String wire;
        Travel(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    /** Whether the walk took the previewed route, and why not when it searched again. */
    public record Adoption(String planId, boolean adopted, Optional<String> reason) {}

    public sealed interface Result permits Trip, Preview {}

    /** A walk or flight that ended. {@code goal} is the planner's description of the resolved target. */
    public record Trip(String goal, int via, Travel travel, Optional<Adoption> plan) implements Result {}

    public record Exposure(int id, String type, int cells) {}

    public record SegmentRisk(List<Exposure> exposedTo, Optional<Double> nearestMob, List<String> regions) {}

    /** Cells {@code from} (inclusive) to {@code to} (exclusive) of the route, over which the risk is the same. */
    public record Segment(Optional<Integer> leg, int from, int to, SegmentRisk risk) {}

    /** A preview: the risk in segments rather than the cells; {@code path} only when includePath asked. */
    public record Preview(String planId, boolean reached, int cells, double cost, int legs, List<Segment> segments,
                          double detourRatio, boolean bestEffort, boolean sightBudgetExhausted, boolean snapshotTruncated,
                          int expanded, double ms, Optional<List<BlockPosition>> path) implements Result {}

    /** {@code plan: 'score'}'s answer. */
    public record Scored(int cells, List<Segment> segments, double detourRatio, boolean sightBudgetExhausted,
                         boolean snapshotTruncated) implements ActionResponse<Result> {}

    private static final ApiType<GoalMode> GOAL_MODE = ApiTypes.enumOf("GoalMode", GoalMode.class);
    private static final ApiType<Direction> DIRECTION = ApiTypes.enumOf("GotoDirection", Direction.class);
    private static final ApiType<Integer> NEAR = ApiTypes.integer(0, 64)
            .describe("Accept any cell within this Euclidean radius; 0 is exact.");
    private static final ApiType<Integer> ENTITY_NEAR = ApiTypes.integer(0, 16)
            .describe("Accept any cell within this Euclidean radius; 0 is exact.");

    public static final UnionType<Target> TARGET = ApiTypes.taggedUnion("GotoTarget", "kind",
            ApiTypes.variant("position", Position.class, ApiTypes.record(null,
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Position::pos),
                    ApiField.field("near", NEAR).defaulted(0).forGetter(Position::near),
                    ApiField.field("goalMode", GOAL_MODE).defaulted(GoalMode.IN).forGetter(Position::goalMode),
                    Position::new)),
            ApiTypes.variant("column", Column.class, ApiTypes.record(null,
                    ApiField.field("x", ApiTypes.integer()).required().forGetter(Column::x),
                    ApiField.field("z", ApiTypes.integer()).required().forGetter(Column::z),
                    ApiField.field("near", NEAR).defaulted(0).forGetter(Column::near),
                    Column::new)),
            ApiTypes.variant("level", Level.class, ApiTypes.record(null,
                    ApiField.field("y", ApiTypes.integer(-4096, 4096))
                            .describe("Alone it drowns in sideways branches: add a hard column leash to dig straight.")
                            .required().forGetter(Level::y),
                    Level::new)),
            ApiTypes.variant("block", BlockTarget.class, ApiTypes.record(null,
                    ApiField.field("block", CommonTypes.BLOCK_OR_TAG).required().forGetter(BlockTarget::block),
                    ApiField.field("radius", ApiTypes.integer(1, 64)).defaulted(32).forGetter(BlockTarget::radius),
                    BlockTarget::new)),
            ApiTypes.variant("entityType", EntityType.class, ApiTypes.record(null,
                    ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(EntityType::type),
                    ApiField.field("near", ENTITY_NEAR).defaulted(3).forGetter(EntityType::near),
                    ApiField.field("goalMode", GOAL_MODE).defaulted(GoalMode.IN).forGetter(EntityType::goalMode),
                    EntityType::new)),
            ApiTypes.variant("entity", EntityTarget.class, ApiTypes.record(null,
                    ApiField.field("entityId", ApiTypes.integer(0, Integer.MAX_VALUE))
                            .describe("The id mc.query entities reports.").required().forGetter(EntityTarget::entityId),
                    ApiField.field("near", ENTITY_NEAR).defaulted(3).forGetter(EntityTarget::near),
                    ApiField.field("goalMode", GOAL_MODE).defaulted(GoalMode.IN).forGetter(EntityTarget::goalMode),
                    EntityTarget::new)),
            ApiTypes.variant("direction", DirectionTarget.class, ApiTypes.record(null,
                    ApiField.field("direction", DIRECTION).required().forGetter(DirectionTarget::direction),
                    ApiField.field("distance", ApiTypes.integer(1, 256)).defaulted(8).forGetter(DirectionTarget::distance),
                    ApiField.field("near", NEAR).defaulted(0).forGetter(DirectionTarget::near),
                    DirectionTarget::new)),
            ApiTypes.variant("heading", Heading.class, ApiTypes.record(null,
                    ApiField.field("direction", DIRECTION).describe("Horizontal only.").required().forGetter(Heading::direction),
                    Heading::new)
                    .checked((h, at) -> {
                        if (h.direction().vertical()) {
                            at.field("direction").fail(ApiIssue.Code.INVALID_VALUE, "a heading is horizontal; use a level target to go up or down");
                        }
                    })),
            ApiTypes.variant("waypoint", Waypoint.class, ApiTypes.record(null,
                    ApiField.field("name", ApiTypes.string(1, 64)).required().forGetter(Waypoint::name),
                    ApiField.field("near", NEAR).defaulted(0).forGetter(Waypoint::near),
                    ApiField.field("goalMode", GOAL_MODE).defaulted(GoalMode.IN).forGetter(Waypoint::goalMode),
                    Waypoint::new)),
            ApiTypes.variant("axis", Axis.class, ApiTypes.record(null, Axis::new)));

    public static final ApiType<PlanMode> PLAN_MODE = ApiTypes.enumOf("PlanMode", PlanMode.class);

    public static final RecordType<Params> PARAMS = ApiTypes.record("GotoParams",
            ApiField.field("body", ActionContract.BODY_REF).defaulted("self").forGetter(Params::body),
            ApiField.field("target", TARGET).describe("Required unless planId or plan: 'score'.")
                    .optional().forGetter(Params::target),
            ApiField.field("invert", ApiTypes.bool()).describe("Flee the target instead of reaching it.")
                    .defaulted(false).forGetter(Params::invert),
            ApiField.field("route", RouteContract.ROUTE).describe("Omitted: plain walking.").optional().forGetter(Params::route),
            ApiField.field("plan", PLAN_MODE).describe("preview searches without walking; score prices route.corridor.points. "
                    + "self only.").optional().forGetter(Params::plan),
            ApiField.field("includePath", ApiTypes.bool()).describe("With plan: 'preview', also return the route's cells.")
                    .defaulted(false).forGetter(Params::includePath),
            ApiField.field("planId", ApiTypes.string(1, 64))
                    .describe("Walk a preview's route; its target and route come from the preview. self only.")
                    .optional().forGetter(Params::planId),
            ApiField.field("awaitMs", ActionContract.AWAIT_MS).defaulted(0).forGetter(Params::awaitMs),
            Params::new)
            .checked(GotoContract::check);

    /** The combinations no single field can refuse. */
    private static void check(Params p, net.magicterra.worlddriver.api.contract.DecodeContext at) {
        if (p.planId().isPresent()) {
            String why = "planId walks the preview's own target and route";
            if (p.target().isPresent()) at.field("target").fail(ApiIssue.Code.INVALID_VALUE, why);
            if (p.route().isPresent()) at.field("route").fail(ApiIssue.Code.INVALID_VALUE, why);
            if (p.plan().isPresent()) at.field("plan").fail(ApiIssue.Code.INVALID_VALUE, why);
            if (p.invert()) at.field("invert").fail(ApiIssue.Code.INVALID_VALUE, why);
            if (p.includePath()) at.field("includePath").fail(ApiIssue.Code.INVALID_VALUE, why);
            return;
        }
        if (p.plan().orElse(null) == PlanMode.SCORE) {
            if (p.target().isPresent()) at.field("target").fail(ApiIssue.Code.INVALID_VALUE, "score prices route.corridor.points and takes no target");
            if (p.invert()) at.field("invert").fail(ApiIssue.Code.INVALID_VALUE, "score takes no target to invert");
            if (p.includePath()) at.field("includePath").fail(ApiIssue.Code.INVALID_VALUE, "score has no searched path");
            if (p.awaitMs() > 0) at.field("awaitMs").fail(ApiIssue.Code.INVALID_VALUE, "score answers at once and starts nothing to wait for");
            if (p.route().flatMap(Route::corridor).isEmpty()) {
                at.field("route").field("corridor").fail(ApiIssue.Code.REQUIRED, "score prices the corridor's points");
            }
            return;
        }
        if (p.target().isEmpty()) {
            at.field("target").fail(ApiIssue.Code.REQUIRED, "missing; expected " + TARGET.expected());
            return;
        }
        boolean preview = p.plan().isPresent();
        if (p.includePath() && !preview) at.field("includePath").fail(ApiIssue.Code.INVALID_VALUE, "only a preview returns a path");
        if (p.route().map(Route::fly).orElse(false)) {
            if (preview) at.field("plan").fail(ApiIssue.Code.INVALID_VALUE, "a preview searches the ground planner; flight has none");
            if (p.invert() || !hasCell(p.target().get())) {
                at.field("route").field("mode").fail(ApiIssue.Code.INVALID_VALUE,
                        "flying needs a target cell: position, entityType, entity, waypoint, block, or a direction with near above 0");
            }
        }
    }

    /** Whether the target resolves to one cell, which a flight needs. */
    private static boolean hasCell(Target t) {
        return switch (t) {
            case Position x -> true;
            case EntityType x -> true;
            case EntityTarget x -> true;
            case Waypoint x -> true;
            case BlockTarget x -> true;
            case DirectionTarget d -> d.near() > 0;
            case Column x -> false;
            case Level x -> false;
            case Heading x -> false;
            case Axis x -> false;
        };
    }

    public static final RecordType<Adoption> ADOPTION = ApiTypes.record("PlanAdoption",
            ApiField.field("planId", ApiTypes.string(1, 64)).required().forGetter(Adoption::planId),
            ApiField.field("adopted", ApiTypes.bool()).describe("false: the walk searched again instead.")
                    .required().forGetter(Adoption::adopted),
            ApiField.field("reason", ApiTypes.string()).describe("Why the preview's route was not taken.")
                    .optional().forGetter(Adoption::reason),
            Adoption::new);

    public static final RecordType<Segment> SEGMENT = ApiTypes.record("RiskSegment",
            ApiField.field("leg", ApiTypes.integer(0, 64)).describe("Which goal's route, when there were via points.")
                    .optional().forGetter(Segment::leg),
            ApiField.field("from", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Segment::from),
            ApiField.field("to", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Segment::to),
            ApiField.field("risk", ApiTypes.record("SegmentRisk",
                    ApiField.field("exposedTo", ApiTypes.list(ApiTypes.record("Exposure",
                            ApiField.field("id", ApiTypes.integer()).required().forGetter(Exposure::id),
                            ApiField.field("type", ApiTypes.string()).required().forGetter(Exposure::type),
                            ApiField.field("cells", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Exposure::cells),
                            Exposure::new), Integer.MAX_VALUE)).required().forGetter(SegmentRisk::exposedTo),
                    ApiField.field("nearestMob", ApiTypes.number()).optional().forGetter(SegmentRisk::nearestMob),
                    ApiField.field("regions", ApiTypes.list(ApiTypes.string(), Integer.MAX_VALUE)).required().forGetter(SegmentRisk::regions),
                    SegmentRisk::new)).required().forGetter(Segment::risk),
            Segment::new);

    private static final ApiType<List<Segment>> SEGMENTS = ApiTypes.list(SEGMENT, Integer.MAX_VALUE);
    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

    public static final UnionType<Result> RESULT = ApiTypes.taggedUnion("GotoResult", "kind",
            ApiTypes.variant("goto", Trip.class, ApiTypes.record(null,
                    ApiField.field("goal", ApiTypes.string()).required().forGetter(Trip::goal),
                    ApiField.field("via", COUNT).required().forGetter(Trip::via),
                    ApiField.field("travel", ApiTypes.enumOf("Travel", Travel.class)).required().forGetter(Trip::travel),
                    ApiField.field("plan", ADOPTION).describe("Present when the walk was started by planId.")
                            .optional().forGetter(Trip::plan),
                    Trip::new)),
            ApiTypes.variant("routePreview", Preview.class, ApiTypes.record(null,
                    ApiField.field("planId", ApiTypes.string(1, 64)).describe("Pass to goto within 60 s to walk this route.")
                            .required().forGetter(Preview::planId),
                    ApiField.field("reached", ApiTypes.bool()).required().forGetter(Preview::reached),
                    ApiField.field("cells", COUNT).required().forGetter(Preview::cells),
                    ApiField.field("cost", ApiTypes.number()).required().forGetter(Preview::cost),
                    ApiField.field("legs", COUNT).required().forGetter(Preview::legs),
                    ApiField.field("segments", SEGMENTS).required().forGetter(Preview::segments),
                    ApiField.field("detourRatio", ApiTypes.number()).required().forGetter(Preview::detourRatio),
                    ApiField.field("bestEffort", ApiTypes.bool()).required().forGetter(Preview::bestEffort),
                    ApiField.field("sightBudgetExhausted", ApiTypes.bool()).required().forGetter(Preview::sightBudgetExhausted),
                    ApiField.field("snapshotTruncated", ApiTypes.bool()).required().forGetter(Preview::snapshotTruncated),
                    ApiField.field("expanded", COUNT).required().forGetter(Preview::expanded),
                    ApiField.field("ms", ApiTypes.number()).required().forGetter(Preview::ms),
                    ApiField.field("path", ApiTypes.list(CommonTypes.BLOCK_POSITION, Integer.MAX_VALUE))
                            .optional().forGetter(Preview::path),
                    Preview::new)));

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final ApiType<ActionResponse<Result>> RESPONSE = ActionContract.response("Goto", RESULT,
            (UnionType.Variant) ApiTypes.variant("scored", Scored.class, ApiTypes.record(null,
                    ApiField.field("cells", COUNT).required().forGetter(Scored::cells),
                    ApiField.field("segments", SEGMENTS).required().forGetter(Scored::segments),
                    ApiField.field("detourRatio", ApiTypes.number()).required().forGetter(Scored::detourRatio),
                    ApiField.field("sightBudgetExhausted", ApiTypes.bool()).required().forGetter(Scored::sightBudgetExhausted),
                    ApiField.field("snapshotTruncated", ApiTypes.bool()).required().forGetter(Scored::snapshotTruncated),
                    Scored::new)));

    public static final MethodSpec<Params, ActionResponse<Result>> SPEC = MethodSpec.of(METHOD, PARAMS, RESPONSE,
                    "Walk the body to a target under the route's conditions, replacing whatever it was doing. "
                            + "plan: 'preview' searches the route without walking and without interrupting a walk; "
                            + "its result is the route's risk in segments and a planId that a later goto walks "
                            + "exactly. plan: 'score' prices route.corridor.points at once, with no search. "
                            + "route.mode ['fly'] hands the trip to the elytra, walking if none is worn; for long "
                            + "airborne trips prefer it to walking. An underwater target needs route.mode ['dive'], "
                            + "without which water is an obstacle.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();
}
