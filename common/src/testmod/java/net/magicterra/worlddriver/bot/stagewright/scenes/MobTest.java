package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.bot.stagewright.kit.ByName;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.magicterra.worlddriver.testcontent.CastingMob;
import net.magicterra.worlddriver.testcontent.NpcContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Vanilla mobs driven by the server bot stack, addressed through the API registry the way every transport calls it:
 *
 * <ul>
 *   <li>a zombie named {@code entity:<uuid>} is taken over by its first order, walks down a stone course, refuses the
 *       verbs that need hands, and gets its goals back from {@code /worlddriver mob release};
 *   <li>a driven zombie removed mid-walk ends its action as {@code bot_removed} and gives up its ref;
 *   <li>an idle adopted wolf steps off magma, and sees only what hunts it;
 *   <li>stand-ins for modded mobs: one that casts its own move control, one that ignores input, ones with a registered
 *       driver, mover or body task; and the mobs that are refused.
 * </ul>
 *
 * <p>No player entity is created. The mobs are invulnerable, so the sun and a fall measure nothing here.
 */
@Scene("wd.scenes")
class MobTest {

    /** The ref of a mob an order takes over by its uuid. */
    private static final String ENTITY = "entity:";

    private static final int COURSE = 10;
    /** Ticks for a fresh mob to be ticked at all and land. */
    private static final int SETTLE_TICKS = 100;

    /**
     * A body task registered as another mod would register one, called by its name on a mob: it starts as an action,
     * runs on the bot's tick, and its result answers {@code mc.wait.result}.
     */
    @Test
    void mobRunsARegisteredTask(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        Mob mob = stage(game, plot, NpcContent.VELOCITY_MOB.get());
        String ref = ENTITY + mob.getUUID();
        ByName.releaseAtEnd(game, ref);

        settle(game, mob);
        Map<String, Object> go = ByName.call(api, "testmod.tally", Map.of("bot", ref, "ticks", 5));
        reporter.publishEntry("tally", String.valueOf(go));
        if (ByName.actionId(go) == null) fail("the registered task did not start: " + go);
        game.await()
                .within(100)
                .as("the registered task ends")
                .until(() ->
                        !"running".equals(ByName.read(api, ByName.actionId(go)).get("state")));
        Map<?, ?> action = ByName.read(api, ByName.actionId(go));
        reporter.publishEntry("ended", String.valueOf(action));
        game.check(
                "succeeded".equals(action.get("state"))
                        && action.get("result") instanceof Map<?, ?> r
                        && "tally".equals(r.get("kind"))
                        && r.get("ticks") instanceof Number n
                        && n.intValue() == 5
                        && Boolean.TRUE.equals(r.get("onGround")),
                "the registered task ran five ticks on the mob and reported it: " + action);
    }

    /**
     * A stand-in whose own travel reads no input, with an entity driver registered for its type as another mod would:
     * the driver turns the bot's input into its motion, so the goto that fails as unresponsive on the unregistered
     * stand-in walks here.
     */
    @Test
    void mobWithRegisteredDriverWalks(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        BlockPos goal = plot.at(COURSE, 0, 0);
        Mob mob = stage(game, plot, NpcContent.VELOCITY_MOB.get());
        String ref = ENTITY + mob.getUUID();
        ByName.releaseAtEnd(game, ref);

        settle(game, mob);
        String id = start(api, reporter, ref, goal, "the stand-in");
        game.await()
                .within(400)
                .as("the walk ends")
                .until(() -> !"running".equals(ByName.read(api, id).get("state")));
        Map<?, ?> action = ByName.read(api, id);
        BlockPos stoppedAt = game.onServer(s -> mob.blockPosition());
        reporter.publishEntry("ended", stoppedAt.toShortString() + " " + action);
        game.check(
                "succeeded".equals(action.get("state")) && stoppedAt.closerThan(goal, 1.5),
                "the stand-in walks to the goal through its registered driver: stopped at " + stoppedAt.toShortString()
                        + ", " + action);
    }

