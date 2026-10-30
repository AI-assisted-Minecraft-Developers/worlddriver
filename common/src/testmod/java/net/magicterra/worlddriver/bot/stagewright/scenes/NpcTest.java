package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Npc;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.BotTaskBlockingApi;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.control.ControlBlockingApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * The NPC bot on five terrains: down a staircase, over a one-block gap, across open water, out of the water onto a
 * bank, and up a ladder. A sixth test orders it the work that needs hands.
 *
 * <p>The NPC is a driven piglin the roster adopts ({@link Staging#drivenPiglin}), so it walks as an order by name walks
 * any mob: a {@code mc.bot.goto} on its own bot, ticked by the server.
 *
 * <p><b>Two arms on one terrain.</b> The driven piglin walks the course first. A server bot then walks the same course
 * with the same order, so a red on the NPC arm arrives with the player's reading beside it: both failing is the rig,
 * only the NPC failing is a difference between the two driven entities.
 *
 * <p>Break and place are off under the pinned baseline. The NPC has no hands, so a course that needed either would
 * measure the refusal rather than the movement.
 */
@Scene("wd.scenes")
@Isolated // see ProcessTest: these tests hold the server thread for long spans
@Execution(ExecutionMode.SAME_THREAD)
class NpcTest {

    /** Ticks with no order before the walk starts: after a spawn the first move finds no floor. */
    private static final int SETTLE_STEPS = 5;

    /** Six treads, each one block along +x and one block down, onto a landing. */
    @Test
    void npcWalksDownAStaircase(Game game, @Plot.Centered @Plot.Size(32) Plot plot, TestReporter reporter) {
        final int steps = 6;
        stage(game);
        for (int i = 0; i <= steps; i++) plot.fill(i, -steps - 1, -1, i, -i, 1, Blocks.STONE);
        plot.fill(steps + 1, -steps - 1, -1, steps + 2, -steps, 1, Blocks.STONE);
        BlockPos bottom = plot.at(steps + 1, -steps + 1, 0);
        compare(
                game,
                reporter,
                "walk down a staircase of " + steps + " steps",
                plot.at(0, 1, 0),
                bottom,
                400,
                e -> e.blockPosition().equals(bottom) && e.onGround());
    }

    /** A one-block gap across a three-wide walk, over a pit four deep that nothing climbs out of. */
    @Test
    void npcJumpsAOneBlockGap(Game game, @Plot.Centered @Plot.Size(32) Plot plot, TestReporter reporter) {
        stage(game);
        plot.fill(-3, -4, -1, 5, -4, 1, Blocks.STONE);
        plot.fill(-3, 0, -1, 0, 0, 1, Blocks.STONE);
        plot.fill(2, 0, -1, 5, 0, 1, Blocks.STONE);
        BlockPos o = plot.at(0, 0, 0);
        BlockPos goal = plot.at(4, 1, 0);
        compare(
                game,
                reporter,
                "jump across a one-block-wide gap",
                plot.at(-2, 1, 0),
                goal,
                300,
                e -> e.blockPosition().getX() >= o.getX() + 2 && e.onGround() && e.getY() >= goal.getY() - 0.01);
    }

    /** Eight cells of water three deep between two banks; the course ends in the last water cell. */
    @Test
    void npcSwimsAcrossOpenWater(Game game, @Plot.Centered @Plot.Size(32) Plot plot, TestReporter reporter) {
        final int span = 8;
        stage(game);
        plot.fill(-3, -3, -2, span + 3, -3, 2, Blocks.STONE);
        plot.fill(-3, -2, -2, span + 3, 0, -2, Blocks.STONE);
        plot.fill(-3, -2, 2, span + 3, 0, 2, Blocks.STONE);
        plot.fill(-3, -2, -1, -1, 0, 1, Blocks.STONE);
        plot.fill(span, -2, -1, span + 3, 0, 1, Blocks.STONE);
        plot.fill(0, -2, -1, span - 1, 0, 1, Blocks.WATER);
        BlockPos goal = plot.at(span - 1, 0, 0);
        compare(
                game,
                reporter,
                "swim across " + span + " blocks of open water",
                plot.at(-2, 1, 0),
                goal,
                400,
                e -> e.blockPosition().getX() >= goal.getX() && e.isInWater());
    }

    /** A pool three deep with a bank flush with the water's top block; the course starts in the water. */
    @Test
    void npcClimbsOutOfWater(Game game, @Plot.Centered @Plot.Size(32) Plot plot, TestReporter reporter) {
        stage(game);
        plot.fill(-3, -3, -3, 3, -3, 6, Blocks.STONE);
        plot.fill(-3, -2, -3, 3, 0, -3, Blocks.STONE);
        plot.fill(-3, -2, -3, -3, 0, 6, Blocks.STONE);
        plot.fill(3, -2, -3, 3, 0, 6, Blocks.STONE);
        plot.fill(-2, -2, 2, 2, 0, 6, Blocks.STONE);
        plot.fill(-2, -2, -2, 2, 0, 1, Blocks.WATER);
        BlockPos o = plot.at(0, 0, 0);
        BlockPos goal = plot.at(0, 1, 4);
        compare(
                game,
                reporter,
                "climb out of the water onto a flush bank",
                o,
                goal,
                400,
                e -> e.blockPosition().getZ() >= o.getZ() + 3 && e.onGround() && e.getY() >= goal.getY() - 0.01);
    }

    /** A ladder five high on the west face of a block whose top is the platform. */
    @Test
    void npcClimbsALadder(Game game, @Plot.Centered @Plot.Size(32) Plot plot, TestReporter reporter) {
        final int rise = 5;
        stage(game);
        plot.fill(-2, 0, -2, 3, 0, 2, Blocks.STONE);
        plot.fill(1, 1, -1, 3, rise, 1, Blocks.STONE);
        game.runOnServer(s -> {
            var level = s.getLevel(plot.dimension());
            var ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
            for (int y = 1; y <= rise; y++) level.setBlockAndUpdate(plot.at(0, y, 0), ladder);
        });
        BlockPos o = plot.at(0, 0, 0);
        BlockPos goal = plot.at(2, rise + 1, 0);
        compare(
                game,
                reporter,
                "climb a ladder " + rise + " blocks high",
                plot.at(-1, 1, 0),
                goal,
                400,
                e -> e.getY() >= goal.getY() - 0.01
                        && e.onGround()
                        && e.blockPosition().getX() >= o.getX() + 1);
    }

    /**
     * Work an entity without hands cannot do, ordered of the NPC by name: each order has to be refused as
     * {@code unsupported_capability} before anything starts and before it has changed a block. Break and place are
     * switched on, so the refusal comes from the NPC and not from the config.
     */
    @Test
    void npcRefusesWorkThatNeedsHands(Game game, @Plot.Centered Plot plot) {
        stage(game).set("allowBreak", true).set("allowPlace", true);
        plot.fill(-3, -1, -3, 3, -1, 3, Blocks.STONE);
        Npc npc = Staging.drivenPiglin(game, plot.at(0, 0, 0));
        String ref = npc.ref();
        int towerTop = plot.at(0, 3, 0).getY();
        Map<String, Responses.ActionResponse<?>> orders = game.onServer(s -> {
            BotTaskBlockingApi tasks = game.api(BotTaskBlockingApi::of);
            Map<String, Responses.ActionResponse<?>> out = new LinkedHashMap<>();
            out.put(
                    "construct bridge",
                    tasks.construct(new BuildContracts.ConstructParams(
                            ref,
                            new BuildContracts.Bridge(
                                    BuildContracts.BridgeDirection.EAST, 3, Optional.of("minecraft:cobblestone")),
                            0)));
            out.put(
                    "construct tower",
                    tasks.construct(new BuildContracts.ConstructParams(
                            ref,
                            new BuildContracts.Tower(
                                    Optional.empty(),
                                    Optional.of(towerTop),
                                    Optional.of("minecraft:cobblestone"),
                                    false),
                            0)));
            out.put(
                    "mine",
                    tasks.mine(new GatherContracts.MineParams(
                            ref, List.of("minecraft:stone"), Optional.empty(), 1, 4, 0)));
            out.put("craft", tasks.craft(new GatherContracts.CraftParams(ref, "minecraft:stick", 1, 0)));
            out.put(
                    "smelt",
                    tasks.smelt(new GatherContracts.SmeltParams(
                            ref, "minecraft:raw_iron", 1, Optional.of("minecraft:coal"), 0)));
            out.put(
                    "combat",
                    tasks.combat(new CombatContracts.Params(
                            ref,
                            CombatContracts.Mode.KILL,
                            Optional.of(new CombatContracts.FoeType("minecraft:zombie")),
                            false,
                            0)));
            return out;
        });
        for (Map.Entry<String, Responses.ActionResponse<?>> order : orders.entrySet()) {
            Responses.ActionResponse<?> r = order.getValue();
            boolean refused = r instanceof Responses.Refused<?> no
                    && no.reason() == Responses.RefusalReason.UNSUPPORTED_CAPABILITY;
            game.check(
                    refused,
                    order.getKey() + " on an entity without hands is refused as unsupported_capability "
                            + "before it starts: " + r);
        }
        boolean busy = game.onServer(s -> status(game, ref).busy());
        game.check(!busy, "the NPC is on no task after the refusals: busy=" + busy);
        long changed = game.onServer(s -> {
            var level = s.getLevel(plot.dimension());
            int floor = plot.at(0, 0, 0).getY();
            return BlockPos.betweenClosedStream(plot.at(-3, -1, -3), plot.at(3, 3, 3))
                    .filter(p -> !level.getBlockState(p).is(p.getY() < floor ? Blocks.STONE : Blocks.AIR))
                    .count();
        });
        game.check(changed == 0, "no block is changed before the request is refused: " + changed + " blocks changed");
    }

    /** The bot {@code ref}'s own status. Server thread. */
    private static ControlContracts.BotStatus status(Game game, String ref) {
        Responses.ImmediateResponse r =
                game.api(ControlBlockingApi::of).status(new ControlContracts.StatusParams(Optional.of(ref)));
        if (!(r instanceof Responses.Status s) || s.status().isEmpty())
            return fail("mc.bot.status " + ref + " answered " + r);
        return s.status().get();
    }

    private record Leg(int ticks, boolean arrived, String ended) {}

    /**
     * Both arms on the course just staged; the NPC's arrival is the test's verdict. The NPC walks on the server's ticks
     * (a mob moves in its own entity tick); the player arm then walks within one server task, the bot ticked by hand.
     */
    private static void compare(
            Game game,
            TestReporter reporter,
            String what,
            BlockPos start,
            BlockPos goal,
            int maxTicks,
            Predicate<LivingEntity> arrived) {
        Npc npc = Staging.drivenPiglin(game, start);
        game.ticks(SETTLE_STEPS);
        game.runOnServer(s -> Calls.started(
                "goto " + goal.toShortString(),
                game.api(BotTaskBlockingApi::of)
                        .goTo(new GotoContracts.Params(
                                npc.ref(),
                                Optional.of(Targets.block(goal)),
                                false,
                                Optional.empty(),
                                Optional.empty(),
                                false,
                                Optional.empty(),
                                0))));
        int[] t = {0};
        double[] minY = {npc.onServer(Mob::getY)};
        boolean[] at = {false};
        game.await()
                .within(maxTicks + 20)
                .as("the NPC arrives or spends its " + maxTicks + " ticks")
                .until(() -> {
                    Mob e = npc.onServer(m -> m);
                    t[0]++;
                    minY[0] = Math.min(minY[0], e.getY());
                    at[0] = arrived.test(e);
                    return at[0] || t[0] >= maxTicks;
                });
        Leg n = game.onServer(s -> {
            Mob e = npc.onServer(m -> m);
            Leg leg = new Leg(t[0], at[0], ended(t[0], e, minY[0], status(game, npc.ref()), at[0]));
            // The NPC leaves the course before the player walks it: a mob standing on the goal would push the player.
            game.api(ControlBlockingApi::of).roster(new ControlContracts.Remove(npc.ref()));
            e.discard();
            return leg;
        });
        reporter.publishEntry("npc.drive", n.ended());

        SceneBot bot = SceneBot.spawn(game, start);
        Leg p = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            bot.step(SETTLE_STEPS);
            bot.goTo(Targets.block(goal));
            double lowest = fp.getY();
            boolean there = false;
            int i = 0;
            while (i < maxTicks && !there) {
                bot.tick();
                i++;
                lowest = Math.min(lowest, fp.getY());
                there = arrived.test(fp);
            }
            Leg leg = new Leg(i, there, ended(i, fp, lowest, bot.status(), there));
            bot.cancel();
            return leg;
        });
        reporter.publishEntry("player.drive", p.ended());
        if (!p.arrived()) {
            fail("THE RIG, not the NPC: the server-side player also failed to " + what + " on the same terrain: "
                    + p.ended());
        }
        game.check(n.arrived(), "the NPC entity manages to " + what + ": " + n.ended());
        reporter.publishEntry("ticks", "NPC " + n.ticks() + ", server-side player " + p.ticks());
    }

    private static String ended(int ticks, LivingEntity e, double minY, ControlContracts.BotStatus status, boolean at) {
        ControlContracts.Slot go = status.slots().goTo();
        return String.format(
                Locale.ROOT,
                "%d ticks, entity=(%.2f,%.2f,%.2f), lowest y=%.2f, busy=%b, goto=%s, arrived=%b",
                ticks,
                e.getX(),
                e.getY(),
                e.getZ(),
                minY,
                status.busy(),
                go.endReason().orElse(go.active() ? "running" : "-"),
                at);
    }

    /** Pin the untimed baseline with break and place off. */
    private static Settings stage(Game game) {
        return Settings.pinUntimed(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("navigatorDebug", true);
    }
}
