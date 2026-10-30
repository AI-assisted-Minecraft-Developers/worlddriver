package net.magicterra.worlddriver.application.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.bot.BotBrain;
import net.magicterra.worlddriver.game.observe.TimeSnap;
import net.magicterra.worlddriver.game.ui.ClientChatLog;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Driver→agent push events only this client can see: the local player's body, the death screen, advancements and the
 * chat / action-bar / title surfaces. The client bot's tick ({@link ClientBot}) calls into an instance of this per
 * tick, and every event goes out on its brain's stream. Owns the cross-tick edge-detection state so a one-shot event
 * fires on each transition rather than every tick. {@code detectDeath} takes a cancel callback so the death edge can
 * still cancel the bot's active processes. The edges of what the bot itself perceives (threats, the world model) are
 * the brain's own, and fire for any bot.
 */
final class ClientEventDetector {

    private final BotBrain brain;

    // Client-tick event detectors (driver→agent push channel). Track the local
    // player's health/death across ticks so we emit a one-shot event on each
    // transition rather than every tick.
    private float evtLastHealth = Float.NaN;
    private boolean evtDeathScreenSeen = false;
    // Day-phase transition (day/sunset/night/sunrise) — emit on change so the Agent
    // gets a sunset alert to bunker/return before dark, and a sunrise cue to resume.
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
    private int evtMainhandDamage;
    private int evtMainhandMax;
    // Advancement-earned detection (client-side, best-effort): completed advancement
    // ids seen so far; new ones emit. evtAdvInit seeds the first poll silently.
    private final Set<String> evtDoneAdv = new HashSet<>();
    private boolean evtAdvInit = false;
    private int evtAdvThrottle = 0;
    // Fluid-entry detection: emit a one-shot on the rising edge of entering water /
    // lava (the fell-into-water / fell-into-lava warnings — the bot fell in and may be drowning/burning).
    private boolean evtInWater = false;
    private boolean evtInLava = false;
    // Client message surfaces (chat / action-bar / title). No server is attached in
    // client-MCP mode, so the server-side chat hook never fires; chat lines drain
    // from ClientChatLog (fed at the packet layer by the platform entrypoints),
    // action-bar / title still poll the Gui's display fields via reflection.
    // Captures system messages, command results and server broadcasts too.
    private long evtChatSeq = -1;
    private String evtLastActionBar = null;
    // Only the TITLE is edge-tracked. The subtitle rides along in the payload when the title
    // changes and is never compared, so a subtitle that changes under a steady title emits
    // nothing — there was a field here suggesting otherwise that nothing ever read or wrote.
    private String evtLastTitle = null;

    ClientEventDetector(BotBrain brain) {
        this.brain = brain;
    }

    /**
     * Fire player.death off the DeathScreen rising edge, carrying the screen's SPECIFIC cause (combat-kill message).
     * Also cancels active bot processes so an autoRespawn can't resume the lethal action into a death loop. Runs before
     * autoRespawn so the screen — and its cause — is still readable.
     */
    void detectDeath(Minecraft mc, Runnable onDeathCancel) {
        boolean onDeath = mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen;
        if (onDeath && !evtDeathScreenSeen) {
            String cause = net.magicterra.worlddriver.game.ui.DeathCause.of(
                    (net.minecraft.client.gui.screens.DeathScreen) mc.screen);
            net.minecraft.core.BlockPos at =
                    mc.player != null ? mc.player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
            brain.emit(
                    EventCatalog.PLAYER_DEATH,
                    at,
                    new EventCatalog.PlayerDeath(
                            mc.player != null ? (double) mc.player.getHealth() : 0.0,
                            cause != null && !cause.isEmpty()
                                    ? java.util.Optional.of(cause)
                                    : java.util.Optional.empty()));
            onDeathCancel.run();
        }
        evtDeathScreenSeen = onDeath;
    }

