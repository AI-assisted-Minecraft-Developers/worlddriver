package net.magicterra.worlddriver.bot.stagewright.kit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.MethodSpec;

/**
 * A registry answering the real client's {@code mc.test.*} methods ({@code mc.test.settings},
 * {@code mc.test.input.press}, …), for {@link Settings#mirrorTo} and {@link ClientRig#press}.
 *
 * <p><b>A stop-gap.</b> Where a topology joins a client to a dedicated server, StageWright's {@link Client#registry()}
 * forwards only WorldDriver's core methods to that client, not the {@code mc.test.*} ones its own mod registers there
 * (measured 2026-10-28: {@code mc.test.settings is not registered with this spec}). Until it does, this forwards
 * them over a connection of its own to the client's WorldDriver RPC port, found the way StageWright finds it: the
 * {@code worlddriver-rpc.port} file in the directory the {@code stagewright.companion.dir} property names. Where
 * {@code Client.registry()} already has them — an integrated server, or a StageWright that forwards them — it is used
 * as it is. The socket is the JDK's: testmod code reaches the driver only through its api.
 */
final class ClientTestMethods {

    private static final String COMPANION_DIR = "stagewright.companion.dir";
    /** Per call; every mc.test method answers within a few milliseconds on loopback. */
    private static final long BUDGET_MS = 30_000;

    private static volatile ApiRegistry forwarded;

    private ClientTestMethods() {}

    /** The registry that answers the client's {@code mc.test.*} methods. */
    static ApiRegistry of(Game game, Client client) {
        ApiRegistry direct = client.registry();
        if (direct.snapshot().methods().containsKey(TestHarnessContracts.SETTINGS.name())) return direct;
        ApiRegistry r = forwarded;
        if (r != null) return r;
        synchronized (ClientTestMethods.class) {
            if (forwarded == null) forwarded = connect(game.registry());
            return forwarded;
        }
    }

    private static ApiRegistry connect(ApiRegistry local) {
        String dir = System.getProperty(COMPANION_DIR);
        if (dir == null) {
            throw new IllegalStateException("the client's registry has no mc.test.settings and this game names no "
                    + COMPANION_DIR + " to reach the client by; a client test cannot pin the client's settings");
        }
        Path file = Path.of(dir, "worlddriver-rpc.port");
        int port;
        try {
            port = Integer.parseInt(Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("the joined client's RPC port is not readable from " + file + ": " + e, e);
        }
        Socket socket = new Socket();
        try {
            socket.ws = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/rpc"), socket).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("cannot reach the joined client's driver on 127.0.0.1:" + port + ": " + e, e);
        }
        List<ApiMethod<?, ?>> methods = new ArrayList<>();
        for (ApiMethod<?, ?> m : local.snapshot().methods().values()) {
            if (m.name().startsWith("mc.test.")) methods.add(forward(m.spec(), socket));
        }
        ApiRegistry registry = new ApiRegistry();
        registry.registerBatch(new ApiBatch(ApiRegistry.TEST_OWNER, "client-test-methods", methods));
        return registry;
    }

    private static <P, R> ApiMethod<P, R> forward(MethodSpec<P, R> spec, Socket socket) {
        return new ApiMethod<>(spec, (params, context) -> {
            JsonElement tree = spec.input().encode(params);
            return socket.call(spec.name(), tree instanceof JsonObject o ? o : new JsonObject())
                    .thenApply(result -> spec.output().decode(result));
        });
    }

    /** One JSON-RPC connection: each call has its own id and future. */
    private static final class Socket implements WebSocket.Listener {
        private final Map<Long, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
        private final AtomicLong nextId = new AtomicLong(1);
        private final StringBuilder partial = new StringBuilder();
        volatile WebSocket ws;

        CompletionStage<JsonElement> call(String method, JsonObject params) {
            long id = nextId.getAndIncrement();
            JsonObject req = new JsonObject();
            req.addProperty("jsonrpc", "2.0");
            req.addProperty("id", id);
            req.addProperty("method", method);
            req.add("params", params);
            JsonObject meta = new JsonObject();
            meta.addProperty("budgetMs", BUDGET_MS - 250);
            req.add("_meta", meta);
            CompletableFuture<JsonElement> f = new CompletableFuture<>();
            pending.put(id, f);
            synchronized (this) {
                // One message at a time: the JDK refuses a send while the previous one is in flight.
                ws.sendText(req.toString(), true).join();
            }
            return f.orTimeout(BUDGET_MS, TimeUnit.MILLISECONDS).whenComplete((r, t) -> pending.remove(id));
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                route(frame);
            }
            webSocket.request(1);
            return null;
        }

        private void route(String frame) {
            JsonElement decoded;
            try {
                decoded = JsonParser.parseString(frame);
            } catch (RuntimeException bad) {
                return;
            }
            if (!(decoded instanceof JsonObject resp) || resp.has("method")) return;
            if (!(resp.get("id") instanceof JsonPrimitive n) || !n.isNumber()) return;
            CompletableFuture<JsonElement> f = pending.remove(n.getAsLong());
            if (f == null) return;
            JsonElement err = resp.get("error");
            if (err == null || err.isJsonNull()) {
                f.complete(resp.has("result") ? resp.get("result") : JsonNull.INSTANCE);
                return;
            }
            ApiError api = err instanceof JsonObject e ? ApiErrors.fromTree(e.get("data")) : null;
            f.completeExceptionally(api != null ? new ApiException(api)
                    : ApiException.of(ApiError.Code.INTERNAL_ERROR, "the client answered " + err));
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            fail(new IOException("the client's RPC connection closed: " + statusCode + " " + reason));
            return null;
        }

        private void fail(Throwable t) {
            forwarded = null;
            for (CompletableFuture<JsonElement> f : pending.values()) f.completeExceptionally(t);
            pending.clear();
        }
    }
}
