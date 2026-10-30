package net.magicterra.worlddriver.api.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import org.junit.jupiter.api.Test;

/** Code that loads before the driver gets the registry when it comes up, in order; code after it, at once. */
class WorldDriverTest {

    @Test
    void whatWaitedRunsWhenTheDriverIsUpAndWhatComesLaterRunsAtOnce() {
        List<String> ran = new ArrayList<>();
        ApiRegistry registry = new ApiRegistry();
        WorldDriver.whenReady(r -> ran.add("first " + (r == registry)));
        WorldDriver.whenReady(r -> ran.add("second " + (r == registry)));
        assertEquals(List.of(), ran);
        WorldDriver.up(registry, null);
        assertEquals(List.of("first true", "second true"), ran);
        WorldDriver.whenReady(r -> ran.add("later " + (r == registry)));
        assertEquals(List.of("first true", "second true", "later true"), ran);
        assertSame(registry, WorldDriver.registry());
    }
}
