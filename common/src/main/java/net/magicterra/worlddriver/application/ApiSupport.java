package net.magicterra.worlddriver.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pure, stateless helpers shared by {@link DriverApplication} and the {@code *Service} sub-handlers
 * ({@link SystemService}, {@link ObserveService}, {@link ActionService}, {@link WaitService}). Extracted from
 * {@code DriverApplication} so the handler bodies can live in their own files without a back-reference just for
 * parsing/encoding.
 */
final class ApiSupport {
    private ApiSupport() {}

    /**
     * Parse a coord token as an absolute decimal int. Returns null for Brigadier-style relative ({@code ~}, {@code ~N},
     * {@code ^N}) or otherwise non-integer tokens; callers fall back to the full command dispatcher rather than letting
     * Integer.parseInt throw a raw NumberFormatException.
     */
    static Integer parseAbsInt(String tok) {
        if (tok == null || tok.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(tok);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /**
     * Resolve a block id to its default state, throwing on unknown ids instead of silently falling back to AIR (the
     * registry's default). Pre-fix, a typo or a modded id not present in the registry would resolve to AIR and silently
     * erase whatever was at the target position while reporting ok:true.
     */
    static BlockState parseBlock(String id) {
        return RegistryIds.lookup(BuiltInRegistries.BLOCK, id)
                .orElseThrow(() -> new IllegalArgumentException("unknown block: " + id))
                .defaultBlockState();
    }

    static Map<String, Object> itemSnapshot(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return Map.of("empty", true);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("empty", false);
        m.put("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        m.put("count", s.getCount());
        ItemSnap.putWear(m, s);
        return m;
    }

    /** Walk a dotted path through nested objects and arrays. Returns null on a miss or a JSON null. */
    static JsonElement walkPath(JsonElement root, String path) {
        if (path == null || path.isEmpty()) {
            return root == null || root.isJsonNull() ? null : root;
        }
        JsonElement cur = root;
        for (String part : path.split("\\.")) {
            if (cur instanceof JsonObject m) {
                cur = m.get(part);
            } else if (cur instanceof JsonArray l) {
                try {
                    cur = l.get(Integer.parseInt(part));
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return cur == null || cur.isJsonNull() ? null : cur;
    }

    /** JS-style truthy. null, false, 0, "", and empty arrays and objects are falsy. */
    static boolean isTruthy(JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return false;
        }
        if (v instanceof JsonArray a) {
            return !a.isEmpty();
        }
        if (v instanceof JsonObject o) {
            return !o.isEmpty();
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            return p.getAsDouble() != 0.0;
        }
        return !p.getAsString().isEmpty();
    }
}
