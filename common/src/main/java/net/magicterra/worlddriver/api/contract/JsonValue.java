package net.magicterra.worlddriver.api.contract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An open JSON value: a payload whose shape belongs to the caller (a skill's arguments, a script's
 * result, a custom event's data), not to this API. Deeply immutable, so a DTO holding one stays
 * immutable. Numbers keep the parser's exact types; an over-budget number never becomes one.
 */
public final class JsonValue {
    public static final JsonValue NULL = new JsonValue(null);

    private final Object tree;

    private JsonValue(Object tree) { this.tree = tree; }

    /** An immutable copy of {@code tree}; throws {@link IllegalArgumentException} for anything that is not JSON. */
    public static JsonValue of(Object tree) {
        return tree == null ? NULL : new JsonValue(freeze(tree, 0));
    }

    /** The value tree: Map, List, String, Boolean, a Number, or null. Unmodifiable throughout. */
    public Object tree() { return tree; }

    public boolean isNull() { return tree == null; }

    private static Object freeze(Object v, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON value nested deeper than 64");
        if (v == null || v instanceof String || v instanceof Boolean) return v;
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                || v instanceof BigInteger || v instanceof BigDecimal) return v;
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) throw new IllegalArgumentException("non-finite number " + d);
            return v;
        }
        if (v instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) throw new IllegalArgumentException("non-finite number " + f);
            return f.doubleValue();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) throw new IllegalArgumentException("object key " + e.getKey() + " is not a string");
                out.put(k, freeze(e.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(out);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) out.add(freeze(e, depth + 1));
            return Collections.unmodifiableList(out);
        }
        throw new IllegalArgumentException(DecodeContext.describe(v) + " is not a JSON value");
    }

    @Override public boolean equals(Object o) { return o instanceof JsonValue j && Objects.equals(tree, j.tree); }
    @Override public int hashCode() { return Objects.hashCode(tree); }
    @Override public String toString() { return String.valueOf(tree); }
}
