package net.magicterra.worlddriver.protocol;

import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.function.UnaryOperator;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.magicterra.worlddriver.api.contract.RejectedNumber;

/** Hand-rolled JSON codec. Sufficient for our RPC use; no external dep. */
public final class JsonCodec {

    // ============================================================
    //                          ENCODE
    // ============================================================
    public static String encode(Object v) {
        return encode(v, UnaryOperator.identity());
    }

    /** Domain adaptation belongs to the caller and applies recursively to nested values. */
    public static String encode(Object v, UnaryOperator<Object> adapter) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, adapter);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, UnaryOperator<Object> adapter) {
        v = adapter.apply(v);
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeStr(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Number n) {
            // Integral types never go through double: its 53-bit mantissa rewrites the low
            // bits of a 64-bit value such as a world seed.
            if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte
                    || n instanceof AtomicLong || n instanceof AtomicInteger) {
                sb.append(n.longValue());
                return;
            }
            if (n instanceof BigInteger bi) { sb.append(bi); return; }
            if (n instanceof BigDecimal bd) {
                BigDecimal s = bd.stripTrailingZeros();
                sb.append(s.scale() <= 0 ? s.toPlainString() : s.toString());
                return;
            }
            double d = n.doubleValue();
            // JSON has no NaN/Infinity literal. Emit null (matches JS
            // JSON.stringify) so the wire stays valid JSON — otherwise a stray
            // non-finite (e.g. an unreachable-path cost) produces a bare
            // `NaN`/`Infinity` token that no compliant parser, including our
            // own decode(), can read back.
            if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
            if (d == (long) d) sb.append((long) d);
            else sb.append(d);
            return;
        }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeStr(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), adapter);
            }
            sb.append('}');
            return;
        }
        if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object e : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, e, adapter);
            }
            sb.append(']');
            return;
        }
        // Enums BEFORE the reflection fallback: Class.getFields() on an enum returns its
        // own constants, which are of the enum's own type, so the fallback recursed forever
        // and blew the stack with a StackOverflowError that `catch (Exception)` cannot catch.
        if (v instanceof Enum<?> en) { writeStr(sb, en.name()); return; }
        if (v instanceof CharSequence cs) { writeStr(sb, cs.toString()); return; }
        // Arrays: getFields() returns ZERO fields for any array type, so the reflection
        // fallback silently encoded int[]/String[] as `{}` — the RPC/MCP transports lost the
        // data outright while an in-JVM Rhino caller got the real array. Encode as a JSON
        // array, which is what every caller means.
        if (v.getClass().isArray()) {
            sb.append('[');
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                write(sb, Array.get(v, i), adapter);
            }
            sb.append(']');
            return;
        }
        // Records: their components are PRIVATE final fields, so getFields() is empty here too
        // and the fallback produced `{}`. Use the record components, which are always readable.
        if (v.getClass().isRecord()) {
            Map<String, Object> bag = new LinkedHashMap<>();
            try {
                for (RecordComponent rc : v.getClass().getRecordComponents()) {
                    bag.put(rc.getName(), rc.getAccessor().invoke(v));
                }
            } catch (ReflectiveOperationException ex) {
                throw new IllegalArgumentException(
                        "JsonCodec cannot encode record " + v.getClass().getName() + ": " + ex, ex);
            }
            write(sb, bag, adapter);
            return;
        }
        // POJO with public fields (e.g. a Vec3-shaped holder) — correct for that shape.
        // Anything else is a bug at the HANDLER, not here: a route that returns a type this
        // codec cannot represent produces a DIFFERENT result on the RPC/MCP transports than
        // it does in-JVM, which silently violates the byte-identical parity invariant every
        // transport is built on. Fail loudly instead — both transports already turn a thrown
        // exception into an error response, so this surfaces at the offending route.
        Map<String, Object> bag = new LinkedHashMap<>();
        Field[] fields = v.getClass().getFields();
        if (fields.length == 0) {
            throw new IllegalArgumentException(
                    "JsonCodec cannot encode " + v.getClass().getName()
                    + " (no public fields) - return a Map/List/primitive from the route");
        }
        try {
            for (Field f : fields) bag.put(f.getName(), f.get(v));
        } catch (IllegalAccessException ex) {
            throw new IllegalArgumentException(
                    "JsonCodec cannot encode " + v.getClass().getName() + ": " + ex, ex);
        }
        write(sb, bag, adapter);
    }

    private static void writeStr(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ============================================================
    //                          DECODE
    // ============================================================

    /** Longest number token {@link #decode} turns into a value. */
    public static final int MAX_NUMBER_CHARS = 100;
    /** Largest exponent magnitude {@link #decode} turns into a value: BigDecimal's toPlainString
     *  of {@code 1e999999999} would spell out a billion zeros. */
    public static final int MAX_EXPONENT = 400;

    /** Thrown for input that is not JSON, including a repeated object key. */
    public static final class JsonSyntaxException extends RuntimeException {
        public JsonSyntaxException(String message) { super(message); }
    }

    /**
     * Strict JSON: integers without fraction or exponent become Long (BigInteger past its range),
     * every other number an exact BigDecimal, so no digit is rounded on the way in; a repeated key
     * in one object is a syntax error rather than a silent overwrite.
     */
    public static Object decode(String json) {
        Parser p = new Parser(json);
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos < p.src.length()) throw new JsonSyntaxException("trailing garbage at " + p.pos);
        return v;
    }

    /** Maximum container nesting {@link #decode} will accept.
     *
     *  <p>The parser is recursive descent, so nesting depth maps 1:1 onto JVM stack frames:
     *  a 16 KB body of nothing but {@code [[[[…} blew the stack at ~8000 levels on a stock
     *  1 MB stack. That is far below BOTH transport caps (MCP allows an 8 MiB body; the WS
     *  frame cap is larger still), so an untrusted peer could reach it with a trivial payload.
     *  The transports catch Throwable and answer with an error, but a caught StackOverflowError
     *  leaves the worker thread in an undefined state — cheaper to refuse the input.
     *  64 is far past anything our own schemas produce (the deepest is ~6). */
    private static final int MAX_DEPTH = 64;

    private static final class Parser {
        final String src; int pos; int depth;
        Parser(String s) { this.src = s; }

        void enter() {
            if (++depth > MAX_DEPTH) {
                throw new JsonSyntaxException("JSON nested deeper than " + MAX_DEPTH + " at " + pos);
            }
        }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) throw new JsonSyntaxException("unexpected eof");
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') { expect("null"); return null; }
            // NaN and Infinity are not JSON, and a request carrying one names no value any
            // field could hold; JSON.stringify writes null for them, never the literal.
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            expect("{");
            enter();
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) { pos++; depth--; return m; }
            while (true) {
                skipWs();
                int keyAt = pos;
                String k = parseString();
                skipWs(); expect(":");
                Object v = parseValue();
                // A parsed tree cannot show which of two values a key had; only the parse can.
                if (m.containsKey(k)) throw new JsonSyntaxException("duplicate key \"" + k + "\" at " + keyAt);
                m.put(k, v);
                skipWs();
                if (peek(',')) { pos++; continue; }
                if (peek('}')) { pos++; depth--; return m; }
                throw new JsonSyntaxException("expected , or } at " + pos);
            }
        }

        List<Object> parseArray() {
            expect("[");
            enter();
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek(']')) { pos++; depth--; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                if (peek(']')) { pos++; depth--; return l; }
                throw new JsonSyntaxException("expected , or ] at " + pos);
            }
        }

        String parseString() {
            expect("\"");
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) throw new JsonSyntaxException("eof in escape");
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            // Bounds-check before substring: an escape truncated at end-of-input
                            // (fewer than four hex digits left) otherwise surfaced as
                            // StringIndexOutOfBoundsException, which the transports report
                            // verbatim ("parse: begin 41, end 45, length 43") — a message that
                            // names no JSON construct and helps nobody.
                            // NB: do not write a backslash-u sequence in a comment; javac
                            // decodes unicode escapes before parsing, even inside comments.
                            if (pos + 4 > src.length()) {
                                throw new JsonSyntaxException("truncated \\u escape at " + pos);
                            }
                            String h = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(h, 16));
                            pos += 4;
                        }
                        default -> throw new JsonSyntaxException("bad escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw new JsonSyntaxException("unescaped control character in string at " + (pos - 1));
                } else sb.append(c);
            }
            throw new JsonSyntaxException("unterminated string");
        }

        Boolean parseBool() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonSyntaxException("expected bool at " + pos);
        }

        /** RFC 8259 number grammar: {@code -? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)?}. */
        Object parseNumber() {
            int s = pos;
            if (peek('-')) pos++;
            if (peek('0')) {
                pos++;
            } else if (pos < src.length() && src.charAt(pos) >= '1' && src.charAt(pos) <= '9') {
                skipDigits();
            } else {
                throw new JsonSyntaxException("invalid JSON: expected value at " + s + " near '"
                        + src.substring(s, Math.min(src.length(), s + 12)) + "'");
            }
            boolean integral = true;
            if (peek('.')) {
                pos++;
                if (skipDigits() == 0) throw new JsonSyntaxException("digit expected after '.' at " + pos);
                integral = false;
            }
            int expStart = -1;
            if (peek('e') || peek('E')) {
                pos++;
                if (peek('+') || peek('-')) pos++;
                expStart = pos;
                if (skipDigits() == 0) throw new JsonSyntaxException("digit expected in exponent at " + pos);
                integral = false;
            }
            String n = src.substring(s, pos);
            if (n.length() > MAX_NUMBER_CHARS) {
                return new RejectedNumber(abbreviate(n), "longer than " + MAX_NUMBER_CHARS + " characters");
            }
            if (expStart >= 0) {
                String digits = src.substring(expStart, pos).replaceFirst("^0+(?=.)", "");
                if (digits.length() > 3 || Integer.parseInt(digits) > MAX_EXPONENT) {
                    return new RejectedNumber(n, "exponent beyond ±" + MAX_EXPONENT);
                }
            }
            if (!integral) return new BigDecimal(n);
            // The grammar has already been checked, so these parse: out of long range is a BigInteger,
            // never a double that would rewrite its low digits.
            try {
                return Long.parseLong(n);
            } catch (NumberFormatException beyondLong) {
                return new BigInteger(n);
            }
        }

        private int skipDigits() {
            int from = pos;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            return pos - from;
        }

        private static String abbreviate(String token) {
            return token.length() <= 24 ? token : token.substring(0, 20) + "…";
        }

        void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
                pos++;
            }
        }
        boolean peek(char c) { return pos < src.length() && src.charAt(pos) == c; }
        void expect(String s) {
            skipWs();
            if (!src.startsWith(s, pos)) throw new JsonSyntaxException("expected " + s + " at " + pos);
            pos += s.length();
        }
    }
}
