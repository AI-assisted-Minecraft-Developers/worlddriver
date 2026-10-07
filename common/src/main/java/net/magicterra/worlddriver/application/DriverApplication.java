package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.ApiBatch;
import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.ApiRegistry;
import net.magicterra.worlddriver.api.EventWire;
import net.magicterra.worlddriver.api.WorldCell;
import net.magicterra.worlddriver.api.ServerThreadGuard;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.methods.EventContracts;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.model.DriverEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.magicterra.worlddriver.client.ClientHooks;
import net.magicterra.worlddriver.client.ClientDriverApi;

/* Ring buffer cap keeps memory bounded for long-running servers. Old events
 * roll off; eventsSince(cursor) on an out-of-window cursor returns whatever
 * is still retained. */

/**
 * The core methods' handlers, bound to their contracts and published as the {@code worlddriver/core}
 * batch. Operates on the live ServerLevel, dispatching writes to the server thread.
 *
 * The {@code mc.system/observe/action/wait} verb groups are implemented in the
 * sibling {@link SystemApi} / {@link ObserveApi} / {@link ActionApi} /
 * {@link WaitApi} handlers (each holding a back-reference to this instance for
 * the shared server handle, event ring buffer, and server-thread hop); pure
 * parse/encode helpers live in {@link ApiSupport}. Every transport dispatches
 * through the registry {@link DriverApi} holds, so a core method is wired here
 * and in {@link CoreMethods}, nowhere else.
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
    private final ExecutorService eventDispatch = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("agent-event-dispatch").daemon().factory());
    private volatile ScriptHost scriptHost;

    public DriverApplication() {
        registry().registerBatch(coreBatch);
    }

    /** The core methods, published once; a retry would hand over this same object. */
    private final ApiBatch coreBatch = new ApiBatch(ApiRegistry.CORE_OWNER, "core", coreMethods());

    private List<ApiMethod<?, ?>> coreMethods() {
        List<ApiMethod<?, ?>> all = new ArrayList<>(new BotActions(this).methods());
        all.addAll(new ScriptMethods(() -> scriptHost).methods());
        all.addAll(eventsApi.methods());
        all.addAll(wait.methods());
        all.addAll(system.methods());
        all.addAll(observe.methods());
        all.addAll(new QueryApi(this).methods());
        all.addAll(world.methods());
        all.addAll(action.methods());
        all.addAll(recipe.methods());
        all.addAll(new ClientMethods(this).methods());
        return all;
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
    public void emitExternal(String type, BlockPos pos, JsonValue data) {
        emit(type, pos, data);
    }

    /** Append an event to the ring buffer (for replay via {@code mc.observe.eventsSince})
     *  AND fan it out to live push subscribers. The buffer append is synchronous and
     *  cheap; listener delivery is handed to the single-thread {@link #eventDispatch}
     *  so the calling thread (server tick / client tick / watcher) never blocks on a
     *  socket write. Returns the assigned sequence number. */
    long emit(String type, BlockPos pos, JsonValue data) {
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
    Optional<List<EventContracts.Event>> eventsAfter(long cursor, boolean wanted) {
        if (!wanted) return Optional.empty();
        return Optional.of(observe.eventsSince(cursor, null, EVENT_BUFFER_CAP).stream()
                .map(EventWire::dto).toList());
    }
}
