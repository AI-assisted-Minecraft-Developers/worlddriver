package net.magicterra.agent.model;
import net.minecraft.core.BlockPos;

public final class AgentEvent {
    public final long seq;
    public final long timestamp;
    public final String type;
    public final BlockPos pos;
    public final String data;

    public AgentEvent(long seq, String type, BlockPos pos, String data) {
        this.seq = seq;
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.pos = pos;
        this.data = data;
    }
}