    /**
     * Emit driver→agent push events for the local player's hurt, fluid-entry, day-phase, pickup, tool-broke and
     * advancement transitions. Called once per client tick after the threat scan refreshes. Everything funnels through
     * the brain's stream → the same event stream block/chat/death use → subscribers on both transports.
     */
    void detectClientEvents(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        var pl = mc.player;
        net.minecraft.core.BlockPos at = pl.blockPosition();

        // Death emission + process-cancel live in detectDeath(), fired off the
        // DeathScreen rising edge (before autoRespawn dismisses it) so the event
        // carries the SPECIFIC cause. Here we only need `dead` to suppress hurt
        // events during the dying transition.
        boolean dead = pl.isDeadOrDying();

        float hp = pl.getHealth();
        if (!Float.isNaN(evtLastHealth) && hp < evtLastHealth - 0.01f && !dead) {
            // Damage attribution (gap #55): without it a fall in a self-dug pit and a
            // mob bite are indistinguishable HP deltas, and both the combat chain and
            // the agent engage phantoms. The client mirrors the server's DamageSource
            // via handleDamageEvent (ClientboundDamageEventPacket, 40-tick window).
            net.minecraft.world.damagesource.DamageSource src = pl.getLastDamageSource();
            java.util.Optional<String> source = java.util.Optional.empty();
            java.util.Optional<Integer> attackerId = java.util.Optional.empty();
            java.util.Optional<String> attackerType = java.util.Optional.empty();
            java.util.Optional<Double> attackerDistance = java.util.Optional.empty();
            if (src != null) {
                source = java.util.Optional.of(src.getMsgId());
                net.minecraft.world.entity.Entity att = src.getEntity();
                if (att != null && att != pl) {
                    attackerId = java.util.Optional.of(att.getId());
                    attackerType = java.util.Optional.of(
                            BuiltInRegistries.ENTITY_TYPE.getKey(att.getType()).toString());
                    attackerDistance = java.util.Optional.of((double) att.distanceTo(pl));
                }
            }
            brain.emit(
                    EventCatalog.PLAYER_HURT,
                    at,
                    new EventCatalog.PlayerHurt(
                            hp, evtLastHealth, evtLastHealth - hp, source, attackerId, attackerType, attackerDistance));
        }
        evtLastHealth = hp;

        // --- Fluid entry (fell into water / lava alert) -------------------------
        // One-shot on the rising edge of stepping into water / lava — the "I fell
        // in" warning so the Agent can react (swim/escape ashore, or that it's
        // burning in lava) without polling observe.player every tick. isInWater()
        // and isInLava() are the vanilla entity-in-fluid flags (true while any part
        // of the hitbox is in the fluid), matched against last tick so leaving and
        // re-entering re-fires.
        boolean inWater = pl.isInWater();
        if (inWater && !evtInWater) {
            brain.emit(EventCatalog.PLAYER_ENTERED_WATER, at, new EventCatalog.EnteredWater(pl.isUnderWater()));
        }
        evtInWater = inWater;
        boolean inLava = pl.isInLava();
        if (inLava && !evtInLava) {
            brain.emit(EventCatalog.PLAYER_ENTERED_LAVA, at, new EventCatalog.EnteredLava(pl.getHealth()));
        }
        evtInLava = inLava;

        // --- Day-phase transition (sunset alert etc.) ---------------------------
        // Emit on each day/sunset/night/sunrise change. The sunset/night edges are
        // the "go bunker NOW" warning whose absence got the naked bot swarmed; the
        // sunrise edge is the cue to break out and resume. Buckets match
        // observe.player.time.phase so the Agent reads the same vocabulary.
        if (mc.level != null) {
            // NOTE the key: this event's "dayTime" carries the FOLDED time-of-day (0..24000),
            // while observe.player.time.dayTime is the raw absolute one. Preserved as-is —
            // renaming it is an event-payload change for every consumer, not a de-duplication.
            long tod = TimeSnap.timeOfDay(mc.level.getDayTime());
            String phase = TimeSnap.phase(mc.level.getDayTime());
            if (!phase.equals(evtLastPhase)) {
                if (evtLastPhase != null) {
                    brain.emit(EventCatalog.TIME_PHASE, at, new EventCatalog.TimePhase(phase, evtLastPhase, tod));
                }
                evtLastPhase = phase;
            }
        }

        // --- Item pickup (item picked up alert) ---------------------------------
        // Diff per-item inventory counts vs last tick; any increase is a gain
        // (picked-up drop, craft result, or give). The Agent's "did my gather land?"
        // signal — e.g. after mine/COLLECT, an item.pickup{oak_log} confirms it.
        {
            Map<String, Integer> cur = new HashMap<>();
            var inv = pl.getInventory();
            for (int s = 0; s < inv.getContainerSize(); s++) {
                ItemStack st = inv.getItem(s);
                if (st.isEmpty()) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                cur.merge(id, st.getCount(), Integer::sum);
            }
            if (evtInvInit) {
                for (Map.Entry<String, Integer> e : cur.entrySet()) {
                    int delta = e.getValue() - evtInvCounts.getOrDefault(e.getKey(), 0);
                    if (delta > 0) {
                        brain.emit(
                                EventCatalog.ITEM_PICKUP,
                                at,
                                new EventCatalog.ItemPickup(e.getKey(), delta, e.getValue()));
                    }
                }
            }
            // --- Tool broke (tool durability exhausted alert) -----------------
            // The mainhand tool durability runs out SILENTLY: the item vanishes
            // and mine/goto grind on bare-handed at up to 12× the planned break
            // cost, with the Agent none the wiser until a "slow dig" mystery.
            // Best-effort edge: last tick's mainhand was a damageable item within
            // 2 uses of max damage, and that item id's total count dropped this
            // tick (a swap/slot-move keeps the count; a second identical tool
            // still decrements on a true break). A Q-drop of a near-dead tool
            // can false-positive — acceptable, the advice ("re-craft before the
            // next dig commit") is identical.
            if (evtInvInit
                    && evtMainhandId != null
                    && evtMainhandDamage >= evtMainhandMax - 2
                    && cur.getOrDefault(evtMainhandId, 0) < evtInvCounts.getOrDefault(evtMainhandId, 0)) {
                brain.emit(
                        EventCatalog.TOOL_BROKE,
                        at,
                        new EventCatalog.ToolBroke(evtMainhandId, evtMainhandDamage, evtMainhandMax));
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

        // --- Advancement earned (advancement alert) -----------------------------
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
                    if (!Map.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Map<?, ?> prog = (Map<?, ?>) f.get(ca);
                    for (Map.Entry<?, ?> e : prog.entrySet()) {
                        if (!(e.getValue() instanceof AdvancementProgress ap) || !ap.isDone()) {
                            continue;
                        }
                        if (!(e.getKey() instanceof AdvancementHolder holder)) {
                            continue;
                        }
                        String key = holder.id().toString();
                        if (evtDoneAdv.add(key) && evtAdvInit) {
                            brain.emit(EventCatalog.ADVANCEMENT, at, new EventCatalog.Advancement(key));
                        }
                    }
                    break; // first Map field is the progress map
                }
                evtAdvInit = true;
            } catch (Throwable ignored) {
                /* mapping/AT differences — never break ticks */
            }
        }
    }

