package net.magicterra.worlddriver.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.rpc.JsonCodec;
import org.junit.jupiter.api.Test;

/**
 * A server-thread hop that gave up reaches an MCP client as a JSON-RPC error carrying one of two
 * codes, not as an ordinary tool error: "not executed" is safe to retry, "outcome unknown" is not,
 * and a client reading only {@code isError} text could not tell them apart from a refused argument.
 * An ordinary tool failure stays an {@code isError} result.
 */
class McpHopTimeoutTest {

    @Test
    void theTwoTimeoutsCarryTheirOwnCodesAndOtherFailuresStayToolErrors() throws Exception {
        DriverApi api = new DriverApi();
        api.addRoute("mc.test.notExecuted", p -> {
            throw new ServerThreadHop.NotExecutedException("withdrawn");
        });
        api.addRoute("mc.test.outcomeUnknown", p -> {
            throw new RuntimeException(new ServerThreadHop.OutcomeUnknownException("still running"));
        });
        api.addRoute("mc.test.badArg", p -> {
            throw new IllegalArgumentException("bad arg");
        });
        try (McpServer server = new McpServer(api, 0)) {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            Map<?, ?> ne = call(http, server.port(), 1, "mc.test.notExecuted");
            Map<?, ?> neErr = assertInstanceOf(Map.class, ne.get("error"), "reply: " + ne);
            assertEquals("-32001", String.valueOf(neErr.get("code")), "reply: " + ne);
            assertTrue(String.valueOf(neErr.get("message")).contains("withdrawn"), "reply: " + ne);

            Map<?, ?> ou = call(http, server.port(), 2, "mc.test.outcomeUnknown");
            Map<?, ?> ouErr = assertInstanceOf(Map.class, ou.get("error"), "reply: " + ou);
            assertEquals("-32002", String.valueOf(ouErr.get("code")), "reply: " + ou);
            assertTrue(String.valueOf(ouErr.get("message")).contains("still running"), "reply: " + ou);

            Map<?, ?> bad = call(http, server.port(), 3, "mc.test.badArg");
            Map<?, ?> result = assertInstanceOf(Map.class, bad.get("result"), "reply: " + bad);
            assertEquals(Boolean.TRUE, result.get("isError"), "reply: " + bad);
        }
    }

    private static Map<?, ?> call(HttpClient http, int port, int id, String tool) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return assertInstanceOf(Map.class, JsonCodec.decode(resp.body()), "body: " + resp.body());
    }
}
