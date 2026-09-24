package net.magicterra.worlddriver.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.magicterra.worlddriver.bot.process.BboxFillProcess;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.BridgeProcess;
import net.magicterra.worlddriver.bot.process.BuildProcess;
import net.magicterra.worlddriver.bot.process.BunkerProcess;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.process.DescendProcess;
import net.magicterra.worlddriver.bot.process.ElytraProcess;
import net.magicterra.worlddriver.bot.process.EscapeProcess;
import net.magicterra.worlddriver.bot.process.ExploreProcess;
import net.magicterra.worlddriver.bot.process.FarmProcess;
import net.magicterra.worlddriver.bot.process.FollowProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.MineProcess;
import net.magicterra.worlddriver.bot.process.RunAwayProcess;
import net.magicterra.worlddriver.bot.process.Schematic;
import net.magicterra.worlddriver.bot.process.SleepProcess;
import net.magicterra.worlddriver.bot.process.SmeltProcess;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What an order to one of the {@code mc.bot.*} verbs that start a process means, apart from which player
 * carries it out: the params read, a bad order refused, the process built and the reply written.
 * {@code BotApiImpl} starts the process on the client player and {@code api/BodyRoutes} on the bot
 * player named by {@code body}, so the two cannot read an order differently.
 *
 * <p>The player comes in as a {@link LivingEntity} for the verbs that read it (feet Y, facing, the chest
 * slot); the rest ignore it. Nothing here may name a client class: on a dedicated server this runs with
 * none loaded.
 */
public final class VerbOrders {

    private VerbOrders() {}

    /** The process to start and the reply to give; a refusal has no process. */
    public record Order(BotProcess process, Map<String, Object> reply) {
        static Order refuse(String error) { return new Order(null, Map.of("ok", false, "error", error)); }
        public boolean refused() { return process == null; }
    }

    public static Order mine(Params p, LivingEntity self) {
        List<String> ids = p.getStringList("blocks");
        if (ids.isEmpty()) return Order.refuse("blocks list required");
        int qtyIn = p.getInt("quantity", 1);
        if (qtyIn < 1) return Order.refuse("quantity must be ≥ 1");
        int qty = Params.clamp(qtyIn, 1, 256);
        int radius = p.getIntClamped("radius", 16, 1, 64);
        return new Order(new MineProcess(ids, qty, radius),
                Map.of("ok", true, "started", true, "blocks", ids, "quantity", qty, "radius", radius));
    }

    /**
     * "acted" (did BunkerProcess ever really break/place a block) can't be known synchronously here — the
     * dig/carve/plug runs over many later ticks, not within this call. The reply stays a start ack
     * ("ok:true" = "accepted", not "sealed"); the honest terminal verdict (goalReached/endReason, folded in via
     * awaitable()) lands on the bunker slot once BunkerProcess actually finishes or bails.
     */
    public static Order bunker(Params p, LivingEntity self) {
        int depth = p.getIntClamped("depth", BotConfig.bunkerDepth, 1, 5);
        return new Order(new BunkerProcess(depth), Map.of("ok", true, "started", true, "depth", depth));
    }

    public static Order escape(Params p, LivingEntity self) {
        int feetY = self.blockPosition().getY();
        // Climb until this Y (default: ~32 above current — far enough to clear any pit; the skyOpen
        // check ends it the moment it surfaces sooner).
        int targetY = p.getIntClamped("targetY", feetY + 32, -64, 320);
        // A targetY below the feet means DIG DOWN: dispatch to the descent mirror (A*'s YLevel descent
        // hunts distant cave mouths instead of digging and stalls in hill terrain — devil-bench
        // day1_iron). Both report through the same escape slot, so await/status are unchanged.
        boolean down = targetY < feetY;
        return new Order(down ? new DescendProcess(targetY) : new EscapeProcess(targetY),
                Map.of("ok", true, "started", true, "targetY", targetY, "direction", down ? "down" : "up"));
    }

    public static Order craft(Params p, LivingEntity self) {
        String item = p.getNonBlank("item");
        if (item == null) return Order.refuse("item required");
        int count = p.getIntClamped("count", 1, 1, 256);
        return new Order(new CraftProcess(item, count),
                Map.of("ok", true, "started", true, "item", item, "count", count));
    }

