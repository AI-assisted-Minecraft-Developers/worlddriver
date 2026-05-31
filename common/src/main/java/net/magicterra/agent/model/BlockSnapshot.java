package net.magicterra.agent.model;
import net.minecraft.core.BlockPos;

public final class BlockSnapshot {
    public final BlockPos pos;
    public final String type;

    public BlockSnapshot(BlockPos pos, String type) {
        this.pos = pos;
        this.type = type;
    }
}
