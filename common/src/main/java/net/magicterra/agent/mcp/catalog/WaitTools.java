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
                "Returns {events, timedOut, cursor, ms}. timeoutMs default 5000, max 120000. " +
                "background:true returns {waitId} at once; result later via mc.wait.result or a wait.done event.",
                object()
                    .req("cursor", integer().min(0))
                    .prop("types", array(string()))
                    .prop("limit", integer(1, 256))
                    .prop("timeoutMs", integer(100, 120000))
                    .prop("pollMs", integer(50, 2000))
                    .prop("background", bool().desc(
                        "Run non-blocking: return {waitId} immediately; fetch via mc.wait.result."))),

            roTool("mc.wait.worldReady",
                "Block until the client finishes loading into a world (player + world both ready). " +
                "Use after 'Play Selected World' so subsequent calls don't fire on the loading screen. " +
                "Does NOT require an attached server. " +
                "Returns {ready, ms, info} (info = last screenInfo). timeoutMs default 30000. " +
                "background:true returns {waitId} at once (fetch via mc.wait.result / wait.done event).",
                object()
                    .prop("timeoutMs", integer(100, 120000))
                    .prop("pollMs", integer(50, 2000))
                    .prop("background", bool().desc(
                        "Run non-blocking: return {waitId} immediately; fetch via mc.wait.result."))),

            roTool("mc.wait.condition",
                "Poll-until-truthy. Each iteration calls invoke(params), walks dotted field into " +
                "the result, stops when truthy (or deep-equals `value` when given). " +
                "E.g. wait for furnace output: {invoke:'mc.observe.container', " +
                "params:{pos:...}, field:'slots.2.count'}. " +
                "Returns {satisfied, value, ms}. timeoutMs default 30000, max 120000. " +
                "IN LIVE PLAY use background:true for long phase/time waits — a blocking wait freezes " +
                "the agent and blinds it to threat/hurt/death events for the whole budget. background " +
                "returns {waitId} immediately; the result arrives via mc.wait.result{waitId} or a wait.done event.",
                object()
                    .req("invoke", string().desc(
                        "Method name to call each poll, e.g. 'mc.observe.container'."))
                    .prop("params", object().desc("Params object passed to the invoked tool."))
                    .prop("field", string().desc(
                        "Dotted path into the result. Omit to test whole result for truthiness."))
                    .prop("value", any().desc(
                        "Optional target value for deep-equal comparison. Omit for truthy check."))
                    .prop("timeoutMs", integer(100, 120000))
                    .prop("pollMs", integer(50, 5000))
                    .prop("background", bool().desc(
                        "Run non-blocking: return {waitId} immediately; fetch via mc.wait.result."))),

            roTool("mc.wait.result",
                "Fetch the result of a background wait (any wait.* started with background:true). " +
                "Returns {pending:true} until it finishes, then the full original result " +
                "(satisfied/timedOut/value/events …). Consumes the result unless consume:false. " +
                "Alternatively, watch the event stream for a wait.done event carrying {waitId, kind, …}.",
                object()
                    .req("waitId", string().desc("The waitId returned by the background wait."))
                    .prop("consume", bool().desc(
                        "Remove the stored result after reading (default true).")))
        );
    }
}