    /**
     * A stand-in whose registered driver brings its own mover: the goto is handed to it whole, reports travel
     * {@code mover}, and ends where the mover put the mob, through the wall across the course that the driver's own
     * route would have to go round.
     */
    @Test
    void mobWithRegisteredMoverTravels(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        BlockPos goal = plot.at(COURSE, 0, 0);
        Mob mob = stage(game, plot, NpcContent.MOVER_MOB.get());
        plot.fill(COURSE / 2, 0, -3, COURSE / 2, 2, 3, Blocks.STONE);
        String ref = ENTITY + mob.getUUID();
        ByName.releaseAtEnd(game, ref);

        settle(game, mob);
        String id = start(api, reporter, ref, goal, "the stand-in");
        game.await()
                .within(200)
                .as("the trip ends")
                .until(() -> !"running".equals(ByName.read(api, id).get("state")));
        Map<?, ?> action = ByName.read(api, id);
        BlockPos stoppedAt = game.onServer(s -> mob.blockPosition());
        reporter.publishEntry("ended", stoppedAt.toShortString() + " " + action);
        game.check(
                "succeeded".equals(action.get("state"))
                        && String.valueOf(action).contains("travel=mover")
                        && stoppedAt.equals(goal),
                "the mover takes the trip whole and ends on the goal: stopped at " + stoppedAt.toShortString() + ", "
                        + action);
    }

    /**
     * A stand-in for a modded mob whose own tick casts its move control to its own class walks to a goal: the driver
     * writes into the mob's AI step and leaves that control in place, so the cast holds and the mob's own tick keeps
     * running.
     */
    @Test
    void mobThatCastsItsOwnControlWalks(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        BlockPos goal = plot.at(COURSE, 0, 0);
        CastingMob mob = (CastingMob) stage(game, plot, NpcContent.CASTING_MOB.get());
        String ref = ENTITY + mob.getUUID();
        ByName.releaseAtEnd(game, ref);

        settle(game, mob);
        int pulsesBefore = game.onServer(s -> ((CastingMob.OwnMoveControl) mob.getMoveControl()).pulses());
        String id = start(api, reporter, ref, goal, "the stand-in");
        game.await().within(400).as("the walk ends").until(() -> !ByName.busy(api, ref));
        BlockPos stoppedAt = game.onServer(s -> mob.blockPosition());
        Map<?, ?> action = ByName.read(api, id);
        int pulses = game.onServer(s -> ((CastingMob.OwnMoveControl) mob.getMoveControl()).pulses()) - pulsesBefore;
        reporter.publishEntry("arrived", stoppedAt.toShortString() + " pulses=" + pulses + " " + action);
        game.check(
                stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && pulses > 0,
                "the stand-in walks to the goal with its own control still in place and its own tick still casting"
                        + " it: stopped at " + stoppedAt.toShortString() + ", pulses=" + pulses + ", " + action);
    }

    /** A stand-in for a modded mob whose own travel reads no input: its goto fails as unresponsive. */
    @Test
    void mobThatIgnoresInputFailsUnresponsive(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        BlockPos o = plot.at(0, 0, 0);
        Mob mob = stage(game, plot, NpcContent.SELF_MOVING_MOB.get());
        String ref = ENTITY + mob.getUUID();
        ByName.releaseAtEnd(game, ref);

        settle(game, mob);
        String id = start(api, reporter, ref, plot.at(COURSE, 0, 0), "the stand-in");
        game.await()
                .within(200)
                .as("the goto ends")
                .until(() -> !"running".equals(ByName.read(api, id).get("state")));
        Map<?, ?> action = ByName.read(api, id);
        BlockPos at = game.onServer(s -> mob.blockPosition());
        reporter.publishEntry("ended", at.toShortString() + " " + action);
        game.check(
                "failed".equals(action.get("state"))
                        && String.valueOf(action).contains("unresponsive")
                        && at.closerThan(o, 1.5),
                "the stand-in never moves, and its goto fails as unresponsive instead of running to its deadline: "
                        + at.toShortString() + ", " + action);
    }

