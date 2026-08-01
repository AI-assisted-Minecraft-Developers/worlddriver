package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ColumnRadius;
import net.magicterra.worlddriver.bot.pathfinder.constraints.LeashHardRadius;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoWater;
import net.magicterra.worlddriver.bot.pathfinder.constraints.YCeil;
import net.magicterra.worlddriver.bot.pathfinder.constraints.YFloor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.AvoidRegion;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.LeashAnchor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.PreferYBand;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.ShorelineHug;
import net.magicterra.worlddriver.bot.process.EntityLeash;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static net.magicterra.worlddriver.bot.GoalResolver.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;

/**
 * Goal parsing for {@code mc.bot.goto}. Extracted verbatim from BotApiImpl;
 * {@code mcGoto} delegates here. The {@code waypoints} map (named positions
 * owned by the bot impl) is passed in so a {@code waypoint:} selector resolves.
 */
final class GotoGoalResolver {

    private GotoGoalResolver() {}

    /**
     * Resolve a goal from goto params on the client thread (some selectors need
     * the player position or live world state). Throws IllegalArgumentException
     * with a descriptive message when a selector matches but cannot be resolved
     * (e.g. block not found in radius, entity id stale, unknown direction);
     * returns null when no selector is recognized so the caller can emit the
     * "missing goal" error.
     */
    static Goal resolveGoal(Params p, LocalPlayer player, Map<String, BlockPos> waypoints) {
        Goal base = resolveBaseGoal(p, player, waypoints);
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
    private static Goal resolveBaseGoal(Params p, LocalPlayer player, Map<String, BlockPos> waypoints) {
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

    /**
     * Parse the per-intent cost bias args (avoid / preferY / leash) into modifiers
     * appended to the Intent. Empty when none supplied → plain navigation, byte-
     * identical to A4a. Malformed entries are skipped per-item (not thrown) — a
     * bad zone drops just itself, unlike {@code avoidPoints} in {@link SettingsCommand}
     * which rejects the whole list.
     */
    static List<CostModifier> resolveBias(Params p) {
        List<CostModifier> bias = new ArrayList<>();
        // avoid: [{x,y,z,radius?,penalty?}, ...] — per-intent route-around zones.
        if (p.get("avoid") instanceof List<?> zones) {
            for (Object o : zones) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object xo = m.get("x"), yo = m.get("y"), zo = m.get("z");
                if (!(xo instanceof Number) || !(yo instanceof Number) || !(zo instanceof Number)) continue;
                double x = ((Number) xo).doubleValue();
                double y = ((Number) yo).doubleValue();
                double z = ((Number) zo).doubleValue();
                double radius = Params.toDouble(m.get("radius"), 8.0);
                double penalty = Params.toDouble(m.get("penalty"), 250.0);
                bias.add(new AvoidRegion(x, y, z, radius, penalty));
            }
        }
        // preferY: {min,max,weight?} — hug a Y band (e.g. "2nd floor", "surface").
        if (p.get("preferY") instanceof Map<?, ?> b) {
            Object loO = b.get("min"), hiO = b.get("max");
            if (loO instanceof Number lo && hiO instanceof Number hi) {
                double weight = Params.toDouble(b.get("weight"), 10.0);
                int loY = (int) Math.floor(lo.doubleValue()), hiY = (int) Math.floor(hi.doubleValue());
                bias.add(new PreferYBand(Math.min(loY, hiY), Math.max(loY, hiY), weight));
            }
        }
        // hugShore: true|{weight} — 沿河岸走: tax nodes with no adjacent water so the
        // route glues to the waterline (pair with forbidWater to stay dry).
        Object hs = p.get("hugShore");
        if (hs instanceof Boolean b && b) bias.add(new ShorelineHug(30.0));
        else if (hs instanceof Map<?, ?> m) bias.add(new ShorelineHug(Params.toDouble(m.get("weight"), 30.0)));
        // leash: {x,y,z,radius,weight?} — soft-tether to a static anchor. An
        // entity-keyed leash (leash:{entity:...}) is handled dynamically by
        // resolveEntityLeash instead — skip the static parse here so it isn't
        // ALSO added as a fixed-point modifier.
        if (p.get("leash") instanceof Map<?, ?> l && !(l.get("entity") instanceof String e && !e.isBlank())) {
            Object xo = l.get("x"), yo = l.get("y"), zo = l.get("z"), ro = l.get("radius");
            if (xo instanceof Number && yo instanceof Number && zo instanceof Number && ro instanceof Number) {
                double x = ((Number) xo).doubleValue();
                double y = ((Number) yo).doubleValue();
                double z = ((Number) zo).doubleValue();
                double radius = ((Number) ro).doubleValue();
                double weight = Params.toDouble(l.get("weight"), 20.0);
                bias.add(new LeashAnchor(x, y, z, radius, weight));
            }
        }
        return bias;
    }

    /**
     * Resolve the capability envelope for this goto. {@code forbidParkour:true}
     * OR {@code capability:"walk"} forbids the PARKOUR move-type; any other
     * capability string is a no-op (A2a only wires "walk"). {@code dive:true}
     * (A5) OPTS IN to {@link Capability#DIVE} — a planned surface dive is
     * otherwise pruned from every search (see {@link net.magicterra.worlddriver.bot.pathfinder.moves.SurfaceDive}).
     * Both are independent gates on the same profile — {@code CapabilityProfile.ALL}
     * only when NEITHER is supplied, byte-identical to A4a/A4b/pre-A5.
     */
    static CapabilityProfile resolveCapability(Params p) {
        boolean forbidParkour = p.getBool("forbidParkour")
                || (p.get("capability") instanceof String s && s.trim().equalsIgnoreCase("walk"));
        boolean dive = p.getBool("dive");
        if (!forbidParkour && !dive) return CapabilityProfile.ALL;
        Set<Capability> forbidden = forbidParkour ? EnumSet.of(Capability.PARKOUR) : EnumSet.noneOf(Capability.class);
        Set<Capability> optIn = dive ? EnumSet.of(Capability.DIVE) : EnumSet.noneOf(Capability.class);
        return new CapabilityProfile(forbidden, optIn);
    }

    /**
     * Parse the per-intent hard constraints (yFloor / yCeil / leashHard) into
     * {@link Constraint}s appended to the Intent. Empty when none supplied →
     * plain navigation, byte-identical to A4a/A4b. Malformed entries are
     * skipped per-item (not thrown), mirroring {@link #resolveBias}'s leniency.
     */
    static List<Constraint> resolveConstraints(Params p) {
        List<Constraint> cs = new ArrayList<>();
        // yFloor: N — hard-prune any move whose destination is below Y=N.
        if (p.get("yFloor") instanceof Number n) {
            cs.add(new YFloor((int) Math.floor(n.doubleValue())));
        }
        // yCeil: N — hard-prune any move whose destination is above Y=N.
        if (p.get("yCeil") instanceof Number n) {
            cs.add(new YCeil((int) Math.floor(n.doubleValue())));
        }
        // leashHard: {x,y,z,radius} — hard tether; route may not leave the radius at all.
        // An entity-keyed leashHard is handled dynamically by resolveEntityLeash instead.
        if (p.get("leashHard") instanceof Map<?, ?> l && !(l.get("entity") instanceof String e && !e.isBlank())) {
            Object xo = l.get("x"), yo = l.get("y"), zo = l.get("z"), ro = l.get("radius");
            if (xo instanceof Number && yo instanceof Number && zo instanceof Number && ro instanceof Number) {
                double x = ((Number) xo).doubleValue();
                double y = ((Number) yo).doubleValue();
                double z = ((Number) zo).doubleValue();
                double radius = ((Number) ro).doubleValue();
                cs.add(new LeashHardRadius(x, y, z, radius));
            }
        }
        // column: {x,z,radius} — hard XZ cylinder: the route may not leave `radius` of the
        // (x,z) vertical line, but Y is UNCONSTRAINED. Binds a vertical goal (y:N / direction:up|down)
        // to the start column so it pillars/digs a fresh shaft instead of drifting sideways to
        // cheap far-off air (the ascent-drift gap). Caller passes its own current XZ as (x,z).
        if (p.get("column") instanceof Map<?, ?> c) {
            Object xo = c.get("x"), zo = c.get("z"), ro = c.get("radius");
            if (xo instanceof Number && zo instanceof Number && ro instanceof Number) {
                double x = ((Number) xo).doubleValue();
                double z = ((Number) zo).doubleValue();
                double radius = ((Number) ro).doubleValue();
                cs.add(new ColumnRadius(x + 0.5, z + 0.5, radius));
            }
        }
        // forbidWater: true — never route through a water cell (hard prune).
        if (p.getBool("forbidWater")) cs.add(new NoWater());
        // forbidDig: true — never plan a block-breaking edge (per-intent allowBreak-off).
        if (p.getBool("forbidDig")) cs.add(new NoBreak());
        return cs;
    }

    /** leash:{entity:'X',radius?,weight?} / leashHard:{entity:'X',radius} → dynamic anchor.
     *  When the entity key is present the STATIC x/y/z parse is skipped for that key
     *  (dynamic wins); absent → null and the static path runs exactly as before. */
    static EntityLeash resolveEntityLeash(Params p) {
        if (p.get("leash") instanceof Map<?, ?> l && l.get("entity") instanceof String e && !e.isBlank()) {
            double radius = Params.toDouble(l.get("radius"), 8.0);
            double weight = Params.toDouble(l.get("weight"), 20.0);
            return new EntityLeash(e, radius, weight, false);
        }
        if (p.get("leashHard") instanceof Map<?, ?> l && l.get("entity") instanceof String e && !e.isBlank()) {
            double radius = Params.toDouble(l.get("radius"), 8.0);
            return new EntityLeash(e, radius, 0, true);
        }
        return null;
    }

    /** requireTool:'minecraft:iron_pickaxe' — fail the goto up front unless the item is
     *  in the player inventory. Presence-only: the Walker already auto-equips the best
     *  tool per dig (Avatar.selectTool), and mid-run tool loss is out of scope here. */
    static void checkRequiredTool(Params p, LocalPlayer player) {
        if (!(p.get("requireTool") instanceof String id) || id.isBlank()) return;
        String want = id.contains(":") ? id : "minecraft:" + id;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(want))
                return;
        }
        throw new IllegalArgumentException("required tool not in inventory: " + want);
    }
}
