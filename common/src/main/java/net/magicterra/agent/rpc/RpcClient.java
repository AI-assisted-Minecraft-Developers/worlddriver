package net.magicterra.agent.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-shot WebSocket RPC client (Netty). One TCP connection + WS handshake per
 * instance; callers use try-with-resources just like the prior NDJSON client.
 *
 * Reads are serialized through a blocking queue so {@code call()} stays synchronous,
 * matching the surface that {@link net.magicterra.agent.script.RpcBridge} expects.
 */
public final class RpcClient implements Closeable {
    private static final long CALL_TIMEOUT_MS = 30_000L;

    private final EventLoopGroup group = new NioEventLoopGroup(1, r -> {
        Thread t = new Thread(r, "agent-rpc-client-io");
        t.setDaemon(true);
        return t;
    });
    private final Channel channel;
    private final ClientHandler handler;
    private final AtomicLong nextId = new AtomicLong(1);

    public RpcClient(String host, int port) throws IOException {
        URI uri = URI.create("ws://" + host + ":" + port + "/rpc");
        WebSocketClientHandshaker hs = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders());
        this.handler = new ClientHandler(hs);

        Bootstrap b = new Bootstrap();
        b.group(group)
         .channel(NioSocketChannel.class)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 ch.pipeline()
                   .addLast(new HttpClientCodec())
                   .addLast(new HttpObjectAggregator(1 << 20))
                   .addLast(handler);
             }
         });
        try {
            this.channel = b.connect(host, port).sync().channel();
            if (!handler.handshakeFuture().await(10, TimeUnit.SECONDS)) {
                throw new IOException("ws handshake timeout");
            }
            if (!handler.handshakeFuture().isSuccess()) {
                throw new IOException("ws handshake failed", handler.handshakeFuture().cause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new IOException("interrupted during ws connect", e);
        } catch (Throwable e) {
            shutdown();
            if (e instanceof IOException io) throw io;
            throw new IOException("ws connect failed", e);
        }
    }

    public synchronized Object call(String method, Map<String, Object> params) throws IOException {
        long id = nextId.getAndIncrement();
        String req = JsonCodec.encode(Map.of(
                "id", id,
                "method", method,
                "params", params == null ? Map.of() : params
        ));
        return parseEnvelope(send(req));
    }

    /** Returns the JSON-encoded {@code result} field as a String. */
    public synchronized String callJson(String method, String paramsJson) throws IOException {
        long id = nextId.getAndIncrement();
        StringBuilder sb = new StringBuilder(128 + (paramsJson == null ? 2 : paramsJson.length()));
        sb.append("{\"id\":").append(id).append(",\"method\":");
        sb.append(JsonCodec.encode(method));
        sb.append(",\"params\":");
        sb.append(paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
        sb.append('}');
        Object result = parseEnvelope(send(sb.toString()));
        return JsonCodec.encode(result);
    }

    private String send(String frameText) throws IOException {
        if (!channel.isActive()) throw new EOFException("ws channel closed");
        channel.writeAndFlush(new TextWebSocketFrame(frameText));
        try {
            String line = handler.inbox.poll(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (line == null) throw new IOException("ws response timeout after " + CALL_TIMEOUT_MS + "ms");
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted awaiting ws response", e);
        }
    }

    private Object parseEnvelope(String line) throws IOException {
        Object decoded = JsonCodec.decode(line);
        if (!(decoded instanceof Map<?, ?> resp)) throw new IOException("malformed response: " + line);
        if (resp.containsKey("error")) throw new IOException("rpc error: " + resp.get("error"));
        return resp.get("result");
    }

    @Override public void close() {
        if (channel.isActive()) {
            channel.writeAndFlush(new CloseWebSocketFrame());
            try { channel.closeFuture().await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        shutdown();
    }

    private void shutdown() {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }

    private static final class ClientHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;
        private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private ChannelPromise handshakeFuture;

        ClientHandler(WebSocketClientHandshaker handshaker) {
            this.handshaker = handshaker;
        }

        ChannelPromise handshakeFuture() { return handshakeFuture; }

        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            this.handshakeFuture = ctx.newPromise();
        }

        @Override public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            Channel ch = ctx.channel();
            if (!handshaker.isHandshakeComplete()) {
                try {
                    handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                    handshakeFuture.setSuccess();
                } catch (Exception e) {
                    handshakeFuture.setFailure(e);
                }
                return;
            }
            if (msg instanceof TextWebSocketFrame text) {
                inbox.offer(text.text());
            } else if (msg instanceof CloseWebSocketFrame) {
                ch.close();
            } else if (msg instanceof WebSocketFrame) {
                // Ignore Ping/Pong/Binary for this minimal client.
            }
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (handshakeFuture != null && !handshakeFuture.isDone()) {
                handshakeFuture.setFailure(cause);
            }
            ctx.close();
        }
    }
}
