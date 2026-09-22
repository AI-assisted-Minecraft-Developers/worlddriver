package net.magicterra.worlddriver.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.model.DriverEvent;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * WebSocket RPC server (Netty). Endpoint: ws://host:port/rpc
 * Request frame:  {"id": int, "method": "mc.observe.player", "params": {...}}
 * Response frame: {"id": int, "result": ...}
 *              or {"id": int|null, "error": "...", "code": int}
 * The {@code id} key is present on EVERY response, null only when the request did
 * not carry one (omitted, or too malformed to read); {@code code} is the JSON-RPC 2.0 reserved code, the
 * same value McpServer would report for the same failure.
 *
 * Same {@code DriverApi.route(method, params)} is invoked here AND from in-JVM Rhino
 * calls, guaranteeing structural parity between paths.
 *
 * The upgrade request is refused with 403 when its {@code Origin} fails
 * {@link OriginPolicy}, the same check the MCP transport applies to a POST.
 *
 * <h2>Event push channel (driver→agent)</h2>
 * This WebSocket is JSON-RPC over a custom transport (the spec permits custom
 * transports that preserve the JSON-RPC message format), so the driver→agent event
 * push rides it as standard server→client <b>{@code notifications/message}</b>
 * (the MCP logging notification) — the same shape an MCP-aware agent loop already
 * knows how to consume. A connection opts in with a control frame
 * {@code {"id":N,"method":"mc.events.subscribe","params":{"types":[...]}}} — omit
 * {@code types} (or pass an empty array) for every type; anything other than an
 * array of non-blank strings is rejected with {@code -32602} rather than quietly
 * behaving like "no filter". The ack carries {@code allTypes} so the unfiltered
 * case is not left to be inferred from an empty {@code types}. The server then
 * pushes, unsolicited, one notification per
 * event it emits — threats, damage, death, chat, command results, custom conditions:
 * {@code {"jsonrpc":"2.0","method":"notifications/message","params":{"level","logger":"minecraft.events","data":{seq,timestamp,type,pos,data}}}}.
 * These carry no {@code id}; a client demultiplexes JSON-RPC notifications (have
 * {@code method}, no {@code id}) from responses ({@code result}/{@code error} with
 * {@code id}). {@code mc.events.unsubscribe} stops the stream. Connections that never
 * subscribe (every existing client, incl. the parity harness which opens one socket
 * per call) receive nothing extra — zero regression.
 *
 * Threading: incoming frames arrive on Netty IO threads. {@code route()} internally
 * marshals work to the server tick via {@code server.execute()} plus a bounded
 * {@code future.get} — {@code DriverApi.SERVER_THREAD_TIMEOUT_MS}, 8s by default and
 * settable with {@code -Dworlddriver.serverThreadTimeoutMs=N}. This said 30s, which was
 * the budget before it was lowered; naming the constant instead of a number keeps the
 * two from drifting apart again.
 * which would deadlock the IO thread if we ran it inline. We hop to a cached worker
 * pool before calling route().
 *
 * Build note: MC ships only netty-codec/transport; netty-codec-http is added via
 * the {@code forgeRuntimeLibrary} configuration (NeoForge) / {@code implementation}
 * (Fabric) plus shadow-bundled into the prod jar. See module build.gradle files.
 */
public final class RpcServer implements Closeable {
    private final EventLoopGroup boss = new NioEventLoopGroup(1, daemonFactory("agent-rpc-boss"));
    private final EventLoopGroup worker = new NioEventLoopGroup(0, daemonFactory("agent-rpc-io"));
    private final ExecutorService routeExec = Executors.newCachedThreadPool(daemonFactory("agent-rpc-handler"));
    private final Channel serverChannel;
    private final int port;

    /** Channels that have opted into the event push stream. {@link DefaultChannelGroup}
     *  auto-removes a channel when it closes, so there's no inactive-cleanup to do. */
    private final ChannelGroup subscribers = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    /** Per-channel event-type filter; {@code null}/empty means "all types". Stored as a
     *  channel attribute so it's GC'd with the connection. */
    private static final AttributeKey<Set<String>> FILTER = AttributeKey.valueOf("worlddriver.eventFilter");

    public RpcServer(DriverApi api, int requestedPort) {
        this(api, "127.0.0.1", requestedPort);
    }

