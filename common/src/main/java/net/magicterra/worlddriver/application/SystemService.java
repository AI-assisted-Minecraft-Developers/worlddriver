package net.magicterra.worlddriver.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;

import net.magicterra.worlddriver.BuildStamp;
import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.ApiTimers;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.WaitPermits;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.system.SystemApi;
import net.magicterra.worlddriver.api.methods.system.SystemApiBinding;
import net.magicterra.worlddriver.api.methods.system.SystemContracts;
import net.magicterra.worlddriver.api.methods.system.SystemContracts.Version;
import net.magicterra.worlddriver.api.methods.system.SystemContracts.Waited;

/** {@code mc.system.*}: what answers without a world. */
public final class SystemService implements SystemApi {
    private final DriverApplication api;
    SystemService(DriverApplication api) { this.api = api; }

    List<ApiMethod<?, ?>> methods() {
        return SystemApiBinding.methods(this);
    }

    @Override
    public CompletionStage<Version> version(NoParams p, CallContext ctx) {
        return CompletableFuture.completedFuture(version());
    }

    @Override
    public CompletionStage<BlockPosition> testOrigin(NoParams p, CallContext ctx) {
        return CompletableFuture.completedFuture(testOrigin());
    }

    @Override
    public CompletionStage<Waited> waitTicks(SystemContracts.WaitTicksParams p, CallContext ctx) {
        return waitTicks(p.ticks(), ctx);
    }

    public Version version() {
        Map<String, Object> s = BuildStamp.asMap();
        return new Version("worlddriver", BuildStamp.VERSION, (System.nanoTime() - api.startNanos) / 1_000_000L,
                String.valueOf(s.get("loadedFrom")), String.valueOf(s.get("loadedKind")),
                Optional.ofNullable((String) s.get("builtAt")), Optional.ofNullable((Long) s.get("builtMs")),
                Optional.ofNullable((Long) s.get("sizeBytes")), Optional.ofNullable((String) s.get("stampError")));
    }

    public static BlockPosition testOrigin() {
        return new BlockPosition(DriverApplication.ORIGIN.getX(), DriverApplication.ORIGIN.getY(), DriverApplication.ORIGIN.getZ());
    }

    /**
     * Completes on the timer thread after {@code ticks} × 50 ms, holding no thread meanwhile. A request that is
     * cancelled or whose budget runs out first answers with the ticks that did pass, marked interrupted.
     */
    private CompletionStage<Waited> waitTicks(int ticks, CallContext ctx) {
        if (ticks == 0) return CompletableFuture.completedFuture(new Waited(0, Optional.empty()));
        ctx.requireBlockingAllowed("mc.system.waitTicks");
        WaitPermits.Lease lease = ctx.waits().tryAcquire();
        if (lease == null) throw ApiException.of(ApiError.Code.SERVER_BUSY, "server busy: every wait slot is in use; retry");
        long t0 = System.nanoTime();
        long fullMs = ticks * 50L;
        CompletableFuture<Waited> out = new CompletableFuture<>();
        Runnable cut = () -> out.complete(new Waited((int) Math.min(ticks, (System.nanoTime() - t0) / 50_000_000L),
                Optional.of(true)));
        ScheduledFuture<?> full = ApiTimers.schedule(() -> out.complete(new Waited(ticks, Optional.empty())), fullMs);
        ScheduledFuture<?> deadline = ctx.remainingMillis() < fullMs ? ApiTimers.schedule(cut, ctx.remainingMillis()) : null;
        AutoCloseable gone = ctx.requestCancelled().onCancel(cut);
        return out.whenComplete((r, t) -> {
            full.cancel(false);
            if (deadline != null) deadline.cancel(false);
            try { gone.close(); } catch (Exception ignored) { /* nothing to release */ }
            lease.close();
        });
    }
}
