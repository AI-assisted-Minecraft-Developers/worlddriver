package net.magicterra.agent.mcp.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One tool's identity + typed schema, bound into a single inseparable unit.
 *
 * <p>The typed {@link Schema} tree is RETAINED here (not rendered away at
 * definition time): {@link #mcpTool()} renders it for MCP {@code tools/list},
 * and {@code SchemaValidator} walks the same tree to validate route params —
 * one source, so advertisement and enforcement cannot drift.
 *
 * <p>{@code hidden} marks a method deliberately reachable over RPC but kept out
 * of {@code tools/list} (a dev/test verb). It still must be declared — RPC-only
 * is an explicit choice, never an omission (see ToolCatalog).
 */
public record ToolSchema(String name, String description, Schema schema,
                         Map<String, Object> annotations, Map<String, Object> meta,
                         boolean hidden) {
    public ToolSchema {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(schema, "schema");
    }

    /** Same tool, declared-but-RPC-only (absent from tools/list). */
    public ToolSchema asHidden() {
        return new ToolSchema(name, description, schema, annotations, meta, true);
    }

    /**
     * The MCP tools/list entry — rendering happens HERE, from the retained typed
     * schema (was Schemas.toolFull). Key order (name, description, inputSchema,
     * annotations, _meta) is unchanged from the pre-refactor output.
     */
    public Map<String, Object> mcpTool() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", Schemas.render(schema));
        if (annotations != null) m.put("annotations", annotations);
        if (meta != null) m.put("_meta", meta);
        return m;
    }
}
