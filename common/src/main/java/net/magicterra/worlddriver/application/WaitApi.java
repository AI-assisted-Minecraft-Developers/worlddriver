package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import net.magicterra.worlddriver.api.ApiErrors;
import net.magicterra.worlddriver.api.ApiHandlers;
import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.ApiRegistry;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.CancellationToken;
import net.magicterra.worlddriver.api.EventWire;
import net.magicterra.worlddriver.api.WaitPermits;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.methods.EventContracts.Event;
import net.magicterra.worlddriver.api.methods.WaitCalls;
import net.magicterra.worlddriver.api.methods.WaitCallsBinding;
import net.magicterra.worlddriver.api.methods.WaitContracts;
import net.magicterra.worlddriver.api.methods.WaitContracts.ConditionParams;
import net.magicterra.worlddriver.api.methods.WaitContracts.ConditionResult;
import net.magicterra.worlddriver.api.methods.WaitContracts.EventParams;
import net.magicterra.worlddriver.api.methods.WaitContracts.EventsArrived;
import net.magicterra.worlddriver.api.methods.WaitContracts.Outcome;
import net.magicterra.worlddriver.api.methods.WaitContracts.Started;
import net.magicterra.worlddriver.api.methods.WaitContracts.WaitDone;
import net.magicterra.worlddriver.api.methods.WaitContracts.WorldReady;
import net.magicterra.worlddriver.api.methods.WaitContracts.WorldReadyParams;
import net.magicterra.worlddriver.api.methods.WaitResultContract;
import net.magicterra.worlddriver.model.DriverEvent;

/**
 * {@code mc.wait.event}, {@code mc.wait.worldReady}, {@code mc.wait.condition} and the wait half of
 * {@code mc.wait.result}. A wait polls through {@link Polling}, so between two polls it holds no thread;
 * what it holds is a wait permit, the caller's for a foreground wait and this class's own for a
 * background one.
 *
 * <p><b>Background mode.</b> An LLM agent issues tool calls serially, so a foreground wait blinds it to
 * the live event stream for the whole budget. With {@code background:true} the reply is a
 * {@code waitId} at once; when the wait ends its reply is (a) kept for {@code mc.wait.result} and (b)
 * pushed as a {@code wait.done} event.
 */
public final class WaitApi implements WaitCalls {
    private final DriverApplication api;

    WaitApi(DriverApplication api) { this.api = api; }

    public static final int MAX_BACKGROUND = WaitContracts.MAX_BACKGROUND;

    /** Public because the WebSocket liveness window must outlast it. */
    public static final long MAX_BUDGET_MS = WaitContracts.MAX_TIMEOUT_MS;

    /** Margin kept off the caller's deadline so the answer is written before the caller gives up. */
    private static final long REPLY_MARGIN_MS = 250;

    /** One read of a condition; a target that takes longer has failed this poll, and the wait with it. */
    private static final long POLL_BUDGET_MS = 10_000;

