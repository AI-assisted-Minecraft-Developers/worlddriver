package net.magicterra.agent.api;

import net.magicterra.agent.model.AgentEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/* Ring buffer cap keeps memory bounded for long-running servers. Old events
 * roll off; eventsSince(cursor) on an out-of-window cursor returns whatever
 * is still retained. */

/**
 * Single source of truth for both in-JVM (Rhino) and external (TCP/MCP) callers.
 * Operates on the live ServerLevel, dispatching writes to the server thread.
 *
 * The {@code mc.system/observe/action/wait} verb groups are implemented in the
 * sibling {@link SystemApi} / {@link ObserveApi} / {@link ActionApi} /
 * {@link WaitApi} handlers (each holding a back-reference to this instance for
 * the shared server handle, event ring buffer, and server-thread hop); pure
 * parse/encode helpers live in {@link ApiSupport}. Every transport still routes
 * through {@link #route(String, Map)} — that is the single dispatch point, and
 * the only place new game-affecting behavior may be wired.
 */
public final class AgentApi {
    public final SystemApi system = new SystemApi(this);
    public final ObserveApi observe = new ObserveApi(this);
    public final ActionApi action = new ActionApi(this);
    public final WaitApi wait = new WaitApi(this);

    static final BlockPos ORIGIN = new BlockPos(0, 200, 0);

    static final int EVENT_BUFFER_CAP = 4096;

    // The command allow-list has been removed: {@code mc.action.runCommand} runs
    // any Brigadier verb at operator level. The MCP/RPC transports bind to
    // localhost, so this is a local/trusted-setup choice — re-add a verb filter
    // here if exposing the transports beyond the loopback interface.

    volatile MinecraftServer server;
    final ArrayDeque<AgentEvent> events = new ArrayDeque<>(EVENT_BUFFER_CAP);
    final Object eventsLock = new Object();
    final AtomicLong eventSeq = new AtomicLong();
    final long startNanos = java.lang.System.nanoTime();
    private final Map<String, Function<Map<String, Object>, Object>> routes = new HashMap<>();
    private volatile Function<Map<String, Object>, Object> scriptHandler;

