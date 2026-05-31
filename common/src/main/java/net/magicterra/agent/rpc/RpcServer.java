package net.magicterra.agent.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import net.magicterra.agent.api.AgentApi;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket RPC server (Netty). Endpoint: ws://host:port/rpc
 * Request frame:  {"id": int, "method": "mc.observe.player", "params": {...}}
 * Response frame: {"id": int, "result": ...} or {"id": int, "error": "..."}
 *
 * Same {@code AgentApi.route(method, params)} is invoked here AND from in-JVM Rhino
 * calls, guaranteeing structural parity between paths.
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

    public RpcServer(AgentApi api, int requestedPort) {
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 ch.pipeline()
                   .addLast(new HttpServerCodec())
                   .addLast(new HttpObjectAggregator(1 << 20))
                   .addLast(new WebSocketServerProtocolHandler("/rpc", null, true))
                   .addLast(new FrameHandler(api, routeExec));
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
    }

    public int port() { return port; }

    @Override public void close() {
        try { serverChannel.close().sync(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        shutdown();
    }

    private void shutdown() {
        boss.shutdownGracefully();
        worker.shutdownGracefully();
        routeExec.shutdown();
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix + "-" + Thread.currentThread().threadId());
            t.setDaemon(true);
            return t;
        };
    }

    private static final class FrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        private final AgentApi api;
        private final ExecutorService routeExec;

        FrameHandler(AgentApi api, ExecutorService routeExec) {
            this.api = api;
            this.routeExec = routeExec;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
            String text = msg.text();
            Channel ch = ctx.channel();
            routeExec.submit(() -> {
                String response = handleRequest(text);
                ch.writeAndFlush(new TextWebSocketFrame(response));
            });
        }

        @SuppressWarnings("unchecked")
        private String handleRequest(String line) {
            try {
                Object decoded = JsonCodec.decode(line);
                if (!(decoded instanceof Map<?, ?> req)) {
                    return JsonCodec.encode(Map.of("error", "request must be JSON object"));
                }
                Object id = req.get("id");
                String method = (String) req.get("method");
                Map<String, Object> params = (Map<String, Object>) req.get("params");
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
    }
}
