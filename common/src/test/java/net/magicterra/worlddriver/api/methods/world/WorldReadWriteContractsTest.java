package net.magicterra.worlddriver.api.methods.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.methods.action.ActionContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.query.QueryContracts;
import net.magicterra.worlddriver.api.methods.recipe.RecipeContracts;
import net.magicterra.worlddriver.api.methods.system.SystemContracts;
import net.magicterra.worlddriver.protocol.JsonCodec;
import org.junit.jupiter.api.Test;

/**
 * The system, observe, query, world, action and recipe methods: the rules their params carry
 * across fields, the shapes their replies take, and which of them a watcher may poll.
 */
class WorldReadWriteContractsTest {

    private static <T> T decode(ApiType<T> type, String json) {
        return type.decode(JsonCodec.decode(json));
    }

    private static ApiIssue issue(ApiType<?> type, String json) {
        List<ApiIssue> issues = assertThrows(ApiException.class, () -> decode(type, json)).error().issues();
        assertEquals(1, issues.size(), issues.toString());
        return issues.get(0);
    }

    @Test
    void boundedReadsArePollableAndWaitingIsNot() {
        for (MethodSpec<?, ?> m : List.of(SystemContracts.VERSION, SystemContracts.TEST_ORIGIN, ObserveContracts.PLAYER,
                ObserveContracts.THREATS, ObserveContracts.BOSS_SPEC, WorldContracts.BLOCK))
            assertTrue(m.pollable(), m.name());
        for (MethodSpec<?, ?> m : List.of(SystemContracts.WAIT_TICKS, QueryContracts.SPEC, ObserveContracts.SCENE,
                WorldContracts.SNAPSHOT, ActionContracts.RUN_COMMAND))
            assertFalse(m.pollable(), m.name());
        assertEquals(MethodSpec.Blocking.MAY_WAIT, SystemContracts.WAIT_TICKS.blocking());
        assertEquals("/ticks", issue(SystemContracts.WAIT_TICKS_PARAMS, "{\"ticks\":201}").path());
    }

    @Test
    void aQueryTakesOnlyItsOwnVariantsFilters() {
        assertEquals("/filter/is_hostile",
                issue(QueryContracts.PARAMS, "{\"q\":\"blocks\",\"filter\":{\"is_hostile\":true}}").path());
        assertEquals("/select/0", issue(QueryContracts.PARAMS, "{\"q\":\"blocks\",\"select\":[\"health\"]}").path());
        assertEquals("/filter/in_radius",
                issue(QueryContracts.PARAMS, "{\"q\":\"blocks\",\"filter\":{\"in_radius\":16}}").path());
        QueryContracts.Entities e = assertInstanceOf(QueryContracts.Entities.class,
                decode(QueryContracts.PARAMS, "{\"q\":\"entities\"}"));
        assertEquals(16, e.filter().inRadius());
        assertEquals("{\"q\":\"blocks\",\"blocks\":[]}",
                JsonCodec.encode(QueryContracts.REPLY.encode(new QueryContracts.BlockRows(List.of()))));
    }

    @Test
    void aBoxPastTheCellBudgetIsRefusedAtItsFarCorner() {
        String far = "{\"from\":{\"x\":0,\"y\":0,\"z\":0},\"to\":{\"x\":32,\"y\":31,\"z\":31}";
        assertEquals("/to", issue(WorldContracts.SNAPSHOT_PARAMS, far + "}").path());
        assertEquals("/to", issue(ActionContracts.FILL_PARAMS, far + ",\"type\":\"minecraft:stone\"}").path());
        decode(ActionContracts.FILL_PARAMS,
                "{\"from\":{\"x\":0,\"y\":0,\"z\":0},\"to\":{\"x\":31,\"y\":31,\"z\":31},\"type\":\"minecraft:stone\"}");
        // Three 2^32 spans multiply to 2^96, which a long wraps to 0.
        String min = "-2147483648", max = "2147483647";
        assertEquals("/to", issue(WorldContracts.SNAPSHOT_PARAMS, "{\"from\":{\"x\":" + min + ",\"y\":" + min + ",\"z\":" + min
                + "},\"to\":{\"x\":" + max + ",\"y\":" + max + ",\"z\":" + max + "}}").path());
    }

    @Test
    void eventsAreThereOnlyWhenAskedFor() {
        assertEquals("{\"placed\":3}",
                JsonCodec.encode(ActionContracts.PLACED.encode(new ActionContracts.Placed(3, Optional.empty()))));
        assertEquals("/blocks", issue(ActionContracts.PLACE_MANY_PARAMS, "{\"blocks\":[]}").path());
    }

    @Test
    void aLookupSearchesOneWay() {
        assertEquals("/ingredient", issue(RecipeContracts.LOOKUP_PARAMS,
                "{\"result\":\"minecraft:stick\",\"ingredient\":\"minecraft:oak_planks\"}").path());
        assertEquals(20, decode(RecipeContracts.LOOKUP_PARAMS, "{}").limit());
    }

    @Test
    void anAbsentPlayerIsAnAbsentField() {
        assertEquals("{}", JsonCodec.encode(ObserveContracts.PLAYER_REPLY.encode(new ObserveContracts.PlayerReading(Optional.empty()))));
        assertEquals("{\"type\":\"miss\"}", JsonCodec.encode(ObserveContracts.CROSSHAIR.encode(new ObserveContracts.Miss())));
    }
}
