package net.magicterra.worlddriver.api.methods.wait;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.methods.event.EventContracts.Event;
import net.magicterra.worlddriver.api.methods.shared.ActionResults;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionId;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionSnapshot;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.As;
import net.magicterra.worlddriver.api.schema.annotation.AsString;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OnlyObject;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * The long waits: {@code mc.wait.event}, {@code mc.wait.worldReady} and {@code mc.wait.condition}, and
 * {@code mc.wait.result}, which reads an action or a background wait without waiting.
 *
 * <p>Each answers {@code {outcome:'done', …}} when it ends, satisfied or out of time. With {@code background:true} it
 * answers {@code {outcome:'started', waitId}} at once instead and keeps waiting on its own; its {@code done} reply is
 * then read with {@code mc.wait.result{kind:'wait'}} and pushed as the {@link EventCatalog.WaitDone} payload of a
 * {@code wait.done} event. Waiting holds no request thread; a foreground wait is refused on a game thread before it
 * starts.
 */
public final class WaitContracts {
    private WaitContracts() {}

    /** The longest any wait accepts. Public because the WebSocket liveness window must outlast it. */
    public static final int MAX_TIMEOUT_MS = 120_000;

    /** Background waits alive at once, across the JVM. */
    public static final int MAX_BACKGROUND = 32;

    /** Any wait's answer: its {@code done} variant, or {@link Started} for a background one. */
    public sealed interface Outcome permits Started, EventsArrived, WorldReady, ConditionResult {}

    @Variant("started")
    public record Started(@Length(min = 1, max = 128) String waitId) implements Outcome {}

    private static <D extends Record & Outcome> UnionType<Outcome> outcome(String name, Class<D> done) {
        return ApiTypes.union(name, "outcome", ApiTypes.record(done), ApiTypes.record(Started.class));
    }

    private static <P> MethodSpec<P, Outcome> waiting(MethodSpec<P, Outcome> spec) {
        return spec.effects(MethodSpec.Effect.SNAPSHOT_READ, MethodSpec.Effect.WAIT)
                .execution(MethodSpec.ExecutionClass.WAIT)
                .mayWait();
    }

    // ------------------------------------------------------------------ mc.wait.event

    @Named("WaitEventParams")
    public record EventParams(
            @Doc("Wait for events after this seq.") @AsString @Range(min = 0)
            long cursor,

            @Doc("Only these types; omit or [] for every type.")
            Optional<@Length(max = 256) List<@Length(min = 1, max = 128) String>> types,

            @Default("32") @Range(min = 1, max = 256) int limit,
            @Default("5000") @Range(min = 100, max = 120000) int timeoutMs,
            @Default("100") @Range(min = 50, max = 2000) int pollMs,

            @Doc("Answer {outcome:'started', waitId} at once; the result arrives through mc.wait.result{kind:'wait'} "
                    + "and a wait.done event.")
            @Default("false")
            boolean background) {}

    /** {@code cursor}: the last returned event's seq, or the asked cursor when none came. */
    @Variant("done")
    public record EventsArrived(
            @Length(max = 256) List<Event> events,
            boolean timedOut,

            @Doc("Pass back as the next call's cursor.") @AsString @Range(min = 0)
            long cursor,

            @Range(min = 0) long ms)
            implements Outcome {}

    public static final MethodSpec<EventParams, Outcome> EVENT = waiting(MethodSpec.of(
            "mc.wait.event",
            ApiTypes.record(EventParams.class),
            outcome("WaitEventReply", EventsArrived.class),
            "Wait until an event after cursor (a decimal string, from mc.observe.cursor or a previous reply) of one "
                    + "of types arrives, then answer the ones there are, up to limit. Out of time: events [] and "
                    + "timedOut true. Chain the reply's cursor. Needs a server."));

    // ------------------------------------------------------------------ mc.wait.worldReady

    @Named("WaitWorldReadyParams")
    public record WorldReadyParams(
            @Default("30000") @Range(min = 100, max = 120000)
            int timeoutMs,

            @Default("250") @Range(min = 50, max = 2000) int pollMs,

            @Doc("Answer {outcome:'started', waitId} at once; the result arrives through mc.wait.result{kind:'wait'} "
                    + "and a wait.done event.")
            @Default("false")
            boolean background) {}

    /** {@code info}: the last screen reading. */
    @Variant("done")
    public record WorldReady(
            boolean ready,
            @Range(min = 0) long ms,
            @Doc("The last mc.client.screen.info reading.") Optional<@OnlyObject JsonValue> info)
            implements Outcome {}

