package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.TestJson;
import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.methods.event.EventContracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A watcher is checked when it is registered, because its polls swallow failures: a misspelled or
 * unsuitable target would otherwise ack, fail every poll in silence and never fire.
 */
@Timeout(30)
class EventsWatchTest {

    private static DriverApplication app() {
        return ProbeMethods.install(new DriverApplication());
    }

    private static Map<?, ?> events(DriverApplication api, Map<String, Object> params) {
        return (Map<?, ?>) TestJson.route(api.registry(), "mc.events", params);
    }

    private static ApiException refusedAt(String path, DriverApplication api, Map<String, Object> params) {
        ApiException e = assertThrows(ApiException.class, () -> events(api, params));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.error().code());
        assertEquals(path, e.error().issues().get(0).path(), e.error().toString());
        assertEquals(0L, events(api, Map.of("op", "list")).get("count"), "a refused watcher must not be registered");
        return e;
    }

    @Test
    void anUnknownMethodIsRefusedAtInvoke() {
        ApiException e = refusedAt("/invoke", app(), Map.of("op", "watch", "invoke", "mc.observe.playr"));
        assertTrue(e.getMessage().contains("mc.observe.playr") || e.error().issues().get(0).message().contains("mc.observe.playr"),
                e.error().toString());
    }

    @Test
    void writesWaitsAndRecursiveWatchesAreRefusedAtInvoke() {
        DriverApplication api = app();
        for (String target : List.of("mc.bot.cancel", "mc.wait.condition", "mc.events", "mc.script.eval", "probe.slow")) {
            refusedAt("/invoke", api, Map.of("op", "watch", "invoke", target));
        }
    }

    @Test
    void theTargetsParamsAreCheckedAgainstItsOwnContract() {
        refusedAt("/params/value", app(), Map.of("op", "watch", "invoke", "probe.echo",
                "params", Map.of("value", "not a number")));
        refusedAt("/params/nope", app(), Map.of("op", "watch", "invoke", "probe.echo",
                "params", Map.of("nope", 1)));
    }

    @Test
    void onlyOnePredicate() {
        refusedAt("/below", app(), Map.of("op", "watch", "invoke", "probe.echo", "above", 1, "below", 5));
    }

    @Test
    void aWatcherFiresOnTheRisingEdgeAndOnceRemovesIt() throws Exception {
        DriverApplication api = app();
        List<EventContracts.Event> fired = new CopyOnWriteArrayList<>();
        CountDownLatch got = new CountDownLatch(1);
        api.addEventListener(e -> {
            if ("probe.fired".equals(e.type())) { fired.add(e); got.countDown(); }
        });
        Map<?, ?> ack = events(api, Map.of("op", "watch", "invoke", "probe.echo", "params", Map.of("value", 7),
                "field", "value", "above", 5, "emitAs", "probe.fired", "everyMs", 200, "once", true));
        assertEquals("watch", ack.get("op"));
        assertTrue(got.await(10, TimeUnit.SECONDS), "the watcher never fired");
        Map<?, ?> data = (Map<?, ?>) TestJson.plain(fired.get(0).data().orElseThrow().json());
        assertEquals(((Number) ack.get("id")).longValue(), ((Number) data.get("watch")).longValue());
        assertEquals(7L, ((Number) data.get("value")).longValue());
        assertEquals(0L, events(api, Map.of("op", "list")).get("count"), "once removes the watcher after it fires");
    }

    @Test
    void watchListUnwatch() {
        DriverApplication api = app();
        Map<?, ?> ack = events(api, Map.of("op", "watch", "invoke", "probe.echo", "everyMs", 60_000));
        Map<?, ?> listed = events(api, Map.of("op", "list"));
        assertEquals(1L, listed.get("count"));
        assertEquals(Boolean.TRUE, events(api, Map.of("op", "unwatch", "id", ack.get("id"))).get("removed"));
        assertEquals(Boolean.FALSE, events(api, Map.of("op", "unwatch", "id", ack.get("id"))).get("removed"));
    }

    @Test
    void theWatcherCountIsCapped() {
        DriverApplication api = app();
        List<Object> ids = new ArrayList<>();
        try {
            for (int i = 0; i < EventContracts.MAX_WATCHERS; i++) {
                ids.add(events(api, Map.of("op", "watch", "invoke", "probe.echo", "everyMs", 60_000)).get("id"));
            }
            ApiException e = assertThrows(ApiException.class,
                    () -> events(api, Map.of("op", "watch", "invoke", "probe.echo", "everyMs", 60_000)));
            assertEquals(ApiError.Code.SERVER_BUSY, e.error().code());
        } finally {
            for (Object id : ids) events(api, Map.of("op", "unwatch", "id", id));
        }
    }

    @Test
    void emitAnswersTheSeqAsAString() {
        DriverApplication api = app();
        Map<?, ?> r = events(api, Map.of("op", "emit", "type", "test.custom", "data", Map.of("hello", "world")));
        assertEquals("emit", r.get("op"));
        assertEquals(String.valueOf(api.eventSeq.get()), r.get("seq"));
        assertEquals(Map.of("hello", "world"), TestJson.plain(api.events.peekLast().data.json()));
    }
}
