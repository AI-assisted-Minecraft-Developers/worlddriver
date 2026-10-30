package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.stagewright.test.methods.AdvancementsContracts;
import net.magicterra.stagewright.test.methods.TestQueriesBlockingApi;
import org.junit.jupiter.api.Test;

/** mc.test.advancements: grant, read and revoke an advancement on a bot that joined for this test. */
@Scene("wd.jupiterSmoke")
class JupiterAdvancementsTest {

    private static final String MINE_STONE = "minecraft:story/mine_stone";

    @Test
    void grantThenProgressThenRevoke(Game game, Bot bot) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);

        var before = assertInstanceOf(
                AdvancementsContracts.Progressed.class,
                queries.advancements(new AdvancementsContracts.Progress(bot.ref(), MINE_STONE)));
        assertFalse(before.done(), "a fresh bot has not mined stone");
        assertFalse(before.remaining().isEmpty(), "criteria outstanding");

        var granted = assertInstanceOf(
                AdvancementsContracts.Granted.class,
                queries.advancements(new AdvancementsContracts.Grant(bot.ref(), MINE_STONE)));
        assertTrue(granted.done(), "granted");
        assertFalse(granted.awarded().isEmpty(), "criteria were awarded");

        var after = assertInstanceOf(
                AdvancementsContracts.Progressed.class,
                queries.advancements(new AdvancementsContracts.Progress(bot.ref(), MINE_STONE)));
        assertTrue(after.done(), "done after the grant");
        assertTrue(after.remaining().isEmpty(), "nothing remaining: " + after.remaining());

        var revoked = assertInstanceOf(
                AdvancementsContracts.Revoked.class,
                queries.advancements(new AdvancementsContracts.Revoke(bot.ref(), MINE_STONE)));
        assertFalse(revoked.done(), "revoked");
        assertFalse(revoked.revoked().isEmpty(), "criteria were taken back");
        assertFalse(assertInstanceOf(
                        AdvancementsContracts.Progressed.class,
                        queries.advancements(new AdvancementsContracts.Progress(bot.ref(), MINE_STONE)))
                .done());
    }

    @Test
    void anUnknownPlayerOrAdvancementIsRefused(Game game, Bot bot) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        assertThrows(
                RuntimeException.class,
                () -> queries.advancements(new AdvancementsContracts.Progress("player:nobody_here", MINE_STONE)));
        assertThrows(
                RuntimeException.class,
                () -> queries.advancements(new AdvancementsContracts.Progress(bot.ref(), "minecraft:story/nope")));
    }
}
