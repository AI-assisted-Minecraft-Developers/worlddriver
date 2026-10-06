package net.magicterra.worlddriver.api;

import java.util.Optional;

import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.EventContracts;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.protocol.JsonCodec;

/**
 * A {@link DriverEvent} as the one {@link EventContracts.Event} value. Every path that writes an event
 * comes through here, so a push frame, a replayed page and a wait's reply cannot disagree on its shape.
 */
public final class EventWire {
    private EventWire() {}

    public static EventContracts.Event dto(DriverEvent e) {
        Optional<BlockPosition> pos = e.pos == null ? Optional.empty()
                : Optional.of(new BlockPosition(e.pos.getX(), e.pos.getY(), e.pos.getZ()));
        return new EventContracts.Event(e.seq, e.timestamp, e.type, pos, json(e.data));
    }

    /** The event's value tree, checked against its contract. */
    public static Object tree(DriverEvent e) {
        return EventContracts.EVENT.encode(dto(e));
    }

    /**
     * A domain value (an emitter's payload, a legacy map holding a BlockPos) as JSON, adapted the way a
     * reply is; empty for null. A value that is no JSON at all becomes its text: an event is never
     * dropped for its payload.
     */
    public static Optional<JsonValue> json(Object data) {
        if (data == null) return Optional.empty();
        Object tree;
        try {
            tree = JsonCodec.decode(WireValues.encode(data));
        } catch (RuntimeException notJson) {
            tree = String.valueOf(data);
        }
        return tree == null ? Optional.empty() : Optional.of(JsonValue.of(tree));
    }
}
