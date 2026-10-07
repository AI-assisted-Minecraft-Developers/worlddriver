package net.magicterra.worlddriver.api.methods.bot;

import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;

/**
 * The body tasks that move the body without a single fixed goal: {@code mc.bot.follow}, {@code explore},
 * {@code runAway}, {@code sleep} and {@code elytraFly}. A plain walk to a target is {@code mc.bot.goto}.
 */
public final class MoveContracts {
    private MoveContracts() {}

    // ------------------------------------------------------------------ follow

    public sealed interface Quarry permits QuarryType, QuarryPlayer {}
    /** The nearest entity of this type, re-chosen whenever it is lost. */
    public record QuarryType(String type) implements Quarry {}
    /** A player by name, case-sensitive. */
    public record QuarryPlayer(String name) implements Quarry {}

    public record FollowParams(String body, Quarry target, int radius, int maxIdleTicks, int giveUpTicks,
                               Optional<Route> route, int awaitMs) {}

    public record Followed(Quarry target, int radius) {}

    public static final UnionType<Quarry> QUARRY = ApiTypes.taggedUnion("FollowTarget", "kind",
            ApiTypes.variant("entityType", QuarryType.class, ApiTypes.record(null,
                    ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(QuarryType::type),
                    QuarryType::new)),
            ApiTypes.variant("player", QuarryPlayer.class, ApiTypes.record(null,
                    ApiField.field("name", ApiTypes.string(1, 64)).required().forGetter(QuarryPlayer::name),
                    QuarryPlayer::new)));

    public static final ApiType<FollowParams> FOLLOW_PARAMS = ApiTypes.record("FollowParams",
            ApiField.field("body", Responses.BODY_REF).defaulted("self").forGetter(FollowParams::body),
            ApiField.field("target", QUARRY).required().forGetter(FollowParams::target),
            ApiField.field("radius", ApiTypes.integer(1, 16)).describe("Standoff distance.").defaulted(3)
                    .forGetter(FollowParams::radius),
            ApiField.field("maxIdleTicks", ApiTypes.integer(0, 100_000))
                    .describe("Succeed once no match has been seen for this many ticks; 0 follows until cancelled.")
                    .defaulted(0).forGetter(FollowParams::maxIdleTicks),
            ApiField.field("giveUpTicks", ApiTypes.integer(0, 100_000))
                    .describe("Fail as unreachable after this many ticks neither at the standoff nor closer than the "
                            + "closest approach so far; 0 never.")
                    .defaulted(600).forGetter(FollowParams::giveUpTicks),
            ApiField.field("route", RouteContracts.ROUTE)
                    .describe("goto's route conditions, without via, an entity leash anchor or fly: it already tracks an entity.")
                    .optional().forGetter(FollowParams::route),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(FollowParams::awaitMs),
            FollowParams::new)
            .checked((p, at) -> p.route().ifPresent(r -> {
                if (!r.via().isEmpty()) at.field("route").field("via").fail(ApiIssue.Code.INVALID_VALUE, "follow has no waypoints");
                if (r.leash().map(l -> l.anchor() instanceof RouteContracts.EntityAnchor).orElse(false)) {
                    at.field("route").field("leash").field("anchor").fail(ApiIssue.Code.INVALID_VALUE,
                            "follow already tracks an entity; anchor to a point or column");
                }
                if (r.fly()) at.field("route").field("mode").fail(ApiIssue.Code.INVALID_VALUE, "follow cannot fly");
            }));

    public static final UnionType<Followed> FOLLOW_RESULT = ApiTypes.taggedUnion("FollowResult", "kind",
            ApiTypes.variant("follow", Followed.class, ApiTypes.record(null,
                    ApiField.field("target", QUARRY).required().forGetter(Followed::target),
                    ApiField.field("radius", ApiTypes.integer(1, 16)).required().forGetter(Followed::radius),
                    Followed::new)));

