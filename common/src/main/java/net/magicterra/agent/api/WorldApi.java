package net.magicterra.agent.api;

import net.magicterra.agent.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code mc.world.*} handlers, extracted from {@code AgentApi}. Captures and
 * restores an axis-aligned box of block states (plus block-entity NBT) into an
 * in-memory store so a test can stash a region, mutate it, then put it back
 * verbatim — the deterministic setup/teardown primitive the GameTest YAML layer
 * (proposal §4.1 C) builds on. Dispatch still flows through {@code AgentApi.route}
 * (single source of truth); all reads/writes bounce through
 * {@code AgentApi.onServerThread}.
 *
 * <p>Snapshots hold live {@link BlockState} / {@link CompoundTag} objects rather
 * than serialized bytes — restore is then an exact identity put with no
 * (de)serialization round-trip. The store is bounded ({@link #MAX_SNAPSHOTS}) and
 * cleared when the server detaches; it is JVM-local and never persisted.
 */
public final class WorldApi {
    /** Same per-call cell budget as {@code mc.action.fill} (= 32^3). */
    static final int MAX_VOLUME = 32768;
    /** Cap on retained snapshots; a new id past this throws rather than evict
     *  silently (a silent eviction would turn a later restore into a no-op). */
    static final int MAX_SNAPSHOTS = 64;

    private final AgentApi api;
    private final Map<String, Snapshot> store = new LinkedHashMap<>();
    private final AtomicLong autoId = new AtomicLong();

    WorldApi(AgentApi api) { this.api = api; }

    /** Immutable captured region. Indexing is x-major, then y, then z. */
    private static final class Snapshot {
        final int minX, minY, minZ, sx, sy, sz;
        final BlockState[] states;
        final CompoundTag[] beTags; // null entry == no block entity at that cell
        int nonAir;
        int blockEntities;

        Snapshot(int minX, int minY, int minZ, int sx, int sy, int sz) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.states = new BlockState[sx * sy * sz];
            this.beTags = new CompoundTag[sx * sy * sz];
        }

        int idx(int dx, int dy, int dz) { return (dx * sy + dy) * sz + dz; }
    }

    /** One block to (re)write: its state and optional block-entity NBT. */
    public record Cell(BlockPos pos, BlockState state, CompoundTag beTag) {}

    /** Write a list of blocks into the level on the calling (server) thread, restoring
     *  block-entity NBT where present. Shared by mc.world.restore and the path replayer. */
    public static void restoreCells(ServerLevel level, List<Cell> cells) {
        for (Cell c : cells) {
            level.setBlockAndUpdate(c.pos(), c.state());
            if (c.beTag() != null) {
                BlockEntity be = level.getBlockEntity(c.pos());
                if (be != null) {
                    try {
                        be.loadWithComponents(c.beTag(), level.registryAccess());
                        be.setChanged();
                    } catch (RuntimeException ignored) {
                        // Malformed/foreign BE data — block state is still
                        // restored; skip the contents rather than abort.
                    }
                }
            }
        }
    }

    /** Drop all retained snapshots — called when the server detaches. */
    public void clearSnapshots() {
        synchronized (store) { store.clear(); autoId.set(0); }
    }

    /**
     * Capture the inclusive box {@code from}..{@code to} under a caller-named
     * {@code id} (auto-generated when omitted). Re-using an id overwrites it.
     * {@code blockEntities:false} skips NBT capture (states only — cheaper, but
     * a chest's contents won't survive a restore). Returns
     * {@code {ok, id, from, to, blocks, nonAir, blockEntities}}.
     */
    public Map<String, Object> snapshot(Map<String, Object> params) {
        Params p = Params.of(params);
        BlockPos from = p.getPos("from");
        BlockPos to = p.getPos("to");
        if (from == null || to == null) throw new IllegalArgumentException("from and to required");
        boolean withBe = p.getBool("blockEntities", true);

        int minX = Math.min(from.getX(), to.getX()), maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY()), maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ()), maxZ = Math.max(from.getZ(), to.getZ());
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        long volume = (long) sx * sy * sz;
        if (volume > MAX_VOLUME) {
            throw new IllegalArgumentException("volume too large: " + volume + " > " + MAX_VOLUME);
        }

        String id = p.getNonBlank("id");
        if (id == null) id = "snap-" + autoId.incrementAndGet();
        final String snapId = id;
        synchronized (store) {
            if (!store.containsKey(snapId) && store.size() >= MAX_SNAPSHOTS) {
                throw new IllegalStateException("snapshot store full (" + MAX_SNAPSHOTS
                        + "); restore with discard:true or pick an existing id");
            }
        }

        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            Snapshot snap = new Snapshot(minX, minY, minZ, sx, sy, sz);
            BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
            for (int dx = 0; dx < sx; dx++)
                for (int dy = 0; dy < sy; dy++)
                    for (int dz = 0; dz < sz; dz++) {
                        cur.set(minX + dx, minY + dy, minZ + dz);
                        BlockState st = level.getBlockState(cur);
                        int i = snap.idx(dx, dy, dz);
                        snap.states[i] = st;
                        if (!st.isAir()) snap.nonAir++;
                        if (withBe) {
                            BlockEntity be = level.getBlockEntity(cur);
                            if (be != null) {
                                snap.beTags[i] = be.saveWithFullMetadata(level.registryAccess());
                                snap.blockEntities++;
                            }
                        }
                    }
            synchronized (store) { store.put(snapId, snap); }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("id", snapId);
            out.put("from", new BlockPos(minX, minY, minZ));
            out.put("to", new BlockPos(maxX, maxY, maxZ));
            out.put("blocks", sx * sy * sz);
            out.put("nonAir", snap.nonAir);
            out.put("blockEntities", snap.blockEntities);
            return out;
        });
    }

    /**
     * Put a previously-captured region back verbatim. {@code id} required; unknown
     * id throws. {@code discard:true} frees the snapshot after a successful
     * restore. Returns {@code {ok, id, restored, blockEntities}}.
     */
    public Map<String, Object> restore(Map<String, Object> params) {
        Params p = Params.of(params);
        String id = p.getNonBlank("id");
        if (id == null) throw new IllegalArgumentException("id required");
        boolean discard = p.getBool("discard", false);
        Snapshot snap;
        synchronized (store) { snap = store.get(id); }
        if (snap == null) throw new IllegalArgumentException("unknown snapshot id: " + id);

        ServerLevel level = api.level();
        Map<String, Object> result = api.onServerThread(() -> {
            // Build the cell list while counting, then delegate the actual block
            // writes to the shared restoreCells helper.
            List<Cell> cells = new ArrayList<>(snap.sx * snap.sy * snap.sz);
            int beRestored = 0;
            for (int dx = 0; dx < snap.sx; dx++)
                for (int dy = 0; dy < snap.sy; dy++)
                    for (int dz = 0; dz < snap.sz; dz++) {
                        int i = snap.idx(dx, dy, dz);
                        BlockPos pos = new BlockPos(snap.minX + dx, snap.minY + dy, snap.minZ + dz);
                        CompoundTag tag = snap.beTags[i];
                        cells.add(new Cell(pos, snap.states[i], tag));
                        if (tag != null) beRestored++;
                    }
            restoreCells(level, cells);
            int restored = cells.size();
            api.emit("world.restore", new BlockPos(snap.minX, snap.minY, snap.minZ),
                    id + "@" + restored + " blocks");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("id", id);
            out.put("restored", restored);
            out.put("blockEntities", beRestored);
            return out;
        });
        if (discard) synchronized (store) { store.remove(id); }
        return result;
    }
}
