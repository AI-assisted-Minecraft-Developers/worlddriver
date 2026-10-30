package net.magicterra.worlddriver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.MethodSpec;

/**
 * The MCP view of the method registry: {@code tools/list} entries and the image adaptation of a result. Each entry
 * comes from the method's {@link MethodSpec}: input and output schema, annotations.
 */
public final class McpTools {
    private McpTools() {}

    /** Every listed method in registration order; a hidden spec is callable but not advertised. */
    public static List<JsonObject> list(ApiRegistry registry) {
        List<JsonObject> out = new ArrayList<>();
        for (ApiMethod<?, ?> m : registry.snapshot().methods().values()) {
            if (m.spec().listedInMcp()) out.add(entry(m.spec()));
        }
        return List.copyOf(out);
    }

    /** One typed tool: its output schema is what {@code structuredContent} will hold. */
    public static JsonObject entry(MethodSpec<?, ?> spec) {
        JsonObject m = new JsonObject();
        m.addProperty("name", spec.name());
        m.addProperty("description", spec.description());
        m.add("inputSchema", spec.input().schema());
        m.add("outputSchema", structuredSchema(spec));
        m.add("annotations", spec.mcpAnnotations());
        return m;
    }

    /**
     * The output schema MCP clients validate {@code structuredContent} against. For an image method it is the metadata
     * view: the bytes travel as a native image block, not inside the JSON.
     */
    static JsonObject structuredSchema(MethodSpec<?, ?> spec) {
        JsonObject s = spec.output().schema();
        MethodSpec.ImageHint image = spec.image();
        if (image == null) return s;
        if (s.get("properties") instanceof JsonObject props) props.remove(image.dataField());
        if (s.get("required") instanceof JsonArray req) req.remove(new JsonPrimitive(image.dataField()));
        return s;
    }
}
