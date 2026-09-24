package net.magicterra.worlddriver.bot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import org.junit.jupiter.api.Test;

/**
 * {@code lastProcessEnd.error} comes from the process's own verdict, not from its slot: a builder
 * writes its success summary into {@code lastError}, and a goto that gave up writes nothing there.
 */
class UserTaskChainEndTest {

    /** Ends on its first tick, writing {@code slotText} into the builder slot and reporting {@code failure}. */
    private static final class Ender implements BotProcess {
        final String slotText;
        final String failure;

        Ender(String slotText, String failure) {
            this.slotText = slotText;
            this.failure = failure;
        }

        @Override public String kind() { return "builder"; }
        @Override public String failure() { return failure; }
        @Override public void attach(BotState st) { st.builder.active = true; }
        @Override public boolean tick(Body a, WorldView w, BotState st) {
            st.builder.lastError = slotText;
            st.builder.reset();
            return true;
        }
    }

    private final BotState st = new BotState();
    private final UserTaskChain chain = new UserTaskChain(st, () -> { });

    private Map<String, Object> endOf(BotProcess p) {
        chain.setProcess(p);
        chain.tick(null, null, st);
        return chain.lastEnd();
    }

    @Test
    void aSuccessSummaryInTheSlotIsNotAnError() {
        Map<String, Object> end = endOf(new Ender("done (placed=4, skipped=0)", null));
        assertEquals("builder", end.get("kind"));
        assertNull(end.get("error"));
        assertEquals("done (placed=4, skipped=0)", st.builder.lastError, "the slot keeps its summary");
    }

    @Test
    void aGiveUpIsReportedEvenWhenTheSlotSaysNothing() {
        Map<String, Object> end = endOf(new Ender(null, "goal not reached (churn-giveup, 6.0 blocks short)"));
        assertEquals("goal not reached (churn-giveup, 6.0 blocks short)", end.get("error"));
    }

    @Test
    void aCancelStillReportsItsReason() {
        chain.setProcess(new Ender(null, null));
        chain.cancel("user-cancel");
        assertEquals("user-cancel", chain.lastEnd().get("error"));
    }

    @Test
    void anAmbientProcessLeavesTheCallersEndingInPlace() {
        endOf(new Ender(null, "goal not reached (churn-giveup, about 6.0 blocks short)"));
        chain.setAmbientProcess(new Ender("backfill done", "incomplete: 2 cells could not be filled"));
        chain.tick(null, null, st);
        assertEquals("goal not reached (churn-giveup, about 6.0 blocks short)", chain.lastEnd().get("error"));
    }

    @Test
    void supersedingAnAmbientProcessRecordsNothing() {
        chain.setAmbientProcess(new Ender(null, null));
        chain.setProcess(new Ender(null, null));
        assertNull(chain.lastEnd(), "the auto-backfill's cancel is not the caller's ending");
    }
}
