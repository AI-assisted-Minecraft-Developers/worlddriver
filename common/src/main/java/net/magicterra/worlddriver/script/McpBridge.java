package net.magicterra.worlddriver.script;

import net.magicterra.worlddriver.api.ApiErrors;
import net.magicterra.worlddriver.api.ServerThreadGuard;
import net.magicterra.worlddriver.api.WireValues;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.protocol.JsonCodec;

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
import java.util.function.Function;

/**
 * The body of {@code Driver.system.mcpRoundtrip(method, params)}: the same call as an MCP
 * {@code tools/call} over HTTP, answered with the envelope {@code DriverApi.invokeJson} gives so a
 * parity check compares like with like. The result is {@code structuredContent}, with an image's bytes
 * put back under the field its spec names; an error is the {@code ApiError} the server sent, whether
 * as a JSON-RPC error's {@code data} or as a tool error's text.
 */
public final class McpBridge {
    private final URI endpoint;
    private final HttpClient client;
    private final AtomicInteger seq = new AtomicInteger();
    private final Function<String, MethodSpec.ImageHint> images;

    public McpBridge(String host, int port) {
        this(host, port, name -> null);
    }

    /** {@code images} names, per method, where an image's bytes belong in the in-JVM result. */
    public McpBridge(String host, int port, Function<String, MethodSpec.ImageHint> images) {
        this.endpoint = URI.create("http://" + host + ":" + port + "/mcp");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.images = images;
    }

    /** {@code {"result":…}} or {@code {"error":ApiError}}; {@code argsJson} null sends no arguments. */
    public String callJson(String toolName, String argsJson) {
        // Outside the envelope: a refusal is the script's mistake, not an outcome of the call.
        ServerThreadGuard.refuseBlocking("an MCP round-trip");
        Map<String, Object> envelope = new LinkedHashMap<>();
        try {
            envelope.put("result", call(toolName, argsJson == null ? null : JsonCodec.decode(argsJson)));
        } catch (BridgeError e) {
            envelope.put("error", ApiErrors.toTree(e.error));
        } catch (Exception e) {
            envelope.put("error", ApiErrors.toTree(new ApiError(ApiError.Code.OUTCOME_UNKNOWN,
                    "mcpRoundtrip failed: " + e.getMessage(), List.of(), ApiError.Execution.UNKNOWN)));
        }
        return WireValues.encode(envelope);
    }

    private static final class BridgeError extends RuntimeException {
        final ApiError error;
        BridgeError(ApiError error) { super(error.message()); this.error = error; }
    }

    @SuppressWarnings("unchecked")
    private Object call(String toolName, Object args) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        if (args != null) params.put("arguments", args);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", seq.incrementAndGet());
        req.put("method", "tools/call");
        req.put("params", params);

        HttpRequest httpReq = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(WireValues.encode(req), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!(JsonCodec.decode(resp.body()) instanceof Map<?, ?> m)) {
            throw new IllegalStateException("MCP response not an object: " + resp.body());
        }
        if (m.get("error") instanceof Map<?, ?> err) {
            ApiError api = ApiErrors.fromTree(err.get("data"));
            throw new BridgeError(api != null ? api : ApiError.of(ApiError.Code.INTERNAL_ERROR,
                    "MCP error " + err.get("code") + ": " + err.get("message")));
        }
        if (!(m.get("result") instanceof Map<?, ?> rm)) throw new IllegalStateException("MCP result not an object");
        Map<String, Object> textBlock = null, imageBlock = null;
        if (rm.get("content") instanceof List<?> cl) {
            for (Object b : cl) {
                if (!(b instanceof Map<?, ?> bm)) continue;
                if ("text".equals(bm.get("type")) && textBlock == null) textBlock = (Map<String, Object>) bm;
                else if ("image".equals(bm.get("type")) && imageBlock == null) imageBlock = (Map<String, Object>) bm;
            }
        }
        String text = textBlock == null ? null : (String) textBlock.get("text");
        if (Boolean.TRUE.equals(rm.get("isError"))) {
            ApiError api = text == null ? null : ApiErrors.fromTree(JsonCodec.decode(text));
            throw new BridgeError(api != null ? api : ApiError.of(ApiError.Code.INTERNAL_ERROR, "MCP tool error: " + text));
        }
        Object inner = rm.containsKey("structuredContent") ? rm.get("structuredContent")
                : (text == null || text.isEmpty()) ? null : JsonCodec.decode(text);
        MethodSpec.ImageHint hint = images.apply(toolName);
        if (imageBlock != null && inner instanceof Map<?, ?> dm) {
            Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) dm);
            merged.put(hint != null ? hint.dataField() : "base64", imageBlock.get("data"));
            return merged;
        }
        return inner;
    }
}
