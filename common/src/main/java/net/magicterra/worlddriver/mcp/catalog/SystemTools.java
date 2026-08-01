package net.magicterra.worlddriver.mcp.catalog;

import java.util.List;

import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

/** {@code mc.system.*} catalog entries. See {@code ToolCatalog} for ordering. */
public final class SystemTools {
    private SystemTools() {}

    public static List<ToolSchema> tools() {
        return List.of(
            roTool("mc.system.version",
                "Probe the agent driver. Use first to confirm the server is reachable. " +
                "Returns {modid:string, version:string, uptimeMs:integer}.",
                emptyObject()),

            roTool("mc.system.testOrigin",
                "Get the canonical test arena origin. Most observe/query tools below default " +
                "their search center to this point. " +
                "Returns {x:integer, y:integer, z:integer}.",
                emptyObject()),

            roTool("mc.system.waitTicks",
                "Block for N server ticks (~50ms each, approximate — server lag is not " +
                "compensated). Refuses to run on the server thread itself. For condition-based " +
                "waits (game ready, furnace done, event arrived), prefer mc.wait.* instead. " +
                "Returns {waited:integer, interrupted?:true}.",
                object().req("ticks", integer(0, 200)))
        );
    }
}
