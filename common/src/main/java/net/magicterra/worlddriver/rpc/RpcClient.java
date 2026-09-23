package net.magicterra.worlddriver.rpc;

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
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import net.magicterra.worlddriver.api.ServerThreadGuard;

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
 * matching the surface that {@link net.magicterra.worlddriver.script.RpcBridge} expects.
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
        ServerThreadGuard.refuseBlocking("an RPC round-trip");
        URI uri = URI.create("ws://" + host + ":" + port + "/rpc");
        // The inbound limit matters at least as much here as on the server: responses
        // are the big direction (mc.client.screenshot returns base64 image bytes), and
        // Netty's 64 KiB default would drop such a frame with no error — the call would
        // simply time out. Same ceiling as every other transport.
        WebSocketClientHandshaker hs = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders(),
                TransportLimits.MAX_REQUEST_BYTES);
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
        return parseEnvelope(send(req, id));
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
        Object result = parseEnvelope(send(sb.toString(), id));
        return JsonCodec.encode(result);
    }

    /**
     * Write one request and return ITS response frame.
     *
     * <p>Every frame the socket receives lands in one queue, including the
     * unsolicited {@code notifications/message} events a connection gets after
     * {@code mc.events.subscribe}. Taking "the next line" as the answer therefore
     * only works while nobody subscribes: one pushed event would be returned as
     * the call's response — {@code result} absent, so the call quietly yields null
     * — and would leave the real response in the queue, shifting every later call
     * by one frame for the life of the connection.
     *
     * <p>So demultiplex the way the server's own class doc specifies: a frame with
     * a {@code method} is a notification, a frame with an {@code id} is a response.
     * Calls are serialized by {@code synchronized}, so at most one is outstanding
     * and an id-less error frame (the server could not parse the request well
     * enough to echo an id) can only belong to it.
     */
    private Map<?, ?> send(String frameText, long id) throws IOException {
        ServerThreadGuard.refuseBlocking("an RPC round-trip");
        if (!channel.isActive()) throw new EOFException("ws channel closed");
        channel.writeAndFlush(new TextWebSocketFrame(frameText));
        long deadlineNs = System.nanoTime() + CALL_TIMEOUT_MS * 1_000_000L;
        while (true) {
            long remainMs = (deadlineNs - System.nanoTime()) / 1_000_000L;
            String line;
            try {
                line = remainMs <= 0 ? null : handler.inbox.poll(remainMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted awaiting ws response", e);
            }
            if (line == null) throw new IOException("ws response timeout after " + CALL_TIMEOUT_MS + "ms");
            Object decoded = JsonCodec.decode(line);
            if (!(decoded instanceof Map<?, ?> resp)) throw new IOException("malformed response: " + line);
            if (resp.containsKey("method")) continue;          // server→client notification
            Object rid = resp.get("id");
            if (rid == null) return resp;                      // id-less server error: ours by elimination
            if (rid instanceof Number n && n.longValue() == id) return resp;
            // A different id can only be a response to an earlier call that already
            // timed out. Dropping it is the recovery: keeping it would hand the wrong
            // payload to this caller and leave the queue permanently one frame behind.
        }
    }

    private Object parseEnvelope(Map<?, ?> resp) throws IOException {
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
            } else if (msg instanceof PingWebSocketFrame ping) {
                // The server closes a peer that stays silent past its liveness window, and
                // one call may block for longer than that.
                ch.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            } else if (msg instanceof WebSocketFrame) {
                // Ignore Pong/Binary for this minimal client.
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
