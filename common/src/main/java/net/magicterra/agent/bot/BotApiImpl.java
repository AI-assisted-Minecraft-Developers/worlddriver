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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.process.*;

import static net.magicterra.agent.bot.GoalResolver.*;
import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import net.minecraft.world.entity.Entity;
import java.util.Locale;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.magicterra.agent.bot.auto.AutoEat;
import net.magicterra.agent.bot.auto.AutoTool;
import net.magicterra.agent.bot.auto.AutoSwim;
import net.magicterra.agent.bot.auto.AutoRespawn;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase-2 client bot impl. Hosts a single active {@link BotProcess} driven
 * each client tick by the platform tick hook. Processes implemented so far:
 * goto (Phase 1), mine (Phase 2). Others return unimplemented.
 *
 * Threading: every {@code mc.bot.*} call marshals to the client thread via
 * {@link #onClient}; {@link #clientTick} runs on it already. {@link #current}
 * is only written from the client thread.
 */
public final class BotApiImpl implements BotApi {

    private final BotState state = new BotState();
    private final WorldView world = new ClientWorldView();
    private volatile BotProcess current;
    volatile boolean paused;
    /** Named positions persisted for the lifetime of the bot impl (no disk).
     *  Survives across goto/mine/etc. so a script can label home/farm/base
     *  and revisit by name. ConcurrentHashMap because list/get can race a
     *  save from a separate RPC handler thread. */
    private final Map<String, BlockPos> waypoints = new ConcurrentHashMap<>();
    /** autoEat hold-keyUse loop. Owns its own {@code eating} flag; the tick
     *  hook drives it and releases the key when a process takes over. */
    private final AutoEat autoEat = new AutoEat();

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

        Goal classic = parseGoal(p.map());
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

    @Override public Map<String, Object> status() {
        Map<String, Object> snap = state.snapshot();
        snap.put("paused", paused);
        // Snapshot the volatile field to a local; the client tick thread may
        // null `current` between the null check and a subsequent `.kind()` read.
        BotProcess c = current;
        snap.put("activeProcess", c == null ? null : c.kind());
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
            BotProcess c = current;
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

    /** Called from the platform client-tick hook every client tick. */
    public void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        // autoRespawn fires even when level/player is in the dying transition —
        // DeathScreen shows briefly with mc.player still alive but at 0 HP, and
        // we want to skip past it ASAP. Done first so the rest of the tick sees
        // the post-respawn world state.
        if (BotConfig.autoRespawn) AutoRespawn.tick(mc);
        if (mc.level == null || mc.player == null) { releaseKeys(); return; }
        if (paused) { releaseKeys(); return; }
        // Always-on water-bucket clutch (survival first): arm reactively on any
        // unplanned damaging fall, and once armed OWN the descent before any
        // process runs. While the clutch is driving the fall (placing/scooping
        // water) it holds the keys and we return early, so an active goto/mine/
        // build is suspended for those airborne ticks instead of fighting it. A
        // planned fallBucket fall is armed by the Walker as it steps off the lip
        // and drives the same descent here. Both gated on allowWaterBucketFall.
        if (BotConfig.allowWaterBucketFall) CLUTCH.armReactive(mc, world);
        if (CLUTCH.tick(mc, world)) return;
        // autoEat runs whenever a process isn't actively driving keyUse — we
        // only fight the bot for the use button if no other process needs it.
        // useItem-based mining/placing happens through gameMode directly, not
        // keyUse, so the only contender is BuildProcess (PLACING phase). Skip
        // there to avoid stealing the place click.
        BotProcess c = current;
        boolean processOwnsUseKey = c != null && c.kind().equals("builder");
        if (BotConfig.autoEat && !processOwnsUseKey) {
            autoEat.tick(mc, mc.player);
        } else {
            // process took over OR autoEat got toggled off — release the key.
            autoEat.releaseIfActive(mc);
        }
        // autoSwim runs whenever no process is actively walking — Walker
        // already sets keyJump every tick and would just be fought. Mining
        // BREAKING also releases keyJump so we're safe to layer there too.
        if (BotConfig.autoSwim && (c == null || !"goto".equals(c.kind()) && !"follow".equals(c.kind()) && !"explore".equals(c.kind()) && !"runAway".equals(c.kind()))) {
            AutoSwim.tick(mc, mc.player);
        }
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
            c = current;
        }
        if (c == null) { releaseKeys(); return; }
        try {
            if (c.tick(mc, world, state)) {
                releaseKeys();
                current = null;
            }
        } catch (RuntimeException e) {
            String err = e.getClass().getSimpleName() + ": " + e.getMessage();
            BotState.ProcessSlot slot = slotFor(c.kind());
            slot.lastError = err;
            slot.reset();
            releaseKeys();
            current = null;
        }
    }

    private void startProcess(BotProcess next) {
        cancelCurrent("superseded");
        next.attach(state);
        current = next;
        paused = false;
    }

    private void cancelCurrent(String reason) {
        BotProcess c = current;
        if (c == null) return;
        slotFor(c.kind()).lastError = reason;
        slotFor(c.kind()).reset();
        releaseKeys();
        current = null;
    }

    private BotState.ProcessSlot slotFor(String kind) {
        return switch (kind) {
            case "goto"    -> state.mc_goto;
            case "mine"    -> state.mine;
            case "builder" -> state.builder;
            case "follow"  -> state.follow;
            case "explore" -> state.explore;
            case "runAway" -> state.runAway;
            case "look"    -> state.look;
            case "elytra"  -> state.elytra;
            default        -> state.mc_goto;
        };
    }

    // === WorldView impl ======================================================

}
