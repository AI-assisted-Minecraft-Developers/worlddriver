package net.magicterra.worlddriver.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Minimal WS client that hands back frames VERBATIM — the point of the server tests is to
 * inspect the bytes, which RpcClient (correctly) filters. It never answers a ping unless
 * told to, so it can stand in for a half-open peer.
 */
final class WsTestClient implements AutoCloseable {
    private final NioEventLoopGroup group = new NioEventLoopGroup(1);
    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    final AtomicInteger pingsSeen = new AtomicInteger();
    volatile boolean answerPings;
    final Channel channel;

    WsTestClient(int port) throws Exception {
        WebSocketClientHandshaker hs = WebSocketClientHandshakerFactory.newHandshaker(
                URI.create("ws://127.0.0.1:" + port + "/rpc"), WebSocketVersion.V13,
                null, false, new DefaultHttpHeaders(), TransportLimits.MAX_REQUEST_BYTES);
        Handler handler = new Handler(hs);
        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel c) {
                 c.pipeline().addLast(new HttpClientCodec())
                  .addLast(new HttpObjectAggregator(1 << 20))
                  .addLast(handler);
             }
         });
        this.channel = b.connect("127.0.0.1", port).sync().channel();
        if (!handler.ready.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("ws handshake timeout");
        }
    }

    private final class Handler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker hs;
        private ChannelPromise ready;

        Handler(WebSocketClientHandshaker hs) { this.hs = hs; }

        @Override public void handlerAdded(ChannelHandlerContext c) { ready = c.newPromise(); }
        @Override public void channelActive(ChannelHandlerContext c) { hs.handshake(c.channel()); }

        @Override protected void channelRead0(ChannelHandlerContext c, Object msg) {
            if (!hs.isHandshakeComplete()) {
                hs.finishHandshake(c.channel(), (FullHttpResponse) msg);
                ready.setSuccess();
                return;
            }
            if (msg instanceof TextWebSocketFrame t) inbox.offer(t.text());
            else if (msg instanceof PingWebSocketFrame p) {
                pingsSeen.incrementAndGet();
                if (answerPings) c.writeAndFlush(new PongWebSocketFrame(p.content().retain()));
            }
        }
    }

    void send(String raw) {
        channel.writeAndFlush(new TextWebSocketFrame(raw));
    }

    /** Next text frame, or null after {@code ms}. */
    String poll(long ms) throws InterruptedException {
        return inbox.poll(ms, TimeUnit.MILLISECONDS);
    }

    Map<?, ?> roundTrip(String raw) throws Exception {
        return roundTrip(new TextWebSocketFrame(raw));
    }

    /** Sends every frame, then waits for one response; used for fragmented messages. */
    Map<?, ?> roundTrip(WebSocketFrame... frames) throws Exception {
        for (WebSocketFrame f : frames) channel.write(f);
        channel.flush();
        String s = inbox.poll(10, TimeUnit.SECONDS);
        if (s == null) throw new IllegalStateException("no response to " + frames.length + " frame(s)");
        return decode(s);
    }

    static Map<?, ?> decode(String s) {
        return assertInstanceOf(Map.class, JsonCodec.decode(s), "non-object response: " + s);
    }

    @Override public void close() {
        channel.close();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
}
