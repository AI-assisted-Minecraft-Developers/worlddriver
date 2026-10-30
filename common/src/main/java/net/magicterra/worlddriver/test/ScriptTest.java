package net.magicterra.worlddriver.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Minimal test harness callable from Rhino JS. No JUnit dependency. */
public final class ScriptTest {
    private static final List<Result> results = new CopyOnWriteArrayList<>();

    private ScriptTest() {}

    /** Runs {@code body} as the test {@code name} and records it as passed unless it throws. */
    public static void run(String name, Consumer<TestContext> body) {
        TestContext ctx = new TestContext(name);
        long t0 = System.nanoTime();
        try {
            body.accept(ctx);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            results.add(Result.ok(name, ms));
        } catch (Throwable e) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            results.add(Result.fail(name, e, ms));
        }
    }

    /** An immutable copy of every result recorded since the last {@link #clear()}. */
    public static List<Result> snapshot() {
        return List.copyOf(results);
    }

    /** Forgets every recorded result. */
    public static void clear() {
        results.clear();
    }

    /** The number of recorded tests that failed. */
    public static int summary() {
        int fail = 0;
        for (Result r : results) {
            if (!r.passed) {
                fail++;
            }
        }
        return fail;
    }

    /** One finished test: its name, whether it passed, how long it took, and what it threw if it failed. */
    public static final class Result {
        public final String name;

        public final boolean passed;

        public final long ms;

        public final Throwable error;

        private Result(String name, boolean passed, long ms, Throwable err) {
            this.name = name;
            this.passed = passed;
            this.ms = ms;
            this.error = err;
        }

        /** A pass that took {@code ms} milliseconds. */
        public static Result ok(String n, long ms) {
            return new Result(n, true, ms, null);
        }

        /** A failure that threw {@code e} after {@code ms} milliseconds. */
        public static Result fail(String n, Throwable e, long ms) {
            return new Result(n, false, ms, e);
        }
    }
}
