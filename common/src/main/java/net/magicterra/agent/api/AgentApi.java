package net.magicterra.agent.api;

import net.magicterra.agent.model.AgentEvent;
import net.magicterra.agent.model.Params;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.magicterra.agent.bot.util.BlockMatch;
import java.util.function.Supplier;
import net.magicterra.agent.client.ClientHooks;
import net.magicterra.agent.client.ClientAgentApi;
import net.magicterra.agent.bot.BotApi;
import java.util.Set;
import net.magicterra.agent.rpc.JsonCodec;
import net.magicterra.agent.test.yaml.YamlTestInterpreter;
import net.magicterra.agent.test.yaml.YamlTestSpec;
import net.magicterra.agent.bot.BotHooks;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

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
    final ArrayDeque<AgentEvent> events = new ArrayDeque<>(EVENT_BUFFER_CAP);
    final Object eventsLock = new Object();
    final AtomicLong eventSeq = new AtomicLong();
    final long startNanos = System.nanoTime();

    /** Live push listeners (the WebSocket and SSE transports register here). Every
     *  {@link #emit} hands the new event to each, off the caller's thread via
     *  {@link #eventDispatch} so neither the server tick nor a client tick ever
     *  blocks on socket I/O. Copy-on-write: registration is rare, iteration frequent. */
    private final List<Consumer<AgentEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService eventDispatch = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-event-dispatch");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Function<Map<String, Object>, Object>> routes = new ConcurrentHashMap<>();
    private volatile Function<Map<String, Object>, Object> scriptHandler;
    private volatile Function<Map<String, Object>, Object> playbookHandler;
    private volatile Function<Map<String, Object>, Object> skillHandler;

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
        routes.put("mc.wait.result",      p -> wait.result(p));
        // Action routes — optional `returnEvents:true` bracket-captures events emitted
        // during the call so a script doesn't need a separate cursor/eventsSince pair.
        // mc.action.placeBlock removed — single-block placement = mc.action.placeMany({blocks:[{pos,type}]}).
        routes.put("mc.action.runCommand", p -> withEvents(p, () -> action.runCommand((String) p.get("cmd"))));
        routes.put("mc.action.fill",       p -> withEvents(p, () -> action.fill(p)));
        routes.put("mc.action.placeMany",  p -> withEvents(p, () -> action.placeMany(p)));
        // World snapshot/restore — deterministic test setup/teardown. restore
        // emits a world.restore event, so it honors returnEvents like the action.* group.
        routes.put("mc.world.snapshot",    p -> world.snapshot(p));
        routes.put("mc.world.restore",     p -> withEvents(p, () -> world.restore(p)));
        // Run YAML GameTest definitions through the interpreter on demand (docs/
        // yaml-gametest.md). {file:"x.yaml"} loads a classpath file, {inline:"..."}
        // parses a literal; returns {results:[{name,pass,failures}], passed, failed}.
        // The same YamlTestInterpreter also backs the @GameTestGenerator hook.
        routes.put("mc.test.yaml",         p -> runYamlTests(p));
        routes.put("mc.recipe.lookup",     p -> recipe.lookup(p));
        routes.put("mc.recipe.resolve",    p -> recipe.resolve(p));
        routes.put("mc.plan.acquire",      p -> recipe.planAcquire(p));
        // Driver→agent event channel (server-side surface). op=emit|watch|unwatch|list.
        // The live push itself rides the transports: subscribe over WebSocket
        // (mc.events.subscribe frame) or the MCP SSE stream at /mcp/events.
        routes.put("mc.events",            p -> eventsApi.dispatch(p));
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
                        Set<String> ids = (typeFilter == null) ? null
                                : new LinkedHashSet<>(Set.of(typeFilter));
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
            ClientAgentApi c = requireClient();
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
        routes.put("mc.client.input.replaceText",    p -> requireClient().replaceText(
                (String) p.get("text"), (String) p.get("match")));
        routes.put("mc.client.input.slider",         p -> requireClient().setSlider(
                (String) p.get("match"),
                p.get("index") == null ? null : num(p.get("index")),
                p.get("fraction") == null ? null : numD(p.get("fraction"))));
        routes.put("mc.client.input.key",            p -> requireClient().key(
                (String) p.get("key"), (String) p.get("action")));
        routes.put("mc.client.screenshot",           p -> requireClient().screenshot(p));

        // Bot routes — client-side; unavailable on dedicated server.
        // Async actions accept an optional `awaitMs` that, when set, makes the
        // route block on bot.status until the named slot goes idle (or times
        // out), folding the final status snapshot into the response.
        routes.put("mc.bot.goto",      p -> awaitable(p, "goto",    requireBot()::mcGoto));
        routes.put("mc.bot.mine",      p -> awaitable(p, "mine",    requireBot()::mine));
        routes.put("mc.bot.bunker",    p -> requireBot().bunker(p));
        routes.put("mc.bot.escape",    p -> requireBot().escape(p));
        routes.put("mc.bot.craft",     p -> awaitable(p, "craft",   requireBot()::craft));
        routes.put("mc.bot.smelt",     p -> awaitable(p, "smelt",   requireBot()::smelt));
        routes.put("mc.bot.combat",    p -> awaitable(p, "combat",  requireBot()::combat));
        routes.put("mc.bot.equip",     p -> requireBot().equip(p));
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
        // Phase G boss playbooks — Rhino scripts run on a background thread by the
        // PlaybookRunner (bound at startup, like scriptHandler). op=start|status|cancel.
        routes.put("mc.bot.playbook", p -> {
            Function<Map<String, Object>, Object> h = playbookHandler;
            if (h == null) throw new IllegalStateException("mc.bot.playbook not available (no playbook runner bound)");
            return h.apply(p == null ? Map.of() : p);
        });

        // Script evaluation. Bound at startup via setScriptHandler() to avoid
        // making AgentApi depend on Rhino classes directly — keeps the api/
        // package free of the script/ package.
        routes.put("mc.script.eval", p -> {
            Function<Map<String, Object>, Object> h = scriptHandler;
            if (h == null) throw new IllegalStateException("mc.script.eval not available (no evaluator bound)");
            return h.apply(p);
        });
        // Phase H — persistent skill library (Voyager). op=save|list|get|run|delete.
        routes.put("mc.skill", p -> {
            Function<Map<String, Object>, Object> h = skillHandler;
            if (h == null) throw new IllegalStateException("mc.skill not available (no skill library bound)");
            return h.apply(p == null ? Map.of() : p);
        });
    }

    /**
     * Bind the implementation of {@code mc.script.eval}. Called from the
     * platform bootstrap so the api package stays free of Rhino.
     */
    public void setScriptHandler(Function<Map<String, Object>, Object> handler) {
        this.scriptHandler = handler;
    }

    /**
     * Bind the implementation of {@code mc.bot.playbook} (the Phase G boss-playbook
     * runner). Bound from the platform bootstrap so the api package stays free of
     * the script/ package, exactly like {@link #setScriptHandler}.
     */
    public void setPlaybookHandler(Function<Map<String, Object>, Object> handler) {
        this.playbookHandler = handler;
    }

    /** Bind the implementation of {@code mc.skill} (the Phase H skill library). */
    public void setSkillHandler(Function<Map<String, Object>, Object> handler) {
        this.skillHandler = handler;
    }

    static ClientAgentApi requireClient() {
        ClientAgentApi c = ClientHooks.impl();
        if (c == null) throw new IllegalStateException("mc.client.* not available (no client registered)");
        return c;
    }

    private static ClientAgentApi clientOrNull() {
        return ClientHooks.impl();
    }

    private static BotApi requireBot() {
        BotApi b = BotHooks.impl();
        if (b == null) throw new IllegalStateException("mc.bot.* not available (client only; bot impl not registered)");
        return b;
    }

    public void attachServer(MinecraftServer s) {
        this.server = s;
    }

    public void detachServer() {
        this.server = null;
        eventsApi.clear(); // stop condition watchers — their routes need the server
        synchronized (eventsLock) {
            events.clear();
            eventSeq.set(0);
        }
        world.clearSnapshots();
    }

    public Object route(String method, Map<String, Object> params) {
        Function<Map<String, Object>, Object> fn = routes.get(method);
        if (fn == null) throw new IllegalArgumentException("unknown method: " + method);
        return fn.apply(params == null ? Map.of() : params);
    }

    /**
     * Register an additional route after construction. Used by optional, strippable
     * subsystems (e.g. the path-debug package) so core never compile-depends on them.
     * Idempotent-safe: a duplicate name overwrites. Thread-safe via the concurrent map.
     * Routes added here are reachable identically through every transport (Hard Rule #1).
     */
    public void addRoute(String method, Function<Map<String, Object>, Object> handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        routes.put(method, handler);
    }

    @SuppressWarnings("unchecked")
    public String invokeJson(String method, String paramsJson) {
        Map<String, Object> params;
        if (paramsJson == null || paramsJson.isBlank() || paramsJson.equals("null")) {
            params = Map.of();
        } else {
            Object decoded = JsonCodec.decode(paramsJson);
            params = (decoded instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
        }
        Object result = route(method, params);
        return JsonCodec.encode(result);
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

    /** Platform event hooks (and client-tick detectors) feed natural (non-API)
     *  signals — block changes, damage, death, chat, threats — into the event
     *  stream through here. */
    public void emitExternal(String type, BlockPos pos, String data) {
        emit(type, pos, data);
    }

    /** Append an event to the ring buffer (for replay via {@code mc.observe.eventsSince})
     *  AND fan it out to live push subscribers. The buffer append is synchronous and
     *  cheap; listener delivery is handed to the single-thread {@link #eventDispatch}
     *  so the calling thread (server tick / client tick / watcher) never blocks on a
     *  socket write. Returns the assigned sequence number. */
    long emit(String type, BlockPos pos, String data) {
        AgentEvent e = new AgentEvent(eventSeq.incrementAndGet(), type, pos, data);
        synchronized (eventsLock) {
            if (events.size() >= EVENT_BUFFER_CAP) events.pollFirst();
            events.addLast(e);
        }
        if (!eventListeners.isEmpty()) {
            eventDispatch.execute(() -> {
                for (Consumer<AgentEvent> l : eventListeners) {
                    try { l.accept(e); } catch (Throwable ignored) { /* a bad listener never breaks emission */ }
                }
            });
        }
        return e.seq;
    }

    /** Register a live push listener (the WebSocket/SSE transports). Idempotent-ish:
     *  the same consumer may appear twice if added twice — callers add exactly once. */
    public void addEventListener(Consumer<AgentEvent> listener) {
        if (listener != null) eventListeners.add(listener);
    }

    public void removeEventListener(Consumer<AgentEvent> listener) {
        eventListeners.remove(listener);
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
        } catch (TimeoutException e) {
            throw new RuntimeException("server thread did not run task within "
                    + SERVER_THREAD_TIMEOUT_MS + "ms (server busy or paused)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting on server thread");
        }
    }

    private int num(Object o) { return Params.toInt(o, 0); }
    private double numD(Object o) { return Params.toDouble(o, 0.0); }

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

        long t0 = System.nanoTime();
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
        long ms = (System.nanoTime() - t0) / 1_000_000L;
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
        long now = System.nanoTime();
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

    // ---------------- YAML GameTest runner ----------------
    /** Backs {@code mc.test.yaml}: parse {file}/{inline} into specs, run each
     *  through {@link YamlTestInterpreter}, return a per-spec pass/fail report. */
    private Map<String, Object> runYamlTests(Map<String, Object> p) {
        List<YamlTestSpec> specs;
        Object inline = (p == null) ? null : p.get("inline");
        Object file = (p == null) ? null : p.get("file");
        boolean all = p != null && Boolean.TRUE.equals(p.get("all"));
        if (inline instanceof String s && !s.isBlank()) {
            specs = net.magicterra.agent.test.yaml.YamlTestLoader.parseString(s, "<inline>");
        } else if (file instanceof String f && !f.isBlank()) {
            specs = net.magicterra.agent.test.yaml.YamlTestLoader.loadFile(f);
        } else if (all) {
            specs = net.magicterra.agent.test.yaml.YamlTestLoader.loadAll();
        } else {
            throw new IllegalArgumentException("mc.test.yaml requires 'file', 'inline', or 'all:true'");
        }
        YamlTestInterpreter interp = new YamlTestInterpreter(this);
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0, failed = 0;
        for (YamlTestSpec spec : specs) {
            YamlTestInterpreter.Result r = interp.runSpec(spec);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r.name());
            row.put("pass", r.pass());
            row.put("failures", r.failures());
            results.add(row);
            if (r.pass()) passed++; else failed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("passed", passed);
        out.put("failed", failed);
        return out;
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
            // Supports exact ids and '#tag' selectors (e.g. #minecraft:logs).
            Predicate<BlockState> match =
                    (typeFilterId == null) ? null : BlockMatch.of(typeFilterId);
            return onServerThread(() -> {
                List<Map<String, Object>> out = new ArrayList<>();
                BlockPos center = centerPos;
                for (int dx = -r; dx <= r; dx++)
                    for (int dy = -r; dy <= r; dy++)
                        for (int dz = -r; dz <= r; dz++) {
                            BlockPos bp = center.offset(dx, dy, dz);
                            BlockState st = level.getBlockState(bp);
                            if (st.isAir()) continue;
                            if (match != null && !match.test(st)) continue;
                            String id = ApiSupport.blockId(st);
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
