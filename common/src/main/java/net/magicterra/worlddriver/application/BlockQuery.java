package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.UnloadedAreaException;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.QueryContract;
import net.magicterra.worlddriver.api.methods.QueryContract.BlockField;
import net.magicterra.worlddriver.api.methods.QueryContract.BlockRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.magicterra.worlddriver.bot.util.BlockMatch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code mc.query q=blocks}: every non-air cell of a cube, as rows.
 *
 * <p>The whole cube is read in one server-thread task, so the radius is held to the cell budget
 * {@code mc.action.fill} and {@code mc.world.snapshot} use, and only chunks that are already
 * loaded are read. A read-only query that loaded or generated chunks would stall the tick it runs
 * in; instead an area that touches an unloaded chunk is refused with {@link UnloadedAreaException}.
 */
final class BlockQuery {
    /** The largest radius whose {@code (2r+1)^3} cube fits the cell budget: 31^3 = 29,791. */
    static final int MAX_RADIUS = QueryContract.MAX_BLOCK_RADIUS;

    private final int radius;
    private final Predicate<BlockState> match;
    private final Optional<List<BlockField>> select;

    private BlockQuery(int radius, Predicate<BlockState> match, Optional<List<BlockField>> select) {
        this.radius = radius;
        this.match = match;
        this.select = select;
    }

    /** The contract has bounded the radius and the select keys; a {@code #tag} filter is resolved here. */
    static BlockQuery of(QueryContract.Blocks p) {
        return new BlockQuery(p.filter().inRadius(), p.filter().type().map(BlockMatch::of).orElse(null), p.select());
    }

    int radius() {
        return radius;
    }

    /** One row with only the selected fields, so the client fallback projects like the server. */
    BlockRow row(BlockPosition pos, String type, Optional<Map<String, String>> state) {
        return new BlockRow(keep(BlockField.POS, pos), keep(BlockField.TYPE, type), state.filter(s -> wants(BlockField.STATE)));
    }

    private <T> Optional<T> keep(BlockField f, T value) {
        return wants(f) ? Optional.of(value) : Optional.empty();
    }

    private boolean wants(BlockField f) {
        return select.isEmpty() || select.get().isEmpty() || select.get().contains(f);
    }

    /** Runs on the server thread: {@code getChunkNow} answers only from loaded chunks there. */
    List<BlockRow> scan(ServerLevel level, BlockPos center) {
        int minCx = (center.getX() - radius) >> 4, maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4, maxCz = (center.getZ() + radius) >> 4;
        int w = maxCx - minCx + 1;
        LevelChunk[] chunks = new LevelChunk[w * (maxCz - minCz + 1)];
        List<ChunkPos> unloaded = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++)
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c == null) unloaded.add(new ChunkPos(cx, cz));
                chunks[(cz - minCz) * w + (cx - minCx)] = c;
            }
        if (!unloaded.isEmpty()) throw new UnloadedAreaException(center, radius, unloaded);

        List<BlockRow> out = new ArrayList<>();
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    bp.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    LevelChunk chunk = chunks[((bp.getZ() >> 4) - minCz) * w + ((bp.getX() >> 4) - minCx)];
                    BlockState st = chunk.getBlockState(bp);
                    if (st.isAir()) continue;
                    if (match != null && !match.test(st)) continue;
                    out.add(row(new BlockPosition(bp.getX(), bp.getY(), bp.getZ()), ApiSupport.blockId(st), state(st)));
                }
        return out;
    }

    /**
     * Blockstate properties (lit/facing/half/…) as a /setblock predicate spells them, so callers can verify more
     * than the id; absent for a property-less state, which keeps large scans lean.
     */
    static Optional<Map<String, String>> state(BlockState st) {
        if (st.getProperties().isEmpty()) return Optional.empty();
        Map<String, String> stateMap = new LinkedHashMap<>();
        for (var prop : st.getProperties()) stateMap.put(prop.getName(), stringifyProperty(st, prop));
        return Optional.of(stateMap);
    }

    private static <T extends Comparable<T>> String stringifyProperty(BlockState st, Property<T> prop) {
        return prop.getName(st.getValue(prop));
    }
}