    /**
     * A type listed in {@code undrivableMobs}, and a mob with no AI, are refused by an order naming them and by
     * {@code /worlddriver mob}, and neither is taken over.
     */
    @Test
    void mobUndrivableIsRefused(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        Settings settings = Settings.pin(game);
        Mob listed = stage(game, plot, EntityType.WOLF);
        Mob noAi = spawnStill(game, plot, EntityType.ZOMBIE, plot.at(0, 0, 2));
        settings.set("undrivableMobs", List.of("minecraft:wolf"));

        game.await()
                .within(SETTLE_TICKS)
                .as("the mobs settle")
                .until(() -> Staging.settled(listed) && noAi.tickCount > 5);
        Map<String, Object> listedGo = ByName.call(
                api,
                "mc.bot.goto",
                Map.of("bot", ENTITY + listed.getUUID(), "target", ByName.at(plot.at(COURSE, 0, 0))));
        Map<String, Object> noAiGo = ByName.call(
                api, "mc.bot.goto", Map.of("bot", ENTITY + noAi.getUUID(), "target", ByName.at(plot.at(COURSE, 0, 2))));
        int cmd = game.onServer(s -> command(s, "worlddriver mob " + listed.getUUID() + " scene-listed"));
        reporter.publishEntry("refusals", listedGo + " / " + noAiGo + " / command=" + cmd);
        boolean keptGoals =
                game.onServer(s -> !listed.goalSelector.getAvailableGoals().isEmpty());
        game.check(
                ByName.refusedAs(listedGo, "bot_unavailable")
                        && String.valueOf(listedGo).contains("undrivableMobs")
                        && ByName.refusedAs(noAiGo, "bot_unavailable")
                        && String.valueOf(noAiGo).contains("NoAI")
                        && cmd == 0
                        && !ByName.registered(api, "mob:scene-listed")
                        && keptGoals,
                "the listed wolf and the zombie with no AI are refused, say why, and keep their own goals: " + listedGo
                        + " / " + noAiGo + " / command=" + cmd);
    }

