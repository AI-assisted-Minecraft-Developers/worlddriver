package net.magicterra.worlddriver.api.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import net.magicterra.worlddriver.protocol.JsonCodec;

/**
 * An object whose members are drawn from a fixed key table, each member's value typed by its key:
 * {@code {autoEat: true, autoEatFoodThreshold: 14}}. Every member is optional, so a table of a hundred
 * keys is one object type rather than a hundred-field record. Decoded as entries in the order sent.
 *
 * <p>A key's default is looked up when the schema or the declaration is rendered, not when the type is
 * built: the values may live in a layer the contract cannot name, which installs the lookup at startup.
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
    private final Function<String, Object> defaults;
    private final int minEntries;
    /** Shared with {@link #atLeast} copies: the TypeScript declaration is the same one. */
    private final Object tsOwner;

    KeyedValueType(String name, Map<String, ApiType<?>> types, Map<String, String> docs, Function<String, Object> defaults) {
        this(name, Collections.unmodifiableMap(new LinkedHashMap<>(types)), Map.copyOf(docs), defaults, 0, new Object());
        if (types.isEmpty()) throw new IllegalArgumentException(name + " has no keys");
    }

    private KeyedValueType(String name, Map<String, ApiType<?>> types, Map<String, String> docs,
                           Function<String, Object> defaults, int minEntries, Object tsOwner) {
        this.name = Objects.requireNonNull(name);
        this.types = types;
        this.docs = docs;
        this.defaults = Objects.requireNonNull(defaults);
        this.minEntries = minEntries;
        this.tsOwner = tsOwner;
    }

    public Map<String, ApiType<?>> types() { return types; }

    /** The same key table, refusing an object with fewer than {@code min} members. */
    public KeyedValueType atLeast(int min) { return new KeyedValueType(name, types, docs, defaults, min, tsOwner); }

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
            out.put(entry.key(), type == null ? entry.value() : wireForm(type, entry.value()));
        }
        return out;
    }

    /**
     * {@code value} in wire form when it is the Java type {@code type} holds; anything else as it came, so
     * read() judges it as it would a request's. An entry's value is an Object, so a Java caller can hand an
     * Integer to a long setting, and the generic write would cast it before any check ran.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object wireForm(ApiType type, Object value) {
        try {
            return type.write(value);
        } catch (ClassCastException otherType) {
            return value;
        }
    }

    /** {@code key}'s default in wire form, or null when it has none. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object defaultOf(String key) {
        Object d = defaults.apply(key);
        return d == null ? null : ((ApiType) types.get(key)).write(d);
    }

    @Override
    protected Map<String, Object> schemaBody() {
        Map<String, Object> props = new LinkedHashMap<>();
        types.forEach((key, type) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            if (docs.containsKey(key)) p.put("description", docs.get(key));
            p.putAll(type.schema());
            Object d = defaultOf(key);
            if (d != null) p.put("default", d);
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
                Object d = defaultOf(key);
                String doc = docs.containsKey(key) ? docs.get(key) : null;
                if (d != null) doc = (doc == null ? "" : doc + " ") + "@default " + JsonCodec.encode(d);
                if (doc != null) b.append("/** ").append(doc.replace("*/", "* /")).append(" */\n    ");
                b.append(TsContext.key(key)).append("?: ").append(type.ts(ts)).append(';');
            });
            return b.append("\n}").toString();
        });
    }
}
