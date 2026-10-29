package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * A bot kept with {@code @Bot.Keep} crosses a restart: the test after it takes the same player, in the dimension and
 * at the place the game before left it, with what it carried. In {@code wd.scenes}, which restarts already.
 */
@Scene("wd.scenes")
class JupiterKeptBotRestartTest {

    /** Outside every plot, and in the nether, so neither a fresh join nor the world spawn could put it there. */
    private static final BlockPos LEFT_AT = new BlockPos(24, 90, 24);

    @Test
    void aKeptBotIsLeftInTheNetherWithAnItem(Game game, @Bot.Name("wdCarried") @Bot.Keep @Bot.Parked Bot bot) {
        bot.give(Items.DIAMOND, 3);
        bot.moveTo(Level.NETHER, LEFT_AT);
        game.store().put("carried.uuid", bot.<String>onServer(p -> p.getUUID().toString()));
    }

    @Test
    @DependsOn(value = "aKeptBotIsLeftInTheNetherWithAnItem", afterRestart = true)
    void isTakenAfterTheRestartWhereItWasLeft(Game game, @Bot.Name("wdCarried") @Bot.Kept @Bot.Parked Bot bot) {
        assertEquals(game.store().get("carried.uuid", String.class), bot.<String>onServer(p -> p.getUUID().toString()),
                "the same player");
        assertEquals(Level.NETHER, bot.dimension(), "the dimension the game before left it in");
        assertEquals(LEFT_AT, bot.blockPos(), "where the game before left it");
        BlockPos spawn = game.onServer(s -> s.overworld().getSharedSpawnPos());
        assertNotEquals(spawn, bot.blockPos(), "at the world spawn, as a fresh bot joins");
        assertEquals(3, bot.count(Items.DIAMOND), "with what it carried");
    }
}
