package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.game.UnloadedAreaException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlockScan;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChunkRef;
import net.magicterra.worlddriver.api.methods.query.QueryContracts;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.BlockRow;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.BlockRows;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.EntityRow;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.EntityRows;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Point;
import net.magicterra.worlddriver.client.ClientDriverApi;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/**
 * {@code mc.query} on a client with no server attached must answer in the server path's shape and
 * honour the same filters, radius bounds, {@code select} fields and unloaded-chunk refusal, or a
 * caller written against one path breaks on the other.
 */
class ClientQueryFallbackTest {

    private static EntityRow row(int id, String type, boolean hostile, Double health) {
        return new EntityRow(Optional.of(new Point(1.5, 64.0, 2.5)), Optional.of(new BlockPosition(1, 64, 2)),
                Optional.of(type), Optional.of("00000000-0000-0000-0000-00000000000" + id), Optional.of(id),
                Optional.ofNullable(health), Optional.empty(), Optional.of(hostile),
                health == null ? Optional.empty() : Optional.of(20.0), Optional.of(1.0 * id));
    }

    /** A client impl whose entity scan answers the way ClientObserve does: every field but effects. */
    private static ClientDriverApi client() {
        List<EntityRow> rows = List.of(
                row(1, "minecraft:zombie", true, 20.0),
                row(2, "minecraft:item", false, null),
                row(3, "minecraft:cow", false, 10.0));
        return (ClientDriverApi) Proxy.newProxyInstance(ClientDriverApi.class.getClassLoader(),
                new Class<?>[] {ClientDriverApi.class}, (proxy, m, args) -> {
                    if (m.getName().equals("queryEntities")) return rows;
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    /** The request as the dispatcher hands it over: decoded by the method's own contract. */
    private static Object query(ClientDriverApi c, Map<String, Object> params) {
        return ClientQueryFallback.query(c, QueryContracts.PARAMS.decode(params));
    }

    private static List<EntityRow> entities(Map<String, Object> params) {
        return assertInstanceOf(EntityRows.class, query(client(), params)).entities();
    }

    private static Set<String> types(List<EntityRow> rows) {
        return rows.stream().map(r -> r.type().orElseThrow()).collect(Collectors.toSet());
    }

    @Test
    void entitiesComeBackAsServerRowsPlusWhatOnlyTheClientMeasures() {
        List<EntityRow> rows = entities(Map.of("q", "entities"));
        assertEquals(3, rows.size());
        EntityRow zombie = rows.get(0);
        assertEquals(Optional.of(new BlockPosition(1, 64, 2)), zombie.blockPos());
        assertEquals(Optional.of(true), zombie.hostile());
        assertTrue(zombie.effects().isEmpty(), "a client is not told other entities' effects");
        assertTrue(rows.get(1).health().isEmpty(), "an item is not living");
        // Every row satisfies the reply contract the server's rows do.
        QueryContracts.REPLY.encode(new EntityRows(rows));
    }

    @Test
    void filterTypeRestrictsToOneIdAndAcceptsABarePath() {
        assertEquals(Set.of("minecraft:zombie"), types(entities(Map.of("q", "entities", "filter", Map.of("type", "zombie")))));
        assertEquals(Set.of("minecraft:cow"), types(entities(Map.of("q", "entities", "filter", Map.of("type", "minecraft:cow")))));
    }

    @Test
    void filterIsLivingSplitsLivingFromNonLiving() {
        assertEquals(Set.of("minecraft:zombie", "minecraft:cow"),
                types(entities(Map.of("q", "entities", "filter", Map.of("is_living", true)))));
        assertEquals(Set.of("minecraft:item"), types(entities(Map.of("q", "entities", "filter", Map.of("is_living", false)))));
    }

    @Test
    void selectProjectsAndRejectsUnknownFields() {
        for (EntityRow r : entities(Map.of("q", "entities", "select", List.of("id", "distance")))) {
            assertTrue(r.id().isPresent() && r.distance().isPresent());
            assertTrue(r.type().isEmpty() && r.pos().isEmpty() && r.hostile().isEmpty(), String.valueOf(r));
        }
        ApiException e = assertThrows(ApiException.class,
                () -> query(client(), Map.of("q", "entities", "select", List.of("bogus"))));
        assertEquals("/select/0", e.error().issues().get(0).path());
    }

    /** A client impl whose block scan answers the way ClientObserve does; records the radius asked for. */
    private static ClientDriverApi blockClient(List<ChunkRef> unloaded, int[] radiusSeen) {
        List<ClientContracts.BlockRow> rows = List.of(
                new ClientContracts.BlockRow(new BlockPosition(0, 64, 0), "minecraft:stone", Optional.empty()),
                new ClientContracts.BlockRow(new BlockPosition(1, 64, 0), "minecraft:oak_stairs",
                        Optional.of(Map.of("facing", "north"))));
        return (ClientDriverApi) Proxy.newProxyInstance(ClientDriverApi.class.getClassLoader(),
                new Class<?>[] {ClientDriverApi.class}, (proxy, m, args) -> {
                    if (m.getName().equals("scanBlocks")) {
                        radiusSeen[0] = (Integer) args[0];
                        return new BlockScan(rows, new BlockPosition(0, 64, 0), (Integer) args[0], Optional.ofNullable(unloaded));
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private static List<BlockRow> blocks(ClientDriverApi c, Map<String, Object> filter, List<String> select) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", "blocks");
        if (filter != null) params.put("filter", filter);
        if (select != null) params.put("select", select);
        return assertInstanceOf(BlockRows.class, query(c, params)).blocks();
    }

    @Test
    void blocksRadiusFollowsTheServerDefaultAndMaximum() {
        int[] seen = {-1};
        blocks(blockClient(null, seen), null, null);
        assertEquals(0, seen[0], "a missing in_radius is the centre cell alone, as on the server");

        blocks(blockClient(null, seen), Map.of("in_radius", BlockQuery.MAX_RADIUS), null);
        assertEquals(BlockQuery.MAX_RADIUS, seen[0]);

        seen[0] = -1;
        ApiException e = assertThrows(ApiException.class,
                () -> blocks(blockClient(null, seen), Map.of("in_radius", BlockQuery.MAX_RADIUS + 1), null));
        assertEquals("/filter/in_radius", e.error().issues().get(0).path());
        assertEquals(-1, seen[0], "a refused radius must not reach the client scan");
    }

    @Test
    void blocksSelectProjectsWithTheServerFieldSet() {
        List<BlockRow> rows = blocks(blockClient(null, new int[1]), null, List.of("type"));
        assertEquals(2, rows.size());
        for (BlockRow r : rows) assertTrue(r.type().isPresent() && r.pos().isEmpty() && r.state().isEmpty(), String.valueOf(r));
        assertEquals(Optional.of(Map.of("facing", "north")), blocks(blockClient(null, new int[1]), null, null).get(1).state());

        // hostile is an entity field; a block query has its own.
        assertThrows(ApiException.class, () -> blocks(blockClient(null, new int[1]), null, List.of("hostile")));
    }

    @Test
    void blocksInAChunkTheClientHasNotLoadedAreRefused() {
        ClientDriverApi c = blockClient(List.of(new ChunkRef(3, -2)), new int[1]);
        UnloadedAreaException e = assertThrows(UnloadedAreaException.class,
                () -> blocks(c, Map.of("in_radius", 2), null));
        assertEquals(List.of(new ChunkPos(3, -2)), e.chunks());
        assertTrue(e.getMessage().contains("[3, -2]"), e.getMessage());
    }
}
