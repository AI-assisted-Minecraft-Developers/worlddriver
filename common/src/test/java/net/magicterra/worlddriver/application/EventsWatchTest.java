package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.application.DriverApplication;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A watcher on a route that does not exist is refused when it is registered.
 *
 * <p>The poll swallows every failure (a route that needs a world must survive a detach), so
 * a misspelled route acked {@code watching:true}, failed every tick in silence, and the
 * alert it was meant to raise never fired.
 */
class EventsWatchTest {

    @Test
    void watchingAnUnknownRouteIsAnErrorAtWatchTime() {
        DriverApplication api = new DriverApplication();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> api.route("mc.events", Map.of("op", "watch", "invoke", "mc.observe.playr",
                        "field", "health", "below", 6)));
        assertTrue(e.getMessage().contains("mc.observe.playr"), e.getMessage());
        assertEquals(0, ((Map<?, ?>) api.route("mc.events", Map.of("op", "list"))).get("count"),
                "a refused watcher must not have been registered");
    }

    @Test
    void watchingAKnownRouteStillRegisters() {
        DriverApplication api = new DriverApplication();
        Map<?, ?> ack = (Map<?, ?>) api.route("mc.events", Map.of("op", "watch", "invoke", "mc.events",
                "params", Map.of("op", "list"), "field", "count", "above", 5));
        assertEquals(Boolean.TRUE, ack.get("watching"));
        Map<?, ?> off = (Map<?, ?>) api.route("mc.events", Map.of("op", "unwatch", "id", ack.get("id")));
        assertEquals(Boolean.TRUE, off.get("removed"));
    }
}
