package net.magicterra.agent.bot.debug;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.mcp.ToolCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time wiring for the path-debug subsystem. Registers the recorder as the active
 * {@link PathTraceHolder#SINK}, the {@code mc.debug.pathChart} route (via the generic
 * {@link AgentApi#addRoute}), and its schema (via {@link ToolCatalog#registerExtra}).
 *
 * Release strip: delete the {@code bot.debug} package and the single call to this method
 * in {@code ClientHooks.register}. Core compiles unchanged.
 */
public final class PathDebugBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger("agent-pathdebug");
    private static volatile boolean done;

    private PathDebugBootstrap() {}

    public static synchronized void init() {
        if (done) return;
        done = true;   // set once up-front so SINK/bind/registerExtra can never double-register
        PathDebugRecorder recorder = new PathDebugRecorder();
        PathTraceHolder.SINK = recorder;
        PathChartTool.bind(recorder);
        ToolCatalog.registerExtra(DebugTools::tools);
        AgentApi api = AgentDriverCommon.api();
        if (api != null) {
            api.addRoute("mc.debug.pathChart", PathChartTool::render);
            LOG.info("[pathdebug] initialised — mc.debug.pathChart ready (set pathDebug:true to capture)");
        } else {
            // Unreachable on the real client path (ensureRpcUp builds the api before init runs).
            LOG.warn("[pathdebug] AgentApi not ready; route not registered");
        }
    }
}
