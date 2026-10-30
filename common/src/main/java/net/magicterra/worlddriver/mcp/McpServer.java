package net.magicterra.worlddriver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.call.CancellationToken;
import net.magicterra.worlddriver.api.call.WaitPermits;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.EventFeed;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.config.BuildStamp;
import net.magicterra.worlddriver.rpc.EventNotifications;
import net.magicterra.worlddriver.rpc.JsonRpcFrames;
import net.magicterra.worlddriver.rpc.OriginPolicy;
import net.magicterra.worlddriver.rpc.RequestLimits;

/**
 * Minimal MCP (Model Context Protocol) server speaking JSON-RPC 2.0 over HTTP.
 *
 * <p>Transport conformance with the Streamable HTTP spec (2025-06-18, see
 * https://modelcontextprotocol.io/specification/2025-06-18/basic/transports):
 *
 * <p>- Single endpoint at {@code /mcp} supports POST and GET. POST carries client requests; GET (with {@code Accept:
 * text/event-stream}) opens the spec's server→client SSE stream — that is how the driver pushes event notifications to
 * an MCP client (see "Server push" below). A GET without that Accept gets 405, which the spec permits. - POST with a
 * JSON-RPC request → 200 with {@code application/json} body (the spec allows either application/json or
 * text/event-stream; we pick application/json — no SSE needed for stateless tool calls). - POST with a JSON-RPC
 * notification (no id, any method) → 202 Accepted with empty body. It is acknowledged, not dispatched;
 * {@code notifications/cancelled} therefore does not interrupt the request it names. - POST whose Content-Type is not
 * {@code application/json} → 415. - {@code Origin} header is validated to defend against DNS rebinding (spec MUST) by
 * {@link OriginPolicy}: absent or loopback passes, the literal {@code null} and everything else get 403. - Bound to
 * 127.0.0.1 only. - Protocol version is negotiated in {@code initialize}: we echo the client's requested version if we
 * know it, otherwise return our latest.
 *
 * <p><b>Server push (driver→agent event notifications).</b> Per the Streamable HTTP spec, a client opens an SSE stream
 * with {@code GET /mcp} (Accept: text/event-stream); the server then sends server-initiated JSON-RPC messages on it. We
 * push each driver event as a {@code notifications/message} (the MCP logging notification — we advertise the
 * {@code logging} capability in {@code initialize}, and accept {@code logging/setLevel}, though we deliberately do NOT
 * apply it to the driver event stream; see {@link #onEvent} for why). The frame is byte-identical to the one the
 * WebSocket {@code /rpc} transport sends (shared {@link EventNotifications}). Simplification: events fan out to ALL
 * open GET streams rather than being correlated to a session via {@code Mcp-Session-Id} (fine for the localhost
 * single-agent setup; add session routing if that changes).
 *
 * <p>Supported MCP methods: initialize, notifications/initialized, tools/list, tools/call, ping, logging/setLevel; GET
 * opens the server→client notification stream
 *
 * <p>Routes tools/call -> ApiRegistry.dispatch(method, params, context) — the same code path in-JVM scripts use. Errors
 * from the registry turn into MCP tool-error results (isError=true), not transport-level JSON-RPC errors, per MCP
 * convention.
 */
public final class McpServer implements Closeable {
    /** The one protocol version spoken: the first with outputSchema and structuredContent. */
    static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String SERVER_NAME = "worlddriver";
    /**
     * Maximum inbound POST body — shared with the WebSocket transport's frame limit so the two cannot disagree about
     * what a request may weigh. See {@link RequestLimits}.
     */
    private static final int MAX_BODY_BYTES = RequestLimits.MAX_REQUEST_BYTES;

    /** Suspended MCP waits across every client; past it a new wait is refused before it starts. */
    private static final WaitPermits WAITS = WaitPermits.bounded(McpLimits.MCP_MAX_WAITS);

    private final ApiRegistry api;
    private final HttpServer http;
    private final ThreadPoolExecutor executor;
    private final Semaphore postSlots = new Semaphore(McpLimits.MCP_MAX_IN_FLIGHT);
    private final Semaphore streamSlots = new Semaphore(McpLimits.MCP_MAX_EVENT_STREAMS);
    /**
     * Open server→client SSE streams (clients that issued {@code GET /mcp}). {@link #onEvent} fans each driver event
     * out to all of them.
     */
    private final Set<SseSubscriber> sse = ConcurrentHashMap.newKeySet();

