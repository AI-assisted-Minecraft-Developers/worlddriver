package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.rpc.TransportLimits;
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
    private static String startQuickWait(DriverApi api) {
        Map<String, Object> ack = api.wait.condition(Map.of(
                "invoke", "mc.events", "params", Map.of("op", "list"), "background", true));
        return (String) ack.get("waitId");
    }

    private static Map<String, Object> awaitFinished(DriverApi api, String waitId) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            Map<String, Object> r = api.wait.result(Map.of("waitId", waitId, "consume", false));
            if (!Boolean.TRUE.equals(r.get("pending"))) return r;
            Thread.sleep(10);
        }
        throw new AssertionError(waitId + " never finished");
    }

    @Test
    void anIdThatWasNeverIssuedIsAnError() {
        DriverApi api = new DriverApi();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> api.wait.result(Map.of("waitId", "condition-no-such-wait")));
        assertTrue(e.getMessage().contains("unknown"), e.getMessage());
    }

    @Test
    void aStillRunningWaitIsPending() {
        DriverApi api = new DriverApi();
        Map<String, Object> ack = api.wait.condition(Map.of(
                "invoke", "mc.events", "params", Map.of("op", "list"), "field", "nope",
                "timeoutMs", 1500, "pollMs", 50, "background", true));
        Map<String, Object> r = api.wait.result(Map.of("waitId", ack.get("waitId")));
        assertEquals(Boolean.TRUE, r.get("pending"), r.toString());
    }

    @Test
    void aConsumedResultIsAnErrorTheSecondTime() throws Exception {
        DriverApi api = new DriverApi();
        String id = startQuickWait(api);
        awaitFinished(api, id);
        assertEquals(Boolean.TRUE, api.wait.result(Map.of("waitId", id)).get("satisfied"));
        assertThrows(IllegalArgumentException.class, () -> api.wait.result(Map.of("waitId", id)));
    }

    @Test
    void backgroundWaitsPastTheCapAreRefusedInsteadOfEachTakingAThread() throws Exception {
        DriverApi api = new DriverApi();
        List<String> started = new ArrayList<>();
        try {
            for (int i = 0; i < TransportLimits.WAIT_MAX_BACKGROUND; i++) {
                started.add((String) api.wait.condition(Map.of("invoke", "mc.events",
                        "params", Map.of("op", "list"), "field", "nope",
                        "timeoutMs", 1500, "pollMs", 100, "background", true)).get("waitId"));
            }
            IllegalStateException e = assertThrows(IllegalStateException.class,
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
        DriverApi api = new DriverApi();
        String first = startQuickWait(api);
        awaitFinished(api, first);
        for (int i = 0; i < 70; i++) awaitFinished(api, startQuickWait(api));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> api.wait.result(Map.of("waitId", first)));
        assertTrue(e.getMessage().contains(first), e.getMessage());
    }
}