    public RpcServer(DriverApi api, String bindHost, int requestedPort) {
        ServerBootstrap b = new ServerBootstrap();
        final ChannelGroup subs = this.subscribers;
        b.group(boss, worker)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 ch.pipeline()
                   .addLast(new HttpServerCodec())
                   .addLast(new HttpObjectAggregator(1 << 20))
                   .addLast(new OriginGate())
                   .addLast(new WebSocketServerProtocolHandler("/rpc", null, true,
                           TransportLimits.MAX_REQUEST_BYTES))
                   // A fragmented message reaches FrameHandler as one frame; without this
                   // the first fragment was parsed alone and the continuations dropped.
                   .addLast(new WebSocketFrameAggregator(TransportLimits.MAX_REQUEST_BYTES))
                   .addLast(new FrameHandler(api, routeExec, subs));
             }
         });
        try {
            this.serverChannel = b.bind(bindHost, requestedPort).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new RuntimeException("interrupted while binding RPC server", e);
        } catch (RuntimeException e) {
            shutdown();
            throw e;
        }
        this.port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        api.addEventListener(this::onEvent);
    }

    public int port() { return port; }

    /** Fan one emitted event out to every subscribed channel whose filter accepts
     *  its type. Runs on DriverApi's single-thread event-dispatch executor; Netty's
     *  {@code writeAndFlush} is itself thread-safe and async, so this never blocks
     *  the game thread that produced the event. */
    private void onEvent(DriverEvent e) {
        if (subscribers.isEmpty()) return;
        // No mutedEvents check here: DriverApi applies the per-type opt-out before it
        // calls any listener, so every transport gets the same policy for free.
        String frame = eventFrame(e);
        for (Channel ch : subscribers) {
            if (!ch.isActive()) continue;
            Set<String> filter = ch.attr(FILTER).get();
            if (filter != null && !filter.isEmpty() && !filter.contains(e.type)) continue;
            ch.writeAndFlush(new TextWebSocketFrame(frame));
        }
    }

    /** Frame an event as the shared MCP {@code notifications/message} (identical on
     *  the WS and MCP-HTTP transports — see {@link EventNotifications}). */
    private static String eventFrame(DriverEvent e) {
        return EventNotifications.frame(e);
    }

    @Override public void close() {
        try { serverChannel.close().sync(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        shutdown();
    }

    private void shutdown() {
        boss.shutdownGracefully();
        worker.shutdownGracefully();
        routeExec.shutdown();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix + "-" + Thread.currentThread().threadId());
            t.setDaemon(true);
            return t;
        };
    }

    /** Refuses the upgrade request of a browser page from a foreign origin. WebSockets are
     *  outside CORS, so without this any page the user opens can drive the socket. */
    private static final class OriginGate extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest req) {
                String origin = req.headers().get(HttpHeaderNames.ORIGIN);
                if (!OriginPolicy.isAllowed(origin)) {
                    req.release();
                    FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                            HttpResponseStatus.FORBIDDEN,
                            Unpooled.copiedBuffer("forbidden origin: " + origin, StandardCharsets.UTF_8));
                    res.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
                    res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
                    res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                    ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    private static final class FrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        private final DriverApi api;
        private final ExecutorService routeExec;
        private final ChannelGroup subscribers;

        FrameHandler(DriverApi api, ExecutorService routeExec, ChannelGroup subscribers) {
            this.api = api;
            this.routeExec = routeExec;
            this.subscribers = subscribers;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
            String text = msg.text();
            Channel ch = ctx.channel();
            routeExec.submit(() -> {
                String response = handleRequest(text, ch);
                ch.writeAndFlush(new TextWebSocketFrame(response));
            });
        }

        @SuppressWarnings("unchecked")
        private String handleRequest(String line, Channel ch) {
            Object id = null;
            boolean parsed = false;
            try {
                Object decoded = JsonCodec.decode(line);
                parsed = true;
                if (!(decoded instanceof Map<?, ?> req)) {
                    return errorFrame(null, CODE_INVALID_REQUEST, "request must be JSON object");
                }
                id = req.get("id");
                if (!(req.get("method") instanceof String method)) {
                    return errorFrame(id, CODE_INVALID_REQUEST,
                            "invalid request: 'method' must be a string, got "
                            + JsonCodec.encode(req.get("method")));
                }
                Map<String, Object> params = (Map<String, Object>) req.get("params");
                // Event-stream subscription is per-connection state, so it's handled
                // at the transport layer (not an DriverApi route): it controls which
                // frames THIS socket receives, not any game behavior.
                if ("mc.events.subscribe".equals(method) || "mc.events.unsubscribe".equals(method)) {
                    return subscriptionControl(method, params, ch, id);
                }
                try {
                    Object result = api.route(method, params);
                    return resultFrame(id, result);
                } catch (Throwable ex) {
                    return errorFrame(id, codeFor(ex), String.valueOf(ex.getMessage()));
                }
            } catch (Throwable err) {
                // Decode succeeded but the frame was still unusable (e.g. "params" was
                // not an object) -> invalid request, not a parse error. The id may have
                // been read before the failure; emit it when we have it.
                return parsed
                        ? errorFrame(id, CODE_INVALID_REQUEST, "invalid request: " + err.getMessage())
                        : errorFrame(null, CODE_PARSE, "parse: " + err.getMessage());
            }
        }

        // JSON-RPC 2.0 §5.1 reserved codes, identical to the ones McpServer emits.
        private static final int CODE_PARSE = -32700;
        private static final int CODE_INVALID_REQUEST = -32600;
        private static final int CODE_METHOD_NOT_FOUND = -32601;
        private static final int CODE_INVALID_PARAMS = -32602;
        private static final int CODE_INTERNAL = -32603;

        /**
         * An error response. The {@code id} key is ALWAYS present — explicitly null
         * when the request was too malformed to carry one — because this transport's
         * own demultiplexing rule (see the class doc) is "notifications have a method
         * and no id; responses have an id". Two paths used to omit the key entirely,
         * so a client following that rule could not classify them at all, and a client
         * correlating by id would wait out its timeout instead of seeing the error.
         *
         * <p>{@code error} stays a bare string: Journeyman's {@code driver.py} and the
         * worlddriver-rpc skill's {@code rpc.py} both read it as one, and breaking two
         * working clients to reshape it into MCP's {@code {code, message}} object buys
         * nothing. {@code code} is added ALONGSIDE it, using the same JSON-RPC codes
         * {@code McpServer.jsonRpcError} already emits, so the two transports finally
         * agree on the classification even though their payload shapes differ.
         */
        private static String errorFrame(Object id, int code, String message) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);          // Map.of would reject the null
            m.put("error", message);
            m.put("code", code);
            return JsonCodec.encode(m);
        }

        /** A request that carried no id is answered with {@code id:null}, never a made-up
         *  number: 0 is a legal client id and would misroute the reply. */
        private static String resultFrame(Object id, Object result) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("result", result);
            return JsonCodec.encode(m);
        }

        /** Mirrors McpServer's classification: an unroutable method is -32601, a bad
         *  argument -32602, anything else the route threw -32603. DriverApi signals the
         *  first two with IllegalArgumentException, so the method-not-found case is
         *  told apart by the message DriverApi.route builds for it. */
        private static int codeFor(Throwable ex) {
            String msg = String.valueOf(ex.getMessage());
            if (ex instanceof IllegalArgumentException)
                return msg.startsWith("unknown method:") ? CODE_METHOD_NOT_FOUND : CODE_INVALID_PARAMS;
            return CODE_INTERNAL;
        }

        private String subscriptionControl(String method, Map<String, Object> params, Channel ch, Object id) {
            Map<String, Object> result = new LinkedHashMap<>();
            if ("mc.events.subscribe".equals(method)) {
                Object rawTypes = params == null ? null : params.get("types");
                Set<String> types = new LinkedHashSet<>();
                // A malformed filter used to fail OPEN. `instanceof List` simply did not
                // match for {"types":"chat.message"}, the set stayed empty, empty meant
                // "no filter", and the client was subscribed to EVERY event while its ack
                // said types:[] — which reads like "none". Same for a non-string entry,
                // which was dropped without a word. Both are now refused.
                if (rawTypes != null) {
                    if (!(rawTypes instanceof List<?> l)) {
                        return errorFrame(id, CODE_INVALID_PARAMS,
                                "mc.events.subscribe: 'types' must be an array of strings, got "
                                + rawTypes.getClass().getSimpleName()
                                + " — omit 'types' entirely to receive every type");
                    }
                    for (Object o : l) {
                        if (!(o instanceof String s) || s.isBlank()) {
                            return errorFrame(id, CODE_INVALID_PARAMS,
                                    "mc.events.subscribe: every entry of 'types' must be a "
                                    + "non-blank string, got " + JsonCodec.encode(o));
                        }
                        types.add(s);
                    }
                }
                // Event type names are open-world — scripts mint their own through
                // mc.events{op:'emit'} and watcher emitAs — so an unknown name cannot be
                // rejected. A typo therefore still yields silence; `types` is echoed back
                // verbatim so at least the ack shows what was actually installed.
                ch.attr(FILTER).set(types.isEmpty() ? null : types);
                subscribers.add(ch);
                result.put("ok", true);
                result.put("subscribed", true);
                result.put("types", List.copyOf(types));
                // Explicit, because types:[] alone is ambiguous: an omitted or empty
                // filter means EVERY type, and reading it as "none" is the natural
                // mistake — the one the silent-failure path above led straight into.
                result.put("allTypes", types.isEmpty());
            } else {
                subscribers.remove(ch);
                ch.attr(FILTER).set(null);
                result.put("ok", true);
                result.put("subscribed", false);
            }
            return resultFrame(id, result);
        }
    }
}
