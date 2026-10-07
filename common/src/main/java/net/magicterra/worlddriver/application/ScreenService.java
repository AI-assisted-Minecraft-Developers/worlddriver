package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.screen.ScreenApi;
import net.magicterra.worlddriver.api.methods.screen.ScreenApiBinding;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.BlockContainer;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ContainerView;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyAction;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotRow;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.bot.util.ItemSnap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import static net.magicterra.worlddriver.application.ClientService.onClient;
import static net.magicterra.worlddriver.application.ClientService.waiting;

/**
 * {@code mc.client.screen.*}, {@code mc.client.input.*} and {@code mc.observe.container}. Each hops onto the
 * client thread the way {@link ClientService} does and shares its wait pool; a block's container is read on the
 * server thread instead.
 */
final class ScreenService implements ScreenApi {
    private final DriverApplication app;

    ScreenService(DriverApplication app) { this.app = app; }

    List<ApiMethod<?, ?>> methods() {
        return ScreenApiBinding.methods(this);
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
    public CompletionStage<ContainerView> container(ScreenContracts.ContainerParams p, CallContext ctx) {
        if (p.pos().isEmpty()) return ClientService.<ContainerView>onClient(ctx, c -> c::menu);
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
