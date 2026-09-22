package net.magicterra.worlddriver.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import net.magicterra.worlddriver.api.DriverApi;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WebSocket transport's wire contract, exercised over a real socket.
 *
 * <p>Both halves of this contract were broken and no gate could see it, because
 * nothing ever ran the transport outside a full dogfood server:
 *
 * <ul>
 *   <li>{@link RpcServer} omitted the {@code id} key entirely on its two
 *       malformed-input paths, contradicting the demultiplexing rule its own class
 *       doc states ("notifications have a method and no id; responses have an id")
 *       and leaving an id-correlating client to wait out its timeout.</li>
 *   <li>{@link RpcClient} took whatever frame arrived next as its response. Since
 *       pushed {@code notifications/message} events share that queue, one event
 *       during a call returned a frame with no {@code result} — a silent null — and
 *       left the real response behind, shifting every later call by one frame for
 *       the life of the connection. The desync is asserted below because it is far
 *       more damaging than the null: it corrupts calls that look successful.</li>
 * </ul>
 */
class RpcFramingTest {

    private static final String NOTIFICATION =
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":"
            + "{\"level\":\"warning\",\"logger\":\"minecraft.events\","
            + "\"data\":{\"type\":\"player.hurt\"}}}";

    // ------------------------------------------------------------------ client

