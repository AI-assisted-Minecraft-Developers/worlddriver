package net.magicterra.agent.rpc;

import net.magicterra.agent.model.AgentEvent;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;
import java.util.*;

/** Hand-rolled JSON codec. Sufficient for our RPC use; no external dep. */
public final class JsonCodec {

    // ============================================================
    //                          ENCODE
    // ============================================================
    public static String encode(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeStr(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Number n) {
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
        if (v instanceof BlockPos bp) {
            sb.append("{\"x\":").append(bp.getX()).append(",\"y\":").append(bp.getY()).append(",\"z\":").append(bp.getZ()).append("}");
            return;
        }
        if (v instanceof AgentEvent ae) {
            sb.append("{\"seq\":").append(ae.seq)
              .append(",\"timestamp\":").append(ae.timestamp)
              .append(",\"type\":"); writeStr(sb, ae.type);
            sb.append(",\"pos\":"); write(sb, ae.pos);
            sb.append(",\"data\":"); writeStr(sb, ae.data);
            sb.append("}");
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
                write(sb, e.getValue());
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
                write(sb, e);
            }
            sb.append(']');
            return;
        }
        // POJO with public final fields — best effort fallback
        try {
            Map<String, Object> bag = new LinkedHashMap<>();
            for (Field f : v.getClass().getFields()) {
                bag.put(f.getName(), f.get(v));
            }
            write(sb, bag);
        } catch (Exception ex) {
            writeStr(sb, String.valueOf(v));
        }
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
    public static Object decode(String json) {
        Parser p = new Parser(json);
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos < p.src.length()) throw new RuntimeException("trailing garbage at " + p.pos);
        return v;
    }

    private static final class Parser {
        final String src; int pos;
        Parser(String s) { this.src = s; }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) throw new RuntimeException("unexpected eof");
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') { expect("null"); return null; }
            // Lenient: a producer (notably Rhino's JSON.stringify over wrapped
            // Java doubles) can emit the non-standard literals NaN / Infinity /
            // -Infinity. Accept them as null so the round-trip never crashes.
            if (c == 'N') { expect("NaN"); return null; }
            if (c == 'I') { expect("Infinity"); return null; }
            if (c == '-' && src.startsWith("-Infinity", pos)) { pos += 9; return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            expect("{");
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) { pos++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs(); expect(":");
                Object v = parseValue();
                m.put(k, v);
                skipWs();
                if (peek(',')) { pos++; continue; }
                if (peek('}')) { pos++; return m; }
                throw new RuntimeException("expected , or } at " + pos);
            }
        }

        List<Object> parseArray() {
            expect("[");
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek(']')) { pos++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (peek(',')) { pos++; continue; }
                if (peek(']')) { pos++; return l; }
                throw new RuntimeException("expected , or ] at " + pos);
            }
        }

        String parseString() {
            expect("\"");
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) throw new RuntimeException("eof in escape");
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
                            String h = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(h, 16));
                            pos += 4;
                        }
                        default -> throw new RuntimeException("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
            throw new RuntimeException("unterminated string");
        }

        Boolean parseBool() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new RuntimeException("expected bool at " + pos);
        }

        Number parseNumber() {
            int s = pos;
            if (peek('-')) pos++;
            while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) pos++;
            String n = src.substring(s, pos);
            // Zero-width / degenerate consume means we dispatched to a number on
            // a non-number char (parser desync). Fail with context instead of a
            // cryptic NumberFormatException ("For input string: \"\"").
            if (n.isEmpty() || n.equals("-") || n.equals("+") || n.equals(".")) {
                throw new RuntimeException("invalid JSON: expected value at " + s + " near '"
                        + src.substring(s, Math.min(src.length(), s + 12)) + "'");
            }
            try {
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                return Long.parseLong(n);
            } catch (NumberFormatException nfe) {
                // Out-of-long-range integers (or odd forms) degrade to double
                // rather than crashing the whole decode.
                return Double.parseDouble(n);
            }
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
        boolean peek(char c) { return pos < src.length() && src.charAt(pos) == c; }
        void expect(String s) {
            skipWs();
            if (!src.startsWith(s, pos)) throw new RuntimeException("expected " + s + " at " + pos);
            pos += s.length();
        }
    }
}
