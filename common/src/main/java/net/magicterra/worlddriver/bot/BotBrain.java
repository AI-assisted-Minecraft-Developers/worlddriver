package net.magicterra.worlddriver.bot;

import static net.magicterra.worlddriver.api.game.Positions.blockPos;
import static net.magicterra.worlddriver.api.game.Positions.position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientScene;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.bot.arbitration.BunkerChain;
import net.magicterra.worlddriver.bot.arbitration.CancelRouting;
import net.magicterra.worlddriver.bot.arbitration.Chain;
import net.magicterra.worlddriver.bot.arbitration.CombatChain;
import net.magicterra.worlddriver.bot.arbitration.DodgeChain;
import net.magicterra.worlddriver.bot.arbitration.DrownEscapeChain;
import net.magicterra.worlddriver.bot.arbitration.DuskSecureChain;
import net.magicterra.worlddriver.bot.arbitration.PanicChain;
import net.magicterra.worlddriver.bot.arbitration.ProcessScheduler;
import net.magicterra.worlddriver.bot.arbitration.RetreatChain;
import net.magicterra.worlddriver.bot.arbitration.Ticket;
import net.magicterra.worlddriver.bot.arbitration.UserTaskChain;
import net.magicterra.worlddriver.bot.behavior.BackfillProcess;
import net.magicterra.worlddriver.bot.behavior.BackfillTracker;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.behavior.Intent;
import net.magicterra.worlddriver.bot.behavior.IntentProcess;
import net.magicterra.worlddriver.bot.behavior.Readiness;
import net.magicterra.worlddriver.bot.behavior.RouteEvents;
import net.magicterra.worlddriver.bot.behavior.reflex.AntiSuffocate;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoEat;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoEquip;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoHeal;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoShield;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoSwim;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoTool;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoTotem;
import net.magicterra.worlddriver.bot.behavior.reflex.ContactDamageEscape;
import net.magicterra.worlddriver.bot.behavior.reflex.LavaProximityEscape;
import net.magicterra.worlddriver.bot.control.ClutchController;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorTelemetry;
import net.magicterra.worlddriver.bot.planning.ScopeSource;
import net.magicterra.worlddriver.bot.rules.SearchScope;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.ui.ClientScreen;
import net.magicterra.worlddriver.game.ui.InputReleaseGate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.game.body.BotUtil.blockPosOf;

/**
 * One bot's decision stack: its scheduler and chains, its reflexes, its threat scan, its world
 * model, its water-bucket clutch, and the user task an agent hands it. Nothing here names a client
 * type, so the same brain can steer any {@link Body}.
 *
 * <p>A tick is four phases, run in this order by whoever drives the bot: {@link #perceive},
 * {@link #clutch}, {@link #refreshThreats} and {@link #act}. They are separate so a host can do
 * its own work between them — the client closes a stray container screen after the clutch and
 * reports its events after the threat scan, at the same points it always has.
 *
 * <p>Threading: everything except the read-only status and {@link #publishLeg} runs on the thread
 * that ticks the bot.
 */
public final class BotBrain {

    final BotState state = new BotState();
    /** Per-tick derived-facts blackboard. Updated each tick; snapshotted for off-thread reads by
     *  {@code mc.client.scene}. */
    private final WorldModel worldModel = new WorldModel();
    /** The foreground user task (goto/mine/build/...) lives in this chain. */
    private final UserTaskChain userTask;
    /** Phase C active-combat chain (priority 60). Holds the combat intent set by
     *  {@code mc.bot.combat} and the CombatProcess that executes it. */
    private final CombatChain combatChain;
    /** Movement-channel scheduler: each tick runs the highest-priority chain,
     *  letting survival/combat chains preempt the user task and hand it back. */
    private final ProcessScheduler scheduler = new ProcessScheduler();
    /** This bot's water-bucket clutch, ticked before the scheduler. */
    private final ClutchController clutch = new ClutchController();
    private final RouteEvents.Emitter events;
    private final PerceptionEvents perceptionEvents;
    /** The view this bot plans and moves over. A supplier, because a server bot's view is rebuilt
     *  when it changes dimension. */
    private final Supplier<? extends WorldView> world;

    /** Gates the idle release so it only fires after the bot itself pressed a movement input —
     *  never on the steady idle stream that used to clobber a human player's held WASD/space when no
     *  agent was driving. See {@link InputReleaseGate}. */
    private final InputReleaseGate releaseGate = new InputReleaseGate();

    /** autoEat hold-use loop. Owns its own {@code eating} flag; the tick drives it and releases
     *  the use intent when a process takes over. */
    private final AutoEat autoEat = new AutoEat();
    /** Phase B ambient hand reflexes (concurrent with movement). AutoShield/AutoHeal contend for
     *  the use key with autoEat — arbitrated in {@link #act}; AutoTotem owns the offhand slot
     *  independently. */
    private final AutoShield autoShield = new AutoShield();
    private final AutoHeal autoHeal = new AutoHeal();
    private final AutoTotem autoTotem = new AutoTotem();
    private final AutoTool autoTool = new AutoTool();
    /** The body-safety reflexes run after the scheduler; each keeps its own episode state. */
    private final AutoSwim autoSwim = new AutoSwim();
    private final AntiSuffocate antiSuffocate = new AntiSuffocate();
    private final ContactDamageEscape contactEscape = new ContactDamageEscape();
    private final LavaProximityEscape lavaEscape = new LavaProximityEscape();

