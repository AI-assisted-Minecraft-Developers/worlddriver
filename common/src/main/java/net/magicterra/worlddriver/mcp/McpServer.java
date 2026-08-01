package net.magicterra.worlddriver.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.AgentApi;
import net.magicterra.worlddriver.model.AgentEvent;
import net.magicterra.worlddriver.rpc.EventNotifications;
import net.magicterra.worlddriver.rpc.JsonCodec;
import net.magicterra.worlddriver.rpc.TransportLimits;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Minimal MCP (Model Context Protocol) server speaking JSON-RPC 2.0 over HTTP.
 *
 * Transport conformance with the Streamable HTTP spec (2025-06-18, see
 * https://modelcontextprotocol.io/specification/2025-06-18/basic/transports):
 *
 *  - Single endpoint at {@code /mcp} supports POST and GET.
 *    POST carries client requests; GET (with {@code Accept: text/event-stream})
 *    opens the spec's server→client SSE stream — that is how the driver pushes
 *    event notifications to an MCP client (see "Server push" below). A GET without
 *    that Accept gets 405, which the spec permits.
 *  - POST with a JSON-RPC request → 200 with {@code application/json} body
 *    (the spec allows either application/json or text/event-stream; we pick
 *    application/json — no SSE needed for stateless tool calls).
 *  - POST with a JSON-RPC notification (no id) → 202 Accepted with empty body.
 *  - {@code Origin} header is validated to defend against DNS rebinding
 *    (spec MUST). Same-origin / curl requests with no Origin pass through.
 *  - Bound to 127.0.0.1 only.
 *  - Protocol version is negotiated in {@code initialize}: we echo the
 *    client's requested version if we know it, otherwise return our latest.
 *
 * <b>Server push (driver→agent event notifications).</b> Per the Streamable HTTP
 * spec, a client opens an SSE stream with {@code GET /mcp} (Accept:
 * text/event-stream); the server then sends server-initiated JSON-RPC messages on
 * it. We push each driver event as a {@code notifications/message} (the MCP logging
 * notification — we advertise the {@code logging} capability in {@code initialize},
 * and accept {@code logging/setLevel}, though we deliberately do NOT apply it to the
 * driver event stream; see {@link #onEvent} for why). The frame is
 * byte-identical to the one the WebSocket {@code /rpc} transport sends (shared
 * {@link EventNotifications}). Simplification: events fan out to ALL open GET
 * streams rather than being correlated to a session via {@code Mcp-Session-Id}
 * (fine for the localhost single-agent setup; add session routing if that changes).
 *
 * Supported MCP methods:
 *   initialize, notifications/initialized, tools/list, tools/call, ping,
 *   logging/setLevel; GET opens the server→client notification stream
 *
 * Routes tools/call -> AgentApi.route(method, params) — the same code path
 * in-JVM scripts use. Errors from AgentApi turn into MCP tool-error results
 * (isError=true), not transport-level JSON-RPC errors, per MCP convention.
 */
public final class McpServer implements Closeable {
    /** Protocol versions we know how to speak. Ordered newest-first. */
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final String LATEST_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS.get(0);
    private static final String SERVER_NAME = "worlddriver";
    private static final String SERVER_VERSION = "0.1.0-dev";
    /** Maximum inbound POST body — shared with the WebSocket transport's frame
     *  limit so the two cannot disagree about what a request may weigh. See
     *  {@link TransportLimits}. */
    private static final long MAX_BODY_BYTES = TransportLimits.MAX_REQUEST_BYTES;

    private final AgentApi api;
    private final HttpServer http;
    /** Open server→client SSE streams (clients that issued {@code GET /mcp}).
     *  {@link #onEvent} fans each driver event out to all of them. */
    private final Set<SseSubscriber> sse = ConcurrentHashMap.newKeySet();

    public McpServer(AgentApi api, int port) throws IOException {
        this(api, "127.0.0.1", port);
    }

    public McpServer(AgentApi api, String bindHost, int port) throws IOException {
        this.api = api;
        this.http = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.http.createContext("/mcp", this::handle);
        this.http.createContext("/", this::handleRoot);
        this.http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-mcp-worker");
            t.setDaemon(true);
            return t;
        }));
        this.http.start();
        api.addEventListener(this::onEvent);
    }

    public int port() { return http.getAddress().getPort(); }

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!checkOrigin(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "method not allowed"); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><h1>Agent Driver MCP</h1>");
        sb.append("<p>POST JSON-RPC 2.0 to /mcp. Tools:</p><ul>");
        for (Map<String, Object> t : ToolCatalog.tools()) {
            sb.append("<li><code>").append(t.get("name")).append("</code> — ")
              .append(t.get("description")).append("</li>");
        }
        sb.append("</ul></body></html>");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex) throws IOException {
        if (!checkOrigin(ex)) return;
        // GET with Accept: text/event-stream opens the spec's server→client SSE
        // stream — our event-notification push. Any other GET → 405 (allowed).
        if ("GET".equals(ex.getRequestMethod())) {
            String accept = ex.getRequestHeaders().getFirst("Accept");
            if (accept != null && accept.contains("text/event-stream")) { handleSse(ex); return; }
            send(ex, 405, "method not allowed (GET requires Accept: text/event-stream for the event stream)");
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "method not allowed"); return; }

        // Pre-flight Content-Length check (cheap path). When the header is
        // missing, fall back to a bounded read that aborts past MAX_BODY_BYTES.
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        if (cl != null) {
            try {
                long n = Long.parseLong(cl);
                if (n > MAX_BODY_BYTES) {
                    send(ex, 413, "payload too large: " + n + " > " + MAX_BODY_BYTES);
                    return;
                }
            } catch (NumberFormatException ignored) { /* fall through to bounded read */ }
        }
        byte[] raw = readBounded(ex.getRequestBody(), MAX_BODY_BYTES);
        if (raw == null) { send(ex, 413, "payload exceeds " + MAX_BODY_BYTES + " bytes"); return; }
        String body = new String(raw, StandardCharsets.UTF_8);

        Object decoded;
        try { decoded = JsonCodec.decode(body); }
        catch (Throwable e) { sendJson(ex, 400, jsonRpcError(null, -32700, "parse error: " + e.getMessage())); return; }

        if (!(decoded instanceof Map<?, ?> req)) {
            sendJson(ex, 400, jsonRpcError(null, -32600, "request must be JSON object"));
            return;
        }
        Object id = req.get("id");
        String method = (String) req.get("method");
        Map<String, Object> params = (req.get("params") instanceof Map<?, ?> mp)
                ? (Map<String, Object>) mp : Map.of();

        if (method == null) { sendJson(ex, 400, jsonRpcError(id, -32600, "missing method")); return; }

        // Notifications have no id -> respond 202 with empty body, do work fire-and-forget
        boolean isNotification = (id == null);

        try {
            switch (method) {
                case "initialize" -> {
                    // Version negotiation per spec lifecycle: echo the client's
                    // requested version when we support it, otherwise return our
                    // latest. Clients that disagree may then disconnect.
                    String requested = (params.get("protocolVersion") instanceof String s) ? s : null;
                    String negotiated = (requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested))
                            ? requested
                            : LATEST_PROTOCOL_VERSION;
                    Map<String, Object> result = Map.of(
                        "protocolVersion", negotiated,
                        // listChanged=false: the catalog is fixed by the time any client
                        // initializes. Optional subsystems append schemas via
                        // ToolCatalog.registerExtra at client-register, which runs before any
                        // external client connects — so the list never changes mid-session.
                        // Clients that respect this skip subscribing to notifications/tools/list_changed.
                        // logging:{} advertises we send notifications/message (our
                        // event push on the GET SSE stream).
                        "capabilities", Map.of(
                            "tools", Map.of("listChanged", false),
                            "logging", Map.of()),
                        "serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION)
                    );
                    sendJson(ex, 200, jsonRpcResult(id, result));
                }
                case "notifications/initialized" -> {
                    // Spec: client tells server initialization complete. No response required.
                    sendNoBody(ex, isNotification ? 202 : 200);
                }
                case "logging/setLevel" -> {
                    // spec: 2025-06-18 §Logging — accept and acknowledge. The level is
                    // deliberately NOT applied to the driver event stream (see onEvent),
                    // and storing it did nothing but make the field look load-bearing:
                    // it was written here and read nowhere.
                    sendJson(ex, 200, jsonRpcResult(id, Map.of()));
                }
                case "ping" -> sendJson(ex, 200, jsonRpcResult(id, Map.of()));
                case "tools/list" -> {
                    Map<String, Object> result = Map.of("tools", ToolCatalog.tools());
                    sendJson(ex, 200, jsonRpcResult(id, result));
                }
                case "tools/call" -> {
                    String toolName = (String) params.get("name");
                    Map<String, Object> args = (params.get("arguments") instanceof Map<?, ?> am)
                            ? (Map<String, Object>) am : Map.of();
                    if (toolName == null) {
                        sendJson(ex, 400, jsonRpcError(id, -32602, "missing tool name"));
                        return;
                    }
                    if (!api.methods().contains(toolName)) {
                        sendJson(ex, 200, jsonRpcResult(id, toolError("unknown tool: " + toolName)));
                        return;
                    }
                    try {
                        Object result = api.route(toolName, args);
                        sendJson(ex, 200, jsonRpcResult(id, toolResultContent(result)));
                    } catch (Throwable t) {
                        sendJson(ex, 200, jsonRpcResult(id, toolError(t.getMessage())));
                    }
                }
                default -> sendJson(ex, 200, jsonRpcError(id, -32601, "method not found: " + method));
            }
        } catch (Throwable t) {
            sendJson(ex, 200, jsonRpcError(id, -32603, "internal: " + t.getMessage()));
        }
    }

    /**
     * {@code GET /mcp} (Accept: text/event-stream) — the spec's server→client SSE
     * stream. Keeps the exchange open and parks the worker thread for the life of
     * the connection, writing one {@code data:} frame (a JSON-RPC
     * {@code notifications/message}) per driver event, plus a heartbeat comment
     * every 15 s so a silently-dropped peer is detected.
     */
    private void handleSse(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0); // 0 = open-ended (chunked); stream stays open

        SseSubscriber sub = new SseSubscriber(ex.getResponseBody());
        sse.add(sub);
        sub.writeBlocking(": connected\n\n"); // flushes headers; dies here if the client already left
        try {
            // This thread is parked for the life of the connection anyway, so it does
            // the writing. It used to only emit keepalives while the event dispatcher
            // wrote the frames — see SseSubscriber.offer for why that was the wrong
            // thread to do it on.
            sub.pumpUntilClosed();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            sse.remove(sub);
            sub.die();
            try { ex.getResponseBody().close(); } catch (IOException ignored) {}
            ex.close();
        }
    }

    /** Fan one driver event out to every open SSE stream as a
     *  {@code notifications/message}. EVERY event pushes by default; the only filter is
     *  the per-type opt-out {@link BotConfig#mutedEvents} (mc.bot.setting{mutedEvents}).
     *  We intentionally do NOT gate on the client's {@code logging/setLevel} minimum —
     *  driver events are domain signals the Agent asked for, and a client that defaults
     *  its filter to {@code warning} would otherwise silently drop every info/notice
     *  event. The frame still carries a severity {@code level} for display/ordering.
     *  Runs on AgentApi's event-dispatch thread. */
    private void onEvent(AgentEvent e) {
        if (sse.isEmpty()) return;
        // No mutedEvents check here: AgentApi applies the per-type opt-out before it
        // calls any listener, so every transport gets the same policy for free.
        String frame = "data: " + EventNotifications.frame(e) + "\n\n";
        for (SseSubscriber sub : sse) sub.offer(frame);  // never blocks: see SseSubscriber.offer
    }

    /** One open SSE connection: its output stream + a latch the parked handler
     *  thread waits on. Writes are synchronized and fail-closed. */
    /** Package-private rather than private so {@code SseBackpressureTest} can drive one
     *  against a deliberately-stalled stream; there is no other way to prove the event
     *  dispatcher stops blocking without wedging a real TCP receive window. */
    static final class SseSubscriber {
        /** Frames buffered for this one client. Bounded on purpose — see {@link #offer}. */
        static final int OUTBOX_CAP = 256;

        private final OutputStream os;
        private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(OUTBOX_CAP);
        volatile boolean alive = true;
        final CountDownLatch done = new CountDownLatch(1);

        SseSubscriber(OutputStream os) { this.os = os; }

        /**
         * Hand a frame to THIS subscriber's writer without touching the socket.
         *
         * <p>{@link #onEvent} runs on AgentApi's single event-dispatch thread, shared
         * by every listener — the WebSocket transport included. Writing the socket
         * there (which is what this class used to do) meant one SSE client whose TCP
         * receive window had filled blocked that thread inside {@code os.write}, and
         * with it every other SSE subscriber, the WebSocket push channel, and the
         * dispatch queue, which is unbounded and would grow for as long as the stall
         * lasted. The WebSocket side never had this problem: Netty's
         * {@code writeAndFlush} is async. This closes that asymmetry.
         *
         * <p>Overflow closes the stream rather than dropping frames. A consumer that
         * silently misses events is the worse failure — it cannot tell that it did.
         * Closing is loud and recoverable: the client sees EOF, reconnects, and
         * replays from its cursor with {@code mc.observe.eventsSince}, which is what
         * the event ring buffer is for.
         */
        void offer(String s) {
            if (!alive) return;
            if (!outbox.offer(s)) {
                WorldDriverCommon.LOG.warn(
                        "[mcp] SSE consumer fell more than {} frames behind — closing its stream; "
                        + "reconnect and replay with mc.observe.eventsSince{cursor}", OUTBOX_CAP);
                die();
            }
        }

        /** Drain and write until the stream dies. Runs on the parked HTTP worker
         *  thread that is already dedicated to this connection, so the blocking
         *  writes cost nothing extra: no new thread, and the keepalive it used to
         *  send is now just what happens when the outbox is idle. */
        void pumpUntilClosed() throws InterruptedException {
            while (alive) {
                String frame = outbox.poll(15, TimeUnit.SECONDS);
                if (!alive) break;
                writeBlocking(frame == null ? ": ping\n\n" : frame);
            }
        }

        /** Only ever called from this subscriber's own writer thread (or from
         *  handleSse before the pump starts), never from the event dispatcher. */
        synchronized void writeBlocking(String s) {
            if (!alive || s.isEmpty()) return;
            try {
                os.write(s.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                die();
            }
        }

        void die() {
            alive = false;
            done.countDown();
            outbox.offer("");  // best-effort nudge so an idle pump notices at once
                               // (a full outbox means the pump is stuck in write, not poll)
        }
    }

    /**
     * Wraps {@code AgentApi.route()} output into MCP tool result content. For
     * image-shaped Maps (format=png/jpeg/... + base64 keys), emits a small text
     * block with the non-base64 metadata as JSON, plus a real {@code image}
     * content block so multimodal LLMs receive it as vision input rather than
     * as a giant base64 string inside text. The companion {@code McpBridge}
     * merges the image bytes back under {@code base64} so JS round-trip tests
     * see the same shape as in-JVM calls.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolResultContent(Object result) {
        if (result instanceof Map<?, ?> raw
                && raw.get("format") instanceof String fmt
                && raw.get("base64") instanceof String b64
                && !b64.isEmpty()
                && isImageMimeFormat(fmt)) {
            Map<String, Object> meta = new LinkedHashMap<>((Map<String, Object>) raw);
            meta.remove("base64");
            Map<String, Object> textBlock = Map.of("type", "text", "text", JsonCodec.encode(meta));
            Map<String, Object> imageBlock = Map.of(
                    "type", "image",
                    "data", b64,
                    "mimeType", "image/" + fmt
            );
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("content", List.of(textBlock, imageBlock));
            m.put("isError", false);
            return m;
        }
        return toolText(result);
    }

    private static boolean isImageMimeFormat(String fmt) {
        return "png".equals(fmt) || "jpeg".equals(fmt) || "jpg".equals(fmt)
                || "webp".equals(fmt) || "gif".equals(fmt);
    }

    private static Map<String, Object> toolText(Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", JsonCodec.encode(result))));
        m.put("isError", false);
        return m;
    }

    /**
     * DNS-rebinding defense (spec MUST). Browser-originated requests carry an
     * {@code Origin} header reflecting the page that initiated them; we only
     * accept loopback. Non-browser clients (curl, Claude Desktop, MCP Inspector)
     * typically send no Origin — those pass through.
     *
     * Returns true when the request should be processed, false when a 403 has
     * already been written and the caller should bail.
     */
    private static boolean checkOrigin(HttpExchange ex) throws IOException {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (isAllowedOrigin(origin)) return true;
        send(ex, 403, "forbidden origin: " + origin);
        return false;
    }

    private static boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isEmpty() || "null".equals(origin)) return true;
        try {
            String host = URI.create(origin).getHost();
            if (host == null) return false;
            return "localhost".equals(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Map<String, Object> toolError(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", message == null ? "error" : message)));
        m.put("isError", true);
        return m;
    }

    private static String jsonRpcResult(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id == null ? null : id);
        m.put("result", result);
        return JsonCodec.encode(m);
    }

    private static String jsonRpcError(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id == null ? null : id);
        m.put("error", Map.of("code", code, "message", message));
        return JsonCodec.encode(m);
    }

    private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendNoBody(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
        ex.getResponseBody().close();
    }

    /** Read at most {@code cap} bytes, returning the buffer or {@code null} if
     *  the stream provides more than cap. We can't just call readAllBytes() — a
     *  client misbehaving (or worse) could OOM us; defense in depth even on loopback. */
    private static byte[] readBounded(java.io.InputStream in, long cap) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int r;
        while ((r = in.read(buf)) >= 0) {
            total += r;
            if (total > cap) return null;
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    @Override public void close() {
        for (SseSubscriber sub : sse) sub.die();
        sse.clear();
        http.stop(0);
    }
}
