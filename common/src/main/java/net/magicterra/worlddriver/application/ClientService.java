package net.magicterra.worlddriver.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.registry.ApiHandlers;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.call.WaitPermits;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.methods.client.ClientApi;
import net.magicterra.worlddriver.api.methods.client.ClientApiBinding;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlocksParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientScene;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.application.client.ClientDriverApi;
import net.magicterra.worlddriver.application.client.ClientHooks;

/**
 * {@code mc.client.*} beyond the screen and input, which are {@link ScreenService}. Each hops onto the client
 * thread and runs there; a JVM with no client answers capability_unavailable. The calls that wait for client
 * ticks or a frame hold a wait permit until they finish, but no thread, and are refused on a game thread.
 */
final class ClientService implements ClientApi {
    ClientService() {}

    List<ApiMethod<?, ?>> methods() {
        return ClientApiBinding.methods(this);
    }

    @Override
    public CompletionStage<ClientContracts.ClientPlayer> player(NoParams p, CallContext ctx) {
        return onClient(ctx, c -> c::player);
    }

    @Override
    public CompletionStage<ClientScene> scene(NoParams p, CallContext ctx) {
        return ApiHandlers.run(ClientService::scene);
    }

    @Override
    public CompletionStage<ClientContracts.BlockScan> blocks(BlocksParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> blocks(c, p));
    }

    /** A reply wait polls the chat log and holds no thread between reads; it ends at the caller's deadline at the latest. */
    @Override
    public CompletionStage<ClientReply> chatSend(ClientContracts.ChatSendParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.chatSend(p)).thenCompose(start -> {
            if (p.awaitReplyMs() <= 0 || !(start.reply() instanceof ClientContracts.ChatSent sent)) {
                return CompletableFuture.completedFuture(start.reply());
            }
            ClientDriverApi.ChatReplies replies = start.replies();
            return Polling.until(Math.min(p.awaitReplyMs(), ctx.remainingMillis()), CHAT_POLL_MS, ctx.requestCancelled(),
                            () -> CompletableFuture.completedFuture(replies.poll()), replies::timedOut)
                    .thenApply(later -> new ClientContracts.ChatSent(sent.kind(), sent.length(), Optional.of(later)));
        });
    }

    private static final long CHAT_POLL_MS = 50;

    @Override
    public CompletionStage<ClientContracts.ChatHistory> chatHistory(ClientContracts.ChatHistoryParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.chatHistory(p));
    }

    @Override
    public CompletionStage<ClientContracts.OverlaysCleared> overlays(ClientContracts.OverlaysParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.overlays(p));
    }

    @Override
    public CompletionStage<ClientContracts.Screenshot> screenshot(ClientContracts.ScreenshotParams p, CallContext ctx) {
        return waiting(ctx, "a screenshot", c -> c.screenshot(p, ctx));
    }

    private static ClientDriverApi client() {
        ClientDriverApi c = ClientHooks.impl();
        if (c == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "mc.client.* needs a game client, and this JVM has none");
        }
        return c;
    }

    /** Runs the task {@code body} makes of the client on the client thread, without holding the caller. */
    static <T> CompletionStage<T> onClient(CallContext ctx, java.util.function.Function<ClientDriverApi, Supplier<T>> body) {
        ClientDriverApi c = client();
        return c.hop().submit(body.apply(c), ctx);
    }

    /** Starts a call that hops onto the client thread by itself. */
    static <T> CompletionStage<T> withClient(java.util.function.Function<ClientDriverApi, CompletionStage<T>> body) {
        return body.apply(client());
    }

    /**
     * Starts a call that finishes after client ticks or a frame, holding one of the caller's wait permits
     * until its stage completes. No thread is held; the permit bounds how many such stages are pending.
     */
    static <T> CompletionStage<T> waiting(CallContext ctx, String what,
                                          java.util.function.Function<ClientDriverApi, CompletionStage<T>> body) {
        ClientDriverApi c = client();
        ctx.requireBlockingAllowed(what);
        WaitPermits.Lease lease = ctx.waits().tryAcquire();
        if (lease == null) throw ApiException.of(ApiError.Code.SERVER_BUSY, "server busy: every wait slot is in use; retry");
        try {
            return body.apply(c).whenComplete((r, t) -> lease.close());
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    private static ClientScene scene() {
        BotApi bot = BotHooks.impl();
        if (bot == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "mc.client.scene needs a game client, and this JVM has none");
        }
        return bot.scene();
    }

    private static ClientContracts.BlockScan blocks(ClientDriverApi c, BlocksParams p) {
        Set<String> ids = p.type().map(t -> (Set<String>) new LinkedHashSet<>(Set.of(t))).orElse(null);
        Double cx = p.center().map(b -> (double) b.x()).orElse(null);
        Double cy = p.center().map(b -> (double) b.y()).orElse(null);
        Double cz = p.center().map(b -> (double) b.z()).orElse(null);
        return c.scanBlocks(p.radius(), cx, cy, cz, ids);
    }
}