    /** Records cells the bot's own digs broke while {@link BotConfig#autoBackfill} is on.
     *  BackfillProcess consumes from this when no main process is active. Bounded so the bot
     *  doesn't carry an unbounded queue across a long session. */
    final BackfillTracker backfillTracker = new BackfillTracker();

    /** gap#68-⑧: post-respawn grace countdown gating autoBackfill's auto-start (mirrors
     *  {@link CombatChain#autoSuppressed()} for autoFight). Armed to {@link BotConfig#respawnGraceTicks}
     *  by {@link #onDeath}; decremented once per tick regardless of process state. */
    private int respawnGraceLeft;

    /** Route previews ({@code plan: true}) and the cache {@code planId} adopts from; advanced by
     *  {@link #perceive}, never by the user task chain. */
    private final PreviewSearch preview = new PreviewSearch(state);

    /** Named positions persisted for the lifetime of the bot (no disk). Survives across
     *  goto/mine/etc. so a script can label home/farm/base and revisit by name. ConcurrentHashMap
     *  because list/get can race a save from a separate RPC handler thread. */
    private final Map<String, BlockPos> waypoints = new ConcurrentHashMap<>();

    volatile boolean paused;

    public BotBrain(RouteEvents.Emitter events, Supplier<? extends WorldView> world) {
        this.events = events;
        this.perceptionEvents = new PerceptionEvents(events);
        this.world = world;
        this.userTask = new UserTaskChain(state);
        this.combatChain = new CombatChain(state);
        // Reflex chains outrank the user task (see Priorities); registration
        // order is irrelevant, selection is purely by per-tick priority.
        scheduler.register(new PanicChain());     // 1000 — creeper blast
        scheduler.register(new DodgeChain());     // 900  — incoming projectile
        // Active-process drowning escape (gap#76, live death #25): mine dug into
        // water and kept digging through seven drown hits — the idle-only gap#70
        // float never runs under a process and AutoSwim's in-process jump backstop
        // loses the input channel to the process's own drive. Drowning must
        // PREEMPT, like every other lethal-now reflex. Above bunker (digging DOWN
        // while drowning is precisely lethal), below panic/dodge.
        scheduler.register(new DrownEscapeChain()); // 500 — drowning under an active process (autoDrownEscape)
        // "Dig three, fill one" emergency dig-in, registered but DEFAULT-OFF (autoBunker=false):
        // an OPT-IN last-resort reflex for a no-gear bot a flee can't save (a skeleton
        // matches walking speed on open ground — fleeing just circles, HP bleeds out).
        // The two original "too uncontrollable" concerns are now structurally fixed:
        //  (1) it no longer fights the creeper-panic — BUNKER(300) sits BELOW
        //      PANIC(1000)/DODGE(900), so those preempt it; and
        //  (2) it no longer digs at bad spots — tick() bails on water/lava/hazard
        //      below or beside the foot.
        // Still default-off (it modifies the world); enable per-run via
        // mc.bot.setting{autoBunker:true}. Agent-planned mc.bot.bunker{depth} stays
        // available for deliberate (e.g. at-dusk) dig-ins.
        scheduler.register(new BunkerChain());    // 300  — opt-in emergency dig-in (autoBunker)
        scheduler.register(new RetreatChain(state)); // 100 — low-HP flee
        scheduler.register(combatChain);          // 60  — active combat
        scheduler.register(userTask);             // 50  — foreground task
        scheduler.register(new DuskSecureChain(state, worldModel, events)); // 40 — idle dusk shelter
    }

    public WorldModel worldModel() { return worldModel; }

    /** {@code mc.client.scene}: the world model's latest snapshot. Safe off the tick thread. */
    public ClientScene scene() { return WorldReadings.clientScene(worldModel.snapshot()); }

    /** The projectile velocities behind the current threat scan. */
    public Map<Integer, Vec3> threatVelocities() { return state.threats.velocities(); }

    /** Push an event to the agent, on the stream the bot's own events use. */
    public <T> void emit(EventCatalog.Entry<T> entry, BlockPos pos, T payload) { emit(events, entry, pos, payload); }

    /**
     * A catalogued event through the bot's emitter. The emitter carries payloads as maps because the
     * layers below this facade may not name the catalog, so only an entry with an object payload fits.
     */
    @SuppressWarnings("unchecked")
    static <T> void emit(RouteEvents.Emitter to, EventCatalog.Entry<T> entry, BlockPos pos, T payload) {
        if (!(entry.payload().encode(payload) instanceof Map<?, ?> tree)) {
            throw new IllegalArgumentException(entry.type() + "'s payload is not an object");
        }
        to.emit(entry.type(), pos, (Map<String, Object>) tree);
    }

    /** What the navigators driving this bot's body have done; null until the first tick with a body. */
    private volatile NavigatorTelemetry navigatorTelemetry;

    /** The last deep search a navigator on this bot finished, or null. */
    private NavigatorTelemetry.PathStats lastPathStats() {
        NavigatorTelemetry t = navigatorTelemetry;
        return t == null ? null : t.lastStats;
    }

    // === the tick ===========================================================

    /** Phase one: the world model and its edge events, then a slice of any route preview in progress. */
    public void perceive(Body body) {
        LivingEntity self = body.entity();
        if (self != null) navigatorTelemetry = NavigatorTelemetry.of(self);
        // Update the perception blackboard every tick so mc.client.scene always
        // serves the freshest snapshot.
        worldModel.update(body, world.get());
        perceptionEvents.scene(worldModel.snapshot());
        // A route preview in progress gets its thin slice here, beside whatever walks.
        preview.tick();
    }

