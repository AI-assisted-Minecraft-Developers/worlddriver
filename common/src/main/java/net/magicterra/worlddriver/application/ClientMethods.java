package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.ApiHandlers;
import net.magicterra.worlddriver.api.Positions;
import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.WaitPermits;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.methods.ClientCalls;
import net.magicterra.worlddriver.api.methods.ClientCallsBinding;
import net.magicterra.worlddriver.api.methods.ClientContracts;
import net.magicterra.worlddriver.api.methods.ClientContracts.BlocksParams;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientScene;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.ScreenCalls;
import net.magicterra.worlddriver.api.methods.ScreenCallsBinding;
import net.magicterra.worlddriver.api.methods.ScreenContracts;
import net.magicterra.worlddriver.api.methods.ScreenContracts.BlockContainer;
import net.magicterra.worlddriver.api.methods.ScreenContracts.ContainerView;
import net.magicterra.worlddriver.api.methods.ScreenContracts.KeyAction;
import net.magicterra.worlddriver.api.methods.ScreenContracts.SlotRow;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.util.ItemSnap;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.magicterra.worlddriver.client.ClientDriverApi;
import net.magicterra.worlddriver.client.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * {@code mc.client.*} and {@code mc.observe.container}. Each hops onto the client thread and runs there;
 * a JVM with no client answers capability_unavailable. The calls that wait for client ticks, a frame or
 * later chat hold a wait permit and a thread of {@link #WAITS} instead of their caller, and are refused
 * on a game thread.
 */
final class ClientMethods implements ScreenCalls, ClientCalls {
    /** Where the waiting calls sit out their wait; their number is bounded by the wait permits they hold. */
    private static final ExecutorService WAITS = Executors.newCachedThreadPool(
            Thread.ofPlatform().name("worlddriver-client-wait").daemon().factory());

    private final DriverApplication app;

    ClientMethods(DriverApplication app) { this.app = app; }

    List<ApiMethod<?, ?>> methods() {
        List<ApiMethod<?, ?>> all = new ArrayList<>(ScreenCallsBinding.methods(this));
        all.addAll(ClientCallsBinding.methods(this));
        return all;
    }

    @Override
    public CompletionStage<ScreenContracts.ScreenInfo> screenInfo(NoParams p, CallContext ctx) {
        return onClient(ctx, c -> c::readScreenInfo);
    }

    @Override
    public CompletionStage<ScreenContracts.ScreenTree> screenTree(NoParams p, CallContext ctx) {
        return onClient(ctx, c -> c::readScreenTree);
    }

    @Override
    public CompletionStage<ScreenContracts.Closed> closeScreen(ScreenContracts.CloseParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.closeScreen(p));
    }

    @Override
    public CompletionStage<ClientReply> click(ScreenContracts.ClickParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.click(p));
    }

    @Override
    public CompletionStage<ScreenContracts.Moved> mouseMove(ScreenContracts.MoveParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.mouseMove(p));
    }

    @Override
    public CompletionStage<ClientReply> slotClick(ScreenContracts.SlotClickParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.slotClick(p));
    }

    @Override
    public CompletionStage<ClientReply> setHotbarSlot(ScreenContracts.HotbarParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.setHotbarSlot(p));
    }

    @Override
    public CompletionStage<ClientReply> typeText(ScreenContracts.TypeParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.typeText(p));
    }

    @Override
    public CompletionStage<ClientReply> replaceText(ScreenContracts.ReplaceParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.replaceText(p));
    }

    @Override
    public CompletionStage<ClientReply> slider(ScreenContracts.SliderParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> c.slider(p));
    }

    // A click's release waits for the next client tick; a press or a release alone does not wait.
    @Override
    public CompletionStage<ClientReply> key(ScreenContracts.KeyParams p, CallContext ctx) {
        return p.action() == KeyAction.CLICK
                ? waiting(ctx, "a key click", c -> () -> c.key(p)) : onClient(ctx, c -> () -> c.key(p));
    }

    @Override
    public CompletionStage<ClientReply> keybind(ScreenContracts.KeybindParams p, CallContext ctx) {
        return p.action() == KeyAction.CLICK && p.name().isPresent()
                ? waiting(ctx, "a keybind click", c -> () -> c.keybind(p)) : onClient(ctx, c -> () -> c.keybind(p));
    }

    @Override
    public CompletionStage<ClientContracts.ClientPlayer> player(NoParams p, CallContext ctx) {
        return onClient(ctx, c -> c::player);
    }

    @Override
    public CompletionStage<ClientScene> scene(NoParams p, CallContext ctx) {
        return ApiHandlers.run(ClientMethods::scene);
    }

    @Override
    public CompletionStage<ClientContracts.BlockScan> blocks(BlocksParams p, CallContext ctx) {
        return onClient(ctx, c -> () -> blocks(c, p));
    }

    @Override
    public CompletionStage<ClientReply> chatSend(ClientContracts.ChatSendParams p, CallContext ctx) {
        return p.awaitReplyMs() > 0
                ? waiting(ctx, "awaitReplyMs", c -> () -> c.chatSend(p)) : onClient(ctx, c -> () -> c.chatSend(p));
    }

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
        return waiting(ctx, "a screenshot", c -> () -> c.screenshot(p));
    }

    private static ClientDriverApi client() {
        ClientDriverApi c = ClientHooks.impl();
        if (c == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "mc.client.* needs a game client, and this JVM has none");
        }
        return c;
    }

    /** Runs the task {@code body} makes of the client on the client thread, without holding the caller. */
    private static <T> CompletionStage<T> onClient(CallContext ctx, java.util.function.Function<ClientDriverApi, Supplier<T>> body) {
        ClientDriverApi c = client();
        return c.hop().submit(body.apply(c), ctx);
    }

    /** Runs a task that waits on a thread of its own, holding one of the caller's wait permits meanwhile. */
    private static <T> CompletionStage<T> waiting(CallContext ctx, String what,
                                                  java.util.function.Function<ClientDriverApi, Supplier<T>> body) {
        ClientDriverApi c = client();
        ctx.requireBlockingAllowed(what);
        WaitPermits.Lease lease = ctx.waits().tryAcquire();
        if (lease == null) throw ApiException.of(ApiError.Code.SERVER_BUSY, "server busy: every wait slot is in use; retry");
        Supplier<T> task = body.apply(c);
        try {
            return CompletableFuture.supplyAsync(task, WAITS).whenComplete((r, t) -> lease.close());
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
        WorldModel.Snapshot s = bot.worldModel().snapshot();
        if (!s.present()) {
            return new ClientScene(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        BlockPos p = s.pos();
        return new ClientScene(true, Optional.of(Positions.position(p)),
                Optional.of((double) s.health()), Optional.of(s.food()), Optional.of(s.dayPhase()),
                Optional.of(s.skyExposed()), Optional.of(s.exposedAtNight()), Optional.of(s.cornered()),
                Optional.of(s.lethalCount()), Optional.of(List.copyOf(s.rows())));
    }

    private static ClientContracts.BlockScan blocks(ClientDriverApi c, BlocksParams p) {
        Set<String> ids = p.type().map(t -> (Set<String>) new LinkedHashSet<>(Set.of(t))).orElse(null);
        Double cx = p.center().map(b -> (double) b.x()).orElse(null);
        Double cy = p.center().map(b -> (double) b.y()).orElse(null);
        Double cz = p.center().map(b -> (double) b.z()).orElse(null);
        return c.scanBlocks(p.radius(), cx, cy, cz, ids);
    }

    @Override
    public CompletionStage<ContainerView> container(ScreenContracts.ContainerParams p, CallContext ctx) {
        if (p.pos().isEmpty()) return ClientMethods.<ContainerView>onClient(ctx, c -> c::menu);
        BlockPosition at = p.pos().get();
        ServerThreadHop hop = app.serverHop();
        if (hop == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "reading a block's container needs a server, and none is attached");
        }
        ServerLevel level = app.level();
        return hop.submit(() -> blockContainer(level, at), ctx);
    }

    private static ContainerView blockContainer(ServerLevel level, BlockPosition at) {
        BlockEntity be = level.getBlockEntity(Positions.blockPos(at));
        if (be == null) return new BlockContainer(at, false, Optional.empty(), Optional.empty());
        Optional<List<SlotRow>> slots = Optional.empty();
        if (be instanceof Container c) {
            List<SlotRow> rows = new ArrayList<>(c.getContainerSize());
            for (int i = 0; i < c.getContainerSize(); i++) rows.add(ItemSnap.slotRow(i, c.getItem(i)));
            slots = Optional.of(rows);
        }
        return new BlockContainer(at, true, Optional.of(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString()), slots);
    }
}
