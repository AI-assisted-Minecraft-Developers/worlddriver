package net.magicterra.agent.mcp.schema;

import java.util.Map;
import java.util.Objects;

/**
 * One tool's identity + MCP schema, bound into a single inseparable unit.
 *
 * <p>The point of this type is a design constraint: a method is only a real,
 * advertisable tool if it ships a schema. {@code ToolCatalog} expresses every
 * registered method as a {@code ToolSchema}, and the bootstrap fails fast at
 * startup if any {@code AgentApi.route} has no matching {@code ToolSchema} — so
 * "registered a route but forgot the schema" can't drift past boot.
 *
 * <p>{@code hidden} marks a method that is deliberately reachable over RPC but
 * kept out of the MCP {@code tools/list} (a dev/test verb, not an agent action).
 * It still must be <em>declared</em> here — RPC-only is an explicit choice, never
 * an omission.
 *
 * @param name    dotted method id, e.g. {@code mc.bot.goto} (mirrors the route key)
 * @param mcpTool the MCP tool object ({@code {name, description, inputSchema, …}})
 * @param hidden  true → declared but not advertised in {@code tools/list}
 */
public record ToolSchema(String name, Map<String, Object> mcpTool, boolean hidden) {
    public ToolSchema {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mcpTool, "mcpTool");
    }

    /** A normal, MCP-advertised tool, keyed by the {@code name} in its tool map. */
    public static ToolSchema visible(Map<String, Object> mcpTool) {
        return new ToolSchema(String.valueOf(mcpTool.get("name")), mcpTool, false);
    }

    /** A declared-but-RPC-only tool: present for the boot invariant, absent from tools/list. */
    public static ToolSchema hidden(Map<String, Object> mcpTool) {
        return new ToolSchema(String.valueOf(mcpTool.get("name")), mcpTool, true);
    }
}
