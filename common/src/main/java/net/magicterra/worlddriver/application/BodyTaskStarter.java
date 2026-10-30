package net.magicterra.worlddriver.application;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.call.ApiTimers;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.call.WaitPermits;
import net.magicterra.worlddriver.api.methods.shared.Responses.Accepted;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionSnapshot;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.api.methods.shared.Responses.Unready;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

/**
 * How every body-task method starts its process and answers: the steps the contract fixes, in order.
 *
 * <ol>
 *   <li>A call that wants to wait checks it may, and takes a wait permit, before anything happens.
 *   <li>On the body's own game thread (the client thread for {@code self}, the server thread for a named bot) the body
 *       is resolved and the method's planner reads it and builds the process, or says why it cannot; the request is
 *       turned away there with nothing started.
 *   <li>Only then is the action admitted, and installed in the same step, superseding the body's held one.
 *   <li>Off the game thread, the answer waits for the action's ending or the wait's deadline, whichever is first; no
 *       thread is held while it does.
 * </ol>
 *
 * <p>Nothing here names a client class: {@code self} is reached through {@link BotApi}, which only a client registers.
 */
final class BodyTaskStarter {

    private static final String NO_CLIENT = "self is this client's own player, and there is no client here "
            + "(a dedicated server); name a bot with bot: player:<name>, mob:<name> or entity:<uuid>";

    /** Margin kept off the caller's deadline so the answer is written before the caller gives up. */
    private static final long REPLY_MARGIN_MS = 250;

    private final DriverApplication app;
    private final ActionRegistry actions;

    /** Reads the body on its game thread and decides what to start. */
    @FunctionalInterface
    interface Planner<R> {
        Plan<R> plan(LivingEntity body);
    }

    /** What a planner decided. */
    sealed interface Plan<R> permits Start, Launch, Refuse {}

    /**
     * Start {@code process}; a success reports what {@code result} reads when the process ends, on the executor's
     * thread, so it may count what the process did.
     */
    record Start<R>(BotProcess process, Supplier<? extends R> result) implements Plan<R> {
        /** A result fixed when the process starts. */
        static <R> Start<R> reporting(BotProcess process, R summary) {
            return new Start<>(process, () -> summary);
        }
    }

    /**
     * Work that is not the body's held process (a route preview): {@code launcher} gets the admitted handle on the game
     * thread, binds its stopper, and ends it with the result.
     */
    record Launch<R>(Consumer<ActionHandle> launcher) implements Plan<R> {}

    /** Start nothing: a valid request this body cannot take now. */
    record Refuse<R>(RefusalReason reason, String message) implements Plan<R> {}

    BodyTaskStarter(DriverApplication app, ActionRegistry actions) {
        this.app = app;
        this.actions = actions;
    }

    /** Start an action on {@code body} and answer as {@code awaitMs} asks. */
    <R> CompletionStage<ActionResponse<R>> start(String body, int awaitMs, CallContext ctx, Planner<R> planner) {
        WaitPermits.Lease lease = null;
        if (awaitMs > 0) {
            ctx.requireBlockingAllowed("awaitMs");
            lease = ctx.waits().tryAcquire();
            if (lease == null) {
                throw ApiException.of(
                        ApiError.Code.SERVER_BUSY,
                        "server busy: every wait slot is in use; retry, or call with awaitMs 0");
            }
        }
        WaitPermits.Lease held = lease;
        try {
            CompletionStage<ActionResponse<R>> started =
                    BotRegistry.isSelf(body) ? startOnSelf(ctx, planner) : startOnHost(body, ctx, planner);
            return started.thenCompose(r -> r instanceof Accepted<R> a
                                    && awaitMs > 0
                                    && !a.action().terminal()
                            ? awaitEnding(a.action(), awaitMs, ctx)
                            : CompletableFuture.completedFuture(r))
                    .whenComplete((r, t) -> {
                        if (held != null) {
                            held.close();
                        }
                    });
        } catch (RuntimeException e) {
            if (held != null) {
                held.close();
            }
            throw e;
        }
    }