    public static Order smelt(Params p, LivingEntity self) {
        String item = p.getNonBlank("item");
        if (item == null) return Order.refuse("item required");
        int count = p.getIntClamped("count", 1, 1, 256);
        String fuel = p.getNonBlank("fuel");
        return new Order(new SmeltProcess(item, count, fuel),
                Map.of("ok", true, "started", true, "item", item, "count", count, "fuel", fuel == null ? "auto" : fuel));
    }

    /** A read combat order. The client hands it to its combat chain; any other player gets a {@link CombatProcess}. */
    public record CombatOrder(CombatProcess.Mode mode, Integer targetId, String targetType, boolean force) {
        public Map<String, Object> reply() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("started", true);
            out.put("mode", mode.name().toLowerCase());
            if (targetId != null) out.put("targetId", targetId);
            if (targetType != null) out.put("targetType", targetType);
            return out;
        }
    }

    /** Reads a combat order; a bad one throws {@link IllegalArgumentException} carrying the refusal. */
    public static CombatOrder combatOrder(Params p) {
        String modeStr = p.get("mode") instanceof String s ? s.trim().toLowerCase() : "engage";
        CombatProcess.Mode mode = switch (modeStr) {
            case "kill"   -> CombatProcess.Mode.KILL;
            case "defend" -> CombatProcess.Mode.DEFEND;
            case "engage" -> CombatProcess.Mode.ENGAGE;
            default       -> null;
        };
        if (mode == null) throw new IllegalArgumentException("mode must be engage|defend|kill");
        // target:{id:int} or {type:"minecraft:zombie"} (also accepts a bare type/id key).
        Integer id = null;
        String type = null;
        Object tgt = p.get("target");
        if (tgt instanceof Map<?, ?> tm) {
            if (tm.get("id") instanceof Number n) id = n.intValue();
            if (tm.get("type") instanceof String ts && !ts.isBlank()) type = normalizeEntityId(ts.trim());
        } else if (tgt instanceof Number n) {
            id = n.intValue();
        } else if (tgt instanceof String ts && !ts.isBlank()) {
            type = normalizeEntityId(ts.trim());
        }
        if (mode == CombatProcess.Mode.KILL && id == null && type == null) {
            throw new IllegalArgumentException("kill mode requires target:{id|type}");
        }
        // gap#68-②: force:true overrides the frail-HP entry gate for this explicit order.
        boolean force = p.get("force") instanceof Boolean b && b;
        return new CombatOrder(mode, id, type, force);
    }

    /**
     * {@code mc.bot.combat} for a player that is not the client's: the same order, as a {@link CombatProcess}.
     * {@code force} has nothing to override there, since the frail-HP gate it lifts belongs to the
     * client's {@code CombatChain} and any other player has no chains.
     */
    public static Order combat(Params p, LivingEntity self) {
        CombatOrder c;
        try { c = combatOrder(p); }
        catch (IllegalArgumentException e) { return Order.refuse(e.getMessage()); }
        return new Order(new CombatProcess(c.mode(), c.targetId(), c.targetType()), c.reply());
    }

    /** Accept a bare entity name ("zombie") or a full id ("minecraft:zombie"). */
    private static String normalizeEntityId(String s) {
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }

    public static Order clearArea(Params p, LivingEntity self) {
        BlockPos from = p.getPos("from");
        BlockPos to   = p.getPos("to");
        if (from == null || to == null) return Order.refuse("from and to required");
        long volume = cells(from, to);
        if (volume > MAX_BOX_CELLS) return Order.refuse("area too large (max " + MAX_BOX_CELLS + " blocks)");
        // Baritone sel-system parity: fill="id" places id after clearing each cell; replace={from,to}
        // only touches cells matching the from id and leaves the to id behind. Each cell costs
        // walk+break(+place) so the bbox is bot-driven (matches Baritone's survival path — survival
        // requires the fill blocks to be in inventory, hotbar preferred).
        String fillId = p.getNonBlank("fill");
        String rFrom = null, rTo = null;
        if (p.get("replace") instanceof Map<?, ?> rm) {
            if (rm.get("from") instanceof String s) rFrom = s;
            if (rm.get("to") instanceof String s)   rTo = s;
            if (rFrom == null || rTo == null) return Order.refuse("replace requires {from:'id', to:'id'}");
        }
        if (fillId != null && rFrom != null) return Order.refuse("specify fill OR replace, not both");
        // Effective semantics — clear: no fill / no filter. fill: fillId, no filter. replace:
        // fillId=replace.to, filterFromId=replace.from.
        String effFillId = (rTo != null) ? rTo : fillId;
        String effFilterFromId = rFrom;
        String mode = (rFrom != null) ? "replace" : (fillId != null ? "fill" : "clear");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("started", true);
        out.put("mode", mode);
        out.put("from", posMap(from)); out.put("to", posMap(to));
        out.put("volume", (int) volume);
        if (effFillId != null) out.put("fill", effFillId);
        if (effFilterFromId != null) out.put("replaceFrom", effFilterFromId);
        return new Order(new BboxFillProcess(from, to, effFillId, effFilterFromId), out);
    }

    /** Most cells a box verb may cover: clearArea works every one, and farm rescans them all after each harvest. */
    static final int MAX_BOX_CELLS = 4096;

    /** Cells in the box spanned by {@code a} and {@code b}, as a long so no span can overflow it. */
    private static long cells(BlockPos a, BlockPos b) {
        return (Math.abs((long) a.getX() - b.getX()) + 1) * (Math.abs((long) a.getY() - b.getY()) + 1)
                * (Math.abs((long) a.getZ() - b.getZ()) + 1);
    }

    /** An oversized box, or a Y outside the world, throws as an argument error rather than refusing:
     *  no later state of the world makes that order valid, so the caller must change it. */
    public static Order farm(Params p, LivingEntity self) {
        if (self == null) return farm(p, Integer.MIN_VALUE, Integer.MAX_VALUE);
        return farm(p, self.level().getMinBuildHeight(), self.level().getMaxBuildHeight() - 1);
    }

    static Order farm(Params p, int minY, int maxY) {
        BlockPos from = p.getPos("from");
        BlockPos to   = p.getPos("to");
        if (from == null || to == null) return Order.refuse("from and to required");
        for (BlockPos end : new BlockPos[] {from, to}) {
            if (end.getY() < minY || end.getY() > maxY) {
                throw new IllegalArgumentException("mc.bot.farm: y=" + end.getY()
                        + " is outside the world's build height [" + minY + "," + maxY + "]");
            }
        }
        long volume = cells(from, to);
        if (volume > MAX_BOX_CELLS) {
            throw new IllegalArgumentException("mc.bot.farm: the box covers " + volume + " cells (x*y*z), max "
                    + MAX_BOX_CELLS + "; every cell is rescanned after each harvest, so keep the Y span to the crop layer");
        }
        long area = (long) (Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getZ() - to.getZ()) + 1);
        // Crops filter: caller may restrict to a subset, otherwise all four vanilla crops. Validated
        // against known ids — unknown entries get silently dropped (Baritone shrugs the same way on bad
        // filter input).
        Set<String> crops = new HashSet<>();
        if (p.get("crops") instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s && FarmProcess.SEED_FOR.containsKey(s)) crops.add(s);
            if (crops.isEmpty()) return Order.refuse("crops must be subset of " + FarmProcess.SEED_FOR.keySet());
        } else {
            crops = new HashSet<>(FarmProcess.SEED_FOR.keySet());
        }
        boolean replant = !(p.get("replant") instanceof Boolean rb) || rb;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("started", true);
        out.put("from", posMap(from)); out.put("to", posMap(to));
        out.put("area", (int) area);
        out.put("volume", (int) volume);
        out.put("crops", new ArrayList<>(crops));
        out.put("replant", replant);
        return new Order(new FarmProcess(from, to, crops, replant), out);
    }

    public static Order construct(Params p, LivingEntity self) {
        String mode = (p.get("mode") instanceof String s && !s.isBlank()) ? s.trim().toLowerCase(Locale.ROOT) : null;
        if (mode == null || (!mode.equals("tower") && !mode.equals("bridge"))) {
            return Order.refuse("mode required (tower|bridge)");
        }
        String blockId = (p.get("block") instanceof String s && !s.isBlank()) ? s.trim() : null;
        if (mode.equals("tower")) {
            // height OR targetY; height is relative, targetY is absolute.
            Integer targetY = p.get("targetY") instanceof Number n ? n.intValue() : null;
            int height = p.getInt("height", -1);
            if (targetY == null && height < 0) return Order.refuse("tower requires height or targetY");
            if (height > 256) return Order.refuse("height too large (max 256)");
            int startY = (int) Math.floor(self.getY());
            int finalTargetY = targetY != null ? targetY : startY + height;
            if (finalTargetY <= startY) {
                return Order.refuse("target Y (" + finalTargetY + ") must be > current feet Y (" + startY + ")");
            }
            if (finalTargetY - startY > 256) return Order.refuse("tower span too large (max 256)");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true); out.put("started", true);
            out.put("mode", "tower");
            out.put("startY", startY); out.put("targetY", finalTargetY);
            if (blockId != null) out.put("block", blockId);
            return new Order(new TowerProcess(finalTargetY, blockId), out);
        }
        String dir = (p.get("direction") instanceof String s && !s.isBlank()) ? s.trim().toLowerCase(Locale.ROOT) : "forward";
        int distance = p.getInt("distance", -1);
        if (distance < 1) return Order.refuse("bridge requires distance >= 1");
        if (distance > 64) return Order.refuse("distance too large (max 64)");
        Direction face = GoalResolver.resolveCardinalDirection(self, dir);
        if (face == null) {
            return Order.refuse("unknown direction: " + dir + " (use forward|back|left|right|north|south|east|west)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("started", true);
        out.put("mode", "bridge");
        out.put("direction", dir);
        out.put("face", face.getName());
        out.put("distance", distance);
        if (blockId != null) out.put("block", blockId);
        return new Order(new BridgeProcess(face, distance, blockId), out);
    }

    public static Order sleep(Params p, LivingEntity self) {
        BlockPos explicit = p.getPos("pos");
        int radius = p.getIntClamped("radius", 16, 1, 64);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("started", true);
        if (explicit != null) out.put("pos", posMap(explicit));
        out.put("radius", radius);
        return new Order(new SleepProcess(explicit, radius), out);
    }

    public static Order build(Params p, LivingEntity self) {
        BlockPos origin = p.getPos("origin");
        if (origin == null) return Order.refuse("origin required");
        Object schemObj = p.get("schematic");
        Object schemB64Obj = p.get("schematicBase64");
        if (schemObj != null && schemB64Obj != null) {
            return Order.refuse("specify either schematic OR schematicBase64, not both");
        }
        Schematic s;
        try {
            if (schemB64Obj instanceof String b64 && !b64.isBlank()) {
                byte[] bytes;
                try { bytes = java.util.Base64.getDecoder().decode(b64.trim()); }
                catch (IllegalArgumentException e) { return Order.refuse("schematicBase64: invalid base64"); }
                s = Schematic.fromSpongeSchem(bytes);
            } else if (schemObj instanceof Map<?, ?> schem) {
                s = Schematic.parse(schem);
            } else {
                return Order.refuse("schematic (procedural object) or schematicBase64 (sponge .schem bytes) required");
            }
        } catch (RuntimeException e) {
            return Order.refuse("schematic: " + e.getMessage());
        }
        return new Order(new BuildProcess(origin, s), Map.of("ok", true, "started", true, "origin", posMap(origin),
                "size", Map.of("w", s.w, "h", s.h, "d", s.d), "blocks", s.entries.size()));
    }

    public static Order follow(Params p, LivingEntity self) {
        String entityType = p.getString("entityType");
        String name = p.getString("name");
        int radius = p.getIntClamped("radius", 3, 1, 16);
        int maxIdleTicks = p.getIntClamped("maxIdleTicks", 0, 0, 100_000);
        int giveUpTicks = p.getIntClamped("giveUpTicks", FollowProcess.DEFAULT_GIVE_UP_TICKS, 0, 100_000);
        if (entityType == null && name == null) return Order.refuse("entityType or name required");
        // The same route object goto takes, minus what a follow has no use for: it already tracks an
        // entity, so via points, an entity leash and the fly mode are refused rather than silently dropped.
        RouteParams.Parsed route;
        try { route = RouteParams.parse(p.getMap("route")); }
        catch (IllegalArgumentException e) { return Order.refuse(e.getMessage()); }
        if (!route.via().isEmpty()) return Order.refuse("route.via: follow has no waypoints");
        if (route.entityLeash() != null) return Order.refuse("route.leash.entity: follow already tracks an entity; give a center");
        if (route.fly()) return Order.refuse("route.mode: follow cannot fly");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true); r.put("started", true);
        if (entityType != null) r.put("entityType", entityType);
        if (name != null) r.put("name", name);
        r.put("radius", radius);
        if (maxIdleTicks > 0) r.put("maxIdleTicks", maxIdleTicks);
        r.put("giveUpTicks", giveUpTicks);
        return new Order(new FollowProcess(entityType, name, radius, maxIdleTicks, giveUpTicks, route.profile()), r);
    }

    public static Order explore(Params p, LivingEntity self) {
        int cx = p.getInt("centerX", Integer.MIN_VALUE);
        int cz = p.getInt("centerZ", Integer.MIN_VALUE);
        if (cx == Integer.MIN_VALUE || cz == Integer.MIN_VALUE) return Order.refuse("centerX and centerZ required");
        int maxChunks = p.getIntClamped("maxChunks", 16, 1, 64);
        return new Order(new ExploreProcess(cx, cz, maxChunks),
                Map.of("ok", true, "started", true, "centerX", cx, "centerZ", cz, "maxChunks", maxChunks));
    }

    public static Order runAway(Params p, LivingEntity self) {
        BlockPos src = p.getPos("from");
        int minDist = p.getIntClamped("minDist", 16, 4, 64);
        if (src == null) src = BlockPos.containing(self.getX(), self.getY(), self.getZ());
        return new Order(new RunAwayProcess(src, minDist),
                Map.of("ok", true, "started", true, "from", posMap(src), "minDist", minDist));
    }

    public static Order elytraFly(Params p, LivingEntity self) {
        BlockPos target = p.getPos("pos");
        Float yaw = p.getFloat("yaw");
        boolean hasPitch = p.get("pitch") instanceof Number;
        float pitch = (float) p.getDouble("pitch", 0.0);
        // Reactive sim-lookahead control (milestone B): used when a 3D target is given and no fixed
        // test-pitch is pinned (explicit pitch forces the fixed-heading glide rig); can be forced on/off
        // via `reactive`.
        boolean reactive = target != null && p.getBool("reactive", !hasPitch);
        boolean fireworks = reactive
                ? !Boolean.FALSE.equals(p.get("fireworks"))   // reactive: boost on by default
                : p.getBool("fireworks");
        int fwEvery = p.getIntClamped("fireworkEveryTicks", 40, 5, 400);
        int maxTicks = p.getIntClamped("ticks", reactive ? 2000 : 200, 1, 20_000);
        double stopXZ = p.getDouble("stopXZDist", 3.0);
        // Ground fallback (milestone D): with no usable elytra, optionally walk to the target via the
        // normal pathfinder instead of failing.
        ItemStack chest = self.getItemBySlot(EquipmentSlot.CHEST);
        boolean flyable = chest.is(Items.ELYTRA)
                && chest.getMaxDamage() > 0 && chest.getDamageValue() < chest.getMaxDamage() - 1;
        if (!flyable && target != null && p.getBool("groundFallback")) {
            int near = p.getIntClamped("near", 1, 0, 64);
            Goal g = near > 0 ? new Goal.Near(target, near) : new Goal.Block(target);
            return new Order(new IntentProcess(new Intent(g)), Map.of("ok", true, "started", true, "mode", "groundFallback",
                    "reason", "no usable elytra", "goal", g.toString()));
        }
        return new Order(new ElytraProcess(target, yaw, pitch, fireworks, fwEvery, maxTicks, stopXZ, reactive),
                Map.of("ok", true, "started", true, "mode", reactive ? "reactive" : (target != null ? "goal" : "glide"),
                        "pitch", pitch, "fireworks", fireworks));
    }

    /** {@code {x, y, z}}, the shape every reply gives a cell in. */
    static Map<String, Object> posMap(BlockPos p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", p.getX());
        m.put("y", p.getY());
        m.put("z", p.getZ());
        return m;
    }
}
