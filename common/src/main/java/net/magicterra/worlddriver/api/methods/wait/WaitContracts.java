package net.magicterra.worlddriver.api.methods.wait;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.event.EventContracts.Event;
import net.magicterra.worlddriver.api.methods.shared.ActionResults;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionSnapshot;

/**
 * The long waits: {@code mc.wait.event}, {@code mc.wait.worldReady} and {@code mc.wait.condition}, and
 * {@code mc.wait.result}, which reads an action or a background wait without waiting.
 *
 * <p>Each answers {@code {outcome:'done', …}} when it ends, satisfied or out of time. With
 * {@code background:true} it answers {@code {outcome:'started', waitId}} at once instead and keeps
 * waiting on its own; its {@code done} reply is then read with {@code mc.wait.result{kind:'wait'}} and
 * pushed as the {@link EventCatalog.WaitDone} payload of a {@code wait.done} event. Waiting holds no request thread;
 * a foreground wait is refused on a game thread before it starts.
 */
public final class WaitContracts {
    private WaitContracts() {}

    /** The longest any wait accepts. Public because the WebSocket liveness window must outlast it. */
    public static final int MAX_TIMEOUT_MS = 120_000;

    /** Background waits alive at once, across the JVM. */
    public static final int MAX_BACKGROUND = 32;

    /** Any wait's answer: its {@code done} variant, or {@link Started} for a background one. */
    public sealed interface Outcome permits Started, EventsArrived, WorldReady, ConditionResult {}

    public record Started(String waitId) implements Outcome {}

    private static ApiType<Integer> timeoutMs() {
        return ApiTypes.integer(100, MAX_TIMEOUT_MS);
    }

    private static final String BACKGROUND = "Answer {outcome:'started', waitId} at once; the result arrives through "
            + "mc.wait.result{kind:'wait'} and a wait.done event.";

