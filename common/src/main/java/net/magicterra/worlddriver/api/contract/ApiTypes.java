package net.magicterra.worlddriver.api.contract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
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
import net.magicterra.worlddriver.protocol.JsonCodec;

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
        return new IntegerType<>(min, max, BigInteger::intValue, Long::valueOf);
    }

    /** Any 32-bit integer. */
    public static ApiType<Integer> integer() {
        return integer(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** An integer in {@code [min,max]} held as a long; for counts and times that outgrow an int. */
    public static ApiType<Long> int64(long min, long max) {
        return new IntegerType<>(min, max, BigInteger::longValue, v -> v);
    }

    /**
     * A 64-bit integer written as a decimal string. JavaScript numbers hold 53 bits, so a sequence
     * number or seed sent as a number arrives rounded; as a string it survives every client.
     */
    public static ApiType<Long> int64String(long min) {
        return new Int64StringType(min);
    }

    /** A finite number in {@code [min,max]}. */
    public static ApiType<Double> number(double min, double max) {
        return new NumberType(min, max);
    }

    /** Any finite number. */
    public static ApiType<Double> number() {
        return new NumberType(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /** The exact integer a JSON number denotes, or null when it denotes none. */
    static BigInteger exactInteger(Object v) {
        BigDecimal d = exactDecimal(v);
        if (d == null) return null;
        try {
            return d.toBigIntegerExact();
        } catch (ArithmeticException fraction) {
            return null;
        }
    }

    /** The exact decimal a JSON number denotes, or null when the value is no finite number. */
    static BigDecimal exactDecimal(Object v) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof BigInteger i) return new BigDecimal(i);
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return new BigDecimal(Double.toString(d));
        }
        return null;
    }

    private static boolean isNumber(Object v) {
        return v instanceof Number;
    }

    private static final class IntegerType<T extends Number> extends ApiType<T> {
        private final long min, max;
        private final Function<BigInteger, T> narrow;
        private final Function<T, Long> widen;

        IntegerType(long min, long max, Function<BigInteger, T> narrow, Function<T, Long> widen) {
            if (min > max) throw new IllegalArgumentException("empty range " + min + ".." + max);
            this.min = min;
            this.max = max;
            this.narrow = narrow;
            this.widen = widen;
        }

        @Override protected T read(Object value, DecodeContext at) {
            if (value instanceof JsonCodec.RejectedNumber r) {
                return at.fail(ApiIssue.Code.INVALID_VALUE, "number " + r.token() + " is " + r.reason());
            }
            if (!isNumber(value)) return at.mismatch(expected(), value);
            BigInteger i = exactInteger(value);
            if (i == null) return at.mismatch(expected(), value);
            if (i.compareTo(BigInteger.valueOf(min)) < 0 || i.compareTo(BigInteger.valueOf(max)) > 0) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + i);
            }
            return narrow.apply(i);
        }

        @Override protected Object write(T value) {
            Objects.requireNonNull(value, "integer");
            return widen.apply(value);
        }

        @Override protected Map<String, Object> schemaBody() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "integer");
            if (min != Integer.MIN_VALUE && min != Long.MIN_VALUE) s.put("minimum", min);
            if (max != Integer.MAX_VALUE && max != Long.MAX_VALUE) s.put("maximum", max);
            return s;
        }

        @Override public String ts(TsContext ts) { return "number"; }

        @Override public String expected() {
            boolean lo = min != Integer.MIN_VALUE && min != Long.MIN_VALUE;
            boolean hi = max != Integer.MAX_VALUE && max != Long.MAX_VALUE;
            if (lo && hi) return "an integer " + min + "–" + max;
            if (lo) return "an integer ≥ " + min;
            if (hi) return "an integer ≤ " + max;
            return "an integer";
        }
    }

    private static final class Int64StringType extends ApiType<Long> {
        private static final Pattern DIGITS = Pattern.compile("-?(0|[1-9][0-9]{0,18})");
        private final long min;

        Int64StringType(long min) { this.min = min; }

        @Override protected Long read(Object value, DecodeContext at) {
            if (!(value instanceof String s)) return at.mismatch(expected(), value);
            if (!DIGITS.matcher(s).matches()) return at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got \"" + s + "\"");
            long v;
            try { v = Long.parseLong(s); } catch (NumberFormatException e) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "\"" + s + "\" is outside the 64-bit range");
            }
            if (v < min) return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + s);
            return v;
        }

        @Override protected Object write(Long value) { return Long.toString(value); }

        @Override protected Map<String, Object> schemaBody() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "string");
            s.put("pattern", "^-?(0|[1-9][0-9]*)$");
            return s;
        }

        @Override public String ts(TsContext ts) { return "string"; }

        @Override public String expected() {
            return "a 64-bit integer as a decimal string" + (min == Long.MIN_VALUE ? "" : " ≥ " + min);
        }
    }

    private static final class NumberType extends ApiType<Double> {
        private final double min, max;

        NumberType(double min, double max) {
            this.min = min;
            this.max = max;
        }

        @Override protected Double read(Object value, DecodeContext at) {
            if (value instanceof JsonCodec.RejectedNumber r) {
                return at.fail(ApiIssue.Code.INVALID_VALUE, "number " + r.token() + " is " + r.reason());
            }
            if (!isNumber(value)) return at.mismatch(expected(), value);
            BigDecimal d = exactDecimal(value);
            if (d == null) return at.mismatch(expected(), value);
            double v = d.doubleValue();
            if (Double.isInfinite(v)) return at.fail(ApiIssue.Code.OUT_OF_RANGE, "number " + d + " does not fit a double");
            if (v < min || v > max) return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + d);
            return v;
        }

        @Override protected Object write(Double value) {
            Objects.requireNonNull(value, "number");
            return value;
        }

        @Override protected Map<String, Object> schemaBody() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "number");
            if (min != Double.NEGATIVE_INFINITY) s.put("minimum", min);
            if (max != Double.POSITIVE_INFINITY) s.put("maximum", max);
            return s;
        }

        @Override public String ts(TsContext ts) { return "number"; }

        @Override public String expected() {
            boolean lo = min != Double.NEGATIVE_INFINITY, hi = max != Double.POSITIVE_INFINITY;
            if (lo && hi) return "a number " + fmt(min) + "–" + fmt(max);
            if (lo) return "a number ≥ " + fmt(min);
            if (hi) return "a number ≤ " + fmt(max);
            return "a finite number";
        }

        private static String fmt(double d) { return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d); }
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

    /** A string type; immutable, so the narrowing methods return new instances. */
    public static final class StringType extends ApiType<String> {
        private final int minLength, maxLength;
        private final Pattern pattern;
        private final String meaning;
        private String tsName, tsType;

        StringType(int minLength, int maxLength, Pattern pattern, String meaning) {
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.pattern = pattern;
            this.meaning = meaning;
        }

        public StringType maxLength(int max) { return copy(minLength, max); }
        public StringType minLength(int min) { return copy(min, maxLength); }

        /**
         * Declare it to TypeScript as {@code type <name> = <tsType>}, a template literal type for a
         * pattern TypeScript can approximate. TypeScript cannot check the whole pattern; the server does.
         */
        public StringType tsAlias(String name, String type) {
            StringType s = copy(minLength, maxLength);
            s.tsName = name;
            s.tsType = type;
            return s;
        }

        private StringType copy(int min, int max) {
            StringType s = new StringType(min, max, pattern, meaning);
            s.tsName = tsName;
            s.tsType = tsType;
            return s;
        }

        @Override protected String read(Object value, DecodeContext at) {
            if (!(value instanceof String s)) return at.mismatch(expected(), value);
            if (s.length() < minLength || s.length() > maxLength) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + s.length() + " characters");
            }
            if (pattern != null && !pattern.matcher(s).matches()) {
                return at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got " + DecodeContext.describe(s));
            }
            return s;
        }

        @Override protected Object write(String value) { return Objects.requireNonNull(value, "string"); }

        @Override protected Map<String, Object> schemaBody() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "string");
            if (minLength > 0) s.put("minLength", minLength);
            if (maxLength != Integer.MAX_VALUE) s.put("maxLength", maxLength);
            if (pattern != null) s.put("pattern", "^(?:" + pattern.pattern() + ")$");
            return s;
        }

        @Override public String ts(TsContext ts) {
            if (tsName == null) return "string";
            return ts.declare(tsName, this, () -> "type " + tsName + " = " + tsType + ";");
        }

        @Override public String expected() {
            if (meaning != null) return meaning;
            if (minLength > 0 && maxLength != Integer.MAX_VALUE) return "a string of " + minLength + "–" + maxLength + " characters";
            if (minLength > 0) return "a non-empty string";
            if (maxLength != Integer.MAX_VALUE) return "a string of at most " + maxLength + " characters";
            return "a string";
        }
    }

    /** Exactly the string {@code value}; a summary's kind, a response's fixed op. */
    public static ApiType<String> literal(String value) {
        return new LiteralType(value);
    }

    private static final class LiteralType extends ApiType<String> {
        private final String value;

        LiteralType(String value) { this.value = Objects.requireNonNull(value); }

        @Override protected String read(Object v, DecodeContext at) {
            if (!(v instanceof String s)) return at.mismatch(expected(), v);
            return value.equals(s) ? s : at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got \"" + s + "\"");
        }
        @Override protected Object write(String v) { return v; }
        @Override protected Map<String, Object> schemaBody() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "string");
            s.put("const", value);
            return s;
        }
        @Override public String ts(TsContext ts) { return TsContext.literal(value); }
        @Override public String expected() { return "\"" + value + "\""; }
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

    private static final class EnumType<E> extends ApiType<E> {
        private final String name;
        private final Map<String, E> byWire = new LinkedHashMap<>();
        private final Map<E, String> toWire = new LinkedHashMap<>();

        EnumType(String name, List<E> values, Function<E, String> wire) {
            this.name = name;
            for (E v : values) {
                String w = wire.apply(v);
                if (byWire.put(w, v) != null) throw new IllegalArgumentException("enum " + name + " spells " + w + " twice");
                toWire.put(v, w);
            }
            if (byWire.isEmpty()) throw new IllegalArgumentException("enum " + name + " has no values");
        }

        @Override protected E read(Object value, DecodeContext at) {
            if (!(value instanceof String s)) return at.mismatch(expected(), value);
            E v = byWire.get(s);
            return v != null ? v : at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got \"" + s + "\"");
        }

        @Override protected Object write(E value) {
            String w = toWire.get(value);
            if (w == null) throw new IllegalStateException(value + " is not a value of " + expected());
            return w;
        }

        @Override protected Map<String, Object> schemaBody() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "string");
            s.put("enum", new ArrayList<>(byWire.keySet()));
            return s;
        }

        @Override public String ts(TsContext ts) {
            StringJoiner j = new StringJoiner(" | ");
            for (String w : byWire.keySet()) j.add(TsContext.literal(w));
            if (name == null) return j.toString();
            return ts.declare(name, this, () -> "type " + name + " = " + j + ";");
        }

        @Override public String expected() { return "one of " + String.join(", ", byWire.keySet()); }
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

    private static final class ListType<T> extends ApiType<List<T>> {
        private final ApiType<T> item;
        private final int minItems, maxItems;

        ListType(ApiType<T> item, int minItems, int maxItems) {
            this.item = Objects.requireNonNull(item);
            this.minItems = minItems;
            this.maxItems = maxItems;
        }

        @Override protected List<T> read(Object value, DecodeContext at) {
            if (!(value instanceof List<?> l)) return at.mismatch(expected(), value);
            if (l.size() < minItems || l.size() > maxItems) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + l.size() + " items");
            }
            int before = at.issues().size();
            List<T> out = new ArrayList<>(l.size());
            for (int i = 0; i < l.size(); i++) {
                DecodeContext eat = at.index(i);
                Object raw = l.get(i);
                if (raw == null) { eat.mismatch(item.expected(), null); continue; }
                out.add(item.decode(raw, eat));
            }
            return at.issues().size() == before ? Collections.unmodifiableList(out) : null;
        }

        @Override protected Object write(List<T> value) {
            List<Object> out = new ArrayList<>(value.size());
            for (T v : value) out.add(item.write(v));
            return out;
        }

        @Override protected Map<String, Object> schemaBody() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "array");
            s.put("items", item.schema());
            if (minItems > 0) s.put("minItems", minItems);
            if (maxItems != Integer.MAX_VALUE) s.put("maxItems", maxItems);
            return s;
        }

        @Override public String ts(TsContext ts) {
            String t = item.ts(ts);
            return t.matches("[A-Za-z0-9_.$<>]+") ? t + "[]" : "Array<" + t + ">";
        }

        @Override public String expected() {
            String size = maxItems == Integer.MAX_VALUE
                    ? (minItems > 0 ? " of at least " + minItems : "")
                    : " of " + minItems + "–" + maxItems;
            return "an array" + size + " of " + item.expected();
        }
    }

    /** An object whose keys are data, every value a {@code value}; at most {@code maxEntries} entries. */
    public static <T> ApiType<Map<String, T>> map(ApiType<T> value, int maxEntries) {
        return new MapType<>(value, maxEntries);
    }

    private static final class MapType<T> extends ApiType<Map<String, T>> {
        private final ApiType<T> value;
        private final int maxEntries;

        MapType(ApiType<T> value, int maxEntries) {
            this.value = value;
            this.maxEntries = maxEntries;
        }

        @Override protected Map<String, T> read(Object v, DecodeContext at) {
            if (!(v instanceof Map<?, ?> m)) return at.mismatch(expected(), v);
            if (m.size() > maxEntries) return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected at most " + maxEntries + " entries, got " + m.size());
            int before = at.issues().size();
            Map<String, T> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                DecodeContext eat = at.field(k);
                if (e.getValue() == null) { eat.mismatch(value.expected(), null); continue; }
                out.put(k, value.decode(e.getValue(), eat));
            }
            return at.issues().size() == before ? Collections.unmodifiableMap(out) : null;
        }

        @Override protected Object write(Map<String, T> v) {
            Map<String, Object> out = new LinkedHashMap<>();
            v.forEach((k, x) -> out.put(k, value.write(x)));
            return out;
        }

        @Override protected Map<String, Object> schemaBody() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("additionalProperties", value.schema());
            s.put("maxProperties", maxEntries);
            return s;
        }

        @Override public String ts(TsContext ts) { return "Record<string, " + value.ts(ts) + ">"; }

        @Override public String expected() { return "an object of " + value.expected(); }
    }

    /** Any JSON value, kept as given; for payloads whose shape is the caller's own. */
    public static ApiType<JsonValue> json() {
        return new JsonType(false);
    }

    /** Any JSON object, kept as given. */
    public static ApiType<JsonValue> jsonObject() {
        return new JsonType(true);
    }

    private static final class JsonType extends ApiType<JsonValue> {
        private final boolean objectOnly;

        JsonType(boolean objectOnly) { this.objectOnly = objectOnly; }

        @Override protected JsonValue read(Object value, DecodeContext at) {
            if (objectOnly && !(value instanceof Map<?, ?>)) return at.mismatch(expected(), value);
            int before = at.issues().size();
            scan(value, at, 0);
            if (at.issues().size() != before) return null;
            try {
                return JsonValue.of(value);
            } catch (IllegalArgumentException e) {
                return at.fail(ApiIssue.Code.INVALID_VALUE, e.getMessage());
            }
        }

        /** Over-budget numbers are reported where they sit, not as the whole payload. */
        private static void scan(Object v, DecodeContext at, int depth) {
            if (v instanceof JsonCodec.RejectedNumber r) {
                at.fail(ApiIssue.Code.INVALID_VALUE, "number " + r.token() + " is " + r.reason());
            } else if (v instanceof Map<?, ?> m && depth < 64) {
                for (Map.Entry<?, ?> e : m.entrySet()) scan(e.getValue(), at.field(String.valueOf(e.getKey())), depth + 1);
            } else if (v instanceof List<?> l && depth < 64) {
                for (int i = 0; i < l.size(); i++) scan(l.get(i), at.index(i), depth + 1);
            }
        }

        @Override protected Object write(JsonValue value) { return value.tree(); }

        @Override protected Map<String, Object> schemaBody() {
            return objectOnly ? new LinkedHashMap<>(Map.of("type", "object")) : new LinkedHashMap<>();
        }

        @Override public String ts(TsContext ts) {
            String json = ts.declare("Json", JsonType.class, () ->
                    "type Json = null | boolean | number | string | Json[] | { [key: string]: Json };");
            return objectOnly ? "{ [key: string]: " + json + " }" : json;
        }

        @Override public String expected() { return objectOnly ? "a JSON object" : "a JSON value"; }
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
     * A {@code {key, value}} pair whose value has the type {@code types} gives its key, in that map's order;
     * {@code docs} describes the keys that have a description.
     */
    public static KeyedValueType keyedValue(String name, Map<String, ApiType<?>> types, Map<String, String> docs) {
        return new KeyedValueType(name, types, docs);
    }
}
