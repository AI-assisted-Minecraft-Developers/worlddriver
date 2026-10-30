package net.magicterra.worlddriver.game.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A cube of blocks read from chunks that are already loaded, and only from those: a read that loaded or generated
 * chunks would stall the tick it runs in. Server thread only, where {@code getChunkNow} answers from loaded chunks.
 */
public final class LoadedCube {
    private LoadedCube() {}

    /**
     * Hand every non-air cell within Chebyshev {@code radius} of {@code center} to {@code cell}, x outermost, then y,
     * then z; the position is reused between calls. Reads nothing, and answers the chunks that are not loaded, when the
     * cube touches any; an empty answer means every cell was read.
     */
    public static List<ChunkPos> nonAir(
            ServerLevel level, BlockPos center, int radius, BiConsumer<BlockPos, BlockState> cell) {
        int minCx = (center.getX() - radius) >> 4;
        int maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4;
        int maxCz = (center.getZ() + radius) >> 4;
        int w = maxCx - minCx + 1;
        LevelChunk[] chunks = new LevelChunk[w * (maxCz - minCz + 1)];
        List<ChunkPos> unloaded = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c == null) {
                    unloaded.add(new ChunkPos(cx, cz));
                }
                chunks[(cz - minCz) * w + cx - minCx] = c;
            }
        }
        if (!unloaded.isEmpty()) {
            return unloaded;
        }

        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    bp.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    LevelChunk chunk = chunks[((bp.getZ() >> 4) - minCz) * w + (bp.getX() >> 4) - minCx];
                    BlockState st = chunk.getBlockState(bp);
                    if (!st.isAir()) {
                        cell.accept(bp, st);
                    }
                }
            }
        }
        return List.of();
    }
}
