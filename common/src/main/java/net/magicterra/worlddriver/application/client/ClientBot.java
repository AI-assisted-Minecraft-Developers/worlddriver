package net.magicterra.worlddriver.application.client;

import static net.magicterra.worlddriver.api.game.Positions.blockPos;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.game.HandWords;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientScene;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotBrain;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.GotoOrders;
import net.magicterra.worlddriver.bot.WorldReadings;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.ClientIntents;
import net.magicterra.worlddriver.game.body.ClientPlayerBody;
import net.magicterra.worlddriver.game.body.HandVerbs;
import net.magicterra.worlddriver.game.ui.ClientChatLog;
import net.magicterra.worlddriver.game.ui.ClientScreen;
import net.magicterra.worlddriver.game.ui.FocusPolicy;
import net.magicterra.worlddriver.game.ui.MouseYield;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The client's own player as a bot: the {@link BotBrain} that decides, ticked from the platform's client-tick hook, and
 * the client-only work around it — the mouse and focus policies, the death screen and auto-respawn, the stray-screen
 * watchdog, the client's events, the camera slew — plus the {@code mc.bot.*} and test verbs that read the client
 * directly.
 *
 * <p>Threading: every {@code mc.bot.*} call marshals to the client thread via {@code onClient}; {@link #clientTick}
 * runs on it already.
 */
public final class ClientBot implements BotApi {

    private final BotBrain brain;
    /**
     * Consecutive ticks a stray container screen has been blocking input while a movement process is live — the gap #58
     * screen-watchdog counter.
     */
    private int screenBlockTicks = 0;

    /**
     * Driver→agent client-tick push-event detection (hurt/death, fluid entry, day-phase, item pickup, advancements,
     * chat/title). Owns its own cross-tick edge state; {@link #clientTick()} drives it.
     */
    private final ClientEventDetector eventDetector;

    /** {@code brain} plans over the client's world view; the wiring builds both. */
    public ClientBot(BotBrain brain) {
        this.brain = brain;
        this.eventDetector = new ClientEventDetector(brain);
    }

    /** The brain, for the debug tools that install processes on it directly. */
    public BotBrain brain() {
        return brain;
    }

    @Override
    public ClientScene scene() {
        return brain.scene();
    }

    @Override
    public net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Threats threats(int radius) {
        Minecraft mc = Minecraft.getInstance();
        return WorldReadings.threats(mc.level, mc.player, radius, brain.threatVelocities());
    }
    /**
     * The body this bot steers: the client's own player. A cancel or stand-down that arrives off the tick releases what
     * the chains held on it.
     */
    private static Body body() {
        return new ClientPlayerBody(Minecraft.getInstance());
    }

    /**
     * The nearest entity of {@code typeId} this client has loaded, for {@code goto entity:}. It reads the client's
     * render list, so it lives here and not in the goto resolver, which a server-side bot runs too.
     */
    private static Entity nearestRenderedEntity(LocalPlayer self, String typeId) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.level instanceof ClientLevel cl)) {
            return null;
        }
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : cl.entitiesForRendering()) {
            if (e == self) {
                continue;
            }
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (!typeId.equals(id)) {
                continue;
            }
            double d = e.distanceToSqr(self);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** Client thread. */
    @Override
    public GotoOrders.Order planGoto(GotoContracts.Params p) {
        LocalPlayer player = Minecraft.getInstance().player;
        return brain.planGoto(p, player, type -> nearestRenderedEntity(player, type));
    }

    /** Client thread: {@code plan: 'score'}, the caller's own line priced without a search. */
    @Override
    public Responses.Scored scoreGoto(GotoContracts.Params p) {
        return brain.scoreGoto(p, Minecraft.getInstance().player);
    }

    @Override
    public ControlContracts.WaypointReply waypoint(ControlContracts.WaypointOp op) {
        return brain.waypoint(op, Minecraft.getInstance().player);
    }

    @Override
    public void engage(CombatContracts.Params p, ActionHandle action) {
        action.bindStopper(why -> Minecraft.getInstance().execute(() -> brain.cancelCombat(body(), action)));
        brain.engage(p, action);
    }

    @Override
    public ImmediateResponse equip(InteractContracts.EquipParams params) {
        return brain.equip(body(), params);
    }

    @Override
    public Map<String, Object> bodyRefusal() {
        return onClient(() -> BotBrain.refusal(body(), ClientScreen.now()));
    }

    @Override
    public Map<String, Object> status() {
        return brain.status();
    }

    @Override
    public ControlContracts.BotStatus botStatus() {
        return brain.botStatus(BotRegistry.SELF);
    }

    @Override
    public List<String> cancel(String which) {
        return onClient(() -> brain.cancel(body(), which));
    }

    // pause/resume removed — handled inside setting() via the {paused:bool} key.

    @Override
    public ImmediateResponse useItem(InteractContracts.UseParams params) {
        return InteractionCommands.useItem(params);
    }

    @Override
    public InteractContracts.OnEntity afterUse(InteractContracts.OnEntity on) {
        return InteractionCommands.afterUse(on);
    }

    @Override
    public ImmediateResponse holdItem(InteractContracts.HoldParams params) {
        return InteractionCommands.holdItem(params);
    }

    @Override
    public ImmediateResponse attackEntity(InteractContracts.AttackParams params) {
        return InteractionCommands.attackEntity(params);
    }

    @Override
    public boolean paused() {
        return brain.paused();
    }

    @Override
    public void setPaused(boolean pause) {
        brain.setPaused(body(), pause);
    }

    /** A test affordance for the flight-handling path: it sets the abilities as the creative toggle would. */
    @Override
    public void debugFly(boolean flying) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            throw new IllegalStateException("debugFly needs this client's player in a world");
        }
        p.getAbilities().mayfly = true;
        p.getAbilities().flying = flying;
        p.onUpdateAbilities();
    }

    @Override
    public void forgetBackfill() {
        brain.forgetBackfill();
    }

    /**
     * {@code mc.test.reset} client-pool entry reset — see {@link BotApi#resetClientEntry()}. Reuses the existing client
     * primitives (no new behaviour): {@link net.magicterra.worlddriver.game.body.BotInteract#releaseKeys()}, the
     * {@code mc.client.screen.close} {@code setScreen(null)} path, {@link ClientChatLog#clear()} and the scheduler's
     * user-slot cancel. The {@code reset[]} list names exactly what changed so P2b's reuse acceptance can diff it. Kept
     * minimal — completeness is P2b's acceptance concern.
     *
     * <p><b>Every token reports an EFFECT, and that is a correction.</b> {@code chat:N} always did; {@code keys} did
     * not — it was appended after {@code releaseKeys()} returned, so it said the release code ran and nothing about
     * whether anything was released, and a {@code releaseKeys()} that became a no-op would have kept it green forever.
     * It is now {@code keys:<names>}, read off {@link KeyMapping#isDown()} on the same client hop, before and after; it
     * is absent when nothing was down. {@code screen} likewise names the screen class it closed, so "there was no
     * screen" and "the close did nothing" stop reading alike.
     */
    @Override
    public List<String> resetClientEntry() {
        List<String> reset = new ArrayList<>();
        // One client-thread hop for everything that touches scheduler or render state.
        // The look-cancel MUST be inside the hop: the scheduler (userTask) is ticked and
        // mutated from clientTick(), and every sibling mutation (mc.bot.cancel's own path
        // included) marshals via onClient — an off-thread cancel here would race the tick
        // (P2a Task 3 review, Important). Cancelling FIRST, same-thread, also guarantees no
        // client tick can interleave between the cancel and the key release, so a live
        // LookProcess can never re-drive the keys we are about to release.
        onClient(() -> {
            brain.resetTasks(body(), reset);
            Minecraft mc = Minecraft.getInstance();
            String wereDown = heldKeyNames(mc);
            releaseKeys();
            String stillDown = heldKeyNames(mc);
            // Named rather than counted, and the surviving names named too: "keys:up" and
            // "keys:up→still:up" are the difference between a release that worked and one that
            // did not, and a bare count cannot tell them apart.
            if (!wereDown.isEmpty()) {
                reset.add("keys:" + wereDown + (stillDown.isEmpty() ? "" : "→still:" + stillDown));
            }
            if (mc.screen != null) {
                String was = mc.screen.getClass().getSimpleName();
                mc.setScreen(null);
                reset.add("screen:" + was
                        + (mc.screen == null
                                ? ""
                                : "→still:" + mc.screen.getClass().getSimpleName()));
            }
            return Map.of();
        });
        int chatCleared = ClientChatLog.clear(); // pure JVM buffer, no client thread needed
        reset.add("chat:" + chatCleared);
        return List.copyOf(reset);
    }

    /**
     * The movement keys {@code releaseKeys()} clears that are down right now, comma separated in the same order
     * {@link #heldKeys()} reports them. Client thread only — the caller is already inside the hop.
     */
    private static String heldKeyNames(Minecraft mc) {
        if (mc.options == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendIfDown(sb, "up", mc.options.keyUp);
        appendIfDown(sb, "down", mc.options.keyDown);
        appendIfDown(sb, "left", mc.options.keyLeft);
        appendIfDown(sb, "right", mc.options.keyRight);
        appendIfDown(sb, "jump", mc.options.keyJump);
        appendIfDown(sb, "sprint", mc.options.keySprint);
        if (ClientIntents.digHeld()) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append("attack");
        }
        appendIfDown(sb, "shift", mc.options.keyShift);
        return sb.toString();
    }

    private static void appendIfDown(StringBuilder sb, String name, KeyMapping key) {
        if (!key.isDown()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        sb.append(name);
    }

    /**
     * {@code mc.test.input.heldKeys} — see {@link BotApi#heldKeys()}. Reads {@link KeyMapping#isDown()} on the client
     * thread for exactly what {@link net.magicterra.worlddriver.game.body.BotInteract#releaseKeys()} clears, in the
     * same order, under the reply names {@code up/down/left/right/jump/sprint/attack/shift}. {@code attack} is the
     * bot's dig latch ({@link ClientIntents#digHeld()}), not a keybind — the bot stopped pressing the attack key on
     * 2026-09-14 — but it keeps its slot so the instrument's shape is stable. Pure observation — mutates nothing.
     */
    @Override
    public TestInputContracts.HeldKeys heldKeys() {
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options == null) {
                throw new IllegalStateException("no client options");
            }
            return new TestInputContracts.HeldKeys(
                    mc.options.keyUp.isDown(),
                    mc.options.keyDown.isDown(),
                    mc.options.keyLeft.isDown(),
                    mc.options.keyRight.isDown(),
                    mc.options.keyJump.isDown(),
                    mc.options.keySprint.isDown(),
                    ClientIntents.digHeld(),
                    mc.options.keyShift.isDown());
        });
    }

    /**
     * {@code mc.test.input.useOnBlock} — see {@link BotApi#useOnBlock}. Instrument-grade: the ONLY state change is the
     * {@code gameMode.useItemOn} right-click itself. The face is the one nearest the player's eye
     * ({@code faceTowardEye}) and the hit Vec3 is that face's centre — the exact synthetic-hit shape
     * {@code mc.bot.useItemOn} builds — but unlike the behaviour verb it does NOT aim (no yaw/pitch write), NOT move,
     * and NOT toggle sneak, so nothing in the path/aim pipeline runs. Opening a block-entity container (empty hand +
     * right-click) does not need any of those.
     */
    @Override
    public TestInputContracts.UsedOnBlock useOnBlock(TestInputContracts.UseOnBlock params) {
        final InteractionHand hand = HandWords.hand(params.hand());
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null) {
                throw new IllegalStateException("no local player");
            }
            BlockPos block = blockPos(params.pos());
            Direction face = faceTowardEye(block, p);
            BlockHitResult hit = new BlockHitResult(HandVerbs.faceCentre(block, face), face, block, false);
            InteractionResult r = mc.gameMode.useItemOn(p, hand, hit);
            if (r.consumesAction()) {
                p.swing(hand);
            }
            return new TestInputContracts.UsedOnBlock(
                    HandWords.interaction(r),
                    r.consumesAction(),
                    params.hand(),
                    InteractContracts.Face.valueOf(face.name()));
        });
    }

    /**
     * The two policies that key off "is the bot currently driving", applied at the very top of every client tick —
     * ahead of all of {@code clientTick}'s early returns (no world, bot paused, clutch owning the tick), because each
     * one's FALLING edge has to be honoured on exactly those ticks too. Handing the cursor back, or handing the human's
     * pause setting back, must not be skipped just because there is nothing else to do this tick.
     *
     * <ul>
     *   <li><b>Mouse coexistence</b> — marked from the PREVIOUS tick's ownership (the scheduler's current chain is last
     *       tick's decision): a 1-tick lag on a 20-tick linger, so it never flickers.
     *   <li><b>Focus</b> — while the bot drives, vanilla's pause-on-lost-focus is suppressed so an alt-tab cannot
     *       freeze the world mid-task (see {@link FocusPolicy}).
     * </ul>
     */
    private void applyTakeoverPolicies(Minecraft mc) {
        String mouseDriver = brain.drivingName();
        boolean botDriving = mouseDriver != null;
        if (botDriving) {
            MouseYield.markDriving(mouseDriver);
        }
        MouseYield.tick(mc);
        FocusPolicy.apply(mc, botDriving);
    }

    /** Called from the platform client-tick hook every client tick. */
    public void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        applyTakeoverPolicies(mc);
        // Death detection must run BEFORE autoRespawn: autoRespawn dismisses the
        // DeathScreen (setScreen(null)), and the screen is the only client-side
        // carrier of the SPECIFIC cause of death (slain by X / drowned / blown
        // up). Read it on the screen's rising edge, emit player.death + cancel
        // active processes, THEN let autoRespawn skip past.
        eventDetector.detectDeath(mc, () -> brain.onDeath(body()));
        // autoRespawn fires even when level/player is in the dying transition —
        // DeathScreen shows briefly with mc.player still alive but at 0 HP, and
        // we want to skip past it ASAP. Done first so the rest of the tick sees
        // the post-respawn world state.
        if (BotConfig.autoRespawn) {
            AutoRespawn.tick(mc);
        }
        if (mc.level == null || mc.player == null || brain.paused()) {
            brain.releaseIfDriven(body());
            return;
        }
        // The client's player as the body every reflex and chain below steers; built fresh per
        // tick for the reason clientAvatar() gives.
        ClientPlayerBody body = new ClientPlayerBody(mc);
        brain.perceive(body);
        // The clutch owns the whole tick when it fires (look-down + place + return), so
        // it is a bot drive in its own right — mark it for the mouse gate, which no
        // process slot would report (the clutch is slot-less by design).
        if (brain.clutch(body)) {
            MouseYield.markDriving("clutch");
            return;
        }
        // Screen watchdog (gap #58): an unexpectedly-open container GUI swallows
        // every movement input, paralysing the navigator AND all reflex chains (live
        // death #3: a stray place-click opened a FurnaceScreen; the pinned navigator's
        // futile-search then misreported "goal unreachable" while a zombie chewed
        // the defenseless bot). Craft/smelt legitimately hold their station screens
        // (craft.active/smelt.active gate), and one grace second tolerates screens
        // a verb opened deliberately; anything else that lingers while a movement
        // process wants the input channel gets closed and reported.
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                && brain.movementWantsInput()) {
            if (++screenBlockTicks >= 20) {
                String type = mc.screen.getClass().getSimpleName();
                mc.player.closeContainer();
                mc.setScreen(null);
                brain.emit(
                        EventCatalog.SCREEN_AUTO_CLOSED,
                        mc.player.blockPosition(),
                        new EventCatalog.ScreenAutoClosed(type, screenBlockTicks));
                LOG.warn("[screenWatchdog] closed stray {} after {} blocked ticks", type, screenBlockTicks);
                screenBlockTicks = 0;
            }
        } else {
            screenBlockTicks = 0;
        }
        brain.refreshThreats(body, mc.level.entitiesForRendering());
        // Driver→agent push: one-shot events on this player's hurt / fluid / pickup / ...
        // transitions (the brain fired threat.appeared from the refresh above).
        eventDetector.detectClientEvents(mc);
        // Driver→agent push: surface new chat / system / command-result lines
        // (packet-level ClientChatLog drain) and action-bar / title text (guarded
        // reflection) — no server-side hook in client-MCP mode. Both paths are
        // guarded; never breaks the tick.
        eventDetector.detectClientMessages(mc);
        brain.act(body);
        // STREAM-GRADE CAMERA (AIRI): single chokepoint, AFTER every actuator above
        // has written the player's rotation. Rate-limits this tick's net yaw/pitch
        // change so no code path can snap the view; a functional exact aim this tick
        // (camera-raycast mine/attack, a leap heading) bypasses it via
        // LookController.requestSnap(). See GameConfig.cameraSlew.
        net.magicterra.worlddriver.game.body.LookController.apply(mc.player);
    }

    /**
     * Start a foreground user task for an accepted action, which the user task chain ends with the process. Client
     * thread only.
     */
    @Override
    public void startAction(BotProcess next, ActionHandle action) {
        brain.startAction(next, action);
    }

    /** Client thread only. */
    @Override
    public boolean cancelAction(ActionHandle action, String reason) {
        return brain.cancelAction(body(), action, reason);
    }

    @Override
    public net.magicterra.worlddriver.api.thread.ServerThreadHop clientHop() {
        return net.magicterra.worlddriver.game.ClientHop.hop();
    }

    @Override
    public java.util.concurrent.Executor clientExecutor() {
        return Minecraft.getInstance();
    }

    @Override
    public net.minecraft.world.entity.LivingEntity selfEntity() {
        return Minecraft.getInstance().player;
    }

    @Override
    public BotProcess lastAskedProcess() {
        return brain.lastAskedProcess();
    }

    /** The client left its server. Client thread only. */
    public void onDisconnect() {
        brain.onDisconnect(body());
    }

    @Override
    public Map<String, Object> runProcess(BotProcess process) {
        long seq = brain.publishLeg(process);
        Runnable install = () -> brain.installLeg(process, seq);
        Minecraft mc = Minecraft.getInstance();
        // mc.execute, deliberately NOT BotUtil.onClient: onClient waits on a future, and the
        // caller this exists for is the server thread of an INTEGRATED server — the same JVM, the
        // other side of a tick handshake. Blocking it for a client frame is the stall a
        // fire-and-forget start exists to avoid.
        if (mc.isSameThread()) {
            install.run();
        } else {
            mc.execute(install);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        out.put("kind", process.kind());
        out.put("seq", seq);
        return out;
    }

    @Override
    public Map<String, Object> userTaskLeg() {
        return brain.userTaskLeg();
    }

    /**
     * Built fresh per call rather than cached, because {@code ClientPlayerBody} binds {@code mc.player} in its
     * constructor and that reference dies on every respawn and dimension change. A cached one would keep actuating a
     * stale player — the same "the world view does not follow the player entity" shape the driver has already paid for
     * once, where a view built at construction planned over the old dimension's terrain for every rung after the
     * portal.
     */
    @Override
    public net.magicterra.worlddriver.game.body.Body clientAvatar() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : new net.magicterra.worlddriver.game.body.ClientPlayerBody(mc);
    }
}
