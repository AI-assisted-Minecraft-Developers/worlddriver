package net.magicterra.agent.model;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed view over a transport params {@code Map<String,Object>}. This is the
 * single place JSON-shaped values are coerced to Java types: every handler
 * wraps its inbound map once ({@code Params p = Params.of(params)}) and reads
 * fields through the typed getters instead of poking {@code map.get(...)} and
 * re-deriving {@code instanceof}/default/clamp logic on its own.
 *
 * <p>The {@code Map<String,Object>} stays the wire/dispatch contract (it mirrors
 * parsed JSON and lets the three transports share one byte-identical dispatch);
 * {@code Params} only stops that map from leaking, untyped, into handler bodies.
 *
 * <p>The {@code toX} statics carry the canonical coercion semantics so the
 * legacy helpers ({@code ApiSupport.numL}, {@code BotUtil.intOr}, …) can delegate
 * here and there is exactly one implementation of each rule.
 */
public final class Params {

    private final Map<String, Object> m;

    private Params(Map<String, Object> m) {
        this.m = (m == null) ? Map.of() : m;
    }

    public static Params of(Map<String, Object> m) {
        return new Params(m);
    }

    /** The backing map, for handlers that still pass the raw params onward. */
    public Map<String, Object> map() { return m; }

    public Object get(String key) { return m.get(key); }
    public Object getOrDefault(String key, Object dflt) { return m.getOrDefault(key, dflt); }
    /** True when the key is present (even if mapped to null/false) — mirrors {@code Map.containsKey}. */
    public boolean has(String key) { return m.containsKey(key); }
    /** True when the key maps to a non-null value. */
    public boolean present(String key) { return m.get(key) != null; }

    public int getInt(String key) { return toInt(m.get(key), 0); }
    public int getInt(String key, int dflt) { return toInt(m.get(key), dflt); }
    public int getIntClamped(String key, int dflt, int lo, int hi) { return clamp(getInt(key, dflt), lo, hi); }

    public long getLong(String key) { return toLong(m.get(key), 0L); }
    public long getLong(String key, long dflt) { return toLong(m.get(key), dflt); }
    public long getLongClamped(String key, long dflt, long lo, long hi) { return clamp(getLong(key, dflt), lo, hi); }

    public double getDouble(String key) { return toDouble(m.get(key), 0.0); }
    public double getDouble(String key, double dflt) { return toDouble(m.get(key), dflt); }

    /** Truthy-by-presence: true only when explicitly {@code true} (mirrors {@code Boolean.TRUE.equals}). */
    public boolean getBool(String key) { return Boolean.TRUE.equals(m.get(key)); }
    /** Defaulted: use {@code dflt} unless an explicit boolean is present. */
    public boolean getBool(String key, boolean dflt) { return m.get(key) instanceof Boolean b ? b : dflt; }

    public String getString(String key) { return m.get(key) instanceof String s ? s : null; }
    public String getString(String key, String dflt) { return m.get(key) instanceof String s ? s : dflt; }

    /** A present, non-blank string, else null — the common "optional name/id" shape. */
    public String getNonBlank(String key) {
        return m.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    /** A numeric field as a boxed Float, or null when absent/non-numeric (e.g. optional yaw/pitch). */
    public Float getFloat(String key) {
        return m.get(key) instanceof Number n ? n.floatValue() : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        return m.get(key) instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
    }

    /** Nested params object; empty (not null) when absent so callers can chain. */
    public Params sub(String key) { return new Params(getMap(key)); }

    public List<String> getStringList(String key) { return toStringList(m.get(key)); }

    /** Decode {@code {x,y,z} | BlockPos | "x,y,z"} to a BlockPos; null if no shape matches. */
    public BlockPos getPos(String key) { return toPos(m.get(key)); }

    // ---- canonical coercion rules (single implementation; delegated to by legacy helpers) ----

    public static int toInt(Object o, int dflt) { return o instanceof Number n ? n.intValue() : dflt; }
    public static long toLong(Object o, long dflt) { return o instanceof Number n ? n.longValue() : dflt; }
    public static double toDouble(Object o, double dflt) { return o instanceof Number n ? n.doubleValue() : dflt; }

    public static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    public static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }

    public static List<String> toStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object e : l) if (e instanceof String s) out.add(s);
        } else if (o instanceof String s) {
            out.add(s);
        }
        return out;
    }

    public static BlockPos toPos(Object o) {
        if (o instanceof BlockPos bp) return bp;
        if (o instanceof Map<?, ?> mm
                && mm.get("x") instanceof Number nx
                && mm.get("y") instanceof Number ny
                && mm.get("z") instanceof Number nz) {
            return new BlockPos(nx.intValue(), ny.intValue(), nz.intValue());
        }
        if (o instanceof String s) {
            try { return parsePos(s); } catch (Exception e) { return null; }
        }
        return null;
    }

    /** Parse a {@code "x,y,z"} token to a BlockPos. Throws on a malformed token. */
    public static BlockPos parsePos(String s) {
        String[] p = s.split(",");
        return new BlockPos(
            Integer.parseInt(p[0].trim()),
            Integer.parseInt(p[1].trim()),
            Integer.parseInt(p[2].trim()));
    }
}
