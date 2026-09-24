package net.magicterra.worlddriver.bot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import org.junit.jupiter.api.Test;

/**
 * The server-side drivers hold their process through {@link HeldProcess}, and every ending has to
 * switch off the slots that process switched on. {@code sleep} and {@code replay} report into the
 * goto slot under kinds no slot is named after, so a lookup by kind leaves {@code goto.active} true
 * after they are cancelled, superseded or finished.
 */
class HeldProcessTest {

    private static final class SlotBorrower implements BotProcess {
        final String kind;
        final Consumer<BotState> attach;
        final List<String> cancels = new ArrayList<>();

        SlotBorrower(String kind, Consumer<BotState> attach) {
            this.kind = kind;
            this.attach = attach;
        }

        @Override public String kind() { return kind; }
        @Override public String failure() { return null; }
        @Override public void attach(BotState st) { attach.accept(st); }
        @Override public boolean tick(Body a, WorldView w, BotState st) { return false; }
        @Override public void onCancelled(String reason) { cancels.add(reason); }
    }

    private static SlotBorrower sleepLike() {
        return new SlotBorrower("sleep", st -> st.mc_goto.active = true);
    }

    private final BotState st = new BotState();
    private final HeldProcess held = new HeldProcess(st);

    /** Ends when ticked, reporting {@code failure} as its verdict. */
    private static final class GiveUp implements BotProcess {
        final String failure;
        GiveUp(String failure) { this.failure = failure; }
        @Override public String kind() { return "goto"; }
        @Override public String failure() { return failure; }
        @Override public void attach(BotState st) { st.mc_goto.active = true; }
        @Override public boolean tick(Body a, WorldView w, BotState st) { return true; }
    }

    @Test
    void aFinishedProcessLeavesItsOwnVerdictAsTheLastEnding() {
        held.start(new GiveUp("goal not reached (churn-giveup, about 10.0 blocks short)"));
        held.finished();
        assertEquals("goto", held.lastEnd().get("kind"));
        assertEquals("goal not reached (churn-giveup, about 10.0 blocks short)", held.lastEnd().get("error"));
    }

    @Test
    void aCancelLeavesItsReasonAsTheLastEnding() {
        held.start(new GiveUp(null));
        held.cancel("user-cancel");
        assertEquals("user-cancel", held.lastEnd().get("error"));
    }

    @Test
    void cancellingASleepSwitchesTheGotoSlotOff() {
        SlotBorrower sleep = sleepLike();
        held.start(sleep);
        assertTrue(st.mc_goto.active, "setup: sleep reports into the goto slot");
        assertSame(sleep, held.cancel("user-cancel"));
        assertFalse(st.mc_goto.active, "a cancelled sleep must not leave goto reported as running");
        assertEquals("user-cancel", st.mc_goto.lastError);
        assertEquals(List.of("user-cancel"), sleep.cancels);
        assertNull(held.process());
    }

    @Test
    void supersedingAReplaySwitchesItsSlotOffAndTheNewProcessKeepsItsOwn() {
        SlotBorrower replay = new SlotBorrower("replay", s -> s.mc_goto.active = true);
        held.start(replay);
        held.start(new SlotBorrower("look", s -> s.look.active = true));
        assertFalse(st.mc_goto.active);
        assertEquals("superseded", st.mc_goto.lastError);
        assertEquals(List.of("superseded"), replay.cancels);
        assertTrue(st.look.active);
    }

    @Test
    void aSleepSupersededByAnotherGotoUserLeavesTheNewClaimIntact() {
        held.start(sleepLike());
        SlotBorrower walk = new SlotBorrower("goto", s -> s.mc_goto.active = true);
        held.start(walk);
        assertTrue(st.mc_goto.active, "the new process switched the slot on again");
        held.cancel("user-cancel");
        assertFalse(st.mc_goto.active, "and its own cancel switches it off");
    }

    @Test
    void aFinishedProcessLeavesNoSlotOn() {
        held.start(sleepLike());
        held.finished();
        assertFalse(st.mc_goto.active);
        assertNull(st.mc_goto.lastError, "a natural ending adds no error of its own");
        assertNull(held.process());
    }

    @Test
    void aSlotAnotherOwnerHeldIsNotReleased() {
        st.retreat.active = true;
        held.start(new SlotBorrower("runAway", s -> { s.runAway.active = true; s.retreat.active = true; }));
        held.cancel("user-cancel");
        assertFalse(st.runAway.active);
        assertTrue(st.retreat.active, "one owner per slot");
    }

    @Test
    void cancellingWithNothingHeldIsANoOp() {
        assertNull(held.cancel("user-cancel"));
    }
}
