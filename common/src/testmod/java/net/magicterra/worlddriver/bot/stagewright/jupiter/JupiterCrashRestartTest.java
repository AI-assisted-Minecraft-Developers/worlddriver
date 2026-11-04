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
 * kept bot's items put there after the last save are gone, the ones before it are there.
 */
@Scene("wd.jupiterCrash")
@Isolated
class JupiterCrashRestartTest {

    /** In the spawn chunk, which every world keeps loaded, and above the flat ground. */
    private static final BlockPos SAVED = new BlockPos(0, 100, 0);

    private static final BlockPos UNSAVED = new BlockPos(1, 100, 0);

    private static final String BOT = "wdCrashed";

    @Test
    void savesSomeThenChangesMoreAndCrashes(Game game, @Bot.Name(BOT) @Bot.Keep @Bot.Parked Bot bot) {
        bot.give(Items.DIAMOND, 3);
        game.onServer(server -> {
            server.overworld().setBlockAndUpdate(SAVED, Blocks.EMERALD_BLOCK.defaultBlockState());
            return server.saveEverything(true, true, true);
        });
        game.onServer(server -> server.overworld().setBlockAndUpdate(UNSAVED, Blocks.GOLD_BLOCK.defaultBlockState()));
        bot.give(Items.DIAMOND, 2);
        game.crashNow();
    }

    @Test
    @DependsOn(value = "savesSomeThenChangesMoreAndCrashes", afterRestart = true)
    void comesBackWithOnlyWhatWasSaved(Game game, @Bot.Name(BOT) @Bot.Kept @Bot.Parked Bot bot) {
        assertEquals(Blocks.EMERALD_BLOCK, block(game, SAVED), "placed before the last save");
        assertEquals(Blocks.AIR, block(game, UNSAVED), "placed after it: the crash saved nothing");
        assertEquals(3, bot.count(Items.DIAMOND), "the kept bot as its data was last saved");
    }

    private static Block block(Game game, BlockPos pos) {
        return game.onServer(server -> server.overworld().getBlockState(pos).getBlock());
    }
}
