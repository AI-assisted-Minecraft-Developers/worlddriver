package net.magicterra.worlddriver.integration.debug;

import net.magicterra.worlddriver.bot.debug.*;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.pathfinder.MultiTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import java.util.Objects;
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
    private static DriverApi registeredApi;
    private static boolean recordersInstalled;

    private PathDebugBootstrap() {}

    /** Register on each API; keep the recorder and schema supplier unique in this JVM. */
    public static synchronized void init(DriverApi api) {
        Objects.requireNonNull(api, "WorldDriver API is not ready");
        if (api == registeredApi) return;
        if (!recordersInstalled) {
            PathDebugRecorder recorder = new PathDebugRecorder();
            PathArchiveRecorder archive = new PathArchiveRecorder();
            PathArchiveRecorder.activate(archive);
            PathTraceHolder.SINK = new MultiTrace(recorder, archive);
            PathChartTool.bind(recorder);
            ToolCatalog.registerExtra(DebugTools::tools);
            recordersInstalled = true;
        }
        api.addRoute("mc.debug.pathChart", PathChartTool::render);
        api.addRoute("mc.debug.plan", PlanProbeTool::plan);
        api.addRoute("mc.debug.replay", ReplayTool::replay);
        registeredApi = api;
        LOG.info("[pathdebug] initialised — mc.debug.pathChart + mc.debug.plan + mc.debug.replay ready (set pathDebug:true to capture)");
    }
}
