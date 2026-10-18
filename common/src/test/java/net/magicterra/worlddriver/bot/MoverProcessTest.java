package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.api.game.Mover;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A goto handed to an entity's own mover: each cell in turn, its answers ending the process. */
class MoverProcessTest {

    /** Answers MOVING {@code ticks} times per target, then {@code end}; records what it was asked. */
    private static final class Scripted implements Mover {
        final List<String> asked = new ArrayList<>();
        final int ticks;
        final Progress end;
        int left;
        boolean stopped;

        Scripted(int ticks, Progress end) { this.ticks = ticks; this.end = end; this.left = ticks; }

        @Override public Progress drive(BlockPos target, double within) {
            asked.add(target.toShortString() + "~" + within);
            if (left-- > 0) return Progress.MOVING;
            left = ticks;
            return end;
        }

        @Override public void stop() { stopped = true; }
        @Override public String failure() { return "blocked"; }
    }

    private static int run(MoverProcess p, BotState st) {
        p.attach(st);
        for (int t = 1; t <= 100; t++) if (p.tick(null, null, st)) return t;
        return -1;
    }

    @Test
    void visitsEveryCellInOrderThenSucceeds() {
        Scripted mover = new Scripted(1, Mover.Progress.ARRIVED);
        BotState st = new BotState();
        MoverProcess p = new MoverProcess(mover, List.of(new Goal.Near(new BlockPos(1, 2, 3), 1), new Goal.Block(new BlockPos(4, 5, 6))));
        assertEquals(4, run(p, st));
        assertEquals(List.of("1, 2, 3~1.0", "1, 2, 3~1.0", "4, 5, 6~0.5", "4, 5, 6~0.5"), mover.asked);
        assertNull(p.failure());
        assertFalse(st.mc_goto.active);
    }

    @Test
    void aFailureEndsItWithTheMoversReason() {
        Scripted mover = new Scripted(0, Mover.Progress.FAILED);
        BotState st = new BotState();
        MoverProcess p = new MoverProcess(mover, List.of(new Goal.Block(new BlockPos(0, 0, 0))));
        assertEquals(1, run(p, st));
        assertEquals("blocked", p.failure());
    }

    @Test
    void aCancelStopsTheMover() {
        Scripted mover = new Scripted(50, Mover.Progress.ARRIVED);
        MoverProcess p = new MoverProcess(mover, List.of(new Goal.Block(new BlockPos(0, 0, 0))));
        p.onCancelled("user-cancel");
        assertTrue(mover.stopped);
    }

    @Test
    void onlyTargetsWithACellAreCovered() {
        assertTrue(MoverProcess.covers(List.of(new Goal.Block(BlockPos.ZERO), new Goal.Near(BlockPos.ZERO, 2))));
        assertFalse(MoverProcess.covers(List.of(new Goal.Block(BlockPos.ZERO), new Goal.XZ(0, 0))));
    }
}
