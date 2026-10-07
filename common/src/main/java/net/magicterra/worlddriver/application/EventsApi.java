package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import net.magicterra.worlddriver.api.ApiHandlers;
import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.ApiTimers;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.EventWire;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.methods.CommonTypes;
import net.magicterra.worlddriver.api.methods.EventCalls;
import net.magicterra.worlddriver.api.methods.EventCallsBinding;
import net.magicterra.worlddriver.api.methods.EventContracts;
import net.magicterra.worlddriver.api.methods.EventContracts.Emit;
import net.magicterra.worlddriver.api.methods.EventContracts.Emitted;
import net.magicterra.worlddriver.api.methods.EventContracts.Event;
import net.magicterra.worlddriver.api.methods.EventContracts.EventsOp;
import net.magicterra.worlddriver.api.methods.EventContracts.EventsReply;
import net.magicterra.worlddriver.api.methods.EventContracts.ListWatchers;
import net.magicterra.worlddriver.api.methods.EventContracts.Unwatch;
import net.magicterra.worlddriver.api.methods.EventContracts.Unwatched;
import net.magicterra.worlddriver.api.methods.EventContracts.Watch;
import net.magicterra.worlddriver.api.methods.EventContracts.Watchers;
import net.magicterra.worlddriver.api.methods.EventContracts.Watching;
import net.magicterra.worlddriver.model.DriverEvent;
import net.minecraft.core.BlockPos;

/**
 * The event stream's methods: {@code mc.observe.cursor}, {@code mc.observe.eventsSince} and
 * {@code mc.events}. The live push rides the transports (WebSocket frames in {@code RpcServer}, SSE in
 * {@code McpServer}); every event, a watcher's included, goes through {@link DriverApplication#emit},
 * so each reaches both transports and the replay buffer alike.
 *
 * <p>A watcher polls a pollable method through {@link Polling}: between polls it holds no thread, a
 * poll that is still running delays the next instead of overlapping it, and each poll has its own budget.
 */
public final class EventsApi implements EventCalls {
    /** One watcher poll; a read that takes longer counts as not satisfied. */
    private static final long WATCH_POLL_BUDGET_MS = 2_000;

    private final DriverApplication api;
    private final Map<Integer, Watcher> watchers = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    EventsApi(DriverApplication api) {
        this.api = api;
    }

    List<ApiMethod<?, ?>> methods() {
        return EventCallsBinding.methods(this);
    }

    @Override
    public CompletionStage<EventContracts.Cursor> cursor(CommonTypes.NoParams p, CallContext ctx) {
        return ApiHandlers.run(() -> new EventContracts.Cursor(api.observe.cursor()));
    }

    @Override
    public CompletionStage<EventContracts.Page> eventsSince(EventContracts.SinceParams p, CallContext ctx) {
        return ApiHandlers.run(() -> since(p));
    }

    @Override
    public CompletionStage<EventsReply> events(EventsOp op, CallContext ctx) {
        return ApiHandlers.run(() -> dispatch(op));
    }

    private EventContracts.Page since(EventContracts.SinceParams p) {
        Set<String> types = p.types().filter(l -> !l.isEmpty()).<Set<String>>map(LinkedHashSet::new).orElse(null);
        List<DriverEvent> page = api.observe.eventsSince(p.cursor(), types, p.limit());
        List<Event> events = new ArrayList<>(page.size());
        for (DriverEvent e : page) events.add(EventWire.dto(e));
        return new EventContracts.Page(events, page.isEmpty() ? p.cursor() : page.get(page.size() - 1).seq);
    }

    private EventsReply dispatch(EventsOp op) {
        return switch (op) {
            case Emit e -> emit(e);
            case Watch w -> watch(w);
            case Unwatch u -> unwatch(u);
            case ListWatchers l -> list();
        };
    }

