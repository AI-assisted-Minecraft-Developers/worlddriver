package net.magicterra.worlddriver.api;

import java.util.Optional;

import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.EventContracts;
import net.magicterra.worlddriver.model.DriverEvent;

/**
 * A {@link DriverEvent} as the one {@link EventContracts.Event} value. Every path that writes an event
 * comes through here, so a push frame, a replayed page and a wait's reply cannot disagree on its shape.
 */
public final class EventWire {
    private EventWire() {}

    public static EventContracts.Event dto(DriverEvent e) {
        Optional<BlockPosition> pos = e.pos == null ? Optional.empty()
                : Optional.of(new BlockPosition(e.pos.getX(), e.pos.getY(), e.pos.getZ()));
        return new EventContracts.Event(e.seq, e.timestamp, e.type, pos,
                e.data.isNull() ? Optional.empty() : Optional.of(e.data));
    }

    /** The event's value tree, checked against its contract. */
    public static Object tree(DriverEvent e) {
        return EventContracts.EVENT.encode(dto(e));
    }
}
