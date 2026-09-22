package net.magicterra.worlddriver.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.magicterra.worlddriver.bot.util.BlockMatch;
import net.magicterra.worlddriver.model.Params;
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
    /** The largest radius whose {@code (2r+1)^3} cube fits {@link WorldApi#MAX_VOLUME}: 31^3 = 29,791. */
    static final int MAX_RADIUS = 15;

    /** Every key a row can carry — {@link DriverApi#checkSelect} validates against it. */
    private static final Set<String> SELECT_KEYS = Set.of("pos", "type", "state");

    private final int radius;
    private final Predicate<BlockState> match;
    private final List<String> select;

    private BlockQuery(int radius, Predicate<BlockState> match, List<String> select) {
        this.radius = radius;
        this.match = match;
        this.select = select;
    }

    /** Validates the parameters without touching the world, so a refused call costs no server hop. */
    static BlockQuery of(QueryParams p) {
        int r = Math.max(0, Params.toInt(p.filter.get("in_radius"), 0));
        if (r > MAX_RADIUS) {
            long side = 2L * r + 1;
            throw new IllegalArgumentException("mc.query q=blocks: in_radius " + r + " scans " + side * side * side
                    + " cells, over the " + WorldApi.MAX_VOLUME + "-cell budget (max " + MAX_RADIUS + ")");
        }
        // filter.type restricts to one block id; exact ids and '#tag' selectors both match.
        Object type = p.filter.get("type");
        Predicate<BlockState> match = type instanceof String s && !s.isBlank() ? BlockMatch.of(s) : null;
        DriverApi.checkSelect(p.select, SELECT_KEYS);
        return new BlockQuery(r, match, p.select);
    }

    /** Runs on the server thread: {@code getChunkNow} answers only from loaded chunks there. */
    List<Map<String, Object>> scan(ServerLevel level, BlockPos center) {
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

        List<Map<String, Object>> out = new ArrayList<>();
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    bp.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    LevelChunk chunk = chunks[((bp.getZ() >> 4) - minCz) * w + ((bp.getX() >> 4) - minCx)];
                    BlockState st = chunk.getBlockState(bp);
                    if (st.isAir()) continue;
                    if (match != null && !match.test(st)) continue;
                    out.add(DriverApi.project(row(bp.immutable(), st), select));
                }
        return out;
    }

    private static Map<String, Object> row(BlockPos pos, BlockState st) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pos", pos);
        row.put("type", ApiSupport.blockId(st));
        // Blockstate properties (lit/facing/half/…) so callers can verify more than the id.
        // Omitted for property-less states (stone etc.) to keep large scans lean.
        if (!st.getProperties().isEmpty()) {
            Map<String, Object> stateMap = new LinkedHashMap<>();
            for (var prop : st.getProperties()) {
                stateMap.put(prop.getName(), stringifyProperty(st, prop));
            }
            row.put("state", stateMap);
        }
        return row;
    }

    /** Property value as the string a /setblock predicate would use ("true", "north", "3"). */
    private static <T extends Comparable<T>> String stringifyProperty(BlockState st, Property<T> prop) {
        return prop.getName(st.getValue(prop));
    }
}
