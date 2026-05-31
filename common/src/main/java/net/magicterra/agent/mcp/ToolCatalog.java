package net.magicterra.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.agent.mcp.catalog.BotTools;
import net.magicterra.agent.mcp.catalog.ClientTools;
import net.magicterra.agent.mcp.catalog.ObserveActionTools;
import net.magicterra.agent.mcp.catalog.ScriptTools;
import net.magicterra.agent.mcp.catalog.SystemTools;
import net.magicterra.agent.mcp.catalog.WaitTools;

/**
 * MCP tool catalog. Each entry mirrors a route registered in {@code AgentApi}
 * and provides a JSON schema so MCP clients can validate inputs and surface
 * sensible UIs.
 *
 * Tool descriptions are written for LLM consumption — they say what to pass,
 * what comes back, and when to use the tool. They avoid client-specific
 * jargon (no mentions of Claude / Inspector / etc.); any client speaking
 * MCP Streamable HTTP will receive identical text.
 *
 * The tool names use dots ({@code mc.observe.player}). MCP itself imposes no
 * naming rule; all spec-conformant clients handle dotted names fine.
 *
 * The actual entries live in the per-category classes under
 * {@code net.magicterra.agent.mcp.catalog} and share schema builders from
 * {@code net.magicterra.agent.mcp.schema.Schemas}. This class only concatenates
 * them — the section order below is load-bearing (it is the tool-enumeration
 * order every MCP client sees), so keep system → script → observe/action →
 * wait → client → bot.
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    public static List<Map<String, Object>> tools() {
        ArrayList<Map<String, Object>> all = new ArrayList<>();
        all.addAll(SystemTools.tools());
        all.addAll(ScriptTools.tools());
        all.addAll(ObserveActionTools.tools());
        all.addAll(WaitTools.tools());
        all.addAll(ClientTools.tools());
        all.addAll(BotTools.tools());
        return List.copyOf(all);
    }
}
