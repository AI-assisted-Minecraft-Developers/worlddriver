package net.magicterra.agent.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.model.AgentEvent;

import java.io.Closeable;
import java.net.InetSocketAddress;
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
 * Response frame: {"id": int, "result": ...} or {"id": int, "error": "..."}
 *
 * Same {@code AgentApi.route(method, params)} is invoked here AND from in-JVM Rhino
 * calls, guaranteeing structural parity between paths.
 *
 * <h2>Event push channel (driver→agent)</h2>
 * This WebSocket is JSON-RPC over a custom transport (the spec permits custom
 * transports that preserve the JSON-RPC message format), so the driver→agent event
 * push rides it as standard server→client <b>{@code notifications/message}</b>
 * (the MCP logging notification) — the same shape an MCP-aware agent loop already
 * knows how to consume. A connection opts in with a control frame
 * {@code {"id":N,"method":"mc.events.subscribe","params":{"types":[...]}}} (omit
 * {@code types} for all); the server then pushes, unsolicited, one notification per
 * event it emits — threats, damage, death, chat, command results, custom conditions:
 * {@code {"jsonrpc":"2.0","method":"notifications/message","params":{"level","logger":"minecraft.events","data":{seq,timestamp,type,pos,data}}}}.
 * These carry no {@code id}; a client demultiplexes JSON-RPC notifications (have
 * {@code method}, no {@code id}) from responses ({@code result}/{@code error} with
 * {@code id}). {@code mc.events.unsubscribe} stops the stream. Connections that never
 * subscribe (every existing client, incl. the parity harness which opens one socket
 * per call) receive nothing extra — zero regression.
 *
 * Threading: incoming frames arrive on Netty IO threads. {@code route()} internally
 * marshals work to the server tick via {@code server.execute() + future.get(30s)},
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
    private static final AttributeKey<Set<String>> FILTER = AttributeKey.valueOf("agent.eventFilter");

    public RpcServer(AgentApi api, int requestedPort) {
        ServerBootstrap b = new ServerBootstrap();
        final ChannelGroup subs = this.subscribers;
        b.group(boss, worker)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 ch.pipeline()
                   .addLast(new HttpServerCodec())
                   .addLast(new HttpObjectAggregator(1 << 20))
                   .addLast(new WebSocketServerProtocolHandler("/rpc", null, true))
                   .addLast(new FrameHandler(api, routeExec, subs));
             }
         });
        try {
            this.serverChannel = b.bind(requestedPort).sync().channel();
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
     *  its type. Runs on AgentApi's single-thread event-dispatch executor; Netty's
     *  {@code writeAndFlush} is itself thread-safe and async, so this never blocks
     *  the game thread that produced the event. */
    private void onEvent(AgentEvent e) {
        if (subscribers.isEmpty()) return;
        if (BotConfig.mutedEvents.contains(e.type)) return; // unified per-type opt-out (same as the MCP channel)
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
    private static String eventFrame(AgentEvent e) {
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

    private static final class FrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        private final AgentApi api;
        private final ExecutorService routeExec;
        private final ChannelGroup subscribers;

        FrameHandler(AgentApi api, ExecutorService routeExec, ChannelGroup subscribers) {
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
            try {
                Object decoded = JsonCodec.decode(line);
                if (!(decoded instanceof Map<?, ?> req)) {
                    return JsonCodec.encode(Map.of("error", "request must be JSON object"));
                }
                Object id = req.get("id");
                String method = (String) req.get("method");
                Map<String, Object> params = (Map<String, Object>) req.get("params");
                // Event-stream subscription is per-connection state, so it's handled
                // at the transport layer (not an AgentApi route): it controls which
                // frames THIS socket receives, not any game behavior.
                if ("mc.events.subscribe".equals(method) || "mc.events.unsubscribe".equals(method)) {
                    return subscriptionControl(method, params, ch, id);
                }
                try {
                    Object result = api.route(method, params);
                    return JsonCodec.encode(Map.of("id", id == null ? 0 : id, "result", result));
                } catch (Throwable ex) {
                    return JsonCodec.encode(Map.of(
                        "id", id == null ? 0 : id,
                        "error", String.valueOf(ex.getMessage())
                    ));
                }
            } catch (Throwable parseErr) {
                return JsonCodec.encode(Map.of("error", "parse: " + parseErr.getMessage()));
            }
        }

        private String subscriptionControl(String method, Map<String, Object> params, Channel ch, Object id) {
            Map<String, Object> result = new LinkedHashMap<>();
            if ("mc.events.subscribe".equals(method)) {
                Set<String> types = new LinkedHashSet<>();
                if (params != null && params.get("types") instanceof List<?> l) {
                    for (Object o : l) if (o instanceof String s) types.add(s);
                }
                ch.attr(FILTER).set(types.isEmpty() ? null : types);
                subscribers.add(ch);
                result.put("ok", true);
                result.put("subscribed", true);
                result.put("types", List.copyOf(types));
            } else {
                subscribers.remove(ch);
                ch.attr(FILTER).set(null);
                result.put("ok", true);
                result.put("subscribed", false);
            }
            return JsonCodec.encode(Map.of("id", id == null ? 0 : id, "result", result));
        }
    }
}
