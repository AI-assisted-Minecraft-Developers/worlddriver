package net.magicterra.worlddriver.bot;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.api.game.Positions.blockPos;
import static net.magicterra.worlddriver.api.game.Positions.position;
import net.magicterra.worlddriver.bot.behavior.RouteEvents;
import net.magicterra.worlddriver.api.game.HandWords;
import net.magicterra.worlddriver.game.body.HandVerbs;

import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientScene;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import java.util.Optional;
import net.magicterra.worlddriver.bot.rules.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.game.ui.ClientChatLog;
import net.magicterra.worlddriver.game.body.ClientIntents;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerPlanAdoption;
import net.magicterra.worlddriver.bot.rules.SearchScope;
import net.magicterra.worlddriver.bot.behavior.BackfillProcess;
import net.magicterra.worlddriver.bot.behavior.BackfillTracker;
import net.magicterra.worlddriver.bot.behavior.BodyReady;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.behavior.Intent;
import net.magicterra.worlddriver.bot.behavior.IntentProcess;
import net.magicterra.worlddriver.bot.behavior.RouteEvents;
import net.magicterra.worlddriver.bot.arbitration.BunkerChain;
import net.magicterra.worlddriver.bot.arbitration.CancelRouting;
import net.magicterra.worlddriver.bot.arbitration.Ticket;
import net.magicterra.worlddriver.bot.arbitration.Chain;
import net.magicterra.worlddriver.bot.arbitration.CombatChain;
import net.magicterra.worlddriver.bot.arbitration.DodgeChain;
import net.magicterra.worlddriver.bot.arbitration.DrownEscapeChain;
import net.magicterra.worlddriver.bot.arbitration.DuskSecureChain;
import net.magicterra.worlddriver.bot.arbitration.PanicChain;
import net.magicterra.worlddriver.bot.arbitration.ProcessScheduler;
import net.magicterra.worlddriver.bot.arbitration.RetreatChain;
import net.magicterra.worlddriver.bot.arbitration.UserTaskChain;
import static net.magicterra.worlddriver.bot.GoalResolver.*;
import static net.magicterra.worlddriver.bot.control.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import java.util.Locale;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoEat;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoEquip;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoShield;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoHeal;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoTotem;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.ClientThreatScanner;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoTool;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoSwim;
import net.magicterra.worlddriver.bot.behavior.reflex.AntiSuffocate;
import net.magicterra.worlddriver.bot.behavior.reflex.ContactDamageEscape;
import net.magicterra.worlddriver.bot.behavior.reflex.AutoRespawn;
import net.magicterra.worlddriver.bot.world.WorldModel;
import java.util.concurrent.ConcurrentHashMap;
import net.magicterra.worlddriver.bot.behavior.BodyReady;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.world.ClientWorldView;
import net.magicterra.worlddriver.game.ui.FocusPolicy;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.game.ui.MouseYield;

/**
 * Phase-2 client bot impl. Hosts a single active {@link BotProcess} driven
 * each client tick by the platform tick hook. Processes implemented so far:
 * goto (Phase 1), mine (Phase 2). Others return unimplemented.
 *
 * Threading: every {@code mc.bot.*} call marshals to the client thread via
 * {@link #onClient}; {@link #clientTick} runs on it already. The movement
 * channel ({@link ProcessScheduler}) is only mutated from the client thread.
 */
public final class BotApiImpl implements BotApi {

    private final BotState state = new BotState();
    private final ClientWorldView world = new ClientWorldView();
    /** Consecutive ticks a stray container screen has been blocking input while a
     *  movement process is live — the gap #58 screen-watchdog counter. */
    private int screenBlockTicks = 0;
    /** Per-tick derived-facts blackboard. Updated each client tick; snapshotted
     *  for off-thread reads by {@code mc.client.scene}. */
    private final WorldModel worldModel = new WorldModel();
    @Override public ClientScene scene() { return WorldReadings.clientScene(worldModel.snapshot()); }
    // Wire the WorldModel into the ClientWorldView so dangerCost can apply the
    // HazardField lethal-cell penalty. Done in an instance initialiser so both
    // final fields are guaranteed initialised before any tick fires. The cast is
    // unnecessary because world is now declared as ClientWorldView directly.
    { world.setWorldModel(worldModel); }
    /** The foreground user task (goto/mine/build/...) lives in this chain. */
    private final UserTaskChain userTask = new UserTaskChain(state);
    /** Phase C active-combat chain (priority 60). Holds the combat intent set by
     *  {@code mc.bot.combat} and the CombatProcess that executes it. */
    private final CombatChain combatChain = new CombatChain(state);
    /** Movement-channel scheduler: each tick runs the highest-priority chain,
     *  letting survival/combat chains preempt the user task and hand it back. */
    private final ProcessScheduler scheduler = new ProcessScheduler();
    private final RouteEvents.Emitter eventEmitter;

    public BotApiImpl(RouteEvents.Emitter eventEmitter) {
        this.eventEmitter = eventEmitter;
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
        scheduler.register(new DuskSecureChain(state, worldModel, eventEmitter)); // 40 — idle dusk shelter
    }
    volatile boolean paused;
    /** Gates the idle {@code releaseKeys()} so it only fires after the bot itself
     *  pressed a movement keybind — never on the steady idle stream that used to
     *  clobber a human player's held WASD/space when no agent was driving. See
     *  {@link net.magicterra.worlddriver.game.ui.InputReleaseGate}. */
    private final net.magicterra.worlddriver.game.ui.InputReleaseGate releaseGate =
            new net.magicterra.worlddriver.game.ui.InputReleaseGate();
    /** Named positions persisted for the lifetime of the bot impl (no disk).
     *  Survives across goto/mine/etc. so a script can label home/farm/base
     *  and revisit by name. ConcurrentHashMap because list/get can race a
     *  save from a separate RPC handler thread. */
    private final Map<String, BlockPos> waypoints = new ConcurrentHashMap<>();
    /** autoEat hold-use loop. Owns its own {@code eating} flag; the tick
     *  hook drives it and releases the use intent when a process takes over. */
    private final AutoEat autoEat = new AutoEat();
    /** Phase B ambient hand reflexes (concurrent with movement). AutoShield/
     *  AutoHeal contend for the use key with autoEat — arbitrated in clientTick;
     *  AutoTotem owns the offhand slot independently. */
    private final AutoShield autoShield = new AutoShield();
    private final AutoHeal autoHeal = new AutoHeal();
    private final AutoTotem autoTotem = new AutoTotem();

