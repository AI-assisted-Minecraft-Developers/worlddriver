package net.magicterra.worlddriver.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.game.WorldCell;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
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
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.game.edit.BlockEdits;
import net.magicterra.worlddriver.game.edit.RegionSnapshot;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code mc.world.*} handlers, extracted from {@code DriverApplication}. Captures and restores an axis-aligned box of
 * block states (plus block-entity NBT) into an in-memory store so a test can stash a region, mutate it, then put it
 * back verbatim — the deterministic setup/teardown primitive the GameTest YAML layer (proposal §4.1 C) builds on. All
 * reads and writes run on the server thread.
 *
 * <p>The store of {@link RegionSnapshot}s is bounded ({@link WorldContracts#MAX_SNAPSHOTS}) and cleared when the server
 * detaches; it is JVM-local and never persisted.
 */
public final class WorldService implements WorldApi {
    /** Same per-call cell budget as {@code mc.action.fill} (= 32^3). */
    static final int MAX_VOLUME = WorldContracts.MAX_VOLUME;

    private static final String OVERWORLD = "minecraft:overworld";

    private final DriverApplication api;
    private final Map<String, RegionSnapshot> store = new LinkedHashMap<>();
    private final AtomicLong autoId = new AtomicLong();

    WorldService(DriverApplication api) {
        this.api = api;
    }

    List<ApiMethod<?, ?>> methods() {
        return WorldApiBinding.methods(this);
    }

    /**
     * Write a list of blocks into the level on the calling (server) thread, restoring block-entity NBT where present.
     * The path replayer's restore.
     */
    public static void restoreCells(ServerLevel level, List<WorldCell> cells) {
        for (WorldCell c : cells) BlockEdits.restore(level, c.pos(), c.state(), c.beTag());
    }

    /** Drop all retained snapshots — called when the server detaches. */
    public void clearSnapshots() {
        synchronized (store) {
            store.clear();
            autoId.set(0);
        }
    }

    /**
     * Read-only single-cell inspection: blockstate id + property map, light levels, and (opt-in) block-entity NBT.
     * External consumers had no first-class way to answer "what blockstate is at this position?" or read a light level
     * at all (docs/archive/feedback/2026-06-08); every verification became an {@code execute if block … run setblock
     * <scratch>} hack. This is the verify half of the build→verify loop, one round-trip, no side effects.
     */
    @Override
    public CompletionStage<BlockReading> block(BlockParams p, CallContext ctx) {
        BlockPos pos = Positions.blockPos(p.pos());
        ServerLevel level = levelOf(p.dimension().orElse(OVERWORLD));
        return api.requireServerHop()
                .submit(
                        () -> {
                            BlockState st = level.getBlockState(pos);
                            Light light = new Light(
                                    level.getBrightness(LightLayer.BLOCK, pos),
                                    level.getBrightness(LightLayer.SKY, pos));
                            Optional<String> nbt = Optional.empty();
                            if (p.nbt()) {
                                BlockEntity be = level.getBlockEntity(pos);
                                if (be != null)
                                    nbt = Optional.of(be.saveWithFullMetadata(level.registryAccess())
                                            .toString());
                            }
                            return new BlockReading(
                                    p.pos(), RegistryIds.blockId(st), RegistryIds.stateProperties(st), light, nbt);
                        },
                        ctx);
    }

    /**
     * Capture the inclusive box {@code from}..{@code to} under a caller-named {@code id} (auto-generated when omitted).
     * Re-using an id overwrites it. {@code blockEntities:false} skips NBT capture (states only — cheaper, but a chest's
     * contents won't survive a restore). The contract has held the box to {@link #MAX_VOLUME}.
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

        ServerLevel level = levelOf(p.dimension().orElse(OVERWORLD));
        return api.requireServerHop()
                .submit(
                        () -> {
                            RegionSnapshot snap = RegionSnapshot.capture(
                                    level, new BlockPos(minX, minY, minZ), sx, sy, sz, p.blockEntities(), p.entities());
                            synchronized (store) {
                                store.put(snapId, snap);
                            }
                            return new Snapshotted(
                                    snapId,
                                    new BlockPosition(minX, minY, minZ),
                                    new BlockPosition(maxX, maxY, maxZ),
                                    snap.cells(),
                                    snap.nonAir(),
                                    snap.blockEntities(),
                                    snap.entities());
                        },
                        ctx);
    }

    /** The level a dimension id names; an id the server has no level for is refused. */
    private ServerLevel levelOf(String dimension) {
        ResourceLocation id = ResourceLocation.parse(dimension);
        ServerLevel level = api.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null)
            throw ApiException.invalidParam("/dimension", ApiIssue.Code.INVALID_VALUE, "no dimension " + id);
        return level;
    }

    /**
     * Put a previously-captured region back verbatim. An unknown id is refused. {@code discard:true} frees the snapshot
     * after a successful restore.
     */
    @Override
    public CompletionStage<Restored> restore(RestoreParams p, CallContext ctx) {
        String id = p.id();
        RegionSnapshot snap;
        synchronized (store) {
            snap = store.get(id);
        }
        if (snap == null)
            throw ApiException.invalidParam("/id", ApiIssue.Code.INVALID_VALUE, "unknown snapshot id: " + id);

        ServerLevel level = levelOf(snap.dimension().location().toString());
        long cursor = api.eventCursor();
        return api.requireServerHop()
                .submit(
                        () -> {
                            snap.restore(level);
                            RegionSnapshot.EntityOutcome entities = snap.restoreEntities(level);
                            api.emit(
                                    EventCatalog.WORLD_RESTORE,
                                    new BlockPos(snap.minX, snap.minY, snap.minZ),
                                    new EventCatalog.WorldRestore(id, snap.cells()));
                            if (p.discard())
                                synchronized (store) {
                                    store.remove(id);
                                }
                            return new Restored(
                                    id,
                                    snap.cells(),
                                    snap.blockEntities(),
                                    entities.removed(),
                                    entities.restored(),
                                    api.eventsAfter(cursor, p.returnEvents()));
                        },
                        ctx);
    }
}
