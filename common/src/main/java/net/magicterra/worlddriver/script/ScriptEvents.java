package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KubeJS-style event group exposed to scripts as the global {@code ScriptEvents}.
 *
 * Scripts register callbacks at load time:
 *   ScriptEvents.onAttach(function () { console.log("ready"); });
 *   ScriptEvents.tick(function () { ... });
 *
 * The mod fires {@link #fireTick()} from the platform's server tick event and
 * {@link #fireAttach()} once after the script manager finishes loading. All
 * callbacks run on whatever thread the firing platform hook uses (the server
 * thread for tick) — Rhino's ContextFactory is per-thread, so we re-enter().
 *
 * State is per-load: {@link #install} stores the factory/scope, {@link #clear}
 * wipes registered callbacks on reload.
 */
public final class ScriptEvents {
    private static final List<Function> tickCallbacks = new CopyOnWriteArrayList<>();
    private static final List<Function> attachCallbacks = new CopyOnWriteArrayList<>();

    private static volatile ContextFactory factory;
    private static volatile Scriptable scope;
    private static final AtomicLong tickCount = new AtomicLong();
    private static volatile boolean attachFired = false;

    private ScriptEvents() {}

    /** Called by ScriptManager once the scope is built. */
    public static void install(ContextFactory f, Scriptable s) {
        factory = f;
        scope = s;
        tickCount.set(0);
        attachFired = false;
    }

    /** Wipes all script-registered callbacks. Called on script reload. */
    public static void clear() {
        tickCallbacks.clear();
        attachCallbacks.clear();
        attachFired = false;
    }

    /** Register a callback to run every server tick. JS: {@code ScriptEvents.tick(fn)}. */
    public static void tick(Function cb) {
        if (cb != null) tickCallbacks.add(cb);
    }

    /**
     * Register a callback to run when the script loader finishes wiring up the
     * scope. If the attach phase has already happened (e.g., scripts loaded
     * before the first hook fires), the callback runs immediately.
     */
    public static void onAttach(Function cb) {
        if (cb == null) return;
        attachCallbacks.add(cb);
        if (attachFired) invoke(cb);
    }

    /** Fired by the platform server-tick hook. Off-server-thread is fine. */
    public static void fireTick() {
        if (factory == null || scope == null) return;
        tickCount.incrementAndGet();
        for (Function cb : tickCallbacks) {
            invoke(cb);
        }
    }

    /** Fired by ScriptManager once all scripts have been evaluated. */
    public static void fireAttach() {
        attachFired = true;
        for (Function cb : attachCallbacks) {
            invoke(cb);
        }
    }

    /** Visible to JS: total ticks observed since install. Useful for tests. */
    public static long tickCount() { return tickCount.get(); }

    /** Visible to JS: how many callbacks are registered (for introspection). */
    public static int tickListenerCount() { return tickCallbacks.size(); }

    private static void invoke(Function cb) {
        ContextFactory f = factory;
        Scriptable s = scope;
        if (f == null || s == null) return;
        Context cx = f.enter();
        try {
            cb.call(cx, s, s, new Object[]{});
        } catch (Throwable t) {
            System.err.println("[agent-events] callback failed: " + t.getMessage());
        }
        // This Rhino fork uses ThreadLocal contexts; no explicit exit needed.
    }
}
