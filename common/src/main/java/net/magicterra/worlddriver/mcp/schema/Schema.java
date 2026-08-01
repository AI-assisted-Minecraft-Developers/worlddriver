package net.magicterra.worlddriver.mcp.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Type-safe JSON-Schema builder for MCP tool input schemas.
 *
 * <p>The catalog used to hand-write schemas as {@code Map<String,Object>} literals,
 * which is stringly-typed: a mistyped key, a {@code "minimum"} on a string, or a
 * forgotten {@code "type"} all compile and only misbehave at runtime. This DSL makes
 * the <em>definition</em> type-safe — each node is a distinct Java type exposing only
 * the constraints valid for it, so an illegal schema doesn't compile.
 *
 * <p>Serialization reuses Minecraft's built-in {@link Codec} (Mojang DataFixerUpper):
 * {@link #CODEC} is one recursive, polymorphic codec for the whole tree, so a node
 * renders to JSON (via {@code JavaOps}/{@code JsonOps}) <em>or</em> NBT for free, with
 * no hand-rolled {@code toMap()}. {@code optionalFieldOf} drops absent fields, and the
 * JSON keys are plain strings — so {@code "enum"} (a Java keyword) is a non-issue.
 *
 * <p>Build via the factories on {@link Schemas} ({@code object()}, {@code integer(1,24)},
 * {@code stringEnum("xz","xy")}, …). {@link Raw} is a migration-only escape hatch and is
 * intentionally outside {@link #CODEC} (rendered by {@code Schemas.render}).
 */
public sealed interface Schema permits Schema.Obj, Schema.Str, Schema.Int, Schema.Num, Schema.Bool, Schema.Arr, Schema.Any, Schema.Union {

    /** JSON-Schema {@code "type"} discriminator — drives {@link #CODEC}'s dispatch. */
    String typeName();

    /** One recursive, polymorphic codec for the whole schema tree (encode → JSON/NBT). */
    Codec<Schema> CODEC = Codec.recursive("Schema", self -> {
        MapCodec<Obj> obj = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.unboundedMap(Codec.STRING, self).fieldOf("properties").forGetter(Obj::properties),
                Codec.STRING.listOf().optionalFieldOf("required").forGetter(o -> nonEmpty(o.required())),
                Codec.BOOL.optionalFieldOf("additionalProperties").forGetter(o -> Optional.ofNullable(o.additionalProperties())),
                Codec.STRING.optionalFieldOf("description").forGetter(o -> Optional.ofNullable(o.description()))
        ).apply(i, Obj::decoded));

        MapCodec<Str> str = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.listOf().optionalFieldOf("enum").forGetter(s -> Optional.ofNullable(s.enumValues())),
                Codec.STRING.optionalFieldOf("format").forGetter(s -> Optional.ofNullable(s.format())),
                Codec.STRING.optionalFieldOf("description").forGetter(s -> Optional.ofNullable(s.description()))
        ).apply(i, Str::decoded));

        MapCodec<Int> intc = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.optionalFieldOf("minimum").forGetter(s -> Optional.ofNullable(s.minimum())),
                Codec.INT.optionalFieldOf("maximum").forGetter(s -> Optional.ofNullable(s.maximum())),
                Codec.STRING.optionalFieldOf("description").forGetter(s -> Optional.ofNullable(s.description()))
        ).apply(i, Int::decoded));

        MapCodec<Num> num = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("minimum").forGetter(s -> Optional.ofNullable(s.minimum())),
                Codec.DOUBLE.optionalFieldOf("maximum").forGetter(s -> Optional.ofNullable(s.maximum())),
                Codec.STRING.optionalFieldOf("description").forGetter(s -> Optional.ofNullable(s.description()))
        ).apply(i, Num::decoded));

        MapCodec<Bool> bool = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("description").forGetter(s -> Optional.ofNullable(s.description()))
        ).apply(i, Bool::decoded));

        MapCodec<Arr> arr = RecordCodecBuilder.mapCodec(i -> i.group(
                self.fieldOf("items").forGetter(Arr::items),
                Codec.STRING.optionalFieldOf("description").forGetter(s -> Optional.ofNullable(s.description()))
        ).apply(i, Arr::decoded));

        // The six concrete kinds carry a "type" discriminator → dispatch on it.
        Codec<Schema> typed = Codec.STRING.<Schema>dispatch("type", Schema::typeName, type -> switch (type) {
            case "object"  -> obj;
            case "string"  -> str;
            case "integer" -> intc;
            case "number"  -> num;
            case "boolean" -> bool;
            case "array"   -> arr;
            default -> throw new IllegalStateException("no Schema codec for type: " + type);
        });

        // Any is the typeless "accept anything" schema (no "type" key) — it can't go
        // through the type-dispatch, so we route by runtime class on encode. Decode is
        // unused (the DSL is authoring-only), so it just delegates to the typed codec.
        Codec<Any> any = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("description").forGetter(a -> Optional.ofNullable(a.description()))
        ).apply(i, Any::decoded));

        // Union renders "type" as a JSON ARRAY of primitive names (vs the six concrete
        // kinds' single-string "type") — also outside the STRING-keyed dispatch above,
        // so it's routed the same way as Any: by runtime class on encode. A full Codec
        // (RecordCodecBuilder.create, not .mapCodec) so it exposes the 3-arg
        // encode(A, ops, prefix) that Any's routing below relies on.
        Codec<Union> union = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.listOf().fieldOf("type").forGetter(Union::types),
                Codec.STRING.optionalFieldOf("description").forGetter(u -> Optional.ofNullable(u.description()))
        ).apply(i, Union::decoded));

        Encoder<Schema> encoder = new Encoder<Schema>() {
            @Override public <T> DataResult<T> encode(Schema input, DynamicOps<T> ops, T prefix) {
                if (input instanceof Any a) return any.encode(a, ops, prefix);
                if (input instanceof Union u) return union.encode(u, ops, prefix);
                return typed.encode(input, ops, prefix);
            }
        };
        return Codec.of(encoder, typed);
    });

    private static Optional<List<String>> nonEmpty(List<String> list) {
        return (list == null || list.isEmpty()) ? Optional.empty() : Optional.of(list);
    }

    // ---------------------------------------------------------------- nodes

    /** Object schema: ordered properties, a required-subset, optional additionalProperties. */
    final class Obj implements Schema {
        private final LinkedHashMap<String, Schema> properties = new LinkedHashMap<>();
        private final ArrayList<String> required = new ArrayList<>();
        private Boolean additionalProperties;   // null → omit; true → emit
        private String description;

        public Obj prop(String name, Schema schema) {
            properties.put(Objects.requireNonNull(name), Objects.requireNonNull(schema));
            return this;
        }
        /** Add a property AND mark it required. */
        public Obj req(String name, Schema schema) { prop(name, schema); required.add(name); return this; }
        public Obj additionalProperties(boolean allow) { this.additionalProperties = allow ? Boolean.TRUE : null; return this; }
        public Obj desc(String d) { this.description = d; return this; }

        @Override public String typeName() { return "object"; }
        Map<String, Schema> properties() { return properties; }
        List<String> required() { return required; }
        Boolean additionalProperties() { return additionalProperties; }
        String description() { return description; }

        static Obj decoded(Map<String, Schema> props, Optional<List<String>> req,
                           Optional<Boolean> addl, Optional<String> desc) {
            Obj o = new Obj();
            props.forEach(o::prop);
            req.ifPresent(o.required::addAll);
            addl.ifPresent(b -> o.additionalProperties = b ? Boolean.TRUE : null);
            desc.ifPresent(o::desc);
            return o;
        }
    }

    /** String schema, optionally constrained to an enum and/or a format. */
    final class Str implements Schema {
        private List<String> enumValues;
        private String format;
        private String description;

        public Str enumOf(String... values) { this.enumValues = List.of(values); return this; }
        public Str format(String f) { this.format = f; return this; }
        public Str desc(String d) { this.description = d; return this; }

        @Override public String typeName() { return "string"; }
        List<String> enumValues() { return enumValues; }
        String format() { return format; }
        String description() { return description; }

        static Str decoded(Optional<List<String>> en, Optional<String> fmt, Optional<String> desc) {
            Str s = new Str();
            en.ifPresent(v -> s.enumValues = v);
            fmt.ifPresent(s::format);
            desc.ifPresent(s::desc);
            return s;
        }
    }

    /** Integer schema with optional inclusive bounds. */
    final class Int implements Schema {
        private Integer minimum;
        private Integer maximum;
        private String description;

        public Int min(int v) { this.minimum = v; return this; }
        public Int max(int v) { this.maximum = v; return this; }
        public Int range(int lo, int hi) { this.minimum = lo; this.maximum = hi; return this; }
        public Int desc(String d) { this.description = d; return this; }

        @Override public String typeName() { return "integer"; }
        Integer minimum() { return minimum; }
        Integer maximum() { return maximum; }
        String description() { return description; }

        static Int decoded(Optional<Integer> lo, Optional<Integer> hi, Optional<String> desc) {
            Int s = new Int();
            lo.ifPresent(v -> s.minimum = v);
            hi.ifPresent(v -> s.maximum = v);
            desc.ifPresent(s::desc);
            return s;
        }
    }

    /** Floating-point schema with optional inclusive bounds. */
    final class Num implements Schema {
        private Double minimum;
        private Double maximum;
        private String description;

        public Num min(double v) { this.minimum = v; return this; }
        public Num max(double v) { this.maximum = v; return this; }
        public Num range(double lo, double hi) { this.minimum = lo; this.maximum = hi; return this; }
        public Num desc(String d) { this.description = d; return this; }

        @Override public String typeName() { return "number"; }
        Double minimum() { return minimum; }
        Double maximum() { return maximum; }
        String description() { return description; }

        static Num decoded(Optional<Double> lo, Optional<Double> hi, Optional<String> desc) {
            Num s = new Num();
            lo.ifPresent(v -> s.minimum = v);
            hi.ifPresent(v -> s.maximum = v);
            desc.ifPresent(s::desc);
            return s;
        }
    }

    /** Boolean schema. */
    final class Bool implements Schema {
        private String description;
        public Bool desc(String d) { this.description = d; return this; }
        @Override public String typeName() { return "boolean"; }
        String description() { return description; }
        static Bool decoded(Optional<String> desc) { Bool s = new Bool(); desc.ifPresent(s::desc); return s; }
    }

    /** Array schema over a typed item schema. */
    final class Arr implements Schema {
        private final Schema items;
        private String description;
        public Arr(Schema items) { this.items = Objects.requireNonNull(items); }
        public Arr desc(String d) { this.description = d; return this; }
        @Override public String typeName() { return "array"; }
        Schema items() { return items; }
        String description() { return description; }
        static Arr decoded(Schema items, Optional<String> desc) { Arr a = new Arr(items); desc.ifPresent(a::desc); return a; }
    }

    /**
     * Typeless "accept anything" schema — emits only an optional {@code description},
     * with no {@code "type"} key (JSON-Schema's any-value form). Use for free-form
     * values like an event payload or a {@code wait.condition} compare-value. Routed
     * by runtime class in {@link #CODEC} since it has no discriminator to dispatch on.
     */
    final class Any implements Schema {
        private String description;
        public Any desc(String d) { this.description = d; return this; }
        @Override public String typeName() { return "any"; }
        String description() { return description; }
        static Any decoded(Optional<String> desc) { Any a = new Any(); desc.ifPresent(a::desc); return a; }
    }

    /**
     * A union of JSON-Schema primitive types — renders {@code "type": ["integer","string","object"]}
     * (a JSON ARRAY, unlike the six concrete kinds' single-string {@code "type"}).
     *
     * <p>Use where the consuming route code genuinely branches on runtime type
     * ({@code instanceof Number}/{@code Map}/{@code String}/…) — i.e. more than one
     * type is accepted, but not literally anything (see {@link Any} for that). This is
     * the gap#67-④ fix: a typeless field (no {@code "type"} key at all) gets
     * JSON-stringified by at least one MCP client before the value reaches
     * {@code tools/call} (verified live: {@code target:99999} arrived server-side as
     * {@code targetType:"minecraft:99999"} — the client re-serialized a bare number
     * because the schema gave it no type to preserve). Declaring the accepted set lets
     * the client keep the caller's JSON type across the wire.
     *
     * <p>Validation ({@code SchemaValidator}) accepts a value matching ANY declared
     * member type, checked with the same shape rules as the six concrete kinds
     * (enum/min/max don't apply to a union member — only the type check).
     */
    final class Union implements Schema {
        private final List<String> types;   // ordered, e.g. ["integer","string","object"]
        private String description;

        public Union(List<String> types) { this.types = List.copyOf(Objects.requireNonNull(types)); }
        public Union desc(String d) { this.description = d; return this; }

        @Override public String typeName() { return "union"; }
        List<String> types() { return types; }
        String description() { return description; }

        static Union decoded(List<String> types, Optional<String> desc) {
            Union u = new Union(types);
            desc.ifPresent(u::desc);
            return u;
        }
    }
}
