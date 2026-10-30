package net.magicterra.worlddriver.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.game.Mover;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.Travel;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.Trip;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.Intent;
import net.magicterra.worlddriver.bot.behavior.IntentProcess;
import net.magicterra.worlddriver.bot.behavior.RouteEvents;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * What a goto starts on a body: the walk under the route, or the flight a {@code fly} route asks for. Shared by
 * {@code self} and the named bots; the preview and {@code planId} variants are the client's planner and stay with it.
 *
 * <p>No client type here, for the reason {@link GoalResolver} gives.
 */
public final class GotoOrders {
    private GotoOrders() {}

    public sealed interface Order permits Start, Launch, Refuse {}

    public record Start(BotProcess process, Trip trip) implements Order {}

    /** Work beside the held process, a preview: {@code launcher} takes the admitted action and ends it. */
    public record Launch(Consumer<ActionHandle> launcher) implements Order {}

    /** A valid request this body or world cannot take now; nothing starts. */
    public record Refuse(RefusalReason reason, String message) implements Order, Resolution {}

    /** The resolved goal and route of a request, or why it cannot be resolved now. */
    public sealed interface Resolution permits Resolved, Refuse {}

    public record Resolved(Goal goal, RouteParams.Parsed route) implements Resolution {}

    /** Resolve on the body's thread. */
    public static Resolution resolve(
            GotoContracts.Params p,
            LivingEntity self,
            Map<String, BlockPos> waypoints,
            Function<String, Entity> nearestOfType) {
        RouteParams.Parsed route = RouteParams.parse(p.route().orElse(Route.NONE));
        if (route.requireTool() != null) {
            if (!(self instanceof Player player)) {
                return new Refuse(
                        RefusalReason.UNSUPPORTED_CAPABILITY,
                        "route.requireTool reads an inventory, and this body has none");
            }
            if (!GotoGoalResolver.carries(player, route.requireTool())) {
                return new Refuse(
                        RefusalReason.UNSUPPORTED_CAPABILITY, "required tool not in inventory: " + route.requireTool());
            }
        }
        Goal goal;
        try {
            goal = GotoGoalResolver.resolve(p.target().orElseThrow(), p.invert(), self, waypoints, nearestOfType);
        } catch (GotoGoalResolver.Unavailable e) {
            return new Refuse(RefusalReason.INVALID_WORLD_TARGET, e.getMessage());
        }
        return new Resolved(goal, route);
    }

    /** The goals in walking order: each via cell within one block, then the goal. */
    public static List<Goal> targets(Resolved r) {
        List<Goal> targets = new ArrayList<>();
        for (BlockPos v : r.route().via()) {
            targets.add(new Goal.Near(v, 1));
        }
        targets.add(r.goal());
        return targets;
    }

    /** Walk, fly when the route says so, or hand the trip to the entity's own mover when it has one. */
    public static Order start(Resolved r, LivingEntity self, RouteEvents.Emitter events) {
        if (r.route().fly()) {
            return fly(r.goal(), self);
        }
        RouteParams.Parsed route = r.route();
        Optional<Mover> mover = MobBot.moverOf(self);
        if (mover.isPresent() && MoverProcess.covers(targets(r))) {
            return new Start(
                    new MoverProcess(mover.get(), targets(r)),
                    new Trip(r.goal().toString(), route.via().size(), Travel.MOVER, Optional.empty()));
        }
        IntentProcess walk = new IntentProcess(
                new Intent(
                        targets(r),
                        route.profile().bias(),
                        route.profile().capability(),
                        route.profile().constraints(),
                        route.entityLeash()),
                events);
        return new Start(walk, new Trip(r.goal().toString(), route.via().size(), Travel.WALK, Optional.empty()));
    }

    /**
     * {@code mc.bot.elytraFly}'s order for the goal's cell, walking there when the body wears no usable elytra. The
     * contract already turned away targets with no cell.
     */
    private static Order fly(Goal goal, LivingEntity self) {
        BlockPos target = goal.targetPos();
        if (target == null) {
            return new Refuse(
                    RefusalReason.INVALID_WORLD_TARGET, "flying needs a target cell, and " + goal + " has none");
        }
        MoveContracts.ElytraParams flight = new MoveContracts.ElytraParams(
                BotRegistry.SELF,
                Optional.of(Positions.position(target)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                40,
                Optional.empty(),
                3.0,
                true,
                1,
                0);
        return switch (VerbOrders.elytraFly(flight, self)) {
            case VerbOrders.Refuse<?> r -> new Refuse(r.reason(), r.message());
            case VerbOrders.Start<?> s ->
                new Start(
                        s.process(),
                        new Trip(
                                goal.toString(),
                                0,
                                s.process() instanceof IntentProcess ? Travel.WALK_WITHOUT_ELYTRA : Travel.FLY,
                                Optional.empty()));
        };
    }
}
