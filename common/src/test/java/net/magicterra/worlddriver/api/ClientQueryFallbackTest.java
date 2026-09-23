package net.magicterra.worlddriver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.worlddriver.client.ClientDriverApi;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/**
 * {@code mc.query} on a client with no server attached must answer in the server path's shape —
 * a flat array of rows — and honour the same filters, radius bounds, {@code select} keys and
 * unloaded-chunk refusal, or a caller written against one path breaks on the other.
 */
class ClientQueryFallbackTest {

    private static Map<String, Object> row(int id, String type, boolean hostile, Double health) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("uuid", "00000000-0000-0000-0000-00000000000" + id);
        r.put("type", type);
        r.put("pos", Map.of("x", 1.5, "y", 64.0, "z", 2.5));
        r.put("hostile", hostile);
        if (health != null) {
            r.put("health", health);
            r.put("maxHealth", 20.0);
        }
        r.put("distance", 1.0 * id);
        return r;
    }

    /** A client impl whose entity scan answers the way ClientObserve does: {entities, radius}. */
    private static ClientDriverApi client() {
        List<Object> rows = List.of(
                row(1, "minecraft:zombie", true, 20.0),
                row(2, "minecraft:item", false, null),
                row(3, "minecraft:cow", false, 10.0));
        return (ClientDriverApi) Proxy.newProxyInstance(ClientDriverApi.class.getClassLoader(),
                new Class<?>[] {ClientDriverApi.class}, (proxy, m, args) -> {
                    if (m.getName().equals("queryEntities")) return Map.of("entities", rows, "radius", 16);
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private static List<?> query(Map<String, Object> params) {
        Object out = ClientQueryFallback.query(client(), params);
        return assertInstanceOf(List.class, out, "the client fallback must return the server's flat array");
    }

    private static Set<Object> types(List<?> rows) {
        return rows.stream().map(r -> ((Map<?, ?>) r).get("type")).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void entitiesComeBackAsAFlatArray() {
        assertEquals(3, query(Map.of("q", "entities")).size());
    }

    @Test
    void filterTypeRestrictsToOneIdAndAcceptsABarePath() {
        assertEquals(Set.of("minecraft:zombie"),
                types(query(Map.of("q", "entities", "filter", Map.of("type", "zombie")))));
        assertEquals(Set.of("minecraft:cow"),
                types(query(Map.of("q", "entities", "filter", Map.of("type", "minecraft:cow")))));
    }

    @Test
    void filterIsLivingSplitsLivingFromNonLiving() {
        assertEquals(Set.of("minecraft:zombie", "minecraft:cow"),
                types(query(Map.of("q", "entities", "filter", Map.of("is_living", true)))));
        assertEquals(Set.of("minecraft:item"),
                types(query(Map.of("q", "entities", "filter", Map.of("is_living", false)))));
    }

    @Test
    void selectProjectsAndRejectsUnknownKeys() {
        List<?> rows = query(Map.of("q", "entities", "select", List.of("id", "distance")));
        for (Object r : rows) assertEquals(Set.of("id", "distance"), ((Map<?, ?>) r).keySet());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClientQueryFallback.query(client(), Map.of("q", "entities", "select", List.of("bogus"))));
        assertTrue(e.getMessage().contains("bogus"), e.getMessage());
    }

    private static Map<String, Object> blockRow(int x, String type, Map<String, Object> state) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pos", Map.of("x", x, "y", 64, "z", 0));
        r.put("type", type);
        if (state != null) r.put("state", state);
        return r;
    }

    /** A client impl whose block scan answers the way ClientObserve does; records the radius asked for. */
    private static ClientDriverApi blockClient(List<Object> unloaded, int[] radiusSeen) {
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("blocks", List.of(blockRow(0, "minecraft:stone", null),
                blockRow(1, "minecraft:oak_stairs", Map.of("facing", "north"))));
        scan.put("center", Map.of("x", 0, "y", 64, "z", 0));
        if (unloaded != null) scan.put("unloaded", unloaded);
        return (ClientDriverApi) Proxy.newProxyInstance(ClientDriverApi.class.getClassLoader(),
                new Class<?>[] {ClientDriverApi.class}, (proxy, m, args) -> {
                    if (m.getName().equals("observeArea")) {
                        radiusSeen[0] = (Integer) args[0];
                        scan.put("radius", args[0]);
                        return scan;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private static Object blocks(ClientDriverApi c, Map<String, Object> filter, List<String> select) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", "blocks");
        if (filter != null) params.put("filter", filter);
        if (select != null) params.put("select", select);
        return ClientQueryFallback.query(c, params);
    }

    @Test
    void blocksRadiusFollowsTheServerDefaultAndMaximum() {
        int[] seen = {-1};
        blocks(blockClient(null, seen), null, null);
        assertEquals(0, seen[0], "a missing in_radius is the centre cell alone, as on the server");

        blocks(blockClient(null, seen), Map.of("in_radius", BlockQuery.MAX_RADIUS), null);
        assertEquals(BlockQuery.MAX_RADIUS, seen[0]);

        seen[0] = -1;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> blocks(blockClient(null, seen), Map.of("in_radius", BlockQuery.MAX_RADIUS + 1), null));
        assertTrue(e.getMessage().contains("max " + BlockQuery.MAX_RADIUS), e.getMessage());
        assertEquals(-1, seen[0], "a refused radius must not reach the client scan");
    }

    @Test
    void blocksSelectProjectsWithTheServerKeySet() {
        List<?> rows = assertInstanceOf(List.class, blocks(blockClient(null, new int[1]), null, List.of("type")));
        assertEquals(2, rows.size());
        for (Object r : rows) assertEquals(Set.of("type"), ((Map<?, ?>) r).keySet());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> blocks(blockClient(null, new int[1]), null, List.of("hostile")));
        assertTrue(e.getMessage().contains("hostile"), e.getMessage());
    }

    @Test
    void blocksInAChunkTheClientHasNotLoadedAreRefused() {
        ClientDriverApi c = blockClient(List.of(Map.of("x", 3, "z", -2)), new int[1]);
        UnloadedAreaException e = assertThrows(UnloadedAreaException.class,
                () -> blocks(c, Map.of("in_radius", 2), null));
        assertEquals(List.of(new ChunkPos(3, -2)), e.chunks());
        assertTrue(e.getMessage().contains("[3, -2]"), e.getMessage());
    }
}