    /**
     * Phase two: the always-on water-bucket clutch (survival first). It arms reactively on any
     * unplanned damaging fall, or on the fall the navigator signalled last tick, and once armed OWNS
     * the descent before any process runs. True while it drives the fall: the caller ends the
     * tick there, so an active goto/mine/build is suspended for those airborne ticks instead of
     * fighting it. Clears the movement signals for this tick either way.
     */
    public boolean clutch(Body body) {
        WorldView world = this.world.get();
        boolean clutching = clutch.tick(body, world);
        // The clutch has taken last tick's plan; everything else in the signals is this tick's to say.
        world.signals().clear();
        if (clutching) releaseGate.markDirtied();
        return clutching;
    }

    /** Phase three: refresh the threat picture once per tick — reflex chains (panic/dodge) and
     *  the use-key arbiter (shield) all read it. {@code loaded} is every entity the host can see,
     *  projectiles among them. Then {@code threat.appeared} when the top threat changed. */
    public void refreshThreats(Body body, Iterable<? extends Entity> loaded) {
        state.threats.refresh(body.entity(), loaded);
        perceptionEvents.threats(state.threats.current());
    }

    /** Phase four: the ambient reflexes, the movement channel, and the body-safety backstops. */
    public void act(Body body) {
        LivingEntity p = body.entity();
        if (p == null) return;
        WorldView world = this.world.get();
        // Ambient hand/equipment/hotbar gating reads the foreground user process
        // (a preempting survival/combat chain leaves it held but suspended).
        BotProcess c = userTask.process();
        // Use-intent arbitration (Phase B): shield > heal > eat, one holder per tick,
        // the losers release. Why a builder silences all three — and what keeps this
        // protocol whole at all — is on builderSuppressesAmbients.
        if (builderSuppressesAmbients(c)) {
            autoShield.release(body); autoHeal.release(body); autoEat.releaseIfActive(body);
        } else {
            ThreatScanner.Scan scan = state.threats.current();
            if (BotConfig.autoShield && autoShield.wants(body, scan)) {
                autoHeal.release(body); autoEat.releaseIfActive(body);
                autoShield.engage(body, scan);
            } else if (BotConfig.autoHeal && autoHeal.wants(body)) {
                autoShield.release(body); autoEat.releaseIfActive(body);
                autoHeal.engage(body);
            } else if (BotConfig.autoEat) {
                autoShield.release(body); autoHeal.release(body);
                autoEat.tick(body);
            } else {
                autoShield.release(body); autoHeal.release(body); autoEat.releaseIfActive(body);
            }
        }
        // autoTotem owns the offhand slot (not the use key) — runs concurrently.
        if (BotConfig.autoTotem) autoTotem.tick(body);
        // autoEquip (Phase F T0): gear up the moment combat starts and keep the
        // best armor/weapon on through the fight. Gated on an engaged combat chain
        // so it doesn't reshuffle the inventory during peaceful crafting/building;
        // idempotent (worn gear outscores the inventory) so it settles after a tick.
        if (BotConfig.autoEquip && combatChain.engaged()) {
            AutoEquip.tick(body, BotConfig.equipDurabilityThreshold);
        }
        // autoSwim is applied AFTER the chain scheduler's idle release
        // below — see the note there. (Running it here was a no-op for an idle
        // bot: the idle release clobbered the jump key every tick, so a
        // submerged idle bot never surfaced and drowned with autoSwim "on".)
        // autoTool only fires when nothing owns hotbar selection — MineProcess
        // / BboxFillProcess / BuildProcess / FarmProcess and the reflex chains all
        // manage hotbar themselves and would fight us. So this is essentially "swap
        // to best tool when the player is manually mining" (or scripted-attack via
        // input.click), the same scope as Baritone's autoTool.
        if (BotConfig.autoTool && AutoTool.mayRun(c, scheduler.current() != null)) {
            autoTool.tick(body);
        }
        // autoBackfill puts back only what the bot broke — every process's digs, taken here
        // whoever drove them — never the natural air it walked through.
        backfillTracker.onClientTick(BotConfig.autoBackfill, body.takeOwnBreaks());
        BlockPos foot = p.blockPosition();
        // Auto-start BackfillProcess when idle + setting on + a cell is waiting.
        // Matches Baritone's BackfillProcess.isActive() trigger pattern: it
        // only runs when no higher-priority process wants the slot. The
        // process self-terminates once its work is done.
        if (respawnGraceLeft > 0) respawnGraceLeft--;
        if (c == null && respawnGraceLeft == 0 && BotConfig.autoBackfill
                && BackfillProcess.autoStartWanted(backfillTracker, foot, p.level())) {
            userTask.setAmbientProcess(new BackfillProcess(backfillTracker));
            paused = false;
        }
        // Movement channel: run the highest-priority chain (user task, or a
        // survival/combat chain preempting it). When every chain sits out, the
        // bot is idle — release the keys, matching the old single-process path.
        // Capture BEFORE the tick: a chain that runs this tick presses movement
        // keys even if it finishes mid-tick (current() then nulls) — its trailing
        // presses still need the one-shot cleanup below. A user chain holding nothing
        // ended its process last tick and settled that process's keys itself (released
        // them, or never took them), so it leaves nothing to clean up.
        Chain driving = scheduler.current();
        boolean schedulerDroveThisTick = driving != null && (driving != userTask || userTask.process() != null);
        scheduler.tick(body, world, state);
        // Immediately after the chain has had its turn, so a caller polling on the next server
        // tick sees the ending rather than one tick of stale "still busy".
        settleLeg();
        if (schedulerDroveThisTick) releaseGate.markDirtied();
        // Edge-clear: only release the bot's OWN trailing presses, never the
        // human's keys on the steady idle stream (the manual-input clobber bug).
        if (scheduler.current() == null && releaseGate.consumeRelease()) body.releaseControls();
        // autoSwim LAST: drowning backstop. Must run after the idle release
        // above — otherwise that call clears the jump key and an idle underwater
        // bot never surfaces (GAP #7). But idle-only was still too narrow (GAP #9):
        // a movement process can be ACTIVE yet STUCK in water — e.g. runAway boxed
        // into a flooded pit with no escape path (expanded≈2, Navigator not stepping),
        // so its own swim-up never fires AND the idle branch is gated off by the
        // active process → the bot drowns mid-process. So hold jump whenever the
        // head is submerged, regardless of process state. Rising is compatible with
        // a walking Navigator (both want the surface), and we only ever ADD lift here —
        // the release branch (surface transition) stays idle-gated so we never
        // clobber a land jump the Navigator set.
        // Idle drowning reflex runs regardless of the autoSwim flag — gated by its own
        // independent BehaviorConfig.autoFloatWhenDrowning (gap#70, live death #18: a bot with
        // no task sank and drowned with zero self-rescue; this used to be alarm-only and
        // is now an unconditional PURE-VERTICAL float, never movement — see AutoSwim
        // .drowningSentinel's doc for the controller's idle-passivity ruling). Mark the
        // release gate dirty when it actually held jump so the idle release path clears
        // that trailing key once air recovers, same bookkeeping as autoSwim's lift below.
        if (autoSwim.drowningSentinel(body, scheduler.current() == null))
            releaseGate.markDirtied();
        // gap#76: while DrownEscapeChain holds the channel the contract is PURE
        // VERTICAL (hold jump only) — AutoSwim.tick's in-process backstop would
        // re-add its near-surface shore-steer (keyUp + yaw) right after the
        // chain's tick zeroed it, so it is skipped for exactly that chain.
        if (BotConfig.autoSwim && p.isInWater()
                && !DrownEscapeChain.NAME.equals(scheduler.currentName())) {
            // Lift while submerged AND (when idle) actively swim to the nearest
            // shore — a bot that respawned/fell into a lake with no process used
            // to bob until it drowned (spawn-water death-loop). Steering is
            // idle-gated inside AutoSwim so it never fights an active goto's own
            // water-escape moves.
            autoSwim.tick(body, world, scheduler.current() == null);
            // autoSwim holds keyJump while submerged; mark dirty so the gate clears
            // that trailing jump once it surfaces (it self-clears keyUp but not jump).
            releaseGate.markDirtied();
        }
        // Suffocation backstop: when sand caves into the bot's head while it digs a
        // disturbed pit (bunker/goto/escape all hit this), break the eye block so it
        // can't be suffocated to death mid-dig. Unconditional like autoSwim's lift —
        // runs after the scheduler so it overrides a digging process's aim ONLY while
        // the head is actually choking, then hands control straight back.
        antiSuffocate.tick(body);
        // Contact-damage backstop (death #14, cactus): while a damaging BLOCK is
        // grinding the hull (cactus/berry bush/fire/magma), face away and step out
        // of contact. LLM latency can never beat a 2 Hz contact tick, and the
        // entity-attribution reflexes never fire for block damage. Same
        // idle-passivity carve-out as drowningSentinel/hurt-entry: reacting to
        // taking damage is a survival reflex, not uncommanded movement.
        contactEscape.tick(body);
        // Lava-front backstop (deaths #27/#29/#30): a FLOWING lava cell beside
        // the feet means the front arrives within ~1.5s — walk away NOW. The
        // path brakes prevent walking/falling INTO the flow; this one prevents
        // dying in place while the planner deliberates.
        lavaEscape.tick(body);
        // Keep the combat status slot's liveness in sync with the chain, so a status poll reads the
        // fight's end the moment it comes. Counters/goal/lastError persist for post-mortem.
        state.combat.active = combatChain.engaged();
    }

