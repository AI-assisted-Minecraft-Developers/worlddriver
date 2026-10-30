package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The numeric types behind {@link ApiTypes#integer}, {@link ApiTypes#int64}, {@link ApiTypes#int64String} and
 * {@link ApiTypes#number}.
 */
final class NumberTypes {
    private NumberTypes() {}

    /**
     * The exact decimal a JSON number denotes, or null when it is no finite number. A parsed number keeps the text it
     * was written as, so no digit is rounded on the way in.
     */
    static BigDecimal exactDecimal(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : new BigDecimal(Double.toString(d));
        }
        try {
            return new BigDecimal(n.toString());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    static final class IntegerType<T extends Number> extends ApiType<T> {
        private final long min;
        private final long max;
        private final Function<Long, T> narrow;
        private final Function<T, Long> widen;

        IntegerType(long min, long max, Function<Long, T> narrow, Function<T, Long> widen) {
            if (min > max) {
                throw new IllegalArgumentException("empty range " + min + ".." + max);
            }
            this.min = min;
            this.max = max;
            this.narrow = narrow;
            this.widen = widen;
        }

        @Override
        protected T read(JsonElement value, DecodeContext at) {
            Number n = Json.number(value);
            BigDecimal d = n == null ? null : exactDecimal(n);
            // Integral and in range are both read off the decimal as written: 1e999999999 is answered without
            // spelling out its digits.
            if (d == null || (d.signum() != 0 && d.stripTrailingZeros().scale() > 0)) {
                return at.mismatch(expected(), value);
            }
            if (d.compareTo(BigDecimal.valueOf(min)) < 0 || d.compareTo(BigDecimal.valueOf(max)) > 0) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + n);
            }
            return narrow.apply(d.longValue());
        }

        @Override
        protected JsonElement write(T value) {
            Objects.requireNonNull(value, "integer");
            return new JsonPrimitive(widen.apply(value));
        }

        @Override
        protected JsonObject schemaBody() {
            JsonObject s = new JsonObject();
            s.addProperty("type", "integer");
            if (min != Integer.MIN_VALUE && min != Long.MIN_VALUE) {
                s.addProperty("minimum", min);
            }
            if (max != Integer.MAX_VALUE && max != Long.MAX_VALUE) {
                s.addProperty("maximum", max);
            }
            return s;
        }

        @Override
        public String ts(TsContext ts) {
            return "number";
        }

        @Override
        public String expected() {
            boolean lo = min != Integer.MIN_VALUE && min != Long.MIN_VALUE;
            boolean hi = max != Integer.MAX_VALUE && max != Long.MAX_VALUE;
            if (lo && hi) {
                return "an integer " + min + "–" + max;
            }
            if (lo) {
                return "an integer ≥ " + min;
            }
            if (hi) {
                return "an integer ≤ " + max;
            }
            return "an integer";
        }
    }

    static final class Int64StringType extends ApiType<Long> {
        private static final Pattern DIGITS = Pattern.compile("-?(0|[1-9][0-9]{0,18})");
        private final long min;

        Int64StringType(long min) {
            this.min = min;
        }

        @Override
        protected Long read(JsonElement value, DecodeContext at) {
            String s = Json.string(value);
            if (s == null) {
                return at.mismatch(expected(), value);
            }
            if (!DIGITS.matcher(s).matches()) {
                return at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got \"" + s + "\"");
            }
            long v;
            try {
                v = Long.parseLong(s);
            } catch (NumberFormatException e) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "\"" + s + "\" is outside the 64-bit range");
            }
            if (v < min) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + s);
            }
            return v;
        }

        @Override
        protected JsonElement write(Long value) {
            return new JsonPrimitive(Long.toString(value));
        }

        @Override
        protected JsonObject schemaBody() {
            JsonObject s = new JsonObject();
            s.addProperty("type", "string");
            s.addProperty("pattern", "^-?(0|[1-9][0-9]*)$");
            return s;
        }

        @Override
        public String ts(TsContext ts) {
            return "string";
        }

        @Override
        public String expected() {
            return "a 64-bit integer as a decimal string" + (min == Long.MIN_VALUE ? "" : " ≥ " + min);
        }
    }

    static final class NumberType extends ApiType<Double> {
        private final double min;
        private final double max;

        NumberType(double min, double max) {
            this.min = min;
            this.max = max;
        }

        @Override
        protected Double read(JsonElement value, DecodeContext at) {
            Number n = Json.number(value);
            if (n == null || exactDecimal(n) == null) {
                return at.mismatch(expected(), value);
            }
            double v = n.doubleValue();
            if (Double.isInfinite(v)) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "number " + n + " does not fit a double");
            }
            if (v < min || v > max) {
                return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + n);
            }
            return v;
        }

        @Override
        protected JsonElement write(Double value) {
            Objects.requireNonNull(value, "number");
            return new JsonPrimitive(value);
        }

        @Override
        protected JsonObject schemaBody() {
            JsonObject s = new JsonObject();
            s.addProperty("type", "number");
            if (min != Double.NEGATIVE_INFINITY) {
                s.addProperty("minimum", min);
            }
            if (max != Double.POSITIVE_INFINITY) {
                s.addProperty("maximum", max);
            }
            return s;
        }

        @Override
        public String ts(TsContext ts) {
            return "number";
        }

        @Override
        public String expected() {
            boolean lo = min != Double.NEGATIVE_INFINITY;
            boolean hi = max != Double.POSITIVE_INFINITY;
            if (lo && hi) {
                return "a number " + fmt(min) + "–" + fmt(max);
            }
            if (lo) {
                return "a number ≥ " + fmt(min);
            }
            if (hi) {
                return "a number ≤ " + fmt(max);
            }
            return "a finite number";
        }

        private static String fmt(double d) {
            return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
        }
    }
}
