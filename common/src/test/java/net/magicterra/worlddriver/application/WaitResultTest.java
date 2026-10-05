package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.protocol.ServerBusyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mc.wait.result} tells a running wait from one that does not exist.
 *
 * <p>Any id missing from the finished-results map read as {@code pending:true}, so a typo, a
 * result already consumed, or one evicted by newer results left an agent polling forever.
 */
@Timeout(60)
class WaitResultTest {

    /** A background wait that is satisfied on its first poll; needs no world. */
    private static String startQuickWait(DriverApplication api) {
        Map<String, Object> ack = api.wait.condition(Map.of(
                "invoke", "mc.events", "params", Map.of("op", "list"), "background", true));
        return (String) ack.get("waitId");
    }

    private static Map<?, ?> result(DriverApplication api, Object waitId, boolean consume) {
        return (Map<?, ?>) api.route("mc.wait.result", Map.of("kind", "wait", "waitId", waitId, "consume", consume));
    }

    private static Map<?, ?> awaitFinished(DriverApplication api, String waitId) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            Map<?, ?> r = result(api, waitId, false);
            if (!Boolean.TRUE.equals(r.get("pending"))) return r;
            Thread.sleep(10);
        }
        throw new AssertionError(waitId + " never finished");
    }

    @Test
    void anIdThatWasNeverIssuedIsAnError() {
        DriverApplication api = new DriverApplication();
        ApiException e = assertThrows(ApiException.class, () -> result(api, "condition-no-such-wait", true));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.error().code());
        assertTrue(e.getMessage().contains("unknown"), e.getMessage());
    }

    @Test
    void aStillRunningWaitIsPending() {
        DriverApplication api = new DriverApplication();
        Map<String, Object> ack = api.wait.condition(Map.of(
                "invoke", "mc.events", "params", Map.of("op", "list"), "field", "nope",
                "timeoutMs", 1500, "pollMs", 50, "background", true));
        Map<?, ?> r = result(api, ack.get("waitId"), true);
        assertEquals("wait", r.get("kind"), r.toString());
        assertEquals(Boolean.TRUE, r.get("pending"), r.toString());
    }

    @Test
    void aConsumedResultIsAnErrorTheSecondTime() throws Exception {
        DriverApplication api = new DriverApplication();
        String id = startQuickWait(api);
        awaitFinished(api, id);
        Map<?, ?> r = result(api, id, true);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) r.get("result")).get("satisfied"), r.toString());
        assertThrows(ApiException.class, () -> result(api, id, true));
    }

    @Test
    void backgroundWaitsPastTheCapAreRefusedInsteadOfEachTakingAThread() throws Exception {
        DriverApplication api = new DriverApplication();
        List<String> started = new ArrayList<>();
        try {
            for (int i = 0; i < WaitApi.MAX_BACKGROUND; i++) {
                started.add((String) api.wait.condition(Map.of("invoke", "mc.events",
                        "params", Map.of("op", "list"), "field", "nope",
                        "timeoutMs", 1500, "pollMs", 100, "background", true)).get("waitId"));
            }
            ServerBusyException e = assertThrows(ServerBusyException.class,
                    () -> api.wait.condition(Map.of("invoke", "mc.events", "params", Map.of("op", "list"),
                            "background", true)));
            assertTrue(e.getMessage().contains("busy"), e.getMessage());
        } finally {
            // The pool is shared by the whole JVM; leave it empty for whatever runs next.
            for (String id : started) awaitFinished(api, id);
        }
    }

    @Test
    void anEvictedResultIsAnErrorNotPendingForever() throws Exception {
        DriverApplication api = new DriverApplication();
        String first = startQuickWait(api);
        awaitFinished(api, first);
        for (int i = 0; i < 70; i++) awaitFinished(api, startQuickWait(api));
        ApiException e = assertThrows(ApiException.class, () -> result(api, first, true));
        assertTrue(e.getMessage().contains(first), e.getMessage());
    }

    @Test
    void anUnknownActionIsActionNotFound() {
        DriverApplication api = new DriverApplication();
        ApiException e = assertThrows(ApiException.class,
                () -> api.route("mc.wait.result", Map.of("kind", "action", "actionId", "nope")));
        assertEquals(ApiError.Code.ACTION_NOT_FOUND, e.error().code());
    }
}
