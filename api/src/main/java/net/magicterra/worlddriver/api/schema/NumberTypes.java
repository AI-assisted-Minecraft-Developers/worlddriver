package net.magicterra.worlddriver.api.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

import net.magicterra.worlddriver.protocol.RejectedNumber;

/** The numeric types behind {@link ApiTypes#integer}, {@link ApiTypes#int64}, {@link ApiTypes#int64String} and {@link ApiTypes#number}. */
final class NumberTypes {
    private NumberTypes() {}

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

    static final class IntegerType<T extends Number> extends ApiType<T> {
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
            if (value instanceof RejectedNumber r) {
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

    static final class Int64StringType extends ApiType<Long> {
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

    static final class NumberType extends ApiType<Double> {
        private final double min, max;

        NumberType(double min, double max) {
            this.min = min;
            this.max = max;
        }

        @Override protected Double read(Object value, DecodeContext at) {
            if (value instanceof RejectedNumber r) {
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
}
