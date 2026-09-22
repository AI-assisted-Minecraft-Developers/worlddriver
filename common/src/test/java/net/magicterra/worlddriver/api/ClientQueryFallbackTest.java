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
import org.junit.jupiter.api.Test;

/**
 * {@code mc.query q=entities} on a client with no server attached must answer in the server
 * path's shape — a flat array of rows — and honour the same {@code filter.type},
 * {@code filter.is_living} and {@code select}, or a caller written against one path breaks on
 * the other.
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
}
