package net.magicterra.agent.bot.debug;

import java.util.List;
import java.util.Map;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/** MCP schema for the path-debug tool. Registered via {@code ToolCatalog.registerExtra}. */
public final class DebugTools {
    private DebugTools() {}

    public static List<Map<String, Object>> tools() {
        return List.of(
            roTool("mc.debug.pathChart",
                "Render the current goto session's pathfinding debug dashboard to a PNG on disk " +
                "(config/agent_driver/debug/) and return its absolute path. Overlays A* candidates, " +
                "every planned route (latest bold, failed dashed), and the actual walked trajectory " +
                "(speed-coloured) on a top-down X/Z map, plus an elevation profile and speed / heading " +
                "time-series. Requires mc.bot.setting{pathDebug:true} BEFORE the goto so data is captured. " +
                "Callable any time — mid-walk or after success/failure. Returns {ok, path, width, height, " +
                "bytes, outcome, plans, candidates, samples}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "width", Map.of("type", "integer", "minimum", 256, "maximum", 4096,
                            "description", "Image width px. Default 1280."),
                        "height", Map.of("type", "integer", "minimum", 256, "maximum", 4096,
                            "description", "Image height px. Default 960."),
                        "includeCandidates", Map.of("type", "boolean",
                            "description", "Draw A* expanded-node heat. Default true."),
                        "save", Map.of("type", "boolean",
                            "description", "Write to disk. Default true; false returns dims only."),
                        "name", Map.of("type", "string",
                            "description", "Optional file name (no extension). Default pathchart-NNNN-<ms>.")
                    )
                ))
        );
    }
}
