package net.magicterra.worlddriver.api.game;

import java.util.Optional;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;

/**
 * A {@link DriverEvent} as the one {@link EventContracts.Event} value. Every path that writes an event comes through
 * here, so a push frame, a replayed page and a wait's reply cannot disagree on its shape.
 */
public final class EventWire {
    private EventWire() {}

    /**
     * The wire form of {@code e}: its position when it has one, its payload unless null, and {@code untrusted} for a
     * type that carries text a player or a server typed.
     */
    public static EventContracts.Event dto(DriverEvent e) {
        Optional<BlockPosition> pos = e.pos == null ? Optional.empty() : Optional.of(Positions.position(e.pos));
        EventCatalog.Entry<?> entry = EventCatalog.byType(e.type);
        return new EventContracts.Event(
                e.seq,
                e.timestamp,
                e.type,
                pos,
                e.data.isNull() ? Optional.empty() : Optional.of(e.data),
                entry != null && entry.untrusted() ? Optional.of(true) : Optional.empty());
    }
}
