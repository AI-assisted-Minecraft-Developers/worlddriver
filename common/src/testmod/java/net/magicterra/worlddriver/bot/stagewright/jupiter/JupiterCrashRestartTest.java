package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * A game that {@code game.crashNow()} ends comes back with what was saved before it and nothing after: a block and a
 * kept bot's items put there after the last save are gone, the ones before it are there. A test the crash kept from
 * starting runs in the game after it, and the crash test's dependent one game later.
 */
@Scene("wd.jupiterCrash")
@Isolated
class JupiterCrashRestartTest {

    /** In the spawn chunk, which every world keeps loaded, and above the flat ground. */
    private static final BlockPos SAVED = new BlockPos(0, 100, 0);

    private static final BlockPos UNSAVED = new BlockPos(1, 100, 0);

    private static final String BOT = "wdCrashed";

    private static final String CRASHED = "crashed";

    private static final String LEFT_OVER_RAN = "leftOverRan";

    @Test
    void savesSomeThenChangesMoreAndCrashes(Game game, @Bot.Name(BOT) @Bot.Keep @Bot.Parked Bot bot) {
        bot.give(Items.DIAMOND, 3);
        game.onServer(server -> {
            server.overworld().setBlockAndUpdate(SAVED, Blocks.EMERALD_BLOCK.defaultBlockState());
            return server.saveEverything(true, true, true);
        });
        game.onServer(server -> server.overworld().setBlockAndUpdate(UNSAVED, Blocks.GOLD_BLOCK.defaultBlockState()));
        bot.give(Items.DIAMOND, 2);
        game.store().put(CRASHED, true);
        game.crashNow();
    }

    /** Not started when the game crashed: it runs in the next game, before the round the crash test restarts into. */
    @Test
    @DependsOn("savesSomeThenChangesMoreAndCrashes")
    void aTestTheCrashKeptFromStartingRunsInTheNextGame(Game game) {
        assertEquals(Boolean.TRUE, game.store().get(CRASHED, Boolean.class), "after the crash");
        game.store().put(LEFT_OVER_RAN, true);
    }

    @Test
    @DependsOn(value = "savesSomeThenChangesMoreAndCrashes", afterRestart = true)
    void comesBackWithOnlyWhatWasSaved(Game game, @Bot.Name(BOT) @Bot.Kept @Bot.Parked Bot bot) {
        assertEquals(Blocks.EMERALD_BLOCK, block(game, SAVED), "placed before the last save");
        assertEquals(Blocks.AIR, block(game, UNSAVED), "placed after it: the crash saved nothing");
        assertEquals(3, bot.count(Items.DIAMOND), "the kept bot as its data was last saved");
        assertEquals(Boolean.TRUE, game.store().get(LEFT_OVER_RAN, Boolean.class), "in the game before this one");
    }

    private static Block block(Game game, BlockPos pos) {
        return game.onServer(server -> server.overworld().getBlockState(pos).getBlock());
    }
}
