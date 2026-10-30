package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A discriminated union: the {@code tagName} member picks the variant, and only that variant's fields are allowed
 * beside it. The discriminator is checked before anything else, so a wrong or missing one is one issue at its own path
 * instead of a cascade of unknown fields.
 */
public final class UnionType<T> extends ApiType<T> {
    private final String name;

    private final String tagName;

    private final List<Variant<T, ?>> variants;

    UnionType(String name, String tagName, List<Variant<T, ?>> variants) {
        this.name = name;
        this.tagName = Objects.requireNonNull(tagName);
        this.variants = List.copyOf(variants);
        if (this.variants.isEmpty()) {
            throw new IllegalArgumentException("union " + name + " has no variants");
        }
        List<String> tags = new ArrayList<>();
        for (Variant<T, ?> v : this.variants) {
            if (tags.contains(v.tag)) {
                throw new IllegalArgumentException("union " + name + " repeats tag " + v.tag);
            }
            tags.add(v.tag);
            for (ApiField<?, ?> f : v.type.fields()) {
                if (f.name().equals(tagName)) {
                    throw new IllegalArgumentException(
                            "variant " + v.tag + " of " + name + " declares the discriminator " + tagName);
                }
            }
        }
        for (int i = 0; i < this.variants.size(); i++) {
            for (int j = i + 1; j < this.variants.size(); j++) {
                Class<?> a = this.variants.get(i).javaType;
                Class<?> b = this.variants.get(j).javaType;
                if (a.isAssignableFrom(b) || b.isAssignableFrom(a)) {
                    throw new IllegalArgumentException("union " + name + ": variants " + a.getSimpleName() + " and "
                            + b.getSimpleName() + " cannot be told apart by class");
                }
            }
        }
    }

    /** One variant: its tag, the Java class that encodes as it, and the record type of its other fields. */
    public record Variant<T, S extends T>(String tag, Class<S> javaType, RecordType<S> type) {
        public Variant {
            Objects.requireNonNull(tag);
            Objects.requireNonNull(javaType);
            Objects.requireNonNull(type);
        }
    }

    public String name() {
        return name;
    }

    public String tagName() {
        return tagName;
    }

    public List<Variant<T, ?>> variants() {
        return variants;
    }

    private String tags() {
        StringJoiner j = new StringJoiner(", ");
        for (Variant<T, ?> v : variants) {
            j.add(v.tag);
        }
        return j.toString();
    }

    @Override
    public String expected() {
        return (name == null ? "an object" : "a " + name + " object") + " with " + tagName + " one of " + tags();
    }

    @Override
    protected T read(JsonElement value, DecodeContext at) {
        if (!(value instanceof JsonObject map)) {
            return at.mismatch(expected(), value);
        }
        DecodeContext tagAt = at.field(tagName);
        if (!map.has(tagName)) {
            return tagAt.fail(ApiIssue.Code.REQUIRED, "missing; expected one of " + tags());
        }
        JsonElement tag = map.get(tagName);
        String s = Json.string(tag);
        if (s == null) {
            return tagAt.mismatch("a string, one of " + tags(), tag);
        }
        for (Variant<T, ?> v : variants) {
            if (v.tag.equals(s)) {
                return v.type.readMembers(map, at, tagName);
            }
        }
        return tagAt.fail(
                ApiIssue.Code.INVALID_VALUE, "unknown " + tagName + " \"" + s + "\"; expected one of " + tags());
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected JsonElement write(T value) {
        for (Variant<T, ?> v : variants) {
            if (v.javaType.isInstance(value)) {
                return ((RecordType) v.type).writeMembers(value, tagName, v.tag);
            }
        }
        throw new IllegalStateException(value.getClass().getName() + " is no variant of " + expected());
    }

    @Override
    protected JsonObject schemaBody() {
        JsonArray oneOf = new JsonArray();
        for (Variant<T, ?> v : variants) {
            oneOf.add(v.type.schemaWithTag(tagName, v.tag));
        }
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("oneOf", oneOf);
        return s;
    }

    @Override
    public String ts(TsContext ts) {
        if (name == null) {
            return inline(ts, " | ");
        }
        return ts.declare(name, this, () -> "type " + name + " =\n    | " + inline(ts, "\n    | ") + ";");
    }

    private String inline(TsContext ts, String separator) {
        StringJoiner j = new StringJoiner(separator);
        // A multi-line variant is indented one step under its "|", so the closing brace lines up with it.
        for (Variant<T, ?> v : variants) {
            j.add(v.type.tsInline(ts, tagName, v.tag).replace("\n", "\n    "));
        }
        return j.toString();
    }
}
