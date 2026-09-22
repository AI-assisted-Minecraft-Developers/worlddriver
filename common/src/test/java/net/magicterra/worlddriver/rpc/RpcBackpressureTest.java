package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.DriverApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An event subscriber that stops reading is disconnected rather than buffered without bound.
 *
 * <p>Netty's {@code writeAndFlush} is asynchronous, which keeps the dispatch thread from
 * blocking but not the heap from growing: every frame a stalled peer does not take sits in
 * the channel's outbound buffer for the life of the connection.
 */
@Timeout(120)
class RpcBackpressureTest {

    @Test
    void aSubscriberThatStopsReadingIsDisconnected() throws Exception {
        DriverApi api = new DriverApi();
        try (RpcServer server = new RpcServer(api, 0);
             WsTestClient client = new WsTestClient(server.port())) {
            client.roundTrip("{\"id\":1,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":[\"test.flood\"]}}");
            // Listeners run in registration order on one dispatch thread, so once this sees
            // the sentinel the server has been offered every flood frame before it.
            CountDownLatch dispatched = new CountDownLatch(1);
            api.addEventListener(e -> { if ("test.done".equals(e.type)) dispatched.countDown(); });

            client.channel.config().setAutoRead(false);
            String payload = "x".repeat(16 * 1024);
            // Far past the high water mark plus whatever the loopback socket buffers absorb.
            for (int i = 0; i < 4000; i++) api.emitExternal("test.flood", null, payload);
            api.emitExternal("test.done", null, "");
            assertTrue(dispatched.await(60, TimeUnit.SECONDS), "dispatch never finished");

            client.channel.config().setAutoRead(true);
            assertTrue(client.channel.closeFuture().await(30, TimeUnit.SECONDS),
                    "the server kept a connection whose reader had stalled");
        }
    }

    @Test
    void aSubscriberThatKeepsReadingStaysConnected() throws Exception {
        // Guards against "fixed the growth by closing everyone".
        DriverApi api = new DriverApi();
        try (RpcServer server = new RpcServer(api, 0);
             WsTestClient client = new WsTestClient(server.port())) {
            client.roundTrip("{\"id\":1,\"method\":\"mc.events.subscribe\","
                    + "\"params\":{\"types\":[\"test.flood\"]}}");
            String payload = "x".repeat(1024);
            for (int i = 0; i < 200; i++) api.emitExternal("test.flood", null, payload);
            for (int i = 0; i < 200; i++) {
                assertTrue(client.poll(10_000) != null, "event " + i + " never arrived");
            }
            assertTrue(client.channel.isActive());
        }
    }
}
