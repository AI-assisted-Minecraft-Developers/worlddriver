package net.magicterra.agent.bot;

import net.magicterra.agent.bot.elytra.ElytraPhysics;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.Params;
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

import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.process.*;
import net.magicterra.agent.bot.scheduler.BunkerChain;
import net.magicterra.agent.bot.scheduler.CombatChain;
import net.magicterra.agent.bot.scheduler.DodgeChain;
import net.magicterra.agent.bot.scheduler.DuskSecureChain;
import net.magicterra.agent.bot.scheduler.PanicChain;
import net.magicterra.agent.bot.scheduler.ProcessScheduler;
import net.magicterra.agent.bot.scheduler.RetreatChain;
import net.magicterra.agent.bot.scheduler.UserTaskChain;

import static net.magicterra.agent.bot.GoalResolver.*;
import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import net.minecraft.world.entity.Entity;
import java.util.Locale;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.magicterra.agent.bot.auto.AutoEat;
import net.magicterra.agent.bot.auto.AutoEquip;
import net.magicterra.agent.bot.auto.AutoShield;
import net.magicterra.agent.bot.auto.AutoHeal;
import net.magicterra.agent.bot.auto.AutoTotem;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.combat.ClientThreatScanner;
import net.magicterra.agent.bot.auto.AutoTool;
import net.magicterra.agent.bot.auto.AutoSwim;
import net.magicterra.agent.bot.auto.AntiSuffocate;
import net.magicterra.agent.bot.auto.AutoRespawn;
import net.magicterra.agent.bot.world.WorldModel;
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
     *  {@link net.magicterra.agent.bot.movement.InputReleaseGate}. */
    private final net.magicterra.agent.bot.movement.InputReleaseGate releaseGate =
            new net.magicterra.agent.bot.movement.InputReleaseGate();
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
            try { goal = resolveGoal(p, player); }
            catch (IllegalArgumentException e) { return Map.of("ok", false, "error", e.getMessage()); }
            if (goal == null) {
                return Map.of("ok", false, "error",
                        "missing goal — provide pos|xz|y|block|entity|entityId|direction|waypoint");
            }
            startProcess(new GotoProcess(goal));
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
     */
    public Map<String, Object> startReplay(java.util.List<net.magicterra.agent.api.WorldApi.Cell> cells,
                                            List<BlockPos> plan, List<Move.Edge> edges,
                                            BlockPos start, Goal endGoal, BlockPos startFoot,
                                            String archiveName, boolean restoreBlocks) {
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return Map.of("ok", false, "error", "no player");

            int restored = 0;
            if (restoreBlocks && cells != null && !cells.isEmpty()) {
                net.magicterra.agent.api.AgentApi api =
                        net.magicterra.agent.AgentDriverCommon.api();
                if (api == null) return Map.of("ok", false, "error", "AgentApi not ready");
                try {
                    restored = api.restoreCellsOnServer(cells);
                } catch (RuntimeException e) {
                    return Map.of("ok", false, "error", "block restore failed: " + e.getMessage());
                }
            }

            // Teleport the bot to the recorded start (block center). Use the server
            // /tp command (authoritative in single-player) and also sync the client
            // position so beginReplay anchors on the start foot immediately.
            double tx = start.getX() + 0.5, ty = start.getY(), tz = start.getZ() + 0.5;
            net.magicterra.agent.api.AgentApi api =
                    net.magicterra.agent.AgentDriverCommon.api();
            if (api != null) {
                try {
                    api.route("mc.action.runCommand",
                            Map.of("cmd", String.format(Locale.ROOT, "tp %.1f %d %.1f", tx, start.getY(), tz)));
                } catch (RuntimeException ignored) {
                    // /tp unavailable (no server) — the client moveTo below still positions the bot.
                }
            }
            player.moveTo(tx, ty, tz, player.getYRot(), player.getXRot());

            // Arm replay capture BEFORE installing the process so the very first tick
            // (which calls beginReplay) is captured against this session.
            net.magicterra.agent.bot.debug.PathArchiveRecorder rec =
                    net.magicterra.agent.bot.debug.PathDebugBootstrap.archiveRecorder();
            if (rec != null) rec.armReplay(plan, archiveName);

            startProcess(new ReplayProcess(plan, edges, endGoal, startFoot));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("file", archiveName);
            out.put("plannedNodes", plan.size());
            out.put("restoredBlocks", restored);
            out.put("replayRun", "pending");
            return out;
        });
    }

    /** Faithful re-run replay (mc.debug.replay replan mode): restore the recorded
     *  terrain, teleport to the recorded start, and re-issue the ORIGINAL goal through
     *  the normal {@link GotoProcess} (full planning). Unlike {@link #startReplay}, the
     *  recorded plan is not consulted — A* re-derives it deterministically in the
     *  restored terrain, so emergent live behaviour (repaths, execution wedges)
     *  reproduces. A normal path archive of the re-run is captured when pathArchive is on. */
    public Map<String, Object> startReplayReplan(java.util.List<net.magicterra.agent.api.WorldApi.Cell> cells,
                                                 BlockPos start, Goal goal,
                                                 String archiveName, boolean restoreBlocks) {
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return Map.of("ok", false, "error", "no player");

            int restored = 0;
            net.magicterra.agent.api.AgentApi api =
                    net.magicterra.agent.AgentDriverCommon.api();
            if (restoreBlocks && cells != null && !cells.isEmpty()) {
                if (api == null) return Map.of("ok", false, "error", "AgentApi not ready");
                try {
                    restored = api.restoreCellsOnServer(cells);
                } catch (RuntimeException e) {
                    return Map.of("ok", false, "error", "block restore failed: " + e.getMessage());
                }
            }

            double tx = start.getX() + 0.5, ty = start.getY(), tz = start.getZ() + 0.5;
            if (api != null) {
                try {
                    api.route("mc.action.runCommand",
                            Map.of("cmd", String.format(Locale.ROOT, "tp %.1f %d %.1f", tx, start.getY(), tz)));
                } catch (RuntimeException ignored) {
                    // /tp unavailable (no server) — the client moveTo below still positions the bot.
                }
            }
            player.moveTo(tx, ty, tz, player.getYRot(), player.getXRot());

            // Normal planning goto — this is what makes the re-run faithful to the live run.
            startProcess(new GotoProcess(goal));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("file", archiveName);
            out.put("goal", goal.toString());
            out.put("restoredBlocks", restored);
            return out;
        });
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
                startProcess(new GotoProcess(g));
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

    /**
     * Resolve a goal from goto params on the client thread (some selectors need
     * the player position or live world state). Throws IllegalArgumentException
     * with a descriptive message when a selector matches but cannot be resolved
     * (e.g. block not found in radius, entity id stale, unknown direction);
     * returns null when no selector is recognized so the caller can emit the
     * "missing goal" error.
     */
    private Goal resolveGoal(Params p, LocalPlayer player) {
        Goal base = resolveBaseGoal(p, player);
        if (base == null) return null;
        // Baritone GoalInverted: flee whatever the resolved goal converges on.
        return p.getBool("invert") ? new Goal.Inverted(base) : base;
    }

    /**
     * Resolve the goal before the optional {@code invert} wrapper. {@code goalMode}
     * picks how a positional target is satisfied — Baritone's distinction between
     * GoalBlock / GoalTwoBlocks / GoalGetToBlock:
     *   "in" (default) → stand exactly on the block,
     *   "two"          → stand inside it at foot or eye level,
     *   "adjacent"     → stand next to / above / below it (chests, furnaces).
     * {@code near>0} always wins and relaxes to a Euclidean radius (GoalNear).
     */
    private Goal resolveBaseGoal(Params p, LocalPlayer player) {
        // GoalAxis: reach the nearest world axis/diagonal at the configured Y.
        if (p.getBool("axis")) return new Goal.Axis(BotConfig.axisHeight);

        String mode = p.get("goalMode") instanceof String s ? s.trim().toLowerCase(Locale.ROOT) : "in";

        Goal classic = parseGoal(p);
        if (classic != null) {
            // parseGoal already honored near/xz/y; only a bare pos respects goalMode.
            if (classic instanceof Goal.Block b && !"in".equals(mode)) return targetGoal(b.target(), mode, 0);
            return classic;
        }

        if (p.get("block") instanceof String blockId && !blockId.isBlank()) {
            int radius = p.getIntClamped("radius", 32, 1, 64);
            BlockPos stand = findNearestStandForBlock(player, blockId, radius);
            if (stand == null) throw new IllegalArgumentException(
                    "no reachable '" + blockId + "' within radius " + radius);
            return new Goal.Block(stand);
        }
        if (p.get("entityId") instanceof Number eidn) {
            int eid = eidn.intValue();
            Level lvl = Minecraft.getInstance().level;
            Entity e = (lvl == null) ? null : lvl.getEntity(eid);
            if (e == null) throw new IllegalArgumentException("no entity with id " + eid);
            int near = p.getIntClamped("near", 3, 0, 16);
            return targetGoal(blockPosOf(e), mode, near);
        }
        if (p.get("entity") instanceof String entType && !entType.isBlank()) {
            Entity e = findNearestEntity(player, entType);
            if (e == null) throw new IllegalArgumentException("no '" + entType + "' visible nearby");
            int near = p.getIntClamped("near", 3, 0, 16);
            return targetGoal(blockPosOf(e), mode, near);
        }
        if (p.get("direction") instanceof String dirName && !dirName.isBlank()) {
            String d = dirName.trim().toLowerCase(Locale.ROOT);
            // Baritone GoalStrictDirection: keep boring this way with no fixed
            // endpoint (the best-effort fallback carries it as far as it can).
            if (p.getBool("strict")) {
                BlockPos origin = blockPosOf(player);
                int[] step = horizontalStep(player, d);
                if (step == null) throw new IllegalArgumentException(
                        "strict direction must be horizontal (north|south|east|west|forward|backward|left|right), got '" + dirName + "'");
                return new Goal.StrictDirection(origin, step[0], step[1]);
            }
            int distance = p.getIntClamped("distance", 8, 1, 256);
            BlockPos target = applyDirection(player, dirName, distance);
            if (target == null) throw new IllegalArgumentException(
                    "unknown direction '" + dirName + "' (north|south|east|west|up|down|forward|backward|left|right)");
            int near = p.getIntClamped("near", 0, 0, 64);
            // Pure-horizontal directions → XZ goal (free Y), vertical → YLevel,
            // mixed (rare — only via 'forward'/'backward' which is horizontal) →
            // Block. `near>0` always uses Near to relax the constraint.
            if (near > 0) return new Goal.Near(target, near);
            if ("up".equals(d) || "down".equals(d)) return new Goal.YLevel(target.getY());
            return new Goal.XZ(target.getX(), target.getZ());
        }
        if (p.get("waypoint") instanceof String wpName && !wpName.isBlank()) {
            BlockPos wpPos = waypoints.get(wpName);
            if (wpPos == null) throw new IllegalArgumentException("no waypoint named '" + wpName + "'");
            int near = p.getIntClamped("near", 0, 0, 64);
            return targetGoal(wpPos, mode, near);
        }
        return null;
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
            startProcess(new EscapeProcess(targetY));
            return Map.of("ok", true, "started", true, "targetY", targetY);
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
        final CombatProcess.Mode fMode = mode;
        final Integer fId = id;
        final String fType = type;
        return onClient(() -> {
            if (Minecraft.getInstance().player == null) {
                state.combat.lastError = "no player";
                return Map.of("ok", false, "error", "no player");
            }
            combatChain.engage(fMode, fId, fType);
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
        // Always-on water-bucket clutch state (idle/lip/falling) — lets a test
        // confirm an unplanned fall actually armed and is self-rescuing.
        snap.put("clutch", CLUTCH.phase());
        return snap;
    }

    @Override public Map<String, Object> cancel(Map<String, Object> params) {
        Params p = Params.of(params);
        return onClient(() -> {
            String which = p.get("process") instanceof String s ? s : "all";
            // Combat lives in its own chain, not the user-task slot — cancel it directly.
            if ("all".equals(which) || "combat".equals(which)) {
                if (combatChain.engaged()) {
                    combatChain.standDown();
                    state.combat.lastError = "user-cancel";
                    state.combat.active = false;
                }
            }
            BotProcess c = userTask.process();
            boolean match = "all".equals(which) || (c != null && which.equals(c.kind()));
            if (match) cancelCurrent("user-cancel");
            return Map.of("ok", true, "cancelled", which);
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
            startProcess(new FollowProcess(entityType, name, radius, maxIdleTicks));
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
        if (params == null) return Map.of("ok", false, "error", "missing pos or yaw/pitch");
        Params q = Params.of(params);
        BlockPos at = q.getPos("pos");
        Object yawO = q.get("yaw"), pitchO = q.get("pitch");
        return onClient(() -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return Map.of("ok", false, "error", "no player");
            float yaw, pitch;
            if (at != null) {
                Vec3 eye = p.getEyePosition();
                double dx = at.getX() + 0.5 - eye.x, dy = at.getY() + 0.5 - eye.y, dz = at.getZ() + 0.5 - eye.z;
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            } else if (yawO instanceof Number yn && pitchO instanceof Number pn) {
                yaw = yn.floatValue();
                pitch = pn.floatValue();
            } else {
                return Map.of("ok", false, "error", "provide pos or both yaw+pitch");
            }
            // smoothLook → hand off to a per-tick LookProcess that pans the
            // camera toward the target; otherwise snap (unchanged contract).
            if (BotConfig.smoothLook) {
                startProcess(new LookProcess(at, yaw, pitch));
                return Map.of("ok", true, "started", true, "smooth", true, "yaw", yaw, "pitch", pitch);
            }
            p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
            return Map.of("ok", true, "yaw", yaw, "pitch", pitch);
        });
    }

    @Override
    public Map<String, Object> useItem(Map<String, Object> params) {
        InteractionHand hand = parseHand(Params.of(params).get("hand"));
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null) return Map.of("ok", false, "error", "no player");
            // Prevent the secondary-use shortcut from short-circuiting an item's
            // primary behavior — vanilla useItem itself doesn't read it, but a
            // prior process may have left keyShift down.
            p.setShiftKeyDown(false);
            InteractionResult result = mc.gameMode.useItem(p, hand);
            if (result.consumesAction()) p.swing(hand);
            return Map.of(
                "ok", true,
                "hand", hand == InteractionHand.MAIN_HAND ? "main" : "off",
                "result", result.name(),
                "consumed", result.consumesAction()
            );
        });
    }

    @Override
    public Map<String, Object> attackEntity(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing entityId");
        Params q = Params.of(params);
        Object idObj = q.get("entityId");
        if (!(idObj instanceof Number)) return Map.of("ok", false, "error", "entityId required (integer)");
        final int entityId = ((Number) idObj).intValue();
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null || mc.level == null) {
                return Map.of("ok", false, "error", "no player");
            }
            Entity target = mc.level.getEntity(entityId);
            if (target == null) {
                return Map.of("ok", false, "error", "no entity with id " + entityId);
            }
            if (target == p) return Map.of("ok", false, "error", "cannot attack self");
            // Same path the vanilla MouseHandler takes on left-click of an entity:
            // turn to face, swing main arm, dispatch attack through MPGameMode so
            // the server applies weapon damage + cooldown + crit/sweep rules.
            p.setShiftKeyDown(false);
            Vec3 ep = target.position();
            double dx = ep.x - p.getX();
            double dz = ep.z - p.getZ();
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(
                    (ep.y + target.getBbHeight() * 0.5) - p.getEyeY(),
                    Math.sqrt(dx * dx + dz * dz)));
            p.setYRot(yaw); p.setXRot(pitch);
            mc.gameMode.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
            return Map.of(
                "ok", true,
                "entityId", entityId,
                "type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                "alive", target.isAlive(),
                "distance", Math.sqrt(p.distanceToSqr(target))
            );
        });
    }

    @Override
    public Map<String, Object> useItemOn(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing pos");
        Params q = Params.of(params);
        BlockPos blockPos = q.getPos("pos");
        if (blockPos == null) return Map.of("ok", false, "error", "pos required");
        Direction face = parseFace(q.get("face"));
        InteractionHand hand = parseHand(q.get("hand"));
        boolean wantLookAt = q.getBool("lookAt", true);

        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null || mc.level == null) return Map.of("ok", false, "error", "no player");

            // The face parameter is "the face of `pos` we're clicking" — i.e. the
            // outward-facing face of the click target. If omitted, pick the face
            // closest to the player (so the synthetic hit looks like a real
            // ray-cast from the player's eye).
            Direction effFace = face != null ? face : pickFaceTowardsPlayer(blockPos, p);

            // Click location = center of that face. The integrated server uses
            // it for ranged interaction checks AND for items that key off the
            // exact hit Vec3 (e.g. slab top/bottom selection).
            double cx = blockPos.getX() + 0.5 + effFace.getStepX() * 0.5;
            double cy = blockPos.getY() + 0.5 + effFace.getStepY() * 0.5;
            double cz = blockPos.getZ() + 0.5 + effFace.getStepZ() * 0.5;
            Vec3 hitLoc = new Vec3(cx, cy, cz);
            BlockHitResult hit = new BlockHitResult(hitLoc, effFace, blockPos, false);

            if (wantLookAt) {
                // Aim at the hit location so the server-side rotation matches
                // the synthetic ray. Without this, server-side checks that look
                // at view direction (e.g. anti-cheat in modded servers) may
                // reject; in single-player it's harmless cosmetic.
                Vec3 eye = p.getEyePosition();
                double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
            }
            p.setShiftKeyDown(false);

            InteractionResult result = mc.gameMode.useItemOn(p, hand, hit);
            if (result.consumesAction()) p.swing(hand);

            return Map.of(
                "ok", true,
                "hand", hand == InteractionHand.MAIN_HAND ? "main" : "off",
                "pos", posMap(blockPos),
                "face", effFace.getName(),
                "result", result.name(),
                "consumed", result.consumesAction()
            );
        });
    }

    @Override
    public Map<String, Object> setting(Map<String, Object> params) {
        return SettingsCommand.apply(this, params);
    }

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

    /** Called from the platform client-tick hook every client tick. */
    public void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        // Death detection must run BEFORE autoRespawn: autoRespawn dismisses the
        // DeathScreen (setScreen(null)), and the screen is the only client-side
        // carrier of the SPECIFIC cause of death (slain by X / drowned / blown
        // up). Read it on the screen's rising edge, emit player.death + cancel
        // active processes, THEN let autoRespawn skip past.
        detectDeath(mc);
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
        {
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
        if (CLUTCH.tick(mc, world)) { releaseGate.markDirtied(); return; }
        // Refresh the shared threat picture once per tick — reflex chains
        // (panic/dodge) and the use-key arbiter (shield) all read it below.
        ClientThreatScanner.refresh(mc);
        // Driver→agent push: emit one-shot events on threat-appeared / player-hurt
        // / player-death transitions (client-sensed; no server-side equivalent for
        // threats, and this is the player the agent actually controls).
        detectClientEvents(mc);
        // Driver→agent push: surface new chat / system / command-result lines and
        // action-bar / title text the client displays (no server-side hook in
        // client-MCP mode). Best-effort reflection, guarded — never breaks the tick.
        detectClientMessages(mc);
        // Ambient hand/equipment/hotbar gating reads the foreground user process
        // (a preempting survival/combat chain leaves it held but suspended).
        BotProcess c = userTask.process();
        boolean processOwnsUseKey = c != null && c.kind().equals("builder");
        // Use-key arbitration (Phase B): shield > heal > eat. Only one ambient
        // may hold keyUse per tick; the losers release. useItem-based mining/
        // placing goes through gameMode directly (not keyUse), so the only
        // process contender is BuildProcess (PLACING) — it owns the key then.
        if (processOwnsUseKey) {
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
        if (c == null && BotConfig.autoBackfill && backfillTracker.size() > 0) {
            startProcess(new BackfillProcess(backfillTracker));
        }
        // Movement channel: run the highest-priority chain (user task, or a
        // survival/combat chain preempting it). When every chain sits out, the
        // bot is idle — release the keys, matching the old single-process path.
        // Reset the flee-context flag first: RunAwayProcess.tick (driven below by
        // the scheduler) re-sets it true right before its A* search, so fleeSearch
        // snapshots true only for an active flee and is never stuck-true.
        BotConfig.fleeActive = false;
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
        if (BotConfig.autoSwim && mc.player != null && mc.player.isInWater()) {
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
        // Keep the combat status slot's liveness in sync with the chain so the
        // awaitable mc.bot.combat route (which polls combat.active) completes the
        // moment the fight ends. Counters/goal/lastError persist for post-mortem.
        state.combat.active = combatChain.engaged();
        // STREAM-GRADE CAMERA (AIRI): single chokepoint, AFTER every actuator above
        // has written the player's rotation. Rate-limits this tick's net yaw/pitch
        // change so no code path can snap the view; a functional exact aim this tick
        // (camera-raycast mine/attack, a leap heading) bypasses it via
        // LookController.requestSnap(). See BotConfig.cameraSlew.
        net.magicterra.agent.bot.movement.LookController.apply(mc.player);
    }

    /** Emit driver→agent push events for the local player's threat/hurt/death
     *  transitions. Called once per client tick after the threat scan refreshes.
     *  Everything funnels through {@code AgentApi.emitExternal} → the same event
     *  stream block/chat/death use → subscribers on both transports. */
    /** Fire player.death off the DeathScreen rising edge, carrying the screen's
     *  SPECIFIC cause (combat-kill message). Also cancels active bot processes so
     *  an autoRespawn can't resume the lethal action into a death loop. Runs
     *  before autoRespawn so the screen — and its cause — is still readable. */
    private void detectDeath(Minecraft mc) {
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
            cancelAllProcesses("player-death");
        }
        evtDeathScreenSeen = onDeath;
    }

    private void detectClientEvents(Minecraft mc) {
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
    private void detectClientMessages(Minecraft mc) {
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

    /** Start a foreground user task. The process lifecycle now lives in
     *  {@link UserTaskChain}; this stays as the single entry point the verbs call. */
    private void startProcess(BotProcess next) {
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
