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
                )),
            roTool("mc.debug.plan",
                "READ-ONLY deterministic single-search probe (no Walker, no walking, no block edits, " +
                "no world mutation). Runs one PathFinder.findPath from a fixed start to an XZ goal and " +
                "returns the committed best-effort segment + a backtrack verdict. The measurement backbone " +
                "for pathfinding A/B: set a NODE budget (mc.bot.setting{pathfinder.maxNodes:N, pathfinder.maxMs:30000}) " +
                "so the search is fully repeatable, then call from a fixed suite of start feet and compare " +
                "the forward-rate before/after a change. Returns {ok, start, goal, goalReached, pathLen, end, " +
                "expanded, computeMs, finalCost, hStart, hEnd, hDelta (<0 = forward progress, >=0 = backward/lateral), forward}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "goal", Map.of("type", "object",
                            "description", "XZ goal column {x,z}.",
                            "properties", Map.of("x", Map.of("type", "integer"), "z", Map.of("type", "integer")),
                            "required", List.of("x", "z")),
                        "from", Map.of("type", "object",
                            "description", "Optional fixed start foot {x,y,z}. Omit to use the bot's current block position.",
                            "properties", Map.of("x", Map.of("type", "integer"), "y", Map.of("type", "integer"), "z", Map.of("type", "integer")),
                            "required", List.of("x", "y", "z")),
                        "chain", Map.of("type", "boolean",
                            "description", "Simulate the full segment-commitment chain read-only: feed each committed endpoint back in as the next start until the goal is reached or no progress. Returns {reached, segments, backwardSegments, maxRegression (worst overshoot back past best progress; /10≈blocks), trail[]}. The headline backtrack metric."),
                        "maxSegments", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                            "description", "Chain cap (default 40).")
                    ),
                    "required", List.of("goal")
                ))
        );
    }
}
