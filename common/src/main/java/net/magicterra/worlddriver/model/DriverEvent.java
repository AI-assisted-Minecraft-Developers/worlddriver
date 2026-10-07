package net.magicterra.worlddriver.model;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.minecraft.core.BlockPos;

public final class DriverEvent {
    public final long seq;
    public final long timestamp;
    public final String type;
    public final BlockPos pos;

    /**
     * The event's payload as a value tree: a String for scalar payloads ({@code block.place} carries a
     * block id, {@code entity.death} an entity id), an object for structured ones, {@link JsonValue#NULL}
     * for none. Typed as a tree so an emitter cannot hand the wire a domain object, and so a structured
     * payload is never pre-encoded into a JSON string inside the JSON event (which once hid every
     * document payload from consumers that tested for an object).
     */
    public final JsonValue data;

    public DriverEvent(long seq, String type, BlockPos pos, JsonValue data) {
        this.seq = seq;
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.pos = pos;
        this.data = data == null ? JsonValue.NULL : data;
    }
}
