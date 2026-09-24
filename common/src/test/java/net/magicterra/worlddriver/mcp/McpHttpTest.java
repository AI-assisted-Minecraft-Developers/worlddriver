package net.magicterra.worlddriver.mcp;

import net.magicterra.worlddriver.api.DriverApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP Streamable HTTP endpoint's request contract, over a real socket. */
@Timeout(30)
class McpHttpTest {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    @Test
    void aTextPlainPostIsRefusedWith415() throws Exception {
        // text/plain is a CORS "simple" content type: a page can send it with no preflight,
        // so accepting it would let a no-cors fetch reach tools/call.
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            assertEquals(415, post(server.port(), "text/plain", PING).statusCode());
        }
    }

    @Test
    void aPostWithoutContentTypeIsRefusedWith415() throws Exception {
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            assertEquals(415, post(server.port(), null, PING).statusCode());
        }
    }

    @Test
    void jsonWithParametersIsAccepted() throws Exception {
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            assertEquals(200, post(server.port(), "application/json", PING).statusCode());
            assertEquals(200, post(server.port(), "Application/JSON; charset=utf-8", PING).statusCode());
        }
    }

    @Test
    void everyIdLessMessageIsANotificationAnswered202WithNoBody() throws Exception {
        // spec: 2025-06-18 §Transports — an accepted notification MUST get 202 and no bot.
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            for (String body : new String[] {
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"mc.nope\"}}"}) {
                HttpResponse<String> r = post(server.port(), "application/json", body);
                assertEquals(202, r.statusCode(), body + " -> " + r.body());
                assertEquals("", r.body(), body);
            }
        }
    }

    @Test
    void anIdLessToolCallIsAcknowledgedButNotRun() throws Exception {
        DriverApi api = new DriverApi();
        AtomicInteger calls = new AtomicInteger();
        api.addRoute("test.probe", p -> calls.incrementAndGet());
        try (McpServer server = new McpServer(api, 0)) {
            HttpResponse<String> r = post(server.port(), "application/json",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"test.probe\"}}");
            assertEquals(202, r.statusCode());
            assertEquals(0, calls.get(), "a tool whose result nobody can receive must not run");

            post(server.port(), "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"test.probe\"}}");
            assertEquals(1, calls.get(), "the same call with an id runs");
        }
    }

    @Test
    void aNonStringMethodIsAnInvalidRequest() throws Exception {
        try (McpServer server = new McpServer(new DriverApi(), 0)) {
            HttpResponse<String> r = post(server.port(), "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":42}");
            assertEquals(400, r.statusCode(), r.body());
            assertTrue(r.body().contains("-32600"), r.body());
        }
    }

    static HttpResponse<String> post(int port, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) b.header("Content-Type", contentType);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
