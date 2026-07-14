package net.magicterra.agent.mcp.catalog;

import java.util.List;

import net.magicterra.agent.mcp.schema.ToolSchema;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/**
 * {@code mc.script.eval} catalog entry. Kept near the top of the catalog so it
 * is considered for compound flows. See {@code ToolCatalog} for ordering.
 */
public final class ScriptTools {
    private ScriptTools() {}

    public static List<ToolSchema> tools() {
        return List.of(
            tool("mc.script.eval",
                "**Prefer this when a task would otherwise need ≥3 separate tool calls** " +
                "(e.g. observe→decide→act, cursor→action→eventsSince, scan→pick→place). " +
                "Runs a short JavaScript snippet against the in-process Agent API. Inside the snippet:\n" +
                "  - Agent.invoke(method, params): call any other tool by name, e.g. " +
                "Agent.invoke('mc.query', {q:'blocks', center:{x:0,y:200,z:0}, filter:{in_radius:5}})\n" +
                "  - Agent.system / Agent.observe / Agent.action / Agent.query / Agent.client: typed helpers\n" +
                "    (e.g. Agent.observe.player(), Agent.action.fill(from,to,type), Agent.action.placeMany([...]),\n" +
                "     Agent.client.screen.info(), Agent.client.screenshot({maxWidth:640,format:'jpeg'}))\n" +
                "  - console.log(x): append to the returned log array (objects auto-JSON-stringified)\n" +
                "Last expression = result. Fresh scope per call. Sandboxed (no file/network/reflection); " +
                "bounded by timeoutMs (default 3000, max 30000). " +
                "Returns {result, error, log, ms}.",
                object()
                    .req("source", string()
                        .desc("JavaScript source. Last expression is the result; use console.log for diagnostics."))
                    .prop("timeoutMs", integer(1, 30000)
                        .desc("Wall-clock budget in milliseconds. Defaults to 3000 if omitted."))),

            wrTool("mc.skill",
                "Persistent skill library (Phase H / Voyager): write a reusable JS skill once, " +
                "save it by name, then list/run it across sessions — the building block for " +
                "self-growing skills. A skill is an ordinary sandbox script (orchestrates " +
                "Agent.invoke like mc.script.eval) that reads its call args from an injected SKILL " +
                "global; running one goes through the same 30s-capped evaluator. `op` selects the " +
                "action: save {name,source} (syntax-checked before it's persisted — a skill that " +
                "doesn't parse is rejected); list (→ {skills:[{name,bytes}]}); get {name} (→ source); " +
                "run {name, args?} (→ {result, error, log, ms, skill}); delete {name}. Names are " +
                "[a-z][a-z0-9_]*. Skills live under config/agent_driver/scripts/skills/.",
                object()
                    .prop("op", stringEnum("save", "list", "get", "run", "delete")
                        .desc("Which action (default list)."))
                    .prop("name", string()
                        .desc("Skill name [a-z][a-z0-9_]* (for save/get/run/delete)."))
                    .prop("source", string()
                        .desc("JS source (for save). Last expression is the result; reads args from SKILL."))
                    .prop("args", union("object", "array", "string", "number", "boolean")
                        .desc("Args passed to the skill as the SKILL global (for run). Any JSON "
                            + "value — object, array, or scalar. Optional."))
                    .prop("timeoutMs", integer(1, 30000)
                        .desc("Run budget ms (for run; default 3000)."))),

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
                    .prop("invoke", string().desc("Route name to poll (for watch)."))
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
