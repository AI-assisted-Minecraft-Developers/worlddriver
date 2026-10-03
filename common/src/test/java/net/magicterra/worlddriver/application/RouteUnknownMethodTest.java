package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.UnknownMethodException;

import net.magicterra.worlddriver.application.DriverApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link DriverApplication#route} with no method name. The route table is a {@code ConcurrentHashMap},
 * which throws a bare NullPointerException for a null key, so the failure has to be decided
 * before the lookup or every transport reports it as an internal fault with a JVM message.
 */
class RouteUnknownMethodTest {

    @Test
    void aNullMethodIsAnUnknownMethodNotAnNpe() {
        Throwable t = assertThrows(Throwable.class, () -> new DriverApplication().route(null, Map.of()));
        UnknownMethodException u = assertInstanceOf(UnknownMethodException.class, t,
                "route(null) must fail with the typed error, got " + t);
        assertNull(u.method());
        assertTrue(u.getMessage().contains("no method"), u.getMessage());
    }

    @Test
    void anUnregisteredNameNamesItself() {
        UnknownMethodException u = assertThrows(UnknownMethodException.class,
                () -> new DriverApplication().route("mc.nope", Map.of()));
        assertEquals("mc.nope", u.method());
        assertTrue(u.getMessage().startsWith("unknown method: mc.nope"), u.getMessage());
    }
}