    @Test
    void pushedEventDoesNotBecomeTheResponse() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.script = List.of(NOTIFICATION, "{\"id\":{ID},\"result\":{\"hp\":20}}");
            try (RpcClient c = new RpcClient("127.0.0.1", server.port)) {
                Object r = c.call("mc.observe.player", Map.of());
                Map<?, ?> m = assertInstanceOf(Map.class, r, "an event was returned as the response");
                assertEquals("20", String.valueOf(m.get("hp")));

                // The frame that matters: the NEXT call must still get its own answer.
                server.script = List.of("{\"id\":{ID},\"result\":\"second\"}");
                assertEquals("second", c.call("mc.x", Map.of()),
                        "connection is one frame behind — every later call is corrupted");
            }
        }
    }

    @Test
    void severalPushedEventsAreSkipped() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.script = List.of(NOTIFICATION, NOTIFICATION, NOTIFICATION,
                    "{\"id\":{ID},\"result\":\"after3\"}");
            try (RpcClient c = new RpcClient("127.0.0.1", server.port)) {
                assertEquals("after3", c.call("mc.x", Map.of()));
            }
        }
    }

    @Test
    void idLessErrorFrameFailsTheCallInsteadOfTimingOut() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.script = List.of("{\"id\":null,\"error\":\"parse: boom\",\"code\":-32700}");
            try (RpcClient c = new RpcClient("127.0.0.1", server.port)) {
                Exception e = assertThrows(Exception.class, () -> c.call("mc.x", Map.of()));
                assertTrue(String.valueOf(e.getMessage()).contains("boom"), e.getMessage());
            }
        }
    }

    @Test
    void staleResponseFromAnEarlierCallIsDropped() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.script = List.of("{\"id\":999999,\"result\":\"stale\"}",
                    "{\"id\":{ID},\"result\":\"fresh\"}");
            try (RpcClient c = new RpcClient("127.0.0.1", server.port)) {
                assertEquals("fresh", c.call("mc.x", Map.of()));
            }
        }
    }

    // ------------------------------------------------------------------ server

    @Test
    void everyErrorResponseCarriesAnIdKeyAndACode() throws Exception {
        // DriverApi's constructor only fills a route map with lambdas, so it needs no
        // running game as long as no route actually executes.
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             RawClient raw = new RawClient(server.port())) {

            Map<?, ?> nonObject = raw.roundTrip("\"just a string\"");
            assertTrue(nonObject.containsKey("id"), "id key missing: " + nonObject);
            assertNull(nonObject.get("id"), "id must be an explicit null, not absent");
            assertEquals("-32600", String.valueOf(nonObject.get("code")));

            Map<?, ?> unparseable = raw.roundTrip("{not json");
            assertTrue(unparseable.containsKey("id"), "id key missing: " + unparseable);
            assertNull(unparseable.get("id"));
            assertEquals("-32700", String.valueOf(unparseable.get("code")));

            Map<?, ?> unknown = raw.roundTrip("{\"id\":7,\"method\":\"mc.nope\",\"params\":{}}");
            assertEquals("7", String.valueOf(unknown.get("id")));
            assertEquals("-32601", String.valueOf(unknown.get("code")));

            // A frame whose id WAS readable must keep it even though the rest was bad.
            Map<?, ?> badMethod = raw.roundTrip("{\"id\":8,\"method\":42,\"params\":{}}");
            assertEquals("8", String.valueOf(badMethod.get("id")));
            assertEquals("-32600", String.valueOf(badMethod.get("code")));
        }
    }

    @Test
    void aMissingMethodIsAnInvalidRequestNotAnInternalError() throws Exception {
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             RawClient raw = new RawClient(server.port())) {
            Map<?, ?> r = raw.roundTrip("{\"id\":5,\"params\":{}}");
            assertEquals("5", String.valueOf(r.get("id")));
            assertEquals("-32600", String.valueOf(r.get("code")), r.toString());
            assertTrue(String.valueOf(r.get("error")).contains("method"), r.toString());
        }
    }

    @Test
    void aRequestWithoutAnIdIsAnsweredWithANullIdNotZero() throws Exception {
        // 0 is a legal client id; answering an id-less request with it misroutes the
        // reply to whichever call really used 0.
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             RawClient raw = new RawClient(server.port())) {
            Map<?, ?> err = raw.roundTrip("{\"method\":\"mc.nope\",\"params\":{}}");
            assertTrue(err.containsKey("id"), "id key missing: " + err);
            assertNull(err.get("id"), err.toString());
            assertEquals("-32601", String.valueOf(err.get("code")));

            Map<?, ?> ok = raw.roundTrip("{\"method\":\"mc.events.unsubscribe\"}");
            assertTrue(ok.containsKey("id"), "id key missing: " + ok);
            assertNull(ok.get("id"), ok.toString());
            assertInstanceOf(Map.class, ok.get("result"));

            Map<?, ?> badFilter = raw.roundTrip(
                    "{\"method\":\"mc.events.subscribe\",\"params\":{\"types\":\"x\"}}");
            assertTrue(badFilter.containsKey("id"));
            assertNull(badFilter.get("id"), badFilter.toString());
        }
    }

    @Test
    void aLargeRequestIsAcceptedLikeTheMcpTransportAcceptsIt() throws Exception {
        // McpServer caps a POST body at agent.mcp.maxBodyBytes (8 MiB default). The
        // WebSocket side inherited Netty's 64 KiB default frame size, so the same
        // DriverApi call succeeded on one transport and killed the connection on the
        // other — with no JSON error, because a frame that never assembles cannot
        // carry one. 100 KiB is comfortably over the old limit and far under the new.
        String pad = "x".repeat(100 * 1024);
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             RawClient raw = new RawClient(server.port())) {
            Map<?, ?> r = raw.roundTrip(
                    "{\"id\":11,\"method\":\"mc.nope\",\"params\":{\"pad\":\"" + pad + "\"}}");
            assertEquals("11", String.valueOf(r.get("id")),
                    "a large frame must still get a response");
            assertEquals("-32601", String.valueOf(r.get("code")));
        }
    }

    // ------------------------------------------------------- events.subscribe

    @Test
    void aMalformedTypeFilterIsRefusedRatherThanIgnored() throws Exception {
        // The trap: `types` was read with `instanceof List`, so a bare string simply
        // did not match, the set stayed empty, empty meant "no filter", and the caller
        // was subscribed to EVERY event while its ack said types:[] — which reads like
        // the opposite. Failing open on a filter is the worst direction to fail.
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             RawClient raw = new RawClient(server.port())) {

            Map<?, ?> str = raw.roundTrip("{\"id\":1,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":\"chat.message\"}}");
            assertEquals("-32602", String.valueOf(str.get("code")),
                    "a string where an array belongs must be refused: " + str);

            Map<?, ?> num = raw.roundTrip("{\"id\":2,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":[\"chat.message\",42]}}");
            assertEquals("-32602", String.valueOf(num.get("code")),
                    "a non-string entry must be refused, not silently dropped: " + num);

            Map<?, ?> blank = raw.roundTrip("{\"id\":3,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":[\"\"]}}");
            assertEquals("-32602", String.valueOf(blank.get("code")), blank.toString());
        }
    }

    @Test
    void subscribeAckSaysWhetherTheFilterIsUnrestricted() throws Exception {
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             RawClient raw = new RawClient(server.port())) {

            Map<?, ?> filtered = result(raw.roundTrip("{\"id\":4,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":[\"chat.message\"]}}"));
            assertEquals(List.of("chat.message"), filtered.get("types"));
            assertEquals(Boolean.FALSE, filtered.get("allTypes"));

            // Omitted and explicitly-empty both mean EVERY type. types:[] alone cannot
            // say that — it reads like "none" — so the ack states it outright.
            Map<?, ?> omitted = result(raw.roundTrip(
                    "{\"id\":5,\"method\":\"mc.events.subscribe\",\"params\":{}}"));
            assertEquals(Boolean.TRUE, omitted.get("allTypes"));

            Map<?, ?> empty = result(raw.roundTrip("{\"id\":6,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":[]}}"));
            assertEquals(Boolean.TRUE, empty.get("allTypes"));

            Map<?, ?> off = result(raw.roundTrip(
                    "{\"id\":7,\"method\":\"mc.events.unsubscribe\",\"params\":{}}"));
            assertEquals(Boolean.FALSE, off.get("subscribed"));
        }
    }

    private static Map<?, ?> result(Map<?, ?> frame) {
        return assertInstanceOf(Map.class, frame.get("result"), "not a result frame: " + frame);
    }

    @Test
    void errorPayloadStaysABareString() throws Exception {
        // Journeyman's driver.py and the worlddriver-rpc skill's rpc.py both read
        // `error` as a string. `code` was added alongside it precisely so neither
        // has to change; if this ever becomes an object, both break silently.
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             RawClient raw = new RawClient(server.port())) {
            Map<?, ?> r = raw.roundTrip("{\"id\":1,\"method\":\"mc.nope\",\"params\":{}}");
            assertInstanceOf(String.class, r.get("error"));
        }
    }

    @Test
    void wellFormedCallStillRoundTrips() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.script = List.of("{\"id\":{ID},\"result\":{\"ok\":true}}");
            try (RpcClient c = new RpcClient("127.0.0.1", server.port)) {
                assertDoesNotThrow(() -> c.call("mc.system.version", Map.of()));
            }
        }
    }

    // ------------------------------------------------------------------ harness

    /** A WS server that answers each request with a scripted frame sequence;
     *  {@code {ID}} is replaced by the request's id. */
    private static final class ScriptedServer implements AutoCloseable {
        private final NioEventLoopGroup boss = new NioEventLoopGroup(1);
        private final NioEventLoopGroup work = new NioEventLoopGroup(1);
        private final Channel channel;
        final int port;
        volatile List<String> script = List.of();

        ScriptedServer() throws Exception {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, work).channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override protected void initChannel(SocketChannel c) {
                     c.pipeline().addLast(new HttpServerCodec())
                      .addLast(new HttpObjectAggregator(1 << 20))
                      .addLast(new WebSocketServerProtocolHandler("/rpc", null, true))
                      .addLast(new SimpleChannelInboundHandler<TextWebSocketFrame>() {
                          @Override protected void channelRead0(ChannelHandlerContext x,
                                                                TextWebSocketFrame f) {
                              Object decoded = JsonCodec.decode(f.text());
                              Object id = decoded instanceof Map<?, ?> m ? m.get("id") : 0;
                              for (String line : script) {
                                  x.channel().writeAndFlush(new TextWebSocketFrame(
                                          line.replace("{ID}", String.valueOf(id))));
                              }
                          }
                      });
                 }
             });
            this.channel = b.bind("127.0.0.1", 0).sync().channel();
            this.port = ((InetSocketAddress) channel.localAddress()).getPort();
        }

        @Override public void close() {
            channel.close();
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            work.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /** Minimal WS client that hands back frames VERBATIM — the point of the server
     *  tests is to inspect the bytes, which RpcClient (correctly) filters. */
    private static final class RawClient implements AutoCloseable {
        private final NioEventLoopGroup group = new NioEventLoopGroup(1);
        private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private final Channel channel;

        RawClient(int port) throws Exception {
            WebSocketClientHandshaker hs = WebSocketClientHandshakerFactory.newHandshaker(
                    URI.create("ws://127.0.0.1:" + port + "/rpc"), WebSocketVersion.V13,
                    null, false, new DefaultHttpHeaders());
            Handler handler = new Handler(hs, inbox);
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

        private static final class Handler extends SimpleChannelInboundHandler<Object> {
            private final WebSocketClientHandshaker hs;
            private final BlockingQueue<String> inbox;
            private ChannelPromise ready;

            Handler(WebSocketClientHandshaker hs, BlockingQueue<String> inbox) {
                this.hs = hs;
                this.inbox = inbox;
            }

            @Override public void handlerAdded(ChannelHandlerContext c) { ready = c.newPromise(); }
            @Override public void channelActive(ChannelHandlerContext c) { hs.handshake(c.channel()); }

            @Override protected void channelRead0(ChannelHandlerContext c, Object msg) {
                if (!hs.isHandshakeComplete()) {
                    hs.finishHandshake(c.channel(), (FullHttpResponse) msg);
                    ready.setSuccess();
                    return;
                }
                if (msg instanceof TextWebSocketFrame t) inbox.offer(t.text());
            }
        }

        Map<?, ?> roundTrip(String raw) throws Exception {
            channel.writeAndFlush(new TextWebSocketFrame(raw));
            String s = inbox.poll(10, TimeUnit.SECONDS);
            if (s == null) throw new IllegalStateException("no response to: " + raw);
            Object decoded = JsonCodec.decode(s);
            return assertInstanceOf(Map.class, decoded, "non-object response: " + s);
        }

        @Override public void close() {
            channel.close();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}
