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

import net.magicterra.worlddriver.api.ApiBatch;
import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.ApiRegistry;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.GameThreads;
import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.bot.SettingsCalls;
import net.magicterra.worlddriver.bot.SettingsContract.Patch;
import net.magicterra.worlddriver.bot.SettingsContract.Read;
import org.junit.jupiter.api.Test;

/**
 * A generated caller is a request without the JSON: the same checks, the same handler, the same
 * refusals. Proven on {@link SettingsCalls} with no game running.
 */
class SettingsCallsTest {

    private static ApiError failure(java.util.concurrent.CompletionStage<?> stage) {
        return assertThrows(ApiException.class, () -> DriverApi.await(stage, 5_000)).error();
    }

    @Test
    void theCallerChecksParametersAsARequestIs() {
        SettingsCalls calls = SettingsCalls.of(new DriverApplication());
        ApiError e = failure(calls.setting(new Patch(List.of()), CallContext.standalone()));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.code(), e.toString());
    }

    @Test
    void theCallerReachesTheRegisteredHandler() {
        SettingsCalls calls = SettingsCalls.of(new DriverApplication());
        // No client and no server: only the handler knows to say so.
        ApiError e = failure(calls.setting(new Read(Optional.empty()), CallContext.standalone()));
        assertEquals(ApiError.Code.CAPABILITY_UNAVAILABLE, e.code(), e.toString());
    }

    @Test
    void anUnknownKeyIsRefusedAtItsOwnPath() {
        SettingsCalls calls = SettingsCalls.of(new DriverApplication());
        ApiError e = failure(calls.setting(Patch.of(Map.of("definitelyNotAKnob", 1)), CallContext.standalone()));
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
            DriverApi.await(registry.invoke(ProbeMethods.ECHO, new ProbeMethods.Echo(3), CallContext.standalone()), 5_000);
        } finally {
            GameThreads.installClient(null);
        }
        assertTrue(allowed.get(), "a game thread's call was handed a narrower context than its caller's");
        DriverApi.await(registry.invoke(ProbeMethods.ECHO, new ProbeMethods.Echo(3),
                CallContext.standalone().onGameThread()), 5_000);
        assertFalse(allowed.get(), "the caller's own context was not passed through");
    }
}
