package net.magicterra.worlddriver.mcp.catalog;

import java.util.List;

import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

/** {@code mc.events} catalog entry. See {@code ToolCatalog} for ordering. */
public final class ScriptTools {
    private ScriptTools() {}

    public static List<ToolSchema> tools() {
        return List.of(
            wrTool("mc.events",
                "Driver→agent event channel — the server-side surface. Events (threats, " +
                "damage, death, chat, command results, custom conditions) are pushed live " +
                "to subscribers: connect a WebSocket and send {method:'mc.events.subscribe'}, " +
                "or open the SSE stream GET /mcp/events (optional ?types=a,b). Past events " +
                "are also replayable via mc.observe.eventsSince. This tool covers two ops: " +
                "emit {type, data?, pos?} injects a custom event into the stream; watch " +
                "{invoke, params?, field?, value?|above?|below?, emitAs?, everyMs?, once?} " +
                "registers a rising-edge watcher that polls a route and emits emitAs " +
                "(default 'condition.met') the first tick the predicate flips false→true — " +
                "e.g. watch mc.observe.player field 'health' below 6 for a low-health alert. " +
                "unwatch {id} cancels; list shows active watchers.",
                object()
                    .req("op", stringEnum("emit", "watch", "unwatch", "list")
                        .desc("Which action."))
                    .prop("type", string().desc("Event type (for emit)."))
                    .prop("data", union("object", "array", "string", "number", "boolean")
                        .desc("Event payload (for emit) — any JSON value; JsonCodec-encodes non-strings "
                            + "as-is (object/array/number/boolean), passes a string through raw. Optional."))
                    .prop("pos", pos()
                        .desc("Optional position attached to the emitted event (for emit)."))
                    .prop("invoke", string().desc("Route name to poll (for watch); an unknown route is "
                        + "refused when the watcher is registered."))
                    .prop("params", object().additionalProperties(true)
                        .desc("Params object passed through to the polled route (for watch); "
                            + "validated against that route's own schema on every poll."))
                    .prop("field", string().desc("Dotted path into the poll result (for watch)."))
                    .prop("value", union("object", "array", "string", "number", "boolean")
                        .desc("Predicate: fire when the field deep-equals this value (watch)."))
                    .prop("above", number().desc("Predicate: fire when the numeric field rises above this (watch)."))
                    .prop("below", number().desc("Predicate: fire when the numeric field drops below this (watch)."))
                    .prop("emitAs", string().desc("Event type to emit on the rising edge (watch; default condition.met)."))
                    .prop("everyMs", integer(200, 60000)
                        .desc("Poll interval ms (watch; default 1000)."))
                    .prop("once", bool().desc("Cancel after the first fire (watch)."))
                    .prop("id", integer().desc("Watcher id (for unwatch).")))
        );
    }
}
