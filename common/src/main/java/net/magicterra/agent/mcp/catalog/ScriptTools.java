package net.magicterra.agent.mcp.catalog;

import java.util.List;
import java.util.Map;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/**
 * {@code mc.script.eval} catalog entry. Kept near the top of the catalog so it
 * is considered for compound flows. See {@code ToolCatalog} for ordering.
 */
public final class ScriptTools {
    private ScriptTools() {}

    public static List<Map<String, Object>> tools() {
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
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "source", Map.of(
                            "type", "string",
                            "description", "JavaScript source. Last expression is the result; use console.log for diagnostics."
                        ),
                        "timeoutMs", Map.of(
                            "type", "integer",
                            "minimum", 1,
                            "maximum", 30000,
                            "description", "Wall-clock budget in milliseconds. Defaults to 3000 if omitted."
                        )
                    ),
                    "required", List.of("source")
                )),

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
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "op", Map.of("type", "string", "enum", List.of("save", "list", "get", "run", "delete"),
                            "description", "Which action (default list)."),
                        "name", Map.of("type", "string",
                            "description", "Skill name [a-z][a-z0-9_]* (for save/get/run/delete)."),
                        "source", Map.of("type", "string",
                            "description", "JS source (for save). Last expression is the result; reads args from SKILL."),
                        "args", Map.of("type", "object",
                            "description", "Args passed to the skill as the SKILL global (for run). Optional."),
                        "timeoutMs", Map.of("type", "integer", "minimum", 1, "maximum", 30000,
                            "description", "Run budget ms (for run; default 3000).")
                    )
                )),

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
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "op", Map.of("type", "string", "enum", List.of("emit", "watch", "unwatch", "list"),
                            "description", "Which action."),
                        "type", Map.of("type", "string", "description", "Event type (for emit)."),
                        "data", Map.of("description", "Event payload — string or object (for emit). Optional."),
                        "invoke", Map.of("type", "string", "description", "Route name to poll (for watch)."),
                        "field", Map.of("type", "string", "description", "Dotted path into the poll result (for watch)."),
                        "emitAs", Map.of("type", "string", "description", "Event type to emit on the rising edge (watch; default condition.met)."),
                        "everyMs", Map.of("type", "integer", "minimum", 200, "maximum", 60000,
                            "description", "Poll interval ms (watch; default 1000)."),
                        "once", Map.of("type", "boolean", "description", "Cancel after the first fire (watch)."),
                        "id", Map.of("type", "integer", "description", "Watcher id (for unwatch).")
                    ),
                    "required", List.of("op")
                ))
        );
    }
}
