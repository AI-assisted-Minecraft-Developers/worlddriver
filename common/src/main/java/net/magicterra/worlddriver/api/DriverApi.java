package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import net.magicterra.worlddriver.bot.VerbOrders;
import java.util.function.Predicate;
import net.magicterra.worlddriver.bot.util.BlockMatch;
import java.util.function.Supplier;
import net.magicterra.worlddriver.client.ClientHooks;
import net.magicterra.worlddriver.client.ClientDriverApi;
import net.magicterra.worlddriver.bot.BotApi;
import java.util.Set;
import net.magicterra.worlddriver.rpc.JsonCodec;
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
public final class DriverApi {
    public final SystemApi system = new SystemApi(this);
    public final ObserveApi observe = new ObserveApi(this);
    public final ActionApi action = new ActionApi(this);
    public final WaitApi wait = new WaitApi(this);
    public final WorldApi world = new WorldApi(this);
    public final RecipeApi recipe = new RecipeApi(this);
    public final EventsApi eventsApi = new EventsApi(this);

    static final BlockPos ORIGIN = new BlockPos(0, 200, 0);

    /**
     * Keeps the test arena's chunks loaded, entities and all, for as long as the server runs.
     *
     * <p>Nothing else does. {@code StageWrightHarness} force-loads a window around each SCENE's
     * arena (AGENTS.md hard rule #11), and this arena is not one — {@link #ORIGIN} is an absolute
     * position outside every scene's grid cell, so the harness's window never covers it. Left
     * unpinned it stays loaded only while a player happens to be standing near 0,200,0, and
     * {@code seedTestArea} is called from scenes that then walk the player 130,000 blocks away.
     *
     * <p>What that costs is a whole class of failure that reads as a product bug. Block writes
     * load the chunk they touch on demand, so terrain always works; {@code Level#getEntities}
     * only sees LOADED entity sections, so entities silently do not exist. The validation suite
     * then reports "exactly the 2 tagged stands, got 0" and "the two seeded props are there, got
     * 0 non-player rows of 0" — which read as the entity query being broken. Measured on both
     * NeoForge client topologies, at both ends of the suite: {@code 05_query} failed before the
     * player's teleport onto the pad had promoted the chunk, and {@code 58_query_type} failed
     * after {@code 40_scheduler} had walked them off it. Same missing ticket, opposite ends,
     * different checks each run — which is what made it look like flakiness.
     *
     * <p>Not persisted, so it never outlives the process and cannot end up in a saved world.
     */
    private static final TicketType<ChunkPos> TEST_ARENA_TICKET =
            TicketType.create("worlddriver_test_arena", Comparator.comparingLong(ChunkPos::toLong));

    /** Chunks within this many of {@link #ORIGIN}'s chunk must be ENTITY_TICKING. The seed clears a
     *  ±20 box and the suite queries a ±16 radius around the origin, so ±2 chunks covers both. */
    private static final int TEST_ARENA_CHUNK_RADIUS = 2;

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
    private final Map<String, Function<Map<String, Object>, Object>> routes = new ConcurrentHashMap<>();
    private volatile ParamsValidator paramsValidator;
    private volatile Function<Map<String, Object>, Object> scriptHandler;
    private volatile Function<Map<String, Object>, Object> playbookHandler;
    private volatile Function<Map<String, Object>, Object> skillHandler;

