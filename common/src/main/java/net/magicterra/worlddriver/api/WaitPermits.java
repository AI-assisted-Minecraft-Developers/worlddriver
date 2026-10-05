package net.magicterra.worlddriver.api;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The quota a suspended wait draws from, separate from the quota for running requests: a full set
 * of waits must still leave room to observe and to cancel. A wait that cannot get a permit is refused
 * before anything starts.
 */
public interface WaitPermits {

    /** A permit, or null when the quota is full. */
    Lease tryAcquire();

    /** A held permit; {@link #close} gives it back exactly once, however often it is called. */
    interface Lease extends AutoCloseable {
        @Override void close();
    }

    /** At most {@code max} waits at a time. */
    static WaitPermits bounded(int max) {
        Semaphore s = new Semaphore(max);
        return () -> {
            if (!s.tryAcquire()) return null;
            AtomicBoolean released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) s.release();
            };
        };
    }

    /** Waits drawn from {@code inner} as well as from this connection's own bound. */
    static WaitPermits both(WaitPermits outer, WaitPermits inner) {
        return () -> {
            Lease a = outer.tryAcquire();
            if (a == null) return null;
            Lease b = inner.tryAcquire();
            if (b == null) { a.close(); return null; }
            return () -> { b.close(); a.close(); };
        };
    }
}
