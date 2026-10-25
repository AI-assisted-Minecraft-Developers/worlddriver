package net.magicterra.worlddriver.bot.stagewright;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.MethodSpec;

/**
 * The registry that drives the client's real player ({@code self}).
 *
 * <p>On an integrated server the client half lives in this JVM, so that is {@link Calls#api()}. On a
 * dedicated server the player is a client in another JVM, joined over the network, and its driver
 * answers on its own RPC port; the server run names the file that port is written to with
 * {@value #PORT_FILE}. This registry then holds, under every spec this JVM registers, a method that
 * sends the call there and decodes the answer with the same spec — so a scene's typed calls
 * ({@code ClientApi.of(...)}, {@code SceneBot.tasks()}, ...) read the same on both topologies. Both
 * JVMs run the same build, so the specs are the same.
 *
 * <p>The socket is the JDK's: testmod code reaches the driver only through its api, and the driver's
 * own RPC client is not part of it.
 */
public final class ClientRegistry {
    /** System property naming the joined client's {@code worlddriver-rpc.port} file; set on the server run only. */
    public static final String PORT_FILE = "worlddriver.test.clientRpcPortFile";

    /** Per call; a body task answers when it is accepted, so only a wait verb comes near this. */
    private static final long BUDGET_MS = 60_000;

    private static volatile ApiRegistry remote;

    private ClientRegistry() {}

    /** Whether self is a client in another JVM, reached over its RPC port. */
    public static boolean remote() {
        return System.getProperty(PORT_FILE) != null;
    }

    /**
     * The registry self's calls go through. On a dedicated server the first call connects, and waits
     * for the connection on the calling thread: once per run, on loopback.
     */
    public static ApiRegistry self() {
        if (!remote()) return Calls.api();
        ApiRegistry r = remote;
        if (r != null) return r;
        synchronized (ClientRegistry.class) {
            if (remote == null) remote = connect(Calls.api());
            return remote;
        }
    }

    private static ApiRegistry connect(ApiRegistry local) {
        Path file = Path.of(System.getProperty(PORT_FILE));
        int port;
        try {
            port = Integer.parseInt(Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            throw new SceneFailure("the joined client's RPC port is not readable from " + file + ": " + e);
        }
        Socket socket = new Socket();
        try {
            // Off this thread: the server thread may be the caller, and HttpClient's own threads do the work.
            socket.ws = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/rpc"), socket).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new SceneFailure("cannot reach the joined client's driver on 127.0.0.1:" + port + ": " + e);
        }
        Map<String, List<ApiMethod<?, ?>>> byOwner = new LinkedHashMap<>();
        for (ApiMethod<?, ?> m : local.snapshot().methods().values()) {
            String owner = m.name().startsWith("mc.test.") ? ApiRegistry.TEST_OWNER
                    : m.name().startsWith("mc.") ? ApiRegistry.CORE_OWNER : "client";
            byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(proxy(m.spec(), socket));
        }
        ApiRegistry registry = new ApiRegistry();
        byOwner.forEach((owner, methods) -> registry.registerBatch(new ApiBatch(owner, "remote-client", methods)));
        return registry;
    }

    private static <P, R> ApiMethod<P, R> proxy(MethodSpec<P, R> spec, Socket socket) {
        return new ApiMethod<>(spec, (params, context) -> {
            JsonElement tree = spec.input().encode(params);
            return socket.call(spec.name(), tree instanceof JsonObject o ? o : new JsonObject())
                    .thenApply(result -> spec.output().decode(result));
        });
    }

    /** One JSON-RPC connection: each call has its own id and future, as the driver's own client does. */
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
            try { decoded = JsonParser.parseString(frame); } catch (RuntimeException bad) { return; }
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
            remote = null;
            for (CompletableFuture<JsonElement> f : pending.values()) f.completeExceptionally(t);
            pending.clear();
        }
    }
}
