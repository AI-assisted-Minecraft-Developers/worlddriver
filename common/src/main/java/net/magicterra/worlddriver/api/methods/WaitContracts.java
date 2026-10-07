package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.methods.EventContracts.Event;

/**
 * The long waits: {@code mc.wait.event}, {@code mc.wait.worldReady} and {@code mc.wait.condition}.
 *
 * <p>Each answers {@code {outcome:'done', …}} when it ends, satisfied or out of time. With
 * {@code background:true} it answers {@code {outcome:'started', waitId}} at once instead and keeps
 * waiting on its own; its {@code done} reply is then read with {@code mc.wait.result{kind:'wait'}} and
 * pushed as the {@link WaitDone} payload of a {@code wait.done} event. Waiting holds no request thread;
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
                ApiTypes.variant("started", Started.class, ApiTypes.record(null,
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
            ApiTypes.record("WaitEventParams",
                    ApiField.field("cursor", EventContracts.cursor()).describe("Wait for events after this seq.")
                            .required().forGetter(EventParams::cursor),
                    ApiField.field("types", ApiTypes.list(EventContracts.eventType(), 256))
                            .describe("Only these types; omit or [] for every type.").optional().forGetter(EventParams::types),
                    ApiField.field("limit", ApiTypes.integer(1, 256)).defaulted(32).forGetter(EventParams::limit),
                    ApiField.field("timeoutMs", timeoutMs()).defaulted(5000).forGetter(EventParams::timeoutMs),
                    ApiField.field("pollMs", ApiTypes.integer(50, 2000)).defaulted(100).forGetter(EventParams::pollMs),
                    ApiField.field("background", ApiTypes.bool()).describe(BACKGROUND).defaulted(false)
                            .forGetter(EventParams::background),
                    EventParams::new),
            outcome("WaitEventReply", EventsArrived.class, ApiTypes.record(null,
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
            ApiTypes.record("WaitWorldReadyParams",
                    ApiField.field("timeoutMs", timeoutMs()).defaulted(30_000).forGetter(WorldReadyParams::timeoutMs),
                    ApiField.field("pollMs", ApiTypes.integer(50, 2000)).defaulted(250).forGetter(WorldReadyParams::pollMs),
                    ApiField.field("background", ApiTypes.bool()).describe(BACKGROUND).defaulted(false)
                            .forGetter(WorldReadyParams::background),
                    WorldReadyParams::new),
            outcome("WaitWorldReadyReply", WorldReady.class, ApiTypes.record(null,
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
            ApiTypes.record("WaitConditionParams",
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
            outcome("WaitConditionReply", ConditionResult.class, ApiTypes.record(null,
                    ApiField.field("satisfied", ApiTypes.bool()).required().forGetter(ConditionResult::satisfied),
                    ApiField.field("value", ApiTypes.json()).describe("The field at the last poll.")
                            .optional().forGetter(ConditionResult::value),
                    ApiField.field("ms", elapsedMs()).required().forGetter(ConditionResult::ms),
                    ConditionResult::new)),
            "Poll invoke(params) every pollMs until the dotted field of its result is truthy, or equals value. "
                    + "invoke must be a pollable read such as mc.bot.status; anything else is invalid_params at "
                    + "/invoke. E.g. {invoke:'mc.bot.status', field:'status.busy', value:false}. In live play prefer "
                    + "background:true for long waits, so the caller stays free to react to events."));

    // ------------------------------------------------------------------ wait.done

    /**
     * The {@code wait.done} event's payload: the background wait's {@code done} reply as {@code result}, or
     * the error that ended it.
     */
    public record WaitDone(String waitId, String method, Optional<JsonValue> result, Optional<JsonValue> error) {}

    public static final RecordType<WaitDone> WAIT_DONE = ApiTypes.record("WaitDone",
            ApiField.field("waitId", ApiTypes.string(1, 128)).required().forGetter(WaitDone::waitId),
            ApiField.field("method", ApiTypes.string(1, 128)).required().forGetter(WaitDone::method),
            ApiField.field("result", ApiTypes.jsonObject()).describe("The wait's {outcome:'done', …} reply.")
                    .optional().forGetter(WaitDone::result),
            ApiField.field("error", ApiTypes.jsonObject()).describe("The ApiError that ended it instead.")
                    .optional().forGetter(WaitDone::error),
            WaitDone::new);
}
