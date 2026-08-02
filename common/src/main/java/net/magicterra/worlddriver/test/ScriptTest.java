package net.magicterra.worlddriver.test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Minimal test harness callable from Rhino JS. No JUnit dependency. */
public final class ScriptTest {
    private static final List<Result> results = new CopyOnWriteArrayList<>();

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

    public static List<Result> snapshot() { return List.copyOf(results); }
    public static void clear() { results.clear(); }

    public static int summary() {
        int pass = 0, fail = 0;
        for (Result r : results) if (r.passed) pass++; else fail++;
        return fail;
    }

    public static final class Result {
        public final String name;
        public final boolean passed;
        public final long ms;
        public final Throwable error;

        private Result(String name, boolean passed, long ms, Throwable err) {
            this.name = name; this.passed = passed; this.ms = ms; this.error = err;
        }
        public static Result ok(String n, long ms) { return new Result(n, true, ms, null); }
        public static Result fail(String n, Throwable e, long ms) { return new Result(n, false, ms, e); }
    }
}
