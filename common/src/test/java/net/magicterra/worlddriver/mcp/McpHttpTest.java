package net.magicterra.worlddriver.mcp;

import net.magicterra.worlddriver.api.DriverApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    static HttpResponse<String> post(int port, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) b.header("Content-Type", contentType);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
