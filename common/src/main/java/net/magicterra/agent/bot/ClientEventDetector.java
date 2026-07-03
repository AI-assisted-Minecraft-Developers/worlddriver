package net.magicterra.agent.bot;

import net.magicterra.agent.bot.combat.ClientThreatScanner;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.world.WorldModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Driver→agent client-tick push-event detection. Extracted verbatim from
 * BotApiImpl; {@link BotApiImpl#clientTick()} calls into an instance of this per
 * tick. Owns the cross-tick edge-detection state so a one-shot event fires on
 * each transition rather than every tick. {@code detectDeath} takes a cancel
 * callback so the death edge can still cancel the impl's active processes.
 */
final class ClientEventDetector {

    // WorldModel rising-edge push: emit duskExposed/cornered once per false→true
    // transition so the Agent learns of these without polling mc.client.scene.
    private boolean prevExposedAtNight = false;
    private boolean prevCornered = false;

    // Client-tick event detectors (driver→agent push channel). Track the local
    // player's health/death and the current top threat across ticks so we emit a
    // one-shot event on each transition rather than every tick.
    private float evtLastHealth = Float.NaN;
    private boolean evtDeathScreenSeen = false;
    private int evtLastThreatId = -1;
    // Day-phase transition (day/sunset/night/sunrise) — emit on change so the Agent
    // gets a 日落提醒 to bunker/return before dark, and a sunrise cue to resume.
    private String evtLastPhase = null;
    // Item-pickup detection: per-item inventory counts last tick; an increase = a
    // pickup (or craft/give) → emit the gained id + delta. evtInvInit gates the
    // first poll so the existing inventory isn't reported as a pickup.
    private final Map<String, Integer> evtInvCounts = new HashMap<>();
    private boolean evtInvInit = false;
    // Advancement-earned detection (client-side, best-effort): completed advancement
    // ids seen so far; new ones emit. evtAdvInit seeds the first poll silently.
    private final Set<String> evtDoneAdv = new HashSet<>();
    private boolean evtAdvInit = false;
    private int evtAdvThrottle = 0;
    // Fluid-entry detection: emit a one-shot on the rising edge of entering water /
    // lava (the 落水 / 落岩浆 warnings — the bot fell in and may be drowning/burning).
    private boolean evtInWater = false;
    private boolean evtInLava = false;
    // Client message surfaces (chat / action-bar / title). No server is attached in
    // client-MCP mode, so the server-side chat hook never fires; instead poll the
    // client's own display buffers each tick (best-effort reflection, like the
    // advancement poll) and emit each NEW line. Captures system messages, command
    // results and server broadcasts too — all land in the ChatComponent buffer.
    private String evtLastChat = null;
    private boolean evtChatInit = false;
    private String evtLastActionBar = null;
    private String evtLastTitle = null;
    private String evtLastSubtitle = null;

    /** Rising-edge scene events: emit duskExposed / cornered once per
     *  false→true transition so the Agent learns of these without polling.
     *  Mirrors the fluid-entry (player.enteredWater/enteredLava) pattern:
     *  api.emitExternal → the same event stream all client-tick events use. */
    void detectSceneEvents(WorldModel worldModel) {
        var wmSnap = worldModel.snapshot();
        net.magicterra.agent.api.AgentApi sceneApi = net.magicterra.agent.AgentDriverCommon.api();
        if (wmSnap.present() && sceneApi != null) {
            net.minecraft.core.BlockPos scenePos = wmSnap.pos();
            if (wmSnap.exposedAtNight() && !prevExposedAtNight) {
                sceneApi.emitExternal("duskExposed", scenePos,
                        net.magicterra.agent.rpc.JsonCodec.encode(Map.of(
                                "pos", Map.of("x", scenePos.getX(),
                                              "y", scenePos.getY(),
                                              "z", scenePos.getZ()))));
            }
            if (wmSnap.cornered() && !prevCornered) {
                sceneApi.emitExternal("cornered", scenePos,
                        net.magicterra.agent.rpc.JsonCodec.encode(Map.of(
                                "pos", Map.of("x", scenePos.getX(),
                                              "y", scenePos.getY(),
                                              "z", scenePos.getZ()))));
            }
            prevExposedAtNight = wmSnap.exposedAtNight();
            prevCornered = wmSnap.cornered();
        } else {
            prevExposedAtNight = false;
            prevCornered = false;
        }
    }

    /** Fire player.death off the DeathScreen rising edge, carrying the screen's
     *  SPECIFIC cause (combat-kill message). Also cancels active bot processes so
     *  an autoRespawn can't resume the lethal action into a death loop. Runs
     *  before autoRespawn so the screen — and its cause — is still readable. */
    void detectDeath(Minecraft mc, Runnable onDeathCancel) {
        boolean onDeath = mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen;
        if (onDeath && !evtDeathScreenSeen) {
            net.magicterra.agent.api.AgentApi api = net.magicterra.agent.AgentDriverCommon.api();
            String cause = net.magicterra.agent.client.internal.ScreenIntrospection
                    .readDeathCause((net.minecraft.client.gui.screens.DeathScreen) mc.screen);
            if (api != null) {
                net.minecraft.core.BlockPos at = mc.player != null
                        ? mc.player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("health", mc.player != null ? (double) mc.player.getHealth() : 0.0);
                if (cause != null && !cause.isEmpty()) data.put("cause", cause);
                api.emitExternal("player.death", at, net.magicterra.agent.rpc.JsonCodec.encode(data));
            }
            onDeathCancel.run();
        }
        evtDeathScreenSeen = onDeath;
    }

    /** Emit driver→agent push events for the local player's threat/hurt/death
     *  transitions. Called once per client tick after the threat scan refreshes.
     *  Everything funnels through {@code AgentApi.emitExternal} → the same event
     *  stream block/chat/death use → subscribers on both transports. */
    void detectClientEvents(Minecraft mc) {
        net.magicterra.agent.api.AgentApi api = net.magicterra.agent.AgentDriverCommon.api();
        if (api == null || mc.player == null) return;
        var pl = mc.player;
        net.minecraft.core.BlockPos at = pl.blockPosition();

        // Death emission + process-cancel live in detectDeath(), fired off the
        // DeathScreen rising edge (before autoRespawn dismisses it) so the event
        // carries the SPECIFIC cause. Here we only need `dead` to suppress hurt
        // events during the dying transition.
        boolean dead = pl.isDeadOrDying();

        float hp = pl.getHealth();
        if (!Float.isNaN(evtLastHealth) && hp < evtLastHealth - 0.01f && !dead) {
            api.emitExternal("player.hurt", at, net.magicterra.agent.rpc.JsonCodec.encode(Map.of(
                    "health", (double) hp, "prev", (double) evtLastHealth, "lost", (double) (evtLastHealth - hp))));
        }
        evtLastHealth = hp;

        ThreatScanner.Threat top = ClientThreatScanner.current(mc).top();
        int topId = (top != null) ? top.id() : -1;
        if (topId != -1 && topId != evtLastThreatId) {
            net.minecraft.core.BlockPos tp = top.entity().blockPosition();
            api.emitExternal("threat.appeared", tp, net.magicterra.agent.rpc.JsonCodec.encode(Map.of(
                    "type", top.type(), "id", topId,
                    "distance", top.distance(), "score", top.score())));
        }
        evtLastThreatId = topId;

        // --- Fluid entry (落水 / 落岩浆提醒) -------------------------------------
        // One-shot on the rising edge of stepping into water / lava — the "I fell
        // in" warning so the Agent can react (swim/escape ashore, or that it's
        // burning in lava) without polling observe.player every tick. isInWater()
        // and isInLava() are the vanilla body-in-fluid flags (true while any part
        // of the hitbox is in the fluid), matched against last tick so leaving and
        // re-entering re-fires.
        boolean inWater = pl.isInWater();
        if (inWater && !evtInWater) {
            api.emitExternal("player.enteredWater", at, net.magicterra.agent.rpc.JsonCodec.encode(Map.of(
                    "submerged", pl.isUnderWater())));
        }
        evtInWater = inWater;
        boolean inLava = pl.isInLava();
        if (inLava && !evtInLava) {
            api.emitExternal("player.enteredLava", at, net.magicterra.agent.rpc.JsonCodec.encode(Map.of(
                    "health", (double) pl.getHealth())));
        }
        evtInLava = inLava;

        // --- Day-phase transition (日落提醒 etc.) --------------------------------
        // Emit on each day/sunset/night/sunrise change. The sunset/night edges are
        // the "go bunker NOW" warning whose absence got the naked bot swarmed; the
        // sunrise edge is the cue to break out and resume. Buckets match
        // observe.player.time.phase so the Agent reads the same vocabulary.
        if (mc.level != null) {
            long tod = mc.level.getDayTime() % 24000L;
            if (tod < 0) tod += 24000L;
            String phase = tod < 12000 ? "day" : tod < 13000 ? "sunset" : tod < 23000 ? "night" : "sunrise";
            if (!phase.equals(evtLastPhase)) {
                if (evtLastPhase != null) {
                    api.emitExternal("time.phase", at, net.magicterra.agent.rpc.JsonCodec.encode(Map.of(
                            "phase", phase, "prev", evtLastPhase, "dayTime", tod)));
                }
                evtLastPhase = phase;
            }
        }

        // --- Item pickup (拾取物品提醒) -----------------------------------------
        // Diff per-item inventory counts vs last tick; any increase is a gain
        // (picked-up drop, craft result, or give). The Agent's "did my gather land?"
        // signal — e.g. after mine/COLLECT, an item.pickup{oak_log} confirms it.
        {
            Map<String, Integer> cur = new HashMap<>();
            var inv = pl.getInventory();
            for (int s = 0; s < inv.getContainerSize(); s++) {
                ItemStack st = inv.getItem(s);
                if (st.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                cur.merge(id, st.getCount(), Integer::sum);
            }
            if (evtInvInit) {
                for (Map.Entry<String, Integer> e : cur.entrySet()) {
                    int delta = e.getValue() - evtInvCounts.getOrDefault(e.getKey(), 0);
                    if (delta > 0) {
                        api.emitExternal("item.pickup", at, net.magicterra.agent.rpc.JsonCodec.encode(Map.of(
                                "id", e.getKey(), "count", delta, "total", e.getValue())));
                    }
                }
            }
            evtInvCounts.clear();
            evtInvCounts.putAll(cur);
            evtInvInit = true;
        }

        // --- Advancement earned (成就获取提醒) ----------------------------------
        // Milestone signals (Getting Wood / Stone Age / Acquire Hardware=iron /
        // We Need to Go Deeper=nether). The client has no clean hook, so poll the
        // ClientAdvancements progress map (throttled ~1s) and emit newly-completed
        // ids. Reflection is guarded — any mapping shift silently no-ops, never
        // breaking the tick. First poll seeds the "already done" set without emitting.
        if (++evtAdvThrottle >= 20 && pl.connection != null) {
            evtAdvThrottle = 0;
            try {
                Object ca = pl.connection.getAdvancements();
                for (java.lang.reflect.Field f : ca.getClass().getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Map<?, ?> prog = (Map<?, ?>) f.get(ca);
                    for (Map.Entry<?, ?> e : prog.entrySet()) {
                        Object p = e.getValue();   // AdvancementProgress
                        if (p == null) continue;
                        Object doneObj = p.getClass().getMethod("isDone").invoke(p);
                        if (!(doneObj instanceof Boolean b) || !b) continue;
                        Object holder = e.getKey();   // AdvancementHolder
                        Object id = holder.getClass().getMethod("id").invoke(holder);
                        String key = String.valueOf(id);
                        if (evtDoneAdv.add(key) && evtAdvInit) {
                            api.emitExternal("advancement", at,
                                    net.magicterra.agent.rpc.JsonCodec.encode(Map.of("id", key)));
                        }
                    }
                    break;   // first Map field is the progress map
                }
                evtAdvInit = true;
            } catch (Throwable ignored) { /* mapping/AT differences — never break ticks */ }
        }
    }

    /** Poll the client's chat buffer + action-bar / title HUD and push each NEW
     *  line as an event. In client-MCP mode no server is attached, so the
     *  server-side chat hook is silent; everything the client DISPLAYS (player
     *  chat, system messages, command results, server broadcasts) funnels into
     *  the {@code ChatComponent} buffer, and the action-bar / title arrive as
     *  client-bound packets the vanilla {@code Gui} stashes in private fields.
     *  All reflection is guarded — a mapping shift silently no-ops (mirrors the
     *  advancement poll); never breaks the tick. */
    void detectClientMessages(Minecraft mc) {
        net.magicterra.agent.api.AgentApi api = net.magicterra.agent.AgentDriverCommon.api();
        net.minecraft.client.gui.Gui gui = mc.gui;
        if (api == null || gui == null) return;
        net.minecraft.core.BlockPos at = mc.player != null
                ? mc.player.blockPosition() : net.minecraft.core.BlockPos.ZERO;

        // --- Chat / system / command-result lines -------------------------------
        // ChatComponent.allMessages is newest-first; emit every line above the last
        // one we saw, oldest-first so the stream stays chronological. First poll
        // seeds the marker silently so existing history isn't replayed.
        try {
            java.util.List<?> all = readListField(gui.getChat(), "allMessages");
            if (all != null) {
                String newest = !all.isEmpty() ? guiMessageText(all.get(0)) : null;
                if (!evtChatInit) {
                    evtLastChat = newest;
                    evtChatInit = true;
                } else if (newest != null && !newest.equals(evtLastChat)) {
                    java.util.List<String> fresh = new java.util.ArrayList<>();
                    for (Object m : all) {
                        String t = guiMessageText(m);
                        if (t == null || t.equals(evtLastChat)) break;
                        fresh.add(t);
                    }
                    for (int i = fresh.size() - 1; i >= 0; i--) {
                        api.emitExternal("client.message", at,
                                net.magicterra.agent.rpc.JsonCodec.encode(Map.of("text", fresh.get(i))));
                    }
                    evtLastChat = newest;
                }
            }
        } catch (Throwable ignored) { /* mapping shift — never break the tick */ }

        // --- Action bar (overlay message) ---------------------------------------
        try {
            String ab = componentFieldText(gui, "overlayMessageString");
            if (ab != null && !ab.isEmpty() && !ab.equals(evtLastActionBar)) {
                api.emitExternal("client.actionBar", at,
                        net.magicterra.agent.rpc.JsonCodec.encode(Map.of("text", ab)));
            }
            evtLastActionBar = ab;
        } catch (Throwable ignored) { }

        // --- Title / subtitle ---------------------------------------------------
        try {
            String title = componentFieldText(gui, "title");
            if (title != null && !title.isEmpty() && !title.equals(evtLastTitle)) {
                String sub = componentFieldText(gui, "subtitle");
                api.emitExternal("client.title", at, net.magicterra.agent.rpc.JsonCodec.encode(
                        sub != null && !sub.isEmpty()
                                ? Map.of("text", title, "subtitle", sub) : Map.of("text", title)));
            }
            evtLastTitle = title;
        } catch (Throwable ignored) { }
    }

    /** Read a named {@code List} field (walking up the hierarchy), or null. */
    private static java.util.List<?> readListField(Object obj, String name) {
        java.lang.reflect.Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try { f.setAccessible(true); Object v = f.get(obj);
            return (v instanceof java.util.List<?> l) ? l : null;
        } catch (Throwable t) { return null; }
    }

    /** {@code GuiMessage.content().getString()} via reflection, or null. */
    private static String guiMessageText(Object guiMessage) {
        try {
            Object content = guiMessage.getClass().getMethod("content").invoke(guiMessage);
            return content == null ? null
                    : String.valueOf(content.getClass().getMethod("getString").invoke(content));
        } catch (Throwable t) { return null; }
    }

    /** Read a named {@code Component} field's {@code getString()}, or null. */
    private static String componentFieldText(Object obj, String name) {
        java.lang.reflect.Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try { f.setAccessible(true); Object c = f.get(obj);
            return c == null ? null
                    : String.valueOf(c.getClass().getMethod("getString").invoke(c));
        } catch (Throwable t) { return null; }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