    public static final MethodSpec<WorldReadyParams, Outcome> WORLD_READY = waiting(MethodSpec.of(
            "mc.wait.worldReady",
            ApiTypes.record(WorldReadyParams.class),
            outcome("WaitWorldReadyReply", WorldReady.class),
            "Wait until this client is in a world with its player, e.g. after Play Selected World, so later calls do "
                    + "not land on the loading screen. Needs a client; not a server."));

    // ------------------------------------------------------------------ mc.wait.condition

    @Named("WaitConditionParams")
    public record ConditionParams(
            @Doc("A pollable method (mc.bot.status, mc.observe.*, …); writes, scripts and waits are refused.")
            @Pattern(value = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+", expected = "a method name")
            @Length(max = 128)
            String invoke,

            @Doc("Its params, checked against that method's own contract before the wait starts.")
            Optional<@OnlyObject JsonValue> params,

            @Doc("Dotted path into the result; omitted: the whole result.")
            Optional<@Length(min = 1, max = 256) String> field,

            @Doc("Satisfied when the field equals this; omitted: when it is truthy.")
            Optional<JsonValue> value,

            @Default("30000") @Range(min = 100, max = 120000)
            int timeoutMs,

            @Default("500") @Range(min = 50, max = 5000) int pollMs,

            @Doc("Answer {outcome:'started', waitId} at once; the result arrives through mc.wait.result{kind:'wait'} "
                    + "and a wait.done event.")
            @Default("false")
            boolean background) {}

    /** {@code value}: what the field held at the last poll; absent when the path led nowhere. */
    @Variant("done")
    public record ConditionResult(
            boolean satisfied,
            @Doc("The field at the last poll.") Optional<JsonValue> value,
            @Range(min = 0) long ms) implements Outcome {}

    public static final MethodSpec<ConditionParams, Outcome> CONDITION = waiting(MethodSpec.of(
            "mc.wait.condition",
            ApiTypes.record(ConditionParams.class),
            outcome("WaitConditionReply", ConditionResult.class),
            "Poll invoke(params) every pollMs until the dotted field of its result is truthy, or equals value. "
                    + "invoke must be a pollable read such as mc.bot.status; anything else is invalid_params at "
                    + "/invoke. E.g. {invoke:'mc.bot.status', field:'status.busy', value:false}. In live play prefer "
                    + "background:true for long waits, so the caller stays free to react to events."));

    // ------------------------------------------------------------------ mc.wait.result
    //
    // Reads either an action by its actionId or a background wait by its waitId. The two ids are different
    // things and are never mixed.

    public static final String RESULT_METHOD = "mc.wait.result";

    public static final ApiType<ActionSnapshot<Object>> ANY_ACTION = Responses.snapshot("Any", ActionResults.UNION);

    @Tag("kind")
    @Named("WaitResultParams")
    public sealed interface Query permits ActionQuery, WaitQuery {}

    /** Reading an action never consumes it; it is kept until it ages out. */
    @Variant("action")
    public record ActionQuery(@ActionId String actionId) implements Query {}

    /** {@code consume}: drop the stored result once it is read. */
    @Variant("wait")
    public record WaitQuery(
            @Length(min = 1, max = 128) String waitId,
            @Default("true") boolean consume) implements Query {}

    @Tag("kind")
    @Named("WaitResultReply")
    public sealed interface Reply permits ActionReply, WaitReply {}

    @Variant("action")
    public record ActionReply(
            @As(value = WaitContracts.class, field = "ANY_ACTION")
            ActionSnapshot<Object> action) implements Reply {}

    /**
     * Once {@code pending} is false, exactly one of {@code result} (the wait's {@code {outcome:'done', …}} reply) and
     * {@code error} (the ApiError that ended it) is present.
     */
    @Variant("wait")
    public record WaitReply(
            @Length(min = 1, max = 128) String waitId,
            boolean pending,
            Optional<@OnlyObject JsonValue> result,
            Optional<@OnlyObject JsonValue> error)
            implements Reply {}

    public static final UnionType<Query> RESULT_PARAMS = ApiTypes.union(Query.class);

    public static final UnionType<Reply> RESULT_REPLY = ApiTypes.union(Reply.class);

    public static final MethodSpec<Query, Reply> RESULT = MethodSpec.of(
            RESULT_METHOD,
            RESULT_PARAMS,
            RESULT_REPLY,
            "Read an action ({kind:'action', actionId}) or a background wait ({kind:'wait', waitId, consume?}) "
                    + "now, without waiting. An action is kept 10 minutes or 128 endings after it ends; an unknown "
                    + "or expired actionId is action_not_found.");
}
