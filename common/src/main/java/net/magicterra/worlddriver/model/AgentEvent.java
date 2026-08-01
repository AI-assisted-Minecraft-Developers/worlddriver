package net.magicterra.worlddriver.model;
import net.minecraft.core.BlockPos;

public final class AgentEvent {
    public final long seq;
    public final long timestamp;
    public final String type;
    public final BlockPos pos;

    /**
     * The event's payload, serialized by {@code JsonCodec} like any other value:
     * a String for scalar payloads ({@code block.place} carries a block id,
     * {@code entity.death} an entity id), a Map for structured ones.
     *
     * <p>This used to be declared {@code String}, which forced every structured
     * emitter to pre-encode with {@code JsonCodec.encode(map)}. The result went on
     * the wire as JSON escaped INSIDE a JSON string
     * ({@code "data":"{\"phase\":\"sunset\"}"}), so the field was an undiscriminated
     * union: a consumer could not tell a scalar payload from a document without
     * already knowing the event type. gpt-player guessed with
     * {@code isinstance(d, dict)}, which was never true, silently disabling its
     * dusk interrupt — the failure mode its own comment calls "the #1 historical
     * killer". Emitters now pass the value itself and the codec does the encoding
     * once.
     */
    public final Object data;

    public AgentEvent(long seq, String type, BlockPos pos, Object data) {
        this.seq = seq;
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.pos = pos;
        this.data = data;
    }
}
