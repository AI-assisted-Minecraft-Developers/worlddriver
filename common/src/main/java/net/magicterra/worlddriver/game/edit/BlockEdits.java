package net.magicterra.worlddriver.game.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Writes straight into the level, as an operator would: no body places anything. Server thread only. */
public final class BlockEdits {
    private BlockEdits() {}

    /** Put {@code state} at {@code pos}; answers what stood there before. */
    public static BlockState set(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState before = level.getBlockState(pos);
        level.setBlockAndUpdate(pos, state);
        return before;
    }

    /** Fill the inclusive box {@code min}..{@code max} with {@code state}; answers the number of cells written. */
    public static int fill(ServerLevel level, BlockPos min, BlockPos max, BlockState state) {
        int n = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), state);
                    n++;
                }
            }
        }
        return n;
    }

    /** Put {@code state} back at {@code pos}, and the block entity's {@code tag} when it is not null. */
    public static void restore(ServerLevel level, BlockPos pos, BlockState state, CompoundTag tag) {
        level.setBlockAndUpdate(pos, state);
        if (tag != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                try {
                    be.loadWithComponents(tag, level.registryAccess());
                    be.setChanged();
                } catch (RuntimeException ignored) {
                    // Malformed/foreign BE data — block state is still
                    // restored; skip the contents rather than abort.
                }
            }
        }
    }
}