    public AgentApi() {
        routes.put("mc.system.version", p -> system.version());
        routes.put("mc.system.testOrigin", p -> system.testOrigin());
        routes.put("mc.system.waitTicks", p -> system.waitTicks(num(p.get("ticks"))));
        // mc.observe.area merged into mc.query — call with {q:'blocks', center?, filter:{in_radius,type?}}.
        routes.put("mc.observe.cursor", p -> observe.cursor());
        routes.put("mc.observe.eventsSince", p -> {
            long cursor = (p.get("cursor") instanceof Number n0) ? n0.longValue() : 0L;
            Object t = p.get("types");
            Set<String> types = null;
            if (t instanceof List<?> l) {
                types = new LinkedHashSet<>();
                for (Object o : l) if (o instanceof String s) types.add(s);
                if (types.isEmpty()) types = null;
            }
            int limit = (p.get("limit") instanceof Number n) ? Math.max(1, Math.min(EVENT_BUFFER_CAP, n.intValue())) : 256;
            return observe.eventsSince(cursor, types, limit);
        });
        routes.put("mc.observe.player", p -> {
            // On a client MCP without a server attached (client connected to a
            // remote dedicated server), fall back to the client-side LocalPlayer
            // snapshot — the only player the client knows about. The optional
            // `name` parameter is ignored in that case; you can only observe
            // yourself client-side.
            if (server == null) {
                var c = clientOrNull();
                if (c != null) return c.observePlayer();
            }
            return observe.player((String) p.get("name"));
        });
        routes.put("mc.observe.container", p -> {
            // No `pos` → look at whatever container menu is open client-side
            // (player inventory, crafting table, the chest the server just
            // pushed). Useful so callers can read slots without first knowing
            // a world coordinate, and so the call works on a client MCP that
            // isn't attached to a server.
            if (!(p.get("pos") instanceof Map)) {
                var c = clientOrNull();
                if (c != null) return c.observeContainerMenu();
            }
            return observe.container(p);
        });
        routes.put("mc.wait.event",       p -> wait.event(p));
        routes.put("mc.wait.worldReady",  p -> wait.worldReady(p));
        routes.put("mc.wait.condition",   p -> wait.condition(p));
        // Action routes — optional `returnEvents:true` bracket-captures events emitted
        // during the call so a script doesn't need a separate cursor/eventsSince pair.
        // mc.action.placeBlock removed — single-block placement = mc.action.placeMany({blocks:[{pos,type}]}).
        routes.put("mc.action.runCommand", p -> withEvents(p, () -> action.runCommand((String) p.get("cmd"))));
        routes.put("mc.action.fill",       p -> withEvents(p, () -> action.fill(p)));
        routes.put("mc.action.placeMany",  p -> withEvents(p, () -> action.placeMany(p)));
        routes.put("mc.query", p -> {
            // Client-MCP fallback — server-side query() asserts attached server.
            // On a runClient JVM connected to a remote dedicated server, scan
            // ClientLevel via the client impl. Result rows match the server
            // schema. q='entities' includes numeric `id` for attackEntity.
            String q = (String) p.get("q");
            if (server == null && ("entities".equals(q) || "blocks".equals(q))) {
                var c = clientOrNull();
                if (c != null) {
                    Object filter = p.get("filter");
                    int r = "entities".equals(q) ? 16 : 4;
                    Boolean wantHostile = null;
                    String typeFilter = null;
                    if (filter instanceof Map<?, ?> fm) {
                        Object rad = fm.get("in_radius");
                        if (rad instanceof Number rn) r = rn.intValue();
                        Object h = fm.get("is_hostile");
                        if (h instanceof Boolean hb) wantHostile = hb;
                        Object tv = fm.get("type");
                        if (tv instanceof String s && !s.isBlank()) typeFilter = s;
                    }
                    Double cx = null, cy = null, cz = null;
                    Object center = p.get("center");
                    if (center instanceof Map<?, ?> cm) {
                        Object xo = cm.get("x"), yo = cm.get("y"), zo = cm.get("z");
                        if (xo instanceof Number nx && yo instanceof Number ny && zo instanceof Number nz) {
                            cx = nx.doubleValue(); cy = ny.doubleValue(); cz = nz.doubleValue();
                        }
                    }
                    if ("entities".equals(q)) {
                        return c.queryEntities(r, cx, cy, cz, wantHostile);
                    } else {
                        // q='blocks' — reuse observeArea client path; unwrap to
                        // match the server's flat-array shape.
                        java.util.Set<String> ids = (typeFilter == null) ? null
                                : new LinkedHashSet<>(java.util.Set.of(typeFilter));
                        Map<String, Object> wrapped = c.observeArea(r, cx, cy, cz, ids);
                        Object blocks = wrapped.get("blocks");
                        return (blocks instanceof List) ? blocks : List.of();
                    }
                }
            }
            return query(QueryParams.from(p));
        });

        // Client routes. Resolution is deferred to call time so the broker's
        // registration ordering doesn't matter; on a dedicated server the impl
        // is never bound and these throw a recognizable IllegalStateException
        // which the JS test harness catches to skip gracefully.
        routes.put("mc.client.screen.tree",          p -> requireClient().screenTree());
        routes.put("mc.client.screen.info",          p -> requireClient().screenInfo());
        // Inventory / pause are reachable via mc.client.input.key{key:'E'} /
        // {key:'ESCAPE'} — same vanilla path. No separate tool needed.
        routes.put("mc.client.chat.send",            p -> requireClient().chatSend(
                (String) p.get("text"),
                p.get("awaitReplyMs") instanceof Number n ? n.intValue() : 0));
        routes.put("mc.client.chat.history",         p -> requireClient().chatHistory(
                p.get("limit") instanceof Number ln ? ln.intValue() : 50,
                p.get("sinceSeq") instanceof Number sn ? sn.intValue() : 0));
        routes.put("mc.client.overlays",             p -> requireClient().overlays(
                !(p.get("tutorial") instanceof Boolean tb) || tb,
                !(p.get("toasts") instanceof Boolean tt) || tt));
        routes.put("mc.client.screen.close",         p -> requireClient().closeScreen());
        routes.put("mc.client.input.click",          p -> requireClient().click(
                numD(p.get("x")), numD(p.get("y")), num(p.getOrDefault("button", 0))));
        routes.put("mc.client.input.slotClick",      p -> requireClient().slotClick(
                num(p.get("slot")), num(p.getOrDefault("button", 0)),
                (String) p.getOrDefault("type", "pickup")));
        routes.put("mc.client.input.mouseMove",      p -> requireClient().mouseMove(
                numD(p.get("x")), numD(p.get("y"))));
        routes.put("mc.client.input.setHotbarSlot",  p -> requireClient().setHotbarSlot(num(p.get("slot"))));
        routes.put("mc.client.input.typeText",       p -> requireClient().typeText((String) p.get("text")));
        routes.put("mc.client.input.key",            p -> requireClient().key(
                (String) p.get("key"), (String) p.get("action")));
        routes.put("mc.client.screenshot",           p -> requireClient().screenshot(p));

        // Bot routes — client-side; unavailable on dedicated server.
        // Async actions accept an optional `awaitMs` that, when set, makes the
        // route block on bot.status until the named slot goes idle (or times
        // out), folding the final status snapshot into the response.
        routes.put("mc.bot.goto",      p -> awaitable(p, "goto",    requireBot()::mcGoto));
        routes.put("mc.bot.mine",      p -> awaitable(p, "mine",    requireBot()::mine));
        routes.put("mc.bot.build",     p -> awaitable(p, "builder", requireBot()::build));
        routes.put("mc.bot.clearArea", p -> awaitable(p, "builder", requireBot()::clearArea));
        routes.put("mc.bot.follow",    p -> awaitable(p, "follow",  requireBot()::follow));
        routes.put("mc.bot.explore",   p -> awaitable(p, "explore", requireBot()::explore));
        routes.put("mc.bot.runAway",   p -> awaitable(p, "runAway", requireBot()::runAway));
        routes.put("mc.bot.lookAt",    p -> requireBot().lookAt(p));
        // mc.bot.useItem dispatches based on params: pass `pos` to use the held
        // item ON a block face (place / bone-meal / shears / etc.); omit pos to
        // use the item in mid-air (eat / draw bow / throw snowball).
        routes.put("mc.bot.useItem",     p -> (p != null && p.get("pos") != null)
                ? requireBot().useItemOn(p)
                : requireBot().useItem(p));
        routes.put("mc.bot.attackEntity",p -> requireBot().attackEntity(p));
        // pause/resume are reachable through mc.bot.setting{paused:bool} —
        // same vol-toggle handler in BotApiImpl.setting absorbs both.
        routes.put("mc.bot.cancel",    p -> requireBot().cancel(p));
        routes.put("mc.bot.status",    p -> requireBot().status());
        routes.put("mc.bot.setting",   p -> requireBot().setting(p));
        routes.put("mc.bot.waypoint",  p -> requireBot().waypoint(p));
        routes.put("mc.bot.farm",      p -> awaitable(p, "builder", requireBot()::farm));
        routes.put("mc.bot.sleep",     p -> awaitable(p, "goto",    requireBot()::sleep));
        routes.put("mc.bot.construct", p -> awaitable(p, "builder", requireBot()::construct));
        routes.put("mc.bot.elytraFly", p -> awaitable(p, "elytra",  requireBot()::elytraFly));

        // Script evaluation. Bound at startup via setScriptHandler() to avoid
        // making AgentApi depend on Rhino classes directly — keeps the api/
        // package free of the script/ package.
        routes.put("mc.script.eval", p -> {
            Function<Map<String, Object>, Object> h = scriptHandler;
            if (h == null) throw new IllegalStateException("mc.script.eval not available (no evaluator bound)");
            return h.apply(p);
        });
    }

