package net.magicterra.worlddriver.mcp;

import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.rpc.JsonCodec;
import net.magicterra.worlddriver.rpc.TransportLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The MCP server runs a bounded number of requests and event streams.
 *
 * <p>Its executor was a cached pool: every POST and every open event stream got its own OS
 * thread, a stream for the life of the connection and a {@code mc.wait.*} call for up to two
 * minutes, with no ceiling short of the JVM failing to create a native thread.
 */
@Timeout(60)
class McpConcurrencyTest {

    private static HttpRequest slowCall(int port, int id) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"mc.wait.condition\",\"arguments\":{\"invoke\":\"mc.events\","
                + "\"params\":{\"op\":\"list\"},\"field\":\"nope\",\"timeoutMs\":3000,\"pollMs\":100}}}";
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    @Test
    void postsPastTheCapAreRefusedWithABusyError() throws Exception {
        int cap = TransportLimits.MCP_MAX_IN_FLIGHT;
        int extra = 6;
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            HttpClient http = HttpClient.newHttpClient();
            List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 1; i <= cap + extra; i++) {
                calls.add(http.sendAsync(slowCall(server.port(), i), HttpResponse.BodyHandlers.ofString()));
            }
            int ok = 0, busy = 0;
            for (CompletableFuture<HttpResponse<String>> f : calls) {
                HttpResponse<String> r = f.get();
                if (r.statusCode() == 200) ok++;
                else {
                    assertEquals(503, r.statusCode(), r.body());
                    Map<?, ?> err = (Map<?, ?>) ((Map<?, ?>) JsonCodec.decode(r.body())).get("error");
                    assertEquals(String.valueOf(TransportLimits.RPC_CODE_SERVER_BUSY), String.valueOf(err.get("code")));
                    busy++;
                }
            }
            assertEquals(cap, ok, "admitted");
            assertEquals(extra, busy, "refused");
        }
    }

    @Test
    void eventStreamsPastTheCapAreRefused() throws Exception {
        int cap = TransportLimits.MCP_MAX_EVENT_STREAMS;
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                    .header("Accept", "text/event-stream").GET().build();
            List<InputStream> open = new ArrayList<>();
            try {
                for (int i = 0; i < cap; i++) {
                    HttpResponse<InputStream> r = http.send(get, HttpResponse.BodyHandlers.ofInputStream());
                    assertEquals(200, r.statusCode());
                    open.add(r.body());
                }
                HttpResponse<InputStream> over = http.send(get, HttpResponse.BodyHandlers.ofInputStream());
                open.add(over.body());
                assertEquals(503, over.statusCode(), "one stream past the cap");
            } finally {
                for (InputStream in : open) in.close();
            }
        }
    }
}
