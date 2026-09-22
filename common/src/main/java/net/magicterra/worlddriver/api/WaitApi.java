package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.model.Params;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * {@code mc.wait.*} long-polling handlers, extracted from {@code DriverApi}. All
 * wait methods block the calling thread (HTTP / WS worker, or script-eval pool)
 * — never the server thread — and bound the wait by a hard wall-clock deadline
 * so a stuck condition can't hang a client. Inner work re-uses the same routes
 * everyone else calls via an {@link DriverApi} back-reference, so policy stays in
 * one place (e.g., {@code observe.container} requires server; {@code wait.condition}
 * inherits that requirement transparently).
 *
 * <p><b>Background mode.</b> An LLM agent issues tool calls SERIALLY: a blocking
 * wait holds its single channel for the whole budget, during which it can neither
 * act nor react to the live {@code <channel>} event stream (it goes blind to
 * threats/death). Pass {@code background:true} to any wait.* tool and it returns a
 * {@code waitId} IMMEDIATELY; the poll loop runs on a daemon thread and, when it
 * finishes, (a) stores the result for {@code mc.wait.result{waitId}} and (b) emits
 * a {@code wait.done} event on the push channel. The agent stays responsive and
 * either polls {@code mc.wait.result} or watches for the event.
 */
public final class WaitApi {
    private final DriverApi api;
    WaitApi(DriverApi api) { this.api = api; }

    /** Maximum wall-clock budget any wait.* tool will accept, in ms. Keeps a
     *  runaway script from squatting on a worker thread for hours. Public because the
     *  WebSocket liveness window must outlast it. */
    public static final long MAX_BUDGET_MS = 120_000L;

