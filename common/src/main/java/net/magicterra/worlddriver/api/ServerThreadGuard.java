package net.magicterra.worlddriver.api;

import java.util.function.BooleanSupplier;

/**
 * Refuses a call that would block the server thread waiting for something only the server thread
 * can do.
 *
 * <p>User scripts and {@code ScriptEvents} callbacks run on the server thread. A round-trip through
 * the local RPC or MCP endpoint lands in a route that hops back onto that thread, and an
 * {@code awaitMs} or {@code mc.wait.*} poll waits for ticks it is itself holding up; either way the
 * game froze until a timeout and the caller got an error that named the timeout, not the cause.
 * Failing at once with this message names the cause.
 *
 * <p>Global because the RPC and MCP clients hold no {@link DriverApi}; {@link DriverApi#attachServer}
 * installs the check and {@link DriverApi#detachServer} removes it.
 */
public final class ServerThreadGuard {
    private static final BooleanSupplier NONE = () -> false;
    private static volatile BooleanSupplier onServerThread = NONE;

    private ServerThreadGuard() {}

    /** Use {@code check} to tell whether the calling thread is the server thread. */
    public static void install(BooleanSupplier check) { onServerThread = check == null ? NONE : check; }

    /** No server thread to protect any more. */
    public static void uninstall() { onServerThread = NONE; }

    /** Throw when called on the server thread; {@code what} names the blocking call being refused. */
    public static void refuseBlocking(String what) {
        if (onServerThread.getAsBoolean()) {
            throw new IllegalStateException(what + " cannot run on the server thread: it waits for work "
                    + "only the server thread can do, so the game would stall until it timed out. "
                    + "Call it from a worker thread, or use background:true where the verb offers it");
        }
    }
}
