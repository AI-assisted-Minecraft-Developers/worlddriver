package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.stagewright.test.methods.LootContracts;
import net.magicterra.stagewright.test.methods.TestQueriesBlockingApi;
import org.junit.jupiter.api.Test;

/** mc.test.loot: a dungeon chest table exists and, rolled many times, gives something. */
@Scene("wd.jupiterSmoke")
class JupiterLootTest {

    private static final String DUNGEON = "minecraft:chests/simple_dungeon";

    @Test
    void aDungeonTableExistsAndAMissingOneDoesNot(Game game) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        assertTrue(assertInstanceOf(LootContracts.Existence.class, queries.loot(new LootContracts.Exists(DUNGEON)))
                .exists());
        assertFalse(assertInstanceOf(LootContracts.Existence.class,
                queries.loot(new LootContracts.Exists("minecraft:chests/no_such_table"))).exists());
    }

    @Test
    void rollingADungeonHundredTimesYieldsItems(Game game) {
        var rolled = assertInstanceOf(LootContracts.Rolled.class,
                game.api(TestQueriesBlockingApi::of).loot(new LootContracts.Roll(DUNGEON, 100)));
        assertTrue(rolled.times() == 100, "rolls");
        assertFalse(rolled.items().isEmpty(), "100 dungeon rolls produced nothing at all");
        assertTrue(rolled.items().stream().allMatch(i -> i.stacks() >= 1 && i.total() >= i.stacks()), "counts");
    }

    @Test
    void aTableNeedingAKillerOrAMissingOneIsRefused(Game game) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        assertThrows(RuntimeException.class, () -> queries.loot(new LootContracts.Roll("minecraft:entities/zombie", 5)));
        assertThrows(RuntimeException.class,
                () -> queries.loot(new LootContracts.Roll("minecraft:chests/no_such_table", 5)));
    }
}
