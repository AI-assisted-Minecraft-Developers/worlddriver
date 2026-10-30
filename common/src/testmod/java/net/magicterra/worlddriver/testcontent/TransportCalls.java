package net.magicterra.worlddriver.testcontent;

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
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.schema.ApiError;

/**
 * One call over the driver's WebSocket RPC server or its MCP HTTP server, made as an outside client makes it, answered
 * as the envelope {@code Driver.invoke} reads: {@code {"result":…}} or {@code {"error":ApiError}}. The validation
 * scripts' transport-parity checks compare that with the in-JVM answer, so the conversion here is the one an external
 * client needs and no more: an RPC error's {@code data}, an MCP error's {@code data} or tool-error text, and an MCP
 * result's {@code structuredContent} with an image's bytes put back under {@code base64}.
 */
final class TransportCalls {
    private TransportCalls() {}

    private static final long BUDGET_MS = 30_000L;
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** An error the server reported, carried to the envelope. */
    private static final class Refused extends Exception {
        final ApiError error;

        Refused(ApiError error) {
            super(error.message());
            this.error = error;
        }
    }

    /** {@code method} with {@code paramsJson} (null: none) over a fresh WebSocket connection to {@code port}. */
    static String rpc(int port, String method, String paramsJson) {
        return envelope("rpcRoundtrip", () -> {
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", SEQ.incrementAndGet());
            req.addProperty("method", method);
            if (paramsJson != null) req.add("params", JsonParser.parseString(paramsJson));
            JsonObject meta = new JsonObject();
            meta.addProperty("budgetMs", BUDGET_MS - 250);
            req.add("_meta", meta);
            String id = req.get("id").toString();
            CompletableFuture<JsonObject> reply = new CompletableFuture<>();
            WebSocket ws = HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/rpc"), new WebSocket.Listener() {
                        private final StringBuilder text = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) {
                                // A frame without our id is a notification the server pushed.
                                if (JsonParser.parseString(text.toString()) instanceof JsonObject m
                                        && m.has("id")
                                        && m.get("id").toString().equals(id)) {
                                    reply.complete(m);
                                }
                                text.setLength(0);
                            }
                            socket.request(1);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket socket, Throwable error) {
                            reply.completeExceptionally(error);
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                            reply.completeExceptionally(
                                    new IllegalStateException("ws closed: " + status + " " + reason));
                            return null;
                        }
                    })
                    .get(10, TimeUnit.SECONDS);
            try {
                ws.sendText(req.toString(), true).get(10, TimeUnit.SECONDS);
                JsonObject resp = reply.get(BUDGET_MS, TimeUnit.MILLISECONDS);
                if (!(resp.get("error") instanceof JsonObject e)) {
                    return resp.has("result") ? resp.get("result") : JsonNull.INSTANCE;
                }
                ApiError api = ApiErrors.fromTree(e.get("data"));
                throw new Refused(
                        api != null
                                ? api
                                : ApiError.of(
                                        ApiError.Code.INTERNAL_ERROR,
                                        "rpc error " + e.get("code") + ": " + string(e.get("message"))));
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(t -> null);
            }
        });
    }

    /** {@code tool} with {@code argsJson} (null: none) as an MCP {@code tools/call} to {@code port}. */
    static String mcp(int port, String tool, String argsJson) {
        return envelope("mcpRoundtrip", () -> {
            JsonObject params = new JsonObject();
            params.addProperty("name", tool);
            if (argsJson != null) params.add("arguments", JsonParser.parseString(argsJson));
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", SEQ.incrementAndGet());
            req.addProperty("method", "tools/call");
            req.add("params", params);
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .timeout(Duration.ofMillis(BUDGET_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(req.toString(), StandardCharsets.UTF_8))
                    .build();
            String body = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .body();
            if (!(JsonParser.parseString(body) instanceof JsonObject m)) {
                throw new IllegalStateException("MCP response not an object: " + body);
            }
            if (m.get("error") instanceof JsonObject err) {
                ApiError api = ApiErrors.fromTree(err.get("data"));
                throw new Refused(
                        api != null
                                ? api
                                : ApiError.of(
                                        ApiError.Code.INTERNAL_ERROR,
                                        "MCP error " + err.get("code") + ": " + string(err.get("message"))));
            }
            if (!(m.get("result") instanceof JsonObject rm))
                throw new IllegalStateException("MCP result not an object");
            JsonObject textBlock = null, imageBlock = null;
            if (rm.get("content") instanceof JsonArray blocks) {
                for (JsonElement b : blocks) {
                    if (!(b instanceof JsonObject bm)) continue;
                    String type = string(bm.get("type"));
                    if ("text".equals(type) && textBlock == null) textBlock = bm;
                    else if ("image".equals(type) && imageBlock == null) imageBlock = bm;
                }
            }
            String text = textBlock == null ? null : string(textBlock.get("text"));
            if (rm.get("isError") instanceof JsonPrimitive e && e.isBoolean() && e.getAsBoolean()) {
                ApiError api = text == null ? null : ApiErrors.fromTree(JsonParser.parseString(text));
                throw new Refused(
                        api != null ? api : ApiError.of(ApiError.Code.INTERNAL_ERROR, "MCP tool error: " + text));
            }
            JsonElement inner = rm.has("structuredContent")
                    ? rm.get("structuredContent")
                    : (text == null || text.isEmpty()) ? JsonNull.INSTANCE : JsonParser.parseString(text);
            if (imageBlock != null && inner instanceof JsonObject dm) {
                JsonObject merged = dm.deepCopy();
                merged.add("base64", imageBlock.get("data"));
                return merged;
            }
            return inner;
        });
    }

    @FunctionalInterface
    private interface Call {
        JsonElement run() throws Exception;
    }

    private static String envelope(String what, Call call) {
        JsonObject envelope = new JsonObject();
        try {
            envelope.add("result", call.run());
        } catch (Refused e) {
            envelope.add("error", ApiErrors.toTree(e.error));
        } catch (Exception e) {
            envelope.add(
                    "error",
                    ApiErrors.toTree(new ApiError(
                            ApiError.Code.OUTCOME_UNKNOWN,
                            what + " failed: " + e,
                            List.of(),
                            ApiError.Execution.UNKNOWN)));
        }
        return envelope.toString();
    }

    private static String string(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