    /** The tool names {@code tools/list} showed last; a publish that changes them is announced. */
    private volatile Set<String> listed;

    public McpServer(ApiRegistry api, EventFeed events, int port) throws IOException {
        this(api, events, "127.0.0.1", port);
    }

    public McpServer(ApiRegistry api, EventFeed events, String bindHost, int port) throws IOException {
        this.api = api;
        this.http = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.http.createContext("/mcp", this::handle);
        this.http.createContext("/", this::handleRoot);
        // More threads than both caps together, so a request past a cap still gets a
        // thread to be told it is busy. Only a burst past threads + queue is dropped
        // without an answer, which is the HTTP server's own behaviour on rejection.
        // Platform threads, unlike the RPC workers: the JDK HTTP server writes a response
        // inside a synchronized block, and on JDK 21 a virtual thread blocked there pins its
        // carrier, so a few slow readers would stall every other virtual thread in the JVM.
        int threads = McpLimits.MCP_MAX_IN_FLIGHT + McpLimits.MCP_MAX_EVENT_STREAMS + 8;
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                Thread.ofPlatform().name("agent-mcp-worker").daemon().factory());
        this.executor.allowCoreThreadTimeOut(true);
        this.http.setExecutor(executor);
        this.http.start();
        events.addEventListener(this::onEvent);
        this.listed = listedNames();
        api.addListener(snapshot -> onRegistryChange());
    }

    private Set<String> listedNames() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (JsonObject t : McpTools.list(api)) {
            names.add(t.get("name").getAsString());
        }
        return names;
    }

    /**
     * Tell every open GET stream that the tool list changed, when a newly published batch changed what
     * {@code tools/list} shows; a hidden method's registration changes nothing visible and is not announced. A client
     * without an open stream reads the new list whenever it next asks.
     */
    private void onRegistryChange() {
        Set<String> now = listedNames();
        if (now.equals(listed)) {
            return;
        }
        listed = now;
        JsonObject changed = new JsonObject();
        changed.addProperty("jsonrpc", "2.0");
        changed.addProperty("method", "notifications/tools/list_changed");
        String frame = "data: " + changed + "\n\n";
        for (SseSubscriber sub : sse) {
            sub.offer(frame);
        }
    }

    public int port() {
        return http.getAddress().getPort();
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!checkOrigin(ex)) {
            return;
        }
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "method not allowed");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><h1>Agent Driver MCP</h1>");
        sb.append("<p>POST JSON-RPC 2.0 to /mcp. Tools:</p><ul>");
        for (JsonObject t : McpTools.list(api)) {
            sb.append("<li><code>")
                    .append(t.get("name").getAsString())
                    .append("</code> — ")
                    .append(t.get("description").getAsString())
                    .append("</li>");
        }
        sb.append("</ul></body></html>");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex) throws IOException {
        if (!checkOrigin(ex)) {
            return;
        }
        // GET with Accept: text/event-stream opens the spec's server→client SSE
        // stream — our event-notification push. Any other GET → 405 (allowed).
        if ("GET".equals(ex.getRequestMethod())) {
            String accept = ex.getRequestHeaders().getFirst("Accept");
            if (accept != null && accept.contains("text/event-stream")) {
                handleSse(ex);
                return;
            }
            send(ex, 405, "method not allowed (GET requires Accept: text/event-stream for the event stream)");
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "method not allowed");
            return;
        }
        // Not a spec requirement: a no-cors browser fetch may only send the CORS "simple"
        // types, which skip the preflight, so demanding application/json keeps web pages
        // out of tools/call even when the Origin check cannot see them.
        if (!isJsonContentType(ex.getRequestHeaders().getFirst("Content-Type"))) {
            send(ex, 415, "unsupported media type: POST requires Content-Type: application/json");
            return;
        }

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
            } catch (NumberFormatException ignored) {
                /* fall through to bounded read */
            }
        }
        // Never readAllBytes(): a client that keeps sending would fill the heap. One byte past the cap
        // tells an oversized body from one exactly at it.
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            send(ex, 413, "payload exceeds " + MAX_BODY_BYTES + " bytes");
            return;
        }

        JsonElement decoded;
        try {
            decoded = JsonParser.parseReader(
                    new InputStreamReader(new ByteArrayInputStream(raw), StandardCharsets.UTF_8));
        } catch (Throwable e) {
            sendJson(ex, 400, jsonRpcError(null, -32_700, "parse error: " + e.getMessage()));
            return;
        }

        if (!(decoded instanceof JsonObject req)) {
            sendJson(ex, 400, jsonRpcError(null, -32_600, "request must be JSON object"));
            return;
        }
        JsonElement id = req.get("id");
        if (!JsonRpcFrames.validId(id)) {
            sendJson(ex, 400, jsonRpcError(null, -32_600, "id must be a string, an integer or null"));
            return;
        }
        // A cast here threw out of the handler, and the HTTP server answers that by dropping
        // the connection without a response.
        String method = string(req.get("method"));
        JsonObject params = req.get("params") instanceof JsonObject mp ? mp : new JsonObject();

        if (method == null) {
            sendJson(ex, 400, jsonRpcError(id, -32_600, "missing or non-string method"));
            return;
        }

        // spec: 2025-06-18 §Transports — an accepted notification gets 202 with no bot.
        // Nothing is dispatched: every client→server notification MCP defines is advisory
        // here, and an id-less tools/call would run a verb whose result nobody can read.
        if (id == null || id.isJsonNull()) {
            sendNoBody(ex, 202);
            return;
        }

        if (!postSlots.tryAcquire()) {
            sendJson(
                    ex,
                    503,
                    jsonRpcError(
                            id,
                            ApiError.Code.SERVER_BUSY.rpcCode,
                            "server busy: " + McpLimits.MCP_MAX_IN_FLIGHT + " requests already running; retry later"));
            return;
        }
        try {
            switch (method) {
                case "initialize" -> {
                    // One protocol version: tool entries always carry outputSchema and results
                    // always carry structuredContent, which older versions do not define. A
                    // client asking for another version is told so (spec: lifecycle, error
                    // handling) rather than handed a session in a version it did not ask for.
                    String requested = string(params.get("protocolVersion"));
                    if (!PROTOCOL_VERSION.equals(requested)) {
                        JsonObject data = new JsonObject();
                        JsonArray supported = new JsonArray();
                        supported.add(PROTOCOL_VERSION);
                        data.add("supported", supported);
                        data.addProperty("requested", requested);
                        sendJson(
                                ex,
                                200,
                                JsonRpcFrames.error(
                                                id, JsonRpcFrames.INVALID_PARAMS, "Unsupported protocol version", data)
                                        .toString());
                        return;
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("protocolVersion", PROTOCOL_VERSION);
                    // listChanged: a batch registered after initialize (StageWright's mc.test.*,
                    // a mod's verbs) is announced with notifications/tools/list_changed on every
                    // open GET stream. logging:{} advertises notifications/message (the event push).
                    JsonObject tools = new JsonObject();
                    tools.addProperty("listChanged", true);
                    JsonObject capabilities = new JsonObject();
                    capabilities.add("tools", tools);
                    capabilities.add("logging", new JsonObject());
                    result.add("capabilities", capabilities);
                    JsonObject serverInfo = new JsonObject();
                    serverInfo.addProperty("name", SERVER_NAME);
                    serverInfo.addProperty("version", BuildStamp.version());
                    result.add("serverInfo", serverInfo);
                    sendJson(ex, 200, jsonRpcResult(id, result));
                }
                case "notifications/initialized" -> {
                    // Only reached when a client sent it WITH an id, which the spec does not
                    // define; acknowledge rather than call it an unknown method.
                    sendNoBody(ex, 200);
                }
                case "logging/setLevel" -> {
                    // spec: 2025-06-18 §Logging — accept and acknowledge. The level is
                    // deliberately NOT applied to the driver event stream (see onEvent),
                    // and storing it did nothing but make the field look load-bearing:
                    // it was written here and read nowhere.
                    sendJson(ex, 200, jsonRpcResult(id, new JsonObject()));
                }
                case "ping" -> sendJson(ex, 200, jsonRpcResult(id, new JsonObject()));
                case "tools/list" -> {
                    JsonArray tools = new JsonArray();
                    for (JsonObject t : McpTools.list(api)) {
                        tools.add(t);
                    }
                    JsonObject result = new JsonObject();
                    result.add("tools", tools);
                    sendJson(ex, 200, jsonRpcResult(id, result));
                }
                case "tools/call" -> {
                    String toolName = string(params.get("name"));
                    if (toolName == null) {
                        sendJson(ex, 200, jsonRpcError(id, -32_602, "missing tool name"));
                        return;
                    }
                    if (!api.methods().contains(toolName)) {
                        // spec: tools, error handling — an unknown tool is a protocol error.
                        ApiError unknown = ApiError.of(ApiError.Code.METHOD_NOT_FOUND, "unknown tool: " + toolName);
                        sendJson(
                                ex,
                                200,
                                JsonRpcFrames.error(
                                                id,
                                                JsonRpcFrames.INVALID_PARAMS,
                                                unknown.message(),
                                                ApiErrors.toTree(unknown))
                                        .toString());
                        return;
                    }
                    // Absent arguments are no arguments; present ones that are not an object are reported
                    // by the method's own decode, never turned into {}.
                    JsonElement args = params.get("arguments");
                    CallContext context = CallContext.withBudget(budgetOf(params), CancellationToken.NEVER, WAITS);
                    CompletableFuture<JsonElement> call =
                            api.dispatch(toolName, args, context).toCompletableFuture();
                    MethodSpec<?, ?> spec = api.spec(toolName);
                    if (call.isDone()) {
                        sendToolOutcome(ex, id, spec, call);
                    } else {
                        // Suspended (an awaited action): this worker and its POST slot go back now, the
                        // wait holds a wait permit, and the answer is written when the call completes.
                        // The exchange stays open until then; sendJson closes it.
                        call.whenComplete((r, t) -> {
                            try {
                                sendToolOutcome(ex, id, spec, call);
                            } catch (IOException gone) {
                                ex.close();
                            } catch (Throwable bad) {
                                // The stage would swallow this and leave the exchange open until the
                                // caller's own timeout; answer as the synchronous path below does.
                                try {
                                    sendJson(ex, 200, jsonRpcError(id, -32_603, "internal: " + bad));
                                } catch (Throwable unusable) {
                                    ex.close();
                                }
                            }
                        });
                    }
                }
                default -> sendJson(ex, 200, jsonRpcError(id, -32_601, "method not found: " + method));
            }
        } catch (Throwable t) {
            sendJson(ex, 200, jsonRpcError(id, -32_603, "internal: " + t.getMessage()));
        } finally {
            postSlots.release();
        }
    }

    /**
     * {@code GET /mcp} (Accept: text/event-stream) — the spec's server→client SSE stream. Keeps the exchange open and
     * parks the worker thread for the life of the connection, writing one {@code data:} frame (a JSON-RPC
     * {@code notifications/message}) per driver event, plus a heartbeat comment every 15 s so a silently-dropped peer
     * is detected.
     */
    private void handleSse(HttpExchange ex) throws IOException {
        if (!streamSlots.tryAcquire()) {
            send(
                    ex,
                    503,
                    "server busy: " + McpLimits.MCP_MAX_EVENT_STREAMS + " event streams already open; close one first");
            return;
        }
        try {
            streamUntilClosed(ex);
        } finally {
            streamSlots.release();
        }
    }

    private void streamUntilClosed(HttpExchange ex) throws IOException {
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
            try {
                ex.getResponseBody().close();
            } catch (IOException ignored) {
            }
            ex.close();
        }
    }

    /**
     * Fan one driver event out to every open SSE stream as a {@code notifications/message}. EVERY event pushes by
     * default; the only filter is the per-type opt-out {@link BotConfig#mutedEvents} (mc.bot.setting{mutedEvents}). We
     * intentionally do NOT gate on the client's {@code logging/setLevel} minimum — driver events are domain signals the
     * Agent asked for, and a client that defaults its filter to {@code warning} would otherwise silently drop every
     * info/notice event. The frame still carries a severity {@code level} for display/ordering. Runs on the host's
     * event-dispatch thread.
     */
    private void onEvent(EventContracts.Event e) {
        if (sse.isEmpty()) {
            return;
        }
        // No mutedEvents check here: the host applies the per-type opt-out before it
        // calls any listener, so every transport gets the same policy for free.
        String frame = "data: " + EventNotifications.frame(e) + "\n\n";
        for (SseSubscriber sub : sse) {
            sub.offer(frame); // never blocks: see SseSubscriber.offer
        }
    }

    /**
     * One open SSE connection: its output stream + a latch the parked handler thread waits on. Writes are synchronized
     * and fail-closed.
     *
     * <p>Package-private rather than private so {@code SseBackpressureTest} can drive one against a
     * deliberately-stalled stream; there is no other way to prove the event dispatcher stops blocking without wedging a
     * real TCP receive window.
     */
    static final class SseSubscriber {
        /** Frames buffered for this one client. Bounded on purpose — see {@link #offer}. */
        static final int OUTBOX_CAP = 256;

        private final OutputStream os;
        final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(OUTBOX_CAP);
        volatile boolean alive = true;
        final CountDownLatch done = new CountDownLatch(1);

        SseSubscriber(OutputStream os) {
            this.os = os;
        }

        /**
         * Hand a frame to THIS subscriber's writer without touching the socket.
         *
         * <p>{@link #onEvent} runs on the host's single event-dispatch thread, shared by every listener — the WebSocket
         * transport included. Writing the socket there (which is what this class used to do) meant one SSE client whose
         * TCP receive window had filled blocked that thread inside {@code os.write}, and with it every other SSE
         * subscriber, the WebSocket push channel, and the dispatch queue, which is unbounded and would grow for as long
         * as the stall lasted. Netty's async {@code writeAndFlush} keeps the WebSocket side from blocking, but async is
         * not bounded: that side closes a subscriber whose channel goes unwritable, which is the same policy as the
         * outbox cap here.
         *
         * <p>Overflow closes the stream rather than dropping frames. A consumer that silently misses events is the
         * worse failure — it cannot tell that it did. Closing is loud and recoverable: the client sees EOF, reconnects,
         * and replays from its cursor with {@code mc.observe.eventsSince}, which is what the event ring buffer is for.
         */
        void offer(String s) {
            if (!alive) {
                return;
            }
            if (!outbox.offer(s)) {
                WorldDriverCommon.LOG.warn(
                        "[mcp] SSE consumer fell more than {} frames behind — closing its stream; "
                                + "reconnect and replay with mc.observe.eventsSince{cursor}",
                        OUTBOX_CAP);
                die();
            }
        }

        /**
         * Drain and write until the stream dies. Runs on the parked HTTP worker thread that is already dedicated to
         * this connection, so the blocking writes cost nothing extra: no new thread, and the keepalive it used to send
         * is now just what happens when the outbox is idle.
         */
        void pumpUntilClosed() throws InterruptedException {
            while (alive) {
                String frame = outbox.poll(15, TimeUnit.SECONDS);
                if (!alive) {
                    break;
                }
                writeBlocking(frame == null ? ": ping\n\n" : frame);
            }
        }

        /**
         * Only ever called from this subscriber's own writer thread (or from handleSse before the pump starts), never
         * from the event dispatcher.
         */
        synchronized void writeBlocking(String s) {
            if (!alive || s.isEmpty()) {
                return;
            }
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
            outbox.offer(""); // best-effort nudge so an idle pump notices at once
            // (a full outbox means the pump is stuck in write, not poll)
        }
    }

    /** The caller's remaining budget from {@code params._meta.budgetMs}, else the server's cap. */
    private static long budgetOf(JsonObject params) {
        if (params.get("_meta") instanceof JsonObject meta
                && meta.get("budgetMs") instanceof JsonPrimitive n
                && n.isNumber()
                && n.getAsNumber().longValue() > 0) {
            return Math.min(n.getAsNumber().longValue(), CallContext.SERVER_CAP_MS);
        }
        return CallContext.SERVER_CAP_MS;
    }

    private static String string(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /**
     * Write a finished call. A result is a tool result with {@code structuredContent}; a call that was withdrawn, may
     * still apply, or never got capacity is a JSON-RPC error, because only its code tells "safe to retry" from "may
     * still apply"; every other API error is a tool error whose text is the {@code ApiError} JSON, so a model reads the
     * issue paths it needs to fix the call.
     */
    private static void sendToolOutcome(
            HttpExchange ex, JsonElement id, MethodSpec<?, ?> spec, CompletableFuture<JsonElement> call)
            throws IOException {
        JsonElement result;
        try {
            result = call.join();
        } catch (Throwable t) {
            ApiError e = ApiRegistry.asApi(t).error();
            switch (e.code()) {
                case SERVER_BUSY, NOT_EXECUTED, OUTCOME_UNKNOWN ->
                    sendJson(
                            ex,
                            200,
                            JsonRpcFrames.error(id, e.code().rpcCode, e.message(), ApiErrors.toTree(e))
                                    .toString());
                default ->
                    sendJson(
                            ex,
                            200,
                            jsonRpcResult(id, toolError(ApiErrors.toTree(e).toString())));
            }
            return;
        }
        sendJson(ex, 200, jsonRpcResult(id, toolResultContent(spec, result)));
    }

    /**
     * Wraps an encoded result into MCP tool result content: {@code structuredContent} plus the same JSON as text. For a
     * method whose spec names an image field, the bytes become a native {@code image} block and both the text and
     * {@code structuredContent} hold the metadata view, so a multimodal model receives the picture as vision input
     * rather than a base64 string. The companion {@code McpBridge} merges the bytes back so in-JVM callers see one
     * shape.
     */
    private static JsonObject toolResultContent(MethodSpec<?, ?> spec, JsonElement result) {
        MethodSpec.ImageHint image = spec == null ? null : spec.image();
        String b64 = image != null && result instanceof JsonObject raw ? string(raw.get(image.dataField())) : null;
        String fmt = b64 != null ? string(result.getAsJsonObject().get(image.formatField())) : null;
        if (b64 != null && !b64.isEmpty() && fmt != null && isImageMimeFormat(fmt)) {
            JsonObject meta = result.getAsJsonObject().deepCopy();
            meta.remove(image.dataField());
            JsonArray content = new JsonArray();
            content.add(block("text", "text", meta.toString()));
            JsonObject imageBlock = block("image", "data", b64);
            imageBlock.addProperty("mimeType", "image/" + fmt);
            content.add(imageBlock);
            JsonObject m = new JsonObject();
            m.add("content", content);
            m.add("structuredContent", meta);
            m.addProperty("isError", false);
            return m;
        }
        JsonObject m = toolText(result);
        if (result instanceof JsonObject) {
            m.add("structuredContent", result);
        }
        return m;
    }

    /** A content block {@code {type, <field>: value}}. */
    private static JsonObject block(String type, String field, String value) {
        JsonObject b = new JsonObject();
        b.addProperty("type", type);
        b.addProperty(field, value);
        return b;
    }

    private static boolean isImageMimeFormat(String fmt) {
        return "png".equals(fmt) || "jpeg".equals(fmt) || "jpg".equals(fmt) || "webp".equals(fmt) || "gif".equals(fmt);
    }

    private static JsonObject toolText(JsonElement result) {
        JsonArray content = new JsonArray();
        content.add(block("text", "text", result.toString()));
        JsonObject m = new JsonObject();
        m.add("content", content);
        m.addProperty("isError", false);
        return m;
    }

    /**
     * DNS-rebinding defense (spec: 2025-06-18 §Transports, Security Warning — servers MUST validate the Origin header).
     * The policy is {@link OriginPolicy}, shared with the WebSocket handshake.
     *
     * <p>Returns true when the request should be processed, false when a 403 has already been written and the caller
     * should bail.
     */
    private static boolean checkOrigin(HttpExchange ex) throws IOException {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (OriginPolicy.isAllowed(origin)) {
            return true;
        }
        send(ex, 403, "forbidden origin: " + origin);
        return false;
    }

    /** {@code application/json}, with or without parameters such as {@code charset}. */
    static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semi = contentType.indexOf(';');
        String type = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
        return "application/json".equalsIgnoreCase(type);
    }

    private static JsonObject toolError(String message) {
        JsonArray content = new JsonArray();
        content.add(block("text", "text", message == null ? "error" : message));
        JsonObject m = new JsonObject();
        m.add("content", content);
        m.addProperty("isError", true);
        return m;
    }

    private static String jsonRpcResult(JsonElement id, JsonElement result) {
        return JsonRpcFrames.result(id, result).toString();
    }

    private static String jsonRpcError(JsonElement id, int code, String message) {
        return JsonRpcFrames.error(id, code, message, null).toString();
    }

    private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendNoBody(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
        ex.getResponseBody().close();
    }

    @Override
    public void close() {
        for (SseSubscriber sub : sse) {
            sub.die();
        }
        sse.clear();
        http.stop(0);
        executor.shutdown();
    }
}
