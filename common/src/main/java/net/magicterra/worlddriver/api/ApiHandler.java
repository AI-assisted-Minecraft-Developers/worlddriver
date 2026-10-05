package net.magicterra.worlddriver.api;

import java.util.concurrent.CompletionStage;

/**
 * The implementation of one method. It must not wait before returning its stage: work that takes
 * time completes the stage later, from whatever thread finishes it, and the dispatcher moves the
 * encoding off that thread.
 */
@FunctionalInterface
public interface ApiHandler<P, R> {
    CompletionStage<R> execute(P params, CallContext context);
}