    /**
     * Push each NEW chat line + action-bar / title change as an event. In client-MCP mode no server is attached, so the
     * server-side chat hook is silent; chat / system / command-result lines are drained from the packet-level
     * {@link ClientChatLog} (fed by the platform receive events), while the action-bar / title arrive as client-bound
     * packets the vanilla {@code Gui} stashes in private fields, read via guarded reflection (a mapping shift silently
     * no-ops, mirroring the advancement poll). The whole poll never breaks the tick.
     */
    void detectClientMessages(Minecraft mc) {
        net.minecraft.client.gui.Gui gui = mc.gui;
        if (gui == null) {
            return;
        }
        net.minecraft.core.BlockPos at =
                mc.player != null ? mc.player.blockPosition() : net.minecraft.core.BlockPos.ZERO;

        // --- Chat / system / command-result lines -------------------------------
        // Drain ClientChatLog above our cursor, oldest-first so the stream stays
        // chronological. First poll seeds the cursor silently so history present
        // before the detector started isn't replayed. Guarded like every other
        // poll in this class — an encode/emit throw must not break the tick, so
        // the cursor advances BEFORE the emit (drop one line, never wedge).
        try {
            if (evtChatSeq < 0) {
                evtChatSeq = ClientChatLog.nextSeq();
            } else if (ClientChatLog.nextSeq() > evtChatSeq) { // O(1) common case
                for (var e : ClientChatLog.since(evtChatSeq)) {
                    evtChatSeq = e.seq() + 1;
                    // Entry.row() = the same {seq,kind,text,self} shape chat.history
                    // returns, so consumers can reconcile the two surfaces by seq
                    // and skip the bot's own echoed lines via self.
                    brain.emit(
                            EventCatalog.CLIENT_MESSAGE,
                            at,
                            new EventCatalog.ChatLine(e.seq(), e.kind(), e.text(), e.self()));
                }
            }
        } catch (Throwable ignored) {
            /* never break the tick */
        }

        // --- Action bar (overlay message) ---------------------------------------
        try {
            String ab = componentFieldText(gui, "overlayMessageString");
            if (ab != null && !ab.isEmpty() && !ab.equals(evtLastActionBar)) {
                brain.emit(EventCatalog.CLIENT_ACTION_BAR, at, new EventCatalog.ClientText(ab));
            }
            evtLastActionBar = ab;
        } catch (Throwable ignored) {
        }

        // --- Title / subtitle ---------------------------------------------------
        try {
            String title = componentFieldText(gui, "title");
            if (title != null && !title.isEmpty() && !title.equals(evtLastTitle)) {
                String sub = componentFieldText(gui, "subtitle");
                brain.emit(
                        EventCatalog.CLIENT_TITLE,
                        at,
                        new EventCatalog.ClientTitle(
                                title,
                                sub != null && !sub.isEmpty()
                                        ? java.util.Optional.of(sub)
                                        : java.util.Optional.empty()));
            }
            evtLastTitle = title;
        } catch (Throwable ignored) {
        }
    }

    /**
     * Read a named {@code Component} field's {@code getString()}, or null.
     *
     * <p>The FIELD is still located by name, so this method stays remap-unsafe until
     * {@code Gui.title/subtitle/overlayMessageString} are opened up (tracked in {@code scripts/check_remap_safety.py}).
     * The {@code getString()} call no longer is: {@code Component} is public API, so a pattern-match reads the text
     * through a normal virtual call that tiny-remapper rewrites like any other.
     */
    private static String componentFieldText(Object obj, String name) {
        java.lang.reflect.Field f = findField(obj.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(obj) instanceof Component c ? c.getString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
