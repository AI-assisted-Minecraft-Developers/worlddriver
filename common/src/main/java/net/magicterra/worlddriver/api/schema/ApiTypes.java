package net.magicterra.worlddriver.api.schema;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.mojang.datafixers.util.Function10;
import com.mojang.datafixers.util.Function11;
import com.mojang.datafixers.util.Function12;
import com.mojang.datafixers.util.Function13;
import com.mojang.datafixers.util.Function14;
import com.mojang.datafixers.util.Function15;
import com.mojang.datafixers.util.Function16;
import com.mojang.datafixers.util.Function3;
import com.mojang.datafixers.util.Function4;
import com.mojang.datafixers.util.Function5;
import com.mojang.datafixers.util.Function6;
import com.mojang.datafixers.util.Function7;
import com.mojang.datafixers.util.Function8;
import com.mojang.datafixers.util.Function9;

/**
 * The factories for every {@link ApiType}: scalars with their ranges, enums, lists, open JSON,
 * records bound to Java records, and discriminated unions. Each type reads strictly: no number
 * from a string, no boolean from a number, no integer from a fraction, no {@code null} for absent.
 */
public final class ApiTypes {
    private ApiTypes() {}

    // ------------------------------------------------------------------ numbers

    /** An integer in {@code [min,max]}; {@code 1} and {@code 1.0} are the same integer, {@code 1.2} is none. */
    public static ApiType<Integer> integer(int min, int max) {
        return new NumberTypes.IntegerType<>(min, max, BigInteger::intValue, Long::valueOf);
    }