    private <R> CompletionStage<ActionResponse<R>> startOnSelf(CallContext ctx, Planner<R> planner) {
        BotApi bot = BotHooks.impl();
        if (bot == null) {
            return refused(RefusalReason.BOT_UNAVAILABLE, NO_CLIENT);
        }
        long generation = actions.generation(ActionHandle.Scope.CLIENT);
        ServerThreadHop hop = bot.clientHop();
        Executor client = bot.clientExecutor();
        return hop.submit(
                () -> {
                    actions.requireGeneration(ActionHandle.Scope.CLIENT, generation);
                    Map<String, Object> refusal = bot.bodyRefusal();
                    LivingEntity self = bot.selfEntity();
                    if (refusal != null || self == null) {
                        return unavailable(refusal);
                    }
                    return admitAndStart(
                            BotRegistry.SELF, ActionHandle.Scope.CLIENT, ctx, planner.plan(self), (process, h) -> {
                                h.bindStopper(why ->
                                        client.execute(() -> bot.cancelAction(h, "cancelled: " + why.wireName())));
                                bot.startAction(process, h);
                            });
                },
                ctx);
    }

    private <R> CompletionStage<ActionResponse<R>> startOnHost(String body, CallContext ctx, Planner<R> planner) {
        ServerThreadHop hop = app.serverHop();
        var server = app.server();
        if (hop == null || server == null) {
            return refused(RefusalReason.BOT_UNAVAILABLE, body + " is unavailable: no server is attached");
        }
        long generation = actions.generation(ActionHandle.Scope.SERVER);
        return hop.submit(
                () -> {
                    actions.requireGeneration(ActionHandle.Scope.SERVER, generation);
                    Bot host = BotRegistry.resolve(body, server);
                    if (host == null) {
                        return unknown(body, server);
                    }
                    Map<String, Object> refusal = host.refusal();
                    if (refusal != null) {
                        return unavailable(refusal);
                    }
                    return admitAndStart(
                            host.id(), ActionHandle.Scope.SERVER, ctx, planner.plan(host.entity()), (process, h) -> {
                                h.bindStopper(why -> {
                                    try {
                                        server.execute(() -> host.cancelAction(h, "cancelled: " + why.wireName()));
                                    } catch (RuntimeException gone) {
                                        /* the server is stopping; its processes stop with it */
                                    }
                                });
                                host.start(process, h);
                            });
                },
                ctx);
    }

    /**
     * Work that ends within the call, on the body's game thread: {@code onSelf} on the client thread with this client's
     * player, {@code onHost} on the server thread with a named bot. A body that cannot act, or that another run holds,
     * is answered through {@code refuse} with nothing done.
     */
    <T, R> CompletionStage<T> within(
            String body,
            CallContext ctx,
            Function<Refused<R>, T> refuse,
            BiFunction<BotApi, LivingEntity, T> onSelf,
            Function<Bot, T> onHost) {
        if (BotRegistry.isSelf(body)) {
            BotApi bot = BotHooks.impl();
            if (bot == null) {
                return CompletableFuture.completedFuture(
                        refuse.apply(new Refused<>(RefusalReason.BOT_UNAVAILABLE, NO_CLIENT)));
            }
            return bot.clientHop()
                    .submit(
                            () -> {
                                Map<String, Object> refusal = bot.bodyRefusal();
                                LivingEntity self = bot.selfEntity();
                                if (refusal != null || self == null) {
                                    return refuse.apply(unavailable(refusal));
                                }
                                ActionRegistry.Denied busy = actions.claim(BotRegistry.SELF, ctx.run());
                                return busy != null
                                        ? refuse.apply(new Refused<>(busy.reason(), busy.message()))
                                        : onSelf.apply(bot, self);
                            },
                            ctx);
        }
        ServerThreadHop hop = app.serverHop();
        if (hop == null) {
            return CompletableFuture.completedFuture(refuse.apply(
                    new Refused<>(RefusalReason.BOT_UNAVAILABLE, body + " is unavailable: no server is attached")));
        }
        return hop.submit(
                () -> {
                    Bot host = BotRegistry.resolve(body, app.server());
                    if (host == null) {
                        return refuse.apply(unknown(body, app.server()));
                    }
                    Map<String, Object> refusal = host.refusal();
                    if (refusal != null) {
                        return refuse.apply(unavailable(refusal));
                    }
                    ActionRegistry.Denied busy = actions.claim(host.id(), ctx.run());
                    return busy != null
                            ? refuse.apply(new Refused<>(busy.reason(), busy.message()))
                            : onHost.apply(host);
                },
                ctx);
    }

