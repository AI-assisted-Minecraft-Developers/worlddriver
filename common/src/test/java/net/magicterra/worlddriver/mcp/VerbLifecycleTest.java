package net.magicterra.worlddriver.mcp;

import java.util.Map;
import net.magicterra.worlddriver.application.DriverApplication;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;
import org.junit.jupiter.api.Test;
import static net.magicterra.worlddriver.mcp.schema.Schemas.*;
import static org.junit.jupiter.api.Assertions.*;

class VerbLifecycleTest {
    @Test
    void rebindingAnApiReplacesThePairWithoutDuplicatingItsSchema() {
        DriverApplication first = new DriverApplication();
        DriverApplication second = new DriverApplication();
        ToolSchema schema = tool("architecture.lifecycle", "test lifecycle", object()).asHidden();
        ToolCatalog.wireRouteSink(first::addRoute);
        ToolCatalog.registerVerb(schema, params -> "first");
        ToolCatalog.wireRouteSink(second::addRoute);
        ToolCatalog.registerVerb(schema, params -> "second");

        assertEquals("first", first.route(schema.name(), Map.of()));
        assertEquals("second", second.route(schema.name(), Map.of()));
        assertEquals(1, ToolCatalog.schemas().stream().filter(s -> s.name().equals(schema.name())).count());
        assertNotNull(ToolCatalog.schemaByName().get(schema.name()));
        assertTrue(ToolCatalog.tools().stream().noneMatch(t -> schema.name().equals(t.get("name"))));
    }
}
