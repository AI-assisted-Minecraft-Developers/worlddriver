package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.magicterra.worlddriver.TestJson;
import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Background waits: {@code mc.wait.result} tells a running wait from one that does not exist, and how a wait ended
 * reaches both that read and the {@code wait.done} event.
 *
 * <p>Any id missing from the finished-results map read as {@code pending:true}, so a typo, a result already consumed,
 * or one evicted by newer results left an agent polling forever.
 */
@Timeout(60)
class WaitResultTest {

    private static DriverApplication app() {
        return ProbeMethods.install(new DriverApplication());
    }

    private static Map<?, ?> call(DriverApplication api, String method, Map<String, Object> params) {
        return (Map<?, ?>) TestJson.route(api.registry(), method, params);
    }

    /** A background wait that is satisfied on its first poll; needs no world. */
    private static String startQuickWait(DriverApplication api) {
        Map<?, ?> ack =
                call(api, "mc.wait.condition", Map.of("invoke", "probe.echo", "field", "value", "background", true));
        assertEquals("started", ack.get("outcome"), ack.toString());
        return (String) ack.get("waitId");
    }

    /** A background wait that is never satisfied: probe.echo has no field "nope". */
    private static String startSlowWait(DriverApplication api, int timeoutMs) {
        return (String) call(
                        api,
                        "mc.wait.condition",
                        Map.of(
                                "invoke",
                                "probe.echo",
                                "field",
                                "nope",
                                "timeoutMs",
                                timeoutMs,
                                "pollMs",
                                100,
                                "background",
                                true))
                .get("waitId");
    }

    private static Map<?, ?> result(DriverApplication api, Object waitId, boolean consume) {
        return call(api, "mc.wait.result", Map.of("kind", "wait", "waitId", waitId, "consume", consume));
    }

    private static Map<?, ?> awaitFinished(DriverApplication api, String waitId) throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            Map<?, ?> r = result(api, waitId, false);
            if (!Boolean.TRUE.equals(r.get("pending"))) return r;
            Thread.sleep(10);
        }
        throw new AssertionError(waitId + " never finished");
    }

    @Test
    void anIdThatWasNeverIssuedIsAnError() {
        DriverApplication api = app();
        ApiException e = assertThrows(ApiException.class, () -> result(api, "condition-no-such-wait", true));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.error().code());
        assertTrue(e.getMessage().contains("unknown"), e.getMessage());
    }

    @Test
    void aStillRunningWaitIsPending() {
        DriverApplication api = app();
        Map<?, ?> r = result(api, startSlowWait(api, 1500), true);
        assertEquals("wait", r.get("kind"), r.toString());
        assertEquals(Boolean.TRUE, r.get("pending"), r.toString());
    }

    @Test
    void theResultIsTheWaitsDoneReplyAndTheSameReachesTheEvent() throws Exception {
        DriverApplication api = app();
        CompletableFuture<EventContracts.Event> done = new CompletableFuture<>();
        api.addEventListener(e -> {
            if ("wait.done".equals(e.type())) done.complete(e);
        });
        String id = startQuickWait(api);
        awaitFinished(api, id);
        Map<?, ?> r = result(api, id, true);
        Map<?, ?> reply = (Map<?, ?>) r.get("result");
        assertEquals("done", reply.get("outcome"), r.toString());
        assertEquals(Boolean.TRUE, reply.get("satisfied"), r.toString());
        assertFalse(r.containsKey("error"), r.toString());

        Map<?, ?> data = (Map<?, ?>) TestJson.plain(
                done.get(10, TimeUnit.SECONDS).data().orElseThrow().json());
        assertEquals(id, data.get("waitId"));
        assertEquals("mc.wait.condition", data.get("method"));
        assertEquals(reply, data.get("result"));
        assertThrows(ApiException.class, () -> result(api, id, true), "a consumed result is gone");
    }

    @Test
    void aWaitThatCannotRunIsRefusedRatherThanAcknowledged() {
        // mc.wait.event needs a server. Without one its first poll would fail in the background; it is
        // refused before the wait starts instead, so no waitId is handed out for a wait that cannot run.
        DriverApplication api = app();
        ApiException e = assertThrows(
                ApiException.class, () -> call(api, "mc.wait.event", Map.of("cursor", "0", "background", true)));
        assertEquals(ApiError.Code.CAPABILITY_UNAVAILABLE, e.error().code());
    }

    @Test
    void backgroundWaitsPastTheCapAreRefused() throws Exception {
        DriverApplication api = app();
        List<String> started = new ArrayList<>();
        try {
            for (int i = 0; i < WaitService.MAX_BACKGROUND; i++) started.add(startSlowWait(api, 1500));
            ApiException e = assertThrows(ApiException.class, () -> startSlowWait(api, 1500));
            assertEquals(ApiError.Code.SERVER_BUSY, e.error().code());
        } finally {
            // The quota is shared by the whole JVM; leave it empty for whatever runs next.
            for (String id : started) awaitFinished(api, id);
        }
    }

    @Test
    void anEvictedResultIsAnErrorNotPendingForever() throws Exception {
        DriverApplication api = app();
        String first = startQuickWait(api);
        awaitFinished(api, first);
        for (int i = 0; i < 70; i++) awaitFinished(api, startQuickWait(api));
        ApiException e = assertThrows(ApiException.class, () -> result(api, first, true));
        assertTrue(e.getMessage().contains(first), e.getMessage());
    }

    @Test
    void anUnknownActionIsActionNotFound() {
        DriverApplication api = app();
        ApiException e = assertThrows(
                ApiException.class,
                () -> TestJson.route(api.registry(), "mc.wait.result", Map.of("kind", "action", "actionId", "nope")));
        assertEquals(ApiError.Code.ACTION_NOT_FOUND, e.error().code());
    }
}
