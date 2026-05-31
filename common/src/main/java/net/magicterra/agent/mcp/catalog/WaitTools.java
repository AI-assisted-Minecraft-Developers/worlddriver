package net.magicterra.agent.mcp.catalog;

import java.util.List;
import java.util.Map;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/** {@code mc.wait.*} (long-poll) catalog entries. See {@code ToolCatalog} for ordering. */
public final class WaitTools {
    private WaitTools() {}

    public static List<Map<String, Object>> tools() {
        return List.of(
            roTool("mc.wait.event",
                "Long-poll for events with seq > cursor matching `types`. Returns when ≥1 event " +
                "arrives, or {timedOut:true, events:[], cursor:N} on deadline. " +
                "cursor in response = last event's seq; chain it as the next call's cursor. " +
                "Returns {events, timedOut, cursor, ms}. timeoutMs default 5000, max 120000.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "cursor", Map.of("type", "integer", "minimum", 0),
                        "types", Map.of("type", "array", "items", Map.of("type", "string")),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 256),
                        "timeoutMs", Map.of("type", "integer", "minimum", 100, "maximum", 120000),
                        "pollMs", Map.of("type", "integer", "minimum", 50, "maximum", 2000)
                    ),
                    "required", List.of("cursor")
                )),

            roTool("mc.wait.worldReady",
                "Block until the client finishes loading into a world (player + world both ready). " +
                "Use after 'Play Selected World' so subsequent calls don't fire on the loading screen. " +
                "Does NOT require an attached server. " +
                "Returns {ready, ms, info} (info = last screenInfo). timeoutMs default 30000.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "timeoutMs", Map.of("type", "integer", "minimum", 100, "maximum", 120000),
                        "pollMs", Map.of("type", "integer", "minimum", 50, "maximum", 2000)
                    )
                )),

            roTool("mc.wait.condition",
                "Poll-until-truthy. Each iteration calls invoke(params), walks dotted field into " +
                "the result, stops when truthy (or deep-equals `value` when given). " +
                "E.g. wait for furnace output: {invoke:'mc.observe.container', " +
                "params:{pos:...}, field:'slots.2.count'}. " +
                "Returns {satisfied, value, ms}. timeoutMs default 30000, max 120000.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "invoke", Map.of("type", "string",
                            "description", "Method name to call each poll, e.g. 'mc.observe.container'."),
                        "params", Map.of("type", "object",
                            "description", "Params object passed to the invoked tool."),
                        "field", Map.of("type", "string",
                            "description", "Dotted path into the result. Omit to test whole result for truthiness."),
                        "value", Map.of("description",
                            "Optional target value for deep-equal comparison. Omit for truthy check."),
                        "timeoutMs", Map.of("type", "integer", "minimum", 100, "maximum", 120000),
                        "pollMs", Map.of("type", "integer", "minimum", 50, "maximum", 5000)
                    ),
                    "required", List.of("invoke")
                ))
        );
    }
}
