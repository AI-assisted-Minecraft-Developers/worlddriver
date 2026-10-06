package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionSnapshot;
import net.magicterra.worlddriver.api.methods.ActionContract.CancelReason;
import net.magicterra.worlddriver.api.methods.ActionContract.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.ActionContract.RefusalReason;
import net.magicterra.worlddriver.api.methods.ActionContract.Refused;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.ControlContracts;
import net.magicterra.worlddriver.api.methods.ControlContracts.BodyStatus;
import net.magicterra.worlddriver.api.methods.ControlContracts.BodySummary;
import net.magicterra.worlddriver.api.methods.ControlContracts.Status;
import net.magicterra.worlddriver.api.methods.ControlContracts.Stopped;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.StatusView;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.body.BodyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code mc.bot.status}, {@code mc.bot.cancel} and {@code mc.bot.waypoint}. A named bot is read and stopped on
 * the server thread, which is the thread that advances it; {@code self} on the client thread. None of these
 * asks whether the body can act: a dead or paused body is still read and stopped.
 */
final class ControlMethods {
    private final DriverApplication app;

    ControlMethods(DriverApplication app) { this.app = app; }

    List<ApiMethod<?, ?>> methods() {
        return List.of(
                new ApiMethod<>(ControlContracts.CANCEL, this::cancel),
                new ApiMethod<>(ControlContracts.STATUS, this::status),
                new ApiMethod<>(ControlContracts.WAYPOINT_SPEC, (op, ctx) -> {
                    BotApi bot = BotHooks.impl();
                    if (bot == null) throw new IllegalStateException("waypoints are this client's, and there is no client here");
                    return bot.clientHop().submit(() -> bot.waypoint(op), ctx);
                }));
    }

    CompletionStage<ImmediateResponse> cancel(ControlContracts.CancelParams p, CallContext ctx) {
        if (p.actionId().isPresent()) {
            String id = p.actionId().get();
            ActionSnapshot<Object> before = ActionRegistry.GLOBAL.snapshot(id);
            ActionHandle h = ActionRegistry.GLOBAL.find(id);
            boolean stopped = h != null && h.requestCancel(CancelReason.REQUESTED);
            return done(new Stopped(stopped ? List.of(id) : List.of(), Optional.of(h == null ? before : h.snapshot())));
        }
        String body = p.body().orElse(BodyRegistry.SELF);
        String which = p.process().orElse("all");
        if (BodyRegistry.isSelf(body)) {
            BotApi bot = BotHooks.impl();
            if (bot == null) return refused(NO_CLIENT);
            return bot.clientHop().<ImmediateResponse>submit(() -> new Stopped(bot.cancel(which), Optional.empty()), ctx);
        }
        ServerThreadHop hop = app.serverHop();
        if (hop == null) return refused(body + " is unavailable: no server is attached");
        return hop.<ImmediateResponse>submit(() -> {
            BodyHost host = BodyRegistry.get(body);
            if (host == null) return unknown(body);
            // A host holds one process, so a named cancel matches its kind or nothing.
            String hit = host.cancel(which);
            return new Stopped("all".equals(which) ? List.of("all") : hit == null ? List.of() : List.of(hit), Optional.empty());
        }, ctx);
    }

    CompletionStage<ImmediateResponse> status(ControlContracts.StatusParams p, CallContext ctx) {
        String body = p.body().orElse(null);
        if (body == null || BodyRegistry.isSelf(body)) {
            BotApi bot = BotHooks.impl();
            if (bot == null) {
                return body == null ? bodies(ctx).<ImmediateResponse>thenApply(b -> new Status(b, Optional.empty())) : refused(NO_CLIENT);
            }
            return bodies(ctx).thenCompose(b -> bot.clientHop().<ImmediateResponse>submit(
                    () -> new Status(b, Optional.of(bot.bodyStatus())), ctx));
        }
        ServerThreadHop hop = app.serverHop();
        if (hop == null) return refused(body + " is unavailable: no server is attached");
        return hop.<ImmediateResponse>submit(() -> {
            BodyHost host = BodyRegistry.get(body);
            if (host == null) return unknown(body);
            BodyStatus s = new BodyStatus(host.id(), host.busy(), Optional.ofNullable(host.botState().activeName()),
                    StatusView.end(host.lastProcessEnd()), StatusView.slots(host.botState()), Optional.empty());
            return new Status(summaries(), Optional.of(s));
        }, ctx);
    }

    /**
     * The registered bots, in registration order, read on the server thread. Nothing registered answers
     * without a hop, which matters on a client joined to a remote server: there is no server thread there.
     */
    private CompletionStage<List<BodySummary>> bodies(CallContext ctx) {
        ServerThreadHop hop = app.serverHop();
        if (BodyRegistry.all().isEmpty() || hop == null) return CompletableFuture.completedFuture(List.of());
        return hop.submit(ControlMethods::summaries, ctx);
    }

    /** Server thread. */
    private static List<BodySummary> summaries() {
        List<BodySummary> out = new ArrayList<>();
        for (BodyHost h : BodyRegistry.all()) {
            LivingEntity e = h.entity();
            boolean present = e != null && !e.isRemoved();
            BlockPos at = present ? e.blockPosition() : null;
            out.add(new BodySummary(h.id(), h.kind(), present ? Optional.of(e.getId()) : Optional.empty(),
                    present ? Optional.of(new BlockPosition(at.getX(), at.getY(), at.getZ())) : Optional.empty(), h.busy()));
        }
        return out;
    }

    private static final String NO_CLIENT = "self is this client's own player, and there is no client here "
            + "(a dedicated server); name a bot with body: player:<name> or npc:<name>";

    private static ImmediateResponse unknown(String body) {
        return new Refused<>(RefusalReason.BODY_UNAVAILABLE, "no bot named " + body + "; mc.bot.status lists the bots");
    }

    private static CompletionStage<ImmediateResponse> refused(String message) {
        return done(new Refused<>(RefusalReason.BODY_UNAVAILABLE, message));
    }

    private static CompletionStage<ImmediateResponse> done(ImmediateResponse r) {
        return CompletableFuture.completedFuture(r);
    }
}
