package net.magicterra.worlddriver.bot.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.VanillaBoot;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A run starts from a slot that has forgotten the last run's verdict. */
class ProcessSlotRunTest {

    @BeforeAll
    static void boot() {
        VanillaBoot.boot();
    }

    /** Every reading a finished run leaves on a slot, the ones {@code reset()} keeps included. */
    private static void stageFinishedRun(BotState.ProcessSlot s) {
        s.goalReached = true;
        s.endReason = "quota (broke 1/1)";
        s.finalDist = 2.5;
        s.lastError = "an old error";
        s.firstPlan = "an old plan";
        s.parkourTakeoff = "an old takeoff";
        s.pathLen = 7;
        s.pathStep = 3;
    }

    @Test
    void beginRunForgetsEveryTerminalReading() {
        BotState.ProcessSlot s = new BotState().mine;
        stageFinishedRun(s);
        s.beginRun("g", null);
        Map<String, Object> snap = s.snapshot();
        assertTrue(s.active);
        assertEquals("g", snap.get("goal"));
        for (String key : List.of("goalReached", "endReason", "finalDist", "lastError", "target")) {
            assertFalse(snap.containsKey(key), key + " survived beginRun: " + snap);
        }
        assertNull(s.firstPlan);
        assertNull(s.parkourTakeoff);
        assertEquals(0, s.pathLen);
        assertEquals(0, s.pathStep);
        assertTrue(s.startedAtMs > 0);
    }

    @Test
    void aSecondMineDoesNotShowTheFirstMinesVerdict() {
        BotState st = new BotState();
        stageFinishedRun(st.mine);
        new MineProcess(List.of("minecraft:stone"), 1, 8).attach(st);
        assertNull(st.mine.goalReached);
        assertNull(st.mine.endReason);
        assertEquals(-1, st.mine.finalDist);
    }

    @Test
    void aReplayDoesNotShowThePreviousGotosVerdict() {
        BotState st = new BotState();
        stageFinishedRun(st.mc_goto);
        new ReplayProcess(List.of(BlockPos.ZERO), List.of(), new Goal.Block(BlockPos.ZERO), BlockPos.ZERO).attach(st);
        assertNull(st.mc_goto.goalReached);
        assertNull(st.mc_goto.endReason);
        assertEquals(-1, st.mc_goto.finalDist);
        assertEquals(BlockPos.ZERO, st.mc_goto.target);
    }
}
