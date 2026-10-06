package net.magicterra.worlddriver.client;

import java.util.Map;
import java.util.Set;

import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.methods.ClientContracts.BlockScan;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChatHistory;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChatHistoryParams;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChatSendParams;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientPlayer;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.ClientContracts.OverlaysCleared;
import net.magicterra.worlddriver.api.methods.ClientContracts.OverlaysParams;
import net.magicterra.worlddriver.api.methods.ClientContracts.Screenshot;
import net.magicterra.worlddriver.api.methods.ClientContracts.ScreenshotParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.ClickParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.CloseParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.Closed;
import net.magicterra.worlddriver.api.methods.ScreenContracts.HotbarParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.KeyParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.KeybindParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.MenuContainer;
import net.magicterra.worlddriver.api.methods.ScreenContracts.MoveParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.Moved;
import net.magicterra.worlddriver.api.methods.ScreenContracts.ReplaceParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.ScreenInfo;
import net.magicterra.worlddriver.api.methods.ScreenContracts.ScreenTree;
import net.magicterra.worlddriver.api.methods.ScreenContracts.SliderParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.SlotClickParams;
import net.magicterra.worlddriver.api.methods.ScreenContracts.TypeParams;
import net.magicterra.worlddriver.bot.util.ClientHop;
import net.magicterra.worlddriver.client.internal.ClientBoss;
import net.magicterra.worlddriver.client.internal.ClientChat;
import net.magicterra.worlddriver.client.internal.ClientInput;
import net.magicterra.worlddriver.client.internal.ClientKeybinds;
import net.magicterra.worlddriver.client.internal.ClientObserve;
import net.magicterra.worlddriver.client.internal.ScreenIntrospection;
import net.magicterra.worlddriver.client.internal.Screenshots;

/**
 * Default {@link ClientDriverApi} implementation. Lives in the common module
 * because both Fabric and NeoForge share the same MC client API surface; class
 * loading is deferred until a platform client entrypoint instantiates it, so
 * dedicated server JVMs never resolve the {@code net.minecraft.client.*}
 * symbols referenced here.
 *
 * This class is a thin facade: each interface method delegates to a stateless
 * helper under {@code net.magicterra.worlddriver.client.internal} grouped by concern
 * (screen introspection, input, chat, observation, screenshot). The behavior —
 * including the client-thread marshalling — lives in those helpers; see
 * {@link net.magicterra.worlddriver.client.internal.ClientThread} for the shared
 * {@code runOnClient} jumper.
 */
public final class ClientDriverApiImpl implements ClientDriverApi {

    @Override public ServerThreadHop hop() { return ClientHop.hop(); }

    @Override public ScreenInfo readScreenInfo() { return ScreenIntrospection.screenInfo(); }

    @Override public ScreenTree readScreenTree() { return ScreenIntrospection.screenTree(); }

    @Override public Closed closeScreen(CloseParams p) { return ClientInput.closeScreen(p); }

    @Override public ClientReply click(ClickParams p) { return ClientInput.click(p); }

    @Override public ClientReply slotClick(SlotClickParams p) { return ClientInput.slotClick(p); }

    @Override public Moved mouseMove(MoveParams p) { return ClientInput.mouseMove(p); }

    @Override public ClientReply setHotbarSlot(HotbarParams p) { return ClientInput.setHotbarSlot(p); }

    @Override public ClientReply typeText(TypeParams p) { return ClientInput.typeText(p); }

    @Override public ClientReply replaceText(ReplaceParams p) { return ClientInput.replaceText(p); }

    @Override public ClientReply slider(SliderParams p) { return ClientInput.slider(p); }

    @Override public ClientReply key(KeyParams p) { return ClientInput.key(p); }

    @Override public ClientReply keybind(KeybindParams p) { return ClientKeybinds.keybind(p); }

    @Override public MenuContainer menu() { return ClientObserve.menu(); }

    @Override public ClientReply chatSend(ChatSendParams p) { return ClientChat.chatSend(p); }

    @Override public ChatHistory chatHistory(ChatHistoryParams p) { return ClientChat.chatHistory(p); }

    @Override public OverlaysCleared overlays(OverlaysParams p) { return ClientChat.overlays(p); }

    @Override public Screenshot screenshot(ScreenshotParams p) { return Screenshots.screenshot(p); }

    @Override public ClientPlayer player() { return ClientObserve.player(); }

    @Override
    public BlockScan scanBlocks(int radius, Double cx, Double cy, Double cz, Set<String> filterIds) {
        return ClientObserve.scanBlocks(radius, cx, cy, cz, filterIds);
    }

    @Override public Map<String, Object> observePlayer() { return ClientObserve.observePlayer(); }

    @Override
    public Map<String, Object> queryEntities(int radius, Double cx, Double cy, Double cz, Boolean hostileFilter) {
        return ClientObserve.queryEntities(radius, cx, cy, cz, hostileFilter);
    }

    @Override
    public net.magicterra.worlddriver.api.methods.ObserveContracts.Threats observeThreats(int radius) {
        return ClientObserve.observeThreats(radius);
    }

    @Override
    public net.magicterra.worlddriver.api.methods.ObserveContracts.BossReading observeBoss(int radius) {
        return ClientBoss.observeBoss(radius);
    }
}