    /** A tick that ends early (no body to steer, or paused): release only what the bot itself
     *  pressed since the last release. */
    public void releaseIfDriven(Body body) {
        if (releaseGate.consumeRelease()) body.releaseControls();
    }

    /**
     * Whether a foreground builder process should silence the ambient use-key reflexes.
     *
     * <p><b>Not intent ownership</b>, despite how it reads. Six process kinds answer
     * {@code "builder"} (Build/Bridge/Tower/Backfill/BboxFill/Farm) and NONE of them
     * holds the use intent — they place through the body's block interaction directly. What this
     * suppresses is an ambient's held use firing a SECOND use-action on the tick a builder places,
     * which is why it gates the reflexes instead of handing the intent over. (Its previous name,
     * {@code processOwnsUseKey}, claimed an ownership that never existed.)
     *
     * <p>The use intent is the one shared input {@link Body#releaseControls()} deliberately omits —
     * the idle release runs AFTER the shield/heal/eat reflexes set it — so the arbitration in
     * {@link #act} is the ONLY thing keeping it from leaking, and every acquirer must join it or
     * self-clear on every exit path. {@code UseKeyOwnershipTest} pins the acquirer set against
     * exactly that drift.
     */
    private static boolean builderSuppressesAmbients(BotProcess c) {
        return c != null && c.kind().equals("builder");
    }

    // === what the host reads ================================================

    /** The name of what is driving the bot right now — the steering chain, else the active
     *  process slot — or null when it is idle or paused. */
    public String drivingName() {
        String n = scheduler.currentName();
        if (n == null) n = state.activeName();
        return n != null && !paused ? n : null;
    }

