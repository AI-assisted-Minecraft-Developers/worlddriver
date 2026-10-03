package net.magicterra.worlddriver.application;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@link DriverApplication} offers in the shipped jar, where no test fixture has a caller. */
class ProductionApiTest {
    /** The seeder clears blocks and discards every non-player entity around the test origin. */
    @Test
    void theTestArenaSeederIsNotInTheProductionApi() {
        assertEquals(0, Arrays.stream(DriverApplication.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(n -> n.equals("seedTestArea") || n.equals("arenaEntityTicking"))
                .count());
    }
}
