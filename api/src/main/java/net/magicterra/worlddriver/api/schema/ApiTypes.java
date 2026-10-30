package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Where a contract gets its {@link ApiType}s: read off a Java declaration with {@link #of}, {@link #record} and
 * {@link #union}, or, for the few shapes known only at run time, built by the open-type factories at the end. The
 * scalar factories are the reader's own. Each type reads strictly: no number from a string, no boolean from a number,
 * no integer from a fraction, no {@code null} for absent.
 */
public final class ApiTypes {
    private static final String BOOLEAN = "boolean";

    // ------------------------------------------------------------------ booleans and strings

    private static final ApiType<Boolean> BOOL = new ApiType<>() {
        @Override
        protected Boolean read(JsonElement value, DecodeContext at) {
            Boolean b = Json.bool(value);
            return b != null ? b : at.mismatch(expected(), value);
        }

        @Override
        protected JsonElement write(Boolean value) {
            return new JsonPrimitive(Objects.requireNonNull(value, BOOLEAN));
        }

        @Override
        protected JsonObject schemaBody() {
            JsonObject s = new JsonObject();
            s.addProperty("type", BOOLEAN);
            return s;
        }

        @Override
        public String ts(TsContext ts) {
            return BOOLEAN;
        }

        @Override
        public String expected() {
            return "a boolean";
        }

        @Override
        ApiType<Boolean> describe(String text) {
            throw new UnsupportedOperationException("describe the field, not the shared boolean type");
        }
    };

    private ApiTypes() {}

    // ------------------------------------------------------------------ numbers

    /** An integer in {@code [min,max]}; {@code 1} and {@code 1.0} are the same integer, {@code 1.2} is none. */
    static ApiType<Integer> integer(int min, int max) {
        return new NumberTypes.IntegerType<>(min, max, Long::intValue, Long::valueOf);
    }

    /** Any 32-bit integer. */
    static ApiType<Integer> integer() {
        return integer(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** An integer in {@code [min,max]} held as a long; for counts and times that outgrow an int. */
    static ApiType<Long> int64(long min, long max) {
        return new NumberTypes.IntegerType<>(min, max, v -> v, v -> v);
    }

    /**
     * A 64-bit integer written as a decimal string. JavaScript numbers hold 53 bits, so a sequence number or seed sent
     * as a number arrives rounded; as a string it survives every client.
     */
    static ApiType<Long> int64String(long min) {
        return new NumberTypes.Int64StringType(min);
    }

    /** A finite number in {@code [min,max]}. */
    static ApiType<Double> number(double min, double max) {
        return new NumberTypes.NumberType(min, max);
    }

    /** Any finite number. */
    static ApiType<Double> number() {
        return new NumberTypes.NumberType(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    static ApiType<Boolean> bool() {
        return BOOL;
    }

    /** Any string. */
    static StringType string() {
        return new StringType(0, Integer.MAX_VALUE, null, null);
    }

    /** A string of {@code minLength}–{@code maxLength} characters. */
    static StringType string(int minLength, int maxLength) {
        return new StringType(minLength, maxLength, null, null);
    }

    /** A string matching {@code regex} entirely; {@code meaning} is how a message describes it ("a block id"). */
    static StringType pattern(String regex, String meaning) {
        return new StringType(1, Integer.MAX_VALUE, Pattern.compile(regex), meaning);
    }

    /** A boolean that may only be {@code true}: an acknowledgement field. */
    static ApiType<Boolean> trueOnly() {
        return new ApiType<>() {
            @Override
            protected Boolean read(JsonElement v, DecodeContext at) {
                Boolean b = Json.bool(v);
                if (b == null) {
                    return at.mismatch(expected(), v);
                }
                return b ? b : at.fail(ApiIssue.Code.INVALID_VALUE, "expected true");
            }

            @Override
            protected JsonElement write(Boolean v) {
                return new JsonPrimitive(v);
            }

            @Override
            protected JsonObject schemaBody() {
                JsonObject s = new JsonObject();
                s.addProperty("type", BOOLEAN);
                s.addProperty("const", true);
                return s;
            }

            @Override
            public String ts(TsContext ts) {
                return "true";
            }

            @Override
            public String expected() {
                return "true";
            }
        };
    }

    // ------------------------------------------------------------------ enums

    /** A closed set of strings, decoded as themselves. */
    public static ApiType<String> stringEnum(String name, String... values) {
        return new EnumType<>(name, List.of(values), v -> v);
    }

    // ------------------------------------------------------------------ containers

    /** A list of {@code item} with {@code minItems}–{@code maxItems} elements; decoded unmodifiable. */
    static <T> ApiType<List<T>> list(ApiType<T> item, int minItems, int maxItems) {
        return new ListType<>(item, minItems, maxItems);
    }

    /** A list of {@code item} with at most {@code maxItems} elements. */
    public static <T> ApiType<List<T>> list(ApiType<T> item, int maxItems) {
        return new ListType<>(item, 0, maxItems);
    }

    /** An object whose keys are data, every value a {@code value}; at most {@code maxEntries} entries. */
    static <T> ApiType<Map<String, T>> map(ApiType<T> value, int maxEntries) {
        return new MapType<>(value, maxEntries);
    }

    /** Any JSON value, kept as given; for payloads whose shape is the caller's own. */
    static ApiType<JsonValue> json() {
        return new JsonType(false);
    }

    /** Any JSON object, kept as given. */
    static ApiType<JsonValue> jsonObject() {
        return new JsonType(true);
    }

    // ------------------------------------------------------------------ read off Java declarations

    /**
     * The wire type of {@code type}: a record, read component by component with the annotations in
     * {@code api.schema.annotation}; an enum implementing {@link WireName}; a sealed interface marked {@code @Tag}; or
     * a scalar. Built once per class, so every use shares one object.
     */
    public static <T> ApiType<T> of(Class<T> type) {
        return Reflected.of(type);
    }

    /**
     * The scalar {@code type} under the constraint annotation {@code constraint} (one whose declaration carries
     * {@code @Range}, {@code @Pattern}, {@code @Doc}…): the same object a component written {@code @Constraint T} gets,
     * for code that needs the type itself.
     */
    public static <T> ApiType<T> of(Class<T> type, Class<? extends java.lang.annotation.Annotation> constraint) {
        return Reflected.of(type, constraint);
    }

    /** {@link #of} for a record, typed as the record type it is. */
    public static <R extends Record> RecordType<R> record(Class<R> type) {
        return Reflected.record(type);
    }

    /**
     * A generic record with its type variables bound: each component named in {@code componentTypes} takes that type
     * (the content type, for an {@code Optional}), the rest are read as usual. Not shared: every call builds a new
     * type, so bind once per use and keep the result.
     */
    public static <R extends Record> RecordType<R> record(Class<R> type, Map<String, ApiType<?>> componentTypes) {
        return Reflected.record(type, componentTypes);
    }

    /** {@link #of} for a sealed interface marked {@code @Tag}, typed as the union it is. */
    public static <T> UnionType<T> union(Class<T> type) {
        return Reflected.union(type);
    }

    /**
     * A union of {@code variants} under the discriminator {@code tagName}, each tagged by its record's
     * {@code @Variant}: for a union that is not every implementation of one sealed interface.
     */
    @SafeVarargs
    public static <T> UnionType<T> union(String name, String tagName, RecordType<? extends T>... variants) {
        return Reflected.union(name, tagName, Arrays.asList(variants));
    }

    // ------------------------------------------------------------------ open types

    /** {@code base}, open to more variants later; see {@link OpenUnion}. */
    public static <T> OpenUnion<T> openUnion(UnionType<T> base) {
        return new OpenUnion<>(base);
    }

    /**
     * An object of optional members, each typed by {@code types} for its key, in that map's order; {@code docs}
     * describes the keys that have a description.
     */
    public static KeyedValueType keyedValues(String name, Map<String, ApiType<?>> types, Map<String, String> docs) {
        return new KeyedValueType(name, types, docs, key -> null);
    }

    /** As above, each key's default looked up through {@code defaults} (null for none) when rendered. */
    public static KeyedValueType keyedValues(
            String name, Map<String, ApiType<?>> types, Map<String, String> docs, Function<String, Object> defaults) {
        return new KeyedValueType(name, types, docs, defaults);
    }
}
