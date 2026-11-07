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

    private static final String PLAYER_A = "player:a";

    private static final String SELF = "self";

    private final ActionRegistry registry = new ActionRegistry();

    private ActionRegistry.Admission admit(String body, RunContext run) {
        return registry.admit(body, ActionHandle.Scope.SERVER, Optional.ofNullable(run), () -> "r");
    }

    private ActionHandle handle(String body, RunContext run) {
        return ((ActionRegistry.Admitted) admit(body, run)).handle();
    }

    @Test
    void idsAreDistinctAndRunningActionIsFound() {
        ActionHandle a = handle(PLAYER_A, null);
        ActionHandle b = handle(PLAYER_A, null);
        assertNotEquals(a.id(), b.id());
        assertInstanceOf(Running.class, registry.snapshot(a.id()));
    }

    @Test
    void theResultIsReadWhenTheActionSucceeds() {
        int[] done = {0};
        ActionHandle h = ((ActionRegistry.Admitted)
                        registry.admit(PLAYER_A, ActionHandle.Scope.SERVER, Optional.empty(), () -> done[0]))
                .handle();
        done[0] = 7;
        h.succeed();
        assertEquals(new Succeeded<>(h.id(), PLAYER_A, 7), h.snapshot());
    }

    @Test
    void anActionAdmittedWithoutResultTakesAnExplicitOne() {
        ActionHandle launched = ((ActionRegistry.Admitted)
                        registry.admit(SELF, ActionHandle.Scope.CLIENT, Optional.empty(), null))
                .handle();
        assertTrue(launched.succeed("preview"));
        assertEquals(new Succeeded<>(launched.id(), SELF, "preview"), launched.snapshot());

        ActionHandle bare = ((ActionRegistry.Admitted)
                        registry.admit(SELF, ActionHandle.Scope.CLIENT, Optional.empty(), null))
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
            ActionHandle h = handle(PLAYER_A, null);
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
    void bodyHeldByRunRefusesEveryoneElseUntilTheRunAndItsActionsEnd() {
        RunContext run = RunContext.start("test");
        final ActionHandle mine = handle(PLAYER_A, run);
        assertEquals(
                RefusalReason.BUSY,
                assertInstanceOf(ActionRegistry.Denied.class, admit(PLAYER_A, null))
                        .reason());
        assertEquals(
                RefusalReason.BUSY,
                assertInstanceOf(ActionRegistry.Denied.class, admit(PLAYER_A, RunContext.start("other")))
                        .reason());
        // The same run may replace its own task.
        assertInstanceOf(ActionRegistry.Admitted.class, admit(PLAYER_A, run));

        // A returned run keeps the body while one of its actions there still runs ...
        run.finish();
        mine.cancelled(CancelReason.REQUESTED);
        assertEquals(
                RefusalReason.BUSY,
                assertInstanceOf(ActionRegistry.Denied.class, admit(PLAYER_A, null))
                        .reason());
    }

    @Test
    void returnedRunReleasesTheBodyOnceItsActionsEnd() {
        RunContext run = RunContext.start("test");
        ActionHandle h = handle(PLAYER_A, run);
        run.finish();
        h.succeed();
        assertInstanceOf(ActionRegistry.Admitted.class, admit(PLAYER_A, null));
    }

    @Test
    void stoppingRunCancelsItsActionsAndAsksTheExecutorToStop() {
        RunContext run = RunContext.start("test");
        ActionHandle h = handle(PLAYER_A, run);
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
    void closingScopeEndsItsActionsAndChangesItsGeneration() {
        final long before = registry.generation(ActionHandle.Scope.CLIENT);
        ActionHandle self = ((ActionRegistry.Admitted)
                        registry.admit(SELF, ActionHandle.Scope.CLIENT, Optional.empty(), () -> "r"))
                .handle();
        ActionHandle bot = handle(PLAYER_A, null);
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
    void removingBodyEndsOnlyItsActions() {
        ActionHandle a = handle(PLAYER_A, null);
        ActionHandle b = handle("player:b", null);
        registry.cancelBot(PLAYER_A, CancelReason.BOT_REMOVED);
        assertEquals(
                CancelReason.BOT_REMOVED,
                assertInstanceOf(Cancelled.class, a.snapshot()).reason());
        assertInstanceOf(Running.class, b.snapshot());
    }
}
