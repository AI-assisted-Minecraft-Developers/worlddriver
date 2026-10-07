package net.magicterra.worlddriver.api.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An object whose members are drawn from a fixed key table, each member's value typed by its key:
 * {@code {autoEat: true, autoEatFoodThreshold: 14}}. Every member is optional, so a table of a hundred
 * keys is one object type rather than a hundred-field record. Decoded as entries in the order sent.
 */
public final class KeyedValueType extends ApiType<List<KeyedValueType.Entry>> {

    /** One member; {@code value} is what the key's type decodes to. */
    public record Entry(String key, Object value) {
        public Entry {
            Objects.requireNonNull(key);
            Objects.requireNonNull(value);
        }
    }

    private final String name;
    private final Map<String, ApiType<?>> types;
    private final Map<String, String> docs;
    private final int minEntries;
    /** Shared with {@link #atLeast} copies: the TypeScript declaration is the same one. */
    private final Object tsOwner;

    KeyedValueType(String name, Map<String, ApiType<?>> types, Map<String, String> docs) {
        this(name, Collections.unmodifiableMap(new LinkedHashMap<>(types)), Map.copyOf(docs), 0, new Object());
        if (types.isEmpty()) throw new IllegalArgumentException(name + " has no keys");
    }

    private KeyedValueType(String name, Map<String, ApiType<?>> types, Map<String, String> docs, int minEntries, Object tsOwner) {
        this.name = Objects.requireNonNull(name);
        this.types = types;
        this.docs = docs;
        this.minEntries = minEntries;
        this.tsOwner = tsOwner;
    }

    public Map<String, ApiType<?>> types() { return types; }

    /** The same key table, refusing an object with fewer than {@code min} members. */
    public KeyedValueType atLeast(int min) { return new KeyedValueType(name, types, docs, min, tsOwner); }

    @Override public String expected() {
        return "a " + name + " object" + (minEntries > 0 ? " with at least " + minEntries + " known key" : " of known keys");
    }

    @Override
    protected List<Entry> read(Object value, DecodeContext at) {
        if (!(value instanceof Map<?, ?> map)) return at.mismatch(expected(), value);
        if (map.size() < minEntries) return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got none");
        int before = at.issues().size();
        List<Entry> out = new ArrayList<>(map.size());
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            DecodeContext here = at.field(key);
            ApiType<?> type = types.get(key);
            if (type == null) { here.fail(ApiIssue.Code.UNKNOWN_FIELD, "unknown key \"" + key + "\""); continue; }
            if (e.getValue() == null) { here.fail(ApiIssue.Code.TYPE_MISMATCH, "null is not a value here; " + key + " takes " + type.expected()); continue; }
            Object v = type.decode(e.getValue(), here);
            if (v != null) out.add(new Entry(key, v));
        }
        return at.issues().size() == before ? Collections.unmodifiableList(out) : null;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Object write(List<Entry> entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Entry entry : entries) {
            ApiType type = types.get(entry.key());
            // An unknown key is written as it came, so read() reports it at its own path: a request
            // error for validate(), a contract violation for encode().
            out.put(entry.key(), type == null ? entry.value() : type.write(entry.value()));
        }
        return out;
    }

    @Override
    protected Map<String, Object> schemaBody() {
        Map<String, Object> props = new LinkedHashMap<>();
        types.forEach((key, type) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            if (docs.containsKey(key)) p.put("description", docs.get(key));
            p.putAll(type.schema());
            props.put(key, p);
        });
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("additionalProperties", false);
        if (minEntries > 0) s.put("minProperties", minEntries);
        return s;
    }

    @Override
    public String ts(TsContext ts) {
        return ts.declare(name, tsOwner, () -> {
            StringBuilder b = new StringBuilder("interface " + name + " {");
            types.forEach((key, type) -> {
                b.append("\n    ");
                if (docs.containsKey(key)) b.append("/** ").append(docs.get(key).replace("*/", "* /")).append(" */\n    ");
                b.append(TsContext.key(key)).append("?: ").append(type.ts(ts)).append(';');
            });
            return b.append("\n}").toString();
        });
    }
}
