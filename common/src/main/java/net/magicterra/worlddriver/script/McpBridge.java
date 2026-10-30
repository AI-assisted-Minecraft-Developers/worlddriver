package net.magicterra.worlddriver.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.thread.ServerThreadGuard;

/**
 * The body of {@code Driver.system.mcpRoundtrip(method, params)}: the same call as an MCP {@code tools/call} over HTTP,
 * answered with the envelope {@link ScriptCalls#invokeJson} gives so a parity check compares like with like. The result
 * is {@code structuredContent}, with an image's bytes put back under the field its spec names; an error is the
 * {@code ApiError} the server sent, whether as a JSON-RPC error's {@code data} or as a tool error's text.
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
        this.client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.images = images;
    }

    /** {@code {"result":…}} or {@code {"error":ApiError}}; {@code argsJson} null sends no arguments. */
    public String callJson(String toolName, String argsJson) {
        // Outside the envelope: a refusal is the script's mistake, not an outcome of the call.
        ServerThreadGuard.refuseBlocking("an MCP round-trip");
        JsonObject envelope = new JsonObject();
        try {
            envelope.add("result", call(toolName, argsJson == null ? null : JsonParser.parseString(argsJson)));
        } catch (BridgeError e) {
            envelope.add("error", ApiErrors.toTree(e.error));
        } catch (Exception e) {
            envelope.add(
                    "error",
                    ApiErrors.toTree(new ApiError(
                            ApiError.Code.OUTCOME_UNKNOWN,
                            "mcpRoundtrip failed: " + e.getMessage(),
                            List.of(),
                            ApiError.Execution.UNKNOWN)));
        }
        return envelope.toString();
    }

    private static final class BridgeError extends RuntimeException {
        final ApiError error;

        BridgeError(ApiError error) {
            super(error.message());
            this.error = error;
        }
    }

    private JsonElement call(String toolName, JsonElement args) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        if (args != null) {
            params.add("arguments", args);
        }
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", seq.incrementAndGet());
        req.addProperty("method", "tools/call");
        req.add("params", params);

        HttpRequest httpReq = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(req.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!(JsonParser.parseString(resp.body()) instanceof JsonObject m)) {
            throw new IllegalStateException("MCP response not an object: " + resp.body());
        }
        if (m.get("error") instanceof JsonObject err) {
            ApiError api = ApiErrors.fromTree(err.get("data"));
            throw new BridgeError(
                    api != null
                            ? api
                            : ApiError.of(
                                    ApiError.Code.INTERNAL_ERROR,
                                    "MCP error " + err.get("code") + ": " + err.get("message")));
        }
        if (!(m.get("result") instanceof JsonObject rm)) {
            throw new IllegalStateException("MCP result not an object");
        }
        JsonObject textBlock = null;
        JsonObject imageBlock = null;
        if (rm.get("content") instanceof JsonArray cl) {
            for (JsonElement b : cl) {
                if (!(b instanceof JsonObject bm)) {
                    continue;
                }
                String type = string(bm.get("type"));
                if ("text".equals(type) && textBlock == null) {
                    textBlock = bm;
                } else if ("image".equals(type) && imageBlock == null) {
                    imageBlock = bm;
                }
            }
        }
        String text = textBlock == null ? null : string(textBlock.get("text"));
        if (rm.get("isError") instanceof JsonPrimitive e && e.isBoolean() && e.getAsBoolean()) {
            ApiError api = text == null ? null : ApiErrors.fromTree(JsonParser.parseString(text));
            throw new BridgeError(
                    api != null ? api : ApiError.of(ApiError.Code.INTERNAL_ERROR, "MCP tool error: " + text));
        }
        JsonElement inner = rm.has("structuredContent")
                ? rm.get("structuredContent")
                : (text == null || text.isEmpty()) ? JsonNull.INSTANCE : JsonParser.parseString(text);
        MethodSpec.ImageHint hint = images.apply(toolName);
        if (imageBlock != null && inner instanceof JsonObject dm) {
            JsonObject merged = dm.deepCopy();
            merged.add(hint != null ? hint.dataField() : "base64", imageBlock.get("data"));
            return merged;
        }
        return inner;
    }

    private static String string(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
