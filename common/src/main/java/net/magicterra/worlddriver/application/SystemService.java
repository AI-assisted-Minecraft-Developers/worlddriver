package net.magicterra.worlddriver.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import net.magicterra.worlddriver.api.call.ApiTimers;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.call.WaitPermits;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.system.SystemApi;
import net.magicterra.worlddriver.api.methods.system.SystemApiBinding;
import net.magicterra.worlddriver.api.methods.system.SystemContracts;
import net.magicterra.worlddriver.api.methods.system.SystemContracts.TickTimes;
import net.magicterra.worlddriver.api.methods.system.SystemContracts.Version;
import net.magicterra.worlddriver.api.methods.system.SystemContracts.Waited;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.config.BuildStamp;

/** {@code mc.system.*}: what answers without a world. */
public final class SystemService implements SystemApi {
    private final DriverApplication api;

    SystemService(DriverApplication api) {
        this.api = api;
    }

    List<ApiMethod<?, ?>> methods() {
        return SystemApiBinding.methods(this);
    }

    @Override
    public CompletionStage<Version> version(NoParams p, CallContext ctx) {
        return CompletableFuture.completedFuture(version());
    }

    /** This build's name, version and uptime, and where its jar was loaded from. */
    public Version version() {
        Map<String, Object> s = BuildStamp.asMap();
        return new Version(
                "worlddriver",
                BuildStamp.version(),
                (System.nanoTime() - api.startNanos) / 1_000_000L,
                String.valueOf(s.get("loadedFrom")),
                String.valueOf(s.get("loadedKind")),
                Optional.ofNullable((String) s.get("builtAt")),
                Optional.ofNullable((Long) s.get("builtMs")),
                Optional.ofNullable((Long) s.get("sizeBytes")),
                Optional.ofNullable((String) s.get("stampError")));
    }

    @Override
    public CompletionStage<BlockPosition> testOrigin(NoParams p, CallContext ctx) {
        return CompletableFuture.completedFuture(testOrigin());
    }

    /** The fixed block (0, 200, 0) that queries and scene reads centre on when given no centre and no player. */
    public static BlockPosition testOrigin() {
        return new BlockPosition(
                DriverApplication.ORIGIN.getX(), DriverApplication.ORIGIN.getY(), DriverApplication.ORIGIN.getZ());
    }

    @Override
    public CompletionStage<Waited> waitTicks(SystemContracts.WaitTicksParams p, CallContext ctx) {
        return waitTicks(p.ticks(), ctx);
    }

    /**
     * Completes on the timer thread after {@code ticks} × 50 ms, holding no thread meanwhile. A request that is
     * cancelled or whose budget runs out first answers with the ticks that did pass, marked interrupted.
     */
    @SuppressWarnings("PMD.CloseResource") // the lease and cancel hook are closed when the wait completes
    private CompletionStage<Waited> waitTicks(int ticks, CallContext ctx) {
        if (ticks == 0) {
            return CompletableFuture.completedFuture(new Waited(0, Optional.empty(), Optional.empty()));
        }
        ctx.requireBlockingAllowed("mc.system.waitTicks");
        WaitPermits.Lease lease = ctx.waits().tryAcquire();
        if (lease == null) {
            throw ApiException.of(ApiError.Code.SERVER_BUSY, "server busy: every wait slot is in use; retry");
        }
        long t0 = System.nanoTime();
        long fullMs = ticks * 50L;
        // Only a JVM that runs the server has tick times to report.
        TickClock clock = TickClock.server();
        long fromTick = api.server() != null ? clock.tickCount() : -1;
        CompletableFuture<Waited> out = new CompletableFuture<>();
        Supplier<Optional<TickTimes>> times = () -> fromTick < 0 ? Optional.empty() : clock.since(fromTick);
        Runnable cut = () -> out.complete(new Waited(
                (int) Math.min(ticks, (System.nanoTime() - t0) / 50_000_000L), Optional.of(true), times.get()));
        ScheduledFuture<?> full =
                ApiTimers.schedule(() -> out.complete(new Waited(ticks, Optional.empty(), times.get())), fullMs);
        ScheduledFuture<?> deadline =
                ctx.remainingMillis() < fullMs ? ApiTimers.schedule(cut, ctx.remainingMillis()) : null;
        AutoCloseable gone = ctx.requestCancelled().onCancel(cut);
        return out.whenComplete((r, t) -> {
            full.cancel(false);
            if (deadline != null) {
                deadline.cancel(false);
            }
            try {
                gone.close();
            } catch (Exception ignored) {
                /* nothing to release */
            }
            lease.close();
        });
    }
}
