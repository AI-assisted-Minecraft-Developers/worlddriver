package net.magicterra.worlddriver.api.registry;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;

/**
 * The implementation of one method. It must not wait before returning its stage: work that takes time completes the
 * stage later, from whatever thread finishes it, and the dispatcher moves the encoding off that thread.
 */
@FunctionalInterface
public interface ApiHandler<P, R> {
    /** Runs the call with its decoded params; the stage completes with the result, or fails with the error. */
    CompletionStage<R> execute(P params, CallContext context);
}
