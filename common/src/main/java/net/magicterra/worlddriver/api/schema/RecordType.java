package net.magicterra.worlddriver.api.schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A closed JSON object bound to a Java record: unknown keys, a missing required key and a present
 * {@code null} are all issues, each at its own path, and the constructor runs only when every field
 * decoded. Built through the {@code ApiTypes.record} overloads, which tie each field's getter to the
 * matching constructor parameter.
 */
public final class RecordType<O> extends ApiType<O> {
    private final String name;
    private final List<ApiField<O, ?>> fields;
    private final Function<Object[], O> constructor;

    RecordType(String name, List<ApiField<O, ?>> fields, Function<Object[], O> constructor) {
        this.name = name;
        this.fields = List.copyOf(fields);
        this.constructor = constructor;
        Set<String> seen = new HashSet<>();
        for (ApiField<O, ?> f : this.fields) {
            if (!seen.add(f.name)) throw new IllegalArgumentException("record " + name + " declares field " + f.name + " twice");
        }
    }

    /** The TypeScript/documentation name; null for an anonymous record written inline. */
    public String name() { return name; }

    public List<ApiField<O, ?>> fields() { return fields; }

    @Override public String expected() { return name == null ? "an object" : "a " + name + " object"; }

    @Override
    protected O read(Object value, DecodeContext at) {
        if (!(value instanceof Map<?, ?> map)) return at.mismatch(expected(), value);
        return readMembers(map, at, null);
    }

    /** Decode the members of {@code map}, ignoring {@code skipKey} (a union's discriminator). */
    O readMembers(Map<?, ?> map, DecodeContext at, String skipKey) {
        int before = at.issues().size();
        for (Object k : map.keySet()) {
            String key = String.valueOf(k);
            if (key.equals(skipKey)) continue;
            if (field(key) == null) {
                at.field(key).fail(ApiIssue.Code.UNKNOWN_FIELD, unknownMessage(key, skipKey));
            }
        }
        Object[] args = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            ApiField<O, ?> f = fields.get(i);
            DecodeContext fat = at.field(f.name);
            boolean present = map.containsKey(f.name);
            Object raw = map.get(f.name);
            if (present && raw == null) {
                fat.fail(ApiIssue.Code.TYPE_MISMATCH, "null is not a value here; omit the field instead");
                continue;
            }
            if (!present) {
                switch (f.presence) {
                    case REQUIRED -> fat.fail(ApiIssue.Code.REQUIRED, "missing; expected " + f.type.expected());
                    case DEFAULTED -> args[i] = f.defaultValue;
                    case OPTIONAL -> args[i] = Optional.empty();
                }
                continue;
            }
            Object v = f.type.decode(raw, fat);
            if (v == null) continue;
            args[i] = f.presence == ApiField.Presence.OPTIONAL ? Optional.of(v) : v;
        }
        if (at.issues().size() != before) return null;
        O value;
        try {
            value = constructor.apply(args);
        } catch (IllegalArgumentException rejected) {
            // A record's compact constructor guarding a rule that spans fields.
            return at.fail(ApiIssue.Code.INVALID_VALUE, rejected.getMessage());
        }
        if (check != null) {
            check.accept(value, at);
            if (at.issues().size() != before) return null;
        }
        return value;
    }

    private BiConsumer<O, DecodeContext> check;

    /**
     * The same record type with a rule spanning its fields, run once every field decoded. Unlike
     * {@link #withCheck} the result is still a record type, so it can be a union variant; the check
     * reports at the field it blames ({@code at.field("widthBias").fail(...)}).
     */
    public RecordType<O> checked(BiConsumer<O, DecodeContext> rule) {
        RecordType<O> copy = new RecordType<>(name, fields, constructor);
        copy.check = check == null ? rule : check.andThen(rule);
        return copy;
    }

    private String unknownMessage(String key, String skipKey) {
        List<String> names = new ArrayList<>();
        if (skipKey != null) names.add(skipKey);
        for (ApiField<O, ?> f : fields) names.add(f.name);
        return "unknown field" + (names.isEmpty() ? "; this object takes no fields" : "; allowed: " + String.join(", ", names));
    }

    private ApiField<O, ?> field(String key) {
        for (ApiField<O, ?> f : fields) if (f.name.equals(key)) return f;
        return null;
    }

    @Override
    protected Object write(O value) {
        return writeMembers(value, null, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Map<String, Object> writeMembers(O value, String tagName, String tag) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (tagName != null) out.put(tagName, tag);
        for (ApiField<O, ?> f : fields) {
            Object v = f.getter.apply(value);
            if (f.presence == ApiField.Presence.OPTIONAL) {
                Optional<?> o = (Optional<?>) v;
                if (o == null || o.isEmpty()) continue;
                v = o.get();
            }
            if (v == null) throw new IllegalStateException(expected() + " field " + f.name + " is null");
            out.put(f.name, ((ApiType) f.type).write(v));
        }
        return out;
    }

    @Override
    protected Map<String, Object> schemaBody() {
        return schemaWithTag(null, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Map<String, Object> schemaWithTag(String tagName, String tag) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (tagName != null) {
            // Not Map.of: its iteration order changes from one JVM to the next, and so would the artifacts.
            Map<String, Object> tagSchema = new LinkedHashMap<>();
            tagSchema.put("type", "string");
            tagSchema.put("const", tag);
            props.put(tagName, tagSchema);
            required.add(tagName);
        }
        for (ApiField<O, ?> f : fields) {
            Map<String, Object> p = new LinkedHashMap<>();
            if (f.description != null) p.put("description", f.description);
            p.putAll(f.type.schema());
            if (f.presence == ApiField.Presence.DEFAULTED) p.put("default", ((ApiType) f.type).write(f.defaultValue));
            props.put(f.name, p);
            if (f.presence == ApiField.Presence.REQUIRED) required.add(f.name);
        }
        s.put("properties", props);
        if (!required.isEmpty()) s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    @Override
    public String ts(TsContext ts) {
        if (name == null) return tsInline(ts, null, null);
        return ts.declare(name, this, () -> "interface " + name + " " + tsInline(ts, null, null));
    }

    /** The object literal type, with the discriminator first when a union asks for one. */
    String tsInline(TsContext ts, String tagName, String tag) {
        if (fields.isEmpty() && tagName == null) return "{}";
        StringBuilder b = new StringBuilder("{");
        if (tagName != null) b.append(' ').append(TsContext.key(tagName)).append(": ").append(TsContext.literal(tag)).append(';');
        boolean multiline = fields.stream().anyMatch(f -> f.description != null);
        for (ApiField<O, ?> f : fields) {
            if (multiline) {
                b.append("\n    ");
                if (f.description != null) b.append("/** ").append(f.description.replace("*/", "* /")).append(" */\n    ");
            } else {
                b.append(' ');
            }
            b.append(TsContext.key(f.name));
            if (f.presence != ApiField.Presence.REQUIRED) b.append('?');
            b.append(": ").append(f.type.ts(ts)).append(';');
        }
        b.append(multiline ? "\n}" : " }");
        return b.toString();
    }
}
