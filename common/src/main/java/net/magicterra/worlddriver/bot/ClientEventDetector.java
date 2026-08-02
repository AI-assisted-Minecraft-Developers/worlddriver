package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.bot.combat.ClientThreatScanner;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.magicterra.worlddriver.client.internal.ClientChatLog;
import net.magicterra.worlddriver.rpc.JsonCodec;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
    // Tool-broke detection: the mainhand tool seen last tick (id + damage). A tool
    // that was within 2 uses of max damage and whose inventory COUNT drops this
    // tick has broken — the count check keeps hotbar swaps / slot moves silent.
    private String evtMainhandId = null;
    private int evtMainhandDamage, evtMainhandMax;
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
    // client-MCP mode, so the server-side chat hook never fires; chat lines drain
    // from ClientChatLog (fed at the packet layer by the platform entrypoints),
    // action-bar / title still poll the Gui's display fields via reflection.
    // Captures system messages, command results and server broadcasts too.
    private long evtChatSeq = -1;
    private String evtLastActionBar = null;
    private String evtLastTitle = null;
    private String evtLastSubtitle = null;

    /** Rising-edge scene events: emit duskExposed / cornered once per
     *  false→true transition so the Agent learns of these without polling.
     *  Mirrors the fluid-entry (player.enteredWater/enteredLava) pattern:
     *  api.emitExternal → the same event stream all client-tick events use. */
    void detectSceneEvents(WorldModel worldModel) {
        var wmSnap = worldModel.snapshot();
        net.magicterra.worlddriver.api.DriverApi sceneApi = net.magicterra.worlddriver.WorldDriverCommon.api();
        if (wmSnap.present() && sceneApi != null) {
            net.minecraft.core.BlockPos scenePos = wmSnap.pos();
            if (wmSnap.exposedAtNight() && !prevExposedAtNight) {
                sceneApi.emitExternal("duskExposed", scenePos, Map.of(
                                "pos", Map.of("x", scenePos.getX(),
                                              "y", scenePos.getY(),
                                              "z", scenePos.getZ())));
            }
            if (wmSnap.cornered() && !prevCornered) {
                sceneApi.emitExternal("cornered", scenePos, Map.of(
                                "pos", Map.of("x", scenePos.getX(),
                                              "y", scenePos.getY(),
                                              "z", scenePos.getZ())));
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
            net.magicterra.worlddriver.api.DriverApi api = net.magicterra.worlddriver.WorldDriverCommon.api();
            String cause = net.magicterra.worlddriver.client.internal.ScreenIntrospection
                    .readDeathCause((net.minecraft.client.gui.screens.DeathScreen) mc.screen);
            if (api != null) {
                net.minecraft.core.BlockPos at = mc.player != null
                        ? mc.player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("health", mc.player != null ? (double) mc.player.getHealth() : 0.0);
                if (cause != null && !cause.isEmpty()) data.put("cause", cause);
                api.emitExternal("player.death", at, data);
            }
            onDeathCancel.run();
        }
        evtDeathScreenSeen = onDeath;
    }

    /** Emit driver→agent push events for the local player's threat/hurt/death
     *  transitions. Called once per client tick after the threat scan refreshes.
     *  Everything funnels through {@code DriverApi.emitExternal} → the same event
     *  stream block/chat/death use → subscribers on both transports. */
    void detectClientEvents(Minecraft mc) {
        net.magicterra.worlddriver.api.DriverApi api = net.magicterra.worlddriver.WorldDriverCommon.api();
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
            java.util.Map<String, Object> hurtData = new java.util.HashMap<>();
            hurtData.put("health", (double) hp);
            hurtData.put("prev", (double) evtLastHealth);
            hurtData.put("lost", (double) (evtLastHealth - hp));
            // Damage attribution (gap #55): without it a fall in a self-dug pit and a
            // mob bite are indistinguishable HP deltas, and both the combat chain and
            // the agent engage phantoms. The client mirrors the server's DamageSource
            // via handleDamageEvent (ClientboundDamageEventPacket, 40-tick window).
            net.minecraft.world.damagesource.DamageSource src = pl.getLastDamageSource();
            if (src != null) {
                hurtData.put("source", src.getMsgId());
                net.minecraft.world.entity.Entity att = src.getEntity();
                if (att != null && att != pl) {
                    hurtData.put("attackerId", att.getId());
                    hurtData.put("attackerType", net.minecraft.core.registries.BuiltInRegistries
                            .ENTITY_TYPE.getKey(att.getType()).toString());
                    hurtData.put("attackerDistance", att.distanceTo(pl));
                }
            }
            api.emitExternal("player.hurt", at, hurtData);
        }
        evtLastHealth = hp;

        ThreatScanner.Threat top = ClientThreatScanner.current(mc).top();
        int topId = (top != null) ? top.id() : -1;
        if (topId != -1 && topId != evtLastThreatId) {
            net.minecraft.core.BlockPos tp = top.entity().blockPosition();
            api.emitExternal("threat.appeared", tp, Map.of(
                    "type", top.type(), "id", topId,
                    "distance", top.distance(), "score", top.score()));
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
            api.emitExternal("player.enteredWater", at, Map.of(
                    "submerged", pl.isUnderWater()));
        }
        evtInWater = inWater;
        boolean inLava = pl.isInLava();
        if (inLava && !evtInLava) {
            api.emitExternal("player.enteredLava", at, Map.of(
                    "health", (double) pl.getHealth()));
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
                    api.emitExternal("time.phase", at, Map.of(
                            "phase", phase, "prev", evtLastPhase, "dayTime", tod));
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
                        api.emitExternal("item.pickup", at, Map.of(
                                "id", e.getKey(), "count", delta, "total", e.getValue()));
                    }
                }
            }
            // --- Tool broke (工具耐久打光提醒) --------------------------------
            // The mainhand tool durability runs out SILENTLY: the item vanishes
            // and mine/goto grind on bare-handed at up to 12× the planned break
            // cost, with the Agent none the wiser until a "slow dig" mystery.
            // Best-effort edge: last tick's mainhand was a damageable item within
            // 2 uses of max damage, and that item id's total count dropped this
            // tick (a swap/slot-move keeps the count; a second identical tool
            // still decrements on a true break). A Q-drop of a near-dead tool
            // can false-positive — acceptable, the advice ("re-craft before the
            // next dig commit") is identical.
            if (evtInvInit && evtMainhandId != null
                    && evtMainhandDamage >= evtMainhandMax - 2
                    && cur.getOrDefault(evtMainhandId, 0) < evtInvCounts.getOrDefault(evtMainhandId, 0)) {
                api.emitExternal("tool.broke", at, Map.of(
                        "id", evtMainhandId,
                        "damage", evtMainhandDamage, "maxDamage", evtMainhandMax));
            }
            ItemStack mh = pl.getMainHandItem();
            if (!mh.isEmpty() && mh.isDamageableItem()) {
                evtMainhandId = BuiltInRegistries.ITEM.getKey(mh.getItem()).toString();
                evtMainhandDamage = mh.getDamageValue();
                evtMainhandMax = mh.getMaxDamage();
            } else {
                evtMainhandId = null;
            }
            evtInvCounts.clear();
            evtInvCounts.putAll(cur);
            evtInvInit = true;
        }

        // --- Advancement earned (成就获取提醒) ----------------------------------
        // Milestone signals (Getting Wood / Stone Age / Acquire Hardware=iron /
        // We Need to Go Deeper=nether). The client has no clean hook, so poll the
        // ClientAdvancements progress map (throttled ~1s) and emit newly-completed
        // ids. First poll seeds the "already done" set without emitting.
        //
        // The progress map itself is still found reflectively, but BY TYPE (the first
        // Map-typed field on ClientAdvancements) — no member-name string, so it is
        // unaffected by remapping. The two calls on its entries used to go through
        // getMethod("isDone")/getMethod("id"), which WERE name-based and therefore
        // broke in the remapped fabric jar; AdvancementProgress and AdvancementHolder
        // are public API, so a typed call is both correct after remap and checked by
        // the compiler.
        if (++evtAdvThrottle >= 20 && pl.connection != null) {
            evtAdvThrottle = 0;
            try {
                Object ca = pl.connection.getAdvancements();
                for (java.lang.reflect.Field f : ca.getClass().getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Map<?, ?> prog = (Map<?, ?>) f.get(ca);
                    for (Map.Entry<?, ?> e : prog.entrySet()) {
                        if (!(e.getValue() instanceof AdvancementProgress ap) || !ap.isDone()) continue;
                        if (!(e.getKey() instanceof AdvancementHolder holder)) continue;
                        String key = holder.id().toString();
                        if (evtDoneAdv.add(key) && evtAdvInit) {
                            api.emitExternal("advancement", at, Map.of("id", key));
                        }
                    }
                    break;   // first Map field is the progress map
                }
                evtAdvInit = true;
            } catch (Throwable ignored) { /* mapping/AT differences — never break ticks */ }
        }
    }

    /** Push each NEW chat line + action-bar / title change as an event. In
     *  client-MCP mode no server is attached, so the server-side chat hook is
     *  silent; chat / system / command-result lines are drained from the
     *  packet-level {@link ClientChatLog} (fed by the platform receive events),
     *  while the action-bar / title arrive as client-bound packets the vanilla
     *  {@code Gui} stashes in private fields, read via guarded reflection (a
     *  mapping shift silently no-ops, mirroring the advancement poll). The
     *  whole poll never breaks the tick. */
    void detectClientMessages(Minecraft mc) {
        net.magicterra.worlddriver.api.DriverApi api = net.magicterra.worlddriver.WorldDriverCommon.api();
        net.minecraft.client.gui.Gui gui = mc.gui;
        if (api == null || gui == null) return;
        net.minecraft.core.BlockPos at = mc.player != null
                ? mc.player.blockPosition() : net.minecraft.core.BlockPos.ZERO;

        // --- Chat / system / command-result lines -------------------------------
        // Drain ClientChatLog above our cursor, oldest-first so the stream stays
        // chronological. First poll seeds the cursor silently so history present
        // before the detector started isn't replayed. Guarded like every other
        // poll in this class — an encode/emit throw must not break the tick, so
        // the cursor advances BEFORE the emit (drop one line, never wedge).
        try {
            if (evtChatSeq < 0) {
                evtChatSeq = ClientChatLog.nextSeq();
            } else if (ClientChatLog.nextSeq() > evtChatSeq) {   // O(1) common case
                for (var e : ClientChatLog.since(evtChatSeq)) {
                    evtChatSeq = e.seq() + 1;
                    // Entry.row() = the same {seq,kind,text,self} shape chat.history
                    // returns, so consumers can reconcile the two surfaces by seq
                    // and skip the bot's own echoed lines via self.
                    api.emitExternal("client.message", at, e.row());
                }
            }
        } catch (Throwable ignored) { /* never break the tick */ }

        // --- Action bar (overlay message) ---------------------------------------
        try {
            String ab = componentFieldText(gui, "overlayMessageString");
            if (ab != null && !ab.isEmpty() && !ab.equals(evtLastActionBar)) {
                api.emitExternal("client.actionBar", at, Map.of("text", ab));
            }
            evtLastActionBar = ab;
        } catch (Throwable ignored) { }

        // --- Title / subtitle ---------------------------------------------------
        try {
            String title = componentFieldText(gui, "title");
            if (title != null && !title.isEmpty() && !title.equals(evtLastTitle)) {
                String sub = componentFieldText(gui, "subtitle");
                api.emitExternal("client.title", at,
                        sub != null && !sub.isEmpty()
                                ? Map.of("text", title, "subtitle", sub) : Map.of("text", title));
            }
            evtLastTitle = title;
        } catch (Throwable ignored) { }
    }

    /** Read a named {@code Component} field's {@code getString()}, or null.
     *
     *  <p>The FIELD is still located by name, so this method stays remap-unsafe until
     *  {@code Gui.title/subtitle/overlayMessageString} are opened up (tracked in
     *  {@code scripts/check_remap_safety.py}). The {@code getString()} call no longer
     *  is: {@code Component} is public API, so a pattern-match reads the text through
     *  a normal virtual call that tiny-remapper rewrites like any other. */
    private static String componentFieldText(Object obj, String name) {
        java.lang.reflect.Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(obj) instanceof Component c ? c.getString() : null;
        } catch (Throwable t) { return null; }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
