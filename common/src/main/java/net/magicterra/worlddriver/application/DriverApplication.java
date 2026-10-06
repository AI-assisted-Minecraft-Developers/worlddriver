package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.WorldCell;
import net.magicterra.worlddriver.api.ServerThreadGuard;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.WireValues;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.model.Params;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
        all.addAll(eventsApi.methods());
        all.addAll(wait.methods());
        all.addAll(system.methods());
        all.addAll(observe.methods());
        all.add(new QueryApi(this).method());
        all.addAll(world.methods());
        all.addAll(action.methods());
        all.addAll(recipe.methods());
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

    /** The server hop, or the capability error a detached application answers with. */
    ServerThreadHop requireServerHop() {
        ServerThreadHop hop = serverHop();
        if (hop == null) throw new IllegalStateException("DriverApplication not attached to a server");
        return hop;
    }

    /** The last sequence number handed out; a {@code returnEvents} call reads past it once it is done. */
    long eventCursor() { return eventSeq.get(); }

    /**
     * With {@code wanted}, every event emitted after {@code cursor}, as the event stream carries it: what
     * {@code returnEvents:true} answers, so a caller needs no separate cursor read. Unrelated work emitting in
     * the same window shows up too, which is rare as the writes run on the server thread.
     */
    Optional<List<net.magicterra.worlddriver.api.methods.EventContracts.Event>> eventsAfter(long cursor, boolean wanted) {
        if (!wanted) return Optional.empty();
        return Optional.of(observe.eventsSince(cursor, null, EVENT_BUFFER_CAP).stream()
                .map(net.magicterra.worlddriver.api.EventWire::dto).toList());
    }
}
