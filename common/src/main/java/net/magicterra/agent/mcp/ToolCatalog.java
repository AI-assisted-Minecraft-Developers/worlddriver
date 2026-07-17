package net.magicterra.agent.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static net.magicterra.agent.mcp.schema.Schemas.object;
import static net.magicterra.agent.mcp.schema.Schemas.tool;

import net.magicterra.agent.mcp.catalog.BotTools;
import net.magicterra.agent.mcp.catalog.ClientTools;
import net.magicterra.agent.mcp.catalog.ObserveActionTools;
import net.magicterra.agent.mcp.catalog.RecipeTools;
import net.magicterra.agent.mcp.catalog.ScriptTools;
import net.magicterra.agent.mcp.catalog.SystemTools;
import net.magicterra.agent.mcp.catalog.WaitTools;
import net.magicterra.agent.mcp.schema.Schema;
import net.magicterra.agent.mcp.schema.ToolSchema;

/**
 * MCP tool catalog. The fixed section order is load-bearing (system → script →
 * observe/action → wait → client → bot). Optional, strippable subsystems append
 * their schemas via {@link #registerExtra}; with none registered the catalog is
 * exactly the fixed set, so removing such a subsystem needs no edit here.
 *
 * <h2>Schema is mandatory by construction</h2>
 * Every method exposed as a {@link ToolSchema} — its identity and its schema bound
 * together. {@link #schemas()} is the single source of which methods are declared;
 * {@link #declaredMethodNames()} feeds the bootstrap's boot-time invariant
 * (see {@code AgentApi.requireSchemasFor}) which refuses to start if any registered
 * {@code AgentApi.route} lacks a {@code ToolSchema}. So "added a route, forgot the
 * schema" fails fast at boot — it can't silently become an RPC-only method.
 *
 * <p>A method that is intentionally RPC-only (a dev/test verb, not an agent action)
 * is still <b>declared</b> here, as a {@link #HIDDEN_TOOLS hidden} {@code ToolSchema}:
 * it satisfies the invariant but is left out of {@link #tools()} (the MCP
 * {@code tools/list}). RPC-only is an explicit, reviewed choice, never an omission.
 * The typed schema also drives route-layer validation ({@code SchemaValidator}) —
 * rendering and validation read the same tree, so they cannot drift.
 *
 * <h2>Verb namespace policy</h2>
 * {@link #registerVerb} is the public, atomic extension point for adding a
 * game-affecting verb (schema + route together, so they can never drift). It
 * enforces this namespace policy at registration time (violations throw
 * {@link IllegalArgumentException}):
 * <ul>
 *   <li>The <b>{@code mc.} prefix is reserved for the driver core</b>. A verb name
 *       under {@code mc.*} is rejected…</li>
 *   <li>…<b>except {@code mc.test.*}</b>, which is granted to the testkit runtime.</li>
 *   <li>Third-party verbs MUST be namespaced under their mod id: at least one dot,
 *       and not starting with {@code mc.} (i.e. {@code <modid>.<verb>}).</li>
 * </ul>
 * The driver's own curated catalog and the internal {@code AgentApi.addRoute}
 * consumers (e.g. the path-debug package) do NOT go through {@code registerVerb}
 * and are unaffected by the policy. {@code mc.test.yaml} (a {@link #HIDDEN_TOOLS
 * hidden} harness verb registered the classic way, not via {@code registerVerb})
 * is grandfathered under the {@code mc.test.*} grant.
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    private static final List<Supplier<List<ToolSchema>>> EXTRA = new CopyOnWriteArrayList<>();
    private static volatile Map<String, Schema> byNameCache;

    /**
     * Route sink injected by the bootstrap at boot (bound to {@code AgentApi::addRoute}).
     * {@link #registerVerb} publishes its route through this so ToolCatalog never imports
     * or holds the {@code AgentApi} — it depends only on the {@code addRoute} shape,
     * mirroring the {@code AgentApi.setParamsValidator}/{@code setScriptHandler} injection
     * direction (bootstrap pushes the functional dependency in).
     */
    private static volatile BiConsumer<String, Function<Map<String, Object>, Object>> routeSink;

    /**
     * Wire the route sink used by {@link #registerVerb}. Called once from the platform
     * bootstrap ({@code AgentDriverCommon.ensureRpcUp}) right after the {@code AgentApi}
     * is constructed, with {@code api::addRoute}. Keeps the mcp layer free of any
     * {@code AgentApi} import (Hard Rule #1: only a data-flow of {@code (name, handler)}
     * crosses the seam, never a type dependency).
     */
    public static void wireRouteSink(BiConsumer<String, Function<Map<String, Object>, Object>> sink) {
        routeSink = Objects.requireNonNull(sink, "sink");
    }

    /** Policy text quoted in every namespace-violation exception. */
    private static final String NAMESPACE_POLICY =
            "namespace policy: 'mc.*' is reserved for the agent-driver core (only 'mc.test.*' "
            + "is granted, to the testkit runtime); third-party verbs must be '<modid>.<verb>' "
            + "(at least one dot, not starting with 'mc.').";

    /**
     * The single public, atomic entry point for registering a game-affecting verb:
     * one call supplies the MCP {@link ToolSchema} <b>and</b> installs the dispatch
     * route, so the pair can never be registered in isolation (the drift that let
     * routes slip to schema-less / RPC-only). Steps, in order:
     * <ol>
     *   <li>enforce the {@linkplain ToolCatalog class-level} namespace policy on
     *       {@code schema.name()} — violation throws {@link IllegalArgumentException};</li>
     *   <li>supply the schema through the {@link #registerExtra} mechanism (which
     *       invalidates the validation cache so the new verb validates immediately);</li>
     *   <li>install the route on the live {@code AgentApi} via the bootstrap-wired
     *       {@linkplain #wireRouteSink route sink};</li>
     *   <li>self-check that the route now has a resolvable schema (single-name mirror
     *       of {@code AgentApi.requireSchemasFor}) — a torn pair is an internal bug.</li>
     * </ol>
     *
     * <p><b>Ordering.</b> Must be called after the driver boots and wires the route
     * sink (mirror {@code PathDebugBootstrap.init}, which runs once the {@code AgentApi}
     * exists). A pre-boot call throws {@link IllegalStateException} — pre-boot queueing
     * is deliberately unsupported, because deferring only the route half would split the
     * atomic (schema+route) pair and could mask a mod registering before boot.
     *
     * <p><b>Baseline guard.</b> After the namespace policy passes, the name is checked
     * against the driver-owned baseline — every name in the curated fixed sections
     * ({@code SystemTools} … {@code BotTools}) plus {@link #HIDDEN_TOOLS}. A match
     * throws {@link IllegalArgumentException}: it closes the hole where a caller
     * granted the {@code mc.test.*} namespace (or, before this guard, any {@code mc.*}
     * name it could otherwise slip past a looser check) could pick a name equal to a
     * driver-owned verb — e.g. {@code mc.test.yaml} — and last-wins shadow it, both in
     * the route sink and in {@code schemaByName()}. The baseline is fixed at class-load
     * (the fixed sections + {@code HIDDEN_TOOLS} never change at runtime, and
     * {@link #registerExtra} — including the extras this method itself feeds — never
     * contributes to it by construction), so the guard cannot be bypassed by first
     * registering something to grow the baseline.
     *
     * <p><b>Duplicate names within the extra space</b> (i.e. names NOT in the driver-owned
     * baseline) still follow {@code AgentApi.addRoute} last-wins semantics for the route;
     * the schema supplier is appended (last entry for a name wins in
     * {@code schemaByName()}), so re-registering the SAME extra verb name replaces its
     * route and its effective schema. This residual last-wins is a same-classpath trust
     * boundary, not a hardened one: any code running in this JVM can call
     * {@code registerVerb} again with a third party's already-registered extra name and
     * silently replace it. The baseline guard above only protects driver-owned names —
     * it does not arbitrate between two third-party mods that collide on the same
     * {@code <modid>.<verb>} name.
     *
     * @throws IllegalArgumentException if {@code schema.name()} violates the namespace policy,
     *                                  or names a driver-owned baseline verb
     * @throws IllegalStateException    if the route sink is not yet wired (pre-boot), or the
     *                                  self-check finds the pair torn
     */
    public static void registerVerb(ToolSchema schema, Function<Map<String, Object>, Object> handler) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(handler, "handler");
        String name = schema.name();
        enforceNamespacePolicy(name);
        if (baselineNames().contains(name)) {
            throw new IllegalArgumentException(
                    "verb name '" + name + "' is driver-owned (registered via the curated catalog or "
                    + "HIDDEN_TOOLS) and cannot be re-registered through registerVerb — the paired entry "
                    + "point does not arbitrate ownership of the driver's own verbs, it only extends the "
                    + "namespace granted to third parties.");
        }
        BiConsumer<String, Function<Map<String, Object>, Object>> sink = routeSink;
        if (sink == null) {
            throw new IllegalStateException(
                    "ToolCatalog.registerVerb('" + name + "') called before agent-driver wired the "
                    + "route sink — register verbs after the driver boots (mirror PathDebugBootstrap.init, "
                    + "which runs once the AgentApi exists). Pre-boot queueing is intentionally not "
                    + "supported: it would split the atomic (schema+route) pair.");
        }
        // Atomic pair: schema first (via EXTRA — invalidates the validation cache), then the
        // route on the live api. Both halves land under this one call.
        registerExtra(() -> List.of(schema));
        sink.accept(name, handler);
        // Self-check — single-name mirror of AgentApi.requireSchemasFor. A paired entry that
        // leaves the route without a resolvable schema is a bug, not a runtime possibility.
        if (schemaByName().get(name) == null) {
            throw new IllegalStateException(
                    "ToolCatalog.registerVerb('" + name + "') left the route without a resolvable "
                    + "schema — the paired registration is torn (internal invariant violation).");
        }
    }

    /** Enforce the {@linkplain ToolCatalog class-level} verb namespace policy. */
    private static void enforceNamespacePolicy(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("verb name must be non-empty; " + NAMESPACE_POLICY);
        }
        if (name.indexOf('.') < 0) {
            throw new IllegalArgumentException(
                    "verb name '" + name + "' is not namespaced; " + NAMESPACE_POLICY);
        }
        if (name.startsWith("mc.") && !name.startsWith("mc.test.")) {
            throw new IllegalArgumentException(
                    "verb name '" + name + "' is reserved for the agent-driver core; " + NAMESPACE_POLICY);
        }
    }

    /**
     * Methods declared but deliberately kept out of MCP {@code tools/list}. Each is
     * still reachable over RPC and still carries a {@code ToolSchema} (so the boot
     * invariant passes) — hidden, not undeclared. Keep this list short and justified.
     */
    private static final List<ToolSchema> HIDDEN_TOOLS = List.of(
            // mc.test.yaml — run YAML gametests on demand; a harness verb, not an agent action.
            tool("mc.test.yaml",
                    "Run YAML GameTest specs on demand (dev/test harness verb; reachable over RPC only). "
                    + "Params: file | inline | all:true.",
                    object().additionalProperties(true)).asHidden()
    );

    /** Register an extra schema supplier (e.g. the path-debug tool). Inert until called. */
    public static void registerExtra(Supplier<List<ToolSchema>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        EXTRA.add(supplier);
        byNameCache = null;   // extras registered after boot wiring must still validate
    }

    /**
     * The curated, hand-written tool schemas in their fixed section order — NO extras.
     * This is the fixed half of {@link #curated()}, split out so the driver-owned
     * {@linkplain #baselineNames() baseline} can be computed without folding in
     * {@link #EXTRA} (registerVerb's own paired schemas among them).
     */
    private static List<ToolSchema> fixedCurated() {
        ArrayList<ToolSchema> all = new ArrayList<>();
        all.addAll(SystemTools.tools());
        all.addAll(ScriptTools.tools());
        all.addAll(ObserveActionTools.tools());
        all.addAll(RecipeTools.tools());
        all.addAll(WaitTools.tools());
        all.addAll(ClientTools.tools());
        all.addAll(BotTools.tools());
        return all;
    }

    /** The curated, hand-written tool schemas in their fixed section order (+ registered extras). */
    private static List<ToolSchema> curated() {
        ArrayList<ToolSchema> all = new ArrayList<>(fixedCurated());
        for (Supplier<List<ToolSchema>> s : EXTRA) all.addAll(s.get());
        return all;
    }

    private static volatile Set<String> baselineNamesCache;

    /**
     * The driver-owned baseline name set: every name in the fixed curated sections
     * ({@link #fixedCurated()}) plus {@link #HIDDEN_TOOLS} — deliberately WITHOUT
     * {@link #EXTRA}, so names registered through {@link #registerExtra} (including
     * {@link #registerVerb}'s own paired schemas) never join the baseline. This is
     * what {@link #registerVerb} guards: a third-party caller cannot pick a name equal
     * to a driver-owned verb and last-wins shadow it. Immutable after class init (the
     * fixed sections and {@code HIDDEN_TOOLS} never change at runtime, and extras never
     * feed the baseline by construction) — cached lazily on first use.
     */
    private static Set<String> baselineNames() {
        Set<String> c = baselineNamesCache;
        if (c == null) {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (ToolSchema s : fixedCurated()) names.add(s.name());
            for (ToolSchema s : HIDDEN_TOOLS) names.add(s.name());
            c = Collections.unmodifiableSet(names);
            baselineNamesCache = c;
        }
        return c;
    }

    /** Every declared tool: the visible curated set + the hidden ones. */
    public static List<ToolSchema> schemas() {
        List<ToolSchema> out = new ArrayList<>(curated());
        out.addAll(HIDDEN_TOOLS);
        return out;
    }

    /** Names of every declared method (visible + hidden). The bootstrap asserts that
     *  {@code AgentApi}'s route table is a subset of this — no route without a schema. */
    public static Set<String> declaredMethodNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolSchema s : schemas()) names.add(s.name());
        return names;
    }

    /** The MCP {@code tools/list} payload: every declared schema except the hidden ones. */
    public static List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSchema s : schemas()) if (!s.hidden()) out.add(s.mcpTool());
        return List.copyOf(out);
    }

    /**
     * name → typed Schema for EVERY declared tool (visible + hidden) — the
     * validation side of the single source. Cached; registerExtra invalidates.
     */
    public static Map<String, Schema> schemaByName() {
        Map<String, Schema> c = byNameCache;
        if (c == null) {
            LinkedHashMap<String, Schema> m = new LinkedHashMap<>();
            for (ToolSchema s : schemas()) m.put(s.name(), s.schema());
            byNameCache = c = Collections.unmodifiableMap(m);
        }
        return c;
    }
}
