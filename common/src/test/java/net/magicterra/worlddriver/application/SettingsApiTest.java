package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.thread.GameThreads;
import net.magicterra.worlddriver.bot.SettingsApi;
import net.magicterra.worlddriver.bot.SettingsContracts.Patch;
import net.magicterra.worlddriver.bot.SettingsContracts.Read;
import org.junit.jupiter.api.Test;

/**
 * A generated caller is a request without the JSON: the same checks, the same handler, the same
 * refusals. Proven on {@link SettingsApi} with no game running.
 */
class SettingsApiTest {

    private static ApiError failure(java.util.concurrent.CompletionStage<?> stage) {
        return assertThrows(ApiException.class, () -> ApiRegistry.await(stage, 5_000)).error();
    }

    @Test
    void theCallerChecksParametersAsARequestIs() {
        var calls = SettingsApi.of(new DriverApplication().registry());
        ApiError e = failure(calls.setting(new Patch(List.of())));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.code(), e.toString());
    }

    @Test
    void theCallerReachesTheRegisteredHandler() {
        var calls = SettingsApi.of(new DriverApplication().registry());
        // No client and no server: only the handler knows to say so.
        ApiError e = failure(calls.setting(new Read(Optional.empty())));
        assertEquals(ApiError.Code.CAPABILITY_UNAVAILABLE, e.code(), e.toString());
    }

    @Test
    void anUnknownKeyIsRefusedAtItsOwnPath() {
        var calls = SettingsApi.of(new DriverApplication().registry());
        ApiError e = failure(calls.setting(Patch.of(Map.of("definitelyNotAKnob", 1))));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.code(), e.toString());
        assertTrue(e.issues().stream().anyMatch(i -> i.path().equals("/values/definitelyNotAKnob")), e.toString());
    }

    /** The call answers a stage, so a game thread may make it: a hop it needs is queued, not refused. */
    @Test
    void aTypedCallKeepsItsCallersContextOnAGameThread() {
        ApiRegistry registry = new ApiRegistry();
        AtomicBoolean allowed = new AtomicBoolean(false);
        registry.registerBatch(new ApiBatch("probe", "probe", List.of(new ApiMethod<>(ProbeMethods.ECHO, (p, ctx) -> {
            allowed.set(ctx.blockingAllowed());
            return CompletableFuture.completedFuture(p);
        }))));
        GameThreads.installClient(() -> true);
        try {
            ApiRegistry.await(registry.invoke(ProbeMethods.ECHO, new ProbeMethods.Echo(3), CallContext.standalone()), 5_000);
        } finally {
            GameThreads.installClient(null);
        }
        assertTrue(allowed.get(), "a game thread's call was handed a narrower context than its caller's");
        ApiRegistry.await(registry.invoke(ProbeMethods.ECHO, new ProbeMethods.Echo(3),
                CallContext.standalone().onGameThread()), 5_000);
        assertFalse(allowed.get(), "the caller's own context was not passed through");
    }
}