    /**
     * Bind the implementation of {@code mc.script.eval}. Called from the
     * platform bootstrap so the api package stays free of Rhino.
     */
    public void setScriptHandler(Function<Map<String, Object>, Object> handler) {
        this.scriptHandler = handler;
    }

    static net.magicterra.agent.client.ClientAgentApi requireClient() {
        net.magicterra.agent.client.ClientAgentApi c = net.magicterra.agent.client.ClientHooks.impl();
        if (c == null) throw new IllegalStateException("mc.client.* not available (no client registered)");
        return c;
    }

    private static net.magicterra.agent.client.ClientAgentApi clientOrNull() {
        return net.magicterra.agent.client.ClientHooks.impl();
    }

    private static net.magicterra.agent.bot.BotApi requireBot() {
        net.magicterra.agent.bot.BotApi b = net.magicterra.agent.bot.BotHooks.impl();
        if (b == null) throw new IllegalStateException("mc.bot.* not available (client only; bot impl not registered)");
        return b;
    }

    public void attachServer(MinecraftServer s) {
        this.server = s;
    }

    public void detachServer() {
        this.server = null;
        synchronized (eventsLock) {
            events.clear();
            eventSeq.set(0);
        }
    }

    public Object route(String method, Map<String, Object> params) {
        Function<Map<String, Object>, Object> fn = routes.get(method);
        if (fn == null) throw new IllegalArgumentException("unknown method: " + method);
        return fn.apply(params == null ? Map.of() : params);
    }

