package net.magicterra.worlddriver.api;

import java.util.Optional;

import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.model.DriverEvent;

/**
 * A {@link DriverEvent} as the one {@link EventContracts.Event} value. Every path that writes an event
 * comes through here, so a push frame, a replayed page and a wait's reply cannot disagree on its shape.
 */
public final class EventWire {
    private EventWire() {}

    public static EventContracts.Event dto(DriverEvent e) {
        Optional<BlockPosition> pos = e.pos == null ? Optional.empty() : Optional.of(Positions.position(e.pos));
        return new EventContracts.Event(e.seq, e.timestamp, e.type, pos,
                e.data.isNull() ? Optional.empty() : Optional.of(e.data));
    }

    /** The event's value tree, checked against its contract. */
    public static Object tree(DriverEvent e) {
        return EventContracts.EVENT.encode(dto(e));
    }
}
