package net.magicterra.worlddriver.api.call;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A one-way signal that something is no longer wanted: one request, or a whole script run. Two tokens rather than one
 * future, because ending a request and stopping a run end different things.
 */
public interface CancellationToken {

    boolean isCancelled();

    /**
     * Run {@code listener} once when this is cancelled, at once if it already is. Closing the returned handle removes a
     * listener that has not run, which is how a finished wait stops being told.
     */
    AutoCloseable onCancel(Runnable listener);

    CancellationToken NEVER = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public AutoCloseable onCancel(Runnable listener) {
            return () -> {};
        }
    };

    /** A token whose owner can {@link #cancel} it. */
    final class Source implements CancellationToken {
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public AutoCloseable onCancel(Runnable listener) {
            listeners.add(listener);
            if (cancelled && listeners.remove(listener)) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        /** Cancel and run every listener once; later calls do nothing. */
        public void cancel() {
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
            }
            for (Runnable r : listeners) {
                if (listeners.remove(r)) {
                    try {
                        r.run();
                    } catch (RuntimeException ignored) {
                        /* one listener cannot stop the rest */
                    }
                }
            }
        }
    }
}