    /** Records cells the bot's own digs broke while {@link BotConfig#autoBackfill}
     *  is on. BackfillProcess consumes from this when no main process is
     *  active. Bounded so the bot doesn't carry an unbounded queue across a
     *  long session. */
    final BackfillTracker backfillTracker = new BackfillTracker();

    /** gap#68-⑧: post-respawn grace countdown gating autoBackfill's auto-start (mirrors
     *  {@link CombatChain#autoSuppressed()} for autoFight). Armed to {@link BotConfig#respawnGraceTicks}
     *  by the death hook; decremented once per tick regardless of process state. */
    private int respawnGraceLeft;

    /** Route previews ({@code plan: true}) and the cache {@code planId} adopts from; advanced by
     *  {@link #clientTick}, never by the user task chain. */
    private final PreviewSearch preview = new PreviewSearch(state);

    /** The per-search entity snapshot for a preview or a scored line: the player's own level with
     *  the player excluded, the same source {@code WalkerFinders} wires into a walking search. */
    private static net.magicterra.worlddriver.bot.planning.ScopeSource scopeOf(LocalPlayer player) {
        return (s, g, prof) -> SearchScope.gather(player.level(), player.getId(), s, g, prof);
    }

    /**
     * The nearest entity of {@code typeId} this client has loaded, for {@code goto entity:}. It reads
     * the client's render list, so it lives here and not in {@link GotoGoalResolver}, which a
     * server-side bot runs too.
     */
    private static Entity nearestRenderedEntity(LocalPlayer self, String typeId) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.level instanceof ClientLevel cl)) return null;
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : cl.entitiesForRendering()) {
            if (e == self) continue;
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (!typeId.equals(id)) continue;
            double d = e.distanceToSqr(self);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** Client thread. */
    @Override
    public GotoOrders.Order planGoto(GotoContracts.Params p) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (p.planId().isPresent()) return adoptPlan(p.planId().get());
        GotoOrders.Resolution resolution = GotoOrders.resolve(p, player, waypoints, type -> nearestRenderedEntity(player, type));
        if (!(resolution instanceof GotoOrders.Resolved r)) return (GotoOrders.Refuse) resolution;
        if (p.plan().isEmpty()) return GotoOrders.start(r, player, eventEmitter);
        PreviewSearch.Request req = new PreviewSearch.Request(player.blockPosition(), GotoOrders.targets(r),
                r.route().profile(), p.includePath(), world, scopeOf(player));
        return new GotoOrders.Launch(action -> preview.submit(req, action));
    }

    /** Client thread: {@code plan: 'score'}, the caller's own line priced without a search. */
    @Override
    public Responses.Scored scoreGoto(GotoContracts.Params p) {
        RouteContracts.Route route = p.route().orElseThrow();
        List<BlockPos> points = new ArrayList<>();
        for (var c : route.corridor().orElseThrow().points()) points.add(blockPos(c));
        return PreviewSearch.score(points, RouteParams.parse(route).profile(), scopeOf(Minecraft.getInstance().player));
    }

    /**
     * {@code mc.bot.goto} with {@code planId}: walk the previewed route. The plan's own goals and
     * conditions are used — the id names them — and its raw result for the first goal is handed to
     * the walker before the process starts, so no search precedes the first step. Falls back to an
     * ordinary search, saying why, when the plan is gone (60 s, last 8), was best-effort, or the
     * walker refused it (the bot is no longer near its start). Later segments of a via plan search
     * as usual: adoption only ever guaranteed the route the bot starts on.
     */
    private GotoOrders.Order adoptPlan(String planId) {
        PreviewSearch.Plan plan = preview.take(planId);
        if (plan == null) {
            return new GotoOrders.Refuse(Responses.RefusalReason.INVALID_WORLD_TARGET, "planId '" + planId
                    + "' is unknown or expired (a preview is kept " + PreviewSearch.TTL_MS / 1000 + " s, the last "
                    + PreviewSearch.KEEP + " of them)");
        }
        IntentProcess process = new IntentProcess(new Intent(plan.goals, plan.profile.bias(),
                plan.profile.capability(), plan.profile.constraints(), null), eventEmitter);
        String why = null;
        boolean adopted = false;
        if (plan.bestEffort()) why = "the preview did not reach the goal (bestEffort)";
        else if (System.currentTimeMillis() - plan.createdMs > PreviewSearch.TTL_MS) why = "the preview is older than 60 s";
        else {
            adopted = WalkerPlanAdoption.adopt(process.walker(), plan.legs.get(0), world, Minecraft.getInstance().player.blockPosition());
            if (!adopted) why = "the walker refused the route: the bot is no longer near its start, or its first stretch is no longer walkable";
        }
        GotoContracts.Adoption adoption = new GotoContracts.Adoption(planId, adopted,
                java.util.Optional.ofNullable(why == null ? null : why + "; searched normally instead"));
        return new GotoOrders.Start(process, new GotoContracts.Trip(plan.goals.get(plan.goals.size() - 1).toString(),
                plan.goals.size() - 1, GotoContracts.Travel.WALK, java.util.Optional.of(adoption)));
    }

    /**
     * Walk a stored plan from {@code start} with no re-planning ({@code mc.debug.replay}, replan false). The
     * caller has restored the terrain and teleported the player. Client thread only; logic in
     * {@link ReplayInstaller}.
     */
    public void startReplay(List<BlockPos> plan, List<Move.Edge> edges, BlockPos start, Goal endGoal,
                            BlockPos startFoot, String archiveName) {
        ReplayInstaller.startReplay(this, plan, edges, start, endGoal, startFoot, archiveName);
    }

    /**
     * Re-issue a recorded goal from {@code start} with normal planning ({@code mc.debug.replay}, the
     * default). The caller has restored the terrain and teleported the player. Client thread only.
     */
    public void startReplayReplan(BlockPos start, Goal goal) {
        ReplayInstaller.startReplayReplan(this, start, goal);
    }

    @Override
    public ControlContracts.WaypointReply waypoint(ControlContracts.WaypointOp op) {
        return switch (op) {
            case ControlContracts.Save s -> {
                BlockPos pos;
                if (s.pos().isPresent()) {
                    pos = blockPos(s.pos().get());
                } else {
                    LocalPlayer pl = Minecraft.getInstance().player;
                    if (pl == null) throw new IllegalStateException("no pos given and no player to take one from");
                    pos = blockPosOf(pl.getX(), pl.getY(), pl.getZ());
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

    @Override
    public void engage(CombatContracts.Params p, ActionHandle action) {
        Ticket ticket = ActionTickets.of(action);
        action.bindStopper(why -> Minecraft.getInstance().execute(() -> combatChain.cancelAction(ticket)));
        combatChain.engage(VerbOrders.mode(p.mode()), VerbOrders.entityId(p), VerbOrders.entityType(p), p.force(), ticket,
                new CombatContracts.Fought(p.mode(), p.target()));
    }

    @Override
    public ImmediateResponse equip(InteractContracts.EquipParams params) {
        boolean armorOnly = params.armorOnly() || params.profile() == InteractContracts.Profile.ARMOR;
        LocalPlayer player = Minecraft.getInstance().player;
        AutoEquip.Result r = AutoEquip.equipBest(Minecraft.getInstance(), player, !armorOnly, BotConfig.equipDurabilityThreshold);
        return new Responses.Equipped(armorOnly ? InteractContracts.Profile.ARMOR : params.profile(), r.equipped(),
                new InteractContracts.Loadout(slot(r, "head"), slot(r, "chest"), slot(r, "legs"), slot(r, "feet"), slot(r, "mainHand")),
                r.lowDurability(),
                r.missing().stream().map(s -> InteractContracts.ArmorSlot.valueOf(s.toUpperCase(java.util.Locale.ROOT))).toList());
    }

    private static Optional<String> slot(AutoEquip.Result r, String name) {
        return r.loadout().get(name) instanceof String id ? Optional.of(id) : Optional.empty();
    }

    @Override public Map<String, Object> bodyRefusal() {
        return onClient(() -> {
            BodyReady.Refusal r = BodyReady.refusal();
            return r == null ? null : r.result();
        });
    }

    @Override public Map<String, Object> status() {
        Map<String, Object> snap = state.snapshot();
        snap.put("paused", paused);
        // Snapshot the volatile field to a local; the client tick thread may
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
        // Pathfinder stats from the most recent A* run (any process). Useful
        // for debugging "why can't the bot get there" — exposes expansion
        // count, wall-clock ms, whether the goal was reached vs. fallback.
        Walker.PathStats ps = Walker.lastStats;
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
        // player-death hook, Task 5) can see chain-internal state that outlives any
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
        snap.put("clutch", CLUTCH.phase());
        return snap;
    }

    @Override public ControlContracts.BodyStatus bodyStatus() {
        BotProcess c = userTask.process();
        Map<String, ControlContracts.ChainState> chains = new LinkedHashMap<>();
        Map<String, Float> prios = scheduler.lastPriorities();
        for (Chain ch : scheduler.chains()) {
            Float pr = prios.get(ch.name());
            ProcessScheduler.Bail bail = scheduler.bailOf(ch);
            chains.put(ch.name(), new ControlContracts.ChainState(pr == null ? 0 : pr, Optional.ofNullable(ch.episodePhase()),
                    bail == null ? Optional.empty() : Optional.of(new ControlContracts.Bail(bail.reason(), bail.ticksLeft()))));
        }
        Walker.PathStats ps = Walker.lastStats;
        Optional<ControlContracts.PathStats> lastPath = ps == null ? Optional.empty()
                : Optional.of(new ControlContracts.PathStats(ps.expanded(), ps.ms(), ps.goalReached(), ps.finalCost(), ps.pathLen(),
                        ps.sightBudgetExhausted(), ps.snapshotTruncated()));
        ControlContracts.Reflexes reflexes = new ControlContracts.Reflexes(paused,
                c == null ? Optional.empty() : Optional.ofNullable(c.statusDetail()),
                Optional.ofNullable(scheduler.currentName()), c != null && scheduler.current() != userTask, chains, lastPath,
                String.valueOf(CLUTCH.phase()),
                new ControlContracts.CombatTally(state.combatSwings, state.combatWellTimed, state.combatCrits, state.combatKills));
        return new ControlContracts.BodyStatus(net.magicterra.worlddriver.bot.BodyRegistry.SELF, c != null,
                c == null ? Optional.empty() : Optional.of(c.kind()), StatusView.end(userTask.lastEnd()),
                StatusView.slots(state), Optional.of(reflexes));
    }

    @Override public List<String> cancel(String which) {
        return onClient(() -> {
            List<String> hits = new ArrayList<>();
            // Combat lives in its own chain, not the user-task slot — cancel it directly.
            if ("all".equals(which) || "combat".equals(which)) {
                if (combatChain.engaged()) {
                    combatChain.standDown();
                    state.combat.lastError = "user-cancel";
                    state.combat.active = false;
                    hits.add("combat");
                }
            }
            // gap#68-⑦/⑫: "cancel all" stays a best-effort broadcast —
            // every chain episode + the user slot + the orphan backfill queue.
            if ("all".equals(which)) {
                cancelCurrent("user-cancel");
                scheduler.cancelAllEpisodes("user-cancel");
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
            if (plan.cancelUserProcess()) cancelCurrent("user-cancel");
            for (Chain target : plan.episodeTargets()) target.cancelEpisode("user-cancel");
            hits.addAll(plan.labels());
            return List.copyOf(hits);
        });
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

    @Override public boolean paused() { return paused; }

    @Override
    public void setPaused(boolean pause) {
        if (pause && !paused) releaseKeys();   // a key held at the moment of pausing would stay down
        paused = pause;
    }

    /** A test affordance for the flight-handling path: it sets the abilities as the creative toggle would. */
    @Override
    public void debugFly(boolean flying) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw new IllegalStateException("debugFly needs this client's player in a world");
        p.getAbilities().mayfly = true;
        p.getAbilities().flying = flying;
        p.onUpdateAbilities();
    }

    @Override public void forgetBackfill() { backfillTracker.clear(); }

    /**
     * {@code mc.test.reset} client-pool entry reset — see {@link BotApi#resetClientEntry()}.
     * Reuses the existing client primitives (no new behaviour): {@link BotInteract#releaseKeys()},
     * the {@code mc.client.screen.close} {@code setScreen(null)} path, {@link ClientChatLog#clear()}
     * and the scheduler's user-slot cancel. The {@code reset[]} list names exactly what changed so
     * P2b's reuse acceptance can diff it. Kept minimal — completeness is P2b's acceptance concern.
     *
     * <p><b>Every token reports an EFFECT, and that is a correction.</b> {@code chat:N} always did;
     * {@code keys} did not — it was appended after {@link BotInteract#releaseKeys()} returned, so it
     * said the release code ran and nothing about whether anything was released, and a
     * {@code releaseKeys()} that became a no-op would have kept it green forever. It is now
     * {@code keys:<names>}, read off {@link KeyMapping#isDown()} on the same client hop, before and
     * after; it is absent when nothing was down. {@code screen} likewise names the screen class it
     * closed, so "there was no screen" and "the close did nothing" stop reading alike.
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
        // The look slot is the user-task slot (mc.bot.lookAt{smoothLook:true} starts a
        // LookProcess there); UserTaskChain.heldProcessKind() is deliberately null
        // (cancel routes the user slot through a step of its own), so read the held process
        // directly and cancel
        // through the same path mc.bot.cancel uses.
        onClient(() -> {
            // Every explicit task ends at a test boundary, not only a look: a goto left running would
            // keep driving the body through the next scene.
            BotProcess held = userTask.process();
            if (held != null) {
                userTask.cancel("mc.test.reset", h -> h.cancelled(Ticket.Stop.TEST_RESET));
                reset.add(held.kind());
            }
            if (combatChain.engaged()) {
                combatChain.standDown(h -> h.cancelled(Ticket.Stop.TEST_RESET));
                state.combat.lastError = "mc.test.reset";
                state.combat.active = false;
                reset.add("combat");
            }
            Minecraft mc = Minecraft.getInstance();
            String wereDown = heldKeyNames(mc);
            releaseKeys();
            String stillDown = heldKeyNames(mc);
            // Named rather than counted, and the surviving names named too: "keys:up" and
            // "keys:up→still:up" are the difference between a release that worked and one that
            // did not, and a bare count cannot tell them apart.
            if (!wereDown.isEmpty())
                reset.add("keys:" + wereDown + (stillDown.isEmpty() ? "" : "→still:" + stillDown));
            if (mc.screen != null) {
                String was = mc.screen.getClass().getSimpleName();
                mc.setScreen(null);
                reset.add("screen:" + was + (mc.screen == null ? "" : "→still:"
                        + mc.screen.getClass().getSimpleName()));
            }
            return Map.of();
        });
        int chatCleared = ClientChatLog.clear();   // pure JVM buffer, no client thread needed
        reset.add("chat:" + chatCleared);
        return List.copyOf(reset);
    }

    /** The movement keys {@link BotInteract#releaseKeys()} clears that are down right now, comma
     *  separated in the same order {@link #heldKeys()} reports them. Client thread only — the
     *  caller is already inside the hop. */
    private static String heldKeyNames(Minecraft mc) {
        if (mc.options == null) return "";
        StringBuilder sb = new StringBuilder();
        appendIfDown(sb, "up", mc.options.keyUp);
        appendIfDown(sb, "down", mc.options.keyDown);
        appendIfDown(sb, "left", mc.options.keyLeft);
        appendIfDown(sb, "right", mc.options.keyRight);
        appendIfDown(sb, "jump", mc.options.keyJump);
        appendIfDown(sb, "sprint", mc.options.keySprint);
        if (ClientIntents.digHeld()) { if (!sb.isEmpty()) sb.append(','); sb.append("attack"); }
        appendIfDown(sb, "shift", mc.options.keyShift);
        return sb.toString();
    }

    private static void appendIfDown(StringBuilder sb, String name, KeyMapping key) {
        if (!key.isDown()) return;
        if (!sb.isEmpty()) sb.append(',');
        sb.append(name);
    }

    /**
     * {@code mc.test.input.heldKeys} — see {@link BotApi#heldKeys()}. Reads
     * {@link KeyMapping#isDown()} on the client thread for exactly what
     * {@link net.magicterra.worlddriver.game.body.BotInteract#releaseKeys()} clears, in the same
     * order, under the reply names {@code up/down/left/right/jump/sprint/attack/shift}.
     * {@code attack} is the bot's dig latch ({@link ClientIntents#digHeld()}), not a keybind —
     * the bot stopped pressing the attack key on 2026-09-14 — but it keeps its slot so the
     * instrument's shape is stable. Pure observation — mutates nothing.
     */
    @Override
    public TestInputContracts.HeldKeys heldKeys() {
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options == null) throw new IllegalStateException("no client options");
            return new TestInputContracts.HeldKeys(mc.options.keyUp.isDown(), mc.options.keyDown.isDown(),
                    mc.options.keyLeft.isDown(), mc.options.keyRight.isDown(), mc.options.keyJump.isDown(),
                    mc.options.keySprint.isDown(), ClientIntents.digHeld(), mc.options.keyShift.isDown());
        });
    }

    /**
     * {@code mc.test.input.useOnBlock} — see {@link BotApi#useOnBlock}. Instrument-grade:
     * the ONLY state change is the {@code gameMode.useItemOn} right-click itself. The face is
     * the one nearest the player's eye ({@code faceTowardEye}) and the hit Vec3 is that
     * face's centre — the exact synthetic-hit shape {@code mc.bot.useItemOn} builds — but unlike
     * the behaviour verb it does NOT aim (no yaw/pitch write), NOT move, and NOT toggle sneak, so
     * nothing in the path/aim pipeline runs. Opening a block-entity container (empty hand +
     * right-click) does not need any of those.
     */
    @Override
    public TestInputContracts.UsedOnBlock useOnBlock(TestInputContracts.UseOnBlock params) {
        final InteractionHand hand = HandWords.hand(params.hand());
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null) throw new IllegalStateException("no local player");
            BlockPos block = blockPos(params.pos());
            Direction face = faceTowardEye(block, p);
            BlockHitResult hit = new BlockHitResult(HandVerbs.faceCentre(block, face), face, block, false);
            InteractionResult r = mc.gameMode.useItemOn(p, hand, hit);
            if (r.consumesAction()) p.swing(hand);
            return new TestInputContracts.UsedOnBlock(HandWords.interaction(r), r.consumesAction(),
                    params.hand(), InteractContracts.Face.valueOf(face.name()));
        });
    }

    /** Driver→agent client-tick push-event detection (threat/hurt/death, fluid
     *  entry, day-phase, item pickup, advancements, chat/title, scene edges).
     *  Owns its own cross-tick edge state; {@link #clientTick()} drives it. */
    private final ClientEventDetector eventDetector = new ClientEventDetector();

    /**
     * Whether a foreground builder process should silence the ambient use-key reflexes.
     *
     * <p><b>Not intent ownership</b>, despite how it reads. Six process kinds answer
     * {@code "builder"} (Build/Bridge/Tower/Backfill/BboxFill/Farm) and NONE of them
     * holds the use intent — they place through {@link net.magicterra.worlddriver.game.body.BotInteract}
     * → {@code gameMode.useItemOn} directly. What this suppresses is an ambient's held
     * use firing a SECOND use-action on the tick a builder places, which is
     * why it gates the reflexes instead of handing the intent over. (Its previous name,
     * {@code processOwnsUseKey}, claimed an ownership that never existed.)
     *
     * <p>The use intent ({@link ClientIntents#holdUse}) is the one shared input
     * {@code BotInteract.releaseKeys()} deliberately omits — the idle release runs AFTER the
     * shield/heal/eat reflexes set it — so the arbitration below is the ONLY thing keeping it
     * from leaking, and every acquirer must join it or self-clear on every exit path.
     * {@code UseKeyOwnershipTest} pins the acquirer set against exactly that drift.
     */
    private static boolean builderSuppressesAmbients(BotProcess c) {
        return c != null && c.kind().equals("builder");
    }

    /**
     * The two policies that key off "is the bot currently driving", applied at the very top of
     * every client tick — ahead of all of {@code clientTick}'s early returns (no world, bot
     * paused, clutch owning the tick), because each one's FALLING edge has to be honoured on
     * exactly those ticks too. Handing the cursor back, or handing the human's pause setting
     * back, must not be skipped just because there is nothing else to do this tick.
     *
     * <ul>
     *   <li><b>Mouse coexistence</b> — marked from the PREVIOUS tick's ownership
     *       ({@code scheduler.current()} is last tick's decision): a 1-tick lag on a 20-tick
     *       linger, so it never flickers.</li>
     *   <li><b>Focus</b> — while the bot drives, vanilla's pause-on-lost-focus is suppressed so
     *       an alt-tab cannot freeze the world mid-task (see {@link FocusPolicy}).</li>
     * </ul>
     */
    private void applyTakeoverPolicies(Minecraft mc) {
        String mouseDriver = scheduler.currentName();
        if (mouseDriver == null) mouseDriver = state.activeName();
        boolean botDriving = mouseDriver != null && !paused;
        if (botDriving) MouseYield.markDriving(mouseDriver);
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
        eventDetector.detectDeath(mc, () -> {
            cancelAllProcesses("player-death");
            scheduler.cancelAllEpisodes("player-death");   // reset every chain's episode state
            backfillTracker.clear();                        // drop the orphaned backfill queue
            combatChain.suppressAutoFor(BotConfig.respawnGraceTicks);
            respawnGraceLeft = BotConfig.respawnGraceTicks;
        });
        // autoRespawn fires even when level/player is in the dying transition —
        // DeathScreen shows briefly with mc.player still alive but at 0 HP, and
        // we want to skip past it ASAP. Done first so the rest of the tick sees
        // the post-respawn world state.
        if (BotConfig.autoRespawn) AutoRespawn.tick(mc);
        if (mc.level == null || mc.player == null) { if (releaseGate.consumeRelease()) releaseKeys(); return; }
        if (paused) { if (releaseGate.consumeRelease()) releaseKeys(); return; }
        // Update the perception blackboard every tick so mc.client.scene always
        // serves the freshest client-authoritative snapshot.
        worldModel.update(mc, world, state);
        // A route preview in progress gets its thin slice here, beside whatever walks.
        preview.tick();
        // Rising-edge scene events: emit duskExposed / cornered once per
        // false→true transition so the Agent learns of these without polling.
        // Mirrors the fluid-entry (player.enteredWater/enteredLava) pattern:
        // api.emitExternal → the same event stream all client-tick events use.
        eventDetector.detectSceneEvents(worldModel);
        // Always-on water-bucket clutch (survival first): arm reactively on any
        // unplanned damaging fall, and once armed OWN the descent before any
        // process runs. While the clutch is driving the fall (placing/scooping
        // water) it holds the keys and we return early, so an active goto/mine/
        // build is suspended for those airborne ticks instead of fighting it. A
        // planned fallBucket fall is armed by the Walker as it steps off the lip
        // and drives the same descent here. armReactive gates on
        // allowWaterBucketFall INTERNALLY (not here) so a dangerous fall with the
        // flag off can still raise the CLUTCH-noArm alarm instead of vanishing.
        CLUTCH.armReactive(mc, world);
        // The clutch owns the whole tick when it fires (look-down + place + return), so
        // it is a bot drive in its own right — mark it for the mouse gate, which no
        // process slot would report (the clutch is slot-less by design).
        if (CLUTCH.tick(mc, world)) { releaseGate.markDirtied(); MouseYield.markDriving("clutch"); return; }
        // Screen watchdog (gap #58): an unexpectedly-open container GUI swallows
        // every movement input, paralysing the walker AND all reflex chains (live
        // death #3: a stray place-click opened a FurnaceScreen; the pinned walker's
        // futile-search then misreported "goal unreachable" while a zombie chewed
        // the defenseless bot). Craft/smelt legitimately hold their station screens
        // (craft.active/smelt.active gate), and one grace second tolerates screens
        // a verb opened deliberately; anything else that lingers while a movement
        // process wants the input channel gets closed and reported.
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                && !state.craft.active && !state.smelt.active
                && (state.mc_goto.active || state.mine.active || state.builder.active
                    || state.follow.active || state.explore.active || state.runAway.active
                    || state.retreat.active || state.escape.active || state.combat.active)) {
            if (++screenBlockTicks >= 20) {
                String type = mc.screen.getClass().getSimpleName();
                mc.player.closeContainer();
                mc.setScreen(null);
                eventEmitter.emit("screen.autoClosed", mc.player.blockPosition(), java.util.Map.of(
                                "screen", type, "blockedTicks", (double) screenBlockTicks));
                LOG.warn(
                        "[screenWatchdog] closed stray {} after {} blocked ticks", type, screenBlockTicks);
                screenBlockTicks = 0;
            }
        } else {
            screenBlockTicks = 0;
        }
        // Refresh the shared threat picture once per tick — reflex chains
        // (panic/dodge) and the use-key arbiter (shield) all read it below.
        ClientThreatScanner.refresh(mc);
        // Driver→agent push: emit one-shot events on threat-appeared / player-hurt
        // / player-death transitions (client-sensed; no server-side equivalent for
        // threats, and this is the player the agent actually controls).
        eventDetector.detectClientEvents(mc);
        // Driver→agent push: surface new chat / system / command-result lines
        // (packet-level ClientChatLog drain) and action-bar / title text (guarded
        // reflection) — no server-side hook in client-MCP mode. Both paths are
        // guarded; never breaks the tick.
        eventDetector.detectClientMessages(mc);
        // Ambient hand/equipment/hotbar gating reads the foreground user process
        // (a preempting survival/combat chain leaves it held but suspended).
        BotProcess c = userTask.process();
        // Use-intent arbitration (Phase B): shield > heal > eat, one holder per tick,
        // the losers release. Why a builder silences all three — and what keeps this
        // protocol whole at all — is on builderSuppressesAmbients.
        if (builderSuppressesAmbients(c)) {
            autoShield.release(mc); autoHeal.release(mc); autoEat.releaseIfActive(mc);
        } else {
            ThreatScanner.Scan scan = ClientThreatScanner.current(mc);
            if (BotConfig.autoShield && autoShield.wants(mc, mc.player, scan)) {
                autoHeal.release(mc); autoEat.releaseIfActive(mc);
                autoShield.engage(mc, mc.player, scan);
            } else if (BotConfig.autoHeal && autoHeal.wants(mc, mc.player)) {
                autoShield.release(mc); autoEat.releaseIfActive(mc);
                autoHeal.engage(mc, mc.player);
            } else if (BotConfig.autoEat) {
                autoShield.release(mc); autoHeal.release(mc);
                autoEat.tick(mc, mc.player);
            } else {
                autoShield.release(mc); autoHeal.release(mc); autoEat.releaseIfActive(mc);
            }
        }
        // autoTotem owns the offhand slot (not the use key) — runs concurrently.
        if (BotConfig.autoTotem) autoTotem.tick(mc, mc.player);
        // autoEquip (Phase F T0): gear up the moment combat starts and keep the
        // best armor/weapon on through the fight. Gated on an engaged combat chain
        // so it doesn't reshuffle the inventory during peaceful crafting/building;
        // idempotent (worn gear outscores the inventory) so it settles after a tick.
        if (BotConfig.autoEquip && combatChain.engaged()) {
            AutoEquip.tick(mc, mc.player, BotConfig.equipDurabilityThreshold);
        }
        // autoSwim is applied AFTER the chain scheduler's idle releaseKeys()
        // below — see the note there. (Running it here was a no-op for an idle
        // bot: the idle releaseKeys() clobbered the jump key every tick, so a
        // submerged idle bot never surfaced and drowned with autoSwim "on".)
        // autoTool only fires when nothing owns hotbar selection — MineProcess
        // / BboxFillProcess / BuildProcess / FarmProcess and the reflex chains all
        // manage hotbar themselves and would fight us. So this is essentially "swap
        // to best tool when the player is manually mining" (or scripted-attack via
        // input.click), the same scope as Baritone's autoTool.
        if (BotConfig.autoTool && AutoTool.mayRun(c, scheduler.current() != null)) {
            AutoTool.tick(mc, mc.player);
        }
        // autoBackfill puts back only what the bot broke — every process's digs, taken here
        // whoever drove them — never the natural air it walked through.
        backfillTracker.onClientTick(BotConfig.autoBackfill, ClientIntents.takeOwnBreaks());
        BlockPos foot = mc.player.blockPosition();
        // Auto-start BackfillProcess when idle + setting on + a cell is waiting.
        // Matches Baritone's BackfillProcess.isActive() trigger pattern: it
        // only runs when no higher-priority process wants the slot. The
        // process self-terminates once its work is done.
        if (respawnGraceLeft > 0) respawnGraceLeft--;
        if (c == null && respawnGraceLeft == 0 && BotConfig.autoBackfill
                && BackfillProcess.autoStartWanted(backfillTracker, foot, mc.level)) {
            userTask.setAmbientProcess(new BackfillProcess(backfillTracker));
            paused = false;
        }
        // Movement channel: run the highest-priority chain (user task, or a
        // survival/combat chain preempting it). When every chain sits out, the
        // bot is idle — release the keys, matching the old single-process path.
        // Reset the flee-context flag first: RunAwayProcess.tick (driven below by
        // the scheduler) re-sets it true right before its A* search, so fleeSearch
        // snapshots true only for an active flee and is never stuck-true.
        WorldConfig.fleeActive = false;
        ControlConfig.walkerDigActive = false;   // same per-tick reset contract as fleeActive: the Walker re-sets it below while holding a dig
        ControlConfig.walkerCruiseActive = false;   // likewise: the Walker re-sets it below while its surface cruise holds the eyes under
        // Capture BEFORE the tick: a chain that runs this tick presses movement
        // keys even if it finishes mid-tick (current() then nulls) — its trailing
        // presses still need the one-shot cleanup below. A user chain holding nothing
        // ended its process last tick and settled that process's keys itself (released
        // them, or never took them), so it leaves nothing to clean up.
        Chain driving = scheduler.current();
        boolean schedulerDroveThisTick = driving != null && (driving != userTask || userTask.process() != null);
        // The scheduler works on Body instances; this tick chain is the client's, so the
        // controlled player is the local player. Built fresh per tick, like every other ClientPlayerBody (see clientAvatar()).
        scheduler.tick(new net.magicterra.worlddriver.game.body.ClientPlayerBody(mc), world, state);
        // Immediately after the chain has had its turn, so a caller polling on the next server
        // tick sees the ending rather than one tick of stale "still busy".
        settleLeg();
        if (schedulerDroveThisTick) releaseGate.markDirtied();
        // Edge-clear: only release the bot's OWN trailing presses, never the
        // human's keys on the steady idle stream (the manual-input clobber bug).
        if (scheduler.current() == null && releaseGate.consumeRelease()) releaseKeys();
        // autoSwim LAST: drowning backstop. Must run after the idle releaseKeys()
        // above — otherwise that call clears the jump key and an idle underwater
        // bot never surfaces (GAP #7). But idle-only was still too narrow (GAP #9):
        // a movement process can be ACTIVE yet STUCK in water — e.g. runAway boxed
        // into a flooded pit with no escape path (expanded≈2, Walker not stepping),
        // so its own swim-up never fires AND the idle branch is gated off by the
        // active process → the bot drowns mid-process. So hold jump whenever the
        // head is submerged, regardless of process state. Rising is compatible with
        // a walking Walker (both want the surface), and we only ever ADD lift here —
        // the release branch (surface transition) stays idle-gated so we never
        // clobber a land jump the Walker set.
        // Idle drowning reflex runs regardless of the autoSwim flag — gated by its own
        // independent BehaviorConfig.autoFloatWhenDrowning (gap#70, live death #18: a bot with
        // no task sank and drowned with zero self-rescue; this used to be alarm-only and
        // is now an unconditional PURE-VERTICAL float, never movement — see AutoSwim
        // .drowningSentinel's doc for the controller's idle-passivity ruling). Mark the
        // release gate dirty when it actually held jump so the idle release path clears
        // that trailing key once air recovers, same bookkeeping as autoSwim's lift below.
        if (mc.player != null && AutoSwim.drowningSentinel(mc, mc.player, scheduler.current() == null))
            releaseGate.markDirtied();
        // gap#76: while DrownEscapeChain holds the channel the contract is PURE
        // VERTICAL (hold jump only) — AutoSwim.tick's in-process backstop would
        // re-add its near-surface shore-steer (keyUp + yaw) right after the
        // chain's tick zeroed it, so it is skipped for exactly that chain.
        if (BotConfig.autoSwim && mc.player != null && mc.player.isInWater()
                && !DrownEscapeChain.NAME.equals(scheduler.currentName())) {
            // Lift while submerged AND (when idle) actively swim to the nearest
            // shore — a bot that respawned/fell into a lake with no process used
            // to bob until it drowned (spawn-water death-loop). Steering is
            // idle-gated inside AutoSwim so it never fights an active goto's own
            // water-escape moves.
            AutoSwim.tick(mc, mc.player, world, scheduler.current() == null);
            // autoSwim holds keyJump while submerged; mark dirty so the gate clears
            // that trailing jump once it surfaces (it self-clears keyUp but not jump).
            releaseGate.markDirtied();
        }
        // Suffocation backstop: when sand caves into the bot's head while it digs a
        // disturbed pit (bunker/goto/escape all hit this), break the eye block so it
        // can't be suffocated to death mid-dig. Unconditional like autoSwim's lift —
        // runs after the scheduler so it overrides a digging process's aim ONLY while
        // the head is actually choking, then hands control straight back.
        if (mc.player != null) AntiSuffocate.tick(mc, mc.player);
        // Contact-damage backstop (death #14, cactus): while a damaging BLOCK is
        // grinding the hull (cactus/berry bush/fire/magma), face away and step out
        // of contact. LLM latency can never beat a 2 Hz contact tick, and the
        // entity-attribution reflexes never fire for block damage. Same
        // idle-passivity carve-out as drowningSentinel/hurt-entry: reacting to
        // taking damage is a survival reflex, not uncommanded movement.
        if (mc.player != null) ContactDamageEscape.tick(mc, mc.player);
        // Lava-front backstop (deaths #27/#29/#30): a FLOWING lava cell beside
        // the feet means the front arrives within ~1.5s — walk away NOW. The
        // path brakes prevent walking/falling INTO the flow; this one prevents
        // dying in place while the planner deliberates.
        if (mc.player != null) net.magicterra.worlddriver.bot.behavior.reflex.LavaProximityEscape.tick(mc, mc.player);
        // Keep the combat status slot's liveness in sync with the chain, so a status poll reads the
        // fight's end the moment it comes. Counters/goal/lastError persist for post-mortem.
        state.combat.active = combatChain.engaged();
        // STREAM-GRADE CAMERA (AIRI): single chokepoint, AFTER every actuator above
        // has written the player's rotation. Rate-limits this tick's net yaw/pitch
        // change so no code path can snap the view; a functional exact aim this tick
        // (camera-raycast mine/attack, a leap heading) bypasses it via
        // LookController.requestSnap(). See GameConfig.cameraSlew.
        net.magicterra.worlddriver.game.body.LookController.apply(mc.player);
    }

    /** Start a foreground user task. The process lifecycle now lives in
     *  {@link UserTaskChain}; this stays as the single entry point the verbs call.
     *  Package-private so extracted command/installer classes in this package
     *  (e.g. {@link ReplayInstaller}, {@link InteractionCommands}) can start tasks. */
    void startProcess(BotProcess next) {
        userTask.setProcess(next);
        paused = false;
    }

    /** {@link #startProcess} for an accepted action, which the user task chain ends with the process. Client thread only. */
    @Override public void startAction(BotProcess next, ActionHandle action) {
        userTask.setProcess(next, ActionTickets.of(action));
        paused = false;
    }

    /** Client thread only. */
    @Override public boolean cancelAction(ActionHandle action, String reason) {
        Ticket ticket = ActionTickets.of(action);
        return userTask.cancelAction(ticket, reason) || combatChain.cancelAction(ticket);
    }

    @Override public net.magicterra.worlddriver.api.thread.ServerThreadHop clientHop() {
        return net.magicterra.worlddriver.game.ClientHop.hop();
    }

    @Override public java.util.concurrent.Executor clientExecutor() { return Minecraft.getInstance(); }

    @Override public net.minecraft.world.entity.LivingEntity selfEntity() { return Minecraft.getInstance().player; }

    /**
     * The client left its server. Whatever the bot was doing there ends now rather than resuming in
     * the next world, which would act on a world the caller never asked about. Client thread only.
     */
    public void onDisconnect() {
        if (combatChain.engaged()) {
            combatChain.standDown(h -> h.cancelled(Ticket.Stop.CONNECTION_CLOSED));
            state.combat.lastError = "disconnected";
            state.combat.active = false;
        }
        userTask.cancel("disconnected", h -> h.cancelled(Ticket.Stop.CONNECTION_CLOSED));
        scheduler.cancelAllEpisodes("disconnected");
        backfillTracker.clear();
    }

    // === the object seam: run a caller's own BotProcess on the real player ======
    // See BotApi for why these two are not routes. They are the in-JVM entry point the
    // playthrough ladder uses to climb on the client's real player instead of spawning a
    // headless FakePlayer beside it.

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
    private final java.util.concurrent.atomic.AtomicLong legSeq = new java.util.concurrent.atomic.AtomicLong();

    /** The process {@link #runProcess} handed to the chain, once it is really in it.
     *  <b>Client thread only</b> — written by the installer, read by {@link #settleLeg}. */
    private BotProcess installedLeg;
    private long installedLegSeq;

    @Override public Map<String, Object> runProcess(BotProcess process) {
        long seq = legSeq.incrementAndGet();
        // Published from the CALLER's thread, before the install is even enqueued. This is what
        // closes the window the caller polls in: between "enqueued" and "installed" the chain
        // holds nothing, and a reader that asked the chain would conclude the task had already
        // finished — so every drive would return instantly having moved nothing.
        leg = new Leg(seq, true, process.kind(), null);
        Runnable install = () -> {
            startProcess(process);
            installedLeg = process;
            installedLegSeq = seq;
        };
        Minecraft mc = Minecraft.getInstance();
        // mc.execute, deliberately NOT BotUtil.onClient: onClient waits on a future, and the
        // caller this exists for is the server thread of an INTEGRATED server — the same JVM, the
        // other side of a tick handshake. Blocking it for a client frame is the stall a
        // fire-and-forget start exists to avoid.
        if (mc.isSameThread()) install.run(); else mc.execute(install);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        out.put("kind", process.kind());
        out.put("seq", seq);
        return out;
    }

    /**
     * Close out a user task whose process has left the chain. <b>Client thread only</b>, once per
     * tick.
     *
     * <p>The test is the process OBJECT, not "is the chain empty": the chain is empty during the whole
     * window between enqueue and install too, and closing the task there is the false-completion
     * this seam exists to prevent.
     */
    private void settleLeg() {
        BotProcess mine = installedLeg;
        if (mine == null || userTask.process() == mine) return;
        // A newer task was published (runProcess from the caller's thread) while this one was still
        // installed — the next scene's goto enqueued behind the last scene's one-tick HoldStill.
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

    @Override public Map<String, Object> userTaskLeg() {
        Leg now = leg;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", now.seq());
        out.put("busy", now.busy());
        out.put("kind", now.kind());
        out.put("error", now.error());
        return out;
    }

    /**
     * Built fresh per call rather than cached, because {@code ClientPlayerBody} binds
     * {@code mc.player} in its constructor and that reference dies on every respawn and dimension
     * change. A cached one would keep actuating a stale player — the same "the world view does not
     * follow the player entity" shape the driver has already paid for once, where a view built at construction planned over the old
     * dimension's terrain for every rung after the portal.
     */
    @Override public net.magicterra.worlddriver.game.body.Body clientAvatar() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : new net.magicterra.worlddriver.game.body.ClientPlayerBody(mc);
    }

    private void cancelCurrent(String reason) {
        userTask.cancel(reason);
    }

    /** Cancel every active bot process — the user-task slot (goto/mine/craft/…)
     *  and the combat chain. Mirrors {@link #cancel} with which="all"; used on
     *  the player-death edge so a respawn doesn't resume a lethal action. The
     *  always-on ClutchController is intentionally left running. */
    private void cancelAllProcesses(String reason) {
        if (combatChain.engaged()) {
            combatChain.standDown(h -> h.fail("the body died (" + reason + ")"));
            state.combat.lastError = reason;
            state.combat.active = false;
        }
        // Only the death edge calls this: the task failed with its body, nobody cancelled it.
        userTask.cancel(reason, h -> h.fail("the body died (" + reason + ")"));
    }

    // === WorldView impl ======================================================

}
