package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.mcp.McpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both network transports apply one Origin policy.
 *
 * <p>A browser page can open a WebSocket to loopback (WebSockets are outside CORS) and can
 * send an {@code Origin: null} POST from a sandboxed iframe. Either reaches
 * {@code mc.script.eval}, which owns the process, so the handshake and the POST are the
 * only places the page can be refused.
 */
@Timeout(30)
class TransportOriginTest {

    @Test
    void policyAllowsOnlyAbsentOrLoopbackHttpOrigins() {
        assertTrue(OriginPolicy.isAllowed(null));
        assertTrue(OriginPolicy.isAllowed("http://localhost:3000"));
        assertTrue(OriginPolicy.isAllowed("http://127.0.0.1"));
        assertTrue(OriginPolicy.isAllowed("https://[::1]:8443"));

        assertFalse(OriginPolicy.isAllowed("null"), "the opaque origin of a sandboxed iframe");
        assertFalse(OriginPolicy.isAllowed(""));
        assertFalse(OriginPolicy.isAllowed("https://evil.example"));
        assertFalse(OriginPolicy.isAllowed("http://localhost.evil.example"));
        assertFalse(OriginPolicy.isAllowed("http://127.0.0.1@evil.example"));
        assertFalse(OriginPolicy.isAllowed("file://localhost"));
        assertFalse(OriginPolicy.isAllowed("not a uri"));
    }

    // ------------------------------------------------------------------ WebSocket

    @Test
    void rpcHandshakeFromAForeignOriginIsRefused() throws Exception {
        try (RpcServer server = new RpcServer(new DriverApi(), 0)) {
            assertEquals(403, handshakeStatus(server.port(), "https://evil.example"));
        }
    }

    @Test
    void rpcHandshakeFromTheNullOriginIsRefused() throws Exception {
        try (RpcServer server = new RpcServer(new DriverApi(), 0)) {
            assertEquals(403, handshakeStatus(server.port(), "null"));
        }
    }

    @Test
    void rpcHandshakeWithoutOriginOrFromLoopbackIsAccepted() throws Exception {
        try (RpcServer server = new RpcServer(new DriverApi(), 0)) {
            assertEquals(101, handshakeStatus(server.port(), null));
            assertEquals(101, handshakeStatus(server.port(), "http://127.0.0.1:5173"));
        }
    }

    /** Status code of a raw RFC 6455 upgrade request; {@code origin == null} sends none. */
    static int handshakeStatus(int port, String origin) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            StringBuilder req = new StringBuilder()
                    .append("GET /rpc HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n");
            if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
            req.append("\r\n");
            OutputStream os = s.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            os.flush();
            String status = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            if (status == null) throw new IllegalStateException("connection closed without a status line");
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    // ------------------------------------------------------------------ MCP HTTP

    @Test
    void mcpPostFromAForeignOriginIsRefused() throws Exception {
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            assertEquals(403, ping(server.port(), "https://evil.example").statusCode());
        }
    }

    @Test
    void mcpPostFromTheNullOriginIsRefused() throws Exception {
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            assertEquals(403, ping(server.port(), "null").statusCode());
        }
    }

    @Test
    void mcpPostWithoutOriginOrFromLoopbackIsAccepted() throws Exception {
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            assertEquals(200, ping(server.port(), null).statusCode());
            assertEquals(200, ping(server.port(), "http://localhost:6274").statusCode());
        }
    }

    private static HttpResponse<String> ping(int port, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        if (origin != null) b.header("Origin", origin);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
