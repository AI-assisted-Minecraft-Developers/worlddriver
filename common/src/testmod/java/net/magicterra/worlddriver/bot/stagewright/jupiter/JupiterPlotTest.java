package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Plots, a test checking what the one it depends on left behind. Isolated, so no other test leases a plot meanwhile:
 * the second plot leased is the first one's cell again, and must come back empty.
 */
@Scene("wd.jupiterSmoke")
@Isolated
class JupiterPlotTest {

    private static BlockPos firstOrigin;

    @Test
    void aPlotIsHandedOverEmpty(Game game, @Plot.Size(8) Plot plot) {
        firstOrigin = plot.origin();
        assertEquals(8, plot.size());
        boolean empty = game.onServer(server -> server.overworld().getBlockStates(
                AABB.encapsulatingFullBlocks(plot.origin(), plot.at(7, 7, 7))).allMatch(s -> s.isAir()));
        assertTrue(empty, "every cell of " + plot + " is air");

        plot.fill(0, 0, 0, 7, 0, 7, Blocks.STONE).place(3, 1, 3, Blocks.CHEST);
        boolean spawned = game.onServer(server ->
                EntityType.PIG.spawn(server.overworld(), plot.at(4, 1, 4), MobSpawnType.COMMAND) != null);
        assertTrue(spawned, "a pig stands in the plot");
        assertEquals("minecraft:chest", game.onServer(server -> BuiltInRegistries.BLOCK.getKey(
                server.overworld().getBlockState(plot.at(3, 1, 3)).getBlock()).toString()));
    }

    @Test
    @DependsOn("aPlotIsHandedOverEmpty")
    void theNextLeasePutsTheFirstOneBack(Game game, @Plot.Size(8) Plot plot) {
        assertEquals(firstOrigin, plot.origin(), "the freed cell is leased again");
        boolean chestGone = game.onServer(server -> server.overworld().getBlockState(plot.at(3, 1, 3)).isAir());
        assertTrue(chestGone, "the chest the last test placed is gone");
        int pigs = game.onServer(server -> server.overworld().getEntities(EntityType.PIG,
                AABB.encapsulatingFullBlocks(plot.origin(), plot.at(7, 7, 7)), pig -> true).size());
        assertEquals(0, pigs, "the pig the last test spawned is gone");
        assertThrows(IllegalArgumentException.class, () -> plot.at(8, 0, 0));
    }
}
