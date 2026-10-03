package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The single validated dispatch point shared by every transport.
 * The bootstrap supplies an application implementation; this package knows no
 * transport implementation or concrete business handler.
 */
public abstract class DriverApi {
    protected final Map<String, Function<Map<String, Object>, Object>> routes = new ConcurrentHashMap<>();
    private volatile ParamsValidator paramsValidator;

    public final Object route(String method, Map<String, Object> params) {
        // ConcurrentHashMap.get(null) throws a bare NPE, which every transport would report as
        // an internal fault instead of a request that named no method.
        Function<Map<String, Object>, Object> fn = method == null ? null : routes.get(method);
        if (fn == null) throw new UnknownMethodException(method);
        Map<String, Object> p = (params == null) ? Map.of() : params;
        ParamsValidator v = paramsValidator;
        if (v != null) v.validate(method, p);
        return fn.apply(p);
    }

    /**
     * Register an additional route after construction. Used by optional, strippable
     * subsystems (e.g. the path-debug package) so core never compile-depends on them.
     * Idempotent-safe: a duplicate name overwrites. Thread-safe via the concurrent map.
     * Routes added here are reachable identically through every transport (Hard Rule #1).
     *
     * <p><b>This is the low-level seam.</b> A route added here still MUST have a declared
     * MCP {@code ToolSchema} (register one via {@code ToolCatalog.registerExtra}), or the
     * boot invariant {@link #requireSchemasFor} refuses to start. As of the paired-registration
     * change, a route reached at dispatch time with no schema is a <b>loud
     * {@link IllegalStateException}</b> in {@link #route} — schema-less dispatch is no longer
     * silently skipped. For game-affecting verbs prefer the paired
     * {@code ToolCatalog.registerVerb(schema, handler)}, which registers the schema and this
     * route together (atomically) and enforces the verb namespace policy; use raw {@code addRoute}
     * only for internal driver routes whose schema you register separately (path-debug pattern).
     */
    public final void addRoute(String method, Function<Map<String, Object>, Object> handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        routes.put(method, handler);
    }

    @SuppressWarnings("unchecked")
    public final String invokeJson(String method, String paramsJson) {
        Map<String, Object> params;
        if (paramsJson == null || paramsJson.isBlank() || paramsJson.equals("null")) {
            params = Map.of();
        } else {
            Object decoded = JsonCodec.decode(paramsJson);
            params = (decoded instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
        }
        Object result = route(method, params);
        return WireValues.encode(result);
    }

    public final Set<String> methods() { return Collections.unmodifiableSet(routes.keySet()); }

    /**
     * Boot-time invariant: every registered route MUST have a declared MCP
     * {@code ToolSchema}. The set of declared names is passed in by the bootstrap
     * (from {@code ToolCatalog.declaredMethodNames()}) so this stays in the api
     * layer without depending on the mcp/transport layer (Hard Rule #1 — core is
     * transport-agnostic). A route with no schema would be half-specified: invisible
     * to MCP {@code tools/list} yet callable, the exact drift that let methods slip
     * to RPC-only. We refuse to start rather than ship it; an intentional RPC-only
     * method is declared as a {@code hidden} ToolSchema, so it counts as "declared".
     *
     * @throws IllegalStateException if any route lacks a declared schema
     */
    public final void requireSchemasFor(Set<String> declaredToolNames) {
        Set<String> missing = new TreeSet<>(routes.keySet());
        missing.removeAll(declaredToolNames);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "worlddriver: " + missing.size() + " route(s) have no MCP ToolSchema — declare each "
                + "in ToolCatalog (a normal schema, or a hidden one for RPC-only verbs): " + missing);
        }
    }

    /**
     * Pre-dispatch params validation, injected by the bootstrap from the MCP
     * ToolCatalog (Hard Rule #1: the api layer never depends on the mcp layer —
     * same seam style as {@link #requireSchemasFor}). Covers EVERY caller of
     * {@link #route}: MCP tools/call, RPC websocket, in-JVM Rhino Driver.invoke,
     * and internal consumers (EventsApi/WaitApi/…) — one
     * contract, uniformly enforced.
     */
    public final void setParamsValidator(ParamsValidator validator) {
        this.paramsValidator = validator;
    }

    public abstract void setScriptHandler(Function<Map<String, Object>, Object> handler);
    public abstract void setPlaybookHandler(Function<Map<String, Object>, Object> handler);
    public abstract void setSkillHandler(Function<Map<String, Object>, Object> handler);
    public abstract void attachServer(MinecraftServer server);
    public abstract void detachServer();
    public abstract void clearEvents();
    public abstract void emitExternal(String type, BlockPos pos, Object data);
    public abstract void addEventListener(Consumer<DriverEvent> listener);
    public abstract void removeEventListener(Consumer<DriverEvent> listener);
    public abstract int restoreCellsOnServer(List<WorldCell> cells);
    public abstract Object query(QueryParams params);
}
