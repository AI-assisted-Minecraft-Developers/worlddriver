package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.stagewright.Routes;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.minecraft.core.BlockPos;

/**
 * The tasks a rung hands {@link JourneyRig}: each the {@code mc.bot.*} verb that starts it on whichever bot the
 * run climbs with, and the status slot it reports into. A rung names what it wants done; the rig owns which bot
 * that is and how its end is waited for.
 */
public final class JourneyTasks {
    private JourneyTasks() {}

    /**
     * One task: {@code kind} is the status slot it reports into ({@code goto}, {@code mine}, {@code builder}, …),
     * which is also what the heartbeat prints as the task driving; {@code start} sends the verb for a bot ref.
     */
    public record Task(String kind, Function<String, CompletionStage<? extends Responses.ActionResponse<?>>> start) {}

    /** A walk that never digs its way: {@code breakPolicy=never}. */
    public static final RouteContracts.Route NO_BREAK = Routes.plain().noBreak().build();

    /** Walk to {@code target}, plainly. */
    public static Task goTo(GotoContracts.Target target) {
        return goTo(target, RouteContracts.Route.NONE);
    }

    /** Walk to {@code target} under {@code route}'s conditions. */
    public static Task goTo(GotoContracts.Target target, RouteContracts.Route route) {
        return new Task("goto", bot -> SceneBot.tasks().goTo(new GotoContracts.Params(bot, Optional.of(target), false,
                Optional.of(route), Optional.empty(), false, Optional.empty(), 0)));
    }

    /** Stand on {@code cell}. */
    public static Task goToBlock(BlockPos cell) { return goTo(Targets.block(cell)); }

    /** Stand within {@code near} blocks of {@code cell}. */
    public static Task goNear(BlockPos cell, int near) { return goTo(Targets.near(cell, near)); }

    /** Stand anywhere in the column {@code (x, z)}, or within {@code near} of it. */
    public static Task goToColumn(int x, int z, int near) { return goTo(Targets.column(x, z, near)); }

    /** Stand at feet height {@code y}, anywhere. */
    public static Task goToLevel(int y) { return goTo(Targets.level(y)); }

    /** Mine {@code quantity} of {@code blocks} within {@code radius}: the verb picks its own targets. */
    public static Task mine(List<String> blocks, int quantity, int radius) {
        return new Task("mine", bot -> SceneBot.tasks().mine(new GatherContracts.MineParams(bot, blocks, Optional.empty(),
                quantity, radius, 0)));
    }

    /** Break the one named {@code cell}, walking within reach first unless it is in reach already. */
    public static Task mineCell(BlockPos cell) {
        return new Task("mine", bot -> SceneBot.tasks().mine(GatherContracts.MineParams.cell(bot, Targets.pos(cell))));
    }

    public static Task craft(String item, int count) {
        return new Task("craft", bot -> SceneBot.tasks().craft(new GatherContracts.CraftParams(bot, item, count, 0)));
    }

    /** Smelt {@code count} of {@code item}, with {@code fuel} or, when null, whatever fuel the bag holds. */
    public static Task smelt(String item, int count, String fuel) {
        return new Task("smelt", bot -> SceneBot.tasks().smelt(new GatherContracts.SmeltParams(bot, item, count,
                Optional.ofNullable(fuel), 0)));
    }

    /**
     * Pillar up to feet height {@code targetY} with {@code block} (any placeable one when null), fetched from the
     * backpack too when {@code fromBag}.
     */
    public static Task tower(int targetY, String block, boolean fromBag) {
        return new Task("builder", bot -> SceneBot.tasks().construct(new BuildContracts.ConstructParams(bot,
                new BuildContracts.Tower(Optional.empty(), Optional.of(targetY), Optional.ofNullable(block), fromBag), 0)));
    }

    /** Fight the one entity {@code entityId} until it dies. */
    public static Task kill(int entityId) {
        return combat(new CombatContracts.FoeEntity(entityId));
    }

    /** Fight the nearest entity of {@code type} until it dies. */
    public static Task killNearest(String type) {
        return combat(new CombatContracts.FoeType(type));
    }

    private static Task combat(CombatContracts.Foe foe) {
        return new Task("combat", bot -> SceneBot.tasks().combat(new CombatContracts.Params(bot, CombatContracts.Mode.KILL,
                Optional.of(foe), false, 0)));
    }
}
