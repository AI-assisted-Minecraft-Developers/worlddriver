package net.magicterra.worlddriver.bot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import org.junit.jupiter.api.Test;

/**
 * A user-task process's status slot is the one its {@code attach} switched on, and it is switched
 * off at every ending, whatever the process's kind says. {@code sleep} and {@code replay} report
 * into the goto slot under kinds no slot is named after, which is the shape a lookup by kind
 * misses: cancelling them left {@code goto.active} true with nothing behind it.
 */
class UserTaskChainSlotTest {

    /** Attaches the way SleepProcess does: goto slot, under its own kind. */
    private static final class SlotBorrower implements BotProcess {
        final String kind;
        final Consumer<BotState> attach;
        int ticks;
        boolean finishOnTick;
        boolean throwOnTick;

        SlotBorrower(String kind, Consumer<BotState> attach) {
            this.kind = kind;
            this.attach = attach;
        }

        @Override public String kind() { return kind; }
        @Override public void attach(BotState st) { attach.accept(st); }
        @Override public boolean tick(Body a, WorldView w, BotState st) {
            ticks++;
            if (throwOnTick) throw new IllegalStateException("boom");
            return finishOnTick;
        }
    }

    private static SlotBorrower sleepLike() {
        return new SlotBorrower("sleep", st -> {
            st.mc_goto.active = true;
            st.mc_goto.goal = "sleep[nearest bed within 16]";
        });
    }

    private final BotState st = new BotState();
    private int keyReleases;
    private final UserTaskChain chain = new UserTaskChain(st, () -> keyReleases++);

    @Test
    void cancellingASleepMidWalkSwitchesTheGotoSlotOff() {
        chain.setProcess(sleepLike());
        chain.tick(null, null, st);
        assertTrue(st.mc_goto.active, "setup: sleep reports into the goto slot");
        chain.cancel("user-cancel");
        assertFalse(st.mc_goto.active, "status must not report goto after the sleep is gone");
        assertEquals("user-cancel", st.mc_goto.lastError);
        assertEquals(null, st.activeName());
    }

    @Test
    void supersedingAReplaySwitchesTheGotoSlotOff() {
        chain.setProcess(new SlotBorrower("replay", s -> s.mc_goto.active = true));
        chain.setProcess(new SlotBorrower("look", s -> s.look.active = true));
        assertFalse(st.mc_goto.active);
        assertEquals("superseded", st.mc_goto.lastError);
        assertTrue(st.look.active, "the new process keeps the slot it claimed");
    }

    @Test
    void aThrowingProcessLeavesNoSlotOn() {
        SlotBorrower p = sleepLike();
        p.throwOnTick = true;
        chain.setProcess(p);
        chain.tick(null, null, st);
        assertFalse(st.mc_goto.active);
        assertEquals("IllegalStateException: boom", st.mc_goto.lastError);
    }

    @Test
    void aProcessThatFinishesWithoutResettingItsSlotLeavesNoSlotOn() {
        SlotBorrower p = sleepLike();
        chain.setProcess(p);
        chain.tick(null, null, st);
        p.finishOnTick = true;
        chain.tick(null, null, st);
        assertFalse(st.mc_goto.active);
        assertEquals(null, st.mc_goto.lastError, "a natural ending adds no error of its own");
    }

    @Test
    void theEpisodeCancelOnDeathReachesTheSlotToo() {
        chain.setProcess(sleepLike());
        chain.cancelEpisode("player-death");
        assertFalse(st.mc_goto.active);
    }

    @Test
    void aSlotAnotherOwnerAlreadyHeldIsNotTheProcesssToRelease() {
        st.retreat.active = true;   // the retreat reflex's slot, live before the user task starts
        chain.setProcess(new SlotBorrower("runAway", s -> {
            s.runAway.active = true;
            s.retreat.active = true;
        }));
        chain.cancel("user-cancel");
        assertFalse(st.runAway.active);
        assertTrue(st.retreat.active, "one owner per slot: the reflex's slot is the reflex's to clear");
    }
}
