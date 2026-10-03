package net.magicterra.worlddriver.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void aWarmSchemaCacheIsReadNotRebuiltUntilSomethingRegisters() {
        AtomicInteger builds = new AtomicInteger();
        ToolSchema schema = tool("architecture.cache", "test cache", object()).asHidden();
        ToolCatalog.registerExtra(() -> {
            builds.incrementAndGet();
            return List.of(schema);
        });
        Map<String, ?> warm = ToolCatalog.schemaByName();
        assertNotNull(warm.get(schema.name()), "registering invalidated the cache");
        assertSame(warm, ToolCatalog.schemaByName());
        assertEquals(1, builds.get());

        ToolCatalog.registerExtra(List::of);
        assertNotSame(warm, ToolCatalog.schemaByName());
        assertEquals(2, builds.get());
    }
}
