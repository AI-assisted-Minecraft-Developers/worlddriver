package net.magicterra.worlddriver.debug;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.methods.debug.DebugApiBinding;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiHandlers;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.planning.MultiTrace;
import net.magicterra.worlddriver.bot.planning.PathTraceHolder;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time wiring for the debug subsystem: publishes {@code mc.debug.*} as a batch of their own wherever the driver
 * runs, and on a client also installs the path recorders as the active {@link PathTraceHolder#SINK}.
 *
 * <p>Release strip: delete the {@code debug} package, the call to {@link #publish} in {@code WorldDriverCommon} and the
 * call to {@link #init} in {@code WorldDriverClientEvents.install}. Core compiles unchanged.
 */
public final class PathDebugBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger("agent-pathdebug");
    private static boolean recordersInstalled;

    private PathDebugBootstrap() {}

    /** Publish the methods on {@code api}, reaching the server through {@code server}; a repeat is ignored. */
    public static synchronized void publish(
            ApiRegistry api, Supplier<ServerThreadHop> server, Supplier<MinecraftServer> level) {
        Objects.requireNonNull(api, "WorldDriver API is not ready");
        serverHop = server;
        serverOf = level;
        installRecorders();
        if (api.registerBatch(batch(api))) {
            LOG.info("[debug] mc.debug.* ready (patch pathDebug on to capture path charts)");
        }
    }

    /** Install the path recorders once per JVM, and publish the methods on {@code api} if they are not yet. */
    public static synchronized void init(ApiRegistry api) {
        Objects.requireNonNull(api, "WorldDriver API is not ready");
        installRecorders();
        api.registerBatch(batch(api));
    }

    /** The chart and archive recorders, once per JVM: server bots plan through them too. */
    private static void installRecorders() {
        if (recordersInstalled) {
            return;
        }
        PathDebugRecorder recorder = new PathDebugRecorder();
        PathArchiveRecorder archive = new PathArchiveRecorder();
        PathArchiveRecorder.activate(archive);
        PathTraceHolder.SINK = new MultiTrace(recorder, archive);
        PathChartTool.bind(recorder);
        recordersInstalled = true;
    }

    private static Supplier<ServerThreadHop> serverHop = () -> null;
    private static Supplier<MinecraftServer> serverOf = () -> null;
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

    /** {@code read} on the server thread: inline there, hopped from anywhere else. */
    private static <T> CompletionStage<T> onServer(Supplier<T> read, CallContext ctx) {
        ServerThreadHop hop = serverHop.get();
        if (hop == null) {
            return CompletableFuture.failedFuture(ApiException.of(
                    ApiError.Code.CAPABILITY_UNAVAILABLE,
                    "mc.debug readings of a bot are the server's, and no server is attached"));
        }
        return hop.submit(read, ctx);
    }

    private record Methods(ApiRegistry api) implements DebugApi {
        @Override
        public CompletionStage<DebugContracts.Chart> pathChart(DebugContracts.ChartParams p, CallContext ctx) {
            return ApiHandlers.run(() -> PathChartTool.render(p));
        }

        @Override
        public CompletionStage<DebugContracts.PlanReply> plan(DebugContracts.PlanParams p, CallContext ctx) {
            if (p.bot().isPresent() && !BotRegistry.isSelf(p.bot().get())) {
                return onServer(() -> PlanProbeTool.onServer(p), ctx);
            }
            if (BotHooks.impl() == null) {
                return CompletableFuture.failedFuture(ApiException.of(
                        ApiError.Code.CAPABILITY_UNAVAILABLE,
                        "mc.debug.plan for self reads this client's world, and there is none; name a server bot"));
            }
            return BotHooks.impl().clientHop().submit(() -> PlanProbeTool.onClient(p), ctx);
        }

        @Override
        public CompletionStage<DebugContracts.WalkingPlan> walkPlan(DebugContracts.WalkPlanParams p, CallContext ctx) {
            return onServer(() -> WalkPlanTool.onServer(p), ctx);
        }

        @Override
        public CompletionStage<DebugContracts.ReplayReply> replay(DebugContracts.ReplayParams p, CallContext ctx) {
            return ReplayTool.replay(api, p, ctx, serverHop.get());
        }

        @Override
        public CompletionStage<BotDebugContracts.NavigatorReading> navigator(
                BotDebugContracts.NavigatorParams p, CallContext ctx) {
            if (!BotRegistry.isSelf(p.bot())) {
                return onServer(() -> BotReadings.navigator(p), ctx);
            }
            BotApi self = BotHooks.impl();
            if (self == null) {
                return CompletableFuture.failedFuture(ApiException.of(
                        ApiError.Code.CAPABILITY_UNAVAILABLE,
                        "self is this client's own player, and there is no client here; name a server bot"));
            }
            return self.clientHop().submit(() -> BotReadings.selfNavigator(p, self), ctx);
        }

        @Override
        public CompletionStage<BotDebugContracts.BodyReading> body(BotDebugContracts.BodyParams p, CallContext ctx) {
            if (!BotRegistry.isSelf(p.bot())) {
                return onServer(() -> BotReadings.body(p), ctx);
            }
            BotApi self = BotHooks.impl();
            if (self == null) {
                return CompletableFuture.failedFuture(ApiException.of(
                        ApiError.Code.CAPABILITY_UNAVAILABLE,
                        "self is this client's own player, and there is no client here; name a server bot"));
            }
            return self.clientHop().submit(() -> BotReadings.selfBody(p, self), ctx);
        }

        @Override
        public CompletionStage<BotDebugContracts.CellsReading> cells(BotDebugContracts.CellsParams p, CallContext ctx) {
            boolean selfClient = p.bot().map(BotRegistry::isSelf).orElse(false)
                    && p.side().orElse(BotDebugContracts.Side.CLIENT) == BotDebugContracts.Side.CLIENT;
            if (!selfClient) {
                return onServer(() -> BotReadings.cells(p, serverOf.get()), ctx);
            }
            BotApi self = BotHooks.impl();
            if (self == null) {
                return CompletableFuture.failedFuture(ApiException.of(
                        ApiError.Code.CAPABILITY_UNAVAILABLE,
                        "self's client view is this client's, and there is no client here; name a server bot"));
            }
            return self.clientHop().submit(() -> ClientPlanProbe.cells(p), ctx);
        }
    }
}
