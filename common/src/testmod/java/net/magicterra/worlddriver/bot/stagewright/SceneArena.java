package net.magicterra.worlddriver.bot.stagewright;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Terrain staging shared by every scene file — the sibling of {@link SceneBody}, which stages the
 * body. A scene that needs a floor to stand on should not have to own one.
 *
 * <p>This exists because it was copied instead. Six scene files each carried a byte-identical
 * {@code buildFloor} (five {@code private}, one package-private, differing only in that keyword)
 * behind 25 call sites, so the arena every scene stands on had six authors and no owner. The
 * copies stayed in step by luck: nothing would have failed if one of them had grown a wall.
 */
public final class SceneArena {
    private SceneArena() {}

    /**
     * An 11×11 stone slab at {@code floorY} centred on {@code cx, cz}, with the 18 blocks above
     * every cell cleared to air — a clean test floor.
     *
     * <p>The cleared column matters as much as the slab: scenes reuse arenas, and a block left
     * standing from a previous run reads as a real terrain feature to a pathfinder. The bounds
     * ({@code ±5} horizontal, {@code +1..+18} vertical) are what a caller's own cleanup box has to
     * cover, so a scene that builds ABOVE this envelope must clear what it built itself.
     *
     * <p>Eighteen deep is more than any current scene needs — it is residue hygiene left from when
     * scenes shared one world. Under grid isolation it is harmless and gets rebuilt over, so it
     * stays: shrinking it would trade a known-safe number for a saved millisecond.
     *
     * <p>Originally inlined from {@code AgentGameTestSupport#buildFloor} when the GameTest path was
     * retired.
     */
    public static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 1; dy <= 18; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
    }
}