    private static final WaitPermits BACKGROUND = WaitPermits.bounded(MAX_BACKGROUND);
    private static final AtomicLong WAIT_SEQ = new AtomicLong();
    private static final int MAX_RESULTS = 64;
    /**
     * waitId → how it ended. Bounded and insertion-ordered so the genuinely oldest is evicted: a hash
     * order could drop a result the agent has not fetched yet.
     */
    private static final Map<String, WaitDone> RESULTS = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, WaitDone> eldest) {
            return size() > MAX_RESULTS;
        }
    });
    /** waitIds still running: without it an id missing from RESULTS could be running or gone. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    List<ApiMethod<?, ?>> methods() {
        return WaitCallsBinding.methods(this);
    }

    /** How a wait runs once its request is checked: within {@code budgetMs}, until {@code cancelled}. */
    @FunctionalInterface
    private interface Body {
        CompletionStage<Outcome> start(long budgetMs, CancellationToken cancelled);
    }

    /**
     * Run {@code body} in the foreground or in the background. Everything that can refuse the request
     * runs before this, so a bad request is never acknowledged and then failed in the background.
     */
    private CompletionStage<Outcome> run(MethodSpec<?, Outcome> spec, String kind, boolean background,
                                         int timeoutMs, CallContext ctx, Body body) {
        if (!background) {
            ctx.requireBlockingAllowed(spec.name() + " without background:true");
            WaitPermits.Lease lease = ctx.waits().tryAcquire();
            if (lease == null) {
                throw ApiException.of(ApiError.Code.SERVER_BUSY,
                        "server busy: every wait slot is in use; retry, or pass background:true");
            }
            long budget = Math.min(timeoutMs, Math.max(0, ctx.remainingMillis() - REPLY_MARGIN_MS));
            try {
                return body.start(budget, ctx.requestCancelled()).whenComplete((r, t) -> lease.close());
            } catch (RuntimeException e) {
                lease.close();
                throw e;
            }
        }
        WaitPermits.Lease lease = BACKGROUND.tryAcquire();
        if (lease == null) {
            throw ApiException.of(ApiError.Code.SERVER_BUSY, "server busy: " + MAX_BACKGROUND
                    + " background waits are already running; wait for one to finish (mc.wait.result or the "
                    + "wait.done event) before starting another");
        }
        String waitId = kind + "-" + WAIT_SEQ.incrementAndGet();
        IN_FLIGHT.add(waitId);
        CompletionStage<Outcome> stage;
        try {
            stage = body.start(timeoutMs, CancellationToken.NEVER);
        } catch (RuntimeException e) {
            IN_FLIGHT.remove(waitId);
            lease.close();
            throw e;
        }
        stage.whenComplete((r, t) -> finish(spec, waitId, r, t, lease));
        return CompletableFuture.completedFuture(new Started(waitId));
    }

    private void finish(MethodSpec<?, Outcome> spec, String waitId, Outcome reply, Throwable failure,
                        WaitPermits.Lease lease) {
        WaitDone done;
        try {
            done = failure == null
                    ? new WaitDone(waitId, spec.name(), Optional.of(JsonValue.of(spec.output().encode(reply))), Optional.empty())
                    : failed(spec, waitId, failure);
        } catch (RuntimeException broken) {
            done = failed(spec, waitId, broken);
        }
        try {
            RESULTS.put(waitId, done);
        } finally {
            // After the put, so a concurrent result() never sees the id in neither place.
            IN_FLIGHT.remove(waitId);
            lease.close();
        }
        api.emit("wait.done", null, JsonValue.of(WaitContracts.WAIT_DONE.encode(done)));
    }

    private static WaitDone failed(MethodSpec<?, ?> spec, String waitId, Throwable t) {
        ApiError e = ApiRegistry.asApi(t).error();
        return new WaitDone(waitId, spec.name(), Optional.empty(), Optional.of(JsonValue.of(ApiErrors.toTree(e))));
    }

    /**
     * Read an action, or a background wait: {@code pending} while it runs, then how it ended (dropped
     * after reading unless {@code consume:false}). An id neither running nor kept is an error.
     */
    @Override
    public CompletionStage<WaitResultContract.Reply> result(WaitResultContract.Query query, CallContext ctx) {
        return ApiHandlers.run(() -> switch (query) {
            case WaitResultContract.ActionQuery a ->
                    new WaitResultContract.ActionReply(ActionRegistry.GLOBAL.snapshot(a.actionId()));
            case WaitResultContract.WaitQuery w -> waitResult(w.waitId(), w.consume());
        });
    }

    private WaitResultContract.Reply waitResult(String waitId, boolean consume) {
        // In-flight first: the finisher stores the result before it clears the flag.
        if (IN_FLIGHT.contains(waitId)) {
            return new WaitResultContract.WaitReply(waitId, true, Optional.empty(), Optional.empty());
        }
        WaitDone r = consume ? RESULTS.remove(waitId) : RESULTS.get(waitId);
        if (r == null) {
            throw ApiException.invalidParam("/waitId", ApiIssue.Code.INVALID_VALUE, "unknown waitId '" + waitId
                    + "': never started, already consumed, or evicted (only the " + MAX_RESULTS
                    + " newest unread results are kept)");
        }
        return new WaitResultContract.WaitReply(waitId, false, r.result(), r.error());
    }

    // ------------------------------------------------------------------ the three waits

    @Override
    public CompletionStage<Outcome> event(EventParams p, CallContext ctx) {
        api.level(); // needs a server; refused now rather than on the first poll
        Set<String> types = p.types().filter(l -> !l.isEmpty()).<Set<String>>map(LinkedHashSet::new).orElse(null);
        return run(WaitContracts.EVENT, "event", p.background(), p.timeoutMs(), ctx, (budget, cancelled) -> {
            long t0 = System.nanoTime();
            return Polling.until(budget, p.pollMs(), cancelled, () -> {
                List<DriverEvent> page = api.observe.eventsSince(p.cursor(), types, p.limit());
                if (page.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
                List<Event> events = new ArrayList<>(page.size());
                for (DriverEvent e : page) events.add(EventWire.dto(e));
                return CompletableFuture.completedFuture(Optional.of(
                        new EventsArrived(events, false, page.get(page.size() - 1).seq, msSince(t0))));
            }, () -> new EventsArrived(List.of(), true, p.cursor(), msSince(t0)));
        });
    }

    /** Needs a client but no server: the point is to wait until there is a world to attach to. */
    @Override
    public CompletionStage<Outcome> worldReady(WorldReadyParams p, CallContext ctx) {
        DriverApplication.requireClient();
        return run(WaitContracts.WORLD_READY, "worldReady", p.background(), p.timeoutMs(), ctx, (budget, cancelled) -> {
            long t0 = System.nanoTime();
            AtomicReference<Map<String, Object>> last = new AtomicReference<>();
            return Polling.until(budget, p.pollMs(), cancelled, () -> {
                Map<String, Object> info = DriverApplication.requireClient().screenInfo();
                last.set(info);
                boolean ready = Boolean.TRUE.equals(info.get("worldOpen")) && Boolean.TRUE.equals(info.get("hasPlayer"));
                return CompletableFuture.completedFuture(ready
                        ? Optional.of(new WorldReady(true, msSince(t0), infoOf(info))) : Optional.empty());
            }, () -> new WorldReady(false, msSince(t0), infoOf(last.get())));
        });
    }

    @Override
    public CompletionStage<Outcome> condition(ConditionParams p, CallContext ctx) {
        Polling.Target target = Polling.target(api, p.invoke(), p.params());
        return run(WaitContracts.CONDITION, "condition", p.background(), p.timeoutMs(), ctx, (budget, cancelled) -> {
            long t0 = System.nanoTime();
            AtomicReference<Optional<JsonValue>> last = new AtomicReference<>(Optional.empty());
            return Polling.until(budget, p.pollMs(), cancelled,
                    () -> target.read(api, Math.min(POLL_BUDGET_MS, Math.max(1, budget))).thenApply(result -> {
                        Optional<JsonValue> value = Polling.field(result, p.field());
                        last.set(value);
                        Object tree = value.map(JsonValue::tree).orElse(null);
                        boolean ok = p.value().isPresent() ? looseEquals(tree, p.value().get().tree()) : ApiSupport.isTruthy(tree);
                        return ok ? Optional.<Outcome>of(new ConditionResult(true, value, msSince(t0))) : Optional.<Outcome>empty();
                    }),
                    () -> new ConditionResult(false, last.get(), msSince(t0)));
        });
    }

    private static Optional<JsonValue> infoOf(Map<String, Object> info) {
        return info == null ? Optional.empty() : Optional.of(JsonValue.of(info));
    }

    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    /**
     * Equality across number types: a result tree holds Long, BigDecimal or Double where the target
     * may hold another, and an honest equal must not read as unsatisfied. Numbers compare by value; a
     * boolean or number also matches its string form, as targets written in a script sometimes are.
     */
    static boolean looseEquals(Object fieldVal, Object target) {
        if (Objects.equals(fieldVal, target)) return true;
        if (fieldVal == null || target == null) return false;
        Double a = asNumber(fieldVal), b = asNumber(target);
        if (a != null && b != null) return a.doubleValue() == b.doubleValue();
        if (fieldVal instanceof Boolean || target instanceof Boolean)
            return String.valueOf(fieldVal).equalsIgnoreCase(String.valueOf(target));
        return String.valueOf(fieldVal).equals(String.valueOf(target));
    }

    private static Double asNumber(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof CharSequence s) {
            try { return Double.parseDouble(s.toString().trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
