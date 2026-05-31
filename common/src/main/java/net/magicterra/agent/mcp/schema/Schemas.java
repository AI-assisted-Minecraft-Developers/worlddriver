package net.magicterra.agent.mcp.schema;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Shared JSON-schema fragments and tool-entry builders for the MCP tool
 * catalog. Extracted from {@code ToolCatalog} so the per-category catalog
 * classes ({@code mcp.catalog.*}) can reuse them without duplicating the
 * boilerplate. Pure functions, no state.
 */
public final class Schemas {
    private Schemas() {}

    /** A {x,y,z} integer triple. Reused by several tools. */
    public static Map<String, Object> blockPosSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "x", Map.of("type", "integer"),
                "y", Map.of("type", "integer"),
                "z", Map.of("type", "integer")
            ),
            "required", List.of("x", "y", "z")
        );
    }

    /** An {x,z} integer pair (Y-agnostic column target for bot.goto). */
    public static Map<String, Object> xzPosSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "x", Map.of("type", "integer"),
                "z", Map.of("type", "integer")
            ),
            "required", List.of("x", "z")
        );
    }

    /** Shared schema for the {@code awaitMs} option on async bot tools. */
    public static Map<String, Object> awaitMsSchema() {
        return Map.of(
            "type", "integer",
            "minimum", 1,
            "maximum", 600000,
            "description", "If set, block until the bot slot goes idle (or this many ms elapse), " +
                "then return the final status snapshot. Omit for fire-and-forget."
        );
    }

    /** Shared schema for the {@code returnEvents} option on action tools. */
    public static Map<String, Object> returnEventsSchema() {
        return Map.of(
            "type", "boolean",
            "description", "If true, the response includes an `events` array of agent events " +
                "emitted during this call. Saves a separate mc.observe.cursor + mc.observe.eventsSince pair."
        );
    }

    /** Plain tool — no annotations, no meta. Kept so call sites that don't care
     *  about safety hints stay compact. */
    public static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        return toolFull(name, description, schema, null, null);
    }

    /** Read-only annotation. Per MCP spec these are hints, not guarantees, but
     *  let clients render confirmations only on destructive tools. */
    public static Map<String, Object> readOnly() {
        return Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true);
    }
    /** Destructive (state-mutating) tool annotation. */
    public static Map<String, Object> destructive() {
        return Map.of("readOnlyHint", false, "destructiveHint", true, "idempotentHint", false);
    }

    /** Read-only + annotations variant (most common shape). */
    public static Map<String, Object> roTool(String name, String description, Map<String, Object> schema) {
        return toolFull(name, description, schema, readOnly(), null);
    }
    /** Destructive + annotations variant. */
    public static Map<String, Object> wrTool(String name, String description, Map<String, Object> schema) {
        return toolFull(name, description, schema, destructive(), null);
    }
    /** Read-only + meta (used by screenshot, which is read-only but carries _meta). */
    public static Map<String, Object> roTool(String name, String description,
                                              Map<String, Object> schema, Map<String, Object> meta) {
        return toolFull(name, description, schema, readOnly(), meta);
    }

    /**
     * Master tool builder. {@code annotations} maps to MCP's
     * {@code Tool.annotations} (readOnlyHint / destructiveHint / idempotentHint /
     * openWorldHint), {@code meta} maps to {@code _meta}. Keys are namespaced by
     * reverse-DNS prefix per the MCP spec; clients ignore prefixes they don't
     * recognize, so the Anthropic-specific {@code anthropic/maxResultSizeChars}
     * on screenshot stays platform-agnostic.
     */
    public static Map<String, Object> toolFull(String name, String description,
                                                Map<String, Object> schema,
                                                Map<String, Object> annotations,
                                                Map<String, Object> meta) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", schema);
        if (annotations != null) m.put("annotations", annotations);
        if (meta != null) m.put("_meta", meta);
        return m;
    }

    public static Map<String, Object> emptyObjectSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false
        );
    }
}