    private static ApiType<Long> elapsedMs() {
        return ApiTypes.int64(0, Long.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    private static <D extends Outcome> UnionType<Outcome> outcome(String name, Class<D> done, RecordType<D> fields) {
        return ApiTypes.taggedUnion(name, "outcome",
                ApiTypes.variant("done", done, fields),
                ApiTypes.variant("started", Started.class, ApiTypes.record(Started.class, null,
                        ApiField.field("waitId", ApiTypes.string(1, 128)).required().forGetter(Started::waitId),
                        Started::new)));
    }

    private static <P> MethodSpec<P, Outcome> waiting(MethodSpec<P, Outcome> spec) {
        return spec.effects(MethodSpec.Effect.SNAPSHOT_READ, MethodSpec.Effect.WAIT)
                .execution(MethodSpec.ExecutionClass.WAIT)
                .mayWait();
    }

    // ------------------------------------------------------------------ mc.wait.event

    public record EventParams(long cursor, Optional<List<String>> types, int limit, int timeoutMs, int pollMs,
                              boolean background) {}

    /** {@code cursor}: the last returned event's seq, or the asked cursor when none came. */
    public record EventsArrived(List<Event> events, boolean timedOut, long cursor, long ms) implements Outcome {}

    public static final MethodSpec<EventParams, Outcome> EVENT = waiting(MethodSpec.of("mc.wait.event",
            ApiTypes.record(EventParams.class, "WaitEventParams",
                    ApiField.field("cursor", EventContracts.cursor()).describe("Wait for events after this seq.")
                            .required().forGetter(EventParams::cursor),
                    ApiField.field("types", EventContracts.typeFilter())
                            .describe("Only these types; omit or [] for every type.").optional().forGetter(EventParams::types),
                    ApiField.field("limit", ApiTypes.integer(1, 256)).defaulted(32).forGetter(EventParams::limit),
                    ApiField.field("timeoutMs", timeoutMs()).defaulted(5000).forGetter(EventParams::timeoutMs),
                    ApiField.field("pollMs", ApiTypes.integer(50, 2000)).defaulted(100).forGetter(EventParams::pollMs),
                    ApiField.field("background", ApiTypes.bool()).describe(BACKGROUND).defaulted(false)
                            .forGetter(EventParams::background),
                    EventParams::new),
            outcome("WaitEventReply", EventsArrived.class, ApiTypes.record(EventsArrived.class, null,
                    ApiField.field("events", ApiTypes.list(EventContracts.EVENT, 256)).required().forGetter(EventsArrived::events),
                    ApiField.field("timedOut", ApiTypes.bool()).required().forGetter(EventsArrived::timedOut),
                    ApiField.field("cursor", EventContracts.cursor()).describe("Pass back as the next call's cursor.")
                            .required().forGetter(EventsArrived::cursor),
                    ApiField.field("ms", elapsedMs()).required().forGetter(EventsArrived::ms),
                    EventsArrived::new)),
            "Wait until an event after cursor (a decimal string, from mc.observe.cursor or a previous reply) of one "
                    + "of types arrives, then answer the ones there are, up to limit. Out of time: events [] and "
                    + "timedOut true. Chain the reply's cursor. Needs a server."));

    // ------------------------------------------------------------------ mc.wait.worldReady

    public record WorldReadyParams(int timeoutMs, int pollMs, boolean background) {}

    /** {@code info}: the last screen reading. */
    public record WorldReady(boolean ready, long ms, Optional<JsonValue> info) implements Outcome {}

    public static final MethodSpec<WorldReadyParams, Outcome> WORLD_READY = waiting(MethodSpec.of("mc.wait.worldReady",
            ApiTypes.record(WorldReadyParams.class, "WaitWorldReadyParams",
                    ApiField.field("timeoutMs", timeoutMs()).defaulted(30_000).forGetter(WorldReadyParams::timeoutMs),
                    ApiField.field("pollMs", ApiTypes.integer(50, 2000)).defaulted(250).forGetter(WorldReadyParams::pollMs),
                    ApiField.field("background", ApiTypes.bool()).describe(BACKGROUND).defaulted(false)
                            .forGetter(WorldReadyParams::background),
                    WorldReadyParams::new),
            outcome("WaitWorldReadyReply", WorldReady.class, ApiTypes.record(WorldReady.class, null,
                    ApiField.field("ready", ApiTypes.bool()).required().forGetter(WorldReady::ready),
                    ApiField.field("ms", elapsedMs()).required().forGetter(WorldReady::ms),
                    ApiField.field("info", ApiTypes.jsonObject()).describe("The last mc.client.screen.info reading.")
                            .optional().forGetter(WorldReady::info),
                    WorldReady::new)),
            "Wait until this client is in a world with its player, e.g. after Play Selected World, so later calls do "
                    + "not land on the loading screen. Needs a client; not a server."));

    // ------------------------------------------------------------------ mc.wait.condition

    public record ConditionParams(String invoke, Optional<JsonValue> params, Optional<String> field,
                                  Optional<JsonValue> value, int timeoutMs, int pollMs, boolean background) {}

    /** {@code value}: what the field held at the last poll; absent when the path led nowhere. */
    public record ConditionResult(boolean satisfied, Optional<JsonValue> value, long ms) implements Outcome {}

    public static final MethodSpec<ConditionParams, Outcome> CONDITION = waiting(MethodSpec.of("mc.wait.condition",
            ApiTypes.record(ConditionParams.class, "WaitConditionParams",
                    ApiField.field("invoke", EventContracts.pollTarget())
                            .describe("A pollable method (mc.bot.status, mc.observe.*, …); writes, scripts and waits are refused.")
                            .required().forGetter(ConditionParams::invoke),
                    ApiField.field("params", EventContracts.pollParams())
                            .describe("Its params, checked against that method's own contract before the wait starts.")
                            .optional().forGetter(ConditionParams::params),
                    ApiField.field("field", ApiTypes.string(1, 256)).describe("Dotted path into the result; omitted: the whole result.")
                            .optional().forGetter(ConditionParams::field),
                    ApiField.field("value", ApiTypes.json()).describe("Satisfied when the field equals this; omitted: when it is truthy.")
                            .optional().forGetter(ConditionParams::value),
                    ApiField.field("timeoutMs", timeoutMs()).defaulted(30_000).forGetter(ConditionParams::timeoutMs),
                    ApiField.field("pollMs", ApiTypes.integer(50, 5000)).defaulted(500).forGetter(ConditionParams::pollMs),
                    ApiField.field("background", ApiTypes.bool()).describe(BACKGROUND).defaulted(false)
                            .forGetter(ConditionParams::background),
                    ConditionParams::new),
            outcome("WaitConditionReply", ConditionResult.class, ApiTypes.record(ConditionResult.class, null,
                    ApiField.field("satisfied", ApiTypes.bool()).required().forGetter(ConditionResult::satisfied),
                    ApiField.field("value", ApiTypes.json()).describe("The field at the last poll.")
                            .optional().forGetter(ConditionResult::value),
                    ApiField.field("ms", elapsedMs()).required().forGetter(ConditionResult::ms),
                    ConditionResult::new)),
            "Poll invoke(params) every pollMs until the dotted field of its result is truthy, or equals value. "
                    + "invoke must be a pollable read such as mc.bot.status; anything else is invalid_params at "
                    + "/invoke. E.g. {invoke:'mc.bot.status', field:'status.busy', value:false}. In live play prefer "
                    + "background:true for long waits, so the caller stays free to react to events."));

    // ------------------------------------------------------------------ mc.wait.result
    //
    // Reads either an action by its actionId or a background wait by its waitId. The two ids are different
    // things and are never mixed.

    public static final String RESULT_METHOD = "mc.wait.result";

    public sealed interface Query permits ActionQuery, WaitQuery {}

    /** Reading an action never consumes it; it is kept until it ages out. */
    public record ActionQuery(String actionId) implements Query {}

    /** {@code consume}: drop the stored result once it is read. */
    public record WaitQuery(String waitId, boolean consume) implements Query {}

    public sealed interface Reply permits ActionReply, WaitReply {}

    public record ActionReply(ActionSnapshot<Object> action) implements Reply {}

    /**
     * Once {@code pending} is false, exactly one of {@code result} (the wait's {@code {outcome:'done', …}}
     * reply) and {@code error} (the ApiError that ended it) is present.
     */
    public record WaitReply(String waitId, boolean pending, Optional<JsonValue> result, Optional<JsonValue> error)
            implements Reply {}

    public static final UnionType<Query> RESULT_PARAMS = ApiTypes.taggedUnion("WaitResultParams", "kind",
            ApiTypes.variant("action", ActionQuery.class, ApiTypes.record(ActionQuery.class, null,
                    ApiField.field("actionId", Responses.ACTION_ID).required().forGetter(ActionQuery::actionId),
                    ActionQuery::new)),
            ApiTypes.variant("wait", WaitQuery.class, ApiTypes.record(WaitQuery.class, null,
                    ApiField.field("waitId", ApiTypes.string(1, 128)).required().forGetter(WaitQuery::waitId),
                    ApiField.field("consume", ApiTypes.bool()).defaulted(true).forGetter(WaitQuery::consume),
                    WaitQuery::new)));

    public static final ApiType<ActionSnapshot<Object>> ANY_ACTION = Responses.snapshot("Any", ActionResults.UNION);

    public static final UnionType<Reply> RESULT_REPLY = ApiTypes.taggedUnion("WaitResultReply", "kind",
            ApiTypes.variant("action", ActionReply.class, ApiTypes.record(ActionReply.class, null,
                    ApiField.field("action", ANY_ACTION).required().forGetter(ActionReply::action),
                    ActionReply::new)),
            ApiTypes.variant("wait", WaitReply.class, ApiTypes.record(WaitReply.class, null,
                    ApiField.field("waitId", ApiTypes.string(1, 128)).required().forGetter(WaitReply::waitId),
                    ApiField.field("pending", ApiTypes.bool()).required().forGetter(WaitReply::pending),
                    ApiField.field("result", ApiTypes.jsonObject()).optional().forGetter(WaitReply::result),
                    ApiField.field("error", ApiTypes.jsonObject()).optional().forGetter(WaitReply::error),
                    WaitReply::new)));

    public static final MethodSpec<Query, Reply> RESULT = MethodSpec.of(RESULT_METHOD, RESULT_PARAMS, RESULT_REPLY,
            "Read an action ({kind:'action', actionId}) or a background wait ({kind:'wait', waitId, consume?}) "
                    + "now, without waiting. An action is kept 10 minutes or 128 endings after it ends; an unknown "
                    + "or expired actionId is action_not_found.");
}
