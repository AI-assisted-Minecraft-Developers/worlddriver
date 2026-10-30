package net.magicterra.worlddriver.rpc;

/** WebSocket connection, liveness and worker limits. */
public final class TransportLimits {

    /**
     * Outbound bytes a WebSocket connection may have queued before it counts as unwritable, and the level it must drain
     * to before it counts as writable again. An event pushed to an unwritable subscriber closes it; the client
     * reconnects and replays from its cursor. High enough that one large response (a full-size screenshot) cannot trip
     * it alone.
     */
    public static final int WS_WRITE_BUFFER_LOW_BYTES = 8 * 1024 * 1024;

    public static final int WS_WRITE_BUFFER_HIGH_BYTES = 16 * 1024 * 1024;

    /**
     * How long a WebSocket connection may go without receiving anything before the server pings it. Every client
     * library answers a ping on its own.
     */
    public static final long WS_PING_INTERVAL_MS = 30_000L;

    /**
     * How long a connection may go without receiving anything at all — no pong, no frame — before it is closed as
     * half-open. Not a few missed pings: some client libraries answer a ping only from inside a read, and a client may
     * legitimately sit a whole {@code mc.wait.*} budget (two minutes) between reads. Twice that budget.
     */
    public static final long WS_IDLE_CLOSE_MS = 240_000L;

    /**
     * Requests one WebSocket connection may have running at once. Past it a request is answered at once with the
     * {@code server_busy} code instead of taking a thread: a slow call holds its worker until it answers, so an
     * unbounded loop on one socket would otherwise exhaust the JVM's native threads and take the game down. A wait
     * gives its worker back and counts against {@link #RPC_MAX_WAITS_PER_CONNECTION} instead.
     */
    public static final int RPC_MAX_IN_FLIGHT_PER_CONNECTION = 16;

    /** Requests the WebSocket server runs at once, each on a virtual thread, across all connections. */
    public static final int RPC_MAX_WORKERS = 64;

    /**
     * Suspended waits (an awaited action, a wait method) one connection may hold. A suspended wait holds no worker, so
     * these are counted apart from {@link #RPC_MAX_IN_FLIGHT_PER_CONNECTION}: a connection with every wait permit taken
     * can still observe and cancel.
     */
    public static final int RPC_MAX_WAITS_PER_CONNECTION = 32;

    /** Suspended waits across all connections; past either bound a new wait is refused before it starts. */
    public static final int RPC_MAX_WAITS = 128;

    private TransportLimits() {}
}
