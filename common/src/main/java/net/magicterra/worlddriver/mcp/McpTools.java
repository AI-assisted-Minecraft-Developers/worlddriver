package net.magicterra.worlddriver.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * The MCP view of the method registry: {@code tools/list} entries and the image adaptation of a
 * result. Each entry comes from the method's {@link MethodSpec}: input and output schema, annotations.
 */
public final class McpTools {
    private McpTools() {}

    /** Every listed method in registration order; a hidden spec is callable but not advertised. */
    public static List<Map<String, Object>> list(ApiRegistry registry) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiMethod<?, ?> m : registry.snapshot().methods().values()) {
            if (m.spec().listedInMcp()) out.add(entry(m.spec()));
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
