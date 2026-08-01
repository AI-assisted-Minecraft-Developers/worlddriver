package net.magicterra.agent.rpc;

/**
 * Inbound size ceiling for every transport, in one place so they cannot disagree.
 *
 * <p>They did. {@code McpServer} capped a POST body at 8 MiB and said so; the
 * WebSocket server never set a frame size at all and silently inherited Netty's
 * 64 KiB default. The same {@code AgentApi} call therefore succeeded over MCP and
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
     * Largest inbound request, in bytes: a POST body on MCP, a WebSocket frame on
     * the RPC socket. Override with {@code -Dagent.maxRequestBytes=N}.
     *
     * <p>Replaces the MCP-only {@code agent.mcp.maxBodyBytes}. An {@code int}
     * because that is what Netty's frame-size parameter takes; the MCP side widens
     * it for its {@code Content-Length} comparison.
     */
    public static final int MAX_REQUEST_BYTES =
            Integer.getInteger("agent.maxRequestBytes", 8 * 1024 * 1024);
}
