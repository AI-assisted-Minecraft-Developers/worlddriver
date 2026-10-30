package net.magicterra.worlddriver.api.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.magicterra.worlddriver.api.call.RunContext;
import net.magicterra.worlddriver.api.methods.shared.Responses.CancelReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Cancelled;
import net.magicterra.worlddriver.api.methods.shared.Responses.Failed;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Running;
import net.magicterra.worlddriver.api.methods.shared.Responses.Succeeded;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.Test;

class ActionRegistryTest {

    private final ActionRegistry registry = new ActionRegistry();

    private ActionRegistry.Admission admit(String body, RunContext run) {
        return registry.admit(body, ActionHandle.Scope.SERVER, Optional.ofNullable(run), () -> "r");
    }

    private ActionHandle handle(String body, RunContext run) {
        return ((ActionRegistry.Admitted) admit(body, run)).handle();
    }

    @Test
    void idsAreDistinctAndARunningActionIsFound() {
        ActionHandle a = handle("player:a", null);
        ActionHandle b = handle("player:a", null);
        assertNotEquals(a.id(), b.id());
        assertInstanceOf(Running.class, registry.snapshot(a.id()));
    }

    @Test
    void theResultIsReadWhenTheActionSucceeds() {
        int[] done = {0};
        ActionHandle h = ((ActionRegistry.Admitted)
                        registry.admit("player:a", ActionHandle.Scope.SERVER, Optional.empty(), () -> done[0]))
                .handle();
        done[0] = 7;
        h.succeed();
        assertEquals(new Succeeded<>(h.id(), "player:a", 7), h.snapshot());
    }

    @Test
    void anActionAdmittedWithoutAResultTakesAnExplicitOne() {
        ActionHandle launched = ((ActionRegistry.Admitted)
                        registry.admit("self", ActionHandle.Scope.CLIENT, Optional.empty(), null))
                .handle();
        assertTrue(launched.succeed("preview"));
        assertEquals(new Succeeded<>(launched.id(), "self", "preview"), launched.snapshot());

        ActionHandle bare = ((ActionRegistry.Admitted)
                        registry.admit("self", ActionHandle.Scope.CLIENT, Optional.empty(), null))
                .handle();
        bare.succeed();
        assertInstanceOf(Failed.class, bare.snapshot());
    }

    @Test
    void anUnknownIdIsActionNotFound() {
        ApiException e = assertThrows(ApiException.class, () -> registry.snapshot("nope"));
        assertEquals(ApiError.Code.ACTION_NOT_FOUND, e.error().code());
    }

    @Test
    void endedActionsAreKeptOnlyUpToTheCap() {
        List<ActionHandle> ended = new ArrayList<>();
        for (int i = 0; i < ActionRegistry.KEEP_ENDED + 5; i++) {
            ActionHandle h = handle("player:a", null);
            h.cancelled(CancelReason.REQUESTED);
            ended.add(h);
        }
        assertThrows(ApiException.class, () -> registry.snapshot(ended.get(0).id()));
        assertInstanceOf(
                Cancelled.class, registry.snapshot(ended.get(ended.size() - 1).id()));
    }

    @Test
    void runningActionsAreNeverEvictedButAdmissionIsCapped() {
        for (int i = 0; i < ActionRegistry.MAX_ACTIVE; i++) {
            handle("player:b" + i, null);
        }
        ActionRegistry.Denied d = assertInstanceOf(ActionRegistry.Denied.class, admit("player:z", null));
        assertEquals(RefusalReason.ACTION_LIMIT, d.reason());
    }

    @Test
    void aBodyHeldByARunRefusesEveryoneElseUntilTheRunAndItsActionsEnd() {
        RunContext run = RunContext.start("test");
        ActionHandle mine = handle("player:a", run);
        assertEquals(
                RefusalReason.BUSY,
                assertInstanceOf(ActionRegistry.Denied.class, admit("player:a", null))
                        .reason());
        assertEquals(
                RefusalReason.BUSY,
                assertInstanceOf(ActionRegistry.Denied.class, admit("player:a", RunContext.start("other")))
                        .reason());
        // The same run may replace its own task.
        assertInstanceOf(ActionRegistry.Admitted.class, admit("player:a", run));

        // A returned run keeps the body while one of its actions there still runs ...
        run.finish();
        mine.cancelled(CancelReason.REQUESTED);
        assertEquals(
                RefusalReason.BUSY,
                assertInstanceOf(ActionRegistry.Denied.class, admit("player:a", null))
                        .reason());
    }

    @Test
    void aReturnedRunReleasesTheBodyOnceItsActionsEnd() {
        RunContext run = RunContext.start("test");
        ActionHandle h = handle("player:a", run);
        run.finish();
        h.succeed();
        assertInstanceOf(ActionRegistry.Admitted.class, admit("player:a", null));
    }

    @Test
    void stoppingARunCancelsItsActionsAndAsksTheExecutorToStop() {
        RunContext run = RunContext.start("test");
        ActionHandle h = handle("player:a", run);
        AtomicReference<CancelReason> stopped = new AtomicReference<>();
        h.bindStopper(stopped::set);
        run.stop();
        assertEquals(
                CancelReason.REQUESTED,
                assertInstanceOf(Cancelled.class, h.snapshot()).reason());
        assertEquals(CancelReason.REQUESTED, stopped.get());
        ApiException e = assertThrows(ApiException.class, () -> admit("player:b", run));
        assertEquals(ApiError.Code.CAPABILITY_UNAVAILABLE, e.error().code());
    }

    @Test
    void closingAScopeEndsItsActionsAndChangesItsGeneration() {
        long before = registry.generation(ActionHandle.Scope.CLIENT);
        ActionHandle self = ((ActionRegistry.Admitted)
                        registry.admit("self", ActionHandle.Scope.CLIENT, Optional.empty(), () -> "r"))
                .handle();
        ActionHandle bot = handle("player:a", null);
        registry.cancelScope(ActionHandle.Scope.CLIENT, CancelReason.CONNECTION_CLOSED);
        assertEquals(
                CancelReason.CONNECTION_CLOSED,
                assertInstanceOf(Cancelled.class, self.snapshot()).reason());
        assertInstanceOf(Running.class, bot.snapshot());
        assertTrue(registry.generation(ActionHandle.Scope.CLIENT) != before);
        ApiException e =
                assertThrows(ApiException.class, () -> registry.requireGeneration(ActionHandle.Scope.CLIENT, before));
        assertEquals(ApiError.Code.CONTEXT_CHANGED, e.error().code());
    }

    @Test
    void removingABodyEndsOnlyItsActions() {
        ActionHandle a = handle("player:a", null);
        ActionHandle b = handle("player:b", null);
        registry.cancelBot("player:a", CancelReason.BOT_REMOVED);
        assertEquals(
                CancelReason.BOT_REMOVED,
                assertInstanceOf(Cancelled.class, a.snapshot()).reason());
        assertInstanceOf(Running.class, b.snapshot());
    }
}
