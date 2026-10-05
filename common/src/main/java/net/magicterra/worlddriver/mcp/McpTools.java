package net.magicterra.worlddriver.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;

/**
 * The MCP view of the method registry: {@code tools/list} entries and the image adaptation of a
 * result. A typed method's entry comes from its {@link MethodSpec} (input and output schema,
 * annotations); a method not yet typed keeps its catalog {@link ToolSchema}.
 */
public final class McpTools {
    private McpTools() {}

    /** Every listed tool, in the catalog's section order, then typed methods the catalog does not name. */
    public static List<Map<String, Object>> list(DriverApi api) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> callable = api.methods();
        for (ToolSchema s : ToolCatalog.schemas()) {
            if (!callable.contains(s.name()) || !done.add(s.name())) continue;
            MethodSpec<?, ?> spec = api.spec(s.name());
            if (spec != null) {
                if (spec.listedInMcp()) out.add(entry(spec));
            } else if (!s.hidden()) {
                out.add(s.mcpTool());
            }
        }
        for (String name : callable) {
            if (done.contains(name)) continue;
            MethodSpec<?, ?> spec = api.spec(name);
            if (spec != null && spec.listedInMcp()) out.add(entry(spec));
        }
        return List.copyOf(out);
    }

    /** One typed tool: its output schema is what {@code structuredContent} will hold. */
    public static Map<String, Object> entry(MethodSpec<?, ?> spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", spec.name());
        m.put("description", spec.description());
        m.put("inputSchema", spec.input().schema());
        m.put("outputSchema", structuredSchema(spec));
        m.put("annotations", spec.mcpAnnotations());
        return m;
    }

    /**
     * The output schema MCP clients validate {@code structuredContent} against. For an image method
     * it is the metadata view: the bytes travel as a native image block, not inside the JSON.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> structuredSchema(MethodSpec<?, ?> spec) {
        Map<String, Object> s = spec.output().schema();
        MethodSpec.ImageHint image = spec.image();
        if (image == null) return s;
        Map<String, Object> copy = new LinkedHashMap<>(s);
        if (copy.get("properties") instanceof Map<?, ?> props) {
            Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) props);
            p.remove(image.dataField());
            copy.put("properties", p);
        }
        if (copy.get("required") instanceof List<?> req) {
            List<Object> r = new ArrayList<>(req);
            r.remove(image.dataField());
            copy.put("required", r);
        }
        return copy;
    }
}
