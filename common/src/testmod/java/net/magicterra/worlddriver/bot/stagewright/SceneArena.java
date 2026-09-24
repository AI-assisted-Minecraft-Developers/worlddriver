package net.magicterra.worlddriver.bot.stagewright;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Terrain staging shared by every scene file — the sibling of {@link SceneBody}, which stages the
 * bot. A scene that needs a floor to stand on should not have to own one.
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

    /**
     * A {@code (2r+1) × h × (2r+1)} box of air with its bottom layer at {@code baseY}, centred on
     * {@code cx, cz} — the scrub that keeps a reused arena from handing the next run a block the
     * previous one left standing.
     *
     * <p>Absolute coordinates, deliberately: this is the sibling of {@link #buildFloor} and the two
     * are always called together. A scene whose terrain is stated in arena-relative offsets should
     * keep using {@code ctx.setBlock(dx, dy, dz, …)} instead — {@code check_scene_arena.py} reads a
     * scene's footprint off those literals, and it cannot see through a shared helper. That is why
     * the eight per-arena {@code clearBox(SceneContext)} scrubs in {@code journey/} stay where they
     * are rather than joining this one: they are not copies of it, they are each an arena's own
     * declared extent.
     *
     * <p>Three scene files carried a byte-identical copy of this, each with a javadoc saying it was
     * "inlined rather than reached across the testmod source-set boundary". There is no such
     * boundary — {@code SceneArena} is in this same source set and two of those three files already
     * imported it for {@link #buildFloor} — and the class the note pointed at,
     * {@code AgentGameTestServer}, has not existed since the GameTest path was retired. Same disease
     * as {@code buildFloor}'s six authors, one file later.
     */
    public static void clearBox(ServerLevel level, int cx, int baseY, int cz, int r, int h) {
        for (int dx = -r; dx <= r; dx++)
            for (int dy = 0; dy < h; dy++)
                for (int dz = -r; dz <= r; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, baseY + dy, cz + dz),
                            Blocks.AIR.defaultBlockState());
    }
}
