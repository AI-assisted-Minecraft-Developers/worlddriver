package net.magicterra.worlddriver.api;

import java.util.function.Consumer;

import net.magicterra.worlddriver.api.methods.event.EventContracts;

/**
 * Where a transport hears events as they happen: each one already in its wire form and already past
 * the {@code mutedEvents} opt-out, so a push frame needs nothing from the game to be written. The
 * host calls every listener on one dispatch thread, never the thread that emitted.
 */
public interface EventFeed {
    void addEventListener(Consumer<EventContracts.Event> listener);
    void removeEventListener(Consumer<EventContracts.Event> listener);
}