    private Emitted emit(Emit e) {
        BlockPos pos = e.pos().map(p -> new BlockPos(p.x(), p.y(), p.z())).orElse(null);
        // The caller's value as given: an object stays an object on the wire.
        long n = api.emit(e.type(), pos, e.data().orElse(JsonValue.NULL));
        return new Emitted(n, e.type());
    }

    private Watching watch(Watch w) {
        // Checked here because a poll swallows its failures (a world detach must not kill the watcher),
        // which would otherwise turn a bad target into a watcher that silently never fires.
        Polling.Target target = Polling.target(api, w.invoke(), w.params());
        Watcher live = new Watcher(seq.incrementAndGet(), w, target);
        synchronized (watchers) {
            if (watchers.size() >= EventContracts.MAX_WATCHERS) {
                throw ApiException.of(ApiError.Code.SERVER_BUSY, EventContracts.MAX_WATCHERS
                        + " watchers are already running; unwatch one first (op:'list' shows them)");
            }
            watchers.put(live.id, live);
        }
        live.schedule();
        return new Watching(live.id, w.invoke(), w.emitAs(), w.everyMs());
    }

    private Unwatched unwatch(Unwatch u) {
        Watcher w = watchers.remove(u.id());
        if (w != null) w.stop();
        return new Unwatched(w != null);
    }

    private Watchers list() {
        List<EventContracts.Watcher> rows = new ArrayList<>();
        for (Watcher w : watchers.values()) {
            rows.add(new EventContracts.Watcher(w.id, w.spec.invoke(), w.spec.field(), w.spec.emitAs(),
                    w.spec.everyMs(), w.spec.once(), w.fired));
        }
        rows.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return new Watchers(rows, rows.size());
    }

    /** Cancel every watcher: called when the server detaches. */
    void clear() {
        for (Watcher w : watchers.values()) w.stop();
        watchers.clear();
    }

    private final class Watcher {
        final int id;
        final Watch spec;
        final Polling.Target target;
        volatile boolean lastOk;
        volatile int fired;
        volatile boolean stopped;
        volatile ScheduledFuture<?> next;

        Watcher(int id, Watch spec, Polling.Target target) {
            this.id = id;
            this.spec = spec;
            this.target = target;
        }

        /** The next poll, {@code everyMs} after the previous one ended. */
        void schedule() {
            if (!stopped) next = ApiTimers.schedule(() -> Polling.execute(this::poll), spec.everyMs());
        }

        void stop() {
            stopped = true;
            ScheduledFuture<?> n = next;
            if (n != null) n.cancel(false);
        }

        void poll() {
            if (stopped) return;
            target.read(api, Math.min(WATCH_POLL_BUDGET_MS, spec.everyMs())).whenComplete((result, failure) -> {
                try {
                    // A failed read (no world right now) is not satisfied, and the watcher lives on.
                    Optional<JsonValue> value = failure == null ? Polling.field(result, spec.field()) : Optional.empty();
                    boolean ok = failure == null && test(value.map(JsonValue::tree).orElse(null));
                    boolean rising = ok && !lastOk;
                    lastOk = ok;
                    if (rising && !stopped) fire(value);
                } finally {
                    schedule();
                }
            });
        }

        private boolean test(Object v) {
            if (spec.value().isPresent()) return WaitApi.looseEquals(v, spec.value().get().tree());
            if (spec.above().isPresent()) { Double d = number(v); return d != null && d > spec.above().get(); }
            if (spec.below().isPresent()) { Double d = number(v); return d != null && d < spec.below().get(); }
            return ApiSupport.isTruthy(v);
        }

        private void fire(Optional<JsonValue> value) {
            fired++;
            if (spec.once()) {
                stopped = true;
                watchers.remove(id);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("watch", id);
            data.put("invoke", spec.invoke());
            spec.field().ifPresent(f -> data.put("field", f));
            value.ifPresent(v -> data.put("value", v.tree()));
            api.emit(spec.emitAs(), null, JsonValue.of(data));
        }
    }

    private static Double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; } }
        return null;
    }
}
