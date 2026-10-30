package net.magicterra.worlddriver.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.TestJson;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.application.DriverApplication;
import org.junit.jupiter.api.Test;

/**
 * An open JSON field renders a JSON array {@code "type"} in {@code tools/list}, never a typeless schema, which one MCP
 * client took as licence to send the value JSON-stringified. A field of a tagged union is looked up in each
 * {@code oneOf} variant. The validation suite's {@code 65_schema_union.js} covers the nested unions through the
 * decoder.
 */
class SchemaUnionRenderingTest {
    private static final List<String> ANY_JSON = List.of("object", "array", "string", "number", "boolean");

    @Test
    void openFieldsRenderEveryJsonType() {
        ApiRegistry api = new DriverApplication().registry();
        assertUnionType(api, "mc.wait.condition", "value");
        assertUnionType(api, "mc.events", "data");
        assertUnionType(api, "mc.events", "value");
        assertUnionType(api, "mc.skill", "args");
    }

    private static void assertUnionType(ApiRegistry api, String toolName, String propName) {
        Map<String, Object> tool = null;
        for (JsonObject j : McpTools.list(api)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) TestJson.plain(j);
            if (toolName.equals(t.get("name"))) {
                tool = t;
                break;
            }
        }
        assertNotNull(tool, "tool not found in tools/list: " + toolName);
        Map<?, ?> inputSchema =
                assertInstanceOf(Map.class, tool.get("inputSchema"), toolName + ": inputSchema not an object");
        Map<?, ?> propSchema = assertInstanceOf(
                Map.class,
                property(inputSchema, propName),
                toolName + "." + propName + ": property schema not an object");
        assertNotNull(
                propSchema.get("type"),
                toolName + "." + propName
                        + ": inputSchema has no \"type\" key (a typeless any()). Full property schema: " + propSchema);
        assertEquals(ANY_JSON, propSchema.get("type"), toolName + "." + propName);
    }

    /** {@code name}'s schema among an object schema's properties, or those of any of its {@code oneOf} variants. */
    private static Object property(Map<?, ?> schema, String name) {
        if (schema.get("properties") instanceof Map<?, ?> props && props.get(name) != null) {
            return props.get(name);
        }
        if (schema.get("oneOf") instanceof List<?> variants) {
            for (Object v : variants) {
                if (v instanceof Map<?, ?> m && property(m, name) instanceof Object found) {
                    return found;
                }
            }
        }
        return null;
    }
}
