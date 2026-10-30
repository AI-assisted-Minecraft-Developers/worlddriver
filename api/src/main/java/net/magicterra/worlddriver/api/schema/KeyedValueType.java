package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * An object whose members are drawn from a fixed key table, each member's value typed by its key: {@code {autoEat:
 * true, autoEatFoodThreshold: 14}}. Every member is optional, so a table of a hundred keys is one object type rather
 * than a hundred-field record. Decoded as entries in the order sent.
 *
 * <p>A key's default is looked up when the schema or the declaration is rendered, not when the type is built: the
 * values may live in a layer the contract cannot name, which installs the lookup at startup.
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

    KeyedValueType(
            String name, Map<String, ApiType<?>> types, Map<String, String> docs, Function<String, Object> defaults) {
        this(
                name,
                Collections.unmodifiableMap(new LinkedHashMap<>(types)),
                Map.copyOf(docs),
                defaults,
                0,
                new Object());
        if (types.isEmpty()) throw new IllegalArgumentException(name + " has no keys");
    }

    private KeyedValueType(
            String name,
            Map<String, ApiType<?>> types,
            Map<String, String> docs,
            Function<String, Object> defaults,
            int minEntries,
            Object tsOwner) {
        this.name = Objects.requireNonNull(name);
        this.types = types;
        this.docs = docs;
        this.defaults = Objects.requireNonNull(defaults);
        this.minEntries = minEntries;
        this.tsOwner = tsOwner;
    }

    public Map<String, ApiType<?>> types() {
        return types;
    }

    /** The same key table, refusing an object with fewer than {@code min} members. */
    public KeyedValueType atLeast(int min) {
        return new KeyedValueType(name, types, docs, defaults, min, tsOwner);
    }

    @Override
    public String expected() {
        return "a " + name + " object"
                + (minEntries > 0 ? " with at least " + minEntries + " known key" : " of known keys");
    }

    @Override
    protected List<Entry> read(JsonElement value, DecodeContext at) {
        if (!(value instanceof JsonObject map)) return at.mismatch(expected(), value);
        if (map.size() < minEntries)
            return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got none");
        int before = at.issues().size();
        List<Entry> out = new ArrayList<>(map.size());
        for (Map.Entry<String, JsonElement> e : map.entrySet()) {
            String key = e.getKey();
            DecodeContext here = at.field(key);
            ApiType<?> type = types.get(key);
            if (type == null) {
                here.fail(ApiIssue.Code.UNKNOWN_FIELD, "unknown key \"" + key + "\"");
                continue;
            }
            if (e.getValue().isJsonNull()) {
                here.fail(
                        ApiIssue.Code.TYPE_MISMATCH, "null is not a value here; " + key + " takes " + type.expected());
                continue;
            }
            Object v = type.decode(e.getValue(), here);
            if (v != null) out.add(new Entry(key, v));
        }
        return at.issues().size() == before ? Collections.unmodifiableList(out) : null;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected JsonElement write(List<Entry> entries) {
        JsonObject out = new JsonObject();
        for (Entry entry : entries) {
            ApiType type = types.get(entry.key());
            // An unknown key is written as best it can be, so read() reports it at its own path: a request
            // error for validate(), a contract violation for encode().
            out.add(entry.key(), type == null ? plain(entry.value()) : wireForm(type, entry.value()));
        }
        return out;
    }

    /**
     * {@code value} in wire form when it is the Java type {@code type} holds; a plain scalar of another type as itself,
     * so read() judges it as it would a request's. An entry's value is an Object, so a Java caller can hand an Integer
     * to a long setting, and the generic write would cast it before any check ran.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonElement wireForm(ApiType type, Object value) {
        try {
            return type.write(value);
        } catch (ClassCastException otherType) {
            return plain(value);
        }
    }

    /** A number, string or boolean as itself; anything else as null, which read() reports where it sits. */
    private static JsonElement plain(Object value) {
        if (value instanceof JsonElement j) return j;
        if (value instanceof Number n) return new JsonPrimitive(n);
        if (value instanceof String s) return new JsonPrimitive(s);
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        return JsonNull.INSTANCE;
    }

    /** {@code key}'s default in wire form, or null when it has none. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private JsonElement defaultOf(String key) {
        Object d = defaults.apply(key);
        return d == null ? null : ((ApiType) types.get(key)).write(d);
    }

    @Override
    protected JsonObject schemaBody() {
        JsonObject props = new JsonObject();
        types.forEach((key, type) -> {
            JsonObject p = new JsonObject();
            if (docs.containsKey(key)) p.addProperty("description", docs.get(key));
            for (Map.Entry<String, JsonElement> e : type.schema().entrySet()) p.add(e.getKey(), e.getValue());
            JsonElement d = defaultOf(key);
            if (d != null) p.add("default", d);
            props.add(key, p);
        });
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", props);
        s.addProperty("additionalProperties", false);
        if (minEntries > 0) s.addProperty("minProperties", minEntries);
        return s;
    }

    @Override
    public String ts(TsContext ts) {
        return ts.declare(name, tsOwner, () -> {
            StringBuilder b = new StringBuilder("interface " + name + " {");
            types.forEach((key, type) -> {
                b.append("\n    ");
                JsonElement d = defaultOf(key);
                String doc = docs.containsKey(key) ? docs.get(key) : null;
                if (d != null) doc = (doc == null ? "" : doc + " ") + "@default " + d;
                if (doc != null)
                    b.append("/** ").append(doc.replace("*/", "* /")).append(" */\n    ");
                b.append(TsContext.key(key)).append("?: ").append(type.ts(ts)).append(';');
            });
            return b.append("\n}").toString();
        });
    }
}
