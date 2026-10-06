package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.WorldCell;
import net.magicterra.worlddriver.api.QueryParams;
import net.magicterra.worlddriver.api.ServerThreadGuard;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.magicterra.worlddriver.client.ClientHooks;
import net.magicterra.worlddriver.client.ClientDriverApi;
import net.magicterra.worlddriver.bot.BotApi;
import java.util.Set;
import net.magicterra.worlddriver.bot.BotHooks;

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
public final class DriverApplication extends DriverApi {
    public final SystemApi system = new SystemApi(this);
    public final ObserveApi observe = new ObserveApi(this);
    public final ActionApi action = new ActionApi(this);
    public final WaitApi wait = new WaitApi(this);
    public final WorldApi world = new WorldApi(this);
    public final RecipeApi recipe = new RecipeApi(this);
    public final EventsApi eventsApi = new EventsApi(this);

    static final BlockPos ORIGIN = new BlockPos(0, 200, 0);

    static final int EVENT_BUFFER_CAP = 4096;

    // The command allow-list has been removed: {@code mc.action.runCommand} runs
    // any Brigadier verb at operator level. The MCP/RPC transports bind to
    // localhost, so this is a local/trusted-setup choice — re-add a verb filter
    // here if exposing the transports beyond the loopback interface.

    volatile MinecraftServer server;
    final ArrayDeque<DriverEvent> events = new ArrayDeque<>(EVENT_BUFFER_CAP);
    final Object eventsLock = new Object();
    final AtomicLong eventSeq = new AtomicLong();
    final long startNanos = System.nanoTime();

