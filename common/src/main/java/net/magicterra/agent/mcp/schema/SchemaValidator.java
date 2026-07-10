package net.magicterra.agent.mcp.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates route params against the SAME typed {@link Schema} the MCP catalog
 * advertises — single source, so runtime enforcement can never drift from
 * tools/list. Wired into AgentApi.route() via the api-layer ParamsValidator seam.
 *
 * <p>Semantics: type mismatch, missing required, enum violation, min/max bounds
 * and unknown keys (unless the object declares additionalProperties(true)) all
 * reject. Integers tolerate integral doubles (JSON decoders hand 3 over as 3.0);
 * no other coercion. An explicit null value counts as absent. All violations
 * aggregate into ONE IllegalArgumentException so a caller can fix the whole
 * request in a single round-trip.
 */
public final class SchemaValidator {
    private SchemaValidator() {}

    public static void validate(String method, Schema schema, Map<String, Object> params) {
        List<String> violations = new ArrayList<>();
        check(schema, params, "", violations);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid params for " + method + ": " + String.join("; ", violations));
        }
    }

    private static void check(Schema schema, Object value, String path, List<String> out) {
        if (value == null) return;   // explicit null == absent; required-ness is checked by the enclosing Obj
        switch (schema) {
            case Schema.Any any -> { /* accepts anything */ }
            case Schema.Obj o -> checkObj(o, value, path, out);
            case Schema.Str s -> {
                if (!(value instanceof String str)) { out.add(typeErr(path, "string", value)); return; }
                if (s.enumValues() != null && !s.enumValues().contains(str)) {
                    out.add(at(path) + "must be one of " + s.enumValues() + ", got '" + str + "'");
                }
            }
            case Schema.Int i -> {
                Long v = integralOf(value);
                if (v == null) { out.add(typeErr(path, "integer", value)); return; }
                if (i.minimum() != null && v < i.minimum()) out.add(at(path) + "must be >= " + i.minimum() + ", got " + v);
                if (i.maximum() != null && v > i.maximum()) out.add(at(path) + "must be <= " + i.maximum() + ", got " + v);
            }
            case Schema.Num n -> {
                if (!(value instanceof Number num)) { out.add(typeErr(path, "number", value)); return; }
                double d = num.doubleValue();
                if (n.minimum() != null && d < n.minimum()) out.add(at(path) + "must be >= " + n.minimum() + ", got " + num);
                if (n.maximum() != null && d > n.maximum()) out.add(at(path) + "must be <= " + n.maximum() + ", got " + num);
            }
            case Schema.Bool b -> {
                if (!(value instanceof Boolean)) out.add(typeErr(path, "boolean", value));
            }
            case Schema.Arr arr -> {
                if (!(value instanceof List<?> list)) { out.add(typeErr(path, "array", value)); return; }
                for (int idx = 0; idx < list.size(); idx++) {
                    check(arr.items(), list.get(idx), path + "[" + idx + "]", out);
                }
            }
        }
    }

    private static void checkObj(Schema.Obj o, Object value, String path, List<String> out) {
        if (!(value instanceof Map<?, ?> map)) { out.add(typeErr(path, "object", value)); return; }
        for (String req : o.required()) {
            if (map.get(req) == null) {
                Schema prop = o.properties().get(req);
                out.add(at(path) + "missing required '" + req + "'"
                        + (prop != null ? " (" + prop.typeName() + ")" : ""));
            }
        }
        boolean open = Boolean.TRUE.equals(o.additionalProperties());
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Schema prop = o.properties().get(key);
            if (prop == null) {
                if (!open) out.add(at(path) + "unexpected key '" + key + "'");
                continue;
            }
            check(prop, e.getValue(), path.isEmpty() ? key : path + "." + key, out);
        }
    }

    /** 3 / 3L / 3.0 → 3; 3.5 / "3" → null (not an integer). */
    private static Long integralOf(Object v) {
        if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            long l = (long) d;
            return (d == l) ? l : null;
        }
        return null;
    }

    private static String typeErr(String path, String want, Object got) {
        return at(path) + "must be " + want + ", got " + kind(got);
    }

    private static String at(String path) { return path.isEmpty() ? "" : "'" + path + "' "; }

    private static String kind(Object v) {
        if (v instanceof String s) return "string ('" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "')";
        if (v instanceof Number n) return "number (" + n + ")";
        if (v instanceof Boolean b) return String.valueOf(b);
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        return v.getClass().getSimpleName();
    }
}
