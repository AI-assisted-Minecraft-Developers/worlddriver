package net.magicterra.worlddriver.protocol;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON text to value trees and back, on Gson's streaming reader and writer.
 *
 * <p>A value tree is a Map with String keys, a List, a String, a Boolean, a number or null. The reader
 * is stricter than Gson's own tree model: a repeated key is a syntax error rather than a silent
 * overwrite, numbers keep every digit, nesting is bounded, and the two leniencies Gson 2.10's
 * non-lenient mode still has (raw control characters inside a string, the {@code \'} escape) are
 * refused by {@link Rfc8259Strings}.
 *
 * <p>Two number tokens never reach {@link RejectedNumber} because Gson cannot lex them, so they fail
 * the whole parse: one past its 1024-character buffer, and an integer of 66 or more digits whose
 * running value wraps to zero, which it mistakes for a leading zero. Both are refused either way, and
 * no contract field holds a number that long; only the error is coarser.
 */
public final class JsonCodec {
    private JsonCodec() {}

    /** Longest number token {@link #decode} turns into a value. */
    public static final int MAX_NUMBER_CHARS = 100;
    /** Largest exponent magnitude {@link #decode} turns into a value: BigDecimal's toPlainString
     *  of {@code 1e999999999} would spell out a billion zeros. */
    public static final int MAX_EXPONENT = 400;
    /**
     * Maximum container nesting either direction accepts. Building the tree recurses once per level,
     * and both transport caps admit bodies far deeper than a stock stack survives; 64 is far past
     * anything the schemas produce (the deepest is about 6).
     */
    private static final int MAX_DEPTH = 64;

    /** Thrown for input that is not JSON, including a repeated object key. */
    public static final class JsonSyntaxException extends RuntimeException {
        public JsonSyntaxException(String message) { super(message); }
    }

    // ------------------------------------------------------------------ encode

    /**
     * The JSON text of a value tree: Map with String keys, List, String, Boolean, an integral number,
     * BigInteger, BigDecimal, a finite Double or Float, or null. Anything else is a caller bug and
     * throws {@link IllegalArgumentException}: a domain value reaches the wire through its contract's
     * {@code ApiType.encode}, never by being guessed at here.
     */
    public static String encode(Object v) {
        StringWriter out = new StringWriter();
        try (JsonWriter w = new JsonWriter(out)) {
            write(w, v, 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static void write(JsonWriter w, Object v, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("value nested deeper than " + MAX_DEPTH);
        if (v == null) { w.nullValue(); return; }
        if (v instanceof String s) { w.value(s); return; }
        if (v instanceof Boolean b) { w.value(b.booleanValue()); return; }
        if (v instanceof Number n) { w.jsonValue(number(n)); return; }
        if (v instanceof Map<?, ?> m) {
            w.beginObject();
            for (var e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    throw new IllegalArgumentException("object key " + e.getKey() + " is not a string");
                }
                w.name(k);
                write(w, e.getValue(), depth + 1);
            }
            w.endObject();
            return;
        }
        if (v instanceof List<?> l) {
            w.beginArray();
            for (Object e : l) write(w, e, depth + 1);
            w.endArray();
            return;
        }
        throw notATree(v);
    }

    /** A number's JSON text. Integral types never go through double, whose 53-bit mantissa would
     *  rewrite the low bits of a 64-bit value such as a world seed. */
    private static String number(Number n) {
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return Long.toString(n.longValue());
        }
        if (n instanceof BigInteger bi) return bi.toString();
        if (n instanceof BigDecimal bd) {
            BigDecimal s = bd.stripTrailingZeros();
            return s.scale() <= 0 ? s.toPlainString() : s.toString();
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            // JSON has no NaN or Infinity, and writing null in their place hides the bug that
            // produced one; the value's owner says what an absent number means instead.
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("non-finite number " + d + " is not JSON");
            }
            return d == (long) d ? Long.toString((long) d) : Double.toString(d);
        }
        throw notATree(n);
    }

    private static IllegalArgumentException notATree(Object v) {
        return new IllegalArgumentException(v.getClass().getName() + " is not a JSON value; "
                + "encode it through its contract or build the tree yourself");
    }

    // ------------------------------------------------------------------ decode

    /**
     * The value tree of {@code json}. Integers without fraction or exponent become Long (BigInteger
     * past its range), every other number an exact BigDecimal, so no digit is rounded on the way in;
     * a number past {@link #MAX_NUMBER_CHARS} or {@link #MAX_EXPONENT} becomes a {@link RejectedNumber}.
     */
    public static Object decode(String json) {
        return decode(new StringReader(json));
    }

    /** {@link #decode(String)} reading from {@code in}, which is not closed. */
    public static Object decode(Reader in) {
        JsonReader r = new JsonReader(new Rfc8259Strings(in));
        r.setLenient(false);
        try {
            Object v = read(r, 0);
            if (r.peek() != JsonToken.END_DOCUMENT) throw new JsonSyntaxException("trailing data at " + r.getPath());
            return v;
        } catch (JsonSyntaxException e) {
            throw e;
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            // Gson reports malformed input as MalformedJsonException (an IOException), an EOFException,
            // or IllegalStateException for a token where another was due.
            throw new JsonSyntaxException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static Object read(JsonReader r, int depth) throws IOException {
        JsonToken t = r.peek();
        switch (t) {
            case BEGIN_OBJECT -> {
                if (depth >= MAX_DEPTH) throw new JsonSyntaxException("JSON nested deeper than " + MAX_DEPTH + " at " + r.getPath());
                Map<String, Object> m = new LinkedHashMap<>();
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    // A parsed tree cannot show which of two values a key had; only the parse can.
                    if (m.containsKey(k)) throw new JsonSyntaxException("duplicate key \"" + k + "\" at " + r.getPath());
                    m.put(k, read(r, depth + 1));
                }
                r.endObject();
                return m;
            }
            case BEGIN_ARRAY -> {
                if (depth >= MAX_DEPTH) throw new JsonSyntaxException("JSON nested deeper than " + MAX_DEPTH + " at " + r.getPath());
                List<Object> l = new ArrayList<>();
                r.beginArray();
                while (r.hasNext()) l.add(read(r, depth + 1));
                r.endArray();
                return l;
            }
            case STRING -> { return r.nextString(); }
            case BOOLEAN -> { return r.nextBoolean(); }
            case NULL -> { r.nextNull(); return null; }
            // On a NUMBER token nextString() answers the literal as written, already held to the
            // RFC 8259 grammar by the non-lenient reader.
            case NUMBER -> { return number(r.nextString()); }
            default -> throw new JsonSyntaxException("unexpected " + t + " at " + r.getPath());
        }
    }

    private static Object number(String n) {
        if (n.length() > MAX_NUMBER_CHARS) {
            return new RejectedNumber(n.length() <= 24 ? n : n.substring(0, 20) + "…",
                    "longer than " + MAX_NUMBER_CHARS + " characters");
        }
        int e = Math.max(n.indexOf('e'), n.indexOf('E'));
        if (e >= 0) {
            String digits = n.substring(e + 1).replaceFirst("^[+-]", "").replaceFirst("^0+(?=.)", "");
            if (digits.length() > 3 || Integer.parseInt(digits) > MAX_EXPONENT) {
                return new RejectedNumber(n, "exponent beyond ±" + MAX_EXPONENT);
            }
        }
        if (e >= 0 || n.indexOf('.') >= 0) return new BigDecimal(n);
        // Out of long range is a BigInteger, never a double that would rewrite its low digits.
        try {
            return Long.parseLong(n);
        } catch (NumberFormatException beyondLong) {
            return new BigInteger(n);
        }
    }

    /**
     * The input with the two string forms RFC 8259 forbids and Gson 2.10's non-lenient reader still
     * takes: an unescaped control character inside a string, and an escape other than
     * {@code \" \\ \/ \b \f \n \r \t \}u. It tracks only whether it is inside a string; everything
     * else is the reader's to judge.
     */
    private static final class Rfc8259Strings extends FilterReader {
        private boolean inString;
        private boolean escaped;
        private long offset;

        Rfc8259Strings(Reader in) { super(in); }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) check((char) c);
            return c;
        }

        @Override
        public int read(char[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            for (int i = 0; i < n; i++) check(buf[off + i]);
            return n;
        }

        private void check(char c) {
            long at = offset++;
            if (!inString) {
                if (c == '"') inString = true;
                return;
            }
            if (escaped) {
                escaped = false;
                if ("\"\\/bfnrtu".indexOf(c) < 0) throw new JsonSyntaxException("bad escape \\" + c + " at " + at);
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = false;
            } else if (c < 0x20) {
                throw new JsonSyntaxException("unescaped control character in string at " + at);
            }
        }
    }
}