    /** A movement process wants the input channel, and it is not craft or smelt, which hold their
     *  station screens on purpose. */
    public boolean movementWantsInput() {
        return !state.craft.active && !state.smelt.active
                && (state.mc_goto.active || state.mine.active || state.builder.active
                    || state.follow.active || state.explore.active || state.runAway.active
                    || state.retreat.active || state.escape.active || state.combat.active);
    }

    public boolean paused() { return paused; }

    /** The foreground user process, held or suspended, or null when there is none. */
    public BotProcess userProcess() { return userTask.process(); }

    /** The last process a caller started, running or ended; null if none ever was. */
    public BotProcess lastAskedProcess() { return userTask.lastAsked(); }

    /** {@code {kind, error}} of the last ending of a process a caller started, or null. */
    public Map<String, Object> lastProcessEnd() { return userTask.lastEnd(); }

    /** Pause or resume the bot; pausing releases what the bot held on {@code body}. */
    public void setPaused(Body body, boolean pause) {
        if (pause && !paused) body.releaseControls();   // an input held at the moment of pausing would stay down
        paused = pause;
    }

    // === tasks ==============================================================

    /** Start a foreground user task. The process lifecycle lives in {@link UserTaskChain}; this is
     *  the single entry point the verbs call. */
    public void startProcess(BotProcess next) {
        userTask.setProcess(next);
        paused = false;
    }

    /** {@link #startProcess} for an accepted action, which the user task chain ends with the process. */
    public void startAction(BotProcess next, ActionHandle action) {
        userTask.setProcess(next, ActionTickets.of(action));
        paused = false;
    }

    public boolean cancelAction(Body body, ActionHandle action, String reason) {
        Ticket ticket = ActionTickets.of(action);
        return userTask.cancelAction(ticket, reason) || combatChain.cancelAction(body, ticket);
    }

    /** {@code mc.bot.combat}: stop the explicit fight that is still {@code action}'s. */
    public void cancelCombat(Body body, ActionHandle action) {
        combatChain.cancelAction(body, ActionTickets.of(action));
    }

    /** {@code mc.bot.combat}: take up the fight {@code p} names, ending {@code action} with it. */
    public void engage(CombatContracts.Params p, ActionHandle action) {
        combatChain.engage(VerbOrders.mode(p.mode()), VerbOrders.entityId(p), VerbOrders.entityType(p), p.force(),
                ActionTickets.of(action), new CombatContracts.Fought(p.mode(), p.target()));
    }

    /** {@code mc.bot.equip}: put the best armour (and, unless armour only, weapon) on {@code body}. */
    public ImmediateResponse equip(Body body, InteractContracts.EquipParams params) {
        boolean armorOnly = params.armorOnly() || params.profile() == InteractContracts.Profile.ARMOR;
        AutoEquip.Result r = AutoEquip.equipBest(body, !armorOnly,
                BotConfig.equipDurabilityThreshold);
        return new Responses.Equipped(armorOnly ? InteractContracts.Profile.ARMOR : params.profile(), r.equipped(),
                new InteractContracts.Loadout(slot(r, "head"), slot(r, "chest"), slot(r, "legs"), slot(r, "feet"), slot(r, "mainHand")),
                r.lowDurability(),
                r.missing().stream().map(s -> InteractContracts.ArmorSlot.valueOf(s.toUpperCase(java.util.Locale.ROOT))).toList());
    }

    private static Optional<String> slot(AutoEquip.Result r, String name) {
        return r.loadout().get(name) instanceof String id ? Optional.of(id) : Optional.empty();
    }

    /** Why {@code body}, seen through the client window {@code ui}, cannot take an order now, as
     *  {@code {ok:false, error, reason}}, or null. */
    public static Map<String, Object> refusal(Body body, ClientScreen ui) {
        Readiness.Refusal r = Readiness.refusal(body, ui);
        return r == null ? null : r.result();
    }

    public void forgetBackfill() { backfillTracker.clear(); }

    /** {@code mc.bot.cancel}: what was cancelled, by name. */
    public List<String> cancel(Body body, String which) {
        List<String> hits = new ArrayList<>();
        // Combat lives in its own chain, not the user-task slot — cancel it directly.
        if ("all".equals(which) || "combat".equals(which)) {
            if (combatChain.engaged()) {
                combatChain.standDown(body);
                state.combat.lastError = "user-cancel";
                state.combat.active = false;
                hits.add("combat");
            }
        }
        // gap#68-⑦/⑫: "cancel all" stays a best-effort broadcast —
        // every chain episode + the user slot + the orphan backfill queue.
        if ("all".equals(which)) {
            userTask.cancel("user-cancel");
            scheduler.cancelAllEpisodes(body, "user-cancel");
            backfillTracker.clear();
            return List.of("all");
        }
        // gap#72-②: a NAMED cancel routes through the shared resolver — user slot
        // by kind, chain episode by NAME, then any chain-HELD process by KIND
        // ("bunker" is both BunkerChain's name and BunkerProcess's kind; duskSecure's
        // held BunkerProcess was unreachable by every lookup step while cancel said ok:true).
        BotProcess c = userTask.process();
        CancelRouting.Plan plan =
                CancelRouting.resolve(which, c != null ? c.kind() : null, scheduler.chains());
        if (plan.cancelUserProcess()) userTask.cancel("user-cancel");
        for (Chain target : plan.episodeTargets()) target.cancelEpisode(body, "user-cancel");
        hits.addAll(plan.labels());
        return List.copyOf(hits);
    }

