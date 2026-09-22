package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.DriverApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One connection cannot make the server start an unbounded number of threads.
 *
 * <p>Every frame was handed to a cached pool, so a loop pipelining long waits on one socket
 * got one OS thread per frame for up to two minutes each, until the JVM could not create
 * another and took the game down with it.
 */
@Timeout(60)
class RpcConcurrencyTest {

    /** A request that holds its worker for {@code ms} without needing a world. */
    private static String slowWait(int id, int ms) {
        return "{\"id\":" + id + ",\"method\":\"mc.wait.condition\",\"params\":{\"invoke\":\"mc.events\","
                + "\"params\":{\"op\":\"list\"},\"field\":\"nope\",\"timeoutMs\":" + ms + ",\"pollMs\":100}}";
    }

    @Test
    void requestsPastThePerConnectionCapAreRefusedWithABusyError() throws Exception {
        int cap = TransportLimits.RPC_MAX_IN_FLIGHT_PER_CONNECTION;
        int extra = 4;
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             WsTestClient client = new WsTestClient(server.port())) {
            for (int i = 1; i <= cap + extra; i++) client.send(slowWait(i, 4_000));

            // The refusals come back at once, long before any wait could finish.
            List<Map<?, ?>> early = new ArrayList<>();
            for (int i = 0; i < extra; i++) {
                String s = client.poll(2_000);
                if (s == null) break;
                early.add(WsTestClient.decode(s));
            }
            assertEquals(extra, early.size(), "busy refusals did not arrive promptly: " + early);
            for (Map<?, ?> r : early) {
                assertEquals(String.valueOf(TransportLimits.RPC_CODE_SERVER_BUSY), String.valueOf(r.get("code")), r.toString());
                long id = ((Number) r.get("id")).longValue();
                assertTrue(id > cap, "a request within the cap was refused: " + r);
            }

            // The admitted ones still complete normally.
            for (int i = 0; i < cap; i++) {
                String s = client.poll(15_000);
                assertTrue(s != null, "admitted request " + i + " never answered");
                assertInstanceOf(Map.class, WsTestClient.decode(s).get("result"), s);
            }
        }
    }

    @Test
    void theCapIsPerConnection() throws Exception {
        int cap = TransportLimits.RPC_MAX_IN_FLIGHT_PER_CONNECTION;
        try (RpcServer server = new RpcServer(new DriverApi(), 0);
             WsTestClient busy = new WsTestClient(server.port());
             WsTestClient other = new WsTestClient(server.port())) {
            for (int i = 1; i <= cap; i++) busy.send(slowWait(i, 3_000));
            Thread.sleep(300);
            Map<?, ?> r = other.roundTrip("{\"id\":1,\"method\":\"mc.events\",\"params\":{\"op\":\"list\"}}");
            assertInstanceOf(Map.class, r.get("result"), r.toString());
        }
    }
}
