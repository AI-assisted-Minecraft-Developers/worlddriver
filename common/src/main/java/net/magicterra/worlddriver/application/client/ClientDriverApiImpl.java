package net.magicterra.worlddriver.application.client;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlockScan;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatHistory;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatHistoryParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatSendParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientPlayer;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.OverlaysCleared;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.OverlaysParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Screenshot;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ScreenshotParams;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ClickParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.CloseParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Closed;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.HotbarParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeybindParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.MenuContainer;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.MoveParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Moved;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ReplaceParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenInfo;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenTree;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SliderParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotClickParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.TypeParams;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.magicterra.worlddriver.game.ClientHop;

/**
 * Default {@link ClientDriverApi} implementation. Lives in the common module because both Fabric and NeoForge share the
 * same MC client API surface; class loading is deferred until a platform client entrypoint instantiates it, so
 * dedicated server JVMs never resolve the {@code net.minecraft.client.*} symbols referenced here.
 *
 * <p>This class is a thin facade: each interface method delegates to a stateless helper in this package grouped by
 * concern (screen introspection, input, chat, screenshot, observation). The behavior — including the client-thread
 * marshalling — lives in those helpers, through {@link ClientHop}.
 */
public final class ClientDriverApiImpl implements ClientDriverApi {

    @Override
    public ServerThreadHop hop() {
        return ClientHop.hop();
    }

    @Override
    public ScreenInfo readScreenInfo() {
        return ScreenIntrospection.screenInfo();
    }

    @Override
    public ScreenTree readScreenTree() {
        return ScreenIntrospection.screenTree();
    }

    @Override
    public Closed closeScreen(CloseParams p) {
        return ClientInput.closeScreen(p);
    }

    @Override
    public ClientReply click(ClickParams p) {
        return ClientInput.click(p);
    }

    @Override
    public ClientReply slotClick(SlotClickParams p) {
        return ClientInput.slotClick(p);
    }

    @Override
    public Moved mouseMove(MoveParams p) {
        return ClientInput.mouseMove(p);
    }

    @Override
    public ClientReply setHotbarSlot(HotbarParams p) {
        return ClientInput.setHotbarSlot(p);
    }

    @Override
    public ClientReply typeText(TypeParams p) {
        return ClientInput.typeText(p);
    }

    @Override
    public ClientReply replaceText(ReplaceParams p) {
        return ClientInput.replaceText(p);
    }

    @Override
    public ClientReply slider(SliderParams p) {
        return ClientInput.slider(p);
    }

    @Override
    public CompletionStage<ClientReply> key(KeyParams p, CallContext ctx) {
        return ClientInput.key(p, ctx);
    }

    @Override
    public CompletionStage<ClientReply> keybind(KeybindParams p, CallContext ctx) {
        return ClientKeybinds.keybind(p, ctx);
    }

    @Override
    public MenuContainer menu() {
        return ClientObserve.menu();
    }

    @Override
    public ChatSendStart chatSend(ChatSendParams p) {
        return ClientChat.chatSend(p);
    }

    @Override
    public ChatHistory chatHistory(ChatHistoryParams p) {
        return ClientChat.chatHistory(p);
    }

    @Override
    public OverlaysCleared overlays(OverlaysParams p) {
        return ClientChat.overlays(p);
    }

    @Override
    public CompletionStage<Screenshot> screenshot(ScreenshotParams p, CallContext ctx) {
        return Screenshots.screenshot(p, ctx);
    }

    @Override
    public ClientPlayer player() {
        return ClientObserve.player();
    }

    @Override
    public BlockScan scanBlocks(int radius, Double cx, Double cy, Double cz, Set<String> filterIds) {
        return ClientObserve.scanBlocks(radius, cx, cy, cz, filterIds);
    }

    @Override
    public Optional<ObserveContracts.ClientView> clientView() {
        return ClientObserve.clientView();
    }

    @Override
    public java.util.List<net.magicterra.worlddriver.api.methods.query.QueryContracts.EntityRow> queryEntities(
            int radius, Double cx, Double cy, Double cz, Boolean hostileFilter) {
        return ClientObserve.queryEntities(radius, cx, cy, cz, hostileFilter);
    }

    @Override
    public net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Threats observeThreats(int radius) {
        return ClientObserve.observeThreats(radius);
    }

    @Override
    public net.magicterra.worlddriver.api.methods.observe.ObserveContracts.BossReading observeBoss(int radius) {
        return ClientBoss.observeBoss(radius);
    }
}
