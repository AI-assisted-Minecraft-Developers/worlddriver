package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * One client leaving its world and opening another inside a test, the run going on throughout: the new world is a world
 * of its own, the player is in it, and the world the test started on is open again for the test after.
 */
@Scene("wd.jupiterWorldsIntegrated")
@Isolated
@Requires(Requires.Capability.INTEGRATED_SERVER)
class JupiterWorldSwitchTest {

    /** In the spawn chunk, which every world keeps loaded, and above the flat ground. */
    private static final BlockPos MARKER = new BlockPos(0, 100, 0);

    @Test
    void opensAnotherWorldAndComesBack(Game game, Player player) {
        set(game, Blocks.EMERALD_BLOCK);
        game.leaveWorld();
        game.openWorld("second");
        assertEquals(Blocks.AIR, block(game), "a world made new for this test");
        assertTrue(player.<Boolean>onServer(p -> p.isAlive()), "the client's player is in the new world");
        set(game, Blocks.GOLD_BLOCK);
        game.openWorld(DependsOn.FIRST_WORLD);
        assertEquals(Blocks.EMERALD_BLOCK, block(game), "the first world as it was saved");
        // Left on the second world: the end of the test opens the first again.
        game.openWorld("second");
        assertEquals(Blocks.GOLD_BLOCK, block(game), "the second world kept as it was left");
    }

    @Test
    @DependsOn("opensAnotherWorldAndComesBack")
    void theTestAfterRunsOnTheFirstWorld(Game game) {
        assertEquals(Blocks.EMERALD_BLOCK, block(game), "put back when the switching test ended");
    }

    private static void set(Game game, Block block) {
        game.onServer(server -> server.overworld().setBlockAndUpdate(MARKER, block.defaultBlockState()));
    }

    private static Block block(Game game) {
        return game.onServer(server -> server.overworld().getBlockState(MARKER).getBlock());
    }
}
