package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Rounds after a restart on other saves: a marker left in the scene's world before {@code game.quitNow()} is absent
 * from a new world the next round opens, and there again when the round after goes back to the first.
 */
@Scene("wd.jupiterWorlds")
@Isolated
class JupiterWorldsRestartTest {

    /** In the spawn chunk, which every world keeps loaded, and above the flat ground. */
    private static final BlockPos MARKER = new BlockPos(0, 100, 0);

    @Test
    void leavesAMarkerAndQuits(Game game) {
        game.onServer(server -> server.overworld().setBlockAndUpdate(MARKER, Blocks.EMERALD_BLOCK.defaultBlockState()));
        game.quitNow();
    }

    @Test
    @DependsOn(value = "leavesAMarkerAndQuits", afterRestart = true, world = "second")
    void aNewWorldHasNoMarker(Game game) {
        assertEquals(Blocks.AIR, block(game), "a world made new for this round");
        game.onServer(server -> server.overworld().setBlockAndUpdate(MARKER, Blocks.GOLD_BLOCK.defaultBlockState()));
    }

    @Test
    @DependsOn(value = "aNewWorldHasNoMarker", afterRestart = true, world = DependsOn.FIRST_WORLD)
    void theFirstWorldStillHasItsMarker(Game game) {
        assertEquals(Blocks.EMERALD_BLOCK, block(game), "the marker saved as the first round quit");
    }

    @Test
    @DependsOn(value = "theFirstWorldStillHasItsMarker", afterRestart = true, world = "second")
    void theSecondWorldIsKeptAsItsRoundLeftIt(Game game) {
        assertEquals(Blocks.GOLD_BLOCK, block(game), "the marker the second world's round left");
    }

    private static Block block(Game game) {
        return game.onServer(server -> server.overworld().getBlockState(MARKER).getBlock());
    }
}
