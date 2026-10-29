package net.magicterra.worlddriver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A routed call whose server thread is busy longer than the hop's limit: the registry itself gives up with
 * {@code not_executed}, as every transport does, and a {@link ApiRegistry#withThreadWait} view waits it out. The
 * hop's limit is 200 ms here, standing in for the server thread's 8 s.
 */
class ThreadWaitRouteTest {

    /** A server thread busy for {@code ms}, then working through its queue. */
    private static Executor busyFor(long ms) {
        Queue<Runnable> queued = new ArrayDeque<>();
        Thread t = new Thread(() -> {
            try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
            Runnable r;
            while (true) {
                synchronized (queued) { r = queued.poll(); }
                if (r == null) return;
                r.run();
            }
        }, "test-busy-server");
        t.setDaemon(true);
        t.start();
        return r -> { synchronized (queued) { queued.add(r); } };
    }

    private static ApiRegistry withHoppingProbe(Executor server) {
        ServerThreadHop hop = new ServerThreadHop(server, () -> false, 200);
        MethodSpec<ProbeMethods.Echo, ProbeMethods.Echo> spec = MethodSpec.of("probe.hop",
                ApiTypes.record(ProbeMethods.Echo.class), ApiTypes.record(ProbeMethods.Echo.class),
                "Answers its value from the server thread.");
        ApiRegistry api = new ApiRegistry();
        api.registerBatch(new ApiBatch("probe", "hop", List.of(
                new ApiMethod<>(spec, (p, ctx) -> hop.submit(() -> p, ctx)))));
        return api;
    }

    private static JsonObject value(int v) {
        JsonObject o = new JsonObject();
        o.addProperty("value", v);
        return o;
    }

    @Test
    @Timeout(10)
    void aViewWithAThreadWaitOutlastsABusyServerThread() {
        ApiRegistry api = withHoppingProbe(busyFor(600));
        ApiRegistry patient = api.withThreadWait(3_000);
        assertEquals(5, patient.route("probe.hop", value(5)).getAsJsonObject().get("value").getAsInt());
        assertSame(api.snapshot(), patient.snapshot(), "a view sees the registry's own methods");
    }

    /** The typed path every generated caller ({@code XxxBlockingApi.of(registry)}) takes. */
    @Test
    @Timeout(10)
    void aTypedCallThroughTheViewWaitsToo() {
        ApiRegistry patient = withHoppingProbe(busyFor(600)).withThreadWait(3_000);
        @SuppressWarnings("unchecked")
        MethodSpec<ProbeMethods.Echo, ProbeMethods.Echo> spec =
                (MethodSpec<ProbeMethods.Echo, ProbeMethods.Echo>) patient.spec("probe.hop");
        ProbeMethods.Echo out = ApiRegistry.await(
                patient.invoke(spec, new ProbeMethods.Echo(9), CallContext.standalone()), CallContext.SERVER_CAP_MS);
        assertEquals(9, out.value());
    }

    @Test
    @Timeout(10)
    void theRegistryItselfStillGivesUpAtTheHopsLimit() {
        ApiRegistry api = withHoppingProbe(busyFor(600));
        long start = System.nanoTime();
        ApiException e = assertThrows(ApiException.class, () -> api.route("probe.hop", value(5)));
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(ApiError.Code.NOT_EXECUTED, e.error().code());
        assertTrue(tookMs < 600, "gave up after " + tookMs + "ms, not at the hop's 200ms");
    }
}
