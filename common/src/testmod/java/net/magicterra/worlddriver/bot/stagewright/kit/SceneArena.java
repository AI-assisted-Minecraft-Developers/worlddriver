package net.magicterra.worlddriver.bot.stagewright.kit;

import net.magicterra.stagewright.test.Plot;
import net.minecraft.world.level.block.Blocks;

/**
 * Terrain staging shared by the test classes — the sibling of {@link SceneBot}, which stages the bot. A test that needs
 * a floor to stand on should not have to own one.
 *
 * <p>This exists because it was copied instead: six files each carried a byte-identical floor builder, so the floor
 * every test stood on had six authors and no owner, and the copies stayed in step by luck.
 */
public final class SceneArena {
    private SceneArena() {}

    /**
     * An 11×11 stone slab at relative {@code y} centred on relative {@code (x, z)}, the cells above it air up to 18
     * blocks or the plot's top. A {@link Plot.Centered} plot of 16 holds it at {@code (0, y, 0)}.
     */
    public static void buildFloor(Plot plot, int x, int y, int z) {
        plot.fill(x - 5, y, z - 5, x + 5, y, z + 5, Blocks.STONE);
        int top = Math.min(y + 18, plot.max().getY());
        if (top > y) plot.fill(x - 5, y + 1, z - 5, x + 5, top, z + 5, Blocks.AIR);
    }
}
