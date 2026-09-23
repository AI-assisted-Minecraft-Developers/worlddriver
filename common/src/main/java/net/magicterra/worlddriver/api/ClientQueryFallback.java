package net.magicterra.worlddriver.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.worlddriver.client.ClientDriverApi;
import net.minecraft.world.level.ChunkPos;

/**
 * {@code mc.query} on a client JVM with no server attached (connected to a remote dedicated
 * server): scans ClientLevel through the client impl instead of the server level, under the
 * server path's contract.
 */
final class ClientQueryFallback {
    private ClientQueryFallback() {}

    @SuppressWarnings("unchecked")
    static Object query(ClientDriverApi c, Map<String, Object> p) {
        String q = (String) p.get("q");
        Object filter = p.get("filter");
        int r = 16; // entity radius; a block scan takes BlockQuery's validated one
        Boolean wantHostile = null;
        String typeFilter = null;
        if (filter instanceof Map<?, ?> fm) {
            Object rad = fm.get("in_radius");
            if (rad instanceof Number rn) r = rn.intValue();
            Object h = fm.get("is_hostile");
            if (h instanceof Boolean hb) wantHostile = hb;
            Object tv = fm.get("type");
            if (tv instanceof String s && !s.isBlank()) typeFilter = s;
        }
        Double cx = null, cy = null, cz = null;
        Object center = p.get("center");
        if (center instanceof Map<?, ?> cm) {
            Object xo = cm.get("x"), yo = cm.get("y"), zo = cm.get("z");
            if (xo instanceof Number nx && yo instanceof Number ny && zo instanceof Number nz) {
                cx = nx.doubleValue(); cy = ny.doubleValue(); cz = nz.doubleValue();
            }
        }
        if ("entities".equals(q)) {
            List<String> select = p.get("select") instanceof List<?> l ? (List<String>) l : null;
            DriverApi.checkSelect(select, CLIENT_ENTITY_SELECT_KEYS);
            Boolean wantLiving = filter instanceof Map<?, ?> fm && fm.get("is_living") instanceof Boolean b ? b : null;
            return entities(c.queryEntities(r, cx, cy, cz, wantHostile), typeFilter, wantLiving, select);
        }
        // Validated by the server's own rules before the client is touched, so both paths refuse
        // the same radius and select keys with the same error.
        BlockQuery rules = BlockQuery.of(QueryParams.from(p));
        Set<String> ids = (typeFilter == null) ? null
                : new LinkedHashSet<>(Set.of(typeFilter));
        return blocks(c.observeArea(rules.radius(), cx, cy, cz, ids), rules);
    }

    /** Unwraps the client scan's {@code {blocks, center, radius, unloaded?}} into the server's flat
     *  array, refusing an unloaded area as the server does: ClientLevel reads those cells as air. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocks(Map<String, Object> scan, BlockQuery rules) {
        if (!(scan.get("blocks") instanceof List<?> rows)) {
            throw new IllegalStateException("mc.query client fallback: " + scan.getOrDefault("error", "no block scan"));
        }
        if (scan.get("unloaded") instanceof List<?> missing && !missing.isEmpty()) {
            List<ChunkPos> chunks = new ArrayList<>();
            for (Object o : missing) {
                Map<?, ?> m = (Map<?, ?>) o;
                chunks.add(new ChunkPos(((Number) m.get("x")).intValue(), ((Number) m.get("z")).intValue()));
            }
            throw new UnloadedAreaException(ApiSupport.readPos(scan.get("center")), rules.radius(), chunks);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : rows) out.add(rules.project((Map<String, Object>) o));
        return out;
    }

    /** The server's row keys plus the three only the client scan computes. */
    private static final Set<String> CLIENT_ENTITY_SELECT_KEYS = union(DriverApi.ENTITY_SELECT_KEYS,
            Set.of("hostile", "maxHealth", "distance"));

    /** Unwraps the client scan's {@code {entities, radius}} into the server's flat array and applies
     *  the filters the scan itself does not take. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entities(Map<String, Object> scan, String type,
                                                      Boolean wantLiving, List<String> select) {
        if (!(scan.get("entities") instanceof List<?> rows)) {
            throw new IllegalStateException("mc.query client fallback: " + scan.getOrDefault("error", "no entity scan"));
        }
        String wantType = type == null ? null : type.contains(":") ? type : "minecraft:" + type;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : rows) {
            Map<String, Object> row = (Map<String, Object>) o;
            if (wantType != null && !wantType.equals(row.get("type"))) continue;
            // The client scan puts health on exactly the LivingEntity rows.
            if (wantLiving != null && wantLiving != row.containsKey("health")) continue;
            out.add(DriverApi.project(row, select));
        }
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return Set.copyOf(s);
    }
}