    /** End the user task and the explicit fight at a test boundary, naming each in {@code reset}. */
    public void resetTasks(Body body, List<String> reset) {
        // Every explicit task ends at a test boundary, not only a look: a goto left running would
        // keep driving the body through the next scene.
        BotProcess held = userTask.process();
        if (held != null) {
            userTask.cancel("mc.test.reset", h -> h.cancelled(Ticket.Stop.TEST_RESET));
            reset.add(held.kind());
        }
        if (combatChain.engaged()) {
            combatChain.standDown(body, h -> h.cancelled(Ticket.Stop.TEST_RESET));
            state.combat.lastError = "mc.test.reset";
            state.combat.active = false;
            reset.add("combat");
        }
    }

    /** The body died: every task fails with it, every chain episode resets, the backfill queue is
     *  dropped, and autoFight and the backfill auto-start sit out the respawn grace. */
    public void onDeath(Body body) {
        String reason = "player-death";
        if (combatChain.engaged()) {
            combatChain.standDown(body, h -> h.fail("the body died (" + reason + ")"));
            state.combat.lastError = reason;
            state.combat.active = false;
        }
        // The task failed with its body, nobody cancelled it. The always-on clutch is
        // intentionally left running.
        userTask.cancel(reason, h -> h.fail("the body died (" + reason + ")"));
        scheduler.cancelAllEpisodes(body, reason);   // reset every chain's episode state
        backfillTracker.clear();                      // drop the orphaned backfill queue
        combatChain.suppressAutoFor(BotConfig.respawnGraceTicks);
        respawnGraceLeft = BotConfig.respawnGraceTicks;
    }

    /** The bot's tick threw outside any process: the task, the fight and every episode end with
     *  {@code error}, and the actions they carried fail with it. */
    public void crashed(Body body, String error) {
        if (combatChain.engaged()) {
            combatChain.standDown(body, h -> h.fail(error));
            state.combat.lastError = error;
            state.combat.active = false;
        }
        userTask.cancel(error, h -> h.fail(error));
        scheduler.cancelAllEpisodes(body, error);
    }

    /** The user task cannot go on: it ends with {@code error} and its action fails with it. The reflexes
     *  and any fight are left as they are. */
    public void failTask(String error) {
        userTask.cancel(error, h -> h.fail(error));
    }

    /** The body left its world. Whatever the bot was doing there ends now rather than resuming in
     *  the next world, which would act on a world the caller never asked about. */
    public void onDisconnect(Body body) {
        if (combatChain.engaged()) {
            combatChain.standDown(body, h -> h.cancelled(Ticket.Stop.CONNECTION_CLOSED));
            state.combat.lastError = "disconnected";
            state.combat.active = false;
        }
        userTask.cancel("disconnected", h -> h.cancelled(Ticket.Stop.CONNECTION_CLOSED));
        scheduler.cancelAllEpisodes(body, "disconnected");
        backfillTracker.clear();
    }

    // === goto and waypoints =================================================

    /** The per-search entity snapshot for a preview or a scored line: the bot's own level with
     *  the bot excluded, the same source {@code NavigatorFinders} wires into a walking search. */
    private static ScopeSource scopeOf(LivingEntity self) {
        return (s, g, prof) -> SearchScope.gather(self.level(), self.getId(), s, g, prof);
    }

    /** {@code mc.bot.goto} for {@code self}; {@code nearestOfType} finds the entity a
     *  {@code goto entity:} names. */
    public GotoOrders.Order planGoto(GotoContracts.Params p, LivingEntity self, Function<String, Entity> nearestOfType) {
        WorldView world = this.world.get();
        if (p.planId().isPresent()) return adoptPlan(p.planId().get(), self, world);
        GotoOrders.Resolution resolution = GotoOrders.resolve(p, self, waypoints, nearestOfType);
        if (!(resolution instanceof GotoOrders.Resolved r)) return (GotoOrders.Refuse) resolution;
        if (p.plan().isEmpty()) return GotoOrders.start(r, self, events);
        PreviewSearch.Request req = new PreviewSearch.Request(self.blockPosition(), GotoOrders.targets(r),
                r.route().profile(), p.includePath(), world, scopeOf(self));
        return new GotoOrders.Launch(action -> preview.submit(req, action));
    }

    /** {@code plan: 'score'}: the caller's own line priced without a search. */
    public Responses.Scored scoreGoto(GotoContracts.Params p, LivingEntity self) {
        RouteContracts.Route route = p.route().orElseThrow();
        List<BlockPos> points = new ArrayList<>();
        for (var c : route.corridor().orElseThrow().points()) points.add(blockPos(c));
        return PreviewSearch.score(points, RouteParams.parse(route).profile(), scopeOf(self));
    }

