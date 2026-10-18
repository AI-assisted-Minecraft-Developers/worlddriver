package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.application.DriverApplication;
import net.magicterra.worlddriver.application.TestTransports;

import net.magicterra.worlddriver.protocol.ServerBusyException;

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

    /** A request that holds its worker for {@code ms} without needing a world; a wait would give it back. */
    private static String slowWait(int id, int ms) {
        return "{\"id\":" + id + ",\"method\":\"probe.slow\",\"params\":{\"ms\":" + ms + "}}";
    }

    private static DriverApplication app() {
        return ProbeMethods.install(new DriverApplication());
    }

    @Test
    void requestsPastThePerConnectionCapAreRefusedWithABusyError() throws Exception {
        int cap = TransportLimits.RPC_MAX_IN_FLIGHT_PER_CONNECTION;
        int extra = 4;
        try (RpcServer server = TestTransports.rpc(app());
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
                assertEquals(String.valueOf(ServerBusyException.CODE), String.valueOf(((Map<?, ?>) r.get("error")).get("code")), r.toString());
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

    /** More waits than the connection has request slots, and an observe behind them still answers at once. */
    @Test
    void waitsGiveTheirWorkerBack() throws Exception {
        int waits = TransportLimits.RPC_MAX_IN_FLIGHT_PER_CONNECTION + 4;
        try (RpcServer server = TestTransports.rpc(app());
             WsTestClient client = new WsTestClient(server.port())) {
            // A full cap first, and the rest only once those have suspended: a wait holds its worker until its
            // first poll has run, so a burst of more than the cap can race ahead of the suspensions.
            for (int i = 1; i <= waits; i++) {
                if (i == TransportLimits.RPC_MAX_IN_FLIGHT_PER_CONNECTION + 1) Thread.sleep(300);
                client.send("{\"id\":" + i + ",\"method\":\"mc.wait.condition\",\"params\":{\"invoke\":\"probe.echo\","
                        + "\"field\":\"nope\",\"timeoutMs\":4000,\"pollMs\":200}}");
            }
            Thread.sleep(300);
            client.send("{\"id\":999,\"method\":\"mc.events\",\"params\":{\"op\":\"list\"}}");
            String first = client.poll(2_000);
            assertTrue(first != null, "nothing answered while the waits were suspended");
            Map<?, ?> r = WsTestClient.decode(first);
            assertEquals(999L, ((Number) r.get("id")).longValue(), "the observe should answer first: " + r);
            for (int i = 0; i < waits; i++) {
                String s = client.poll(10_000);
                assertTrue(s != null, "wait " + i + " never answered");
                Map<?, ?> w = (Map<?, ?>) WsTestClient.decode(s).get("result");
                assertEquals(Boolean.FALSE, w.get("satisfied"), s);
            }
        }
    }

    @Test
    void theCapIsPerConnection() throws Exception {
        int cap = TransportLimits.RPC_MAX_IN_FLIGHT_PER_CONNECTION;
        try (RpcServer server = TestTransports.rpc(app());
             WsTestClient busy = new WsTestClient(server.port());
             WsTestClient other = new WsTestClient(server.port())) {
            for (int i = 1; i <= cap; i++) busy.send(slowWait(i, 3_000));
            Thread.sleep(300);
            Map<?, ?> r = other.roundTrip("{\"id\":1,\"method\":\"mc.events\",\"params\":{\"op\":\"list\"}}");
            assertInstanceOf(Map.class, r.get("result"), r.toString());
        }
    }
}
