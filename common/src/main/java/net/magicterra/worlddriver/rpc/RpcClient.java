package net.magicterra.worlddriver.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.thread.ServerThreadGuard;

/**
 * WebSocket RPC client (Netty). One TCP connection and handshake per instance.
 *
 * <p>Calls may overlap: each request has its own id and its own pending future, and the reader completes whichever one
 * a response names. No lock spans "send until answered", so a second thread's observe goes out while a first thread's
 * awaited action is still pending. Server-pushed notifications go to {@link #onNotification}, never to a caller.
 */
public final class RpcClient implements Closeable {
    /** A call's budget when the caller gives none; sent to the server as {@code _meta.budgetMs}. */
    public static final long DEFAULT_CALL_TIMEOUT_MS = 30_000L;

    private final EventLoopGroup group = new NioEventLoopGroup(
            1, Thread.ofPlatform().name("agent-rpc-client-io").daemon().factory());

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
                uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders(), RequestLimits.MAX_REQUEST_BYTES);
        this.handler = new ClientHandler(hs);

        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
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
                throw new IOException(
                        "ws handshake failed", handler.handshakeFuture().cause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new IOException("interrupted during ws connect", e);
        } catch (Throwable e) {
            shutdown();
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("ws connect failed", e);
        }
    }

    /** Receive every server-pushed notification frame ({@code method}, no {@code id}), on the IO thread. */
    public void onNotification(Consumer<JsonObject> listener) {
        handler.notifications = listener;
    }

    public JsonElement call(String method, JsonObject params) throws IOException {
        return call(method, params, DEFAULT_CALL_TIMEOUT_MS);
    }

    /**
     * One call, waiting at most {@code budgetMs}. The same budget travels to the server, which ends its own wait before
     * this one gives up.
     *
     * @throws RpcCallException for an error response, carrying the API's error when there is one
     */
    public JsonElement call(String method, JsonElement params, long budgetMs) throws IOException {
        JsonObject resp = await(send(method, params, budgetMs), budgetMs);
        return resultOf(resp);
    }

    /** Send one call and return the future of its response frame. */
    public CompletableFuture<JsonObject> callAsync(String method, JsonObject params, long budgetMs) {
        return send(method, params, budgetMs);
    }

    private CompletableFuture<JsonObject> send(String method, JsonElement params, long budgetMs) {
        long id = nextId.getAndIncrement();
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        req.addProperty("method", method);
        if (params != null) {
            req.add("params", params);
        }
        // The server sees how long this caller will wait and stops its own wait first.
        JsonObject meta = new JsonObject();
        meta.addProperty("budgetMs", Math.max(1, budgetMs - 250));
        req.add("_meta", meta);
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        handler.pending.put(id, f);
        if (!channel.isActive()) {
            handler.pending.remove(id);
            f.completeExceptionally(new EOFException("ws channel closed"));
            return f;
        }
        channel.writeAndFlush(new TextWebSocketFrame(req.toString())).addListener(w -> {
            if (!w.isSuccess()) {
                handler.pending.remove(id);
                f.completeExceptionally(new IOException("ws write failed", w.cause()));
            }
        });
        return f.whenComplete((r, t) -> handler.pending.remove(id));
    }

    private static JsonObject await(CompletableFuture<JsonObject> f, long budgetMs) throws IOException {
        ServerThreadGuard.refuseBlocking("an RPC round-trip");
        try {
            return f.get(Math.max(1, budgetMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw new IOException("ws response timeout after " + budgetMs + "ms; the call may still take effect");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted awaiting ws response", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw new IOException(c);
        }
    }

    /** The result, or the error as an {@link RpcCallException}: standard JSON-RPC {@code error:{code,message,data}}. */
    private static JsonElement resultOf(JsonObject resp) throws RpcCallException {
        JsonElement err = resp.get("error");
        if (err == null || err.isJsonNull()) {
            return resp.has("result") ? resp.get("result") : JsonNull.INSTANCE;
        }
        if (err instanceof JsonObject e) {
            int code = e.get("code") instanceof JsonPrimitive n && n.isNumber() ? n.getAsInt() : 0;
            String message =
                    e.get("message") instanceof JsonPrimitive s && s.isString() ? s.getAsString() : err.toString();
            throw new RpcCallException(code, message, ApiErrors.fromTree(e.get("data")));
        }
        throw new RpcCallException(0, "malformed error " + err, null);
    }

    @Override
    public void close() {
        if (channel.isActive()) {
            channel.writeAndFlush(new CloseWebSocketFrame());
            try {
                channel.closeFuture().await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        shutdown();
    }

    private void shutdown() {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }

    private static final class ClientHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;

        final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

        volatile Consumer<JsonObject> notifications;

        private ChannelPromise handshakeFuture;

        ClientHandler(WebSocketClientHandshaker handshaker) {
            this.handshaker = handshaker;
        }

        ChannelPromise handshakeFuture() {
            return handshakeFuture;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.handshakeFuture = ctx.newPromise();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            EOFException closed = new EOFException("ws channel closed");
            for (CompletableFuture<JsonObject> f : pending.values()) {
                f.completeExceptionally(closed);
            }
            pending.clear();
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
                route(text.text());
            } else if (msg instanceof CloseWebSocketFrame) {
                ch.close();
            } else if (msg instanceof PingWebSocketFrame ping) {
                // The server closes a peer that stays silent past its liveness window, and
                // one call may be pending for longer than that.
                ch.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            } else if (msg instanceof WebSocketFrame) {
                // Ignore Pong/Binary for this minimal client.
            }
        }

        /**
         * A frame with a {@code method} is a notification, one with an {@code id} answers that call. An error with
         * {@code id:null} (the server could not read the request's id) can only be attributed when exactly one call is
         * pending; otherwise it is dropped and the calls time out.
         */
        private void route(String line) {
            JsonElement decoded;
            try {
                decoded = JsonParser.parseString(line);
            } catch (RuntimeException bad) {
                return;
            }
            if (!(decoded instanceof JsonObject resp)) {
                return;
            }
            if (resp.has("method")) {
                Consumer<JsonObject> l = notifications;
                if (l != null) {
                    l.accept(resp);
                }
                return;
            }
            JsonElement rid = resp.get("id");
            CompletableFuture<JsonObject> f = null;
            if (rid instanceof JsonPrimitive n && n.isNumber()) {
                f = pending.remove(n.getAsLong());
            } else if ((rid == null || rid.isJsonNull()) && pending.size() == 1) {
                Long only = pending.keySet().iterator().next();
                f = pending.remove(only);
            }
            if (f != null) {
                f.complete(resp);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (handshakeFuture != null && !handshakeFuture.isDone()) {
                handshakeFuture.setFailure(cause);
            }
            ctx.close();
        }
    }
}
