package net.magicterra.worlddriver.rpc;


import net.magicterra.worlddriver.protocol.RequestLimits;
import net.magicterra.worlddriver.protocol.ServerBusyException;
import net.magicterra.worlddriver.protocol.JsonCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
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
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.ApiErrors;
import net.magicterra.worlddriver.api.ApiRegistry;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.CancellationToken;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.WaitPermits;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.DecodeContext;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.EventStreamContracts;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.protocol.JsonRpcFrames;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket RPC server (Netty). Endpoint: ws://host:port/rpc
 * Request frame:  {"jsonrpc":"2.0", "id": 1, "method": "mc.observe.player", "params": {...},
 *                  "_meta": {"budgetMs": N}}   (_meta optional: the caller's remaining budget)
 * Response frame: {"jsonrpc":"2.0", "id": 1, "result": ...}
 *              or {"jsonrpc":"2.0", "id": 1|null, "error": {"code": int, "message": "...", "data": ApiError}}
 * Standard JSON-RPC 2.0: the {@code id} key is present on EVERY response, null only when the
 * request did not carry a usable one; {@code error.code} is the JSON-RPC code, and {@code data}
 * carries the API's {@code {code, message, issues, execution}} whenever the API produced the error.
 *
 * Same {@code DriverApi.dispatch(method, params, context)} is invoked here AND from in-JVM Rhino
 * calls, guaranteeing structural parity between paths. A call that suspends (an awaited action)
 * hands its worker back; its answer is written when its stage completes.
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
 * which would deadlock the IO thread if we ran it inline. We hop to a bounded worker
 * pool before calling route(); past the per-connection or pool cap in
 * {@link TransportLimits} a request is answered with a busy error instead.
 *
 * Build note: MC ships only netty-codec/transport; netty-codec-http is added via
 * the {@code forgeRuntimeLibrary} configuration (NeoForge) / {@code implementation}
 * (Fabric) plus shadow-bundled into the prod jar. See module build.gradle files.
 */
public final class RpcServer implements Closeable {
    private final EventLoopGroup boss = new NioEventLoopGroup(1, daemonFactory("agent-rpc-boss"));
    private final EventLoopGroup worker = new NioEventLoopGroup(0, daemonFactory("agent-rpc-io"));
    /** No queue: a request either gets a thread now or is refused with a busy error, which
     *  a client can act on; a queued one would just time out later. */
    private final ExecutorService routeExec = new ThreadPoolExecutor(0, TransportLimits.RPC_MAX_WORKERS,
            60L, TimeUnit.SECONDS, new SynchronousQueue<>(), daemonFactory("agent-rpc-handler"));
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
        this(api, bindHost, requestedPort, TransportLimits.WS_PING_INTERVAL_MS, TransportLimits.WS_IDLE_CLOSE_MS);
    }

    /** Liveness timings as parameters so a test can run them in seconds. */
    RpcServer(DriverApi api, String bindHost, int requestedPort, long pingIntervalMs, long idleCloseMs) {
        ServerBootstrap b = new ServerBootstrap();
        final ChannelGroup subs = this.subscribers;
        b.group(boss, worker)
         .channel(NioServerSocketChannel.class)
         .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                 TransportLimits.WS_WRITE_BUFFER_LOW_BYTES, TransportLimits.WS_WRITE_BUFFER_HIGH_BYTES))
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 ch.pipeline()
                   // First, so any inbound byte counts, a pong included: the protocol
                   // handler consumes pongs before any later handler could see them.
                   .addLast(new IdleStateHandler(pingIntervalMs, 0, 0, TimeUnit.MILLISECONDS))
                   .addLast(new HttpServerCodec())
                   .addLast(new HttpObjectAggregator(1 << 20))
                   .addLast(new OriginGate())
                   .addLast(new WebSocketServerProtocolHandler("/rpc", null, true,
                           RequestLimits.MAX_REQUEST_BYTES))
                   // A fragmented message reaches FrameHandler as one frame; without this
                   // the first fragment was parsed alone and the continuations dropped.
                   .addLast(new WebSocketFrameAggregator(RequestLimits.MAX_REQUEST_BYTES))
                   .addLast(new PeerLiveness(pingIntervalMs, idleCloseMs))
                   .addLast(new FrameHandler(api, routeExec, subs));
             }
         });
        try {
            this.serverChannel = b.bind(bindHost, requestedPort).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new RuntimeException("interrupted while binding RPC server", e);
        } catch (Throwable e) {
            // Throwable, not RuntimeException: sync() rethrows the checked BindException
            // undeclared, and missing it here leaked both event loops on every taken port.
            shutdown();
            if (e instanceof RuntimeException || e instanceof Error) throw e;
            throw new RuntimeException("could not bind RPC server to " + bindHost + ":" + requestedPort
                    + ": " + e.getMessage(), e);
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
            // Async is not bounded: a peer that stops reading would keep every frame in
            // the outbound buffer. Close instead of dropping, as the SSE side does, so the
            // client can tell it missed events and replay them from its cursor.
            if (!ch.isWritable()) {
                WorldDriverCommon.LOG.warn("[rpc] event subscriber {} is not reading (over {} bytes queued) — "
                        + "closing it; reconnect and replay with mc.observe.eventsSince{cursor}",
                        ch.remoteAddress(), TransportLimits.WS_WRITE_BUFFER_HIGH_BYTES);
                ch.close();
                continue;
            }
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
        return Thread.ofPlatform().name(prefix + "-", 1).daemon().factory();
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

    /** Pings a quiet peer and closes one that has sent nothing, pong included, for the
     *  whole window. A half-open peer never fails a write the kernel can still buffer, so
     *  nothing else would ever reclaim its connection or its event subscription. */
    private static final class PeerLiveness extends ChannelInboundHandlerAdapter {
        private final long pingIntervalMs;
        private final long idleCloseMs;
        private boolean upgraded;
        private long quietMs;

        PeerLiveness(long pingIntervalMs, long idleCloseMs) {
            this.pingIntervalMs = pingIntervalMs;
            this.idleCloseMs = idleCloseMs;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                upgraded = true;
            } else if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                // isFirst marks the first idle event since the last read, which resets the count.
                quietMs = idle.isFirst() ? pingIntervalMs : quietMs + pingIntervalMs;
                if (quietMs >= idleCloseMs) {
                    ctx.close();
                } else if (upgraded) {
                    ctx.channel().writeAndFlush(new PingWebSocketFrame());
                }
                return;
            }
            super.userEventTriggered(ctx, evt);
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

        /**
         * Requests of THIS connection that hold a worker; one handler per channel. A request that
         * suspends (an awaited action, a wait) gives its worker back and holds a wait permit instead,
         * so a connection with its waits outstanding can still observe and cancel.
         */
        private final AtomicInteger inFlight = new AtomicInteger();
        /** Suspended waits across every connection of this JVM's RPC servers. */
        private static final WaitPermits GLOBAL_WAITS = WaitPermits.bounded(TransportLimits.RPC_MAX_WAITS);
        /** Cancelled when the socket closes: every wait it left suspended ends then, nothing it started does. */
        private final CancellationToken.Source gone = new CancellationToken.Source();
        private final WaitPermits waits = WaitPermits.both(GLOBAL_WAITS,
                WaitPermits.bounded(TransportLimits.RPC_MAX_WAITS_PER_CONNECTION));

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            gone.cancel();
            super.channelInactive(ctx);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
            String text = msg.text();
            Channel ch = ctx.channel();
            if (inFlight.incrementAndGet() > TransportLimits.RPC_MAX_IN_FLIGHT_PER_CONNECTION) {
                inFlight.decrementAndGet();
                ch.writeAndFlush(new TextWebSocketFrame(busyFrame(text, "this connection already has "
                        + TransportLimits.RPC_MAX_IN_FLIGHT_PER_CONNECTION + " requests running")));
                return;
            }
            try {
                routeExec.execute(() -> {
                    try {
                        handleRequest(text, ch);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException full) {
                inFlight.decrementAndGet();
                ch.writeAndFlush(new TextWebSocketFrame(busyFrame(text, "all "
                        + TransportLimits.RPC_MAX_WORKERS + " RPC workers are busy")));
            }
        }

        /** A refusal that still carries the request's id, so the caller can correlate it. */
        private static String busyFrame(String line, String why) {
            Object id = null;
            try {
                if (JsonCodec.decode(line) instanceof Map<?, ?> req && JsonRpcFrames.validId(req.get("id"))) id = req.get("id");
            } catch (Throwable ignored) { /* unreadable: answer with a null id */ }
            ApiError busy = ApiError.of(ApiError.Code.SERVER_BUSY, "server busy: " + why + "; retry later");
            return encode(JsonRpcFrames.error(id, busy.code().rpcCode, busy.message(), ApiErrors.toTree(busy)));
        }

        /**
         * Answer one frame. The answer is written when the call's stage completes, which for an
         * awaited action is after this worker has returned; the stage arrives already encoded, off any
         * game thread (see {@code ApiRegistry.dispatch}).
         */
        private void handleRequest(String line, Channel ch) {
            Object decoded;
            try {
                decoded = JsonCodec.decode(line);
            } catch (Throwable err) {
                write(ch, JsonRpcFrames.error(null, JsonRpcFrames.PARSE_ERROR, "parse: " + err.getMessage(), null));
                return;
            }
            if (!(decoded instanceof Map<?, ?> req)) {
                write(ch, JsonRpcFrames.error(null, JsonRpcFrames.INVALID_REQUEST, "request must be a JSON object", null));
                return;
            }
            Object rawId = req.get("id");
            Object id = JsonRpcFrames.validId(rawId) ? rawId : null;
            if (!JsonRpcFrames.validId(rawId)) {
                write(ch, JsonRpcFrames.error(null, JsonRpcFrames.INVALID_REQUEST,
                        "invalid request: id must be a string, an integer or null", null));
                return;
            }
            if (!(req.get("method") instanceof String method)) {
                write(ch, JsonRpcFrames.error(id, JsonRpcFrames.INVALID_REQUEST,
                        "invalid request: 'method' must be a string, got " + DecodeContext.describe(req.get("method")), null));
                return;
            }
            Object params = req.get("params");
            // Event-stream subscription is per-connection state, so it's handled at the transport
            // layer: it controls which frames THIS socket receives, not any game behavior.
            if ("mc.events.subscribe".equals(method) || "mc.events.unsubscribe".equals(method)) {
                write(ch, subscriptionControl(method, params, ch, id));
                return;
            }
            CallContext context = CallContext.withBudget(budgetOf(req), gone, waits);
            api.dispatch(method, params, context).whenComplete((result, failure) -> {
                if (failure == null) {
                    write(ch, JsonRpcFrames.result(id, result));
                } else {
                    ApiError e = ApiRegistry.asApi(failure).error();
                    write(ch, JsonRpcFrames.error(id, e.code().rpcCode, e.message(), ApiErrors.toTree(e)));
                }
            });
        }

        /**
         * The caller's remaining budget in ms, from {@code _meta.budgetMs} beside the params (never
         * inside them), else the server's own cap. A remote client's local timeout is not something
         * this server can know unless it is sent.
         */
        private static long budgetOf(Map<?, ?> req) {
            if (req.get("_meta") instanceof Map<?, ?> meta && meta.get("budgetMs") instanceof Number n) {
                long ms = n.longValue();
                if (ms > 0) return Math.min(ms, CallContext.SERVER_CAP_MS);
            }
            return CallContext.SERVER_CAP_MS;
        }

        private static void write(Channel ch, Map<String, Object> frame) {
            if (!ch.isActive()) return;
            ch.writeAndFlush(new TextWebSocketFrame(encode(frame)));
        }

        private static String encode(Map<String, Object> frame) { return JsonCodec.encode(frame); }

        /**
         * The two connection-control calls, decoded by their own contract: a malformed filter is an
         * invalid_params error, never a silent "every type" (it used to fail open that way).
         */
        private Map<String, Object> subscriptionControl(String method, Object params, Channel ch, Object id) {
            try {
                Object tree = params == null ? Map.of() : params;
                if (EventStreamContracts.SUBSCRIBE.equals(method)) {
                    EventStreamContracts.SubscribeParams p = EventStreamContracts.SUBSCRIBE_PARAMS.decode(tree);
                    Set<String> types = new LinkedHashSet<>(p.types().orElse(List.of()));
                    // Event type names are open-world (scripts mint their own), so an unknown name
                    // cannot be rejected; the ack echoes what was installed, and allTypes says
                    // outright that an empty filter means every type.
                    ch.attr(FILTER).set(types.isEmpty() ? null : types);
                    subscribers.add(ch);
                    return JsonRpcFrames.result(id, EventStreamContracts.SUBSCRIPTION.encode(
                            new EventStreamContracts.Subscription(true, List.copyOf(types), types.isEmpty())));
                }
                CommonTypes.NO_PARAMS.decode(tree);
                subscribers.remove(ch);
                ch.attr(FILTER).set(null);
                return JsonRpcFrames.result(id, EventStreamContracts.UNSUBSCRIBED.encode(new EventStreamContracts.Unsubscribed(false)));
            } catch (RuntimeException bad) {
                ApiError e = ApiRegistry.asApi(bad).error();
                return JsonRpcFrames.error(id, e.code().rpcCode, e.message(), ApiErrors.toTree(e));
            }
        }
    }
}
