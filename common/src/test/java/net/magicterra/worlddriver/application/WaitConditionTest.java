package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.TestJson;
import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code mc.wait.condition} polls only what may be polled, checks the polled call before it starts, and the stream's
 * cursors are strings on the way in as well as out.
 */
@Timeout(30)
class WaitConditionTest {

    private static final String INVOKE = "invoke";

    private static final String MC_WAIT_CONDITION = "mc.wait.condition";

    private static final String VALUE = "value";

    private static DriverApplication app() {
        return ProbeMethods.install(new DriverApplication());
    }

    private static ApiException refused(DriverApplication api, String method, Map<String, Object> params, String path) {
        ApiException e = assertThrows(ApiException.class, () -> TestJson.route(api.registry(), method, params));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.error().code(), e.error().toString());
        assertEquals(path, e.error().issues().get(0).path(), e.error().toString());
        return e;
    }

    /** The goto example from the contract review: a write repeated by a wait is refused at /invoke. */
    @Test
    void writeIsNotConditionSource() {
        DriverApplication api = app();
        refused(
                api,
                MC_WAIT_CONDITION,
                Map.of(
                        INVOKE,
                        "mc.bot.goto",
                        "params",
                        Map.of("target", Map.of("kind", "position", "pos", Map.of("x", 10, "y", 64, "z", 20))),
                        "field",
                        "completed"),
                "/invoke");
    }

    @Test
    void nestedWaitsScriptsAndUntypedRoutesAreRefused() {
        DriverApplication api = app();
        for (String target : List.of(
                MC_WAIT_CONDITION, "mc.wait.event", "mc.script.eval", "mc.events", "mc.query", "mc.observe.playr")) {
            refused(api, MC_WAIT_CONDITION, Map.of(INVOKE, target), "/invoke");
        }
    }

    @Test
    void thePolledParamsAreCheckedBeforeTheWaitStarts() {
        refused(
                app(),
                MC_WAIT_CONDITION,
                Map.of(INVOKE, "probe.echo", "params", Map.of(VALUE, -1), "background", true),
                "/params/value");
    }

    @Test
    void satisfiedConditionAnswersDoneWithTheValue() {
        Map<?, ?> r = (Map<?, ?>) TestJson.route(
                app().registry(),
                MC_WAIT_CONDITION,
                Map.of(INVOKE, "probe.echo", "params", Map.of(VALUE, 3), "field", VALUE, VALUE, 3));
        assertEquals("done", r.get("outcome"), r.toString());
        assertEquals(Boolean.TRUE, r.get("satisfied"), r.toString());
        assertEquals(3L, ((Number) r.get(VALUE)).longValue());
    }

    @Test
    void anUnsatisfiedConditionTimesOutWithoutValue() {
        Map<?, ?> r = (Map<?, ?>) TestJson.route(
                app().registry(),
                MC_WAIT_CONDITION,
                Map.of(INVOKE, "probe.echo", "field", "nope", "timeoutMs", 300, "pollMs", 50));
        assertEquals(Boolean.FALSE, r.get("satisfied"), r.toString());
        assertEquals(false, r.containsKey(VALUE), "a path that leads nowhere is omitted, not null: " + r);
    }

    @Test
    void cursorsAreDecimalStrings() {
        DriverApplication api = app();
        refused(api, "mc.wait.event", Map.of("cursor", 5), "/cursor");
        refused(api, "mc.observe.eventsSince", Map.of("cursor", 5), "/cursor");
        refused(api, "mc.observe.eventsSince", Map.of("cursor", "5.0"), "/cursor");
    }
}