    // ---- background wait machinery (see class javadoc) -------------------------
    private static final ExecutorService BG = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-wait-bg");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong WAIT_SEQ = new AtomicLong();
    private static final int MAX_RESULTS = 64;
    /** waitId → finished result. Bounded and INSERTION-ORDERED so the genuinely
     *  oldest entry is evicted on overflow. A ConcurrentHashMap iterates in hash
     *  order, so its "drop the first key" eviction could discard a just-finished
     *  result the agent hasn't fetched yet, hanging its mc.wait.result poll. Wrapped
     *  synchronized since it's touched from the agent-wait-bg daemon and result()'s
     *  handler thread; removeEldestEntry makes put() self-bounding (atomic). */
    private static final Map<String, Map<String, Object>> RESULTS =
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > MAX_RESULTS;
                }
            });
    /** waitIds whose body is still running. Without it an id missing from RESULTS is
     *  ambiguous — still running, or gone — and reading it as "pending" hung agents. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * Run a wait body either inline (blocking, default) or — when {@code background:true}
     * — on a daemon thread, returning a {@code waitId} ack immediately. Caller-side
     * validation (e.g. {@code api.level()}) must run BEFORE this so a bad request
     * still fails fast rather than being acked and failing on the background thread.
     */
    private Map<String, Object> run(Params p, String kind, Supplier<Map<String, Object>> body) {
        if (!p.getBool("background", false)) return body.get();
        String waitId = kind + "-" + WAIT_SEQ.incrementAndGet();
        IN_FLIGHT.add(waitId);
        BG.execute(() -> {
            Map<String, Object> result;
            try {
                result = body.get();
            } catch (Throwable t) {
                result = new LinkedHashMap<>();
                result.put("error", String.valueOf(t.getMessage()));
            }
            result.put("waitId", waitId);
            result.put("kind", kind);
            try {
                RESULTS.put(waitId, result);  // self-bounding via removeEldestEntry
            } finally {
                // After the put, so a concurrent result() never sees the id in neither place.
                IN_FLIGHT.remove(waitId);
            }
            api.emit("wait.done", null, result);
        });
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("waitId", waitId);
        ack.put("background", true);
        ack.put("started", true);
        return ack;
    }

    /**
     * Fetch the result of a background wait. Returns {@code {pending:true}} while
     * the wait is running, then the full result (and removes it unless
     * {@code consume:false}). An id that is neither running nor stored — never
     * issued, already consumed, or evicted by newer results — is an error.
     */
    public Map<String, Object> result(Map<String, Object> raw) {
        Params p = Params.of(raw);
        String waitId = p.getString("waitId");
        if (waitId == null || waitId.isBlank()) throw new IllegalArgumentException("waitId required");
        // In-flight first: the finisher stores the result before it clears the flag.
        if (IN_FLIGHT.contains(waitId)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pending", true);
            out.put("waitId", waitId);
            return out;
        }
        Map<String, Object> r = p.getBool("consume", true) ? RESULTS.remove(waitId) : RESULTS.get(waitId);
        if (r == null) {
            throw new IllegalArgumentException("unknown waitId '" + waitId + "': never started, already "
                    + "consumed, or evicted (only the " + MAX_RESULTS + " newest unread results are kept)");
        }
        return r;
    }

    /**
     * Long-poll for the next event(s) since {@code cursor} matching {@code types}.
     * Returns as soon as at least one event is available, or {@code {timedOut:true}}
     * on deadline. {@code cursor} in the response is the seq of the last event
     * returned — pass it back on the next call to chain. Pass {@code background:true}
     * to return a {@code waitId} immediately (see class javadoc).
     */
    public Map<String, Object> event(Map<String, Object> params) {
        api.level();
        Params p = Params.of(params);
        long cursor = p.getLong("cursor");
        Set<String> types = null;
        if (p.get("types") instanceof List<?>) {
            types = new LinkedHashSet<>(p.getStringList("types"));
            if (types.isEmpty()) types = null;
        }
        int limit = p.getIntClamped("limit", 32, 1, 256);
        long budgetMs = p.getLongClamped("timeoutMs", 5000L, 100L, MAX_BUDGET_MS);
        long pollMs = p.getLongClamped("pollMs", 100L, 50L, 2000L);
        Set<String> finalTypes = types;

        return run(p, "event", () -> {
            long t0 = System.nanoTime();
            long deadlineNanos = t0 + budgetMs * 1_000_000L;
            while (true) {
                List<DriverEvent> page = api.observe.eventsSince(cursor, finalTypes, limit);
                if (!page.isEmpty()) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    long newCursor = page.get(page.size() - 1).seq;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("events", page);
                    out.put("timedOut", false);
                    out.put("cursor", newCursor);
                    out.put("ms", ms);
                    return out;
                }
                if (sleepUntil(deadlineNanos, pollMs)) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("events", List.of());
                    out.put("timedOut", true);
                    out.put("cursor", cursor);
                    out.put("ms", ms);
                    return out;
                }
            }
        });
    }

    /**
     * Wait until the client is fully in-world (a player exists and a world is
     * loaded). Does NOT require {@code DriverApi} to be attached to a server —
     * the whole point is to wait until that happens. On dedicated server (no
     * client bound) throws "mc.client.* not available" immediately.
     */
    public Map<String, Object> worldReady(Map<String, Object> params) {
        Params p = Params.of(params);
        long budgetMs = p.getLongClamped("timeoutMs", 30_000L, 100L, MAX_BUDGET_MS);
        long pollMs = p.getLongClamped("pollMs", 250L, 50L, 2000L);

        return run(p, "worldReady", () -> {
            long t0 = System.nanoTime();
            long deadlineNanos = t0 + budgetMs * 1_000_000L;
            Map<String, Object> info = null;
            while (true) {
                info = DriverApi.requireClient().screenInfo();
                boolean ready = Boolean.TRUE.equals(info.get("worldOpen")) && Boolean.TRUE.equals(info.get("hasPlayer"));
                if (ready) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ready", true);
                    out.put("ms", ms);
                    out.put("info", info);
                    return out;
                }
                if (sleepUntil(deadlineNanos, pollMs)) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ready", false);
                    out.put("ms", ms);
                    out.put("info", info);
                    return out;
                }
            }
        });
    }

    /**
     * Generic poll-until-truthy. Calls {@code invoke}/{@code params} every {@code pollMs};
     * walks {@code field} (dotted path) into the result; succeeds when the value is truthy
     * (or, if {@code value} is provided, deep-equals it). Use for things like:
     *
     *   {invoke:"mc.observe.container", params:{pos:{...}}, field:"slots.2.count"} —
     *   wait for a furnace's output slot to be non-empty.
     *
     * Pass {@code background:true} to return a {@code waitId} immediately (see class
     * javadoc) — strongly recommended for long phase/time waits in LIVE play so the
     * agent stays responsive to the threat/hurt/death stream.
     */
    public Map<String, Object> condition(Map<String, Object> raw) {
        Params p = Params.of(raw);
        String method = p.getString("invoke");
        if (method == null || method.isBlank()) throw new IllegalArgumentException("invoke method name required");
        Map<String, Object> params = p.getMap("params");
        String field = p.getString("field");
        boolean strict = p.has("value");
        Object target = p.get("value");
        long budgetMs = p.getLongClamped("timeoutMs", 30_000L, 100L, MAX_BUDGET_MS);
        long pollMs = p.getLongClamped("pollMs", 500L, 50L, 5000L);

        return run(p, "condition", () -> {
            long t0 = System.nanoTime();
            long deadlineNanos = t0 + budgetMs * 1_000_000L;
            Object lastValue = null;
            while (true) {
                Object result = api.route(method, params);
                Object value = (field == null || field.isEmpty()) ? result : ApiSupport.walkPath(result, field);
                lastValue = value;
                if (strict && DIAG_LOGGED.compareAndSet(false, true)) {
                    LOG.info("[wait.condition] strict compare value={} ({}) target={} ({}) looseEq={}",
                            value, value == null ? "null" : value.getClass().getSimpleName(),
                            target, target == null ? "null" : target.getClass().getSimpleName(),
                            looseEquals(value, target));
                }
                boolean ok = strict ? looseEquals(value, target) : ApiSupport.isTruthy(value);
                if (ok) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("satisfied", true);
                    out.put("value", value);
                    out.put("ms", ms);
                    return out;
                }
                if (sleepUntil(deadlineNanos, pollMs)) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("satisfied", false);
                    out.put("value", lastValue);
                    out.put("ms", ms);
                    return out;
                }
            }
        });
    }

    /** One-shot diagnostic so the first strict comparison logs its operand types. */
    private static final AtomicBoolean DIAG_LOGGED = new AtomicBoolean(false);

    /**
     * Type-tolerant equality for {@code value}-target comparison. The live field
     * value is a RAW Java object from the handler (Boolean / Integer / Long /
     * Double / String), while the {@code value} param arrives through a JSON
     * decoder that may box it differently (numbers as Long vs Double, etc.), so
     * {@link Objects#equals} alone yields false negatives — e.g. field count
     * {@code Integer 1} vs target {@code Long 1}, or a boolean field vs a
     * stringified target. Compare numbers by double value and fall back to a
     * string-form match so an honest equal never reads as "not satisfied".
     */
    static boolean looseEquals(Object fieldVal, Object target) {
        if (Objects.equals(fieldVal, target)) return true;
        if (fieldVal == null || target == null) return false;
        // Numeric compare when both sides are numbers OR a numeric string (handles
        // field Float 20.0 / Integer 1 vs target String "20" / "1").
        Double a = asNumber(fieldVal), b = asNumber(target);
        if (a != null && b != null) return a.doubleValue() == b.doubleValue();
        if (fieldVal instanceof Boolean || target instanceof Boolean)
            return String.valueOf(fieldVal).equalsIgnoreCase(String.valueOf(target));
        return String.valueOf(fieldVal).equals(String.valueOf(target));
    }

    /** Coerce a number or numeric string to Double; null if it isn't numeric. */
    private static Double asNumber(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof CharSequence s) {
            try { return Double.parseDouble(s.toString().trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Sleep up to {@code pollMs}, but not past the deadline. Returns true when the
     *  deadline has been reached (the caller should bail), false to keep looping. */
    private boolean sleepUntil(long deadlineNanos, long pollMs) {
        long now = System.nanoTime();
        if (now >= deadlineNanos) return true;
        long remainingMs = (deadlineNanos - now) / 1_000_000L;
        try {
            Thread.sleep(Math.max(1L, Math.min(pollMs, remainingMs)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting");
        }
        return false;
    }
}
