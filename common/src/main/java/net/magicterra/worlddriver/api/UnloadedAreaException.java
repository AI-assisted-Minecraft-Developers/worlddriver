package net.magicterra.worlddriver.api;

import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * A read-only query reached into chunks that are not loaded. Refused rather than skipped: the
 * result is a flat array of rows, so a skipped chunk would read exactly like a chunk with nothing
 * in it, and loading it instead would stall the tick the query runs in.
 */
public final class UnloadedAreaException extends IllegalArgumentException {
    private final List<ChunkPos> chunks;

    public UnloadedAreaException(BlockPos center, int radius, List<ChunkPos> chunks) {
        super("mc.query q=blocks: the cube of radius " + radius + " around " + center.toShortString()
                + " touches " + chunks.size() + " unloaded chunk(s) "
                + chunks.stream().map(c -> "[" + c.x + ", " + c.z + "]").collect(Collectors.joining(" "))
                + "; the query never loads or generates chunks, so move the center, shrink in_radius,"
                + " or load the area first");
        this.chunks = List.copyOf(chunks);
    }

    /** The chunks that were not loaded, in chunk coordinates. */
    public List<ChunkPos> chunks() {
        return chunks;
    }
}
