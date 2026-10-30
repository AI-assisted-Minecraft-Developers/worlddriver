package net.magicterra.worlddriver.api.call;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The one timer thread behind every deadline the API sets: a wait that runs out, a hop that is given up. A task here
 * only completes a future; it never encodes, writes to a socket or touches the game.
 */
public final class ApiTimers {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("worlddriver-timer").daemon().factory());

    private ApiTimers() {}

    /** Run {@code task} once after {@code delayMs} on the shared timer thread; a negative delay counts as zero. */
    public static ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        return TIMER.schedule(task, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }
}
