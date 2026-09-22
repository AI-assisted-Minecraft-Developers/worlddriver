package net.magicterra.worlddriver.rpc;

/**
 * Inbound size ceiling for every transport, in one place so they cannot disagree.
 *
 * <p>They did. {@code McpServer} capped a POST body at 8 MiB and said so; the
 * WebSocket server never set a frame size at all and silently inherited Netty's
 * 64 KiB default. The same {@code DriverApi} call therefore succeeded over MCP and
 * failed over the WebSocket at 128× less payload — and failed in the worst way
 * available: a frame that exceeds the decoder's limit never assembles, so there is
 * no request to answer and no id to answer it with. The caller saw the connection
 * drop, not an error. Nothing tied the two numbers together, and nothing would have
 * noticed if only one of them changed.
 *
 * <p>8 MiB is well above any real request (the biggest inbound payload is a script
 * body for {@code mc.script.eval}; screenshots travel the other way) and small
 * enough that a single hostile frame cannot be a one-shot OOM. Both transports
 * buffer a whole request in memory, so the exposure is the same on each — which is
 * the argument for one number rather than two.
 */
public final class TransportLimits {
    private TransportLimits() {}

    /**
     * Largest inbound request, in bytes: a POST body on MCP, a WebSocket message on
     * the RPC socket (each frame, and a fragmented message's fragments together).
     * Override with {@code -Dworlddriver.maxRequestBytes=N}.
     *
     * <p>Replaces the MCP-only {@code agent.mcp.maxBodyBytes}. An {@code int}
     * because that is what Netty's frame-size parameter takes; the MCP side widens
     * it for its {@code Content-Length} comparison.
     */
    public static final int MAX_REQUEST_BYTES =
            Integer.getInteger("worlddriver.maxRequestBytes", 8 * 1024 * 1024);

    /**
     * Outbound bytes a WebSocket connection may have queued before it counts as unwritable,
     * and the level it must drain to before it counts as writable again. An event pushed to
     * an unwritable subscriber closes it; the client reconnects and replays from its cursor.
     * High enough that one large response (a full-size screenshot) cannot trip it alone.
     */
    public static final int WS_WRITE_BUFFER_LOW_BYTES = 8 * 1024 * 1024;
    public static final int WS_WRITE_BUFFER_HIGH_BYTES = 16 * 1024 * 1024;

    /** How long a WebSocket connection may go without receiving anything before the server
     *  pings it. Every client library answers a ping on its own. */
    public static final long WS_PING_INTERVAL_MS = 30_000L;

    /**
     * How long a connection may go without receiving anything at all — no pong, no frame —
     * before it is closed as half-open. Not a few missed pings: some client libraries answer
     * a ping only from inside a read, and a client may legitimately sit a whole
     * {@code mc.wait.*} budget (two minutes) between reads. Twice that budget.
     */
    public static final long WS_IDLE_CLOSE_MS = 240_000L;
}