    public static final MethodSpec<FollowParams, ActionResponse<Followed>> FOLLOW = MethodSpec.of("mc.bot.follow",
                    FOLLOW_PARAMS, Responses.response("Follow", FOLLOW_RESULT),
                    "Follow an entity, re-aiming whenever it changes block. Fails as unreachable after 5 failed replans "
                            + "in a row or giveUpTicks without progress (a quarry in a pen, or one that outruns it); "
                            + "succeeds after maxIdleTicks with nothing to follow.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ explore

    public record ExploreParams(String body, int centerX, int centerZ, int maxChunks, int awaitMs) {}

    public record Explored(int centerX, int centerZ, int maxChunks, int visited) {}

    public static final ApiType<ExploreParams> EXPLORE_PARAMS = ApiTypes.record("ExploreParams",
            ApiField.field("body", Responses.BODY_REF).defaulted("self").forGetter(ExploreParams::body),
            ApiField.field("centerX", ApiTypes.integer()).required().forGetter(ExploreParams::centerX),
            ApiField.field("centerZ", ApiTypes.integer()).required().forGetter(ExploreParams::centerZ),
            ApiField.field("maxChunks", ApiTypes.integer(1, 64)).describe("Chunks to visit before succeeding.")
                    .defaulted(16).forGetter(ExploreParams::maxChunks),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(ExploreParams::awaitMs),
            ExploreParams::new);

    public static final UnionType<Explored> EXPLORE_RESULT = ApiTypes.taggedUnion("ExploreResult", "kind",
            ApiTypes.variant("explore", Explored.class, ApiTypes.record(null,
                    ApiField.field("centerX", ApiTypes.integer()).required().forGetter(Explored::centerX),
                    ApiField.field("centerZ", ApiTypes.integer()).required().forGetter(Explored::centerZ),
                    ApiField.field("maxChunks", ApiTypes.integer(1, 64)).required().forGetter(Explored::maxChunks),
                    ApiField.field("visited", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Explored::visited),
                    Explored::new)));

    public static final MethodSpec<ExploreParams, ActionResponse<Explored>> EXPLORE = MethodSpec.of("mc.bot.explore",
                    EXPLORE_PARAMS, Responses.response("Explore", EXPLORE_RESULT),
                    "Walk to unvisited chunk centres in a spiral around (centerX, centerZ) until maxChunks are visited: "
                            + "reveals terrain and spawns for later queries.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ runAway

    public record RunAwayParams(String body, Optional<BlockPosition> from, int minDist, int awaitMs) {}

    /** {@code at}: the cell it stopped in. */
    public record RanAway(BlockPosition from, int minDist, BlockPosition at) {}

    public static final ApiType<RunAwayParams> RUN_AWAY_PARAMS = ApiTypes.record("RunAwayParams",
            ApiField.field("body", Responses.BODY_REF).defaulted("self").forGetter(RunAwayParams::body),
            ApiField.field("from", CommonTypes.BLOCK_POSITION).describe("Omitted: where the body stands.")
                    .optional().forGetter(RunAwayParams::from),
            ApiField.field("minDist", ApiTypes.integer(4, 64)).defaulted(16).forGetter(RunAwayParams::minDist),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(RunAwayParams::awaitMs),
            RunAwayParams::new);

    public static final UnionType<RanAway> RUN_AWAY_RESULT = ApiTypes.taggedUnion("RunAwayResult", "kind",
            ApiTypes.variant("runAway", RanAway.class, ApiTypes.record(null,
                    ApiField.field("from", CommonTypes.BLOCK_POSITION).required().forGetter(RanAway::from),
                    ApiField.field("minDist", ApiTypes.integer(4, 64)).required().forGetter(RanAway::minDist),
                    ApiField.field("at", CommonTypes.BLOCK_POSITION).required().forGetter(RanAway::at),
                    RanAway::new)));

    public static final MethodSpec<RunAwayParams, ActionResponse<RanAway>> RUN_AWAY = MethodSpec.of("mc.bot.runAway",
                    RUN_AWAY_PARAMS, Responses.response("RunAway", RUN_AWAY_RESULT),
                    "Walk to any reachable point at least minDist blocks from 'from': flee a mob or a hazard.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ sleep

    public record SleepParams(String body, Optional<BlockPosition> pos, int radius, int awaitMs) {}

    public record Slept(BlockPosition bed) {}

    public static final ApiType<SleepParams> SLEEP_PARAMS = ApiTypes.record("SleepParams",
            ApiField.field("body", Responses.BODY_REF).defaulted("self").forGetter(SleepParams::body),
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).describe("A bed to use instead of searching.")
                    .optional().forGetter(SleepParams::pos),
            ApiField.field("radius", ApiTypes.integer(1, 64)).describe("Search radius around the body.")
                    .defaulted(16).forGetter(SleepParams::radius),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(SleepParams::awaitMs),
            SleepParams::new);

    public static final UnionType<Slept> SLEEP_RESULT = ApiTypes.taggedUnion("SleepResult", "kind",
            ApiTypes.variant("sleep", Slept.class, ApiTypes.record(null,
                    ApiField.field("bed", CommonTypes.BLOCK_POSITION).required().forGetter(Slept::bed),
                    Slept::new)));

    public static final MethodSpec<SleepParams, ActionResponse<Slept>> SLEEP = MethodSpec.of("mc.bot.sleep",
                    SLEEP_PARAMS, Responses.response("Sleep", SLEEP_RESULT),
                    "Walk to the nearest bed and sleep in it. The game decides whether sleeping is allowed (night or "
                            + "thunder, no mob near, bed free); a refusal fails the action with the game's reason.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ elytraFly

    public enum FlightMode implements WireName {
        /** Steered toward pos by a lookahead controller. */
        REACTIVE,
        /** Toward pos on a fixed pitch. */
        GOAL,
        /** On the current heading, with no target. */
        GLIDE,
        /** No usable elytra: walked to pos instead. */
        GROUND_FALLBACK;
        @Override public String wireName() {
            return this == GROUND_FALLBACK ? "groundFallback" : name().toLowerCase(Locale.ROOT);
        }
    }

    public record ElytraParams(String body, Optional<BlockPosition> pos, Optional<Double> yaw, Optional<Double> pitch,
                               Optional<Boolean> reactive, Optional<Boolean> fireworks, int fireworkEveryTicks,
                               Optional<Integer> ticks, double stopXZDist, boolean groundFallback, int near, int awaitMs) {}

    public record Flown(FlightMode mode, boolean fireworks) {}

    public static final ApiType<ElytraParams> ELYTRA_PARAMS = ApiTypes.record("ElytraFlyParams",
            ApiField.field("body", Responses.BODY_REF).defaulted("self").forGetter(ElytraParams::body),
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).describe("Where to fly. Omitted: glide on the current heading.")
                    .optional().forGetter(ElytraParams::pos),
            ApiField.field("yaw", ApiTypes.number(-360, 360)).describe("Initial heading. Omitted: the current look.")
                    .optional().forGetter(ElytraParams::yaw),
            ApiField.field("pitch", ApiTypes.number(-90, 90))
                    .describe("A fixed glide pitch; giving it turns the steering off.").optional().forGetter(ElytraParams::pitch),
            ApiField.field("reactive", ApiTypes.bool())
                    .describe("Steer toward pos. Omitted: on when pos is given without a pitch.")
                    .optional().forGetter(ElytraParams::reactive),
            ApiField.field("fireworks", ApiTypes.bool())
                    .describe("Boost with rockets. Omitted: on when steering, off otherwise.")
                    .optional().forGetter(ElytraParams::fireworks),
            ApiField.field("fireworkEveryTicks", ApiTypes.integer(5, 400)).defaulted(40).forGetter(ElytraParams::fireworkEveryTicks),
            ApiField.field("ticks", ApiTypes.integer(1, 20_000))
                    .describe("Flight budget. Omitted: 2000 when steering, 200 otherwise.")
                    .optional().forGetter(ElytraParams::ticks),
            ApiField.field("stopXZDist", ApiTypes.number(0, 1024)).describe("Succeed within this horizontal distance of pos.")
                    .defaulted(3.0).forGetter(ElytraParams::stopXZDist),
            ApiField.field("groundFallback", ApiTypes.bool())
                    .describe("With no usable elytra, walk to pos instead of failing.")
                    .defaulted(false).forGetter(ElytraParams::groundFallback),
            ApiField.field("near", ApiTypes.integer(0, 64)).describe("groundFallback: accept any cell this close to pos.")
                    .defaulted(1).forGetter(ElytraParams::near),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(ElytraParams::awaitMs),
            ElytraParams::new)
            .checked((p, at) -> {
                if (p.groundFallback() && p.pos().isEmpty()) {
                    at.field("groundFallback").fail(ApiIssue.Code.INVALID_VALUE, "walking needs a pos to walk to");
                }
                if (p.reactive().orElse(false) && p.pos().isEmpty()) {
                    at.field("reactive").fail(ApiIssue.Code.INVALID_VALUE, "steering needs a pos to steer toward");
                }
            });

    public static final UnionType<Flown> ELYTRA_RESULT = ApiTypes.taggedUnion("ElytraFlyResult", "kind",
            ApiTypes.variant("elytraFly", Flown.class, ApiTypes.record(null,
                    ApiField.field("mode", ApiTypes.enumOf("FlightMode", FlightMode.class)).required().forGetter(Flown::mode),
                    ApiField.field("fireworks", ApiTypes.bool()).required().forGetter(Flown::fireworks),
                    Flown::new)));

    public static final MethodSpec<ElytraParams, ActionResponse<Flown>> ELYTRA_FLY = MethodSpec.of("mc.bot.elytraFly",
                    ELYTRA_PARAMS, Responses.response("ElytraFly", ELYTRA_RESULT),
                    "Glide with the elytra worn in the chest slot; the body must already be airborne. With pos and no "
                            + "pitch a lookahead controller steers toward it and boosts with rockets; a pitch pins a "
                            + "fixed glide instead. For a trip to a target, goto with route.mode ['fly'] also takes off.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();
}
