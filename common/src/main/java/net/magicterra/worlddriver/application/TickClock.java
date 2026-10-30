package net.magicterra.worlddriver.application;

import java.util.Arrays;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.system.SystemContracts.TickTimes;
import net.minecraft.server.MinecraftServer;

/**
 * The work time of the last {@value #SIZE} server ticks, as vanilla times them: from the start of a tick to its end,
 * not counting the wait for the next one. Vanilla keeps only the last 100 in a private array that the call that ends a
 * wait would find half overwritten, so each tick's figure is copied here as it is made.
 *
 * <p>Written on the server thread from the post-tick hook, read on whatever thread finishes a wait, hence the lock. One
 * clock per JVM, since a JVM runs one server at a time.
 */
public final class TickClock {
    static final int SIZE = 4096;

    private static final TickClock SERVER = new TickClock();

    private final long[] nanos = new long[SIZE];

    private long count;

    TickClock() {}

    /** The clock the server's post-tick hook feeds and a wait reads. */
    public static TickClock server() {
        return SERVER;
    }

    /** Copies the figure vanilla has just stored for the tick that ended. */
    public void record(MinecraftServer server) {
        long[] vanilla = server.tickTimesNanos;
        record(vanilla[server.getTickCount() % vanilla.length]);
    }

    synchronized void record(long tickNanos) {
        nanos[(int) (count % SIZE)] = tickNanos;
        count++;
    }

    /** Ticks recorded since the server started; the start mark of a wait. */
    public synchronized long tickCount() {
        return count;
    }

    /** Forgets everything, as a stopping server does. */
    public synchronized void reset() {
        count = 0;
    }

    /** The ticks recorded after {@code fromTickCount}, at most the last {@value #SIZE}; empty when there were none. */
    public Optional<TickTimes> since(long fromTickCount) {
        double[] ms;
        synchronized (this) {
            int n = (int) Math.min(count - fromTickCount, SIZE);
            if (n <= 0) {
                return Optional.empty();
            }
            ms = new double[n];
            for (int i = 0; i < n; i++) {
                ms[i] = nanos[(int) ((count - n + i) % SIZE)] / 1_000_000.0;
            }
        }
        double sum = 0;
        for (double v : ms) {
            sum += v;
        }
        Arrays.sort(ms);
        int n = ms.length;
        double median = n % 2 == 1 ? ms[n / 2] : (ms[n / 2 - 1] + ms[n / 2]) / 2;
        return Optional.of(new TickTimes(n, sum / n, median, ms[n - 1]));
    }
}
