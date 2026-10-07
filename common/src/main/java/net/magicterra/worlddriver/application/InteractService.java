package net.magicterra.worlddriver.application;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.ApiTimers;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.methods.interact.InteractApi;
import net.magicterra.worlddriver.api.methods.interact.InteractApiBinding;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.LookParams;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Looked;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.OnEntity;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.api.methods.shared.Responses.Used;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.body.BodyRegistry;
import net.magicterra.worlddriver.bot.process.LookProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** The verbs that act within the call, on {@code self} through {@link BotApi} and on a named bot through {@link BodyInteractions}. */
final class InteractService implements InteractApi {
    /** How many times, {@link #SETTLE_MS} apart, a use on an entity rereads the vehicle and screen the server answers with. */
    private static final int SETTLE_READS = 6;
    private static final long SETTLE_MS = 50;

    private final BodyTaskStarter actions;

    InteractService(BodyTaskStarter actions) { this.actions = actions; }

    List<ApiMethod<?, ?>> methods() {
        return InteractApiBinding.methods(this);
    }

    @Override
    public CompletionStage<ImmediateResponse> attackEntity(InteractContracts.AttackParams p, CallContext ctx) {
        return actions.within(p.body(), ctx, Refused::new,
                (bot, self) -> bot.attackEntity(p), host -> BodyInteractions.attackEntity(host, p));
    }

    @Override
    public CompletionStage<ImmediateResponse> equip(InteractContracts.EquipParams p, CallContext ctx) {
        return actions.within(BodyRegistry.SELF, ctx, Refused::new,
                (bot, self) -> bot.equip(p), host -> { throw new IllegalStateException("equip is self's alone"); });
    }

    @Override
    public CompletionStage<ActionResponse<Looked>> lookAt(LookParams p, CallContext ctx) {
        if (BotConfig.smoothLook) {
            return actions.start(p.body(), p.awaitMs(), ctx, body -> {
                float[] aim = BodyInteractions.aim(body, p);
                BlockPos track = p.pos().map(Positions::blockPos).orElse(null);
                return BodyTaskStarter.Start.reporting(new LookProcess(track, aim[0], aim[1]), new Looked(aim[0], aim[1]));
            });
        }
        return actions.within(p.body(), ctx, Refused::new,
                (bot, self) -> BodyInteractions.turn(self, p), host -> BodyInteractions.turn(host.entity(), p));
    }

    @Override
    public CompletionStage<ImmediateResponse> holdItem(InteractContracts.HoldParams p, CallContext ctx) {
        BotTaskService.requireRegistered(BuiltInRegistries.ITEM, "item", "/item", p.item());
        return actions.within(p.body(), ctx, Refused::new, (bot, self) -> bot.holdItem(p), host -> BodyInteractions.holdItem(host, p));
    }

    /**
     * On {@code self}, a use on an entity rereads the vehicle and screen until they change or the reads run
     * out: the server mounts or opens the menu on a later tick, and the client applies it on another.
     */
    @Override
    public CompletionStage<ImmediateResponse> useItem(InteractContracts.UseParams p, CallContext ctx) {
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
