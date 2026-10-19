package net.magicterra.worlddriver.api.methods.bot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockOrTag;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Direction;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.methods.shared.Responses.Scored;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * {@code mc.bot.goto}: walk (or fly) the body to a target under a route's conditions, preview that walk
 * without taking it, walk a previewed route by its {@code planId}, or price a line the caller drew.
 * Every variant but the last is an action; {@code plan: 'score'} answers at once with {@code scored}.
 */
public final class GotoContracts {
    private GotoContracts() {}

    public static final String METHOD = "mc.bot.goto";

    /** How a positional target is satisfied; ignored when {@code near} is above 0. */
    @Named("GoalMode")
    public enum GoalMode implements WireName {
        /** Stand on the cell. */
        IN,
        /** Stand inside it, at foot or eye level. */
        TWO,
        /** Stand beside, above or below it: a chest, a furnace. */
        ADJACENT;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("PlanMode")
    public enum PlanMode implements WireName {
        /** Search the route without walking it or interrupting a walk; the action's result describes it. */
        PREVIEW,
        /** Price route.corridor.points as if walked, without a search; answered at once. */
        SCORE;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** How close counts as there, for a target that is a cell. */
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.TYPE_USE)
    @Range(min = 0, max = 64)
    @Doc("Accept any cell within this Euclidean radius; 0 is exact.")
    public @interface Near {}

    /** How close counts as there, for a target that is an entity. */
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.TYPE_USE)
    @Range(min = 0, max = 16)
    @Doc("Accept any cell within this Euclidean radius; 0 is exact.")
    public @interface EntityNear {}

    @Tag("kind") @Named("GotoTarget")
    public sealed interface Target permits Position, Column, Level, BlockTarget, EntityType, EntityTarget,
            DirectionTarget, Heading, Waypoint, Axis {}

    @Variant("position")
    public record Position(
            BlockPosition pos,
            @Default("0") @Near int near,
            @Default("in") GoalMode goalMode) implements Target {}
    @Variant("column")
    public record Column(int x, int z, @Default("0") @Near int near) implements Target {}
    @Variant("level")
    public record Level(
            @Doc("Alone it drowns in sideways branches: add a hard column leash to dig straight.")
            @Range(min = -4096, max = 4096) int y) implements Target {}
    /** The nearest cell of {@code block} (an id or a {@code #tag}) within {@code radius} that can be stood beside. */
    @Variant("block")
    public record BlockTarget(
            @BlockOrTag String block,
            @Default("32") @Range(min = 1, max = 64) int radius) implements Target {}
    /** The nearest entity of {@code type} the body can see. */
    @Variant("entityType")
    public record EntityType(
            @ResourceId String type,
            @Default("3") @EntityNear int near,
            @Default("in") GoalMode goalMode) implements Target {}
    @Variant("entity")
    public record EntityTarget(
            @Doc("The id mc.query entities reports.") @Range(min = 0) int entityId,
            @Default("3") @EntityNear int near,
            @Default("in") GoalMode goalMode) implements Target {}
    @Variant("direction")
    public record DirectionTarget(
            @Doc("A turning word follows the body's exact heading, diagonals included.") Direction direction,
            @Default("8") @Range(min = 1, max = 256) int distance,
            @Default("0") @Near int near) implements Target {}
    /** Keep going one horizontal way, with no endpoint. */
    @Variant("heading")
    public record Heading(
            @Doc("Horizontal only. A turning word is snapped to the nearest compass direction.") Direction direction)
            implements Target, SelfChecked {
        @Override public void check(DecodeContext at) {
            if (direction.vertical()) {
                at.field("direction").fail(ApiIssue.Code.INVALID_VALUE, "a heading is horizontal; use a level target to go up or down");
            }
        }
    }
    /** A waypoint saved through {@code mc.bot.waypoint}; {@code self} only. */
    @Variant("waypoint")
    public record Waypoint(
            @Length(min = 1, max = 64) String name,
            @Default("0") @Near int near,
            @Default("in") GoalMode goalMode) implements Target {}
    /** The nearest world axis or diagonal at the axisHeight setting's Y. */
    @Variant("axis")
    public record Axis() implements Target {}

