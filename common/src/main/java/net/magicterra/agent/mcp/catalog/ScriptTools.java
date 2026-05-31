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
                ))
        );
    }
}
