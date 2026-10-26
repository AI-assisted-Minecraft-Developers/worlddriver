package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.stagewright.test.methods.RecipesContracts;
import net.magicterra.stagewright.test.methods.TestQueriesBlockingApi;
import org.junit.jupiter.api.Test;

/** mc.test.recipes: the recipe graph answers who makes the crafting table, out of what, and whether that closes. */
@Scene("wd.jupiterSmoke")
class JupiterRecipesTest {

    private static final String TABLE = "minecraft:crafting_table";

    @Test
    void aCraftingTableHasAMakerWhoseSlotsAcceptPlanks(Game game) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        var made = assertInstanceOf(RecipesContracts.ProducedBy.class,
                queries.recipes(new RecipesContracts.Producing(TABLE)));
        assertFalse(made.recipes().isEmpty(), "some recipe makes a crafting table");

        var recipe = assertInstanceOf(RecipesContracts.IngredientsOf.class,
                queries.recipes(new RecipesContracts.Ingredients(made.recipes().get(0))));
        assertEquals(TABLE, recipe.result());
        assertEquals(1, recipe.count());
        assertFalse(recipe.slots().isEmpty(), "it has ingredient slots");
        assertTrue(recipe.slots().stream().anyMatch(s -> s.accepts().contains("minecraft:oak_planks")),
                "a slot accepts oak planks");
    }

    @Test
    void theClosureOfACraftingTableReachesLogsAndLeavesNothingUnresolved(Game game) {
        var closure = assertInstanceOf(RecipesContracts.Closure.class, game.api(TestQueriesBlockingApi::of)
                .recipes(new RecipesContracts.ClosureOf(TABLE, Optional.empty(), Optional.empty())));
        assertEquals(TABLE, closure.root());
        assertTrue(closure.items().contains("minecraft:oak_planks"), "reaches planks");
        assertTrue(closure.unresolvable().isEmpty(), "unresolvable: " + closure.unresolvable());
        assertFalse(closure.truncated(), "the default bounds hold a crafting table");
    }

    @Test
    void aTinyBoundTruncatesRatherThanFailing(Game game) {
        var closure = assertInstanceOf(RecipesContracts.Closure.class, game.api(TestQueriesBlockingApi::of)
                .recipes(new RecipesContracts.ClosureOf(TABLE, Optional.of(1), Optional.empty())));
        assertTrue(closure.truncated(), "one node cannot hold the closure");
    }

    @Test
    void anUnknownItemOrRecipeIsRefused(Game game) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        assertThrows(RuntimeException.class, () -> queries.recipes(new RecipesContracts.Producing("minecraft:nope")));
        assertThrows(RuntimeException.class, () -> queries.recipes(new RecipesContracts.Ingredients("minecraft:nope")));
    }

    @Test
    void theWholeGraphAudits(Game game) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        var audit = assertInstanceOf(RecipesContracts.AuditReport.class,
                queries.recipes(new RecipesContracts.Audit()));
        assertTrue(audit.attempted() > 100, "attempted " + audit.attempted());
        assertTrue(audit.skipped() > 0, "special recipes are skipped, not run");
        assertEquals(0, audit.failureCount(), "audit failures: " + audit.failures());

        var uncraftable = assertInstanceOf(RecipesContracts.UncraftableRecipes.class,
                queries.recipes(new RecipesContracts.Uncraftable()));
        assertEquals(0, uncraftable.count(), "uncraftable: " + uncraftable.recipes());
    }
}
