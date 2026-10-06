package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;

/**
 * The event stream as values: the one {@link Event} shape every place an event leaves the JVM writes
 * (WebSocket and SSE pushes, {@code mc.observe.eventsSince}, {@code mc.wait.event}, the
 * {@code wait.done} event, an action's {@code returnEvents}), and the methods that read, inject and
 * watch it: {@code mc.observe.cursor}, {@code mc.observe.eventsSince} and {@code mc.events}.
 *
 * <p>{@code seq} and every cursor are 64-bit and travel as decimal strings, because a JavaScript
 * number rounds past 2^53. The other numbers keep their own declared types: {@code timestamp} is
 * epoch milliseconds and fits a JSON number, a payload's entity id is a 32-bit int.
 */
public final class EventContracts {
    private EventContracts() {}

    // ------------------------------------------------------------------ the event

    /**
     * One event. {@code pos}: where it happened, when it happened somewhere. {@code data}: its payload,
     * whose shape belongs to its type (a block id for {@code block.place}, an object for
     * {@code time.phase}, the caller's own value for an emitted event); absent when it has none.
     */
    public record Event(long seq, long timestamp, String type, Optional<BlockPosition> pos, Optional<JsonValue> data) {}

    /** A position in the stream: the seq of the last event seen, {@code "0"} for before the first. */
    public static ApiType<Long> cursor() {
        return ApiTypes.int64String(0);
    }

    /** Event type names are open: scripts mint their own. */
    public static ApiType<String> eventType() {
        return ApiTypes.string(1, 128);
    }

