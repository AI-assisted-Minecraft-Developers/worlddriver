package net.magicterra.worlddriver.integration.debug;

import net.magicterra.worlddriver.bot.debug.*;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiHandlers;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.methods.debug.DebugApiBinding;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.pathfinder.MultiTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time wiring for the path-debug subsystem: installs the recorder as the active
 * {@link PathTraceHolder#SINK} and publishes {@code mc.debug.*} as a batch of their own.
 *
 * Release strip: delete the {@code bot.debug} package and the single call to this method
 * in {@code ClientHooks.register}. Core compiles unchanged.
 */
public final class PathDebugBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger("agent-pathdebug");
    private static boolean recordersInstalled;

    private PathDebugBootstrap() {}

    /** Install the recorders once per JVM; publish the methods on {@code api}, which ignores a repeat. */
    public static synchronized void init(ApiRegistry api) {
        Objects.requireNonNull(api, "WorldDriver API is not ready");
        if (!recordersInstalled) {
            PathDebugRecorder recorder = new PathDebugRecorder();
            PathArchiveRecorder archive = new PathArchiveRecorder();
            PathArchiveRecorder.activate(archive);
            PathTraceHolder.SINK = new MultiTrace(recorder, archive);
            PathChartTool.bind(recorder);
            recordersInstalled = true;
        }
        if (api.registerBatch(batch(api))) {
            LOG.info("[pathdebug] initialised — mc.debug.pathChart + mc.debug.plan + mc.debug.replay ready (patch pathDebug on to capture)");
        }
    }

    private static ApiRegistry batchApi;
    private static ApiBatch batch;

    /** One batch object per API, so publishing it again is recognised as a retry. */
    private static ApiBatch batch(ApiRegistry api) {
        if (api != batchApi) {
            batchApi = api;
            batch = new ApiBatch(ApiRegistry.CORE_OWNER, "debug", DebugApiBinding.methods(new Methods(api)));
        }
        return batch;
    }

    private record Methods(ApiRegistry api) implements DebugApi {
        @Override
        public CompletionStage<DebugContracts.Chart> pathChart(DebugContracts.ChartParams p, CallContext ctx) {
            return ApiHandlers.run(() -> PathChartTool.render(p));
        }

        @Override
        public CompletionStage<DebugContracts.PlanReply> plan(DebugContracts.PlanParams p, CallContext ctx) {
            if (BotHooks.impl() == null) throw new IllegalStateException("mc.debug.plan reads this client's world, and there is none");
            return BotHooks.impl().clientHop().submit(() -> PlanProbeTool.plan(p), ctx);
        }

        @Override
        public CompletionStage<DebugContracts.ReplayReply> replay(DebugContracts.ReplayParams p, CallContext ctx) {
            return ReplayTool.replay(api, p, ctx);
        }
    }
}
