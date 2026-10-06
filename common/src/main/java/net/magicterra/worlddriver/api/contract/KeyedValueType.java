package net.magicterra.worlddriver.api.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A {@code {key, value}} pair whose value type is chosen by its key: a union discriminated on
 * {@code key} whose variants share one Java class, which {@link UnionType} cannot express. The key is
 * checked first, so an unknown key is one issue at {@code /key} rather than a cascade under {@code value}.
 */
public final class KeyedValueType extends ApiType<KeyedValueType.Entry> {

    /** One pair; {@code value} is what the key's type decodes to. */
    public record Entry(String key, Object value) {
        public Entry {
            Objects.requireNonNull(key);
            Objects.requireNonNull(value);
        }
    }

    private static final String KEY = "key", VALUE = "value";

    private final String name;
    private final Map<String, ApiType<?>> types;
    private final Map<String, String> docs;

    KeyedValueType(String name, Map<String, ApiType<?>> types, Map<String, String> docs) {
        this.name = Objects.requireNonNull(name);
        if (types.isEmpty()) throw new IllegalArgumentException(name + " has no keys");
        this.types = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(types));
        this.docs = Map.copyOf(docs);
    }

    public Map<String, ApiType<?>> types() { return types; }

    @Override public String expected() { return "a " + name + " object {key, value} with a known key"; }

    @Override
    protected Entry read(Object value, DecodeContext at) {
        if (!(value instanceof Map<?, ?> map)) return at.mismatch(expected(), value);
        DecodeContext keyAt = at.field(KEY);
        if (!map.containsKey(KEY)) return keyAt.fail(ApiIssue.Code.REQUIRED, "missing; a " + name + " needs a key");
        if (!(map.get(KEY) instanceof String key)) return keyAt.mismatch("a string", map.get(KEY));
        ApiType<?> type = types.get(key);
        if (type == null) return keyAt.fail(ApiIssue.Code.INVALID_VALUE, "unknown key \"" + key + "\"");
        for (Object k : map.keySet()) {
            String member = String.valueOf(k);
            if (!member.equals(KEY) && !member.equals(VALUE)) {
                at.field(member).fail(ApiIssue.Code.UNKNOWN_FIELD, "unexpected key '" + member + "'; a " + name + " has key and value");
            }
        }
        DecodeContext valueAt = at.field(VALUE);
        if (!map.containsKey(VALUE) || map.get(VALUE) == null) {
            return valueAt.fail(ApiIssue.Code.REQUIRED, "missing; " + key + " takes " + type.expected());
        }
        int before = at.issues().size();
        Object v = type.read(map.get(VALUE), valueAt);
        return at.issues().size() == before ? new Entry(key, v) : null;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Object write(Entry entry) {
        ApiType type = types.get(entry.key());
        if (type == null) throw new IllegalStateException(entry.key() + " is no key of " + name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY, entry.key());
        out.put(VALUE, type.write(entry.value()));
        return out;
    }

    @Override
    protected Map<String, Object> schemaBody() {
        List<Object> oneOf = new ArrayList<>();
        types.forEach((key, type) -> {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(KEY, Map.of("const", key));
            props.put(VALUE, type.schema());
            Map<String, Object> variant = new LinkedHashMap<>();
            if (docs.containsKey(key)) variant.put("description", docs.get(key));
            variant.put("type", "object");
            variant.put("properties", props);
            variant.put("required", List.of(KEY, VALUE));
            variant.put("additionalProperties", false);
            oneOf.add(variant);
        });
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("oneOf", oneOf);
        return s;
    }

    @Override
    public String ts(TsContext ts) {
        return ts.declare(name, this, () -> {
            StringJoiner j = new StringJoiner("\n    | ");
            types.forEach((key, type) -> j.add("{ key: " + TsContext.literal(key) + "; value: " + type.ts(ts) + " }"));
            return "type " + name + " =\n    | " + j + ";";
        });
    }
}