    public static final RecordType<Event> EVENT = ApiTypes.record("DriverEvent",
            ApiField.field("seq", ApiTypes.int64String(1))
                    .describe("Its place in the stream, increasing by one per event; a decimal string.")
                    .required().forGetter(Event::seq),
            ApiField.field("timestamp", ApiTypes.int64(0, Long.MAX_VALUE)).describe("Epoch milliseconds.")
                    .required().forGetter(Event::timestamp),
            ApiField.field("type", eventType()).required().forGetter(Event::type),
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).optional().forGetter(Event::pos),
            ApiField.field("data", ApiTypes.json()).describe("The payload; its shape depends on the type.")
                    .optional().forGetter(Event::data),
            Event::new);

    private static ApiType<List<String>> typeFilter() {
        return ApiTypes.list(eventType(), 256);
    }

    // ------------------------------------------------------------------ mc.observe.cursor

    public record NoParams() {}

    public record Cursor(long cursor) {}

    public static final MethodSpec<NoParams, Cursor> CURSOR = MethodSpec.of("mc.observe.cursor",
                    ApiTypes.record("CursorParams", NoParams::new),
                    ApiTypes.record("Cursor",
                            ApiField.field("cursor", cursor()).describe("The seq of the newest event so far.")
                                    .required().forGetter(Cursor::cursor),
                            Cursor::new),
                    "The newest event's seq, as a decimal string. Pass it as the cursor of mc.observe.eventsSince or "
                            + "mc.wait.event to get only what happens after now. Needs a server.")
            .asPollable();

    // ------------------------------------------------------------------ mc.observe.eventsSince

    public record SinceParams(long cursor, Optional<List<String>> types, int limit) {}

    /** {@code cursor}: the seq of the last event in the page, or the asked cursor when the page is empty. */
    public record Page(List<Event> events, long cursor) {}

    public static final MethodSpec<SinceParams, Page> EVENTS_SINCE = MethodSpec.of("mc.observe.eventsSince",
                    ApiTypes.record("EventsSinceParams",
                            ApiField.field("cursor", cursor()).describe("Events after this seq; \"0\" for all retained.")
                                    .defaulted(0L).forGetter(SinceParams::cursor),
                            ApiField.field("types", typeFilter()).describe("Only these types; omit or [] for every type.")
                                    .optional().forGetter(SinceParams::types),
                            ApiField.field("limit", ApiTypes.integer(1, 4096)).defaulted(256).forGetter(SinceParams::limit),
                            SinceParams::new),
                    ApiTypes.record("EventPage",
                            ApiField.field("events", ApiTypes.list(EVENT, 4096)).describe("Oldest first.")
                                    .required().forGetter(Page::events),
                            ApiField.field("cursor", cursor()).describe("Pass back as the next call's cursor.")
                                    .required().forGetter(Page::cursor),
                            Page::new),
                    "The events after cursor, oldest first, at most limit of them. Only the newest 4096 events are kept, "
                            + "so a cursor older than that gets what is left. Types include block.break, block.place, "
                            + "block.fill, entity.death, player.join, player.leave, player.hurt, chat.message, "
                            + "threat.appeared, wait.done and whatever mc.events emit names. After an action, returnEvents "
                            + "on the action saves this round trip. Needs a server.")
            .asPollable();

    // ------------------------------------------------------------------ mc.events

    public sealed interface EventsOp permits Emit, Watch, Unwatch, ListWatchers {}

    public record Emit(String type, Optional<JsonValue> data, Optional<BlockPosition> pos) implements EventsOp {}

    /**
     * Poll {@code invoke(params)} every {@code everyMs}, walk {@code field} into the result, and emit
     * {@code emitAs} on each poll where the test turns from false to true. The test: equal to
     * {@code value}, numerically above {@code above}, below {@code below}, else truthy.
     */
    public record Watch(String invoke, Optional<JsonValue> params, Optional<String> field, Optional<JsonValue> value,
                        Optional<Double> above, Optional<Double> below, String emitAs, int everyMs, boolean once)
            implements EventsOp {}

    public record Unwatch(int id) implements EventsOp {}

    public record ListWatchers() implements EventsOp {}

    public sealed interface EventsReply permits Emitted, Watching, Unwatched, Watchers {}

    public record Emitted(long seq, String type) implements EventsReply {}

    public record Watching(int id, String invoke, String emitAs, int everyMs) implements EventsReply {}

    public record Unwatched(boolean removed) implements EventsReply {}

    public record Watcher(int id, String invoke, Optional<String> field, String emitAs, int everyMs, boolean once, int fired) {}

    public record Watchers(List<Watcher> watchers, int count) implements EventsReply {}

    /** The most watchers alive at once; each is a poll every {@code everyMs}, for as long as it lives. */
    public static final int MAX_WATCHERS = 16;

    /** What {@code invoke} names: a method that may be polled. Checked against the registry when the call arrives. */
    public static ApiType<String> pollTarget() {
        return ApiTypes.pattern("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+", "a method name")
                .maxLength(128);
    }

    /** {@code params} of a polled call: checked against the polled method's own parameters. */
    public static ApiType<JsonValue> pollParams() {
        return ApiTypes.jsonObject();
    }

    public static final ApiType<Integer> WATCHER_ID = ApiTypes.integer(1, Integer.MAX_VALUE);

    private static final RecordType<Watch> WATCH_FIELDS = ApiTypes.record(null,
            ApiField.field("invoke", pollTarget())
                    .describe("A pollable method (mc.bot.status, mc.observe.*, …); writes, scripts and waits are refused.")
                    .required().forGetter(Watch::invoke),
            ApiField.field("params", pollParams()).describe("Its params, checked against that method's own contract.")
                    .optional().forGetter(Watch::params),
            ApiField.field("field", ApiTypes.string(1, 256)).describe("Dotted path into the result; omitted: the whole result.")
                    .optional().forGetter(Watch::field),
            ApiField.field("value", ApiTypes.json()).describe("Fire when the field equals this.")
                    .optional().forGetter(Watch::value),
            ApiField.field("above", ApiTypes.number()).describe("Fire when the field rises above this number.")
                    .optional().forGetter(Watch::above),
            ApiField.field("below", ApiTypes.number()).describe("Fire when the field drops below this number.")
                    .optional().forGetter(Watch::below),
            ApiField.field("emitAs", eventType()).describe("The event type emitted on the rising edge.")
                    .defaulted("condition.met").forGetter(Watch::emitAs),
            ApiField.field("everyMs", ApiTypes.integer(200, 60_000)).defaulted(1000).forGetter(Watch::everyMs),
            ApiField.field("once", ApiTypes.bool()).describe("Remove the watcher after it first fires.")
                    .defaulted(false).forGetter(Watch::once),
            Watch::new)
            .checked((w, at) -> {
                int tests = (w.value().isPresent() ? 1 : 0) + (w.above().isPresent() ? 1 : 0) + (w.below().isPresent() ? 1 : 0);
                if (tests > 1) {
                    at.field(w.below().isPresent() ? "below" : "above")
                            .fail(ApiIssue.Code.INVALID_VALUE, "give at most one of value, above and below");
                }
            });

    public static final UnionType<EventsOp> EVENTS_PARAMS = ApiTypes.taggedUnion("EventsParams", "op",
            ApiTypes.variant("emit", Emit.class, ApiTypes.record(null,
                    ApiField.field("type", eventType()).required().forGetter(Emit::type),
                    ApiField.field("data", ApiTypes.json()).describe("Any JSON payload; omitted: none.")
                            .optional().forGetter(Emit::data),
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).optional().forGetter(Emit::pos),
                    Emit::new)),
            ApiTypes.variant("watch", Watch.class, WATCH_FIELDS),
            ApiTypes.variant("unwatch", Unwatch.class, ApiTypes.record(null,
                    ApiField.field("id", WATCHER_ID).required().forGetter(Unwatch::id),
                    Unwatch::new)),
            ApiTypes.variant("list", ListWatchers.class, ApiTypes.record(null, ListWatchers::new)));

    private static final RecordType<Watcher> WATCHER = ApiTypes.record("EventWatcher",
            ApiField.field("id", WATCHER_ID).required().forGetter(Watcher::id),
            ApiField.field("invoke", ApiTypes.string(1, 128)).required().forGetter(Watcher::invoke),
            ApiField.field("field", ApiTypes.string(1, 256)).optional().forGetter(Watcher::field),
            ApiField.field("emitAs", eventType()).required().forGetter(Watcher::emitAs),
            ApiField.field("everyMs", ApiTypes.integer(200, 60_000)).required().forGetter(Watcher::everyMs),
            ApiField.field("once", ApiTypes.bool()).required().forGetter(Watcher::once),
            ApiField.field("fired", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Watcher::fired),
            Watcher::new);

    public static final UnionType<EventsReply> EVENTS_REPLY = ApiTypes.taggedUnion("EventsReply", "op",
            ApiTypes.variant("emit", Emitted.class, ApiTypes.record(null,
                    ApiField.field("seq", ApiTypes.int64String(1)).required().forGetter(Emitted::seq),
                    ApiField.field("type", eventType()).required().forGetter(Emitted::type),
                    Emitted::new)),
            ApiTypes.variant("watch", Watching.class, ApiTypes.record(null,
                    ApiField.field("id", WATCHER_ID).required().forGetter(Watching::id),
                    ApiField.field("invoke", ApiTypes.string(1, 128)).required().forGetter(Watching::invoke),
                    ApiField.field("emitAs", eventType()).required().forGetter(Watching::emitAs),
                    ApiField.field("everyMs", ApiTypes.integer(200, 60_000)).required().forGetter(Watching::everyMs),
                    Watching::new)),
            ApiTypes.variant("unwatch", Unwatched.class, ApiTypes.record(null,
                    ApiField.field("removed", ApiTypes.bool()).describe("False when no such watcher was alive.")
                            .required().forGetter(Unwatched::removed),
                    Unwatched::new)),
            ApiTypes.variant("list", Watchers.class, ApiTypes.record(null,
                    ApiField.field("watchers", ApiTypes.list(WATCHER, MAX_WATCHERS)).required().forGetter(Watchers::watchers),
                    ApiField.field("count", ApiTypes.integer(0, MAX_WATCHERS)).required().forGetter(Watchers::count),
                    Watchers::new)));

    public static final MethodSpec<EventsOp, EventsReply> EVENTS = MethodSpec.of("mc.events", EVENTS_PARAMS, EVENTS_REPLY,
                    "The event stream's server side. emit {type, data?, pos?} injects an event for every reader and "
                            + "subscriber. watch {invoke, params?, field?, value?|above?|below?, emitAs?, everyMs?, once?} "
                            + "polls a pollable method and emits emitAs (default condition.met, data {watch, invoke, "
                            + "field?, value?}) each time the test turns true, e.g. mc.bot.status field "
                            + "status.busy value false. At most " + MAX_WATCHERS + " watchers; a method that is not "
                            + "pollable is invalid_params at /invoke. unwatch {id}; list. Live delivery: the WebSocket's "
                            + "mc.events.subscribe or the MCP GET /mcp event stream; replay: mc.observe.eventsSince.")
            .effects(MethodSpec.Effect.SNAPSHOT_READ, MethodSpec.Effect.CONFIG_WRITE);
}
