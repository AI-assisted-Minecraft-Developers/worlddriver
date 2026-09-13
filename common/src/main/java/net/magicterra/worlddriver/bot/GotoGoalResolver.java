package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.model.Params;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.Map;

import static net.magicterra.worlddriver.bot.GoalResolver.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;

/**
 * Goal parsing for {@code mc.bot.goto}. Extracted verbatim from BotApiImpl;
 * {@code mcGoto} delegates here. The {@code waypoints} map (named positions
 * owned by the bot impl) is passed in so a {@code waypoint:} selector resolves.
 *
 * <p>Only the GOAL lives here — the part that needs the player and the live world. The route
 * conditions ({@code route}: bias, capability, hard constraints, via, the entity leash) are
 * {@link RouteParams#parse}, a pure function shared with {@code mc.bot.follow} and with the
 * dedicated-server scenes.
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
                // Three doubles, not `player`: widening a LocalPlayer into blockPosOf(Entity)
                // makes the verifier load that class. The two blockPosOf(e) calls above are a
                // genuine Entity from lvl.getEntity / findNearestEntity and stay as they are.
                BlockPos origin = blockPosOf(player.getX(), player.getY(), player.getZ());
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

    /** {@code route.requireTool: 'minecraft:iron_pickaxe'} — fail the goto up front unless the
     *  item is in the player inventory. Presence-only: the Walker already auto-equips the best
     *  tool per dig (Avatar.selectTool), and mid-run tool loss is out of scope here. */
    static void checkRequiredTool(String id, LocalPlayer player) {
        if (id == null || id.isBlank()) return;
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