    @SuppressWarnings("unchecked")
    public String invokeJson(String method, String paramsJson) {
        Map<String, Object> params;
        if (paramsJson == null || paramsJson.isBlank() || paramsJson.equals("null")) {
            params = Map.of();
        } else {
            Object decoded = net.magicterra.agent.rpc.JsonCodec.decode(paramsJson);
            params = (decoded instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
        }
        Object result = route(method, params);
        return net.magicterra.agent.rpc.JsonCodec.encode(result);
    }

    public Set<String> methods() { return Collections.unmodifiableSet(routes.keySet()); }

    /**
     * Lays down a deterministic test arena: 5x5 stones at y=200, oak log at y=201,
     * one cow at (3,201,0), one sheep at (-3,201,2). Clears surrounding air first
     * so {@code /agent test} is idempotent.
     */
    public void seedTestArea() {
        ServerLevel level = level();
        onServerThread(() -> {
            BlockPos origin = ORIGIN;
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int dx = -4; dx <= 4; dx++)
                for (int dy = -1; dy <= 5; dy++)
                    for (int dz = -4; dz <= 4; dz++)
                        level.setBlockAndUpdate(origin.offset(dx, dy, dz), air);
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    level.setBlockAndUpdate(origin.offset(dx, 0, dz), stone);
            level.setBlockAndUpdate(origin.offset(0, 1, 0), Blocks.OAK_LOG.defaultBlockState());

            // Despawn every non-player entity in the test box. Cow/Sheep from a
            // prior run get persisted by setPersistenceRequired() and reload with
            // the chunk; nuking them all keeps the entity count deterministic.
            AABB clearBox = new AABB(origin).inflate(20.0);
            for (Entity e : level.getEntities((Entity) null, clearBox)) {
                if (e instanceof Player) continue;
                e.discard();
            }
            // Place both animals on top of the 5x5 stone plane (x,z ∈ [-2,2]),
            // not outside it — otherwise gravity drops them out of the query AABB
            // and length-2 assertions in 05_query become flaky after a few ticks.
            Cow cow = EntityType.COW.create(level);
            if (cow != null) {
                cow.moveTo(ORIGIN.getX() + 1 + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0f, 0f);
                cow.setPersistenceRequired();
                level.addFreshEntity(cow);
            }
            Sheep sheep = EntityType.SHEEP.create(level);
            if (sheep != null) {
                sheep.moveTo(ORIGIN.getX() - 1 + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 1 + 0.5, 0f, 0f);
                sheep.setPersistenceRequired();
                level.addFreshEntity(sheep);
            }
            synchronized (eventsLock) {
                events.clear();
                eventSeq.set(0);
            }
            return null;
        });
    }

