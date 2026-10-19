package net.magicterra.worlddriver.api.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.protocol.RejectedNumber;

/** Any JSON value, or any JSON object, kept as given; see {@link ApiTypes#json} and {@link ApiTypes#jsonObject}. */
final class JsonType extends ApiType<JsonValue> {
    private final boolean objectOnly;

    JsonType(boolean objectOnly) { this.objectOnly = objectOnly; }

    @Override protected JsonValue read(Object value, DecodeContext at) {
        if (objectOnly && !(value instanceof Map<?, ?>)) return at.mismatch(expected(), value);
        int before = at.issues().size();
        scan(value, at, 0);
        if (at.issues().size() != before) return null;
        try {
            return JsonValue.of(value);
        } catch (IllegalArgumentException e) {
            return at.fail(ApiIssue.Code.INVALID_VALUE, e.getMessage());
        }
    }

    /** Over-budget numbers are reported where they sit, not as the whole payload. */
    private static void scan(Object v, DecodeContext at, int depth) {
        if (v instanceof RejectedNumber r) {
            at.fail(ApiIssue.Code.INVALID_VALUE, "number " + r.token() + " is " + r.reason());
        } else if (v instanceof Map<?, ?> m && depth < 64) {
            for (Map.Entry<?, ?> e : m.entrySet()) scan(e.getValue(), at.field(String.valueOf(e.getKey())), depth + 1);
        } else if (v instanceof List<?> l && depth < 64) {
            for (int i = 0; i < l.size(); i++) scan(l.get(i), at.index(i), depth + 1);
        }
    }

    @Override protected Object write(JsonValue value) { return value.tree(); }

    /**
     * Every non-null kind spelled out rather than an empty schema: an MCP client was seen to read a
     * typeless field as licence to send the value JSON-stringified.
     */
    @Override protected Map<String, Object> schemaBody() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", objectOnly ? "object" : List.of("object", "array", "string", "number", "boolean"));
        return s;
    }

    @Override public String ts(TsContext ts) {
        String json = ts.declare("Json", JsonType.class, () ->
                "type Json = null | boolean | number | string | Json[] | { [key: string]: Json };");
        return objectOnly ? "{ [key: string]: " + json + " }" : json;
    }

    @Override public String expected() { return objectOnly ? "a JSON object" : "a JSON value"; }
}
