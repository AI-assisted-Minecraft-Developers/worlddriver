package net.magicterra.worlddriver.protocol;

/** Inbound request budget shared by every transport. */
public final class RequestLimits {
    private RequestLimits() {}

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
}