    @Named("GotoParams")
    public record Params(
            @Default("self") @BotRef String bot,
            @Doc("Required unless planId or plan: 'score'.") Optional<Target> target,
            @Doc("Flee the target instead of reaching it.") @Default("false") boolean invert,
            @Doc("Omitted: plain walking.") Optional<Route> route,
            @Doc("preview searches without walking; score prices route.corridor.points, for self only.")
            Optional<PlanMode> plan,
            @Doc("With plan: 'preview', also return the route's cells.") @Default("false") boolean includePath,
            @Doc("Walk a preview's route; its target and route come from the preview, which the same bot made.")
            Optional<@Length(min = 1, max = 64) String> planId,
            @Default("0") @AwaitMs int awaitMs) implements SelfChecked {

        /** Walk self to exactly {@code pos}, every other field at its default. */
        public static Params to(BlockPosition pos) {
            return toTarget(new Position(pos, 0, GoalMode.IN));
        }

        /** Walk self to {@code target}, every other field at its default. */
        public static Params toTarget(Target target) {
            return new Params(Responses.SELF, Optional.of(target), false, Optional.empty(), Optional.empty(), false,
                    Optional.empty(), 0);
        }

        /** Walk the route of the preview {@code planId}. */
        public static Params walkPlan(String planId) {
            return new Params(Responses.SELF, Optional.empty(), false, Optional.empty(), Optional.empty(), false,
                    Optional.of(planId), 0);
        }

        /** This request, answered after waiting up to {@code ms} for the walk to end. */
        public Params awaiting(int ms) {
            return new Params(bot, target, invert, route, plan, includePath, planId, ms);
        }

        @Override public void check(DecodeContext at) {
            if (planId.isPresent()) {
                String why = "planId walks the preview's own target and route";
                if (target.isPresent()) at.field("target").fail(ApiIssue.Code.INVALID_VALUE, why);
                if (route.isPresent()) at.field("route").fail(ApiIssue.Code.INVALID_VALUE, why);
                if (plan.isPresent()) at.field("plan").fail(ApiIssue.Code.INVALID_VALUE, why);
                if (invert) at.field("invert").fail(ApiIssue.Code.INVALID_VALUE, why);
                if (includePath) at.field("includePath").fail(ApiIssue.Code.INVALID_VALUE, why);
                return;
            }
            if (plan.orElse(null) == PlanMode.SCORE) {
                if (target.isPresent()) at.field("target").fail(ApiIssue.Code.INVALID_VALUE, "score prices route.corridor.points and takes no target");
                if (invert) at.field("invert").fail(ApiIssue.Code.INVALID_VALUE, "score takes no target to invert");
                if (includePath) at.field("includePath").fail(ApiIssue.Code.INVALID_VALUE, "score has no searched path");
                if (awaitMs > 0) at.field("awaitMs").fail(ApiIssue.Code.INVALID_VALUE, "score answers at once and starts nothing to wait for");
                if (route.flatMap(Route::corridor).isEmpty()) {
                    at.field("route").field("corridor").fail(ApiIssue.Code.REQUIRED, "score prices the corridor's points");
                }
                return;
            }
            if (target.isEmpty()) {
                at.field("target").fail(ApiIssue.Code.REQUIRED, "missing; expected " + TARGET.expected());
                return;
            }
            boolean preview = plan.isPresent();
            if (includePath && !preview) at.field("includePath").fail(ApiIssue.Code.INVALID_VALUE, "only a preview returns a path");
            if (route.map(Route::fly).orElse(false)) {
                if (preview) at.field("plan").fail(ApiIssue.Code.INVALID_VALUE, "a preview searches the ground planner; flight has none");
                if (invert || !hasCell(target.get())) {
                    at.field("route").field("mode").fail(ApiIssue.Code.INVALID_VALUE,
                            "flying needs a target cell: position, entityType, entity, waypoint, block, or a direction with near above 0");
                }
            }
        }
    }

    /** How the trip is made. */
    @Named("Travel")
    public enum Travel implements WireName {
        WALK("walk"),
        FLY("fly"),
        /** Asked to fly with no usable elytra on. */
        WALK_WITHOUT_ELYTRA("walkWithoutElytra"),
        /** Handed to the entity's own way of moving, which another mod registered for its type. */
        MOVER("mover");

        private final String wire;
        Travel(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    /** Whether the walk took the previewed route, and why not when it searched again. */
    @Named("PlanAdoption")
    public record Adoption(
            @Length(min = 1, max = 64) String planId,
            @Doc("false: the walk searched again instead.") boolean adopted,
            @Doc("Why the preview's route was not taken.") Optional<String> reason) {}

    @Tag("kind") @Named("GotoResult")
    public sealed interface Result permits Trip, Preview {}

    /** A walk or flight that ended. {@code goal} is the planner's description of the resolved target. */
    @Variant("goto")
    public record Trip(
            @Doc("For display only; its wording is not a format to parse.") String goal,
            @Range(min = 0) int via,
            Travel travel,
            @Doc("Present when the walk was started by planId.") Optional<Adoption> plan) implements Result {}

    @Named("Exposure")
    public record Exposure(int id, String type, @Range(min = 0) int cells) {}

    @Named("SegmentRisk")
    public record SegmentRisk(List<Exposure> exposedTo, Optional<Double> nearestMob, List<String> regions) {}

    /** Cells {@code from} (inclusive) to {@code to} (exclusive) of the route, over which the risk is the same. */
    @Named("RiskSegment")
    public record Segment(
            @Doc("Which goal's route, when there were via points.") Optional<@Range(min = 0, max = 64) Integer> leg,
            @Range(min = 0) int from,
            @Range(min = 0) int to,
            SegmentRisk risk) {}

    /** A preview: the risk in segments rather than the cells; {@code path} only when includePath asked. */
    @Variant("routePreview")
    public record Preview(
            @Doc("Pass to goto within 60 s to walk this route.") @Length(min = 1, max = 64) String planId,
            boolean reached,
            @Range(min = 0) int cells,
            double cost,
            @Range(min = 0) int legs,
            List<Segment> segments,
            double detourRatio,
            boolean bestEffort,
            boolean sightBudgetExhausted,
            boolean snapshotTruncated,
            @Range(min = 0) int expanded,
            double ms,
            Optional<List<BlockPosition>> path) implements Result {}


    public static final UnionType<Target> TARGET = ApiTypes.union(Target.class);

    public static final ApiType<PlanMode> PLAN_MODE = ApiTypes.of(PlanMode.class);

    public static final RecordType<Params> PARAMS = ApiTypes.record(Params.class);

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

    public static final RecordType<Adoption> ADOPTION = ApiTypes.record(Adoption.class);

    public static final RecordType<Segment> SEGMENT = ApiTypes.record(Segment.class);

    public static final UnionType<Result> RESULT = ApiTypes.union(Result.class);

    public static final ApiType<ActionResponse<Result>> RESPONSE = Responses.response("Goto", RESULT, Scored.class);

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
