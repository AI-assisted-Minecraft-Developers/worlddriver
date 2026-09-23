package net.magicterra.worlddriver.bot.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A bot idles in the cells it just dug, and its foot and head cells are exactly the ones the
 * candidate scan skips. An auto-start gated on the tracker's size therefore starts a process on
 * every idle tick that finds nothing and ends at once. The gate must ask the scan's own question.
 */
class BackfillAutoStartTest {

    /** Stone floor at y=63 and below, air above, plus whatever cells a test marks solid. */
    private static final class Flat implements BackfillProcess.Cells {
        final Set<BlockPos> solid = new HashSet<>();
        @Override public boolean isAir(BlockPos p) { return p.getY() >= 64 && !solid.contains(p); }
        @Override public boolean isSolid(BlockPos p) { return p.getY() < 64 || solid.contains(p); }
    }

    private static final int RADIUS = 6;
    private final BlockPos foot = new BlockPos(0, 64, 0);

    @Test
    void anIdleBotWhoseOnlyTrackedCellIsItsOwnFootStartsNothing() {
        BackfillTracker tracker = new BackfillTracker();
        tracker.record(foot);
        assertFalse(BackfillProcess.autoStartWanted(tracker, foot, RADIUS, new Flat()),
                "the foot cell is never a candidate, so there is nothing to start for");
    }

    @Test
    void cellsOutOfRadiusStartNothing() {
        BackfillTracker tracker = new BackfillTracker();
        tracker.record(foot);
        tracker.record(new BlockPos(RADIUS + 5, 64, 0));
        assertFalse(BackfillProcess.autoStartWanted(tracker, foot, RADIUS, new Flat()));
    }

    @Test
    void aTrackedHoleInReachStartsTheProcess() {
        BackfillTracker tracker = new BackfillTracker();
        BlockPos hole = new BlockPos(2, 64, 0);
        tracker.record(hole);
        tracker.record(foot);
        assertTrue(BackfillProcess.autoStartWanted(tracker, foot, RADIUS, new Flat()));
        assertEquals(hole, BackfillProcess.pickCandidate(tracker, foot, RADIUS, Set.of(), new Flat()),
                "the gate and the process agree on the cell");
    }

    @Test
    void aFilledCellIsDroppedFromTheTracker() {
        BackfillTracker tracker = new BackfillTracker();
        BlockPos filled = new BlockPos(2, 64, 0);
        tracker.record(filled);
        Flat cells = new Flat();
        cells.solid.add(filled);
        assertFalse(BackfillProcess.autoStartWanted(tracker, foot, RADIUS, cells));
        assertEquals(0, tracker.size());
    }
}