    /**
     * {@code mc.bot.goto} with {@code planId}: walk the previewed route. The plan's own goals and
     * conditions are used — the id names them — and its raw result for the first goal is handed to
     * the navigator before the process starts, so no search precedes the first step. Falls back to an
     * ordinary search, saying why, when the plan is gone (60 s, last 8), was best-effort, or the
     * navigator refused it (the bot is no longer near its start). Later segments of a via plan search
     * as usual: adoption only ever guaranteed the route the bot starts on.
     */
    private GotoOrders.Order adoptPlan(String planId, LivingEntity self, WorldView world) {
        PreviewSearch.Plan plan = preview.take(planId);
        if (plan == null) {
            return new GotoOrders.Refuse(Responses.RefusalReason.INVALID_WORLD_TARGET, "planId '" + planId
                    + "' is unknown or expired (a preview is kept " + PreviewSearch.TTL_MS / 1000 + " s, the last "
                    + PreviewSearch.KEEP + " of them)");
        }
        IntentProcess process = new IntentProcess(new Intent(plan.goals, plan.profile.bias(),
                plan.profile.capability(), plan.profile.constraints(), null), events);
        String why = null;
        boolean adopted = false;
        if (plan.bestEffort()) why = "the preview did not reach the goal (bestEffort)";
        else if (System.currentTimeMillis() - plan.createdMs > PreviewSearch.TTL_MS) why = "the preview is older than 60 s";
        else {
            adopted = process.navigator().adoptPlan(plan.legs.get(0), world, self.blockPosition());
            if (!adopted) why = "the navigator refused the route: the bot is no longer near its start, or its first stretch is no longer walkable";
        }
        GotoContracts.Adoption adoption = new GotoContracts.Adoption(planId, adopted,
                Optional.ofNullable(why == null ? null : why + "; searched normally instead"));
        return new GotoOrders.Start(process, new GotoContracts.Trip(plan.goals.get(plan.goals.size() - 1).toString(),
                plan.goals.size() - 1, GotoContracts.Travel.WALK, Optional.of(adoption)));
    }

    /** {@code mc.bot.waypoint}; a save with no position takes {@code self}'s feet. */
    public ControlContracts.WaypointReply waypoint(ControlContracts.WaypointOp op, LivingEntity self) {
        return switch (op) {
            case ControlContracts.Save s -> {
                BlockPos pos;
                if (s.pos().isPresent()) {
                    pos = blockPos(s.pos().get());
                } else {
                    if (self == null) throw new IllegalStateException("no pos given and no player to take one from");
                    pos = blockPosOf(self.getX(), self.getY(), self.getZ());
                }
                waypoints.put(s.name(), pos);
                yield new ControlContracts.Saved(s.name(), position(pos), waypoints.size());
            }
            case ControlContracts.Get g -> {
                BlockPos pos = waypoints.get(g.name());
                if (pos == null) {
                    throw ApiException.invalidParam("/name", ApiIssue.Code.INVALID_VALUE, "no waypoint named '" + g.name() + "'");
                }
                yield new ControlContracts.Got(g.name(), position(pos));
            }
            case ControlContracts.ListAll l -> new ControlContracts.Listed(waypoints.entrySet().stream()
                    .map(e -> new ControlContracts.Waypoint(e.getKey(), position(e.getValue()))).toList());
            case ControlContracts.Delete d -> {
                boolean existed = waypoints.remove(d.name()) != null;
                yield new ControlContracts.Deleted(d.name(), existed, waypoints.size());
            }
            case ControlContracts.Clear c -> {
                int n = waypoints.size();
                waypoints.clear();
                yield new ControlContracts.Cleared(n);
            }
        };
    }

    // === status =============================================================

    public Map<String, Object> status() {
        Map<String, Object> snap = state.snapshot();
        snap.put("paused", paused);
        // Snapshot the volatile field to a local; the tick thread may
        // null the held process between the null check and a subsequent read.
        BotProcess c = userTask.process();
        // activeProcess = the foreground user task (unchanged semantics, even
        // while suspended by a survival/combat chain — that's what the agent
        // started). activeChain = what's actually steering right now.
        snap.put("activeProcess", c == null ? null : c.kind());
        // Sub-phase of a multi-phase process (e.g. bunker DIG_DOWN/…/SEALED/DONE)
        // so the agent can distinguish "still working" from "safely sealed" from
        // "finished/bailed" — a flat "bunker" reads identically in all cases and
        // led to a working shelter being cancelled mid-seal. null for processes
        // with no sub-state.
        snap.put("activeProcessDetail", c == null ? null : c.statusDetail());
        // Why the PREVIOUS process stopped: {kind, error} (error null = did what it was
        // asked). Not derivable from the slots: their lastError also holds success
        // summaries, and a process that switched no slot on leaves nothing there.
        snap.put("lastProcessEnd", userTask.lastEnd());
        snap.put("activeChain", scheduler.currentName());
        snap.put("userTaskSuspended", c != null && scheduler.current() != userTask);
        snap.put("chainPriorities", scheduler.lastPriorities());
        // Pathfinder stats from this bot's most recent A* run (any process). Useful
        // for debugging "why can't the bot get there" — exposes expansion
        // count, wall-clock ms, whether the goal was reached vs. fallback.
        NavigatorTelemetry.PathStats ps = lastPathStats();
        if (ps != null) {
            Map<String, Object> lp = new LinkedHashMap<>();
            lp.put("expanded", ps.expanded());
            lp.put("ms", ps.ms());
            lp.put("goalReached", ps.goalReached());
            lp.put("finalCost", ps.finalCost());
            lp.put("pathLen", ps.pathLen());
            lp.put("sightBudgetExhausted", ps.sightBudgetExhausted());
            lp.put("snapshotTruncated", ps.snapshotTruncated());
            snap.put("lastPath", lp);
        }
        // gap#68-R1a: per-chain priority + episode phase, so the agent (and the
        // player-death hook) can see chain-internal state that outlives any
        // BotProcess — the ⑦ deadlock (a sealed BunkerAnchor bidding 300 forever with
        // nothing in the process slot) was invisible to status() before this.
        Map<String, Object> chains = new LinkedHashMap<>();
        Map<String, Float> prios = scheduler.lastPriorities();
        for (Chain ch : scheduler.chains()) {
            Map<String, Object> one = new LinkedHashMap<>();
            Float pr = prios.get(ch.name());
            one.put("priority", pr == null ? 0f : pr);
            String ep = ch.episodePhase();
            if (ep != null) one.put("episode", ep);
            ProcessScheduler.Bail bail = scheduler.bailOf(ch);
            if (bail != null) one.put("bail", Map.of("reason", bail.reason(), "ticksLeft", bail.ticksLeft()));
            chains.put(ch.name(), one);
        }
        snap.put("chains", chains);
        // Always-on water-bucket clutch state (idle/lip/falling) — lets a test
        // confirm an unplanned fall actually armed and is self-rescuing.
        snap.put("clutch", clutch.phase());
        return snap;
    }

