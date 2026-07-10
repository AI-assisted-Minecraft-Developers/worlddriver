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
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    private static final List<Supplier<List<ToolSchema>>> EXTRA = new CopyOnWriteArrayList<>();
    private static volatile Map<String, Schema> byNameCache;

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

    /** The curated, hand-written tool schemas in their fixed section order (+ registered extras). */
    private static List<ToolSchema> curated() {
        ArrayList<ToolSchema> all = new ArrayList<>();
        all.addAll(SystemTools.tools());
        all.addAll(ScriptTools.tools());
        all.addAll(ObserveActionTools.tools());
        all.addAll(RecipeTools.tools());
        all.addAll(WaitTools.tools());
        all.addAll(ClientTools.tools());
        all.addAll(BotTools.tools());
        for (Supplier<List<ToolSchema>> s : EXTRA) all.addAll(s.get());
        return all;
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
