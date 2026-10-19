package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import com.google.gson.JsonObject;
import net.magicterra.worlddriver.TestJson;
import static net.magicterra.worlddriver.application.TestTransports.rpc;

import net.magicterra.worlddriver.application.DriverApplication;



import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    /** The JSON-RPC {@code error.code} of a response frame, as text; "null" when it has none. */
    private static String code(Map<?, ?> frame) {
        return frame.get("error") instanceof Map<?, ?> e ? String.valueOf(e.get("code")) : "null";
    }

    // ------------------------------------------------------------------ client

    @Test
    void pushedEventDoesNotBecomeTheResponse() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.script = List.of(NOTIFICATION, "{\"id\":{ID},\"result\":{\"hp\":20}}");
            try (RpcClient c = new RpcClient("127.0.0.1", server.port)) {
                Object r = TestJson.plain(c.call("mc.observe.player", new JsonObject()));
                Map<?, ?> m = assertInstanceOf(Map.class, r, "an event was returned as the response");
                assertEquals("20", String.valueOf(m.get("hp")));

                // The frame that matters: the NEXT call must still get its own answer.
                server.script = List.of("{\"id\":{ID},\"result\":\"second\"}");
                assertEquals("second", TestJson.plain(c.call("mc.x", new JsonObject())),
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
                assertEquals("after3", TestJson.plain(c.call("mc.x", new JsonObject())));
            }
        }
    }

    @Test
    void idLessErrorFrameFailsTheCallInsteadOfTimingOut() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.script = List.of("{\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse: boom\"}}");
            try (RpcClient c = new RpcClient("127.0.0.1", server.port)) {
                RpcCallException e = assertThrows(RpcCallException.class, () -> TestJson.plain(c.call("mc.x", new JsonObject())));
                assertTrue(String.valueOf(e.getMessage()).contains("boom"), e.getMessage());
                assertEquals(-32700, e.rpcCode());
            }
        }
    }

    @Test
    void staleResponseFromAnEarlierCallIsDropped() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            server.script = List.of("{\"id\":999999,\"result\":\"stale\"}",
                    "{\"id\":{ID},\"result\":\"fresh\"}");
            try (RpcClient c = new RpcClient("127.0.0.1", server.port)) {
                assertEquals("fresh", TestJson.plain(c.call("mc.x", new JsonObject())));
            }
        }
    }

    // ------------------------------------------------------------------ server

    @Test
    void everyErrorResponseCarriesAnIdKeyAndACode() throws Exception {
        // DriverApplication's constructor only fills a route map with lambdas, so it needs no
        // running game as long as no route actually executes.
        try (RpcServer server = rpc(new DriverApplication());
             WsTestClient raw = new WsTestClient(server.port())) {

            Map<?, ?> nonObject = raw.roundTrip("\"just a string\"");
            assertTrue(nonObject.containsKey("id"), "id key missing: " + nonObject);
            assertNull(nonObject.get("id"), "id must be an explicit null, not absent");
            assertEquals("-32600", code(nonObject));

            Map<?, ?> unparseable = raw.roundTrip("{not json");
            assertTrue(unparseable.containsKey("id"), "id key missing: " + unparseable);
            assertNull(unparseable.get("id"));
            assertEquals("-32700", code(unparseable));

            Map<?, ?> unknown = raw.roundTrip("{\"id\":7,\"method\":\"mc.nope\",\"params\":{}}");
            assertEquals("7", String.valueOf(unknown.get("id")));
            assertEquals("-32601", code(unknown));

            // A frame whose id WAS readable must keep it even though the rest was bad.
            Map<?, ?> badMethod = raw.roundTrip("{\"id\":8,\"method\":42,\"params\":{}}");
            assertEquals("8", String.valueOf(badMethod.get("id")));
            assertEquals("-32600", code(badMethod));
        }
    }

    @Test
    void aFragmentedMessageIsReassembledBeforeParsing() throws Exception {
        // java.net.http.WebSocket.sendText(part, false) and browsers under load both
        // fragment. Parsing the first fragment alone answered -32700 for a valid request.
        try (RpcServer server = rpc(new DriverApplication());
             WsTestClient raw = new WsTestClient(server.port())) {
            Map<?, ?> r = raw.roundTrip(
                    new TextWebSocketFrame(false, 0, "{\"id\":21,\"method\":\"mc.no"),
                    new ContinuationWebSocketFrame(false, 0, "pe\",\"params\""),
                    new ContinuationWebSocketFrame(true, 0, ":{}}"));
            assertEquals("21", String.valueOf(r.get("id")), r.toString());
            assertEquals("-32601", code(r), r.toString());
        }
    }

    @Test
    void aMissingMethodIsAnInvalidRequestNotAnInternalError() throws Exception {
        try (RpcServer server = rpc(new DriverApplication());
             WsTestClient raw = new WsTestClient(server.port())) {
            Map<?, ?> r = raw.roundTrip("{\"id\":5,\"params\":{}}");
            assertEquals("5", String.valueOf(r.get("id")));
            assertEquals("-32600", code(r), r.toString());
            assertTrue(String.valueOf(r.get("error")).contains("method"), r.toString());
        }
    }

    @Test
    void aRequestWithoutAnIdIsAnsweredWithANullIdNotZero() throws Exception {
        // 0 is a legal client id; answering an id-less request with it misroutes the
        // reply to whichever call really used 0.
        try (RpcServer server = rpc(new DriverApplication());
             WsTestClient raw = new WsTestClient(server.port())) {
            Map<?, ?> err = raw.roundTrip("{\"method\":\"mc.nope\",\"params\":{}}");
            assertTrue(err.containsKey("id"), "id key missing: " + err);
            assertNull(err.get("id"), err.toString());
            assertEquals("-32601", code(err));

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
    void aServerThreadTimeoutSaysWhetherTheTaskCanStillApply() throws Exception {
        DriverApplication api = new DriverApplication();
        ProbeMethods.failing(api.registry(),"probe.notExecuted", () -> new ServerThreadHop.NotExecutedException("withdrawn"));
        // Handlers may wrap what the hop threw; the classification must survive that.
        ProbeMethods.failing(api.registry(),"probe.outcomeUnknown",
                () -> new RuntimeException(new ServerThreadHop.OutcomeUnknownException("still running")));
        try (RpcServer server = rpc(api);
             WsTestClient raw = new WsTestClient(server.port())) {
            Map<?, ?> ne = raw.roundTrip("{\"id\":21,\"method\":\"probe.notExecuted\",\"params\":{}}");
            assertEquals("-32001", code(ne), "reply: " + ne);
            Map<?, ?> ou = raw.roundTrip("{\"id\":22,\"method\":\"probe.outcomeUnknown\",\"params\":{}}");
            assertEquals("-32002", code(ou), "reply: " + ou);
        }
    }

    @Test
    void aRouteRefusedAtACapIsBusyNotAnInternalFault() throws Exception {
        // The background-wait cap refuses inside a route, past the transport's own cap check;
        // it must reach the client with the same busy code the transport cap answers with.
        DriverApplication api = new DriverApplication();
        ProbeMethods.failing(api.registry(),"probe.busy", () -> ApiException.of(ApiError.Code.SERVER_BUSY, "busy: all background waits are running"));
        try (RpcServer server = rpc(api);
             WsTestClient raw = new WsTestClient(server.port())) {
            Map<?, ?> r = raw.roundTrip("{\"id\":23,\"method\":\"probe.busy\",\"params\":{}}");
            assertEquals(String.valueOf(ApiError.Code.SERVER_BUSY.rpcCode), code(r), "reply: " + r);
        }
    }

    @Test
    void aLargeRequestIsAcceptedLikeTheMcpTransportAcceptsIt() throws Exception {
        // McpServer caps a POST body at agent.mcp.maxBodyBytes (8 MiB default). The
        // WebSocket side inherited Netty's 64 KiB default frame size, so the same
        // driver call succeeded on one transport and killed the connection on the
        // other — with no JSON error, because a frame that never assembles cannot
        // carry one. 100 KiB is comfortably over the old limit and far under the new.
        String pad = "x".repeat(100 * 1024);
        try (RpcServer server = rpc(new DriverApplication());
             WsTestClient raw = new WsTestClient(server.port())) {
            Map<?, ?> r = raw.roundTrip(
                    "{\"id\":11,\"method\":\"mc.nope\",\"params\":{\"pad\":\"" + pad + "\"}}");
            assertEquals("11", String.valueOf(r.get("id")),
                    "a large frame must still get a response");
            assertEquals("-32601", code(r));
        }
    }

    // ------------------------------------------------------- events.subscribe

    @Test
    void aMalformedTypeFilterIsRefusedRatherThanIgnored() throws Exception {
        // The trap: `types` was read with `instanceof List`, so a bare string simply
        // did not match, the set stayed empty, empty meant "no filter", and the caller
        // was subscribed to EVERY event while its ack said types:[] — which reads like
        // the opposite. Failing open on a filter is the worst direction to fail.
        try (RpcServer server = rpc(new DriverApplication());
             WsTestClient raw = new WsTestClient(server.port())) {

            Map<?, ?> str = raw.roundTrip("{\"id\":1,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":\"chat.message\"}}");
            assertEquals("-32602", code(str),
                    "a string where an array belongs must be refused: " + str);

            Map<?, ?> num = raw.roundTrip("{\"id\":2,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":[\"chat.message\",42]}}");
            assertEquals("-32602", code(num),
                    "a non-string entry must be refused, not silently dropped: " + num);

            Map<?, ?> blank = raw.roundTrip("{\"id\":3,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":[\"\"]}}");
            assertEquals("-32602", code(blank), blank.toString());
        }
    }

    @Test
    void subscribeAckSaysWhetherTheFilterIsUnrestricted() throws Exception {
        try (RpcServer server = rpc(new DriverApplication());
             WsTestClient raw = new WsTestClient(server.port())) {

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

    /**
     * The push is the shared event value inside {@code notifications/message}: seq a string, the filter
     * applied, nothing after unsubscribe. The two control calls are connection state, not methods.
     */
    @Test
    void aSubscribedConnectionIsPushedTheEventValueItAskedFor() throws Exception {
        DriverApplication api = new DriverApplication();
        try (RpcServer server = rpc(api);
             WsTestClient raw = new WsTestClient(server.port())) {
            assertTrue(!api.registry().methods().contains("mc.events.subscribe")
                            && !api.registry().methods().contains("mc.events.unsubscribe"),
                    "subscription is the transport's, not a registered method");
            result(raw.roundTrip("{\"id\":8,\"method\":\"mc.events.subscribe\",\"params\":{\"types\":[\"probe.kept\"]}}"));
            api.emitExternal("probe.dropped", null, JsonValue.of(TestJson.json("no")));
            api.emitExternal("probe.kept", new net.minecraft.core.BlockPos(1, 2, 3), JsonValue.of(TestJson.json(Map.of("n", 4))));
            Map<?, ?> frame = WsTestClient.decode(raw.poll(5_000));
            assertEquals("notifications/message", frame.get("method"));
            assertTrue(!frame.containsKey("id"), "a push carries no id: " + frame);
            Map<?, ?> event = (Map<?, ?>) ((Map<?, ?>) frame.get("params")).get("data");
            assertEquals("probe.kept", event.get("type"), "the filter must drop other types: " + event);
            assertEquals("2", event.get("seq"), "the second event of a fresh application, as a string");
            assertEquals(Map.of("x", 1L, "y", 2L, "z", 3L), event.get("pos"));
            assertEquals(Map.of("n", 4L), event.get("data"));

            Map<?, ?> stray = raw.roundTrip("{\"id\":9,\"method\":\"mc.events.unsubscribe\",\"params\":{\"types\":[]}}");
            assertEquals("-32602", code(stray), "unsubscribe takes no params: " + stray);
            result(raw.roundTrip("{\"id\":10,\"method\":\"mc.events.unsubscribe\"}"));
            api.emitExternal("probe.kept", null, JsonValue.of(TestJson.json("late")));
            assertNull(raw.poll(500), "nothing is pushed after unsubscribe");
        }
    }

    @Test
    void anApiErrorTravelsAsTheStandardErrorObjectWithItsDataAttached() throws Exception {
        // JSON-RPC 2.0 error shape: {code, message, data}; data is the ApiError a caller
        // branches on, so a client never has to parse the message text.
        try (RpcServer server = rpc(new DriverApplication());
             WsTestClient raw = new WsTestClient(server.port())) {
            Map<?, ?> r = raw.roundTrip("{\"id\":1,\"method\":\"mc.nope\",\"params\":{}}");
            Map<?, ?> err = assertInstanceOf(Map.class, r.get("error"));
            assertInstanceOf(String.class, err.get("message"));
            Map<?, ?> data = assertInstanceOf(Map.class, err.get("data"));
            assertEquals("method_not_found", data.get("code"));
            assertEquals("not_started", data.get("execution"));
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
                              Object decoded = TestJson.plain(f.text());
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
}
