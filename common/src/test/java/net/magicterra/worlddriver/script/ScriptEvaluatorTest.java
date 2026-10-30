package net.magicterra.worlddriver.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.Outcome;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.Test;

/**
 * Runs alive at once are bounded: one past the bound is refused before it starts, and a slot comes back with its
 * thread.
 */
class ScriptEvaluatorTest {

    @Test
    void aRunPastTheBoundIsRefusedAndASlotComesBack() throws Exception {
        ScriptEvaluator eval = new ScriptEvaluator(new ApiRegistry());
        List<CompletableFuture<Outcome>> busy = new ArrayList<>();
        for (int i = 0; i < ScriptEvaluator.MAX_RUNS; i++)
            busy.add(eval.run("while (true) {}", 1500, CallContext.standalone(), "test")
                    .toCompletableFuture());

        ApiException refused =
                assertThrows(ApiException.class, () -> eval.run("1", 1000, CallContext.standalone(), "test"));
        assertEquals(ApiError.Code.SERVER_BUSY, refused.error().code());

        for (CompletableFuture<Outcome> f : busy) f.get(10, TimeUnit.SECONDS);
        // The callers are answered by the watchdog a moment before the threads let go of their slots.
        Outcome later = null;
        for (int i = 0; i < 50 && later == null; i++) {
            try {
                later = eval.run("6 * 7", 1000, CallContext.standalone(), "test")
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
            } catch (ApiException stillBusy) {
                Thread.sleep(100);
            }
        }
        assertTrue(later != null && later.error().isEmpty(), "a run after the others ended was not accepted: " + later);
    }
}