    /** Platform event hooks feed natural (non-API) block changes into the event stream. */
    public void emitExternal(String type, BlockPos pos, String data) {
        emit(type, pos, data);
    }

    void emit(String type, BlockPos pos, String data) {
        AgentEvent e = new AgentEvent(eventSeq.incrementAndGet(), type, pos, data);
        synchronized (eventsLock) {
            if (events.size() >= EVENT_BUFFER_CAP) events.pollFirst();
            events.addLast(e);
        }
    }

    ServerLevel level() {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("AgentApi not attached to a server");
        return s.overworld();
    }

    /** Default budget for waiting on a server-tick hop. Lowered from 30s so a
     *  stuck tick surfaces as a clear error within ~8s instead of stalling every
     *  MCP / RPC call for half a minute. Override with
     *  {@code -Dagent.serverThreadTimeoutMs=N}. */
    private static final long SERVER_THREAD_TIMEOUT_MS =
            Long.getLong("agent.serverThreadTimeoutMs", 8_000L);

    <T> T onServerThread(Supplier<T> task) {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("AgentApi not attached to a server");
        if (s.isSameThread()) return task.get();
        CompletableFuture<T> f = new CompletableFuture<>();
        s.execute(() -> {
            try { f.complete(task.get()); }
            catch (Throwable e) { f.completeExceptionally(e); }
        });
        try {
            return f.get(SERVER_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("server thread did not run task within "
                    + SERVER_THREAD_TIMEOUT_MS + "ms (server busy or paused)");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting on server thread");
        }
    }

    private int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private double numD(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }

    /**
     * Bot async-route wrapper. If {@code params.awaitMs} is set, invoke the
     * underlying impl, then poll {@code mc.bot.status} until {@code <slot>.active}
     * becomes falsy (success), the slot's {@code lastError} appears (failure), or
     * the deadline elapses (timeout). Folds the started-response and the final
     * status snapshot into one merged Map. Without {@code awaitMs}, behaves
     * identically to the previous fire-and-forget call.
     *
     * The {@code awaitMs} key is stripped from params before calling the impl so
     * existing impls that don't know about it stay happy.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitable(Map<String, Object> params, String slot,
                                          Function<Map<String, Object>, Map<String, Object>> impl) {
        Object awaitObj = params.get("awaitMs");
        if (!(awaitObj instanceof Number)) {
            return impl.apply(params);
        }
        long budgetMs = ApiSupport.clamp(((Number) awaitObj).longValue(), 1L, 600_000L);
        Map<String, Object> innerParams = new LinkedHashMap<>(params);
        innerParams.remove("awaitMs");
        Map<String, Object> started = impl.apply(innerParams);

        // If the impl rejected (ok:false / started:false), don't bother polling.
        Object startedFlag = started.get("started");
        if (Boolean.FALSE.equals(startedFlag) || Boolean.FALSE.equals(started.get("ok"))) {
            return started;
        }

        long t0 = java.lang.System.nanoTime();
        long deadlineNanos = t0 + budgetMs * 1_000_000L;
        long pollMs = 200L;
        Map<String, Object> finalStatus = null;
        boolean completed = false;
        while (true) {
            Map<String, Object> status = requireBot().status();
            finalStatus = status;
            Object slotObj = status.get(slot);
            if (slotObj instanceof Map<?, ?> slotMap) {
                Object active = slotMap.get("active");
                if (!Boolean.TRUE.equals(active)) {
                    completed = true;
                    break;
                }
            } else {
                // Slot disappeared — treat as completed.
                completed = true;
                break;
            }
            if (sleepUntilNanos(deadlineNanos, pollMs)) break;
        }
        long ms = (java.lang.System.nanoTime() - t0) / 1_000_000L;
        Map<String, Object> out = new LinkedHashMap<>(started);
        out.put("awaited", true);
        out.put("completed", completed);
        out.put("ms", ms);
        if (finalStatus != null) {
            Object slotObj = finalStatus.get(slot);
            if (slotObj instanceof Map<?, ?>) {
                out.put("status", slotObj);
            }
        }
        return out;
    }

    /**
     * Action-route wrapper. When {@code params.returnEvents == true}, capture the
     * event cursor before {@code impl} runs and append any new events to the
     * response under the {@code events} key. Saves the
     * cursor→action→eventsSince round-trip an MCP client would otherwise need.
     *
     * Events are filtered to {@code seq > cursorBefore} — i.e. anything emitted
     * during the impl call. Concurrent emissions from unrelated work would also
     * leak in, but action handlers run on the server thread so the window is
     * narrow in practice.
     */
    private Object withEvents(Map<String, Object> params, Supplier<Map<String, Object>> impl) {
        if (!Boolean.TRUE.equals(params.get("returnEvents"))) {
            return impl.get();
        }
        long cursorBefore = eventSeq.get();
        Map<String, Object> result = impl.get();
        List<AgentEvent> page = observe.eventsSince(cursorBefore, null, EVENT_BUFFER_CAP);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("events", page);
        return out;
    }

    /** Sleep up to pollMs without exceeding deadlineNanos. Returns true when the
     *  deadline has been reached (caller should bail). */
    private static boolean sleepUntilNanos(long deadlineNanos, long pollMs) {
        long now = java.lang.System.nanoTime();
        if (now >= deadlineNanos) return true;
        long remainingMs = (deadlineNanos - now) / 1_000_000L;
        try {
            Thread.sleep(Math.max(1L, Math.min(pollMs, remainingMs)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
        return false;
    }

    // ---------------- Query DSL ----------------
    public Object query(QueryParams p) {
        ServerLevel level = level();
        BlockPos centerPos = (p.center != null) ? p.center : ORIGIN;
        if ("blocks".equals(p.q)) {
            int r = Math.max(0, Math.min(64, num(p.filter.get("in_radius"))));
            // filter.type lets callers restrict to one block id (absorbed from
            // the former mc.observe.area). Server reads the block, then skips
            // anything that doesn't match.
            Object typeFilter = p.filter.get("type");
            String typeFilterId = (typeFilter instanceof String s && !s.isBlank()) ? s : null;
            return onServerThread(() -> {
                List<Map<String, Object>> out = new ArrayList<>();
                BlockPos center = centerPos;
                for (int dx = -r; dx <= r; dx++)
                    for (int dy = -r; dy <= r; dy++)
                        for (int dz = -r; dz <= r; dz++) {
                            BlockPos bp = center.offset(dx, dy, dz);
                            BlockState st = level.getBlockState(bp);
                            if (st.isAir()) continue;
                            String id = ApiSupport.blockId(st);
                            if (typeFilterId != null && !typeFilterId.equals(id)) continue;
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("pos", new BlockPos(bp.getX(), bp.getY(), bp.getZ()));
                            row.put("type", id);
                            out.add(project(row, p.select));
                        }
                return (Object) out;
            });
        } else if ("entities".equals(p.q)) {
            int r = Math.max(0, Math.min(128, num(p.filter.getOrDefault("in_radius", 16))));
            Boolean wantHostile = (p.filter.get("is_hostile") instanceof Boolean b) ? b : null;
            return onServerThread(() -> {
                List<Map<String, Object>> out = new ArrayList<>();
                BlockPos center = centerPos;
                AABB box = new AABB(center).inflate(r);
                for (Entity e : level.getEntities((Entity) null, box)) {
                    boolean hostile = e instanceof Enemy;
                    if (wantHostile != null && wantHostile != hostile) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("pos", new BlockPos(e.blockPosition().getX(), e.blockPosition().getY(), e.blockPosition().getZ()));
                    row.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                    if (e instanceof LivingEntity le) row.put("health", (double) le.getHealth());
                    out.add(project(row, p.select));
                }
                return (Object) out;
            });
        }
        return List.of();
    }

    private Map<String, Object> project(Map<String, Object> row, List<String> select) {
        if (select == null || select.isEmpty()) return row;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : select) if (row.containsKey(k)) out.put(k, row.get(k));
        return out;
    }
}
