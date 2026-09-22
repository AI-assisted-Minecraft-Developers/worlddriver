package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import net.magicterra.worlddriver.model.Params;
import org.junit.jupiter.api.Test;

/**
 * {@code mc.bot.farm} rescans its whole box for a mature crop after every harvest, on the tick
 * thread. The cap therefore has to be on the cells scanned, Y included: a cap on the XZ area alone
 * accepted a 64x64 field 385 blocks tall, and a Y span of two billion.
 */
class VerbOrdersFarmTest {

    private static Params box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return Params.of(Map.of(
                "from", Map.of("x", x0, "y", y0, "z", z0),
                "to", Map.of("x", x1, "y", y1, "z", z1)));
    }

    @Test
    void aFieldAsTallAsTheWorldIsRefusedAsABadArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VerbOrders.farm(box(0, -64, 0, 63, 320, 63), null));
        assertTrue(e.getMessage().contains("4096"), e.getMessage());
    }

    @Test
    void anAbsurdYSpanIsRefusedWithoutOverflowing() {
        assertThrows(IllegalArgumentException.class,
                () -> VerbOrders.farm(box(0, -1_000_000_000, 0, 0, 1_000_000_000, 0), null));
    }

    @Test
    void anOversizedFlatFieldIsRefusedTheSameWay() {
        assertThrows(IllegalArgumentException.class, () -> VerbOrders.farm(box(0, 64, 0, 64, 64, 63), null));
    }

    @Test
    void aFlatFieldAtTheCapIsAccepted() {
        VerbOrders.Order o = VerbOrders.farm(box(0, 64, 0, 63, 64, 63), null);
        assertFalse(o.refused(), String.valueOf(o.reply()));
        assertEquals(4096, o.reply().get("area"));
        assertEquals(4096, o.reply().get("volume"));
    }

    @Test
    void aYOutsideTheWorldIsRefusedAsABadArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VerbOrders.farm(box(0, 400, 0, 3, 400, 3), -64, 319));
        assertTrue(e.getMessage().contains("[-64,319]"), e.getMessage());
        assertFalse(VerbOrders.farm(box(0, -64, 0, 3, -64, 3), -64, 319).refused(), "the bottom layer is in the world");
    }
}
