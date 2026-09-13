package net.magicterra.worlddriver.testcontent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A marker's label and free-form arguments. Every marker has one (the block is an
 * {@code EntityBlock}); most leave it empty. The label's meaning depends on the role — a goal type
 * for {@code goal}, {@code same} or a block id for {@code watch}, a sequence number for {@code via},
 * the scene's name on the anchor ({@code origin}, or {@code start} standing in for it).
 */
public final class MarkerBlockEntity extends BlockEntity {
    /**
     * Every marker entity alive on a server level, kept from the moment a chunk registers it
     * ({@link #clearRemoved}) until the chunk drops it ({@link #setRemoved}). This is how a scene
     * is found in the world by the name on its anchor without walking every loaded chunk.
     */
    private static final Set<MarkerBlockEntity> LIVE = ConcurrentHashMap.newKeySet();

    private String label = "";
    private CompoundTag args = new CompoundTag();

    public MarkerBlockEntity(BlockPos pos, BlockState state) {
        super(MarkerContent.MARKER_ENTITY.get(), pos, state);
    }

    /** The loaded markers of {@code level}. */
    public static List<MarkerBlockEntity> live(ServerLevel level) {
        List<MarkerBlockEntity> out = new ArrayList<>();
        for (MarkerBlockEntity be : LIVE) if (be.level == level && !be.isRemoved()) out.add(be);
        return out;
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level instanceof ServerLevel) LIVE.add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LIVE.remove(this);
    }

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
        changed();
    }

    /** The arguments as stored; mutate through {@link #setArgs} so the entity is marked changed. */
    public CompoundTag args() {
        return args;
    }

    public void setArgs(CompoundTag args) {
        this.args = args == null ? new CompoundTag() : args.copy();
        changed();
    }

    /** The box an anchor declares: six offsets from this cell, min corner then max; null when none. */
    public int[] box() {
        int[] b = args.getIntArray("box");
        return b.length == 6 ? b : null;
    }

    /** Declares the box, given as its two corners relative to this cell; the other args stay. */
    public void setBox(Vec3i minOffset, Vec3i maxOffset) {
        CompoundTag next = args.copy();
        next.putIntArray("box", new int[] { minOffset.getX(), minOffset.getY(), minOffset.getZ(),
                maxOffset.getX(), maxOffset.getY(), maxOffset.getZ() });
        setArgs(next);
    }

    /**
     * Marks the entity dirty and, on the server, pushes it to clients: a label or box set on an
     * existing marker changes no block state, so nothing else would tell the renderer or the
     * anchor screen.
     */
    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("label", label);
        tag.put("args", args.copy());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        label = tag.getString("label");
        args = tag.getCompound("args").copy();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