    /** {@code body} names no bot. */
    private static <R> Refused<R> unknown(String body, MinecraftServer server) {
        return new Refused<>(
                RefusalReason.BOT_UNAVAILABLE, BotRegistry.unresolved(body, server), Optional.of(Unready.UNKNOWN_BOT));
    }

    /** The bot cannot act now; {@code refusal} is the facade's {@code {error, reason}}, null for no player. */
    private static <R> Refused<R> unavailable(Map<String, Object> refusal) {
        if (refusal == null) {
            return new Refused<>(RefusalReason.BOT_UNAVAILABLE, "self has no player", Optional.of(Unready.NO_PLAYER));
        }
        return new Refused<>(
                RefusalReason.BOT_UNAVAILABLE,
                String.valueOf(refusal.get("error")),
                Unready.of(String.valueOf(refusal.get("reason"))));
    }

    @FunctionalInterface
    private interface Installer {
        void install(BotProcess process, ActionHandle handle);
    }

    /** Game thread: admit and install in one step, so no other start can come between them. */
    @SuppressWarnings("unchecked")
    private <R> ActionResponse<R> admitAndStart(
            String body, ActionHandle.Scope scope, CallContext ctx, Plan<R> plan, Installer installer) {
        if (plan instanceof Refuse<R> r) {
            return new Refused<>(r.reason(), r.message());
        }
        Supplier<?> result = plan instanceof Start<R> s ? s.result() : null;
        ActionRegistry.Admission admission = actions.admit(body, scope, ctx.run(), result);
        if (admission instanceof ActionRegistry.Denied d) {
            return new Refused<>(d.reason(), d.message());
        }
        ActionHandle h = ((ActionRegistry.Admitted) admission).handle();
        try {
            if (plan instanceof Launch<R> l) {
                l.launcher().accept(h);
            } else {
                installer.install(((Start<R>) plan).process(), h);
            }
        } catch (RuntimeException e) {
            // A half-installed action must not stay running, and nothing may name it as a replacement.
            h.fail("the process could not be started: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
        return new Accepted<>((ActionSnapshot<R>) (ActionSnapshot<?>) h.snapshot(), false);
    }

    /**
     * Off the game thread: answer with the action's ending, or with it still running once the wait runs out. Both read
     * the same atomic state, so an answer never says ended and timed out at once.
     */
    @SuppressWarnings("unchecked")
    private <R> CompletionStage<ActionResponse<R>> awaitEnding(
            ActionSnapshot<R> started, int awaitMs, CallContext ctx) {
        ActionHandle h = actions.find(started.actionId());
        if (h == null) {
            return CompletableFuture.completedFuture(new Accepted<>(started, false));
        }
        long waitMs = Math.min(awaitMs, Math.max(0, ctx.remainingMillis() - REPLY_MARGIN_MS));
        CompletableFuture<ActionResponse<R>> out = new CompletableFuture<>();
        Runnable deadline = () -> {
            ActionSnapshot<R> now = (ActionSnapshot<R>) (ActionSnapshot<?>) h.snapshot();
            out.complete(new Accepted<>(now, !now.terminal()));
        };
        ScheduledFuture<?> timer = ApiTimers.schedule(deadline, waitMs);
        AutoCloseable gone = ctx.requestCancelled().onCancel(deadline);
        h.ended().thenAccept(s -> out.complete(new Accepted<>((ActionSnapshot<R>) (ActionSnapshot<?>) s, false)));
        return out.whenComplete((r, t) -> {
            timer.cancel(false);
            try {
                gone.close();
            } catch (Exception ignored) {
                /* nothing to release */
            }
        });
    }

    private static <R> CompletionStage<ActionResponse<R>> refused(RefusalReason reason, String message) {
        return CompletableFuture.completedFuture(new Refused<>(reason, message));
    }

    /** {@code invalid_world_target} for a target the current world cannot hold. */
    static <R> Plan<R> invalidTarget(String message) {
        return new Refuse<>(RefusalReason.INVALID_WORLD_TARGET, message);
    }

    /** {@code unsupported_capability}: this body cannot do the work at all. */
    static <R> Plan<R> unsupported(String message) {
        return new Refuse<>(RefusalReason.UNSUPPORTED_CAPABILITY, message);
    }
}
