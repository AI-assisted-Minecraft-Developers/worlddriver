package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.DriverApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static Set<Thread> rpcThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith("agent-rpc-"))
                .collect(Collectors.toSet());
    }
}
