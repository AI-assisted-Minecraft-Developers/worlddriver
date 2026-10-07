package net.magicterra.worlddriver.application;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.ApiTimers;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionResponse;
import net.magicterra.worlddriver.api.methods.ActionContract.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.ActionContract.Refused;
import net.magicterra.worlddriver.api.methods.HandCalls;
import net.magicterra.worlddriver.api.methods.HandCallsBinding;
import net.magicterra.worlddriver.api.methods.HandContracts;
import net.magicterra.worlddriver.api.methods.HandContracts.LookParams;
import net.magicterra.worlddriver.api.methods.HandContracts.Looked;
import net.magicterra.worlddriver.api.methods.HandContracts.OnEntity;
import net.magicterra.worlddriver.api.methods.HandContracts.Used;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.body.BodyRegistry;
import net.magicterra.worlddriver.bot.process.LookProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** The verbs that act within the call, on {@code self} through {@link BotApi} and on a named bot through {@link BodyInteractions}. */
final class HandMethods implements HandCalls {
    /** How many times, {@link #SETTLE_MS} apart, a use on an entity rereads the vehicle and screen the server answers with. */
    private static final int SETTLE_READS = 6;
    private static final long SETTLE_MS = 50;

    private final ActionService actions;

    HandMethods(ActionService actions) { this.actions = actions; }

    List<ApiMethod<?, ?>> methods() {
        return HandCallsBinding.methods(this);
    }

    @Override
    public CompletionStage<ImmediateResponse> attackEntity(HandContracts.AttackParams p, CallContext ctx) {
        return actions.within(p.body(), ctx, Refused::new,
                (bot, self) -> bot.attackEntity(p), host -> BodyInteractions.attackEntity(host, p));
    }

    @Override
    public CompletionStage<ImmediateResponse> equip(HandContracts.EquipParams p, CallContext ctx) {
        return actions.within(BodyRegistry.SELF, ctx, Refused::new,
                (bot, self) -> bot.equip(p), host -> { throw new IllegalStateException("equip is self's alone"); });
    }

    @Override
    public CompletionStage<ActionResponse<Looked>> lookAt(LookParams p, CallContext ctx) {
        if (BotConfig.smoothLook) {
            return actions.start(p.body(), p.awaitMs(), ctx, body -> {
                float[] aim = BodyInteractions.aim(body, p);
                BlockPos track = p.pos().map(b -> new BlockPos(b.x(), b.y(), b.z())).orElse(null);
                return ActionService.Start.reporting(new LookProcess(track, aim[0], aim[1]), new Looked(aim[0], aim[1]));
            });
        }
        return actions.within(p.body(), ctx, Refused::new,
                (bot, self) -> BodyInteractions.turn(self, p), host -> BodyInteractions.turn(host.entity(), p));
    }

    @Override
    public CompletionStage<ImmediateResponse> holdItem(HandContracts.HoldParams p, CallContext ctx) {
        BotActions.requireRegistered(BuiltInRegistries.ITEM, "item", "/item", p.item());
        return actions.within(p.body(), ctx, Refused::new, (bot, self) -> bot.holdItem(p), host -> BodyInteractions.holdItem(host, p));
    }

    /**
     * On {@code self}, a use on an entity rereads the vehicle and screen until they change or the reads run
     * out: the server mounts or opens the menu on a later tick, and the client applies it on another.
     */
    @Override
    public CompletionStage<ImmediateResponse> useItem(HandContracts.UseParams p, CallContext ctx) {
        CompletionStage<ImmediateResponse> used = actions.within(p.body(), ctx, Refused::new,
                (bot, self) -> bot.useItem(p), host -> BodyInteractions.useItem(host, p));
        if (!BodyRegistry.isSelf(p.body())) return used;
        return used.thenCompose(r -> r instanceof Used u && u.on() instanceof OnEntity before
                ? settle(BotHooks.impl(), u, before, 0, ctx) : CompletableFuture.completedFuture(r));
    }

    private static CompletionStage<ImmediateResponse> settle(BotApi bot, Used used, OnEntity before, int read, CallContext ctx) {
        CompletableFuture<ImmediateResponse> out = new CompletableFuture<>();
        ApiTimers.schedule(() -> bot.clientHop().submit(() -> bot.afterUse(before), ctx).whenComplete((now, t) -> {
            if (t != null) {
                out.complete(used);
                return;
            }
            Used latest = new Used(used.hand(), used.result(), used.consumed(), now);
            boolean changed = !now.riding().equals(before.riding()) || !now.screen().equals(before.screen());
            if (changed || read + 1 >= SETTLE_READS) out.complete(latest);
            else settle(bot, latest, before, read + 1, ctx).thenAccept(out::complete);
        }), read == 0 ? 0 : SETTLE_MS);
        return out;
    }
}
