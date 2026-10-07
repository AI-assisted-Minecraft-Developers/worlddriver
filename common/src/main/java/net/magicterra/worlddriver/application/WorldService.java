package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.bot.util.RegistryIds;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.game.WorldCell;

import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.world.WorldApi;
import net.magicterra.worlddriver.api.methods.world.WorldApiBinding;
import net.magicterra.worlddriver.api.methods.world.WorldContracts;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.BlockParams;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.BlockReading;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.Light;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.RestoreParams;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.Restored;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.SnapshotParams;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.Snapshotted;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code mc.world.*} handlers, extracted from {@code DriverApplication}. Captures and
 * restores an axis-aligned box of block states (plus block-entity NBT) into an
 * in-memory store so a test can stash a region, mutate it, then put it back
 * verbatim — the deterministic setup/teardown primitive the GameTest YAML layer
 * (proposal §4.1 C) builds on. All reads and writes run on the server thread.
 *
 * <p>Snapshots hold live {@link BlockState} / {@link CompoundTag} objects rather
 * than serialized bytes — restore is then an exact identity put with no
 * (de)serialization round-trip. The store is bounded ({@link WorldContracts#MAX_SNAPSHOTS}) and
 * cleared when the server detaches; it is JVM-local and never persisted.
 */
public final class WorldService implements WorldApi {
    /** Same per-call cell budget as {@code mc.action.fill} (= 32^3). */
    static final int MAX_VOLUME = WorldContracts.MAX_VOLUME;

    private final DriverApplication api;
    private final Map<String, Snapshot> store = new LinkedHashMap<>();
    private final AtomicLong autoId = new AtomicLong();

    WorldService(DriverApplication api) { this.api = api; }

    List<ApiMethod<?, ?>> methods() {
        return WorldApiBinding.methods(this);
    }

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

    /** Write a list of blocks into the level on the calling (server) thread, restoring
     *  block-entity NBT where present. Shared by mc.world.restore and the path replayer. */
    public static void restoreCells(ServerLevel level, List<WorldCell> cells) {
        for (WorldCell c : cells) {
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
     * Read-only single-cell inspection: blockstate id + property map, light
     * levels, and (opt-in) block-entity NBT. External consumers had no
     * first-class way to answer "what blockstate is at this position?" or read
     * a light level at all (docs/archive/feedback/2026-06-08); every verification became
     * an {@code execute if block … run setblock <scratch>} hack. This is the
     * verify half of the build→verify loop, one round-trip, no side effects.
     */
    @Override
    public CompletionStage<BlockReading> block(BlockParams p, CallContext ctx) {
        BlockPos pos = Positions.blockPos(p.pos());
        ServerLevel level = api.level();
        return api.requireServerHop().submit(() -> {
            BlockState st = level.getBlockState(pos);
            Light light = new Light(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
            Optional<String> nbt = Optional.empty();
            if (p.nbt()) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) nbt = Optional.of(be.saveWithFullMetadata(level.registryAccess()).toString());
            }
            return new BlockReading(p.pos(), RegistryIds.blockId(st), RegistryIds.stateProperties(st), light, nbt);
        }, ctx);
    }

    /**
     * Capture the inclusive box {@code from}..{@code to} under a caller-named
     * {@code id} (auto-generated when omitted). Re-using an id overwrites it.
     * {@code blockEntities:false} skips NBT capture (states only — cheaper, but
     * a chest's contents won't survive a restore). The contract has held the box
     * to {@link #MAX_VOLUME}.
     */
    @Override
    public CompletionStage<Snapshotted> snapshot(SnapshotParams p, CallContext ctx) {
        int minX = Math.min(p.from().x(), p.to().x()), maxX = Math.max(p.from().x(), p.to().x());
        int minY = Math.min(p.from().y(), p.to().y()), maxY = Math.max(p.from().y(), p.to().y());
        int minZ = Math.min(p.from().z(), p.to().z()), maxZ = Math.max(p.from().z(), p.to().z());
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;

        String snapId = p.id().orElseGet(() -> "snap-" + autoId.incrementAndGet());
        synchronized (store) {
            if (!store.containsKey(snapId) && store.size() >= WorldContracts.MAX_SNAPSHOTS) {
                throw new IllegalStateException("snapshot store full (" + WorldContracts.MAX_SNAPSHOTS
                        + "); restore with discard:true or pick an existing id");
            }
        }

        ServerLevel level = api.level();
        return api.requireServerHop().submit(() -> {
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
                        if (p.blockEntities()) {
                            BlockEntity be = level.getBlockEntity(cur);
                            if (be != null) {
                                snap.beTags[i] = be.saveWithFullMetadata(level.registryAccess());
                                snap.blockEntities++;
                            }
                        }
                    }
            synchronized (store) { store.put(snapId, snap); }
            return new Snapshotted(snapId, new BlockPosition(minX, minY, minZ), new BlockPosition(maxX, maxY, maxZ),
                    sx * sy * sz, snap.nonAir, snap.blockEntities);
        }, ctx);
    }

    /**
     * Put a previously-captured region back verbatim. An unknown id is refused.
     * {@code discard:true} frees the snapshot after a successful restore.
     */
    @Override
    public CompletionStage<Restored> restore(RestoreParams p, CallContext ctx) {
        String id = p.id();
        Snapshot snap;
        synchronized (store) { snap = store.get(id); }
        if (snap == null) throw ApiException.invalidParam("/id", ApiIssue.Code.INVALID_VALUE, "unknown snapshot id: " + id);

        ServerLevel level = api.level();
        long cursor = api.eventCursor();
        return api.requireServerHop().submit(() -> {
            // Build the cell list while counting, then delegate the actual block
            // writes to the shared restoreCells helper.
            List<WorldCell> cells = new ArrayList<>(snap.sx * snap.sy * snap.sz);
            int beRestored = 0;
            for (int dx = 0; dx < snap.sx; dx++)
                for (int dy = 0; dy < snap.sy; dy++)
                    for (int dz = 0; dz < snap.sz; dz++) {
                        int i = snap.idx(dx, dy, dz);
                        BlockPos pos = new BlockPos(snap.minX + dx, snap.minY + dy, snap.minZ + dz);
                        CompoundTag tag = snap.beTags[i];
                        cells.add(new WorldCell(pos, snap.states[i], tag));
                        if (tag != null) beRestored++;
                    }
            restoreCells(level, cells);
            api.emit("world.restore", new BlockPos(snap.minX, snap.minY, snap.minZ),
                    JsonValue.of(id + "@" + cells.size() + " blocks"));
            if (p.discard()) synchronized (store) { store.remove(id); }
            return new Restored(id, cells.size(), beRestored, api.eventsAfter(cursor, p.returnEvents()));
        }, ctx);
    }
}
