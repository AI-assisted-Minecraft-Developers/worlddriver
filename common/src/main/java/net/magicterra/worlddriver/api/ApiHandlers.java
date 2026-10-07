package net.magicterra.worlddriver.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Adapters from plain functions to {@link ApiHandler}. */
public final class ApiHandlers {
    private ApiHandlers() {}

    /**
     * A short synchronous service: it runs on the calling thread and its result or exception becomes
     * a completed stage. "Short" is a property of the service, not something this adapter makes true;
     * one that hops to a game thread still holds its caller for that hop.
     */
    public static <P, R> ApiHandler<P, R> immediate(Function<P, R> service) {
        return (p, ctx) -> run(() -> service.apply(p));
    }

    /** {@link #immediate} for a service that reads the call context. */
    public static <P, R> ApiHandler<P, R> immediate(BiFunction<P, CallContext, R> service) {
        return (p, ctx) -> run(() -> service.apply(p, ctx));
    }

    /** {@code body}'s result, or its exception, as a completed stage: an immediate handler's body. */
    public static <R> CompletableFuture<R> run(java.util.function.Supplier<R> body) {
        try {
            return CompletableFuture.completedFuture(body.get());
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }
}