    /** Live push listeners (the WebSocket and SSE transports register here). Every
     *  {@link #emit} hands the new event to each, off the caller's thread via
     *  {@link #eventDispatch} so neither the server tick nor a client tick ever
     *  blocks on socket I/O. Copy-on-write: registration is rare, iteration frequent. */
    private final List<Consumer<DriverEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService eventDispatch = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-event-dispatch");
        t.setDaemon(true);
        return t;
    });
    private volatile ScriptHost scriptHost;

    public DriverApplication() {
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
        routes.put("mc.observe.threats", p -> {
            // Client-only sensing (entity render set + creeper swell + projectile
            // velocity are client state). No server-side equivalent; returns empty
            // when no client is attached (e.g. dedicated-server GameTest).
            int radius = p.get("radius") instanceof Number n
                    ? Math.max(1, Math.min(64, n.intValue())) : 24;
            var c = clientOrNull();
            if (c != null) return c.observeThreats(radius);
            return Map.of("threats", List.of(), "incomingProjectiles", List.of());
        });
        routes.put("mc.observe.boss", p -> {
            // Phase G boss sensing — client-only (reads the ClientLevel entity set,
            // dragon phaseManager, wither invul ticks). Returns absent on a
            // dedicated server. Default radius 64 covers the End-pillar crystal ring.
            int radius = p.get("radius") instanceof Number n
                    ? Math.max(1, Math.min(256, n.intValue())) : 64;
            var c = clientOrNull();
            if (c != null) return c.observeBoss(radius);
            return Map.of("present", false, "crystals", List.of());
        });
        // Server-side hazard scene: HazardField + SurvivalFacts + optional ASCII map.
        // Works headless in GameTest; no client required.
        routes.put("mc.observe.scene", p -> observe.scene(p));
        // ASCII spatial map (top-down heightmap or vertical cross-section) — a
        // compact, glanceable substitute for parsing block + threat JSON when
        // making fast tactical/flee decisions. Server-side (works headless).
        routes.put("mc.observe.map", p -> observe.map(p));
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
        // World snapshot/restore — deterministic test setup/teardown. restore
        // emits a world.restore event, so it honors returnEvents like the action.* group.
        // Read-only single-cell inspection (blockstate + light + optional BE NBT)
        // — the verify half of build→verify (docs/archive/feedback/2026-06-08).
        routes.put("mc.world.block",       p -> world.block(p));
        routes.put("mc.world.snapshot",    p -> world.snapshot(p));
        routes.put("mc.world.restore",     p -> withEvents(p, () -> world.restore(p)));
        routes.put("mc.recipe.lookup",     p -> recipe.lookup(p));
        routes.put("mc.recipe.resolve",    p -> recipe.resolve(p));
        routes.put("mc.plan.acquire",      p -> recipe.planAcquire(p));
        // Driver→agent event channel (server-side surface). op=emit|watch|unwatch|list.
        // The live push itself rides the transports: subscribe over WebSocket
        // (mc.events.subscribe frame) or the MCP SSE stream at /mcp/events.
        routes.put("mc.events",            p -> eventsApi.dispatch(p));
        routes.put("mc.query", p -> {
            // Client-MCP fallback — server-side query() asserts attached server.
            String q = (String) p.get("q");
            if (server == null && ("entities".equals(q) || "blocks".equals(q))) {
                var c = clientOrNull();
                if (c != null) return ClientQueryFallback.query(c, p);
            }
            return query(QueryParams.from(p));
        });

        registerClientRoutes();
        registry().registerBatch(coreBatch);
    }

    /** The typed core methods, published once; a retry would hand over this same object. */
    private final net.magicterra.worlddriver.api.ApiBatch coreBatch =
            new net.magicterra.worlddriver.api.ApiBatch(net.magicterra.worlddriver.api.ApiRegistry.CORE_OWNER, "core",
                    coreMethods());

    private List<net.magicterra.worlddriver.api.ApiMethod<?, ?>> coreMethods() {
        List<net.magicterra.worlddriver.api.ApiMethod<?, ?>> all = new ArrayList<>(new BotActions(this).methods());
        all.addAll(new ScriptMethods(() -> scriptHost).methods());
        return all;
    }

    private void registerClientRoutes() {
        // Client routes. Resolution is deferred to call time so the broker's
        // registration ordering doesn't matter; on a dedicated server the impl
        // is never bound and these throw a recognizable IllegalStateException
        // which the JS test harness catches to skip gracefully.
        routes.put("mc.client.screen.tree",          p -> requireClient().screenTree());
        routes.put("mc.client.screen.info",          p -> requireClient().screenInfo());
        // Client-AUTHORITATIVE player + world reads. Unlike mc.observe.player /
        // mc.query (which prefer the SERVER when one is attached), these ALWAYS
        // read the client LocalPlayer / ClientLevel — so an agent (or a
        // mc.script.eval snippet) can see what the *client* predicts: pose,
        // isInWall, eye-cell block — and diff it against the server. This is the
        // introspection the client-tick reflexes (autoSwim, antiSuffocate) gate
        // on; without it a desync (e.g. the client crawl-evading a command-placed
        // block while the server suffocates) is invisible from the agent side.
        routes.put("mc.client.player",               p -> requireClient().observePlayer());
        routes.put("mc.client.scene",                p -> requireBot().worldModel().snapshot().toMap());
        routes.put("mc.client.blocks",               p -> {
            ClientDriverApi c = requireClient();
            int r = 4;
            String typeFilter = null;
            Object filter = p.get("filter");
            if (filter instanceof Map<?, ?> fm) {
                if (fm.get("in_radius") instanceof Number rn) r = rn.intValue();
                if (fm.get("type") instanceof String s && !s.isBlank()) typeFilter = s;
            }
            Double cx = null, cy = null, cz = null;
            if (p.get("center") instanceof Map<?, ?> cm
                    && cm.get("x") instanceof Number nx
                    && cm.get("y") instanceof Number ny
                    && cm.get("z") instanceof Number nz) {
                cx = nx.doubleValue(); cy = ny.doubleValue(); cz = nz.doubleValue();
            }
            Set<String> ids = (typeFilter == null) ? null
                    : new LinkedHashSet<>(Set.of(typeFilter));
            return c.observeArea(r, cx, cy, cz, ids);
        });
        // Inventory / pause are reachable via mc.client.input.key{key:'E'} /
        // {key:'ESCAPE'} — same vanilla path. No separate tool needed.
        routes.put("mc.client.chat.send",            p -> requireClient().chatSend(
                (String) p.get("text"),
                p.get("awaitReplyMs") instanceof Number n ? n.intValue() : 0));
        routes.put("mc.client.chat.history",         p -> requireClient().chatHistory(
                p.get("limit") instanceof Number ln ? ln.intValue() : 50,
                p.get("sinceSeq") instanceof Number sn ? sn.longValue() : 0L));
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
        routes.put("mc.client.input.replaceText",    p -> requireClient().replaceText(
                (String) p.get("text"), (String) p.get("match")));
        routes.put("mc.client.input.slider",         p -> requireClient().setSlider(
                (String) p.get("match"),
                p.get("index") == null ? null : num(p.get("index")),
                p.get("fraction") == null ? null : numD(p.get("fraction"))));
        routes.put("mc.client.input.key",            p -> requireClient().key(
                (String) p.get("key"), (String) p.get("action"), (String) p.get("route"),
                p.get("modifiers")));
        routes.put("mc.client.input.keybind",        p -> requireClient().keybind(
                (String) p.get("name"), (String) p.get("action")));
        routes.put("mc.client.screenshot",           p -> requireClient().screenshot(p));

    }

    /**
     * Bind what answers {@code mc.script.eval}, {@code mc.skill} and {@code mc.bot.playbook}. Called from the
     * platform bootstrap, so this package never loads the script engine.
     */
    public void setScriptHost(ScriptHost host) {
        this.scriptHost = host;
    }

    static ClientDriverApi requireClient() {
        ClientDriverApi c = ClientHooks.impl();
        if (c == null) throw new IllegalStateException("mc.client.* not available (no client registered)");
        return c;
    }

    private static ClientDriverApi clientOrNull() {
        return ClientHooks.impl();
    }

    private static BotApi requireBot() {
        BotApi b = BotHooks.impl();
        if (b == null) throw new IllegalStateException("mc.bot.* not available (client only; bot impl not registered)");
        return b;
    }

    public void attachServer(MinecraftServer s) {
        this.server = s;
        ServerThreadGuard.install(s::isSameThread);
    }

    /**
     * Write a list of cells into the attached server's overworld on the server
     * thread. Public seam for the path-replay debug tool ({@code mc.debug.replay}),
     * which restores a recorded block envelope before re-executing a plan. Returns
     * the number of cells written. Throws if no server is attached.
     */
    public int restoreCellsOnServer(List<WorldCell> cells) {
        ServerLevel level = level();
        return onServerThread(() -> {
            WorldApi.restoreCells(level, cells);
            return cells.size();
        });
    }

    public void detachServer() {
        this.server = null;
        ServerThreadGuard.uninstall();
        eventsApi.clear(); // stop condition watchers — their routes need the server
        clearEvents();
        world.clearSnapshots();
    }

    /** Drop the buffered events but never rewind the seq: the transports outlive a world, and a
     *  client still holding cursor N would see nothing until the counter climbed back past N. */
    public void clearEvents() {
        synchronized (eventsLock) {
            events.clear();
        }
    }

    /** Platform event hooks (and client-tick detectors) feed natural (non-API)
     *  signals — block changes, damage, death, chat, threats — into the event
     *  stream through here. */
    public void emitExternal(String type, BlockPos pos, Object data) {
        emit(type, pos, data);
    }

    /** Append an event to the ring buffer (for replay via {@code mc.observe.eventsSince})
     *  AND fan it out to live push subscribers. The buffer append is synchronous and
     *  cheap; listener delivery is handed to the single-thread {@link #eventDispatch}
     *  so the calling thread (server tick / client tick / watcher) never blocks on a
     *  socket write. Returns the assigned sequence number. */
    long emit(String type, BlockPos pos, Object data) {
        DriverEvent e;
        // The seq is taken under the lock: readers advance their cursor to the last seq they
        // saw, so an event appended behind a higher seq would never be returned to them.
        synchronized (eventsLock) {
            e = new DriverEvent(eventSeq.incrementAndGet(), type, pos, data);
            if (events.size() >= EVENT_BUFFER_CAP) events.pollFirst();
            events.addLast(e);
        }
        if (!eventListeners.isEmpty()) {
            eventDispatch.execute(() -> {
                // The mc.bot.setting{mutedEvents} per-type opt-out is POLICY, so it
                // belongs here rather than in each transport. Both the WebSocket and
                // MCP-SSE push paths used to carry their own identical copy of this
                // line — the arrangement where a third transport is muted only if its
                // author remembers to be, and where the two can silently disagree.
                // Muting suppresses the PUSH only: the event is already in the replay
                // buffer above, so mc.observe.eventsSince still returns it, exactly as
                // before. Evaluated here on the dispatch thread, the same moment the
                // transports evaluated it, so the timing is unchanged too.
                if (BotConfig.mutedEvents.contains(e.type)) return;
                for (Consumer<DriverEvent> l : eventListeners) {
                    try { l.accept(e); } catch (Throwable ignored) { /* a bad listener never breaks emission */ }
                }
            });
        }
        return e.seq;
    }

    /** Register a live push listener (the WebSocket/SSE transports). Idempotent-ish:
     *  the same consumer may appear twice if added twice — callers add exactly once. */
    public void addEventListener(Consumer<DriverEvent> listener) {
        if (listener != null) eventListeners.add(listener);
    }

    public void removeEventListener(Consumer<DriverEvent> listener) {
        eventListeners.remove(listener);
    }

    ServerLevel level() {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("DriverApplication not attached to a server");
        return s.overworld();
    }

    /** Default budget for waiting on a server-tick hop. Lowered from 30s so a
     *  stuck tick surfaces as a clear error within ~8s instead of stalling every
     *  MCP / RPC call for half a minute. Override with
     *  {@code -Dworlddriver.serverThreadTimeoutMs=N}. */
    private static final long SERVER_THREAD_TIMEOUT_MS =
            Long.getLong("worlddriver.serverThreadTimeoutMs", 8_000L);

    <T> T onServerThread(Supplier<T> task) {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("DriverApplication not attached to a server");
        return new ServerThreadHop(s, s::isSameThread, SERVER_THREAD_TIMEOUT_MS).call(task);
    }

    /** The hop onto the attached server's thread, or null with no server attached. */
    ServerThreadHop serverHop() {
        MinecraftServer s = server;
        return s == null ? null : new ServerThreadHop(s, s::isSameThread, SERVER_THREAD_TIMEOUT_MS);
    }

    /** The attached server, or null. */
    MinecraftServer server() { return server; }

    private int num(Object o) { return Params.toInt(o, 0); }
    private double numD(Object o) { return Params.toDouble(o, 0.0); }

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
        List<DriverEvent> page = observe.eventsSince(cursorBefore, null, EVENT_BUFFER_CAP);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("events", page);
        return out;
    }


    // ---------------- Query DSL ----------------
    public Object query(QueryParams p) {
        BlockPos centerPos = (p.center != null) ? p.center : ORIGIN;
        if ("blocks".equals(p.q)) {
            BlockQuery blocks = BlockQuery.of(p);
            ServerLevel level = level();
            return onServerThread(() -> (Object) blocks.scan(level, centerPos));
        }
        ServerLevel level = level();
        if ("entities".equals(p.q)) {
            int r = Math.max(0, Math.min(128, num(p.filter.getOrDefault("in_radius", 16))));
            Boolean wantHostile = (p.filter.get("is_hostile") instanceof Boolean b) ? b : null;
            // filter.is_living drops non-living rows (dropped items, XP orbs) so
            // health-delta assertions don't need client-side filtering
            // (docs/archive/feedback/2026-06-04, bug #6).
            Boolean wantLiving = (p.filter.get("is_living") instanceof Boolean b) ? b : null;
            // filter.type restricts to one entity id (exact match; bare paths get the
            // minecraft: namespace) — mirrors the blocks branch, which had it first.
            Object entityTypeFilter = p.filter.get("type");
            String wantType = (entityTypeFilter instanceof String s && !s.isBlank())
                    ? (s.contains(":") ? s : "minecraft:" + s) : null;
            checkSelect(p.select, ENTITY_SELECT_KEYS);
            return onServerThread(() -> {
                List<Map<String, Object>> out = new ArrayList<>();
                BlockPos center = centerPos;
                AABB box = new AABB(center).inflate(r);
                for (Entity e : level.getEntities((Entity) null, box)) {
                    boolean hostile = e instanceof Enemy;
                    if (wantHostile != null && wantHostile != hostile) continue;
                    if (wantLiving != null && wantLiving != (e instanceof LivingEntity)) continue;
                    if (wantType != null && !wantType.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("pos", new BlockPos(e.blockPosition().getX(), e.blockPosition().getY(), e.blockPosition().getZ()));
                    row.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                    // uuid for stable identity across queries, numeric id for
                    // attackEntity — parity with the client fallback path.
                    row.put("uuid", e.getUUID().toString());
                    row.put("id", e.getId());
                    if (e instanceof LivingEntity le) {
                        row.put("health", (double) le.getHealth());
                        // Active MobEffects — the single biggest gap for testing
                        // effect-based mechanics (docs/archive/feedback/2026-06-04, bug #4).
                        // Same entry shape as mc.observe.player's effects.
                        List<Object> fx = new ArrayList<>();
                        for (var inst : le.getActiveEffects()) {
                            Map<String, Object> fe = new LinkedHashMap<>();
                            fe.put("id", BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString());
                            fe.put("amplifier", inst.getAmplifier());
                            fe.put("durationTicks", inst.getDuration());
                            fx.add(fe);
                        }
                        row.put("effects", fx);
                    }
                    out.add(project(row, p.select));
                }
                return (Object) out;
            });
        }
        return List.of();
    }

    /** Every key a q='entities' row can carry — {@link #checkSelect} validates against it. */
    static final Set<String> ENTITY_SELECT_KEYS =
            Set.of("pos", "type", "uuid", "id", "health", "effects");

    /** Unknown select keys used to be silently ignored, misleading callers into
     *  "field not supported" detours (docs/archive/feedback/2026-06-04, bug #3). Reject
     *  them instead; the transport layers surface the message as isError. */
    static void checkSelect(List<String> select, Set<String> allowed) {
        if (select == null) return;
        for (String k : select) {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException(
                        "unknown select key '" + k + "' (allowed: " + String.join(", ", allowed) + ")");
            }
        }
    }

    static Map<String, Object> project(Map<String, Object> row, List<String> select) {
        if (select == null || select.isEmpty()) return row;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : select) if (row.containsKey(k)) out.put(k, row.get(k));
        return out;
    }
}
