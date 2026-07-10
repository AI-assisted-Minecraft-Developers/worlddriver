package net.magicterra.agent.bot.debug;

import java.util.List;

import net.magicterra.agent.mcp.schema.ToolSchema;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/** MCP schema for the path-debug tool. Registered via {@code ToolCatalog.registerExtra}. */
public final class DebugTools {
    private DebugTools() {}

    public static List<ToolSchema> tools() {
        return List.of(
            roTool("mc.debug.pathChart",
                "Render the current goto session's pathfinding debug chart to a PNG on disk " +
                "(config/agent_driver/debug/) and return its absolute path. view=\"dashboard\" (default): " +
                "A* candidates, every planned route (latest bold, failed dashed) and the actual walked " +
                "trajectory (speed-coloured) on a top-down X/Z map, plus an elevation profile and speed / " +
                "heading time-series. view=\"threeview\": an engineering-style three-view of the PATH — FRONT " +
                "(X/Y), SIDE (Z/Y) and TOP (X/Z) orthographic projections with shared aligned axes, each " +
                "overlaying the PLANNED route(s) (blue) vs the ACTUAL trajectory (speed-coloured), to read the " +
                "3D geometry of plan-vs-executed. Requires mc.bot.setting{pathDebug:true} BEFORE the goto so " +
                "data is captured. Callable any time — mid-walk or after success/failure. Returns {ok, path, " +
                "width, height, bytes, outcome, plans, candidates, samples}.",
                object()
                    .prop("view", string().desc("\"dashboard\" (default) or \"threeview\" (FRONT/SIDE/TOP plan-vs-actual projections)."))
                    .prop("width", integer(256, 4096).desc("Image width px. Default 1280."))
                    .prop("height", integer(256, 4096).desc("Image height px. Default 960."))
                    .prop("includeCandidates", bool().desc("Draw A* expanded-node heat (dashboard only). Default true."))
                    .prop("save", bool().desc("Write to disk. Default true; false returns dims only."))
                    .prop("name", string().desc("Optional file name (no extension). Default pathchart-NNNN-<ms>."))),
            roTool("mc.debug.plan",
                "READ-ONLY deterministic single-search probe (no Walker, no walking, no block edits, " +
                "no world mutation). Runs one PathFinder.findPath from a fixed start to an XZ goal and " +
                "returns the committed best-effort segment + a backtrack verdict. The measurement backbone " +
                "for pathfinding A/B: set a NODE budget (mc.bot.setting{pathfinder.maxNodes:N, pathfinder.maxMs:30000}) " +
                "so the search is fully repeatable, then call from a fixed suite of start feet and compare " +
                "the forward-rate before/after a change. Returns {ok, start, goal, goalReached, pathLen, end, " +
                "expanded, computeMs, finalCost, hStart, hEnd, hDelta (<0 = forward progress, >=0 = backward/lateral), forward}.",
                object()
                    .req("goal", xz().desc("XZ goal column {x,z}."))
                    .prop("from", pos().desc("Optional fixed start foot {x,y,z}. Omit to use the bot's current block position."))
                    .prop("chain", bool().desc("Simulate the full segment-commitment chain read-only: feed each committed endpoint back in as the next start until the goal is reached or no progress. Returns {reached, segments, backwardSegments, maxRegression (worst overshoot back past best progress; /10≈blocks), trail[]}. The headline backtrack metric."))
                    .prop("maxSegments", integer(1, 200).desc("Chain cap (default 40)."))),
            roTool("mc.debug.replay",
                "Replay a recorded goto archive: restore its block envelope, teleport the bot to the " +
                "recorded start, and re-execute the stored plan through the Walker (no re-planning) so " +
                "wedges reproduce. Writes a replay-run-*.json with the actual trajectory + per-step deviation.",
                object()
                    .prop("file", string().desc("Archive filename under config/agent_driver/replays/. Default: newest plan archive."))
                    .prop("restoreBlocks", bool().desc("Restore the recorded block envelope before replay. Default true."))
                    .prop("replan", bool().desc("Re-plan from the recorded goal instead of re-executing the stored plan verbatim. Default true."))
                    .prop("fromStep", integer(0, 100000).desc("Start at this plan step. Default 0 (MVP: accepted but ignored).")))
        );
    }
}
