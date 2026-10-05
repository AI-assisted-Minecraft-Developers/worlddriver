package net.magicterra.worlddriver.mcp;

/** HTTP request and event-stream concurrency limits. */
public final class McpLimits {
    private McpLimits() {}

    /** POSTs the MCP server runs at once; past it a request gets 503 with
     *  {@link net.magicterra.worlddriver.protocol.ServerBusyException#CODE}, for the same reason
     *  as the WebSocket cap. */
    public static final int MCP_MAX_IN_FLIGHT = 32;

    /** Open MCP event streams ({@code GET /mcp}); each parks a worker for its lifetime. */
    public static final int MCP_MAX_EVENT_STREAMS = 8;

    /**
     * Suspended {@code tools/call} waits (an awaited action). A suspended call holds neither a worker
     * nor a POST slot, only its exchange and one of these, so a full set of waits still leaves every
     * POST slot for observing and cancelling.
     */
    public static final int MCP_MAX_WAITS = 64;
}
