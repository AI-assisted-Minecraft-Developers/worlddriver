package net.magicterra.worlddriver.bot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.methods.ActionContract.CancelReason;
import net.magicterra.worlddriver.api.methods.ActionContract.Cancelled;
import net.magicterra.worlddriver.api.methods.ActionContract.Failed;
import net.magicterra.worlddriver.api.methods.ActionContract.Running;
import net.magicterra.worlddriver.api.methods.ActionContract.Succeeded;
import net.magicterra.worlddriver.api.methods.ActionContract.Superseded;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import org.junit.jupiter.api.Test;

/**
 * Every way a held process can end writes the ending of the action it carries, in both executors:
 * success only from the process's own verdict, a throw as a failure, a new explicit task as
 * superseded by its id, and a scheduler suspension as no ending at all.
 */
class ActionEndingTest {

    /** Ends on tick {@code after} with {@code failure}, or throws there when {@code boom}. */
    private static final class Step implements BotProcess {
        private final int after;
        private final String failure;
        private final boolean boom;
        private int ticks;

        Step(int after, String failure, boolean boom) {
            this.after = after;
            this.failure = failure;
            this.boom = boom;
        }

        @Override public String kind() { return "builder"; }
        @Override public String failure() { return failure; }
        @Override public void attach(BotState st) { }
        @Override public boolean tick(Body a, WorldView w, BotState st) {
            if (++ticks < after) return false;
            if (boom) throw new IllegalStateException("kaboom");
            return true;
        }
    }

    private final ActionRegistry registry = new ActionRegistry();
    private final BotState st = new BotState();
    private final UserTaskChain chain = new UserTaskChain(st, () -> { });
    private final HeldProcess held = new HeldProcess(new BotState());

    private ActionHandle admit(String body) {
        return ((ActionRegistry.Admitted) registry.admit(body, ActionHandle.Scope.CLIENT, Optional.empty(), "summary")).handle();
    }

    @Test
    void aProcessThatDidItsJobSucceedsWithTheStartedSummary() {
        ActionHandle h = admit("self");
        chain.setProcess(new Step(2, null, false), h);
        chain.tick(null, null, st);
        assertInstanceOf(Running.class, h.snapshot(), "one tick in, still running");
        chain.tick(null, null, st);
        Succeeded<?> s = assertInstanceOf(Succeeded.class, h.snapshot());
        assertEquals("summary", s.result());
    }

    @Test
    void aGiveUpFailsWithTheProcessesOwnWords() {
        ActionHandle h = admit("self");
        chain.setProcess(new Step(1, "goal not reached", false), h);
        chain.tick(null, null, st);
        Failed<?> f = assertInstanceOf(Failed.class, h.snapshot());
        assertEquals("goal not reached", f.message());
    }

    @Test
    void aThrowFailsTheAction() {
        ActionHandle h = admit("self");
        chain.setProcess(new Step(1, null, true), h);
        chain.tick(null, null, st);
        Failed<?> f = assertInstanceOf(Failed.class, h.snapshot());
        assertTrue(f.message().contains("kaboom"), f.message());
    }

    @Test
    void aNewExplicitTaskSupersedesByItsId() {
        ActionHandle first = admit("self");
        ActionHandle second = admit("self");
        chain.setProcess(new Step(5, null, false), first);
        chain.setProcess(new Step(5, null, false), second);
        Superseded<?> s = assertInstanceOf(Superseded.class, first.snapshot());
        assertEquals(second.id(), s.replacementActionId());
        assertInstanceOf(Running.class, second.snapshot());
    }

    @Test
    void aSuspensionByAHigherChainIsNotAnEnding() {
        ActionHandle h = admit("self");
        chain.setProcess(new Step(3, null, false), h);
        chain.tick(null, null, st);
        chain.onInterrupt(null);
        chain.onResume();
        assertInstanceOf(Running.class, h.snapshot());
        chain.tick(null, null, st);
        chain.tick(null, null, st);
        assertInstanceOf(Succeeded.class, h.snapshot());
    }

    @Test
    void aCancelRecordsTheReasonItWasGiven() {
        ActionHandle h = admit("self");
        chain.setProcess(new Step(5, null, false), h);
        chain.cancel("mc.test.reset", a -> a.cancelled(CancelReason.TEST_RESET));
        assertEquals(CancelReason.TEST_RESET, assertInstanceOf(Cancelled.class, h.snapshot()).reason());
    }

    @Test
    void aStopForAnActionThatAlreadyLeftDoesNotTouchItsSuccessor() {
        ActionHandle first = admit("self");
        ActionHandle second = admit("self");
        chain.setProcess(new Step(5, null, false), first);
        chain.setProcess(new Step(5, null, false), second);
        assertFalse(chain.cancelAction(first, "late"));
        assertInstanceOf(Running.class, second.snapshot());
    }

    @Test
    void theFirstEndingWins() {
        ActionHandle h = admit("self");
        chain.setProcess(new Step(5, null, false), h);
        chain.cancel("player-death", a -> a.fail("the body died"));
        chain.cancel("user-cancel");
        assertInstanceOf(Failed.class, h.snapshot());
    }

    @Test
    void theServerExecutorEndsActionsTheSameWay() {
        ActionHandle ok = admit("player:a");
        held.start(new Step(1, null, false), ok);
        held.finished();
        assertInstanceOf(Succeeded.class, ok.snapshot());

        ActionHandle crashed = admit("player:a");
        held.start(new Step(1, null, false), crashed);
        held.crashed(new IllegalStateException("tick blew up"));
        assertTrue(assertInstanceOf(Failed.class, crashed.snapshot()).message().contains("tick blew up"));

        ActionHandle a = admit("player:a");
        ActionHandle b = admit("player:a");
        held.start(new Step(5, null, false), a);
        held.start(new Step(5, null, false), b);
        assertEquals(b.id(), assertInstanceOf(Superseded.class, a.snapshot()).replacementActionId());
    }
}
