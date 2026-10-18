package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlApiBinding;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.BotStatus;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.BotSummary;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionSnapshot;
import net.magicterra.worlddriver.api.methods.shared.Responses.CancelReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.api.methods.shared.Responses.Status;
import net.magicterra.worlddriver.api.methods.shared.Responses.Stopped;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.StatusView;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code mc.bot.status}, {@code mc.bot.cancel} and {@code mc.bot.waypoint}. A named bot is read and stopped on
 * the server thread, which is the thread that advances it; {@code self} on the client thread. None of these
 * asks whether the body can act: a dead or paused body is still read and stopped.
 */
final class ControlService implements ControlApi {
    private final DriverApplication app;

    ControlService(DriverApplication app) { this.app = app; }

    List<ApiMethod<?, ?>> methods() {
        return ControlApiBinding.methods(this);
    }

    @Override
    public CompletionStage<ControlContracts.WaypointReply> waypoint(ControlContracts.WaypointOp op, CallContext ctx) {
        BotApi bot = BotHooks.impl();
        if (bot == null) throw new IllegalStateException("waypoints are this client's, and there is no client here");
        return bot.clientHop().submit(() -> bot.waypoint(op), ctx);
    }

    @Override
    public CompletionStage<ImmediateResponse> cancel(ControlContracts.CancelParams p, CallContext ctx) {
        if (p.actionId().isPresent()) {
            String id = p.actionId().get();
            ActionSnapshot<Object> before = ActionRegistry.GLOBAL.snapshot(id);
            ActionHandle h = ActionRegistry.GLOBAL.find(id);
            boolean stopped = h != null && h.requestCancel(CancelReason.REQUESTED);
            return done(new Stopped(stopped ? List.of(id) : List.of(), Optional.of(h == null ? before : h.snapshot())));
        }
        String body = p.bot().orElse(BotRegistry.SELF);
        String which = p.process().map(ControlContracts.ProcessName::wireName).orElse("all");
        if (BotRegistry.isSelf(body)) {
            BotApi bot = BotHooks.impl();
            if (bot == null) return refused(NO_CLIENT);
            return bot.clientHop().<ImmediateResponse>submit(() -> new Stopped(bot.cancel(which), Optional.empty()), ctx);
        }
        ServerThreadHop hop = app.serverHop();
        if (hop == null) return refused(body + " is unavailable: no server is attached");
        return hop.<ImmediateResponse>submit(() -> {
            Bot host = BotRegistry.get(body);
            if (host == null) return unknown(body);
            // A host holds one process, so a named cancel matches its kind or nothing.
            String hit = host.cancel(which);
            return new Stopped("all".equals(which) ? List.of("all") : hit == null ? List.of() : List.of(hit), Optional.empty());
        }, ctx);
    }

    @Override
    public CompletionStage<ImmediateResponse> status(ControlContracts.StatusParams p, CallContext ctx) {
        String body = p.bot().orElse(null);
        if (body == null || BotRegistry.isSelf(body)) {
            BotApi bot = BotHooks.impl();
            if (bot == null) {
                return body == null ? bodies(ctx).<ImmediateResponse>thenApply(b -> new Status(b, Optional.empty())) : refused(NO_CLIENT);
            }
            return bodies(ctx).thenCompose(b -> bot.clientHop().<ImmediateResponse>submit(
                    () -> new Status(b, Optional.of(bot.botStatus())), ctx));
        }
        ServerThreadHop hop = app.serverHop();
        if (hop == null) return refused(body + " is unavailable: no server is attached");
        return hop.<ImmediateResponse>submit(() -> {
            Bot host = BotRegistry.get(body);
            if (host == null) return unknown(body);
            BotStatus s = new BotStatus(host.id(), host.busy(), Optional.ofNullable(host.botState().activeName()),
                    StatusView.end(host.lastProcessEnd()), StatusView.slots(host.botState()), Optional.empty());
            return new Status(summaries(), Optional.of(s));
        }, ctx);
    }

    /**
     * The registered bots, in registration order, read on the server thread. Nothing registered answers
     * without a hop, which matters on a client joined to a remote server: there is no server thread there.
     */
    private CompletionStage<List<BotSummary>> bodies(CallContext ctx) {
        ServerThreadHop hop = app.serverHop();
        if (BotRegistry.all().isEmpty() || hop == null) return CompletableFuture.completedFuture(List.of());
        return hop.submit(ControlService::summaries, ctx);
    }

    /** Server thread. */
    private static List<BotSummary> summaries() {
        List<BotSummary> out = new ArrayList<>();
        for (Bot h : BotRegistry.all()) {
            LivingEntity e = h.entity();
            boolean present = e != null && !e.isRemoved();
            BlockPos at = present ? e.blockPosition() : null;
            out.add(new BotSummary(h.id(), h.kind(), present ? Optional.of(e.getId()) : Optional.empty(),
                    present ? Optional.of(Positions.position(at)) : Optional.empty(), h.busy()));
        }
        return out;
    }

    private static final String NO_CLIENT = "self is this client's own player, and there is no client here "
            + "(a dedicated server); name a bot with bot: player:<name>, mob:<name> or entity:<uuid>";

    private static ImmediateResponse unknown(String body) {
        return new Refused<>(RefusalReason.BOT_UNAVAILABLE, "no bot named " + body + "; mc.bot.status lists the bots");
    }

    private static CompletionStage<ImmediateResponse> refused(String message) {
        return done(new Refused<>(RefusalReason.BOT_UNAVAILABLE, message));
    }

    private static CompletionStage<ImmediateResponse> done(ImmediateResponse r) {
        return CompletableFuture.completedFuture(r);
    }
}
