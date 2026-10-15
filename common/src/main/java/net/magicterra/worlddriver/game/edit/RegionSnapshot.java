package net.magicterra.worlddriver.game.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An axis-aligned box of block states, plus block-entity NBT, captured so it can be put back verbatim.
 *
 * <p>Holds live {@link BlockState} / {@link CompoundTag} objects rather than serialized bytes — restore
 * is then an exact identity put with no (de)serialization round-trip. Immutable once captured. Indexing
 * is x-major, then y, then z.
 */
public final class RegionSnapshot {
    public final int minX, minY, minZ, sx, sy, sz;
    private final BlockState[] states;
    private final CompoundTag[] beTags; // null entry == no block entity at that cell
    private int nonAir;
    private int blockEntities;

    private RegionSnapshot(int minX, int minY, int minZ, int sx, int sy, int sz) {
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.states = new BlockState[sx * sy * sz];
        this.beTags = new CompoundTag[sx * sy * sz];
    }

    private int idx(int dx, int dy, int dz) { return (dx * sy + dy) * sz + dz; }

    /** Capture the box of {@code sx}×{@code sy}×{@code sz} cells from {@code min}; {@code blockEntities:false}
     *  skips NBT (states only — cheaper, but a chest's contents won't survive a restore). Server thread. */
    public static RegionSnapshot capture(ServerLevel level, BlockPos min, int sx, int sy, int sz, boolean blockEntities) {
        RegionSnapshot snap = new RegionSnapshot(min.getX(), min.getY(), min.getZ(), sx, sy, sz);
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < sx; dx++)
            for (int dy = 0; dy < sy; dy++)
                for (int dz = 0; dz < sz; dz++) {
                    cur.set(snap.minX + dx, snap.minY + dy, snap.minZ + dz);
                    BlockState st = level.getBlockState(cur);
                    int i = snap.idx(dx, dy, dz);
                    snap.states[i] = st;
                    if (!st.isAir()) snap.nonAir++;
                    if (blockEntities) {
                        BlockEntity be = level.getBlockEntity(cur);
                        if (be != null) {
                            snap.beTags[i] = be.saveWithFullMetadata(level.registryAccess());
                            snap.blockEntities++;
                        }
                    }
                }
        return snap;
    }

    /** Put every captured cell back, in capture order. Server thread. */
    public void restore(ServerLevel level) {
        for (int dx = 0; dx < sx; dx++)
            for (int dy = 0; dy < sy; dy++)
                for (int dz = 0; dz < sz; dz++) {
                    int i = idx(dx, dy, dz);
                    BlockEdits.restore(level, new BlockPos(minX + dx, minY + dy, minZ + dz), states[i], beTags[i]);
                }
    }

    public int cells() { return sx * sy * sz; }

    public int nonAir() { return nonAir; }

    /** The cells captured with block-entity NBT, which a restore puts back too. */
    public int blockEntities() { return blockEntities; }
}
