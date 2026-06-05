package net.magicterra.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import net.magicterra.agent.mcp.catalog.BotTools;
import net.magicterra.agent.mcp.catalog.ClientTools;
import net.magicterra.agent.mcp.catalog.ObserveActionTools;
import net.magicterra.agent.mcp.catalog.RecipeTools;
import net.magicterra.agent.mcp.catalog.ScriptTools;
import net.magicterra.agent.mcp.catalog.SystemTools;
import net.magicterra.agent.mcp.catalog.WaitTools;

/**
 * MCP tool catalog. The fixed section order is load-bearing (system → script →
 * observe/action → wait → client → bot). Optional, strippable subsystems append
 * their schemas via {@link #registerExtra}; with none registered the catalog is
 * exactly the fixed set, so removing such a subsystem needs no edit here.
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    private static final List<Supplier<List<Map<String, Object>>>> EXTRA = new CopyOnWriteArrayList<>();

    /** Register an extra schema supplier (e.g. the path-debug tool). Inert until called. */
    public static void registerExtra(Supplier<List<Map<String, Object>>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        EXTRA.add(supplier);
    }

    public static List<Map<String, Object>> tools() {
        ArrayList<Map<String, Object>> all = new ArrayList<>();
        all.addAll(SystemTools.tools());
        all.addAll(ScriptTools.tools());
        all.addAll(ObserveActionTools.tools());
        all.addAll(RecipeTools.tools());
        all.addAll(WaitTools.tools());
        all.addAll(ClientTools.tools());
        all.addAll(BotTools.tools());
        for (Supplier<List<Map<String, Object>>> s : EXTRA) all.addAll(s.get());
        return List.copyOf(all);
    }
}
