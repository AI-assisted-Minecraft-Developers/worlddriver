package net.magicterra.agent.api;

import net.magicterra.agent.model.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, stateless helpers shared by {@link AgentApi} and the {@code *Api}
 * sub-handlers ({@link SystemApi}, {@link ObserveApi}, {@link ActionApi},
 * {@link WaitApi}). Extracted from {@code AgentApi} so the handler bodies can
 * live in their own files without a back-reference just for parsing/encoding.
 */
final class ApiSupport {
    private ApiSupport() {}

    static long numL(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Parse a coord token as an absolute decimal int. Returns null for
     *  Brigadier-style relative ({@code ~}, {@code ~N}, {@code ^N}) or otherwise
     *  non-integer tokens; callers fall back to the full command dispatcher
     *  rather than letting Integer.parseInt throw a raw NumberFormatException. */
    static Integer parseAbsInt(String tok) {
        if (tok == null || tok.isEmpty()) return null;
        try { return Integer.parseInt(tok); }
        catch (NumberFormatException nfe) { return null; }
    }

    /** Decode {x,y,z} | BlockPos | "x,y,z" to BlockPos; null if shape doesn't match. */
    static BlockPos readPos(Object o) {
        if (o instanceof BlockPos bp) return bp;
        if (o instanceof Map<?, ?> m
                && m.get("x") instanceof Number nx
                && m.get("y") instanceof Number ny
                && m.get("z") instanceof Number nz) {
            return new BlockPos(nx.intValue(), ny.intValue(), nz.intValue());
        }
        if (o instanceof String s) {
            try { return BlockPos.parse(s); } catch (Exception e) { return null; }
        }
        return null;
    }

    static net.minecraft.core.BlockPos mc(BlockPos p) {
        return new net.minecraft.core.BlockPos(p.x, p.y, p.z);
    }

    static String blockId(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    /**
     * Resolve a block id to its default state, throwing on unknown ids instead of
     * silently falling back to AIR (the registry's default). Pre-fix, a typo or a
     * modded id not present in the registry would resolve to AIR and silently
     * erase whatever was at the target position while reporting ok:true.
     */
    static BlockState parseBlock(String id) {
        ResourceLocation rl;
        try { rl = ResourceLocation.parse(id); }
        catch (Exception e) { throw new IllegalArgumentException("invalid block id: " + id); }
        if (!BuiltInRegistries.BLOCK.containsKey(rl)) {
            throw new IllegalArgumentException("unknown block: " + id);
        }
        return BuiltInRegistries.BLOCK.get(rl).defaultBlockState();
    }

    static Map<String, Object> itemSnapshot(ItemStack s) {
        if (s == null || s.isEmpty()) return Map.of("empty", true);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("empty", false);
        m.put("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        m.put("count", s.getCount());
        return m;
    }

    /** Walk a dotted path through nested Map/List. Returns null on miss. */
    static Object walkPath(Object root, String path) {
        if (path == null || path.isEmpty()) return root;
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null) return null;
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(part);
            } else if (cur instanceof List<?> l) {
                try { cur = l.get(Integer.parseInt(part)); }
                catch (NumberFormatException | IndexOutOfBoundsException e) { return null; }
            } else {
                return null;
            }
        }
        return cur;
    }

    /** JS-style truthy. null, false, 0, "", and empty collections are falsy. */
    static boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof CharSequence s) return s.length() > 0;
        if (v instanceof java.util.Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }
}
