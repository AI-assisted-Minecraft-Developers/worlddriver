package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ApiException e = assertThrows(ApiException.class, () -> new DriverApplication().route(null, Map.of()),
                "route(null) must fail with the typed error");
        assertEquals(ApiError.Code.METHOD_NOT_FOUND, e.error().code());
        assertTrue(e.getMessage().contains("no method"), e.getMessage());
    }

    @Test
    void anUnregisteredNameNamesItself() {
        ApiException e = assertThrows(ApiException.class,
                () -> new DriverApplication().route("mc.nope", Map.of()));
        assertEquals(ApiError.Code.METHOD_NOT_FOUND, e.error().code());
        assertTrue(e.getMessage().startsWith("unknown method: mc.nope"), e.getMessage());
    }
}
