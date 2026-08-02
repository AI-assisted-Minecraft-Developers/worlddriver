package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.bot.elytra.ElytraPhysics;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.magicterra.worlddriver.client.internal.ClientChatLog;

import net.magicterra.worlddriver.bot.movement.Walker;
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
import net.minecraft.world.entity.Entity;
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
    /** autoEat hold-keyUse loop. Owns its own {@code eating} flag; the tick
     *  hook drives it and releases the key when a process takes over. */
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

    @Override
    public Map<String, Object> mcGoto(Map<String, Object> params) {
        final Params p = Params.of(params);
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                state.mc_goto.lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            Goal goal;
            try { goal = GotoGoalResolver.resolveGoal(p, player, waypoints); }
            catch (IllegalArgumentException e) { return Map.of("ok", false, "error", e.getMessage()); }
            if (goal == null) {
                return Map.of("ok", false, "error",
                        "missing goal — provide pos|xz|y|block|entity|entityId|direction|waypoint");
            }
            try { GotoGoalResolver.checkRequiredTool(p, player); }
            catch (IllegalArgumentException e) { return Map.of("ok", false, "error", e.getMessage()); }
            startProcess(new IntentProcess(new Intent(goal,
                    GotoGoalResolver.resolveBias(p),
                    GotoGoalResolver.resolveCapability(p),
                    GotoGoalResolver.resolveConstraints(p),
                    GotoGoalResolver.resolveEntityLeash(p))));
            return Map.of("ok", true, "started", true, "goal", goal.toString());
        });
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

    @Override
    public Map<String, Object> elytraFly(Map<String, Object> params) {
        final Params p = Params.of(params);
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return Map.of("ok", false, "error", "no player");
            BlockPos target = p.getPos("pos");
            Float yaw = p.getFloat("yaw");
            boolean hasPitch = p.get("pitch") instanceof Number;
            float pitch = (float) p.getDouble("pitch", 0.0);
            // Reactive sim-lookahead control (milestone B): used when a 3D target
            // is given and no fixed test-pitch is pinned (explicit pitch forces
            // the fixed-heading glide rig); can be forced on/off via `reactive`.
            boolean reactive = target != null && p.getBool("reactive", !hasPitch);
            boolean fireworks = reactive
                    ? !Boolean.FALSE.equals(p.get("fireworks"))   // reactive: boost on by default
                    : p.getBool("fireworks");
            int fwEvery = p.getIntClamped("fireworkEveryTicks", 40, 5, 400);
            int maxTicks = p.getIntClamped("ticks", reactive ? 2000 : 200, 1, 20_000);
            double stopXZ = p.getDouble("stopXZDist", 3.0);
            // Ground fallback (milestone D): with no usable elytra, optionally walk
            // to the target via the normal pathfinder instead of failing.
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            boolean flyable = chest.is(Items.ELYTRA)
                    && chest.getMaxDamage() > 0 && chest.getDamageValue() < chest.getMaxDamage() - 1;
            if (!flyable && target != null && p.getBool("groundFallback")) {
                int near = p.getIntClamped("near", 1, 0, 64);
                Goal g = near > 0 ? new Goal.Near(target, near) : new Goal.Block(target);
                startProcess(new IntentProcess(new Intent(g)));
                return Map.of("ok", true, "started", true, "mode", "groundFallback",
                        "reason", "no usable elytra", "goal", g.toString());
            }
            startProcess(new ElytraProcess(target, yaw, pitch, fireworks, fwEvery, maxTicks, stopXZ, reactive));
            return Map.of("ok", true, "started", true,
                    "mode", reactive ? "reactive" : (target != null ? "goal" : "glide"),
                    "pitch", pitch, "fireworks", fireworks);
        });
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
                        pos = blockPosOf(pl);
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
        Params p = Params.of(params);
        List<String> ids = p.getStringList("blocks");
        if (ids.isEmpty()) return Map.of("ok", false, "error", "blocks list required");
        int qtyIn = p.getInt("quantity", 1);
        if (qtyIn < 1) return Map.of("ok", false, "error", "quantity must be ≥ 1");
        final int qty = clamp(qtyIn, 1, 256);
        final int radius = p.getIntClamped("radius", 16, 1, 64);
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) {
                state.mine.lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            startProcess(new MineProcess(ids, qty, radius));
            return Map.of("ok", true, "started", true,
                    "blocks", ids, "quantity", qty, "radius", radius);
        });
    }

    @Override
    public Map<String, Object> bunker(Map<String, Object> params) {
        Params p = Params.of(params == null ? Map.of() : params);
        final int depth = p.getIntClamped("depth", BotConfig.bunkerDepth, 1, 5);
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) {
                return Map.of("ok", false, "error", "no player");
            }
            startProcess(new BunkerProcess(depth));
            // "acted" (did BunkerProcess ever really break/place a block) can't be
            // known synchronously here — the dig/carve/plug runs over many later
            // ticks, not within this call. This response stays a start ack
            // ("ok:true" = "受理", not "sealed"); the honest terminal verdict
            // (goalReached/endReason, folded in via awaitable()) lands on the
            // bunker slot once BunkerProcess actually finishes or bails.
            return Map.of("ok", true, "started", true, "depth", depth);
        });
    }

    @Override
    public Map<String, Object> escape(Map<String, Object> params) {
        Params p = Params.of(params == null ? Map.of() : params);
        return onClient(() -> {
            LocalPlayer pl = Minecraft.getInstance().player;
            if (pl == null) return Map.of("ok", false, "error", "no player");
            // Climb until this Y (default: ~32 above current — far enough to clear
            // any pit; the skyOpen check ends it the moment it surfaces sooner).
            int targetY = p.getIntClamped("targetY", pl.blockPosition().getY() + 32, -64, 320);
            // A targetY below the feet means DIG DOWN: dispatch to the descent
            // mirror (A*'s YLevel descent hunts distant cave mouths instead of
            // digging and stalls in hill terrain — devil-bench day1_iron). Both
            // report through the same escape slot, so await/status are unchanged.
            boolean down = targetY < pl.blockPosition().getY();
            startProcess(down ? new DescendProcess(targetY) : new EscapeProcess(targetY));
            return Map.of("ok", true, "started", true, "targetY", targetY,
                    "direction", down ? "down" : "up");
        });
    }

    @Override
    public Map<String, Object> craft(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing item");
        Params p = Params.of(params);
        String item = p.getNonBlank("item");
        if (item == null) return Map.of("ok", false, "error", "item required");
        final int count = p.getIntClamped("count", 1, 1, 256);
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) {
                state.craft.lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            startProcess(new CraftProcess(item, count));
            return Map.of("ok", true, "started", true, "item", item, "count", count);
        });
    }

    @Override
    public Map<String, Object> smelt(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing item");
        Params p = Params.of(params);
        String item = p.getNonBlank("item");
        if (item == null) return Map.of("ok", false, "error", "item required");
        final int count = p.getIntClamped("count", 1, 1, 256);
        final String fuel = p.getNonBlank("fuel");
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) {
                state.smelt.lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            startProcess(new SmeltProcess(item, count, fuel));
            return Map.of("ok", true, "started", true, "item", item, "count", count,
                    "fuel", fuel == null ? "auto" : fuel);
        });
    }

    @Override
    public Map<String, Object> combat(Map<String, Object> params) {
        Params p = Params.of(params);
        String modeStr = p.get("mode") instanceof String s ? s.trim().toLowerCase() : "engage";
        CombatProcess.Mode mode = switch (modeStr) {
            case "kill"   -> CombatProcess.Mode.KILL;
            case "defend" -> CombatProcess.Mode.DEFEND;
            case "engage" -> CombatProcess.Mode.ENGAGE;
            default       -> null;
        };
        if (mode == null) {
            return Map.of("ok", false, "error", "mode must be engage|defend|kill");
        }
        // target:{id:int} or {type:"minecraft:zombie"} (also accepts a bare type/id key).
        Integer id = null;
        String type = null;
        Object tgt = p.get("target");
        if (tgt instanceof Map<?, ?> tm) {
            Object idObj = tm.get("id");
            if (idObj instanceof Number n) id = n.intValue();
            Object tyObj = tm.get("type");
            if (tyObj instanceof String ts && !ts.isBlank()) type = normalizeEntityId(ts.trim());
        } else if (tgt instanceof Number n) {
            id = n.intValue();
        } else if (tgt instanceof String ts && !ts.isBlank()) {
            type = normalizeEntityId(ts.trim());
        }
        if (mode == CombatProcess.Mode.KILL && id == null && type == null) {
            return Map.of("ok", false, "error", "kill mode requires target:{id|type}");
        }
        // gap#68-②: force:true overrides the frail-HP entry gate for this explicit order.
        final boolean force = p.get("force") instanceof Boolean b && b;
        final CombatProcess.Mode fMode = mode;
        final Integer fId = id;
        final String fType = type;
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) {
                state.combat.lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            combatChain.engage(fMode, fId, fType, force);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("started", true);
            out.put("mode", fMode.name().toLowerCase());
            if (fId != null) out.put("targetId", fId);
            if (fType != null) out.put("targetType", fType);
            return out;
        });
    }

    /** Accept a bare entity name ("zombie") or a full id ("minecraft:zombie"). */
    private static String normalizeEntityId(String s) {
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
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
        Params p = Params.of(params);
        BlockPos from = p.getPos("from");
        BlockPos to   = p.getPos("to");
        if (from == null || to == null) return Map.of("ok", false, "error", "from and to required");
        long volume = (long)(Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getY() - to.getY()) + 1) * (Math.abs(from.getZ() - to.getZ()) + 1);
        if (volume > 4096) return Map.of("ok", false, "error", "area too large (max 4096 blocks)");
        // Baritone sel-system parity: fill="id" places id after clearing each
        // cell; replace={from,to} only touches cells matching the from id and
        // leaves the to id behind. Each cell costs walk+break(+place) so the
        // bbox is bot-driven (matches Baritone's survival path — survival
        // requires the fill blocks to be in inventory, hotbar preferred).
        final String fillId = p.getNonBlank("fill");
        String rFrom = null, rTo = null;
        if (p.get("replace") instanceof Map<?,?> rm) {
            if (rm.get("from") instanceof String s) rFrom = s;
            if (rm.get("to") instanceof String s)   rTo = s;
            if (rFrom == null || rTo == null) return Map.of("ok", false, "error", "replace requires {from:'id', to:'id'}");
        }
        if (fillId != null && rFrom != null) return Map.of("ok", false, "error", "specify fill OR replace, not both");
        // Effective semantics — clear: no fill / no filter. fill: fillId, no
        // filter. replace: fillId=replace.to, filterFromId=replace.from.
        final String effFillId = (rTo != null) ? rTo : fillId;
        final String effFilterFromId = rFrom; // null for clear/fill
        final String mode = (rFrom != null) ? "replace" : (fillId != null ? "fill" : "clear");
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) return Map.of("ok", false, "error", "no player");
            startProcess(new BboxFillProcess(from, to, effFillId, effFilterFromId));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("started", true);
            out.put("mode", mode);
            out.put("from", posMap(from)); out.put("to", posMap(to));
            out.put("volume", (int) volume);
            if (effFillId != null) out.put("fill", effFillId);
            if (effFilterFromId != null) out.put("replaceFrom", effFilterFromId);
            return out;
        });
    }

    @Override
    public Map<String, Object> farm(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing from/to");
        Params p = Params.of(params);
        BlockPos from = p.getPos("from");
        BlockPos to   = p.getPos("to");
        if (from == null || to == null) return Map.of("ok", false, "error", "from and to required");
        long area = (long)(Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getZ() - to.getZ()) + 1);
        if (area > 4096) return Map.of("ok", false, "error", "area too large (max 4096 cells)");
        // Crops filter: caller may restrict to a subset, otherwise all four
        // vanilla crops. Validated against known ids — unknown entries get
        // silently dropped (Baritone shrugs the same way on bad filter input).
        Set<String> crops = new HashSet<>();
        if (p.get("crops") instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s && FarmProcess.SEED_FOR.containsKey(s)) crops.add(s);
            if (crops.isEmpty()) return Map.of("ok", false, "error",
                    "crops must be subset of " + FarmProcess.SEED_FOR.keySet());
        } else {
            crops = new HashSet<>(FarmProcess.SEED_FOR.keySet());
        }
        boolean replant = !(p.get("replant") instanceof Boolean rb) || rb;
        final Set<String> effCrops = crops;
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) return Map.of("ok", false, "error", "no player");
            startProcess(new FarmProcess(from, to, effCrops, replant));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("started", true);
            out.put("from", posMap(from)); out.put("to", posMap(to));
            out.put("area", (int) area);
            out.put("crops", new ArrayList<>(effCrops));
            out.put("replant", replant);
            return out;
        });
    }

    @Override
    public Map<String, Object> construct(Map<String, Object> params) {
        final Params p = Params.of(params);
        String mode = (p.get("mode") instanceof String s && !s.isBlank()) ? s.trim().toLowerCase(Locale.ROOT) : null;
        if (mode == null || (!mode.equals("tower") && !mode.equals("bridge")))
            return Map.of("ok", false, "error", "mode required (tower|bridge)");
        String blockId = (p.get("block") instanceof String s && !s.isBlank()) ? s.trim() : null;
        if (mode.equals("tower")) {
            // height OR targetY; height is relative, targetY is absolute.
            Integer targetY = null;
            if (p.get("targetY") instanceof Number n) targetY = n.intValue();
            int height = p.getInt("height", -1);
            if (targetY == null && height < 0) return Map.of("ok", false, "error", "tower requires height or targetY");
            if (height > 256) return Map.of("ok", false, "error", "height too large (max 256)");
            final Integer targetYf = targetY;
            final int heightF = height;
            return onClient(() -> {
                LocalPlayer pl = Minecraft.getInstance().player;
                if (pl == null) return Map.of("ok", false, "error", "no player");
                int startY = (int) Math.floor(pl.getY());
                int finalTargetY = targetYf != null ? targetYf : startY + heightF;
                if (finalTargetY <= startY)
                    return Map.of("ok", false, "error", "target Y (" + finalTargetY + ") must be > current feet Y (" + startY + ")");
                if (finalTargetY - startY > 256)
                    return Map.of("ok", false, "error", "tower span too large (max 256)");
                startProcess(new TowerProcess(finalTargetY, blockId));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true); out.put("started", true);
                out.put("mode", "tower");
                out.put("startY", startY); out.put("targetY", finalTargetY);
                if (blockId != null) out.put("block", blockId);
                return out;
            });
        }
        // mode == "bridge"
        String dir = (p.get("direction") instanceof String s && !s.isBlank()) ? s.trim().toLowerCase(Locale.ROOT) : "forward";
        int distance = p.getInt("distance", -1);
        if (distance < 1) return Map.of("ok", false, "error", "bridge requires distance >= 1");
        if (distance > 64) return Map.of("ok", false, "error", "distance too large (max 64)");
        final int distanceF = distance;
        return onClient(() -> {
            LocalPlayer pl = Minecraft.getInstance().player;
            if (pl == null) return Map.of("ok", false, "error", "no player");
            Direction face = resolveCardinalDirection(pl, dir);
            if (face == null) return Map.of("ok", false, "error", "unknown direction: " + dir + " (use forward|back|left|right|north|south|east|west)");
            startProcess(new BridgeProcess(face, distanceF, blockId));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("started", true);
            out.put("mode", "bridge");
            out.put("direction", dir);
            out.put("face", face.getName());
            out.put("distance", distanceF);
            if (blockId != null) out.put("block", blockId);
            return out;
        });
    }

    @Override
    public Map<String, Object> sleep(Map<String, Object> params) {
        final Params p = Params.of(params);
        BlockPos explicit = p.getPos("pos");
        int radius = p.getIntClamped("radius", 16, 1, 64);
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer pl = mc.player;
            if (pl == null) return Map.of("ok", false, "error", "no player");
            startProcess(new SleepProcess(explicit, radius));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("started", true);
            if (explicit != null) out.put("pos", posMap(explicit));
            out.put("radius", radius);
            return out;
        });
    }

    @Override
    public Map<String, Object> build(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing origin/schematic");
        Params p = Params.of(params);
        BlockPos origin = p.getPos("origin");
        if (origin == null) return Map.of("ok", false, "error", "origin required");
        Object schemObj = p.get("schematic");
        Object schemB64Obj = p.get("schematicBase64");
        if (schemObj != null && schemB64Obj != null)
            return Map.of("ok", false, "error", "specify either schematic OR schematicBase64, not both");
        Schematic s;
        try {
            if (schemB64Obj instanceof String b64 && !b64.isBlank()) {
                byte[] bytes;
                try { bytes = java.util.Base64.getDecoder().decode(b64.trim()); }
                catch (IllegalArgumentException e) {
                    return Map.of("ok", false, "error", "schematicBase64: invalid base64");
                }
                s = Schematic.fromSpongeSchem(bytes);
            } else if (schemObj instanceof Map<?, ?> schem) {
                s = Schematic.parse(schem);
            } else {
                return Map.of("ok", false, "error",
                        "schematic (procedural object) or schematicBase64 (sponge .schem bytes) required");
            }
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error", "schematic: " + e.getMessage());
        }
        final Schematic finalS = s;
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) return Map.of("ok", false, "error", "no player");
            startProcess(new BuildProcess(origin, finalS));
            return Map.of("ok", true, "started", true, "origin", posMap(origin),
                "size", Map.of("w", finalS.w, "h", finalS.h, "d", finalS.d),
                "blocks", finalS.entries.size());
        });
    }

    @Override
    public Map<String, Object> follow(Map<String, Object> params) {
        Params p = Params.of(params);
        String entityType = p.getString("entityType");
        String name = p.getString("name");
        int radius = p.getIntClamped("radius", 3, 1, 16);
        int maxIdleTicks = p.getIntClamped("maxIdleTicks", 0, 0, 100_000);
        if (entityType == null && name == null) return Map.of("ok", false, "error", "entityType or name required");
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) return Map.of("ok", false, "error", "no player");
            SearchProfile followProfile = new SearchProfile(
                    GotoGoalResolver.resolveBias(p),
                    GotoGoalResolver.resolveCapability(p),
                    GotoGoalResolver.resolveConstraints(p));
            startProcess(new FollowProcess(entityType, name, radius, maxIdleTicks, followProfile));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true); r.put("started", true);
            if (entityType != null) r.put("entityType", entityType);
            if (name != null) r.put("name", name);
            r.put("radius", radius);
            if (maxIdleTicks > 0) r.put("maxIdleTicks", maxIdleTicks);
            return r;
        });
    }

    @Override
    public Map<String, Object> explore(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing centerX/centerZ");
        Params p = Params.of(params);
        int cx = p.getInt("centerX", Integer.MIN_VALUE);
        int cz = p.getInt("centerZ", Integer.MIN_VALUE);
        if (cx == Integer.MIN_VALUE || cz == Integer.MIN_VALUE)
            return Map.of("ok", false, "error", "centerX and centerZ required");
        int maxChunks = p.getIntClamped("maxChunks", 16, 1, 64);
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) return Map.of("ok", false, "error", "no player");
            startProcess(new ExploreProcess(cx, cz, maxChunks));
            return Map.of("ok", true, "started", true, "centerX", cx, "centerZ", cz, "maxChunks", maxChunks);
        });
    }

    @Override
    public Map<String, Object> runAway(Map<String, Object> params) {
        Params q = Params.of(params);
        BlockPos source = q.getPos("from");
        int minDist = q.getIntClamped("minDist", 16, 4, 64);
        return onClient(() -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return Map.of("ok", false, "error", "no player");
            BlockPos src = source;
            if (src == null) src = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
            startProcess(new RunAwayProcess(src, minDist));
            return Map.of("ok", true, "started", true, "from", posMap(src), "minDist", minDist);
        });
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
            releaseKeys();
            reset.add("keys");
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) { mc.setScreen(null); reset.add("screen"); }
            return Map.of();
        });
        int chatCleared = ClientChatLog.clear();   // pure JVM buffer, no client thread needed
        reset.add("chat:" + chatCleared);
        return Map.of("ok", true, "reset", reset);
    }

    /**
     * {@code mc.test.input.heldKeys} — see {@link BotApi#heldKeys()}. Reads
     * {@link KeyMapping#isDown()} on the client thread for exactly the eight keymappings
     * {@link net.magicterra.worlddriver.bot.util.BotInteract#releaseKeys()} clears, in the same
     * order, under the reply names {@code up/down/left/right/jump/sprint/attack/shift}.
     * Pure observation — mutates nothing.
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
            keys.put("attack", mc.options.keyAttack.isDown());
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
     * <p><b>Not key ownership</b>, despite how it reads. Six process kinds answer
     * {@code "builder"} (Build/Bridge/Tower/Backfill/BboxFill/Farm) and NONE of them
     * presses {@code keyUse} — they place through {@link net.magicterra.worlddriver.bot.util.BotInteract}
     * → {@code gameMode.useItemOn} directly. What this suppresses is an ambient's
     * {@code keyUse} firing a SECOND use-action on the tick a builder places, which is
     * why it gates the reflexes instead of handing a key over. (Its previous name,
     * {@code processOwnsUseKey}, claimed an ownership that never existed.)
     *
     * <p>{@code keyUse} is the one shared input {@code BotInteract.releaseKeys()}
     * deliberately omits — the idle release runs AFTER the shield/heal/eat reflexes set
     * it — so the arbitration below is the ONLY thing keeping the key from leaking, and
     * every acquirer must join it or self-clear on every exit path. {@code
     * UseKeyOwnershipTest} pins the acquirer set against exactly that drift.
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
        // Use-key arbitration (Phase B): shield > heal > eat, one holder per tick,
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
        // Capture BEFORE the tick: a chain that runs this tick presses movement
        // keys even if it finishes mid-tick (current() then nulls) — its trailing
        // presses still need the one-shot cleanup below.
        boolean schedulerDroveThisTick = scheduler.current() != null;
        scheduler.tick(mc, world, state);
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
