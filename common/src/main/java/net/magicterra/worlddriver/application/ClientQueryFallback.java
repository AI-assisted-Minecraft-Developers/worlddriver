package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.UnloadedAreaException;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.QueryContract.BlockRow;
import net.magicterra.worlddriver.api.methods.QueryContract.BlockRows;
import net.magicterra.worlddriver.api.methods.QueryContract.Blocks;
import net.magicterra.worlddriver.api.methods.QueryContract.Entities;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityFilter;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityRow;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityRows;
import net.magicterra.worlddriver.api.methods.QueryContract.Query;
import net.magicterra.worlddriver.api.methods.QueryContract.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.magicterra.worlddriver.client.ClientDriverApi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * {@code mc.query} on a client JVM with no server attached (connected to a remote dedicated
 * server): scans ClientLevel through the client impl instead of the server level, under the
 * server path's contract. Runs on the client thread, where the client scans run inline.
 */
final class ClientQueryFallback {
    private ClientQueryFallback() {}

    static Rows query(ClientDriverApi c, Query q) {
        return switch (q) {
            case Blocks b -> blocks(c, b);
            case Entities e -> entities(c, e);
        };
    }

    /** The client scan's {@code {blocks, center, radius, unloaded?}} as the server's rows, refusing an unloaded
     *  area as the server does: ClientLevel reads those cells as air. */
    @SuppressWarnings("unchecked")
    private static Rows blocks(ClientDriverApi c, Blocks b) {
        BlockQuery rules = BlockQuery.of(b);
        Point center = b.center().map(p -> new Point(p.x(), p.y(), p.z())).orElse(null);
        Set<String> ids = b.filter().type().map(Set::of).orElse(null);
        Map<String, Object> scan = c.observeArea(rules.radius(), center == null ? null : center.x(),
                center == null ? null : center.y(), center == null ? null : center.z(), ids);
        if (!(scan.get("blocks") instanceof List<?> rows)) {
            throw new IllegalStateException("mc.query client fallback: " + scan.getOrDefault("error", "no block scan"));
        }
        if (scan.get("unloaded") instanceof List<?> missing && !missing.isEmpty()) {
            List<ChunkPos> chunks = new ArrayList<>();
            for (Object o : missing) {
                Map<?, ?> m = (Map<?, ?>) o;
                chunks.add(new ChunkPos(((Number) m.get("x")).intValue(), ((Number) m.get("z")).intValue()));
            }
            Map<?, ?> at = (Map<?, ?>) scan.get("center");
            throw new UnloadedAreaException(new BlockPos(num(at, "x"), num(at, "y"), num(at, "z")), rules.radius(), chunks);
        }
        List<BlockRow> out = new ArrayList<>();
        for (Object o : rows) {
            Map<String, Object> row = (Map<String, Object>) o;
            Map<?, ?> pos = (Map<?, ?>) row.get("pos");
            Optional<Map<String, String>> state = Optional.empty();
            if (row.get("state") instanceof Map<?, ?> s) {
                Map<String, String> m = new LinkedHashMap<>();
                s.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
                state = Optional.of(m);
            }
            out.add(rules.row(new BlockPosition(num(pos, "x"), num(pos, "y"), num(pos, "z")), (String) row.get("type"), state));
        }
        return new BlockRows(out);
    }

    /** The client scan's {@code {entities, radius}} as the server's rows, with the filters the scan itself does not
     *  take applied here. The client knows what the server does not (hostile, maxHealth, distance) and is not told
     *  other entities' effects. */
    @SuppressWarnings("unchecked")
    private static Rows entities(ClientDriverApi c, Entities e) {
        EntityFilter f = e.filter();
        Point center = e.center().map(p -> new Point(p.x(), p.y(), p.z())).orElse(null);
        Map<String, Object> scan = c.queryEntities(f.inRadius(), center == null ? null : center.x(),
                center == null ? null : center.y(), center == null ? null : center.z(), f.isHostile().orElse(null));
        if (!(scan.get("entities") instanceof List<?> rows)) {
            throw new IllegalStateException("mc.query client fallback: " + scan.getOrDefault("error", "no entity scan"));
        }
        String wantType = f.type().map(QueryApi::namespaced).orElse(null);
        List<EntityRow> out = new ArrayList<>();
        for (Object o : rows) {
            Map<String, Object> row = (Map<String, Object>) o;
            if (wantType != null && !wantType.equals(row.get("type"))) continue;
            // The client scan puts health on exactly the LivingEntity rows.
            boolean living = row.containsKey("health");
            if (f.isLiving().isPresent() && f.isLiving().get() != living) continue;
            Map<?, ?> pos = (Map<?, ?>) row.get("pos");
            double x = dbl(pos, "x"), y = dbl(pos, "y"), z = dbl(pos, "z");
            out.add(QueryApi.select(new EntityRow(Optional.of(new Point(x, y, z)),
                    Optional.of(new BlockPosition((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))),
                    Optional.of((String) row.get("type")), Optional.of((String) row.get("uuid")),
                    Optional.of(((Number) row.get("id")).intValue()),
                    living ? Optional.of(dbl(row, "health")) : Optional.empty(), Optional.empty(),
                    Optional.of((Boolean) row.get("hostile")),
                    living ? Optional.of(dbl(row, "maxHealth")) : Optional.empty(),
                    Optional.of(dbl(row, "distance"))), e.select()));
        }
        return new EntityRows(out);
    }

    private static int num(Map<?, ?> m, String key) {
        return ((Number) m.get(key)).intValue();
    }

    private static double dbl(Map<?, ?> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }
}
