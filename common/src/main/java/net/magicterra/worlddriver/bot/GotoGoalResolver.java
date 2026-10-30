package net.magicterra.worlddriver.bot;

import static net.magicterra.worlddriver.bot.GoalResolver.applyDirection;
import static net.magicterra.worlddriver.bot.GoalResolver.findNearestStandForBlock;
import static net.magicterra.worlddriver.bot.GoalResolver.snapped;
import static net.magicterra.worlddriver.game.body.BotUtil.blockPosOf;

import java.util.Map;
import java.util.function.Function;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.GoalMode;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.Target;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.config.BotConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The goal of {@code mc.bot.goto}: a decoded target resolved against the body and the live world, on the body's own
 * thread. The shape is already checked by the contract, so everything thrown here is about the world: no such block in
 * range, no such entity, no such waypoint.
 *
 * <p>Only the GOAL lives here. The route conditions are {@link RouteParams#parse}, a pure function shared with
 * {@code mc.bot.follow} and with the dedicated-server scenes.
 *
 * <p><b>Free of client types, like {@link GoalResolver}</b>, because a server-side bot resolves its goal here too. The
 * one target whose answer depends on the side is {@code entityType}: the client looks through what it has loaded for
 * rendering, a server through its level. So the caller passes that lookup in.
 */
public final class GotoGoalResolver {

    private GotoGoalResolver() {}

    /** A target the world cannot satisfy now; the request itself was valid. */
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Carries {@code message} to the caller; no cause and no stack trace are recorded. */
        public Unavailable(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Resolves a goto target to the goal the navigator walks to, inverted when {@code invert} is set.
     *
     * @param waypoints the body's saved waypoints; empty for a body that keeps none
     * @param nearestOfType the nearest entity of a registry id the body can see, or null
     */
    public static Goal resolve(
            Target target,
            boolean invert,
            Entity self,
            Map<String, BlockPos> waypoints,
            Function<String, Entity> nearestOfType) {
        Goal base = base(target, self, waypoints, nearestOfType);
        // Baritone GoalInverted: flee whatever the resolved goal converges on.
        return invert ? new Goal.Inverted(base) : base;
    }

    // The Axis case binds a name it never reads: Java 21 has no unnamed pattern without preview features.
    @SuppressWarnings("PMD.UnusedLocalVariable")
    private static Goal base(
            Target target, Entity self, Map<String, BlockPos> waypoints, Function<String, Entity> nearestOfType) {
        return switch (target) {
            case GotoContracts.Position t ->
                cellGoal(new BlockPos(t.pos().x(), t.pos().y(), t.pos().z()), t.goalMode(), t.near());
            case GotoContracts.Column t -> new Goal.XZ(t.x(), t.z(), t.near());
            case GotoContracts.Level t -> new Goal.YLevel(t.y());
            case GotoContracts.Axis t -> new Goal.Axis(BotConfig.axisHeight);
            case GotoContracts.BlockTarget t -> {
                BlockPos stand = findNearestStandForBlock(self, t.block(), t.radius());
                if (stand == null) {
                    throw new Unavailable("no reachable '" + t.block() + "' within radius " + t.radius());
                }
                yield new Goal.Block(stand);
            }
            case GotoContracts.EntityTarget t -> {
                Level lvl = self.level();
                Entity e = lvl == null ? null : lvl.getEntity(t.entityId());
                if (e == null) {
                    throw new Unavailable("no entity with id " + t.entityId());
                }
                yield cellGoal(blockPosOf(e), t.goalMode(), t.near());
            }
            case GotoContracts.EntityType t -> {
                Entity e = nearestOfType == null ? null : nearestOfType.apply(t.type());
                if (e == null) {
                    throw new Unavailable("no '" + t.type() + "' visible nearby");
                }
                yield cellGoal(blockPosOf(e), t.goalMode(), t.near());
            }
            case GotoContracts.Heading t -> {
                // Baritone GoalStrictDirection: keep boring this way with no fixed endpoint.
                var face = snapped(self, t.direction());
                yield new Goal.StrictDirection(
                        blockPosOf(self.getX(), self.getY(), self.getZ()), face.getStepX(), face.getStepZ());
            }
            case GotoContracts.DirectionTarget t -> {
                BlockPos cell = applyDirection(self, t.direction(), t.distance());
                if (t.near() > 0) {
                    yield new Goal.Near(cell, t.near());
                }
                yield t.direction().vertical() ? new Goal.YLevel(cell.getY()) : new Goal.XZ(cell.getX(), cell.getZ());
            }
            case GotoContracts.Waypoint t -> {
                BlockPos pos = waypoints.get(t.name());
                if (pos == null) {
                    throw new Unavailable("no waypoint named '" + t.name() + "'");
                }
                yield cellGoal(pos, t.goalMode(), t.near());
            }
        };
    }

    /** {@code near > 0} always wins and relaxes to a Euclidean radius (GoalNear). */
    private static Goal cellGoal(BlockPos cell, GoalMode mode, int near) {
        if (near > 0) {
            return new Goal.Near(cell, near);
        }
        return switch (mode) {
            case IN -> new Goal.Block(cell);
            case TWO -> new Goal.TwoBlocks(cell);
            case ADJACENT -> new Goal.GetToBlock(cell);
        };
    }

    /**
     * {@code route.requireTool}: the item is somewhere in the inventory. Presence only: the navigator equips the best
     * tool per dig itself, and losing it mid-run is not watched.
     */
    public static boolean carries(Player player, String id) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
