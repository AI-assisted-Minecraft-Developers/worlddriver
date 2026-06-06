package net.magicterra.agent.client;

import java.util.Map;

import net.magicterra.agent.client.internal.ClientBoss;
import net.magicterra.agent.client.internal.ClientChat;
import net.magicterra.agent.client.internal.ClientInput;
import net.magicterra.agent.client.internal.ClientObserve;
import net.magicterra.agent.client.internal.ScreenIntrospection;
import net.magicterra.agent.client.internal.Screenshots;
import java.util.Set;

/**
 * Default {@link ClientAgentApi} implementation. Lives in the common module
 * because both Fabric and NeoForge share the same MC client API surface; class
 * loading is deferred until a platform client entrypoint instantiates it, so
 * dedicated server JVMs never resolve the {@code net.minecraft.client.*}
 * symbols referenced here.
 *
 * This class is a thin facade: each interface method delegates to a stateless
 * helper under {@code net.magicterra.agent.client.internal} grouped by concern
 * (screen introspection, input, chat, observation, screenshot). The behavior —
 * including the client-thread marshalling — lives in those helpers; see
 * {@link net.magicterra.agent.client.internal.ClientThread} for the shared
 * {@code runOnClient} jumper.
 */
public final class ClientAgentApiImpl implements ClientAgentApi {

    @Override
    public Map<String, Object> screenInfo() { return ScreenIntrospection.screenInfo(); }

    @Override
    public Map<String, Object> screenTree() { return ScreenIntrospection.screenTree(); }

    @Override
    public Map<String, Object> closeScreen() { return ClientInput.closeScreen(); }

    @Override
    public Map<String, Object> click(double x, double y, int button) { return ClientInput.click(x, y, button); }

    @Override
    public Map<String, Object> slotClick(int slotIndex, int button, String type) {
        return ClientInput.slotClick(slotIndex, button, type);
    }

    @Override
    public Map<String, Object> mouseMove(double x, double y) { return ClientInput.mouseMove(x, y); }

    @Override
    public Map<String, Object> setHotbarSlot(int slot) { return ClientInput.setHotbarSlot(slot); }

    @Override
    public Map<String, Object> typeText(String text) { return ClientInput.typeText(text); }

    @Override
    public Map<String, Object> replaceText(String text, String match) { return ClientInput.replaceText(text, match); }

    @Override
    public Map<String, Object> setSlider(String match, Integer index, Double fraction) { return ClientInput.setSlider(match, index, fraction); }

    @Override
    public Map<String, Object> key(String key, String action) { return ClientInput.key(key, action); }

    @Override
    public Map<String, Object> chatSend(String text, int awaitReplyMs) { return ClientChat.chatSend(text, awaitReplyMs); }

    @Override
    public Map<String, Object> chatHistory(int limit, int sinceSeq) { return ClientChat.chatHistory(limit, sinceSeq); }

    @Override
    public Map<String, Object> overlays(boolean tutorial, boolean toasts) { return ClientChat.overlays(tutorial, toasts); }

    @Override
    public Map<String, Object> observePlayer() { return ClientObserve.observePlayer(); }

    @Override
    public Map<String, Object> observeArea(int radius, Double cx, Double cy, Double cz,
                                           Set<String> filterIds) {
        return ClientObserve.observeArea(radius, cx, cy, cz, filterIds);
    }

    @Override
    public Map<String, Object> queryEntities(int radius, Double cx, Double cy, Double cz, Boolean hostileFilter) {
        return ClientObserve.queryEntities(radius, cx, cy, cz, hostileFilter);
    }

    @Override
    public Map<String, Object> observeThreats(int radius) { return ClientObserve.observeThreats(radius); }

    @Override
    public Map<String, Object> observeBoss(int radius) { return ClientBoss.observeBoss(radius); }

    @Override
    public Map<String, Object> observeContainerMenu() { return ClientObserve.observeContainerMenu(); }

    @Override
    public Map<String, Object> screenshot(Map<String, Object> opts) { return Screenshots.screenshot(opts); }
}
