package net.magicterra.worlddriver.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.Callable;

import net.magicterra.worlddriver.rpc.RpcClient;
import net.magicterra.worlddriver.script.McpBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A user script or a {@code ScriptEvents} callback runs on the server thread. A call from there that
 * waits on the server thread cannot finish; it must say so at once instead of freezing the game until
 * a timeout. Each case below pretends the test thread is the server thread.
 */
@Timeout(20)
class ServerThreadGuardTest {

    @BeforeEach void onTheServerThread() { ServerThreadGuard.install(() -> true); }
    @AfterEach void offIt() { ServerThreadGuard.uninstall(); }

    /** A loopback port nothing listens on: the refusal must come before any connect attempt. */
    private static int deadPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static void assertRefusedFast(String what, Callable<?> call) {
        long t0 = System.nanoTime();
        IllegalStateException e = assertThrows(IllegalStateException.class, call::call, what);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(e.getMessage().contains("server thread"), what + ": " + e.getMessage());
        assertTrue(ms < 1000, what + " took " + ms + "ms to refuse");
    }

    @Test
    void anRpcRoundTripIsRefused() throws IOException {
        int port = deadPort();
        assertRefusedFast("RpcClient", () -> new RpcClient("127.0.0.1", port));
    }

    @Test
    void anMcpRoundTripIsRefused() throws IOException {
        int port = deadPort();
        assertRefusedFast("McpBridge", () -> new McpBridge("127.0.0.1", port).call("mc.events", Map.of()));
    }

    @Test
    void aForegroundWaitIsRefused() {
        DriverApi api = new DriverApi();
        assertRefusedFast("mc.wait.condition", () -> api.wait.condition(Map.of(
                "invoke", "mc.events", "params", Map.of("op", "list"), "field", "nope", "timeoutMs", 5000)));
    }

    /** A named bot player, because the client's own bot does not exist in a JVM test; both reach the same await. */
    @Test
    void anAwaitMsOrderIsRefusedBeforeItStarts() {
        DriverApi api = new DriverApi();
        assertRefusedFast("awaitMs", () -> api.route("mc.bot.mine",
                Map.of("blocks", java.util.List.of("minecraft:stone"), "body", "npc:ghost", "awaitMs", 5000)));
    }

    @Test
    void aBackgroundWaitIsStillAllowed() {
        DriverApi api = new DriverApi();
        Map<String, Object> ack = api.wait.condition(Map.of(
                "invoke", "mc.events", "params", Map.of("op", "list"), "background", true));
        assertTrue(ack.containsKey("waitId"), ack.toString());
    }

    @Test
    void offTheServerThreadNothingIsRefused() throws IOException {
        ServerThreadGuard.uninstall();
        int port = deadPort();
        assertThrows(IOException.class, () -> new RpcClient("127.0.0.1", port),
                "a worker thread gets the ordinary connect failure");
    }
}
