package net.magicterra.worlddriver.bot.util;

import java.util.Comparator;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order the "stop at the first acceptable cell" callers depend on. */
class NearestFirstScanTest {

    /** The enumeration order buildSorted starts from, which a stable sort keeps among ties. */
    private static final Comparator<BlockPos> ENUMERATION =
            Comparator.<BlockPos>comparingInt(p -> p.getX()).thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ());

    @Test
    void coversTheBoxOnce() {
        BlockPos[] offsets = NearestFirstScan.offsetsNearestFirst(3, 2);
        assertEquals(7 * 5 * 7, offsets.length);
        assertEquals(offsets.length, java.util.Set.of(offsets).size());
    }

    @Test
    void sortsByTheDistanceCallersMeasureAndBreaksTiesInEnumerationOrder() {
        // An origin away from zero: callers compare against the bot's feet, not the offset itself.
        BlockPos origin = new BlockPos(1037, 64, -2201);
        BlockPos[] offsets = NearestFirstScan.offsetsNearestFirst(5, 3);
        for (int i = 1; i < offsets.length; i++) {
            double prev = origin.offset(offsets[i - 1]).distSqr(origin);
            double cur = origin.offset(offsets[i]).distSqr(origin);
            assertTrue(prev <= cur, "out of order at " + i + ": " + offsets[i - 1] + " then " + offsets[i]);
            if (prev == cur) {
                assertTrue(ENUMERATION.compare(offsets[i - 1], offsets[i]) < 0,
                        "tie not in enumeration order at " + i + ": " + offsets[i - 1] + " then " + offsets[i]);
            }
        }
    }
}
