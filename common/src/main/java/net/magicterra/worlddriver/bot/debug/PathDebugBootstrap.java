package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.pathfinder.MultiTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time wiring for the path-debug subsystem. Registers the recorder as the active
 * {@link PathTraceHolder#SINK}, the {@code mc.debug.pathChart} route (via the generic
 * {@link DriverApi#addRoute}), and its schema (via {@link ToolCatalog#registerExtra}).
 *
 * Release strip: delete the {@code bot.debug} package and the single call to this method
 * in {@code ClientHooks.register}. Core compiles unchanged.
 */
public final class PathDebugBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger("agent-pathdebug");
    private static volatile boolean done;

    /** The archive recorder, reachable for tests via {@link #archiveRecorder()}. */
    private static volatile PathArchiveRecorder archiveRecorder;

    private PathDebugBootstrap() {}

    /** Returns the active {@link PathArchiveRecorder}, or {@code null} before {@link #init()} runs. */
    public static PathArchiveRecorder archiveRecorder() { return archiveRecorder; }

    public static synchronized void init() {
        if (done) return;
        done = true;   // set once up-front so SINK/bind/registerExtra can never double-register
        PathDebugRecorder recorder = new PathDebugRecorder();
        PathArchiveRecorder archive = new PathArchiveRecorder();
        archiveRecorder = archive;
        PathTraceHolder.SINK = new MultiTrace(recorder, archive);
        PathChartTool.bind(recorder);
        ToolCatalog.registerExtra(DebugTools::tools);
        DriverApi api = WorldDriverCommon.api();
        if (api != null) {
            api.addRoute("mc.debug.pathChart", PathChartTool::render);
            api.addRoute("mc.debug.plan", PlanProbeTool::plan);
            api.addRoute("mc.debug.replay", ReplayTool::replay);
            LOG.info("[pathdebug] initialised — mc.debug.pathChart + mc.debug.plan + mc.debug.replay ready (set pathDebug:true to capture)");
        } else {
            // Unreachable on the real client path (ensureRpcUp builds the api before init runs).
            LOG.warn("[pathdebug] DriverApi not ready; route not registered");
        }
    }
}
