package net.magicterra.worlddriver.game.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * gap#67-⑤: the block scans walked a dy-outer / dx,dz-inner loop under a flat "cells scanned" budget, so at a large
 * horizontal radius ONE dy layer blew the whole budget (r=32 -> 65x65=4225 cells a layer, a 50_000 cap -> dy in [+4,+8]
 * never scanned) and a canopy log 6 blocks up went invisible. Nearest-first means any budget cutoff drops the FARTHEST
 * cells instead of a whole height band.
 */
class NearestFirstScanBudgetTest {

    @Test
    void aNearButHighCellRanksInsideTheBudget() {
        // (a) full coverage: every offset in the box appears exactly once.
        BlockPos[] atR32 = NearestFirstScan.offsetsNearestFirst(32, 8);
        assertEquals(65 * 65 * 17, atR32.length, "gap#67(a): offset count wrong: " + atR32.length);

        // (b) the reproduction from the live bug report: (8,6,6) is a NEAR cell (distSq=136) that the
        // old dy-outer loop never reached at r=32 — it must land inside a 50_000 cap.
        int idx = indexOfOffset(atR32, 8, 6, 6);
        assertTrue(
                idx >= 0 && idx < 50_000,
                "gap#67(b): near-but-high cell (8,6,6) must rank inside a 50k budget, got index " + idx);

        // (c) nearest-first really means sorted ascending by squared distance.
        long prev = -1;
        boolean sorted = true;
        for (BlockPos p : atR32) {
            long d2 = (long) p.getX() * p.getX() + (long) p.getY() * p.getY() + (long) p.getZ() * p.getZ();
            if (d2 < prev) {
                sorted = false;
                break;
            }
            prev = d2;
        }
        assertTrue(sorted, "gap#67(c): offsets must be sorted ascending by squared distance");

        // (d) the origin itself (zero distance) is always first.
        assertEquals(BlockPos.ZERO, atR32[0], "gap#67(d): nearest offset must be the origin itself");

        // (e) goto's call site (radius 64) gets the same guarantee at the larger radius.
        BlockPos[] atR64 = NearestFirstScan.offsetsNearestFirst(64, 8);
        int idx64 = indexOfOffset(atR64, 10, 6, 0);
        assertTrue(
                idx64 >= 0 && idx64 < 50_000,
                "gap#67(e): radius-64 near-but-high cell (10,6,0) must still rank inside a 50k budget, got index "
                        + idx64);
    }

    private static int indexOfOffset(BlockPos[] arr, int x, int y, int z) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].getX() == x && arr[i].getY() == y && arr[i].getZ() == z) {
                return i;
            }
        }
        return -1;
    }
}