    public DriverApi() {
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

        // Bot routes — client-side; unavailable on dedicated server.
        // Async actions accept an optional `awaitMs` that, when set, makes the
        // route block on bot.status until the named slot goes idle (or times
        // out), folding the final status snapshot into the response.
        // Verbs that drive the body go through body(): BodyReady's refusal answers
        // first when the player is missing, dead, paused, in bed, loading or off a
        // loaded chunk. status/cancel/setting/waypoint stay open while it is down.
        // The verbs that also take `body` are in putBodyRoutes().
        putBodyRoutes();
        routes.put("mc.bot.equip",     body(p -> requireBot().equip(p)));
        // pause/resume are reachable through mc.bot.setting{paused:bool} —
        // same vol-toggle handler in BotApiImpl.setting absorbs both.
        routes.put("mc.bot.setting",   p -> requireBot().setting(p));
        routes.put("mc.bot.waypoint",  p -> requireBot().waypoint(p));
        // Phase G boss playbooks — Rhino scripts run on a background thread by the
        // PlaybookRunner (bound at startup, like scriptHandler). op=start|status|cancel.
        routes.put("mc.bot.playbook", p -> {
            Function<Map<String, Object>, Object> h = playbookHandler;
            if (h == null) throw new IllegalStateException("mc.bot.playbook not available (no playbook runner bound)");
            return h.apply(p == null ? Map.of() : p);
        });

        // Script evaluation. Bound at startup via setScriptHandler() to avoid
        // making DriverApi depend on Rhino classes directly — keeps the api/
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

    /** A body verb behind the client's {@link BotApi#bodyRefusal()}: the refusal is the answer when there is one. */
    private static Function<Map<String, Object>, Object> body(Function<Map<String, Object>, Object> verb) {
        return p -> {
            Map<String, Object> refused = requireBot().bodyRefusal();
            return refused != null ? refused : verb.apply(p);
        };
    }

    /**
     * The verbs that also take {@code body}: anything but self goes to {@link BodyRoutes}, which
     * never touches the client bot, so these answer for server bodies on a dedicated server.
     */
    private void putBodyRoutes() {
        BodyRoutes bodies = new BodyRoutes(this);
        routes.put("mc.bot.cancel", p -> BodyRoutes.isSelf(p) ? requireBot().cancel(p) : bodies.cancel(p));
        routes.put("mc.bot.status", bodies::status);
        putBodyVerb(bodies, "mc.bot.goto",      "goto",    BotApi::mcGoto,    BodyRoutes::mcGoto);
        putBodyVerb(bodies, "mc.bot.mine",      "mine",    BotApi::mine,      BodyRoutes.order(VerbOrders::mine));
        putBodyVerb(bodies, "mc.bot.bunker",    "bunker",  BotApi::bunker,    BodyRoutes.order(VerbOrders::bunker));
        putBodyVerb(bodies, "mc.bot.escape",    null,      BotApi::escape,    BodyRoutes.order(VerbOrders::escape));
        putBodyVerb(bodies, "mc.bot.craft",     "craft",   BotApi::craft,     BodyRoutes.order(VerbOrders::craft));
        putBodyVerb(bodies, "mc.bot.smelt",     "smelt",   BotApi::smelt,     BodyRoutes.order(VerbOrders::smelt));
        putBodyVerb(bodies, "mc.bot.combat",    "combat",  BotApi::combat,    BodyRoutes.order(VerbOrders::combat));
        putBodyVerb(bodies, "mc.bot.build",     "builder", BotApi::build,     BodyRoutes.order(VerbOrders::build));
        putBodyVerb(bodies, "mc.bot.clearArea", "builder", BotApi::clearArea, BodyRoutes.order(VerbOrders::clearArea));
        putBodyVerb(bodies, "mc.bot.farm",      "builder", BotApi::farm,      BodyRoutes.order(VerbOrders::farm));
        putBodyVerb(bodies, "mc.bot.construct", "builder", BotApi::construct, BodyRoutes.order(VerbOrders::construct));
        putBodyVerb(bodies, "mc.bot.sleep",     "goto",    BotApi::sleep,     BodyRoutes.order(VerbOrders::sleep));
        putBodyVerb(bodies, "mc.bot.follow",    "follow",  BotApi::follow,    BodyRoutes.order(VerbOrders::follow));
        putBodyVerb(bodies, "mc.bot.explore",   "explore", BotApi::explore,   BodyRoutes.order(VerbOrders::explore));
        putBodyVerb(bodies, "mc.bot.runAway",   "runAway", BotApi::runAway,   BodyRoutes.order(VerbOrders::runAway));
        putBodyVerb(bodies, "mc.bot.elytraFly", "elytra",  BotApi::elytraFly, BodyRoutes.order(VerbOrders::elytraFly));
        putBodyVerb(bodies, "mc.bot.lookAt",       null, BotApi::lookAt,       BodyInteractions::lookAt);
        putBodyVerb(bodies, "mc.bot.holdItem",     null, BotApi::holdItem,     BodyInteractions::holdItem);
        putBodyVerb(bodies, "mc.bot.attackEntity", null, BotApi::attackEntity, BodyInteractions::attackEntity);
        // mc.bot.useItem dispatches based on params: pass `entityId` to right-click
        // an entity (mount / trade / shear / milk / feed / leash); pass `pos` to use
        // the held item ON a block face (place / bone-meal / shears / etc.); omit both
        // to use the item in mid-air (eat / draw bow / throw snowball).
        putBodyVerb(bodies, "mc.bot.useItem", null, (bot, p) -> p != null && p.get("entityId") != null ? bot.useItemOnEntity(p)
                : p != null && p.get("pos") != null ? bot.useItemOn(p) : bot.useItem(p), BodyInteractions::useItem);
    }

    /**
     * {@code method} on self through {@code onSelf} behind {@link #body}, on another body through
     * {@code onHost} by way of {@link BodyRoutes#onHost}. {@code slot} is what {@code awaitMs} waits
     * on, or null for a verb that does not wait.
     */
    private void putBodyVerb(BodyRoutes bodies, String method, String slot,
                             BiFunction<BotApi, Map<String, Object>, Map<String, Object>> onSelf,
                             BiFunction<net.magicterra.worlddriver.bot.body.BodyHost, Params, Map<String, Object>> onHost) {
        Function<Map<String, Object>, Map<String, Object>> self = p -> onSelf.apply(requireBot(), p);
        Function<Map<String, Object>, Map<String, Object>> other = p -> bodies.onHost(p, onHost);
        Function<Map<String, Object>, Object> selfRoute = body(slot == null ? self::apply : p -> awaitable(p, slot, self));
        routes.put(method, p -> BodyRoutes.isSelf(p) ? selfRoute.apply(p)
                : slot == null ? other.apply(p) : awaitable(p, slot, other, bodies.slotsOf(p)));
    }

    public void attachServer(MinecraftServer s) {
        this.server = s;
    }

    /**
     * Write a list of cells into the attached server's overworld on the server
     * thread. Public seam for the path-replay debug tool ({@code mc.debug.replay}),
     * which restores a recorded block envelope before re-executing a plan. Returns
     * the number of cells written. Throws if no server is attached.
     */
    public int restoreCellsOnServer(java.util.List<WorldApi.Cell> cells) {
        ServerLevel level = level();
        return onServerThread(() -> {
            WorldApi.restoreCells(level, cells);
            return cells.size();
        });
    }

    public void detachServer() {
        this.server = null;
        eventsApi.clear(); // stop condition watchers — their routes need the server
        clearEvents();
        world.clearSnapshots();
    }

    /** Drop the buffered events but never rewind the seq: the transports outlive a world, and a
     *  client still holding cursor N would see nothing until the counter climbed back past N. */
    void clearEvents() {
        synchronized (eventsLock) {
            events.clear();
        }
    }

    public Object route(String method, Map<String, Object> params) {
        // ConcurrentHashMap.get(null) throws a bare NPE, which every transport would report as
        // an internal fault instead of a request that named no method.
        Function<Map<String, Object>, Object> fn = method == null ? null : routes.get(method);
        if (fn == null) throw new UnknownMethodException(method);
        Map<String, Object> p = (params == null) ? Map.of() : params;
        ParamsValidator v = paramsValidator;
        if (v != null) v.validate(method, p);
        return fn.apply(p);
    }

    /**
     * Register an additional route after construction. Used by optional, strippable
     * subsystems (e.g. the path-debug package) so core never compile-depends on them.
     * Idempotent-safe: a duplicate name overwrites. Thread-safe via the concurrent map.
     * Routes added here are reachable identically through every transport (Hard Rule #1).
     *
     * <p><b>This is the low-level seam.</b> A route added here still MUST have a declared
     * MCP {@code ToolSchema} (register one via {@code ToolCatalog.registerExtra}), or the
     * boot invariant {@link #requireSchemasFor} refuses to start. As of the paired-registration
     * change, a route reached at dispatch time with no schema is a <b>loud
     * {@link IllegalStateException}</b> in {@link #route} — schema-less dispatch is no longer
     * silently skipped. For game-affecting verbs prefer the paired
     * {@code ToolCatalog.registerVerb(schema, handler)}, which registers the schema and this
     * route together (atomically) and enforces the verb namespace policy; use raw {@code addRoute}
     * only for internal driver routes whose schema you register separately (path-debug pattern).
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
     * Boot-time invariant: every registered route MUST have a declared MCP
     * {@code ToolSchema}. The set of declared names is passed in by the bootstrap
     * (from {@code ToolCatalog.declaredMethodNames()}) so this stays in the api
     * layer without depending on the mcp/transport layer (Hard Rule #1 — core is
     * transport-agnostic). A route with no schema would be half-specified: invisible
     * to MCP {@code tools/list} yet callable, the exact drift that let methods slip
     * to RPC-only. We refuse to start rather than ship it; an intentional RPC-only
     * method is declared as a {@code hidden} ToolSchema, so it counts as "declared".
     *
     * @throws IllegalStateException if any route lacks a declared schema
     */
    public void requireSchemasFor(Set<String> declaredToolNames) {
        Set<String> missing = new TreeSet<>(routes.keySet());
        missing.removeAll(declaredToolNames);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "worlddriver: " + missing.size() + " route(s) have no MCP ToolSchema — declare each "
                + "in ToolCatalog (a normal schema, or a hidden one for RPC-only verbs): " + missing);
        }
    }

    /**
     * Pre-dispatch params validation, injected by the bootstrap from the MCP
     * ToolCatalog (Hard Rule #1: the api layer never depends on the mcp layer —
     * same seam style as {@link #requireSchemasFor}). Covers EVERY caller of
     * {@link #route}: MCP tools/call, RPC websocket, in-JVM Rhino Driver.invoke,
     * and internal consumers (EventsApi/WaitApi/…) — one
     * contract, uniformly enforced.
     */
    public void setParamsValidator(ParamsValidator validator) {
        this.paramsValidator = validator;
    }

    /**
     * Lays down a deterministic test arena: 5x5 stones at y=200, oak log at y=201,
     * one cow at (3,201,0), one sheep at (-3,201,2). Clears surrounding air first
     * so {@code /worlddriver test} is idempotent.
     *
     * <p>Pins the arena's chunks on the way in ({@link #TEST_ARENA_TICKET}) and asserts on the way
     * out that the props it just placed are actually visible. "Deterministic" is the whole point of
     * this verb, and an arena whose entities exist only while somebody stands next to it is not.
     */
    /** True once every chunk within {@link #TEST_ARENA_CHUNK_RADIUS} of {@code center} has its
     *  entity sections at the ticking visibility — the state in which {@code getEntities} sees
     *  what {@code addFreshEntity} added. */
    private static boolean arenaEntityTicking(ServerLevel level, ChunkPos center) {
        for (int cx = center.x - TEST_ARENA_CHUNK_RADIUS; cx <= center.x + TEST_ARENA_CHUNK_RADIUS; cx++)
            for (int cz = center.z - TEST_ARENA_CHUNK_RADIUS; cz <= center.z + TEST_ARENA_CHUNK_RADIUS; cz++)
                if (!level.isPositionEntityTicking(new BlockPos(cx << 4, ORIGIN.getY(), cz << 4))) return false;
        return true;
    }

    public void seedTestArea() {
        ServerLevel level = level();
        onServerThread(() -> {
            BlockPos origin = ORIGIN;
            // Pin first, write second. The ticket's level has to reach ENTITY_TICKING (31) out to
            // TEST_ARENA_CHUNK_RADIUS, and a region ticket at distance d puts its own chunk at
            // 33-d and each ring one higher — so d = radius + 2. Re-adding an identical ticket is
            // a no-op in DistanceManager, which is what makes this safe to call on every seed.
            ChunkPos center = new ChunkPos(origin);
            level.getChunkSource().addRegionTicket(
                    TEST_ARENA_TICKET, center, TEST_ARENA_CHUNK_RADIUS + 2, center);
            // Then drive the load to completion before touching anything. addRegionTicket only
            // registers intent; the chunks reach FULL (and their entity sections become visible)
            // through the chunk source's own update pass, and a blocking getChunk on the server
            // thread is what runs it. Without this the seed still writes its blocks — those load
            // on demand — and its animals still land in a section nothing can see yet.
            for (int cx = center.x - TEST_ARENA_CHUNK_RADIUS; cx <= center.x + TEST_ARENA_CHUNK_RADIUS; cx++)
                for (int cz = center.z - TEST_ARENA_CHUNK_RADIUS; cz <= center.z + TEST_ARENA_CHUNK_RADIUS; cz++)
                    level.getChunk(cx, cz);
            // The blocking loads above only SCHEDULE the step that makes entities visible. A chunk
            // reaching FULL / BLOCK_TICKING / ENTITY_TICKING goes through
            // ChunkHolder.scheduleFullChunkPromotion, which hands ChunkMap.onFullChunkStatusChange —
            // the call that flips the chunk's entity sections from HIDDEN to accessible — to the
            // main-thread executor with thenRunAsync. A task queued that way cannot run inside the
            // task that queued it, so seeding and checking in one server-thread turn saw the props
            // it had just added in a section getEntities does not iterate: "holds 0 of its 2 props"
            // on every dedicated-server run, while topologies where something had promoted these
            // chunks in an earlier tick (a player nearby, a previous seed) passed by accident.
            //
            // Pump the CHUNK SOURCE's own queue, not the server's. ServerChunkCache.pollTask runs
            // the distance-manager update and then the chunk-thread tasks, which is exactly where
            // the promotion sits — it is what a blocking getChunk spins on. MinecraftServer's
            // managedBlock would not do: its pollTaskInternal only reaches the chunk sources while
            // haveTime() holds, and haveTime() is runningTask() (a task is executing) or the tick
            // still having budget; a scene runs from the tick loop, not from a task, and by the
            // time the loads above return the tick's 50 ms are long gone — measured: ten seconds
            // of spinning with entityTicking still false. Bounded so a promotion that never lands
            // is reported by the assertion below instead of hanging the server.
            //
            // Every arena chunk, not just the origin's: the sheep stands at x = -0.5, one chunk
            // west, and a wait on the origin chunk alone seeded "1 of its 2 props".
            long promoteDeadline = System.nanoTime() + 10_000_000_000L;
            while (!arenaEntityTicking(level, center) && System.nanoTime() < promoteDeadline) {
                if (!level.getChunkSource().pollTask()) {
                    java.util.concurrent.locks.LockSupport.parkNanos("seedTestArea: chunk promotion", 100_000L);
                }
            }
            BlockState air = Blocks.AIR.defaultBlockState();
            // Clear up to dy=12 (origin.y+12) — deliberately taller than any cell the
            // suite currently writes. The ceiling was raised from +5 to +12 to kill a
            // flake whose mechanism outlives its original culprit: the world PERSISTS
            // across runs, so a block left above the cleared band (a one-off restore
            // hiccup, or world-gen residue) is never wiped by the seed and poisons the
            // next run's "cell is air before the run" precondition FOREVER. The verb
            // that first exposed this (mc.test.yaml, cells @ +6..+10) is gone, but the
            // headroom stays: it costs one pass over ~1000 air blocks and makes every
            // run self-healing regardless of which script reaches highest.
            for (int dx = -4; dx <= 4; dx++)
                for (int dy = -1; dy <= 12; dy++)
                    for (int dz = -4; dz <= 4; dz++)
                        level.setBlockAndUpdate(origin.offset(dx, dy, dz), air);
            // Floor the whole cleared footprint, not a 5×5 island in it. The suite's moving checks
            // need somewhere to move: a kiting bot backs away from what it is shooting at, and off
            // a five-wide pad it is over the edge in two steps — which reports as "kited away from
            // the skeleton, not into melee (d=3.3)", a distance that reads like a behaviour bug and
            // is a missing floor. Same width as the air above it, so walking off the stone and
            // walking out of the cleared box are the same boundary rather than two.
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++)
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
            // NoAI: they are query props, not livestock — a wandering cow stepped
            // one block between 06_rpc_parity's two snapshots (in-JVM vs TCP,
            // 15 ms apart) and failed the row-equality assert (2026-07-09).
            Cow cow = EntityType.COW.create(level);
            if (cow != null) {
                cow.moveTo(ORIGIN.getX() + 1 + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0f, 0f);
                cow.setPersistenceRequired();
                cow.setNoAi(true);
                level.addFreshEntity(cow);
            }
            Sheep sheep = EntityType.SHEEP.create(level);
            if (sheep != null) {
                sheep.moveTo(ORIGIN.getX() - 1 + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 1 + 0.5, 0f, 0f);
                sheep.setPersistenceRequired();
                sheep.setNoAi(true);
                level.addFreshEntity(sheep);
            }
            // Read the props back through the same lookup every caller will use, and refuse to
            // report a seeded arena that is not one. Both creates are null-guarded and
            // addFreshEntity can decline, so up to here every way this fails is silent — and a
            // silent failure here is not an error anyone sees, it is a suite that reports the
            // ENTITY QUERY as broken. Throwing puts the message at the cause.
            int props = 0;
            for (Entity e : level.getEntities((Entity) null, new AABB(ORIGIN).inflate(4.0))) {
                if (e instanceof Cow || e instanceof Sheep) props++;
            }
            if (props != 2) {
                throw new IllegalStateException("seedTestArea: the arena at " + ORIGIN + " holds "
                        + props + " of its 2 props after seeding (arenaEntityTicking="
                        + arenaEntityTicking(level, center) + ") — the chunk is loaded for blocks"
                        + " but not for entities, so every entity check downstream would report an"
                        + " empty world instead of this");
            }
            clearEvents();
            return null;
        });
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
        if (s == null) throw new IllegalStateException("DriverApi not attached to a server");
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
        if (s == null) throw new IllegalStateException("DriverApi not attached to a server");
        return new ServerThreadHop(s, s::isSameThread, SERVER_THREAD_TIMEOUT_MS).call(task);
    }

    private int num(Object o) { return Params.toInt(o, 0); }
    private double numD(Object o) { return Params.toDouble(o, 0.0); }

    /**
     * The status slot an awaited verb waits on: the reply's own non-blank {@code slot} string
     * wins, else the route table's literal. NOT "only the reply's": the other fourteen awaitable
     * verbs never answer with a slot, and reading only the reply would send them into the
     * "slot disappeared → completed" branch, making their {@code awaitMs} return at once without
     * an error. Package-visible for the unit test that pins this.
     */
    static String slotToAwait(Map<String, Object> started, String routeSlot) {
        return started != null && started.get("slot") instanceof String s && !s.isBlank() ? s : routeSlot;
    }

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
     *
     * <p><b>Which slot is waited on</b> is {@link #slotToAwait}: the impl's own {@code slot} when
     * its reply carries one, else the literal the route table passed. {@code mc.bot.goto} answers
     * with {@code slot: "elytra"} when {@code route.mode} is {@code ["fly"]} and hands the intent
     * to elytra, so waiting on {@code goto} would return at once.
     */
    private Map<String, Object> awaitable(Map<String, Object> params, String slot,
                                          Function<Map<String, Object>, Map<String, Object>> impl) {
        return awaitable(params, slot, impl, () -> requireBot().status());
    }

    /** {@link #awaitable} polling {@code statusSource} for the slot: how an order to a body named by
     *  {@code body} waits on that body's slots rather than the client bot's. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitable(Map<String, Object> params, String slot,
                                          Function<Map<String, Object>, Map<String, Object>> impl,
                                          Supplier<Map<String, Object>> statusSource) {
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
        slot = slotToAwait(started, slot);

        long t0 = System.nanoTime();
        long deadlineNanos = t0 + budgetMs * 1_000_000L;
        long pollMs = 200L;
        Map<String, Object> finalStatus = null;
        boolean completed = false;
        while (true) {
            Map<String, Object> status = statusSource.get();
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
            if (slotObj instanceof Map<?, ?> slotMap) {
                out.put("status", slotObj);
                Object gr = ((Map<String, Object>) slotMap).get("goalReached");
                if (gr != null) out.put("goalReached", gr);
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
        List<DriverEvent> page = observe.eventsSince(cursorBefore, null, EVENT_BUFFER_CAP);
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
            checkSelect(p.select, BLOCK_SELECT_KEYS);
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
                            // Blockstate properties (lit/facing/half/…) so callers can
                            // verify more than the block id (docs/archive/feedback/2026-06-08,
                            // fix #2). Omitted for property-less states (stone etc.)
                            // to keep large scans lean.
                            if (!st.getProperties().isEmpty()) {
                                Map<String, Object> stateMap = new LinkedHashMap<>();
                                for (var prop : st.getProperties()) {
                                    stateMap.put(prop.getName(), stringifyProperty(st, prop));
                                }
                                row.put("state", stateMap);
                            }
                            out.add(project(row, p.select));
                        }
                return (Object) out;
            });
        } else if ("entities".equals(p.q)) {
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

    /** Property value as the string a /setblock predicate would use ("true", "north", "3"). */
    private static <T extends Comparable<T>> String stringifyProperty(BlockState st, Property<T> prop) {
        return prop.getName(st.getValue(prop));
    }

    /** Every key a q='entities' row can carry — {@link #checkSelect} validates against it. */
    private static final Set<String> ENTITY_SELECT_KEYS =
            Set.of("pos", "type", "uuid", "id", "health", "effects");
    /** Every key a q='blocks' row can carry. */
    private static final Set<String> BLOCK_SELECT_KEYS = Set.of("pos", "type", "state");

    /** Unknown select keys used to be silently ignored, misleading callers into
     *  "field not supported" detours (docs/archive/feedback/2026-06-04, bug #3). Reject
     *  them instead; the transport layers surface the message as isError. */
    private static void checkSelect(List<String> select, Set<String> allowed) {
        if (select == null) return;
        for (String k : select) {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException(
                        "unknown select key '" + k + "' (allowed: " + String.join(", ", allowed) + ")");
            }
        }
    }

    private Map<String, Object> project(Map<String, Object> row, List<String> select) {
        if (select == null || select.isEmpty()) return row;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : select) if (row.containsKey(k)) out.put(k, row.get(k));
        return out;
    }
}
