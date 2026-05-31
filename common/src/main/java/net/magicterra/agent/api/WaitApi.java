package net.magicterra.agent.api;

import net.magicterra.agent.model.AgentEvent;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * {@code mc.wait.*} long-polling handlers, extracted from {@code AgentApi}. All
 * wait methods block the calling thread (HTTP / WS worker, or script-eval pool)
 * — never the server thread — and bound the wait by a hard wall-clock deadline
 * so a stuck condition can't hang a client. Inner work re-uses the same routes
 * everyone else calls via an {@link AgentApi} back-reference, so policy stays in
 * one place (e.g., {@code observe.container} requires server; {@code wait.condition}
 * inherits that requirement transparently).
 */
public final class WaitApi {
    private final AgentApi api;
    WaitApi(AgentApi api) { this.api = api; }

    /** Maximum wall-clock budget any wait.* tool will accept, in ms. Keeps a
     *  runaway script from squatting on a worker thread for hours. */
    private static final long MAX_BUDGET_MS = 120_000L;

    /**
     * Long-poll for the next event(s) since {@code cursor} matching {@code types}.
     * Returns as soon as at least one event is available, or {@code {timedOut:true}}
     * on deadline. {@code cursor} in the response is the seq of the last event
     * returned — pass it back on the next call to chain.
     */
    public Map<String, Object> event(Map<String, Object> p) {
        api.level();
        long cursor = ApiSupport.numL(p.get("cursor"));
        Set<String> types = null;
        if (p.get("types") instanceof List<?> l) {
            types = new LinkedHashSet<>();
            for (Object o : l) if (o instanceof String s) types.add(s);
            if (types.isEmpty()) types = null;
        }
        int limit = (p.get("limit") instanceof Number n) ? Math.max(1, Math.min(256, n.intValue())) : 32;
        long budgetMs = ApiSupport.clamp(ApiSupport.numL(p.getOrDefault("timeoutMs", 5000L)), 100L, MAX_BUDGET_MS);
        long pollMs = ApiSupport.clamp(ApiSupport.numL(p.getOrDefault("pollMs", 100L)), 50L, 2000L);

        long t0 = System.nanoTime();
        long deadlineNanos = t0 + budgetMs * 1_000_000L;
        Set<String> finalTypes = types;
        while (true) {
            List<AgentEvent> page = api.observe.eventsSince(cursor, finalTypes, limit);
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
    }

    /**
     * Wait until the client is fully in-world (a player exists and a world is
     * loaded). Does NOT require {@code AgentApi} to be attached to a server —
     * the whole point is to wait until that happens. On dedicated server (no
     * client bound) throws "mc.client.* not available" immediately.
     */
    public Map<String, Object> worldReady(Map<String, Object> p) {
        long budgetMs = ApiSupport.clamp(ApiSupport.numL(p.getOrDefault("timeoutMs", 30_000L)), 100L, MAX_BUDGET_MS);
        long pollMs = ApiSupport.clamp(ApiSupport.numL(p.getOrDefault("pollMs", 250L)), 50L, 2000L);
        long t0 = System.nanoTime();
        long deadlineNanos = t0 + budgetMs * 1_000_000L;
        Map<String, Object> info = null;
        while (true) {
            info = AgentApi.requireClient().screenInfo();
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
    }

    /**
     * Generic poll-until-truthy. Calls {@code invoke}/{@code params} every {@code pollMs};
     * walks {@code field} (dotted path) into the result; succeeds when the value is truthy
     * (or, if {@code value} is provided, deep-equals it). Use for things like:
     *
     *   {invoke:"mc.observe.container", params:{pos:{...}}, field:"slots.2.count"} —
     *   wait for a furnace's output slot to be non-empty.
     */
    public Map<String, Object> condition(Map<String, Object> p) {
        String method = (String) p.get("invoke");
        if (method == null || method.isBlank()) throw new IllegalArgumentException("invoke method name required");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (p.get("params") instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
        String field = (String) p.get("field");
        boolean strict = p.containsKey("value");
        Object target = p.get("value");
        long budgetMs = ApiSupport.clamp(ApiSupport.numL(p.getOrDefault("timeoutMs", 30_000L)), 100L, MAX_BUDGET_MS);
        long pollMs = ApiSupport.clamp(ApiSupport.numL(p.getOrDefault("pollMs", 500L)), 50L, 5000L);

        long t0 = System.nanoTime();
        long deadlineNanos = t0 + budgetMs * 1_000_000L;
        Object lastValue = null;
        while (true) {
            Object result = api.route(method, params);
            Object value = (field == null || field.isEmpty()) ? result : ApiSupport.walkPath(result, field);
            lastValue = value;
            boolean ok = strict ? Objects.equals(value, target) : ApiSupport.isTruthy(value);
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