    public ControlContracts.BotStatus botStatus(String id) {
        BotProcess c = userTask.process();
        Map<String, ControlContracts.ChainState> chains = new LinkedHashMap<>();
        Map<String, Float> prios = scheduler.lastPriorities();
        for (Chain ch : scheduler.chains()) {
            Float pr = prios.get(ch.name());
            ProcessScheduler.Bail bail = scheduler.bailOf(ch);
            chains.put(ch.name(), new ControlContracts.ChainState(pr == null ? 0 : pr, Optional.ofNullable(ch.episodePhase()),
                    bail == null ? Optional.empty() : Optional.of(new ControlContracts.Bail(bail.reason(), bail.ticksLeft()))));
        }
        NavigatorTelemetry.PathStats ps = lastPathStats();
        Optional<ControlContracts.PathStats> lastPath = ps == null ? Optional.empty()
                : Optional.of(new ControlContracts.PathStats(ps.expanded(), ps.ms(), ps.goalReached(), ps.finalCost(), ps.pathLen(),
                        ps.sightBudgetExhausted(), ps.snapshotTruncated()));
        ControlContracts.Reflexes reflexes = new ControlContracts.Reflexes(paused,
                c == null ? Optional.empty() : Optional.ofNullable(c.statusDetail()),
                Optional.ofNullable(scheduler.currentName()), c != null && scheduler.current() != userTask, chains, lastPath,
                String.valueOf(clutch.phase()),
                new ControlContracts.CombatTally(state.combatSwings, state.combatWellTimed, state.combatCrits, state.combatKills));
        return new ControlContracts.BotStatus(id, c != null,
                c == null ? Optional.empty() : Optional.of(c.kind()), StatusView.end(userTask.lastEnd()),
                StatusView.slots(state), Optional.of(reflexes));
    }

    // === the object seam: run a caller's own BotProcess =====================
    // See BotApi for why these are not routes. They are the in-JVM entry point the playthrough
    // ladder uses to climb on the client's real player instead of spawning a headless FakePlayer
    // beside it.

    /**
     * One user task's whole state, published as a single immutable value.
     *
     * <p>A record rather than three volatile fields because the reader is on ANOTHER THREAD and
     * wants the three together: two independently-atomic reads do not compose into an atomic
     * pair, and the torn pair "busy is already cleared, but error still belongs to the previous
     * process run" is indistinguishable from a task that just finished cleanly. One reference, one read, one consistent answer.
     */
    private record Leg(long seq, boolean busy, String kind, String error) {}

    private volatile Leg leg = new Leg(0L, false, null, null);
    private final AtomicLong legSeq = new AtomicLong();

    /** The process {@link #installLeg} handed to the chain, once it is really in it.
     *  <b>Tick thread only</b> — written by the installer, read by {@link #settleLeg}. */
    private BotProcess installedLeg;
    private long installedLegSeq;

    /**
     * Publish {@code process} as the running task, from the CALLER's thread, before its install is
     * even enqueued. This is what closes the window the caller polls in: between "enqueued" and
     * "installed" the chain holds nothing, and a reader that asked the chain would conclude the
     * task had already finished — so every drive would return instantly having moved nothing.
     * Returns the sequence number {@link #installLeg} takes.
     */
    public long publishLeg(BotProcess process) {
        long seq = legSeq.incrementAndGet();
        leg = new Leg(seq, true, process.kind(), null);
        return seq;
    }

    /** Hand the published task to the chain. Tick thread only. */
    public void installLeg(BotProcess process, long seq) {
        startProcess(process);
        installedLeg = process;
        installedLegSeq = seq;
    }

    /**
     * Close out a user task whose process has left the chain. <b>Tick thread only</b>, once per
     * tick.
     *
     * <p>The test is the process OBJECT, not "is the chain empty": the chain is empty during the whole
     * window between enqueue and install too, and closing the task there is the false-completion
     * this seam exists to prevent.
     */
    private void settleLeg() {
        BotProcess mine = installedLeg;
        if (mine == null || userTask.process() == mine) return;
        // A newer task was published (publishLeg from the caller's thread) while this one was still
        // installed — the next scene's goto enqueued behind the last scene's one-tick hold.
        // Its departure is not the new task's ending: publishing busy=false here under the OLD seq
        // overwrote the new task's busy=true before its install ran, and the caller read "ended at
        // tick 0" for a walk that never started. The new task's own install re-points installedLeg.
        if (leg.seq() > installedLegSeq) {
            installedLeg = null;
            return;
        }
        Map<String, Object> end = userTask.lastEnd();
        Object err = end == null ? null : end.get("error");
        leg = new Leg(installedLegSeq, false, mine.kind(), err == null ? null : String.valueOf(err));
        installedLeg = null;
    }

    public Map<String, Object> userTaskLeg() {
        Leg now = leg;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", now.seq());
        out.put("busy", now.busy());
        out.put("kind", now.kind());
        out.put("error", now.error());
        return out;
    }
}
