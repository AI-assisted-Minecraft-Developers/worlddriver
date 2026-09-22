package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.model.Params;
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
import net.magicterra.worlddriver.client.internal.ClientChatLog;

import net.magicterra.worlddriver.bot.movement.ClientIntents;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.movement.WalkerPlanAdoption;
import net.magicterra.worlddriver.bot.pathfinder.SearchScope;
import net.magicterra.worlddriver.bot.process.*;
import net.magicterra.worlddriver.bot.scheduler.BunkerChain;
import net.magicterra.worlddriver.bot.scheduler.CancelRouting;
import net.magicterra.worlddriver.bot.scheduler.Chain;
import net.magicterra.worlddriver.bot.scheduler.CombatChain;
import net.magicterra.worlddriver.bot.scheduler.DodgeChain;
import net.magicterra.worlddriver.bot.scheduler.DrownEscapeChain;
import net.magicterra.worlddriver.bot.scheduler.DuskSecureChain;
import net.magicterra.worlddriver.bot.scheduler.PanicChain;
import net.magicterra.worlddriver.bot.scheduler.ProcessScheduler;
import net.magicterra.worlddriver.bot.scheduler.RetreatChain;
import net.magicterra.worlddriver.bot.scheduler.UserTaskChain;

import static net.magicterra.worlddriver.bot.GoalResolver.*;
import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.magicterra.worlddriver.bot.auto.AutoEat;
import net.magicterra.worlddriver.bot.auto.AutoEquip;
import net.magicterra.worlddriver.bot.auto.AutoShield;
import net.magicterra.worlddriver.bot.auto.AutoHeal;
import net.magicterra.worlddriver.bot.auto.AutoTotem;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.combat.ClientThreatScanner;
import net.magicterra.worlddriver.bot.auto.AutoTool;
import net.magicterra.worlddriver.bot.auto.AutoSwim;
import net.magicterra.worlddriver.bot.auto.AntiSuffocate;
import net.magicterra.worlddriver.bot.auto.ContactDamageEscape;
import net.magicterra.worlddriver.bot.auto.AutoRespawn;
import net.magicterra.worlddriver.bot.world.WorldModel;
import java.util.concurrent.ConcurrentHashMap;

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
    @Override public WorldModel worldModel() { return worldModel; }
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
    {
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
        // 挖三填一 emergency dig-in, registered but DEFAULT-OFF (autoBunker=false):
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
        scheduler.register(new DuskSecureChain(state, worldModel)); // 40 — idle dusk shelter
    }
    volatile boolean paused;
    /** Gates the idle {@code releaseKeys()} so it only fires after the bot itself
     *  pressed a movement keybind — never on the steady idle stream that used to
     *  clobber a human player's held WASD/space when no agent was driving. See
     *  {@link net.magicterra.worlddriver.bot.movement.InputReleaseGate}. */
    private final net.magicterra.worlddriver.bot.movement.InputReleaseGate releaseGate =
            new net.magicterra.worlddriver.bot.movement.InputReleaseGate();
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

    /** Records cells the player's foot passed through while {@link BotConfig#autoBackfill}
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
    private static net.magicterra.worlddriver.bot.pathfinder.ScopeSource scopeOf(LocalPlayer player) {
        return (s, g, prof) -> SearchScope.gather(player.level(), player.getId(), s, g, prof);
    }

    /**
     * The nearest entity of {@code typeId} this client has loaded, for {@code goto entity:}. It reads
     * the client's render list, so it lives here and not in {@link GotoGoalResolver}, which a server
     * body runs too.
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

    @Override
    public Map<String, Object> mcGoto(Map<String, Object> params) {
        final Params p = Params.of(params);
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                state.mc_goto.lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            String planId = p.getString("planId");
            if (planId != null && !planId.isBlank()) return adoptPlan(player, planId.trim());
            Goal goal;
            RouteParams.Parsed route;
            try {
                goal = GotoGoalResolver.resolveGoal(p, player, waypoints, type -> nearestRenderedEntity(player, type));
                route = RouteParams.parse(p.getMap("route"));
            }
            catch (IllegalArgumentException e) { return Map.of("ok", false, "error", e.getMessage()); }
            // `plan` is a top-level goto key (it is about this call, not the route); a `route.plan`
            // is honoured too for a caller that put it there.
            Object plan = p.get("plan") != null ? p.get("plan") : route.plan();
            if ("score".equals(plan)) {
                // The caller's own line, priced without a search: needs the corridor's points.
                List<BlockPos> pts = new ArrayList<>();
                if (p.getMap("route").get("corridor") instanceof Map<?, ?> cm && cm.get("points") instanceof List<?> pl) {
                    for (Object o : pl) {
                        if (o instanceof List<?> c && c.size() == 3 && c.get(0) instanceof Number x
                                && c.get(1) instanceof Number y && c.get(2) instanceof Number z) {
                            pts.add(new BlockPos(x.intValue(), y.intValue(), z.intValue()));
                        }
                    }
                }
                return PreviewSearch.score(pts, route.profile(), scopeOf(player));
            }
            if (goal == null) {
                return Map.of("ok", false, "error",
                        "missing goal — provide pos|xz|y|block|entity|entityId|direction|waypoint");
            }
            try { GotoGoalResolver.checkRequiredTool(route.requireTool(), player); }
            catch (IllegalArgumentException e) { return Map.of("ok", false, "error", e.getMessage()); }
            if (plan != null && !Boolean.FALSE.equals(plan)) {
                if (!Boolean.TRUE.equals(plan)) return Map.of("ok", false, "error", "plan must be true or \"score\"");
                if (route.fly()) return Map.of("ok", false, "error", "plan: true previews the ground planner; route.mode fly has none");
                List<Goal> targets = new ArrayList<>();
                for (BlockPos v : route.via()) targets.add(new Goal.Near(v, 1));
                targets.add(goal);
                String id = preview.submit(new PreviewSearch.Request(player.blockPosition(), targets, route.profile(),
                        p.getBool("includePath"), world, scopeOf(player)));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("started", true);
                out.put("slot", "plan");
                out.put("planId", id);
                out.put("goal", goal.toString());
                return out;
            }
            if (route.fly()) {
                // route.mode: ["fly"] — the whole intent goes to elytra, which has its own 3-D
                // planner and no A*. Needs a target cell; the reply names the slot it lives in
                // so an awaitMs waits on `elytra`, not on a goto that never started.
                BlockPos target = goal.targetPos();
                if (target == null) return Map.of("ok", false, "error",
                        "route.mode fly needs a goal with a target cell (pos/entity/waypoint), got " + goal);
                Map<String, Object> flyParams = new LinkedHashMap<>();
                flyParams.put("pos", posMap(target));
                flyParams.put("groundFallback", true);
                Map<String, Object> out = new LinkedHashMap<>(elytraFly(flyParams));
                // With no usable elytra the fallback walks, and a walk lives in the goto slot.
                out.put("slot", "groundFallback".equals(out.get("mode")) ? "goto" : "elytra");
                out.put("goal", goal.toString());
                return out;
            }
            List<Goal> targets = new ArrayList<>();
            for (BlockPos v : route.via()) targets.add(new Goal.Near(v, 1));
            targets.add(goal);
            startProcess(new IntentProcess(new Intent(targets,
                    route.profile().bias(),
                    route.profile().capability(),
                    route.profile().constraints(),
                    route.entityLeash())));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("started", true);
            out.put("goal", goal.toString());
            if (!route.via().isEmpty()) out.put("via", route.via().size());
            return out;
        });
    }

    /**
     * {@code mc.bot.goto} with {@code planId}: walk the previewed route. The plan's own goals and
     * conditions are used — the id names them — and its raw first-leg result is handed to the
     * walker before the process starts, so no search precedes the first step. Falls back to an
     * ordinary search, saying why, when the plan is gone (60 s, last 8), was best-effort, or the
     * walker refused it (the body is no longer near its start). Later legs of a via plan search
     * as usual: adoption only ever guaranteed the route the body starts on.
     */
    private Map<String, Object> adoptPlan(LocalPlayer player, String planId) {
        PreviewSearch.Plan plan = preview.take(planId);
        if (plan == null) {
            return Map.of("ok", false, "error", "planId '" + planId + "' is unknown or expired (a preview is kept "
                    + PreviewSearch.TTL_MS / 1000 + " s, the last " + PreviewSearch.KEEP + " of them)");
        }
        IntentProcess process = new IntentProcess(new Intent(plan.goals, plan.profile.bias(),
                plan.profile.capability(), plan.profile.constraints(), null));
        String why = null;
        boolean adopted = false;
        if (plan.bestEffort()) why = "the preview did not reach the goal (bestEffort)";
        else if (System.currentTimeMillis() - plan.createdMs > PreviewSearch.TTL_MS) why = "the preview is older than 60 s";
        else {
            adopted = WalkerPlanAdoption.adopt(process.walker(), plan.legs.get(0), world, player.blockPosition());
            if (!adopted) why = "the walker refused the route: the body is not near its start any more, or its first stretch is no longer walkable";
        }
        startProcess(process);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("started", true);
        out.put("goal", plan.goals.get(plan.goals.size() - 1).toString());
        out.put("planId", planId);
        out.put("adopted", adopted);
        if (why != null) out.put("adoptReason", why + " — searching normally instead");
        if (plan.goals.size() > 1) out.put("via", plan.goals.size() - 1);
        return out;
    }

    /**
     * Install a replay run: the caller ({@code mc.debug.replay} / ReplayTool) has
     * already parsed the archive and built the cell list + concatenated plan/edges.
     * On the client thread this (1) restores the block envelope via the server
     * thread, (2) teleports the bot to the recorded start, (3) arms the path-archive
     * recorder for replay capture, and (4) installs a {@link ReplayProcess} that
     * drives {@link Walker#beginReplay} so the wedge reproduces with no re-planning.
     *
     * <p>Logic lives in {@link ReplayInstaller}; kept here as the public entry point
     * ReplayTool calls.
     */
    public Map<String, Object> startReplay(java.util.List<net.magicterra.worlddriver.api.WorldApi.Cell> cells,
                                            List<BlockPos> plan, List<Move.Edge> edges,
                                            BlockPos start, Goal endGoal, BlockPos startFoot,
                                            String archiveName, boolean restoreBlocks) {
        return ReplayInstaller.startReplay(this, cells, plan, edges, start, endGoal, startFoot,
                archiveName, restoreBlocks);
    }

    /** Faithful re-run replay (mc.debug.replay replan mode): restore the recorded
     *  terrain, teleport to the recorded start, and re-issue the ORIGINAL goal through
     *  the normal {@link net.magicterra.worlddriver.bot.process.IntentProcess} (full planning). Unlike {@link #startReplay}, the
     *  recorded plan is not consulted — A* re-derives it deterministically in the
     *  restored terrain, so emergent live behaviour (repaths, execution wedges)
     *  reproduces. A normal path archive of the re-run is captured when pathArchive is on.
     *
     *  <p>Logic lives in {@link ReplayInstaller}; kept here as the public entry point
     *  ReplayTool calls. */
    public Map<String, Object> startReplayReplan(java.util.List<net.magicterra.worlddriver.api.WorldApi.Cell> cells,
                                                 BlockPos start, Goal goal,
                                                 String archiveName, boolean restoreBlocks) {
        return ReplayInstaller.startReplayReplan(this, cells, start, goal, archiveName, restoreBlocks);
    }

    /**
     * Starts a {@link VerbOrders} order on this client's body. {@code noPlayerSlot} names the slot a
     * missing player is recorded on, or null for the verbs that only answer it.
     */
    private Map<String, Object> order(Map<String, Object> params, String noPlayerSlot,
                                      java.util.function.BiFunction<Params, net.minecraft.world.entity.LivingEntity, VerbOrders.Order> build) {
        final Params p = Params.of(params == null ? Map.of() : params);
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                if (noPlayerSlot != null) state.slotFor(noPlayerSlot).lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            VerbOrders.Order o = build.apply(p, player);
            if (!o.refused()) startProcess(o.process());
            return o.reply();
        });
    }

    @Override
    public Map<String, Object> elytraFly(Map<String, Object> params) {
        return order(params, null, VerbOrders::elytraFly);
    }

    @Override
    public Map<String, Object> waypoint(Map<String, Object> params) {
        final Params p = Params.of(params);
        final String op = (p.get("op") instanceof String s && !s.isBlank()) ? s.trim().toLowerCase(Locale.ROOT) : "list";
        return onClient(() -> {
            switch (op) {
                case "save" -> {
                    String name = p.getNonBlank("name");
                    if (name == null) return Map.of("ok", false, "error", "name required");
                    BlockPos pos = p.getPos("pos");
                    if (pos == null) {
                        LocalPlayer pl = Minecraft.getInstance().player;
                        if (pl == null) return Map.of("ok", false, "error", "no pos provided and no player");
                        pos = blockPosOf(pl.getX(), pl.getY(), pl.getZ());
                    }
                    waypoints.put(name, pos);
                    return Map.of("ok", true, "op", "save", "name", name, "pos", posMap(pos), "count", waypoints.size());
                }
                case "delete" -> {
                    String name = p.getNonBlank("name");
                    if (name == null) return Map.of("ok", false, "error", "name required");
                    BlockPos prev = waypoints.remove(name);
                    return Map.of("ok", true, "op", "delete", "name", name, "existed", prev != null, "count", waypoints.size());
                }
                case "clear" -> {
                    int n = waypoints.size();
                    waypoints.clear();
                    return Map.of("ok", true, "op", "clear", "cleared", n);
                }
                case "list" -> {
                    List<Map<String, Object>> entries = new ArrayList<>();
                    for (var e : waypoints.entrySet()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", e.getKey());
                        row.put("pos", posMap(e.getValue()));
                        entries.add(row);
                    }
                    return Map.of("ok", true, "op", "list", "waypoints", entries, "count", entries.size());
                }
                case "get" -> {
                    String name = p.getNonBlank("name");
                    if (name == null) return Map.of("ok", false, "error", "name required");
                    BlockPos pos = waypoints.get(name);
                    if (pos == null) return Map.of("ok", false, "error", "no waypoint named '" + name + "'");
                    return Map.of("ok", true, "op", "get", "name", name, "pos", posMap(pos));
                }
                default -> {
                    return Map.of("ok", false, "error", "unknown op '" + op + "' (save|list|get|delete|clear)");
                }
            }
        });
    }

    @Override
    public Map<String, Object> mine(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing blocks");
        return order(params, "mine", VerbOrders::mine);
    }

    @Override
    public Map<String, Object> bunker(Map<String, Object> params) {
        return order(params, null, VerbOrders::bunker);
    }

    @Override
    public Map<String, Object> escape(Map<String, Object> params) {
        return order(params, null, VerbOrders::escape);
    }

    @Override
    public Map<String, Object> craft(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing item");
        return order(params, "craft", VerbOrders::craft);
    }

    @Override
    public Map<String, Object> smelt(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing item");
        return order(params, "smelt", VerbOrders::smelt);
    }

    @Override
    public Map<String, Object> combat(Map<String, Object> params) {
        final VerbOrders.CombatOrder c;
        try { c = VerbOrders.combatOrder(Params.of(params)); }
        catch (IllegalArgumentException e) { return Map.of("ok", false, "error", e.getMessage()); }
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) {
                state.combat.lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            combatChain.engage(c.mode(), c.targetId(), c.targetType(), c.force());
            return c.reply();
        });
    }

    @Override
    public Map<String, Object> equip(Map<String, Object> params) {
        Params p = Params.of(params);
        // profile: "best"/"combat" → armor + weapon; "armor" → armor only.
        String profile = p.get("profile") instanceof String s ? s.trim().toLowerCase() : "best";
        boolean armorOnly = p.getBool("armorOnly", false) || "armor".equals(profile);
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return Map.of("ok", false, "error", "no player");
            AutoEquip.Result r = AutoEquip.equipBest(
                    Minecraft.getInstance(), player, !armorOnly, BotConfig.equipDurabilityThreshold);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("profile", armorOnly ? "armor" : profile);
            out.put("equipped", r.equipped());
            out.put("loadout", r.loadout());
            out.put("lowDurability", r.lowDurability());
            out.put("missing", r.missing());
            return out;
        });
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
        // Why the PREVIOUS process stopped: {kind, error} (error null = ran to
        // completion). The per-verb slots already carry this for the kinds that own
        // one, but `sleep` and `replay` have no slot, so their failure used to leave
        // no trace at all once activeProcess went back to null.
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

    @Override public Map<String, Object> cancel(Map<String, Object> params) {
        Params p = Params.of(params);
        return onClient(() -> {
            String which = p.get("process") instanceof String s ? s : "all";
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
            // gap#68-⑦/⑫: "cancel all" stays a best-effort broadcast (always ok:true) —
            // every chain episode + the user slot + the orphan backfill queue.
            if ("all".equals(which)) {
                cancelCurrent("user-cancel");
                scheduler.cancelAllEpisodes("user-cancel");
                backfillTracker.clear();
                return Map.of("ok", true, "cancelled", "all");
            }
            // gap#72-②: a NAMED cancel routes through the shared resolver — user slot
            // by kind, chain episode by NAME, then any chain-HELD process by KIND
            // ("bunker" is both BunkerChain's name and BunkerProcess's kind; duskSecure's
            // held BunkerProcess was unreachable by every leg while cancel said ok:true).
            BotProcess c = userTask.process();
            CancelRouting.Plan plan =
                    CancelRouting.resolve(which, c != null ? c.kind() : null, scheduler.chains());
            if (plan.cancelUserProcess()) cancelCurrent("user-cancel");
            for (Chain target : plan.episodeTargets()) target.cancelEpisode("user-cancel");
            hits.addAll(plan.labels());
            return CancelRouting.honestResult(hits, which);
        });
    }

    // pause/resume removed — handled inside setting() via the {paused:bool} key.

    @Override
    public Map<String, Object> clearArea(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing from/to");
        return order(params, null, VerbOrders::clearArea);
    }

    @Override
    public Map<String, Object> farm(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing from/to");
        return order(params, null, VerbOrders::farm);
    }

    @Override
    public Map<String, Object> construct(Map<String, Object> params) {
        return order(params, null, VerbOrders::construct);
    }

    @Override
    public Map<String, Object> sleep(Map<String, Object> params) {
        return order(params, null, VerbOrders::sleep);
    }

    @Override
    public Map<String, Object> build(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing origin/schematic");
        return order(params, null, VerbOrders::build);
    }

    @Override
    public Map<String, Object> follow(Map<String, Object> params) {
        return order(params, null, VerbOrders::follow);
    }

    @Override
    public Map<String, Object> explore(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing centerX/centerZ");
        return order(params, null, VerbOrders::explore);
    }

    @Override
    public Map<String, Object> runAway(Map<String, Object> params) {
        return order(params, null, VerbOrders::runAway);
    }

    @Override
    public Map<String, Object> lookAt(Map<String, Object> params) {
        return InteractionCommands.lookAt(this, params);
    }

    @Override
    public Map<String, Object> useItem(Map<String, Object> params) {
        return InteractionCommands.useItem(params);
    }

    @Override
    public Map<String, Object> holdItem(Map<String, Object> params) {
        return InteractionCommands.holdItem(params);
    }

    @Override
    public Map<String, Object> attackEntity(Map<String, Object> params) {
        return InteractionCommands.attackEntity(params);
    }

    @Override
    public Map<String, Object> useItemOn(Map<String, Object> params) {
        return InteractionCommands.useItemOn(params);
    }

    @Override
    public Map<String, Object> useItemOnEntity(Map<String, Object> params) {
        return InteractionCommands.useItemOnEntity(params);
    }

    @Override
    public Map<String, Object> setting(Map<String, Object> params) {
        return SettingsCommand.apply(this, params);
    }

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
    public Map<String, Object> resetClientEntry() {
        List<String> reset = new ArrayList<>();
        // One client-thread hop for everything that touches scheduler or render state.
        // The look-cancel MUST be inside the hop: the scheduler (userTask) is ticked and
        // mutated from clientTick(), and every sibling mutation (mc.bot.cancel's own leg
        // included) marshals via onClient — an off-thread cancel here would race the tick
        // (P2a Task 3 review, Important). Cancelling FIRST, same-thread, also guarantees no
        // client tick can interleave between the cancel and the key release, so a live
        // LookProcess can never re-drive the keys we are about to release.
        // The look slot is the user-task slot (mc.bot.lookAt{smoothLook:true} starts a
        // LookProcess there); UserTaskChain.heldProcessKind() is deliberately null
        // (cancel's own routing leg), so read the held process directly and cancel
        // through the same path mc.bot.cancel uses.
        onClient(() -> {
            BotProcess held = userTask.process();
            if (held != null && "look".equals(held.kind())) {
                cancelCurrent("mc.test.reset");
                reset.add("look");
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
        return Map.of("ok", true, "reset", reset);
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
     * {@link net.magicterra.worlddriver.bot.util.BotInteract#releaseKeys()} clears, in the same
     * order, under the reply names {@code up/down/left/right/jump/sprint/attack/shift}.
     * {@code attack} is the bot's dig latch ({@link ClientIntents#digHeld()}), not a keybind —
     * the bot stopped pressing the attack key on 2026-09-14 — but it keeps its slot so the
     * instrument's shape is stable. Pure observation — mutates nothing.
     */
    @Override
    public Map<String, Object> heldKeys() {
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Map<String, Object> keys = new LinkedHashMap<>();
            if (mc.options == null) {
                return Map.of("ok", false, "error", "no client options");
            }
            keys.put("up", mc.options.keyUp.isDown());
            keys.put("down", mc.options.keyDown.isDown());
            keys.put("left", mc.options.keyLeft.isDown());
            keys.put("right", mc.options.keyRight.isDown());
            keys.put("jump", mc.options.keyJump.isDown());
            keys.put("sprint", mc.options.keySprint.isDown());
            keys.put("attack", ClientIntents.digHeld());
            keys.put("shift", mc.options.keyShift.isDown());
            return Map.of("ok", true, "keys", keys);
        });
    }

    /**
     * {@code mc.test.input.useOnBlock} — see {@link BotApi#useOnBlock(Map)}. Instrument-grade:
     * the ONLY state change is the {@code gameMode.useItemOn} right-click itself. The face is
     * the one nearest the player's eye ({@code pickFaceTowardsPlayer}) and the hit Vec3 is that
     * face's centre — the exact synthetic-hit shape {@code mc.bot.useItemOn} builds — but unlike
     * the behaviour verb it does NOT aim (no yaw/pitch write), NOT move, and NOT toggle sneak, so
     * nothing in the path/aim pipeline runs. Opening a block-entity container (empty hand +
     * right-click) does not need any of those.
     */
    @Override
    public Map<String, Object> useOnBlock(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "x,y,z required");
        Object ox = params.get("x"), oy = params.get("y"), oz = params.get("z");
        if (!(ox instanceof Number) || !(oy instanceof Number) || !(oz instanceof Number)) {
            return Map.of("ok", false, "error", "x,y,z required (int)");
        }
        final int x = ((Number) ox).intValue();
        final int y = ((Number) oy).intValue();
        final int z = ((Number) oz).intValue();
        Object oh = params.get("hand");
        final InteractionHand hand = (oh != null && "off".equalsIgnoreCase(String.valueOf(oh)))
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null) {
                return Map.of("ok", false, "error", "no local player");
            }
            BlockPos block = new BlockPos(x, y, z);
            Direction face = pickFaceTowardsPlayer(block, p);
            double cx = block.getX() + 0.5 + face.getStepX() * 0.5;
            double cy = block.getY() + 0.5 + face.getStepY() * 0.5;
            double cz = block.getZ() + 0.5 + face.getStepZ() * 0.5;
            BlockHitResult hit = new BlockHitResult(new Vec3(cx, cy, cz), face, block, false);
            InteractionResult r = mc.gameMode.useItemOn(p, hand, hit);
            if (r.consumesAction()) p.swing(hand);
            return Map.of(
                    "ok", true,
                    "result", r.name(),
                    "consumed", r.consumesAction(),
                    "hand", hand == InteractionHand.MAIN_HAND ? "main" : "off",
                    "face", face.getName());
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
     * holds the use intent — they place through {@link net.magicterra.worlddriver.bot.util.BotInteract}
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
            scheduler.cancelAllEpisodes("player-death");   // gap#68-③⑦: 全链 episode 清零
            backfillTracker.clear();                        // gap#68-⑧⑫: 孤儿回填队列清零
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
                net.magicterra.worlddriver.api.DriverApi api = net.magicterra.worlddriver.WorldDriverCommon.api();
                if (api != null) api.emitExternal("screen.autoClosed", mc.player.blockPosition(), java.util.Map.of(
                                "screen", type, "blockedTicks", (double) screenBlockTicks));
                net.magicterra.worlddriver.WorldDriverCommon.LOG.warn(
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
        // autoTool only fires when no process owns hotbar selection — MineProcess
        // / BboxFillProcess / BuildProcess / FarmProcess all manage hotbar
        // themselves and would fight us. So this is essentially "swap to best
        // tool when the player is manually mining" (or scripted-attack via
        // input.click), the same scope as Baritone's autoTool.
        if (BotConfig.autoTool && c == null) {
            AutoTool.tick(mc, mc.player);
        }
        // Record foot position for autoBackfill — runs every tick the setting
        // is on, regardless of current process, so that cells passed through
        // during mining/walking are candidates once the bot idles. The
        // tracker itself dedupes and caps storage.
        if (BotConfig.autoBackfill && mc.player != null) {
            BlockPos foot = new BlockPos(
                    (int) Math.floor(mc.player.getX()),
                    (int) Math.floor(mc.player.getY()),
                    (int) Math.floor(mc.player.getZ()));
            backfillTracker.record(foot);
        }
        // Auto-start BackfillProcess when idle + setting on + queue non-empty.
        // Matches Baritone's BackfillProcess.isActive() trigger pattern: it
        // only runs when no higher-priority process wants the slot. The
        // process self-terminates once its work is done.
        if (respawnGraceLeft > 0) respawnGraceLeft--;
        if (c == null && respawnGraceLeft == 0 && BotConfig.autoBackfill && backfillTracker.size() > 0) {
            startProcess(new BackfillProcess(backfillTracker));
        }
        // Movement channel: run the highest-priority chain (user task, or a
        // survival/combat chain preempting it). When every chain sits out, the
        // bot is idle — release the keys, matching the old single-process path.
        // Reset the flee-context flag first: RunAwayProcess.tick (driven below by
        // the scheduler) re-sets it true right before its A* search, so fleeSearch
        // snapshots true only for an active flee and is never stuck-true.
        BotConfig.fleeActive = false;
        BotConfig.walkerDigActive = false;   // same per-tick reset contract as fleeActive: the Walker re-sets it below while holding a dig
        BotConfig.walkerCruiseActive = false;   // likewise: the Walker re-sets it below while its surface cruise holds the eyes under
        // Capture BEFORE the tick: a chain that runs this tick presses movement
        // keys even if it finishes mid-tick (current() then nulls) — its trailing
        // presses still need the one-shot cleanup below.
        boolean schedulerDroveThisTick = scheduler.current() != null;
        // The scheduler talks bodies; this tick chain is the client's, so the body is the local
        // player's. Built fresh per tick, like every other ClientPlayerBody (see clientAvatar()).
        scheduler.tick(new net.magicterra.worlddriver.bot.body.ClientPlayerBody(mc), world, state);
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
        // independent BotConfig.autoFloatWhenDrowning (gap#70, live death #18: a bot with
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
        if (mc.player != null) net.magicterra.worlddriver.bot.auto.LavaProximityEscape.tick(mc, mc.player);
        // Keep the combat status slot's liveness in sync with the chain so the
        // awaitable mc.bot.combat route (which polls combat.active) completes the
        // moment the fight ends. Counters/goal/lastError persist for post-mortem.
        state.combat.active = combatChain.engaged();
        // STREAM-GRADE CAMERA (AIRI): single chokepoint, AFTER every actuator above
        // has written the player's rotation. Rate-limits this tick's net yaw/pitch
        // change so no code path can snap the view; a functional exact aim this tick
        // (camera-raycast mine/attack, a leap heading) bypasses it via
        // LookController.requestSnap(). See BotConfig.cameraSlew.
        net.magicterra.worlddriver.bot.movement.LookController.apply(mc.player);
    }

    /** Start a foreground user task. The process lifecycle now lives in
     *  {@link UserTaskChain}; this stays as the single entry point the verbs call.
     *  Package-private so extracted command/installer classes in this package
     *  (e.g. {@link ReplayInstaller}, {@link InteractionCommands}) can start tasks. */
    void startProcess(BotProcess next) {
        userTask.setProcess(next);
        paused = false;
    }

    // === the object seam: run a caller's own BotProcess on the real player ======
    // See BotApi for why these two are not routes. They are the in-JVM entry point the
    // playthrough ladder uses to climb on the client's real player instead of spawning a
    // headless FakePlayer beside it.

    /**
     * One leg's whole state, published as a single immutable value.
     *
     * <p>A record rather than three volatile fields because the reader is on ANOTHER THREAD and
     * wants the three together: two independently-atomic reads do not compose into an atomic
     * pair, and the torn pair「busy 已清，但 error 还是上一腿的」is indistinguishable from a
     * leg that just finished cleanly. One reference, one read, one consistent answer.
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
        // holds nothing, and a reader that asked the chain would conclude the leg had already
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
     * Close out a leg whose process has left the chain. <b>Client thread only</b>, once per tick.
     *
     * <p>The test is the process OBJECT, not「链子空不空」: the chain is empty during the whole
     * window between enqueue and install too, and closing the leg there is the false-completion
     * this seam exists to prevent.
     */
    private void settleLeg() {
        BotProcess mine = installedLeg;
        if (mine == null || userTask.process() == mine) return;
        // A newer leg was published (runProcess from the caller's thread) while this one was still
        // installed — the next scene's goto enqueued behind the last scene's one-tick HoldStill.
        // Its departure is not the new leg's ending: publishing busy=false here under the OLD seq
        // overwrote the new leg's busy=true before its install ran, and the caller read "ended at
        // tick 0" for a walk that never started. The new leg's own install re-points installedLeg.
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
     * change. A cached one would keep actuating a stale body — the same「视图不跟着身体走」shape the
     * driver has already paid for once, where a view built at construction planned over the old
     * dimension's terrain for every rung after the portal.
     */
    @Override public net.magicterra.worlddriver.bot.body.Body clientAvatar() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : new net.magicterra.worlddriver.bot.body.ClientPlayerBody(mc);
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
            combatChain.standDown();
            state.combat.lastError = reason;
            state.combat.active = false;
        }
        cancelCurrent(reason);
    }

    // === WorldView impl ======================================================

}
