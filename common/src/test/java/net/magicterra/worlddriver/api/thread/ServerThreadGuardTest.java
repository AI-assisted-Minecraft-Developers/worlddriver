package net.magicterra.worlddriver.api.thread;

import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.application.DriverApplication;

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
        // A Java caller sees the guard's own exception; a call through the registry sees it as an ApiError.
        RuntimeException e = assertThrows(RuntimeException.class, call::call, what);
        assertTrue(e instanceof IllegalStateException || e instanceof ApiException, what + ": " + e);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(e.getMessage().contains("server thread") || e.getMessage().contains("game thread"),
                what + ": " + e.getMessage());
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
        assertRefusedFast("McpBridge", () -> new McpBridge("127.0.0.1", port).callJson("mc.events", "{}"));
    }

    @Test
    void aForegroundWaitIsRefused() {
        ApiRegistry api = ProbeMethods.install(new DriverApplication()).registry();
        assertRefusedFast("mc.wait.condition", () -> api.route("mc.wait.condition", Map.of(
                "invoke", "probe.echo", "field", "nope", "timeoutMs", 5000)));
    }

    /**
     * A named bot player, because the client's own bot does not exist in a JVM test; both reach the same
     * await. explore, because it names no registry id: a JVM test has no bootstrapped registries.
     */
    @Test
    void anAwaitMsOrderIsRefusedBeforeItStarts() {
        ApiRegistry api = new DriverApplication().registry();
        assertRefusedFast("awaitMs", () -> api.route("mc.bot.explore",
                Map.of("centerX", 0, "centerZ", 0, "body", "npc:ghost", "awaitMs", 5000)));
    }

    @Test
    void aBackgroundWaitIsStillAllowed() {
        ApiRegistry api = ProbeMethods.install(new DriverApplication()).registry();
        Map<?, ?> ack = (Map<?, ?>) api.route("mc.wait.condition", Map.of(
                "invoke", "probe.echo", "background", true));
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
