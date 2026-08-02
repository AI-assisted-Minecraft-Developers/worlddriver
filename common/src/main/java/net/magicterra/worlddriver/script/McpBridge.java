package net.magicterra.worlddriver.script;

import net.magicterra.worlddriver.rpc.JsonCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Used as the body of Driver.system.mcpRoundtrip(method, params).
 *
 * Sends a JSON-RPC 2.0 tools/call request over HTTP to the local MCP server,
 * pulls the "text" content of the first content block, JSON-decodes it, and
 * returns the result Object. Lets validation scripts compare MCP-path results
 * against in-JVM results from the same DriverApi route.
 */
public final class McpBridge {
    private final URI endpoint;
    private final HttpClient client;
    private final AtomicInteger seq = new AtomicInteger();

    public McpBridge(String host, int port) {
        this.endpoint = URI.create("http://" + host + ":" + port + "/mcp");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Calls a tool by name and returns the inner result as a parsed Object
     * (Map / List / Number / String / Boolean / null) — same shape DriverApi
     * would have returned in-process.
     */
    @SuppressWarnings("unchecked")
    public Object call(String toolName, Map<String, Object> args) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", seq.incrementAndGet());
        req.put("method", "tools/call");
        req.put("params", Map.of("name", toolName, "arguments", args == null ? Map.of() : args));

        String body = JsonCodec.encode(req);
        try {
            HttpRequest httpReq = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Object decoded = JsonCodec.decode(resp.body());
            if (!(decoded instanceof Map<?, ?> m)) {
                throw new RuntimeException("MCP response not an object: " + resp.body());
            }
            Object err = m.get("error");
            if (err != null) {
                throw new RuntimeException("MCP error: " + err);
            }
            Object result = m.get("result");
            if (!(result instanceof Map<?, ?> rm)) {
                throw new RuntimeException("MCP result not an object: " + result);
            }
            Object content = rm.get("content");
            if (!(content instanceof List<?> cl) || cl.isEmpty()) {
                throw new RuntimeException("MCP tool returned no content");
            }
            Object isErr = rm.get("isError");

            // Find the text block (usually content[0]) and any image block
            // (present for screenshot-shaped results). Order is not guaranteed
            // by the spec; we scan instead of indexing.
            Map<String, Object> textBlock = null;
            Map<String, Object> imageBlock = null;
            for (Object b : cl) {
                if (!(b instanceof Map<?, ?> bm)) continue;
                String type = (String) bm.get("type");
                if ("text".equals(type) && textBlock == null) {
                    textBlock = (Map<String, Object>) bm;
                } else if ("image".equals(type) && imageBlock == null) {
                    imageBlock = (Map<String, Object>) bm;
                }
            }
            if (textBlock == null && imageBlock == null) {
                throw new RuntimeException("MCP tool returned no text or image block");
            }

            String text = textBlock != null ? (String) textBlock.get("text") : null;
            if (Boolean.TRUE.equals(isErr)) {
                throw new RuntimeException("MCP tool isError: " + text);
            }

            Object inner = (text == null || text.isEmpty()) ? null : JsonCodec.decode(text);

            // If the server split a screenshot-shaped result into [text(meta), image(bytes)],
            // re-attach the bytes as 'base64' so JS scripts comparing in-JVM and MCP-path
            // results see byte-compatible maps. (Spec says image.data is base64.)
            if (imageBlock != null && inner instanceof Map<?, ?> dm) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) dm);
                merged.put("base64", imageBlock.get("data"));
                return merged;
            }
            return inner;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("mcpRoundtrip failed: " + e.getMessage(), e);
        }
    }

    /** JSON-in / JSON-out variant used by the JS prelude. */
    @SuppressWarnings("unchecked")
    public String callJson(String toolName, String argsJson) {
        Map<String, Object> args;
        if (argsJson == null || argsJson.isBlank() || argsJson.equals("null")) {
            args = Map.of();
        } else {
            Object decoded = JsonCodec.decode(argsJson);
            args = (decoded instanceof Map<?, ?> mm) ? (Map<String, Object>) mm : Map.of();
        }
        Object result = call(toolName, args);
        return JsonCodec.encode(result);
    }
}