    /** Any 32-bit integer. */
    public static ApiType<Integer> integer() {
        return integer(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** An integer in {@code [min,max]} held as a long; for counts and times that outgrow an int. */
    public static ApiType<Long> int64(long min, long max) {
        return new NumberTypes.IntegerType<>(min, max, BigInteger::longValue, v -> v);
    }

    /**
     * A 64-bit integer written as a decimal string. JavaScript numbers hold 53 bits, so a sequence
     * number or seed sent as a number arrives rounded; as a string it survives every client.
     */
    public static ApiType<Long> int64String(long min) {
        return new NumberTypes.Int64StringType(min);
    }

    /** A finite number in {@code [min,max]}. */
    public static ApiType<Double> number(double min, double max) {
        return new NumberTypes.NumberType(min, max);
    }

    /** Any finite number. */
    public static ApiType<Double> number() {
        return new NumberTypes.NumberType(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    // ------------------------------------------------------------------ booleans and strings

    private static final ApiType<Boolean> BOOL = new ApiType<>() {
        @Override protected Boolean read(Object value, DecodeContext at) {
            return value instanceof Boolean b ? b : at.mismatch(expected(), value);
        }
        @Override protected Object write(Boolean value) { return Objects.requireNonNull(value, "boolean"); }
        @Override protected Map<String, Object> schemaBody() { return new LinkedHashMap<>(Map.of("type", "boolean")); }
        @Override public String ts(TsContext ts) { return "boolean"; }
        @Override public String expected() { return "a boolean"; }
        @Override public ApiType<Boolean> describe(String text) {
            throw new UnsupportedOperationException("describe the field, not the shared boolean type");
        }
    };

    public static ApiType<Boolean> bool() { return BOOL; }

    /** Any string. */
    public static StringType string() { return new StringType(0, Integer.MAX_VALUE, null, null); }

    /** A string of {@code minLength}–{@code maxLength} characters. */
    public static StringType string(int minLength, int maxLength) { return new StringType(minLength, maxLength, null, null); }

    /** A string matching {@code regex} entirely; {@code meaning} is how a message describes it ("a block id"). */
    public static StringType pattern(String regex, String meaning) {
        return new StringType(1, Integer.MAX_VALUE, Pattern.compile(regex), meaning);
    }

    /** Exactly the string {@code value}; a summary's kind, a response's fixed op. */
    public static ApiType<String> literal(String value) {
        return new LiteralType(value);
    }

    /** A boolean that may only be {@code true}: an acknowledgement field. */
    public static ApiType<Boolean> trueOnly() {
        return new ApiType<>() {
            @Override protected Boolean read(Object v, DecodeContext at) {
                if (!(v instanceof Boolean b)) return at.mismatch(expected(), v);
                return b ? b : at.fail(ApiIssue.Code.INVALID_VALUE, "expected true");
            }
            @Override protected Object write(Boolean v) { return v; }
            @Override protected Map<String, Object> schemaBody() {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", "boolean");
                s.put("const", true);
                return s;
            }
            @Override public String ts(TsContext ts) { return "true"; }
            @Override public String expected() { return "true"; }
        };
    }

    // ------------------------------------------------------------------ enums

    /** An enum whose constants spell themselves through {@link WireName}; named for TypeScript when {@code name} is not null. */
    public static <E extends Enum<E> & WireName> ApiType<E> enumOf(String name, Class<E> type) {
        return new EnumType<>(name, Arrays.asList(type.getEnumConstants()), WireName::wireName);
    }

    /** An enum spelled by {@code wire}. */
    public static <E extends Enum<E>> ApiType<E> enumOf(String name, Class<E> type, Function<E, String> wire) {
        return new EnumType<>(name, Arrays.asList(type.getEnumConstants()), wire);
    }

    /** A closed set of strings, decoded as themselves. */
    public static ApiType<String> stringEnum(String name, String... values) {
        return new EnumType<>(name, List.of(values), v -> v);
    }

    // ------------------------------------------------------------------ containers

    /** A list of {@code item} with {@code minItems}–{@code maxItems} elements; decoded unmodifiable. */
    public static <T> ApiType<List<T>> list(ApiType<T> item, int minItems, int maxItems) {
        return new ListType<>(item, minItems, maxItems);
    }

    /** A list of {@code item} with at most {@code maxItems} elements. */
    public static <T> ApiType<List<T>> list(ApiType<T> item, int maxItems) {
        return new ListType<>(item, 0, maxItems);
    }

    /** An object whose keys are data, every value a {@code value}; at most {@code maxEntries} entries. */
    public static <T> ApiType<Map<String, T>> map(ApiType<T> value, int maxEntries) {
        return new MapType<>(value, maxEntries);
    }

    /** Any JSON value, kept as given; for payloads whose shape is the caller's own. */
    public static ApiType<JsonValue> json() {
        return new JsonType(false);
    }

    /** Any JSON object, kept as given. */
    public static ApiType<JsonValue> jsonObject() {
        return new JsonType(true);
    }

    // ------------------------------------------------------------------ records

    /** A record with no fields: the parameters of a method that takes none. */
    public static <R> RecordType<R> record(String name, Supplier<R> constructor) {
        return new RecordType<>(name, List.of(), a -> constructor.get());
    }

    @SuppressWarnings("unchecked")
    public static <R, A> RecordType<R> record(String name, ApiField<R, A> f1, Function<A, R> constructor) {
        return new RecordType<>(name, List.of(f1), a -> constructor.apply((A) a[0]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B> RecordType<R> record(String name, ApiField<R, A> f1, ApiField<R, B> f2,
                                                 BiFunction<A, B, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2), a -> constructor.apply((A) a[0], (B) a[1]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3,
            Function3<A, B, C, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3), a -> constructor.apply((A) a[0], (B) a[1], (C) a[2]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4,
            Function4<A, B, C, D, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4), a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            Function5<A, B, C, D, E, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6,
            Function6<A, B, C, D, E, F, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7,
            Function7<A, B, C, D, E, F, G, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8,
            Function8<A, B, C, D, E, F, G, H, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8, ApiField<R, I> f9,
            Function9<A, B, C, D, E, F, G, H, I, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8, f9),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7], (I) a[8]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8, ApiField<R, I> f9, ApiField<R, J> f10,
            Function10<A, B, C, D, E, F, G, H, I, J, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7], (I) a[8], (J) a[9]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8, ApiField<R, I> f9, ApiField<R, J> f10,
            ApiField<R, K> f11,
            Function11<A, B, C, D, E, F, G, H, I, J, K, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7], (I) a[8], (J) a[9],
                        (K) a[10]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8, ApiField<R, I> f9, ApiField<R, J> f10,
            ApiField<R, K> f11, ApiField<R, L> f12,
            Function12<A, B, C, D, E, F, G, H, I, J, K, L, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7], (I) a[8], (J) a[9],
                        (K) a[10], (L) a[11]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8, ApiField<R, I> f9, ApiField<R, J> f10,
            ApiField<R, K> f11, ApiField<R, L> f12, ApiField<R, M> f13,
            Function13<A, B, C, D, E, F, G, H, I, J, K, L, M, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7], (I) a[8], (J) a[9],
                        (K) a[10], (L) a[11], (M) a[12]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8, ApiField<R, I> f9, ApiField<R, J> f10,
            ApiField<R, K> f11, ApiField<R, L> f12, ApiField<R, M> f13, ApiField<R, N> f14,
            Function14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7], (I) a[8], (J) a[9],
                        (K) a[10], (L) a[11], (M) a[12], (N) a[13]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8, ApiField<R, I> f9, ApiField<R, J> f10,
            ApiField<R, K> f11, ApiField<R, L> f12, ApiField<R, M> f13, ApiField<R, N> f14, ApiField<R, O> f15,
            Function15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7], (I) a[8], (J) a[9],
                        (K) a[10], (L) a[11], (M) a[12], (N) a[13], (O) a[14]));
    }

    @SuppressWarnings("unchecked")
    public static <R, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P> RecordType<R> record(String name,
            ApiField<R, A> f1, ApiField<R, B> f2, ApiField<R, C> f3, ApiField<R, D> f4, ApiField<R, E> f5,
            ApiField<R, F> f6, ApiField<R, G> f7, ApiField<R, H> f8, ApiField<R, I> f9, ApiField<R, J> f10,
            ApiField<R, K> f11, ApiField<R, L> f12, ApiField<R, M> f13, ApiField<R, N> f14, ApiField<R, O> f15,
            ApiField<R, P> f16,
            Function16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, R> constructor) {
        return new RecordType<>(name, List.of(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16),
                a -> constructor.apply((A) a[0], (B) a[1], (C) a[2], (D) a[3], (E) a[4], (F) a[5], (G) a[6], (H) a[7], (I) a[8], (J) a[9],
                        (K) a[10], (L) a[11], (M) a[12], (N) a[13], (O) a[14], (P) a[15]));
    }

    // ------------------------------------------------------------------ unions

    /** One variant of a {@link #taggedUnion}. */
    public static <T, S extends T> UnionType.Variant<T, S> variant(String tag, Class<S> javaType, RecordType<S> fields) {
        return new UnionType.Variant<>(tag, javaType, fields);
    }

    /** A union discriminated by the string member {@code tagName}; each variant's record must not declare it. */
    @SafeVarargs
    public static <T> UnionType<T> taggedUnion(String name, String tagName, UnionType.Variant<T, ? extends T>... variants) {
        List<UnionType.Variant<T, ?>> list = new ArrayList<>(Arrays.asList(variants));
        return new UnionType<>(name, tagName, list);
    }

    /**
     * An object of optional members, each typed by {@code types} for its key, in that map's order;
     * {@code docs} describes the keys that have a description.
     */
    public static KeyedValueType keyedValues(String name, Map<String, ApiType<?>> types, Map<String, String> docs) {
        return new KeyedValueType(name, types, docs);
    }
}
