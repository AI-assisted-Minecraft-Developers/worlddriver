package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.WaitApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an {@link RpcServer} leaves behind when it does not come up. */
@Timeout(60)
class RpcServerLifecycleTest {

    @Test
    void aFailedBindShutsDownTheEventLoopsItStarted() throws Exception {
        // A pinned port that is taken is routine (a second game instance), and the
        // bring-up retries on an ephemeral port — so every failed attempt that leaks its
        // loops leaves a selector thread alive for the life of the JVM.
        Set<Thread> before = rpcThreads();
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertThrows(Exception.class,
                    () -> new RpcServer(new DriverApi(), "127.0.0.1", taken.getLocalPort()));
        }
        List<String> leaked = List.of();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            leaked = rpcThreads().stream().filter(t -> !before.contains(t)).map(Thread::getName).toList();
            if (leaked.isEmpty()) break;
            Thread.sleep(100);
        }
        assertEquals(List.of(), leaked, "event-loop threads of the server that never bound");
    }

    @Test
    void aPeerThatAnswersNoPingsIsClosedAfterTheSilenceWindow() throws Exception {
        // A half-open peer never errors a write the kernel can still buffer, so without
        // a liveness check its connection and subscription live as long as the JVM.
        try (RpcServer server = new RpcServer(new DriverApi(), "127.0.0.1", 0, 200, 1_000);
             WsTestClient silent = new WsTestClient(server.port())) {
            assertTrue(silent.channel.closeFuture().await(10, TimeUnit.SECONDS),
                    "a peer silent past the window must be closed");
            assertTrue(silent.pingsSeen.get() >= 1, "it must have been pinged first");
        }
    }

    @Test
    void aPeerThatAnswersPingsOutlivesTheWindowWhileSendingNothingElse() throws Exception {
        // Stands in for a client blocked on a long mc.wait.*: its library answers pings
        // while the application sends nothing.
        try (RpcServer server = new RpcServer(new DriverApi(), "127.0.0.1", 0, 200, 1_000);
             WsTestClient alive = new WsTestClient(server.port())) {
            alive.answerPings = true;
            Thread.sleep(3_000);
            assertTrue(alive.channel.isActive(), "a peer answering pings was closed");
            assertTrue(alive.pingsSeen.get() >= 3, "pings seen: " + alive.pingsSeen.get());
        }
    }

    @Test
    void theInJvmClientSurvivesACallLongerThanTheWindow() throws Exception {
        // Driver.invokeRpc opens one RpcClient per call, and an awaitMs call may block for
        // minutes; the client must answer pings meanwhile or the server hangs up on it.
        try (RpcServer server = new RpcServer(new DriverApi(), "127.0.0.1", 0, 200, 1_000);
             RpcClient client = new RpcClient("127.0.0.1", server.port())) {
            Object r = client.call("mc.wait.condition", Map.of("invoke", "mc.events",
                    "params", Map.of("op", "list"), "field", "nope", "timeoutMs", 2_500, "pollMs", 100));
            assertEquals(Boolean.FALSE, ((Map<?, ?>) r).get("satisfied"), String.valueOf(r));
        }
    }

    @Test
    void theSilenceWindowOutlastsTheLongestWait() {
        assertTrue(TransportLimits.WS_IDLE_CLOSE_MS > WaitApi.MAX_BUDGET_MS + TransportLimits.WS_PING_INTERVAL_MS,
                "a client blocked on mc.wait.* would be cut off before its wait returns");
    }

    private static Set<Thread> rpcThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith("agent-rpc-"))
                .collect(Collectors.toSet());
    }
}
