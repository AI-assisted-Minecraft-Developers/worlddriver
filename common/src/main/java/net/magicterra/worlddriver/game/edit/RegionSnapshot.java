package net.magicterra.worlddriver.game.edit;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * An axis-aligned box of block states, plus block-entity NBT, captured so it can be put back verbatim.
 *
 * <p>Holds live {@link BlockState} / {@link CompoundTag} objects rather than serialized bytes — restore is then an
 * exact identity put with no (de)serialization round-trip. Immutable once captured. Indexing is x-major, then y, then
 * z.
 */
public final class RegionSnapshot {
    public final int minX;

    public final int minY;

    public final int minZ;

    public final int sx;

    public final int sy;

    public final int sz;

    private final BlockState[] states;

    private final CompoundTag[] beTags; // null entry == no block entity at that cell

    private final ResourceKey<Level> dimension;

    private final List<CompoundTag> entityTags = new ArrayList<>();

    private boolean withEntities;

    private int nonAir;

    private int blockEntities;

    /** What a restore did to the box's entities. */
    public record EntityOutcome(int removed, int restored) {}

    private RegionSnapshot(ResourceKey<Level> dimension, int minX, int minY, int minZ, int sx, int sy, int sz) {
        this.dimension = dimension;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.states = new BlockState[sx * sy * sz];
        this.beTags = new CompoundTag[sx * sy * sz];
    }

    private int idx(int dx, int dy, int dz) {
        return (dx * sy + dy) * sz + dz;
    }

    /**
     * Capture the box of {@code sx}×{@code sy}×{@code sz} cells from {@code min}; {@code blockEntities:false} skips NBT
     * (states only — cheaper, but a chest's contents won't survive a restore); {@code entities:true} also keeps the
     * non-player entities in the box. Server thread.
     */
    public static RegionSnapshot capture(
            ServerLevel level, BlockPos min, int sx, int sy, int sz, boolean blockEntities, boolean entities) {
        RegionSnapshot snap = new RegionSnapshot(level.dimension(), min.getX(), min.getY(), min.getZ(), sx, sy, sz);
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < sx; dx++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dz = 0; dz < sz; dz++) {
                    cur.set(snap.minX + dx, snap.minY + dy, snap.minZ + dz);
                    BlockState st = level.getBlockState(cur);
                    int i = snap.idx(dx, dy, dz);
                    snap.states[i] = st;
                    if (!st.isAir()) {
                        snap.nonAir++;
                    }
                    if (blockEntities) {
                        BlockEntity be = level.getBlockEntity(cur);
                        if (be != null) {
                            snap.beTags[i] = be.saveWithFullMetadata(level.registryAccess());
                            snap.blockEntities++;
                        }
                    }
                }
            }
        }
        if (entities) {
            snap.withEntities = true;
            for (Entity e : level.getEntities((Entity) null, snap.box(), x -> !(x instanceof Player))) {
                if (e.isPassenger()) {
                    continue; // its vehicle saves it
                }
                CompoundTag tag = new CompoundTag();
                if (e.save(tag)) {
                    snap.entityTags.add(tag);
                }
            }
        }
        return snap;
    }

    private AABB box() {
        return new AABB(minX, minY, minZ, minX + sx, minY + sy, minZ + sz);
    }

    /** The dimension the box was captured in. */
    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** Put every captured cell back, in capture order. Server thread. */
    public void restore(ServerLevel level) {
        for (int dx = 0; dx < sx; dx++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dz = 0; dz < sz; dz++) {
                    int i = idx(dx, dy, dz);
                    BlockEdits.restore(level, new BlockPos(minX + dx, minY + dy, minZ + dz), states[i], beTags[i]);
                }
            }
        }
    }

    /**
     * Swap the box's non-player entities for the kept ones; nothing happens unless the capture asked for entities. A
     * kept entity whose UUID is still taken is not added (and not given a new one), so it is left out of the count.
     * Server thread, after {@link #restore}.
     */
    public EntityOutcome restoreEntities(ServerLevel level) {
        if (!withEntities) {
            return new EntityOutcome(0, 0);
        }
        int removed = 0;
        int restored = 0;
        for (Entity e : level.getEntities((Entity) null, box(), x -> !(x instanceof Player))) {
            e.discard();
            removed++;
        }
        for (CompoundTag tag : entityTags) {
            Entity entity = EntityType.loadEntityRecursive(tag, level, e -> e);
            // addFreshEntityWithPassengers answers nothing and drops a duplicate UUID silently, so ask first.
            if (entity == null || level.getEntity(entity.getUUID()) != null) {
                continue;
            }
            level.addFreshEntityWithPassengers(entity);
            restored++;
        }
        return new EntityOutcome(removed, restored);
    }

    public int cells() {
        return sx * sy * sz;
    }

    public int nonAir() {
        return nonAir;
    }

    /** The cells captured with block-entity NBT, which a restore puts back too. */
    public int blockEntities() {
        return blockEntities;
    }

    /** The non-player root entities kept; 0 unless the capture asked for entities. */
    public int entities() {
        return entityTags.size();
    }
}
