package net.magicterra.agent.api;

import net.magicterra.agent.model.Params;
import net.magicterra.agent.rpc.JsonCodec;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code mc.events} handler — the server-side surface of the driver→agent event
 * channel (the push channel itself lives in the transports: WebSocket frames in
 * {@code RpcServer}, an SSE stream in {@code McpServer}). Every event still funnels
 * through {@link AgentApi#emit}, so a watcher firing here reaches subscribers on
 * both transports exactly like a block/chat/death event does — single source of
 * truth.
 *
 * Ops ({@code op}):
 *   - {@code emit}  — inject a custom event into the stream ({@code type}, optional
 *     {@code data}/{@code pos}). The "自定义条件满足 / manual" path: an agent that
 *     computed its own condition can publish it for other subscribers.
 *   - {@code watch} — register a server-side rising-edge watcher. Polls a route
 *     ({@code invoke}/{@code params}) every {@code everyMs}, walks {@code field}
 *     into the result, and the first tick the predicate flips false→true it emits
 *     {@code emitAs} (default {@code condition.met}). This is the automatic
 *     "自定义条件满足" path — e.g. watch {@code mc.observe.player} field {@code health}
 *     {@code below} 6 to get pushed a low-health alert. {@code once:true} self-cancels
 *     after the first fire.
 *   - {@code unwatch} — cancel a watcher by {@code id}.
 *   - {@code list} — list active watchers.
 *
 * Predicate (checked against the walked field value, in order): {@code value}
 * (deep-equals), {@code above} (numeric &gt;), {@code below} (numeric &lt;),
 * otherwise JS-truthy.
 */
public final class EventsApi {
    private final AgentApi api;
    private final ScheduledExecutorService exec;
    private final Map<Integer, Watcher> watchers = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    EventsApi(AgentApi api) {
        this.api = api;
        this.exec = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "agent-event-watch-" + System.identityHashCode(r));
            t.setDaemon(true);
            return t;
        });
    }

    public Object dispatch(Map<String, Object> raw) {
        Params p = Params.of(raw);
        String op = p.getString("op", "");
        switch (op) {
            case "emit":    return emit(p);
            case "watch":   return watch(p);
            case "unwatch": return unwatch(p);
            case "list":    return list();
            default:
                throw new IllegalArgumentException(
                        "mc.events: op must be one of emit|watch|unwatch|list (got '" + op + "')");
        }
    }

    // ---- emit -------------------------------------------------------------
    private Map<String, Object> emit(Params p) {
        String type = p.getString("type");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("emit: type required");
        BlockPos pos = p.getPos("pos"); // may be null
        // Pass the caller's value through untouched — a Map stays a Map and reaches
        // the wire as an object. It used to be flattened to a JSON string here, so a
        // script that emitted {hello:'world'} read it back as the text
        // "{\"hello\":\"world\"}" and had to JSON.parse its own payload.
        // Absent data stays "" rather than becoming null, so events emitted without
        // a payload keep the shape every existing consumer already sees.
        Object data = p.get("data");
        long n = api.emit(type, pos, data == null ? "" : data);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("seq", n);
        out.put("type", type);
        return out;
    }

    // ---- watch ------------------------------------------------------------
    private Map<String, Object> watch(Params p) {
        String invoke = p.getString("invoke");
        if (invoke == null || invoke.isBlank()) throw new IllegalArgumentException("watch: invoke route name required");
        Map<String, Object> params = p.getMap("params");
        String field = p.getString("field");
        String emitAs = p.getString("emitAs", "condition.met");
        long everyMs = p.getLongClamped("everyMs", 1000L, 200L, 60_000L);
        boolean once = Boolean.TRUE.equals(p.get("once"));

        Watcher w = new Watcher();
        w.id = seq.incrementAndGet();
        w.invoke = invoke;
        w.params = params;
        w.field = field;
        w.emitAs = emitAs;
        w.everyMs = everyMs;
        w.once = once;
        w.hasValue = p.has("value");
        w.targetValue = p.get("value");
        w.above = p.has("above") ? num(p.get("above")) : null;
        w.below = p.has("below") ? num(p.get("below")) : null;
        watchers.put(w.id, w);
        w.future = exec.scheduleWithFixedDelay(() -> tick(w), everyMs, everyMs, TimeUnit.MILLISECONDS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("watching", true);
        out.put("id", w.id);
        out.put("emitAs", emitAs);
        out.put("everyMs", everyMs);
        return out;
    }

    private void tick(Watcher w) {
        Object value;
        try {
            Object result = api.route(w.invoke, w.params);
            value = (w.field == null || w.field.isEmpty()) ? result : ApiSupport.walkPath(result, w.field);
        } catch (Throwable t) {
            // Route not ready (e.g. world detached) — treat as not-satisfied and
            // keep the watcher alive; never let a poll throw out of the executor.
            w.lastOk = false;
            return;
        }
        boolean ok = test(w, value);
        boolean rising = ok && !w.lastOk;
        w.lastOk = ok;
        if (!rising) return;
        w.fired++;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("watch", w.id);
        data.put("invoke", w.invoke);
        if (w.field != null) data.put("field", w.field);
        data.put("value", value);
        api.emit(w.emitAs, null, data);
        if (w.once) {
            watchers.remove(w.id);
            if (w.future != null) w.future.cancel(false);
        }
    }

    private boolean test(Watcher w, Object value) {
        if (w.hasValue) return Objects.equals(value, w.targetValue);
        if (w.above != null) { Double v = num(value); return v != null && v > w.above; }
        if (w.below != null) { Double v = num(value); return v != null && v < w.below; }
        return ApiSupport.isTruthy(value);
    }

    // ---- unwatch / list ---------------------------------------------------
    private Map<String, Object> unwatch(Params p) {
        int id = (p.get("id") instanceof Number n) ? n.intValue() : -1;
        Watcher w = watchers.remove(id);
        if (w != null && w.future != null) w.future.cancel(false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("removed", w != null);
        return out;
    }

    private Map<String, Object> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Watcher w : watchers.values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", w.id);
            r.put("invoke", w.invoke);
            if (w.field != null) r.put("field", w.field);
            r.put("emitAs", w.emitAs);
            r.put("everyMs", w.everyMs);
            r.put("once", w.once);
            r.put("fired", w.fired);
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("watchers", rows);
        out.put("count", rows.size());
        return out;
    }

    /** Cancel every watcher — called when the server detaches (world unload). */
    void clear() {
        for (Watcher w : watchers.values()) {
            if (w.future != null) w.future.cancel(false);
        }
        watchers.clear();
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; } }
        return null;
    }

    private static final class Watcher {
        int id;
        String invoke;
        Map<String, Object> params;
        String field;
        String emitAs;
        long everyMs;
        boolean once;
        boolean hasValue;
        Object targetValue;
        Double above;
        Double below;
        volatile boolean lastOk;
        volatile int fired;
        ScheduledFuture<?> future;
    }
}
