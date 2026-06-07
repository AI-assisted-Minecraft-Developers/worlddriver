package net.magicterra.agent.mcp;

import java.util.ArrayList;
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
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    private static final List<Supplier<List<Map<String, Object>>>> EXTRA = new CopyOnWriteArrayList<>();

    /**
     * Methods declared but deliberately kept out of MCP {@code tools/list}. Each is
     * still reachable over RPC and still carries a {@code ToolSchema} (so the boot
     * invariant passes) — hidden, not undeclared. Keep this list short and justified.
     */
    private static final List<ToolSchema> HIDDEN_TOOLS = List.of(
            // mc.test.yaml — run YAML gametests on demand; a harness verb, not an agent action.
            ToolSchema.hidden(tool("mc.test.yaml",
                    "Run YAML GameTest specs on demand (dev/test harness verb; reachable over RPC only). "
                    + "Params: file | inline | all:true.",
                    object().additionalProperties(true)))
    );

    /** Register an extra schema supplier (e.g. the path-debug tool). Inert until called. */
    public static void registerExtra(Supplier<List<Map<String, Object>>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        EXTRA.add(supplier);
    }

    /** The curated, hand-written MCP tool maps in their fixed section order (+ registered extras). */
    private static List<Map<String, Object>> curated() {
        ArrayList<Map<String, Object>> all = new ArrayList<>();
        all.addAll(SystemTools.tools());
        all.addAll(ScriptTools.tools());
        all.addAll(ObserveActionTools.tools());
        all.addAll(RecipeTools.tools());
        all.addAll(WaitTools.tools());
        all.addAll(ClientTools.tools());
        all.addAll(BotTools.tools());
        for (Supplier<List<Map<String, Object>>> s : EXTRA) all.addAll(s.get());
        return all;
    }

    /** Every declared tool as a {@link ToolSchema}: the visible curated set + the hidden ones. */
    public static List<ToolSchema> schemas() {
        List<ToolSchema> out = new ArrayList<>();
        for (Map<String, Object> m : curated()) out.add(ToolSchema.visible(m));
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
}
