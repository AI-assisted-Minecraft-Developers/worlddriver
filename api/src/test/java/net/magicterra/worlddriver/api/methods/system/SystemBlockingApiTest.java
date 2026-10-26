package net.magicterra.worlddriver.api.methods.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import org.junit.jupiter.api.Test;

/** The generated blocking caller answers a stage's value and rethrows a failed stage's {@link ApiException}. */
class SystemBlockingApiTest {

    private static final class Fake implements SystemApi {
        @Override public CompletionStage<SystemContracts.Version> version(NoParams p, CallContext ctx) {
            return CompletableFuture.failedFuture(ApiException.of(ApiError.Code.INTERNAL_ERROR, "no version here"));
        }

        @Override public CompletionStage<BlockPosition> testOrigin(NoParams p, CallContext ctx) {
            return CompletableFuture.completedFuture(new BlockPosition(1, 2, 3));
        }

        @Override public CompletionStage<SystemContracts.Waited> waitTicks(SystemContracts.WaitTicksParams p, CallContext ctx) {
            return CompletableFuture.failedFuture(ApiException.of(ApiError.Code.INTERNAL_ERROR, "no waiting"));
        }
    }

    private static SystemBlockingApi blocking() {
        ApiRegistry registry = new ApiRegistry();
        registry.registerBatch(new ApiBatch(ApiRegistry.CORE_OWNER, "core", SystemApiBinding.methods(new Fake())));
        return SystemBlockingApi.of(registry);
    }

    @Test
    void answersTheValueItsStageCompletedWith() {
        assertEquals(new BlockPosition(1, 2, 3), blocking().testOrigin(new NoParams()));
    }

    @Test
    void rethrowsTheApiExceptionOfAFailedStage() {
        ApiException e = assertThrows(ApiException.class, () -> blocking().version(new NoParams()));
        assertEquals(ApiError.Code.INTERNAL_ERROR, e.error().code());
    }
}
