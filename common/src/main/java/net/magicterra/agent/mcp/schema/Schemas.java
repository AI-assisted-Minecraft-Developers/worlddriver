package net.magicterra.agent.mcp.schema;

import java.util.List;
import java.util.Map;

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
    /**
     * A union of JSON-Schema primitive types (renders {@code "type":[...]}) — for a param
     * whose consuming route code branches on runtime type over MORE THAN ONE type but not
     * literally anything (see {@link #any()} for that). Verify the accepted set against the
     * consuming code, not the intent — see gap#67-④ (a typeless field gets JSON-stringified
     * by at least one MCP client, since it has no type to preserve across the wire).
     * Member names: {@code "integer"|"number"|"string"|"boolean"|"object"|"array"|"null"}.
     */
    public static Schema.Union union(String... types) { return new Schema.Union(List.of(types)); }

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
    public static ToolSchema tool(String name, String description, Schema schema) {
        return new ToolSchema(name, description, schema, null, null, false);
    }
    /** Read-only tool from a typed schema. */
    public static ToolSchema roTool(String name, String description, Schema schema) {
        return new ToolSchema(name, description, schema, readOnly(), null, false);
    }
    /** Destructive tool from a typed schema. */
    public static ToolSchema wrTool(String name, String description, Schema schema) {
        return new ToolSchema(name, description, schema, destructive(), null, false);
    }
    /** Read-only + _meta tool from a typed schema. */
    public static ToolSchema roTool(String name, String description, Schema schema, Map<String, Object> meta) {
        return new ToolSchema(name, description, schema, readOnly(), meta, false);
    }

    // ----- Tool annotations. -----

    /** Read-only annotation. Per MCP spec these are hints, not guarantees, but
     *  let clients render confirmations only on destructive tools. */
    public static Map<String, Object> readOnly() {
        return Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true);
    }
    /** Destructive (state-mutating) tool annotation. */
    public static Map<String, Object> destructive() {
        return Map.of("readOnlyHint", false, "destructiveHint", true, "idempotentHint", false);
    }
}
