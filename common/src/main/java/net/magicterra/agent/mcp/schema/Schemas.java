package net.magicterra.agent.mcp.schema;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import com.mojang.serialization.JavaOps;

/**
 * Shared JSON-schema fragments and tool-entry builders for the MCP tool
 * catalog. Extracted from {@code ToolCatalog} so the per-category catalog
 * classes ({@code mcp.catalog.*}) can reuse them without duplicating the
 * boilerplate. Pure functions, no state.
 */
public final class Schemas {
    private Schemas() {}

    // ----- Type-safe schema factories (preferred). See Schema for the DSL. -----

    /** An object schema; add properties with {@code .prop(name, schema)} / {@code .req(...)}. */
    public static Schema.Obj object() { return new Schema.Obj(); }
    /** A plain string schema. */
    public static Schema.Str string() { return new Schema.Str(); }
    /** A string constrained to an enum. */
    public static Schema.Str stringEnum(String... values) { return new Schema.Str().enumOf(values); }
    /** An unbounded integer schema. */
    public static Schema.Int integer() { return new Schema.Int(); }
    /** An integer bounded to {@code [lo,hi]}. */
    public static Schema.Int integer(int lo, int hi) { return new Schema.Int().range(lo, hi); }
    /** An unbounded number (double) schema. */
    public static Schema.Num number() { return new Schema.Num(); }
    /** A number bounded to {@code [lo,hi]}. */
    public static Schema.Num number(double lo, double hi) { return new Schema.Num().range(lo, hi); }
    /** A boolean schema. */
    public static Schema.Bool bool() { return new Schema.Bool(); }
    /** An array over a typed item schema. */
    public static Schema.Arr array(Schema items) { return new Schema.Arr(items); }
    /** A typeless "accept anything" schema (no {@code type}) — for free-form values. */
    public static Schema.Any any() { return new Schema.Any(); }

    /** Typed {x,y,z} integer block position. */
    public static Schema.Obj pos() {
        return object().req("x", integer()).req("y", integer()).req("z", integer());
    }
    /** Typed {x,z} integer column (Y-agnostic goal). */
    public static Schema.Obj xz() {
        return object().req("x", integer()).req("z", integer());
    }
    /** Typed {@code awaitMs} option for async bot tools. */
    public static Schema.Int awaitMs() {
        return integer(1, 600_000).desc(
            "If set, block until the bot slot goes idle (or this many ms elapse), then return "
            + "the final status snapshot. Omit for fire-and-forget.");
    }
    /** A parameterless tool's input schema. */
    public static Schema.Obj emptyObject() { return object(); }
    /** Typed {@code returnEvents} option for action tools. */
    public static Schema.Bool returnEvents() {
        return bool().desc(
            "If true, the response includes an `events` array of agent events emitted during "
            + "this call. Saves a separate mc.observe.cursor + mc.observe.eventsSince pair.");
    }

    // ----- Schema-based tool builders. -----

    /**
     * Render a typed {@link Schema} to its JSON-Schema map by running Minecraft's
     * built-in {@link Schema#CODEC} through {@link JavaOps} (plain Java objects — no
     * JSON-string round-trip). {@code optionalFieldOf} already dropped absent fields,
     * so the result is clean.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> render(Schema schema) {
        Object encoded = Schema.CODEC.encodeStart(JavaOps.INSTANCE, schema)
                .getOrThrow(e -> new IllegalStateException("schema encode failed: " + e));
        return (Map<String, Object>) encoded;
    }

    /** Plain tool from a typed schema. */
    public static Map<String, Object> tool(String name, String description, Schema schema) {
        return toolFull(name, description, render(schema), null, null);
    }
    /** Read-only tool from a typed schema. */
    public static Map<String, Object> roTool(String name, String description, Schema schema) {
        return toolFull(name, description, render(schema), readOnly(), null);
    }
    /** Destructive tool from a typed schema. */
    public static Map<String, Object> wrTool(String name, String description, Schema schema) {
        return toolFull(name, description, render(schema), destructive(), null);
    }
    /** Read-only + _meta tool from a typed schema. */
    public static Map<String, Object> roTool(String name, String description, Schema schema, Map<String, Object> meta) {
        return toolFull(name, description, render(schema), readOnly(), meta);
    }

    // ----- Tool annotations + the master tool builder. -----

    /** Read-only annotation. Per MCP spec these are hints, not guarantees, but
     *  let clients render confirmations only on destructive tools. */
    public static Map<String, Object> readOnly() {
        return Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true);
    }
    /** Destructive (state-mutating) tool annotation. */
    public static Map<String, Object> destructive() {
        return Map.of("readOnlyHint", false, "destructiveHint", true, "idempotentHint", false);
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
}