    /**
     * An adopted wolf with no order, standing on a magma block: the contact-damage reflex walks it off. Not
     * invulnerable here, since the reflex fires on the damage.
     */
    @Test
    void mobStepsOffMagmaUnordered(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.at(0, 0, 0);
        Settings settings = Settings.pin(game);
        Mob wolf = stage(game, plot, EntityType.WOLF);
        settings.set("contactDamageEscape", true);
        String ref = "mob:scene-magma";
        ByName.releaseAtEnd(game, ref);

        settle(game, wolf);
        int ok = game.onServer(s -> {
            int r = command(s, "worlddriver mob " + wolf.getUUID() + " scene-magma");
            if (r == 1) {
                // Laid after the takeover, so it is the bot and not the wolf's own AI that leaves it.
                wolf.setInvulnerable(false);
                s.getLevel(plot.dimension()).setBlockAndUpdate(o.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
            }
            return r;
        });
        if (ok != 1) fail("/worlddriver mob did not adopt the wolf: result=" + ok);
        game.await()
                .within(100)
                .as("the wolf leaves its cell")
                .until(() -> !wolf.blockPosition().equals(o));
        String left = game.onServer(s -> wolf.blockPosition().toShortString() + " hp=" + wolf.getHealth());
        boolean offMagma =
                game.onServer(s -> wolf.isAlive() && !wolf.getBlockStateOn().is(Blocks.MAGMA_BLOCK));
        reporter.publishEntry("left", left);
        game.check(offMagma, "the idle wolf steps off the magma alive: at " + left);
    }

    /**
     * An adopted wolf beside two zombies with their AI off: one has the wolf as its target, the other has none. The
     * wolf's threat scan lists the first and not the second.
     */
    @Test
    void mobSeesOnlyWhatHuntsIt(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        Mob wolf = stage(game, plot, EntityType.WOLF);
        Mob hunter = spawnStill(game, plot, EntityType.ZOMBIE, plot.at(4, 0, -2));
        Mob idle = spawnStill(game, plot, EntityType.ZOMBIE, plot.at(4, 0, 2));
        String ref = "mob:scene-prey";
        ByName.releaseAtEnd(game, ref);

        // The zombies have no AI, so they never update onGround; being ticked is enough for them.
        game.await()
                .within(SETTLE_TICKS)
                .as("the mobs settle")
                .until(() -> Staging.settled(wolf) && hunter.tickCount > 5 && idle.tickCount > 5);
        int ok = game.onServer(s -> {
            hunter.setTarget(wolf);
            return command(s, "worlddriver mob " + wolf.getUUID() + " scene-prey");
        });
        if (ok != 1 || !"mob".equals(ByName.entry(api, ref).get("kind"))) {
            fail("/worlddriver mob did not adopt the wolf: result=" + ok);
        }
        game.ticks(3);
        List<Integer> seen = new ArrayList<>();
        if (ByName.call(api, "mc.observe.threats", Map.of("bot", ref)).get("threats") instanceof List<?> threats) {
            for (Object t : threats)
                if (t instanceof Map<?, ?> m && m.get("id") instanceof Number id) seen.add(id.intValue());
        }
        reporter.publishEntry("threats", seen + " hunter=" + hunter.getId() + " idle=" + idle.getId());
        game.check(
                seen.contains(hunter.getId()) && !seen.contains(idle.getId()),
                "the wolf's threats are the zombie hunting it and not the idle one: " + seen + ", hunter="
                        + hunter.getId() + ", idle=" + idle.getId());
    }

    @Test
    void mobZombieWalksByEntityRefAndIsReleased(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        BlockPos goal = plot.at(COURSE, 0, 0);
        Mob zombie = stage(game, plot, EntityType.ZOMBIE);
        String ref = ENTITY + zombie.getUUID();
        ByName.releaseAtEnd(game, ref);
        int goals = game.onServer(s -> zombie.goalSelector.getAvailableGoals().size());
        int targets =
                game.onServer(s -> zombie.targetSelector.getAvailableGoals().size());

        settle(game, zombie);
        Map<String, Object> go = ByName.call(api, "mc.bot.goto", Map.of("bot", ref, "target", ByName.at(goal)));
        reporter.publishEntry("goto", String.valueOf(go));
        Map<?, ?> adopted = ByName.entry(api, ref);
        if (ByName.actionId(go) == null || !"mob".equals(adopted.get("kind"))) {
            fail("a goto naming the zombie by entity ref did not adopt it and start: " + go + ", registered="
                    + adopted);
        }
        String stripped =
                game.onServer(s -> zombie.goalSelector.getAvailableGoals().isEmpty()
                                && zombie.targetSelector.getAvailableGoals().isEmpty()
                                && zombie.isPersistenceRequired()
                        ? ""
                        : "goals=" + zombie.goalSelector.getAvailableGoals().size() + " targets="
                                + zombie.targetSelector.getAvailableGoals().size());
        game.check(stripped.isEmpty(), "the adopted zombie has its goals taken off and stops despawning: " + stripped);
        Map<?, ?> listed = ByName.entry(api, ref);
        reporter.publishEntry("status", String.valueOf(listed));
        game.check("mob".equals(listed.get("kind")), "status lists the zombie under its ref as a mob: " + listed);

        game.await().within(400).as("the walk ends").until(() -> !ByName.busy(api, ref));
        BlockPos stoppedAt = game.onServer(s -> zombie.blockPosition());
        Map<?, ?> action = ByName.read(api, ByName.actionId(go));
        reporter.publishEntry("arrived", stoppedAt.toShortString() + " " + action);
        game.check(
                stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")),
                "the zombie walks to the goal and the order succeeds: stopped at " + stoppedAt.toShortString() + ", "
                        + action);

        Map<String, Object> mine =
                ByName.call(api, "mc.bot.mine", Map.of("bot", ref, "blocks", List.of("minecraft:stone"), "radius", 4));
        Map<String, Object> hold = ByName.call(api, "mc.bot.holdItem", Map.of("bot", ref, "item", "minecraft:stone"));
        reporter.publishEntry("hands", mine + " / " + hold);
        game.check(
                ByName.refusedAs(mine, "unsupported_capability")
                        && ByName.refusedAs(hold, "unsupported_capability")
                        && !ByName.busy(api, ref),
                "a zombie has no hands, so mine and holdItem are refused before anything starts: " + mine + " / "
                        + hold);

        int ok = game.onServer(s -> command(s, "worlddriver mob release " + ref));
        int goalsAfter =
                game.onServer(s -> zombie.goalSelector.getAvailableGoals().size());
        int targetsAfter =
                game.onServer(s -> zombie.targetSelector.getAvailableGoals().size());
        reporter.publishEntry("release", "result=" + ok + " goals=" + goalsAfter + " targets=" + targetsAfter);
        game.check(
                ok == 1 && !ByName.registered(api, ref) && goalsAfter == goals && targetsAfter == targets,
                "/worlddriver mob release gives the zombie its " + goals + " goals and " + targets
                        + " target goals back and frees the ref: result=" + ok + ", goals=" + goalsAfter
                        + ", targets=" + targetsAfter + ", registered=" + ByName.entry(api, ref));
    }

    @Test
    void mobRemovedEndsItsAction(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        Mob zombie = stage(game, plot, EntityType.ZOMBIE);
        String ref = ENTITY + zombie.getUUID();
        ByName.releaseAtEnd(game, ref);

        settle(game, zombie);
        String id = start(api, reporter, ref, plot.at(COURSE, 0, 0), "the zombie");
        game.ticks(10);
        game.runOnServer(s -> zombie.discard());
        game.await()
                .within(40)
                .as("the walk ends")
                .until(() -> !"running".equals(ByName.read(api, id).get("state")));
        Map<?, ?> action = ByName.read(api, id);
        reporter.publishEntry("removed", action + " registered=" + ByName.entry(api, ref));
        game.check(
                "cancelled".equals(action.get("state"))
                        && "bot_removed".equals(action.get("reason"))
                        && !ByName.registered(api, ref),
                "removing the driven zombie ends its walk as bot_removed and frees its ref: " + action + ", registered="
                        + ByName.entry(api, ref));
    }

    /** A goto for {@code ref} to {@code goal}, started: its action id. */
    private static String start(ApiRegistry api, TestReporter reporter, String ref, BlockPos goal, String who) {
        Map<String, Object> go = ByName.call(api, "mc.bot.goto", Map.of("bot", ref, "target", ByName.at(goal)));
        reporter.publishEntry("goto", String.valueOf(go));
        String id = ByName.actionId(go);
        if (id == null) fail("a goto naming " + who + " by entity ref did not start: " + go);
        return id;
    }

    private static void settle(Game game, Mob mob) {
        game.await().within(SETTLE_TICKS).as("the mob is ticked and lands").until(() -> Staging.settled(mob));
    }

    /** A flat stone course along +x under a pinned baseline, and an invulnerable mob of {@code type} at its start. */
    private static Mob stage(Game game, Plot plot, EntityType<? extends Mob> type) {
        Settings.pin(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                // The shipped arrival rule, which the pinned baseline turns off.
                .set("navigatorHoldLastNodeUntilStanding", true);
        plot.fill(-2, -1, -3, COURSE + 2, -1, 3, Blocks.STONE);
        BlockPos o = plot.at(0, 0, 0);
        Mob mob = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            Mob m = type.create(level);
            if (m == null) throw new IllegalStateException(type + " created no entity");
            m.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90f, 0f);
            m.setInvulnerable(true);
            m.setPersistenceRequired();
            level.addFreshEntity(m);
            return m;
        });
        game.afterTest(s -> mob.discard());
        return mob;
    }

    /** An invulnerable mob of {@code type} with its AI off, standing at {@code foot}. */
    private static Mob spawnStill(Game game, Plot plot, EntityType<? extends Mob> type, BlockPos foot) {
        Mob mob = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            Mob m = type.create(level);
            if (m == null) throw new IllegalStateException(type + " created no entity");
            m.moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 90f, 0f);
            m.setInvulnerable(true);
            m.setPersistenceRequired();
            m.setNoAi(true);
            level.addFreshEntity(m);
            return m;
        });
        game.afterTest(s -> mob.discard());
        return mob;
    }

    /** The command's result, or -1 when it does not parse. Server thread. */
    private static int command(MinecraftServer server, String line) {
        try {
            return server.getCommands()
                    .getDispatcher()
                    .execute(line, server.createCommandSourceStack().withSuppressedOutput());
        } catch (CommandSyntaxException e) {
            return -1;
        }
    }
}
