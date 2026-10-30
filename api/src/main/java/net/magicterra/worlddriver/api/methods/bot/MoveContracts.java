package net.magicterra.worlddriver.api.methods.bot;

import java.util.Locale;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
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
 * The body tasks that move the body without a single fixed goal: {@code mc.bot.follow}, {@code explore},
 * {@code runAway}, {@code sleep} and {@code elytraFly}. A plain walk to a target is {@code mc.bot.goto}.
 */
public final class MoveContracts {

    public static final UnionType<Quarry> QUARRY = ApiTypes.union(Quarry.class);

    public static final ApiType<FollowParams> FOLLOW_PARAMS = ApiTypes.record(FollowParams.class);

    public static final UnionType<Followed> FOLLOW_RESULT =
            ApiTypes.union("FollowResult", "kind", ApiTypes.record(Followed.class));

    public static final MethodSpec<FollowParams, ActionResponse<Followed>> FOLLOW = MethodSpec.of(
                    "mc.bot.follow",
                    FOLLOW_PARAMS,
                    Responses.response("Follow", FOLLOW_RESULT),
                    "Follow an entity, re-aiming whenever it changes block. Fails as unreachable after 5 failed replans "
                            + "in a row or giveUpTicks without progress (a quarry in a pen, or one that outruns it); "
                            + "succeeds after maxIdleTicks with nothing to follow.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    public static final ApiType<ExploreParams> EXPLORE_PARAMS = ApiTypes.record(ExploreParams.class);

    public static final UnionType<Explored> EXPLORE_RESULT =
            ApiTypes.union("ExploreResult", "kind", ApiTypes.record(Explored.class));

    public static final MethodSpec<ExploreParams, ActionResponse<Explored>> EXPLORE = MethodSpec.of(
                    "mc.bot.explore",
                    EXPLORE_PARAMS,
                    Responses.response("Explore", EXPLORE_RESULT),
                    "Walk to unvisited chunk centres in a spiral around (centerX, centerZ) until maxChunks are visited: "
                            + "reveals terrain and spawns for later queries.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    public static final ApiType<RunAwayParams> RUN_AWAY_PARAMS = ApiTypes.record(RunAwayParams.class);

    public static final UnionType<RanAway> RUN_AWAY_RESULT =
            ApiTypes.union("RunAwayResult", "kind", ApiTypes.record(RanAway.class));

    public static final MethodSpec<RunAwayParams, ActionResponse<RanAway>> RUN_AWAY = MethodSpec.of(
                    "mc.bot.runAway",
                    RUN_AWAY_PARAMS,
                    Responses.response("RunAway", RUN_AWAY_RESULT),
                    "Walk to any reachable point at least minDist blocks from 'from': flee a mob or a hazard.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    public static final ApiType<SleepParams> SLEEP_PARAMS = ApiTypes.record(SleepParams.class);

    public static final UnionType<Slept> SLEEP_RESULT =
            ApiTypes.union("SleepResult", "kind", ApiTypes.record(Slept.class));

    public static final MethodSpec<SleepParams, ActionResponse<Slept>> SLEEP = MethodSpec.of(
                    "mc.bot.sleep",
                    SLEEP_PARAMS,
                    Responses.response("Sleep", SLEEP_RESULT),
                    "Walk to the nearest bed and sleep in it. The game decides whether sleeping is allowed (night or "
                            + "thunder, no mob near, bed free); a refusal fails the action with the game's reason.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    public static final ApiType<ElytraParams> ELYTRA_PARAMS = ApiTypes.record(ElytraParams.class);

    public static final UnionType<Flown> ELYTRA_RESULT =
            ApiTypes.union("ElytraFlyResult", "kind", ApiTypes.record(Flown.class));

    public static final MethodSpec<ElytraParams, ActionResponse<Flown>> ELYTRA_FLY = MethodSpec.of(
                    "mc.bot.elytraFly",
                    ELYTRA_PARAMS,
                    Responses.response("ElytraFly", ELYTRA_RESULT),
                    "Glide with the elytra worn in the chest slot; the body must already be airborne. With pos and no "
                            + "pitch a lookahead controller steers toward it and boosts with rockets; a pitch pins a "
                            + "fixed glide instead. For a trip to a target, goto with route.mode ['fly'] also takes off.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    private MoveContracts() {}

    // ------------------------------------------------------------------ follow

    @Tag("kind")
    @Named("FollowTarget")
    public sealed interface Quarry permits QuarryType, QuarryPlayer {}
    /** The nearest entity of this type, re-chosen whenever it is lost. */
    @Variant("entityType")
    public record QuarryType(@ResourceId String type) implements Quarry {}
    /** A player by name, case-sensitive. */
    @Variant("player")
    public record QuarryPlayer(@Length(min = 1, max = 64) String name) implements Quarry {}

    @Named("FollowParams")
    public record FollowParams(
            @Default("self") @BotRef String bot,
            Quarry target,

            @Doc("Standoff distance.") @Default("3") @Range(min = 1, max = 16)
            int radius,

            @Doc("Succeed once no match has been seen for this many ticks; 0 follows until cancelled.")
            @Default("0")
            @Range(min = 0, max = 100000)
            int maxIdleTicks,

            @Doc("Fail as unreachable after this many ticks neither at the standoff nor closer than the closest "
                    + "approach so far; 0 never.")
            @Default("600")
            @Range(min = 0, max = 100000)
            int giveUpTicks,

            @Doc("goto's route conditions, without via, an entity leash anchor or fly: it already tracks an entity.")
            Optional<Route> route,

            @Default("0") @AwaitMs int awaitMs)
            implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            route.ifPresent(r -> {
                if (!r.via().isEmpty()) {
                    at.field("route").field("via").fail(ApiIssue.Code.INVALID_VALUE, "follow has no waypoints");
                }
                if (r.leash()
                        .map(l -> l.anchor() instanceof RouteContracts.EntityAnchor)
                        .orElse(false)) {
                    at.field("route")
                            .field("leash")
                            .field("anchor")
                            .fail(
                                    ApiIssue.Code.INVALID_VALUE,
                                    "follow already tracks an entity; anchor to a point or column");
                }
                if (r.fly()) {
                    at.field("route").field("mode").fail(ApiIssue.Code.INVALID_VALUE, "follow cannot fly");
                }
            });
        }
    }

    @Variant("follow")
    public record Followed(
            Quarry target, @Range(min = 1, max = 16) int radius) {}

    // ------------------------------------------------------------------ explore

    @Named("ExploreParams")
    public record ExploreParams(
            @Default("self") @BotRef String bot,
            int centerX,
            int centerZ,

            @Doc("Chunks to visit before succeeding.") @Default("16") @Range(min = 1, max = 64)
            int maxChunks,

            @Default("0") @AwaitMs int awaitMs) {}

    @Variant("explore")
    public record Explored(
            int centerX,
            int centerZ,
            @Range(min = 1, max = 64) int maxChunks,
            @Range(min = 0) int visited) {}

    // ------------------------------------------------------------------ runAway

    @Named("RunAwayParams")
    public record RunAwayParams(
            @Default("self") @BotRef String bot,
            @Doc("Omitted: where the body stands.") Optional<BlockPosition> from,
            @Default("16") @Range(min = 4, max = 64) int minDist,
            @Default("0") @AwaitMs int awaitMs) {}

    /** {@code at}: the cell it stopped in. */
    @Variant("runAway")
    public record RanAway(
            BlockPosition from, @Range(min = 4, max = 64) int minDist, BlockPosition at) {}

    // ------------------------------------------------------------------ sleep

    @Named("SleepParams")
    public record SleepParams(
            @Default("self") @BotRef String bot,
            @Doc("A bed to use instead of searching.") Optional<BlockPosition> pos,

            @Doc("Search radius around the body.") @Default("16") @Range(min = 1, max = 64)
            int radius,

            @Default("0") @AwaitMs int awaitMs) {}

    @Variant("sleep")
    public record Slept(BlockPosition bed) {}

    // ------------------------------------------------------------------ elytraFly

    @Named("FlightMode")
    public enum FlightMode implements WireName {
        /** Steered toward pos by a lookahead controller. */
        REACTIVE,
        /** Toward pos on a fixed pitch. */
        GOAL,
        /** On the current heading, with no target. */
        GLIDE,
        /** No usable elytra: walked to pos instead. */
        GROUND_FALLBACK;

        @Override
        public String wireName() {
            return this == GROUND_FALLBACK ? "groundFallback" : name().toLowerCase(Locale.ROOT);
        }
    }

    @Named("ElytraFlyParams")
    public record ElytraParams(
            @Default("self") @BotRef String bot,

            @Doc("Where to fly. Omitted: glide on the current heading.")
            Optional<BlockPosition> pos,

            @Doc("Initial heading. Omitted: the current look.")
            Optional<@Range(min = -360, max = 360) Double> yaw,

            @Doc("A fixed glide pitch; giving it turns the steering off.")
            Optional<@Range(min = -90, max = 90) Double> pitch,

            @Doc("Steer toward pos. Omitted: on when pos is given without a pitch.")
            Optional<Boolean> reactive,

            @Doc("Boost with rockets. Omitted: on when steering, off otherwise.")
            Optional<Boolean> fireworks,

            @Default("40") @Range(min = 5, max = 400) int fireworkEveryTicks,

            @Doc("Flight budget. Omitted: 2000 when steering, 200 otherwise.")
            Optional<@Range(min = 1, max = 20000) Integer> ticks,

            @Doc("Succeed within this horizontal distance of pos.") @Default("3") @Range(min = 0, max = 1024)
            double stopXZDist,

            @Doc("With no usable elytra, walk to pos instead of failing.") @Default("false")
            boolean groundFallback,

            @Doc("groundFallback: accept any cell this close to pos.") @Default("1") @Range(min = 0, max = 64)
            int near,

            @Default("0") @AwaitMs int awaitMs)
            implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            if (groundFallback && pos.isEmpty()) {
                at.field("groundFallback").fail(ApiIssue.Code.INVALID_VALUE, "walking needs a pos to walk to");
            }
            if (reactive.orElse(false) && pos.isEmpty()) {
                at.field("reactive").fail(ApiIssue.Code.INVALID_VALUE, "steering needs a pos to steer toward");
            }
        }
    }

    @Variant("elytraFly")
    public record Flown(FlightMode mode, boolean fireworks) {}
}
