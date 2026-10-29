package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * The bot's opt-in reflexes, each run with its setting on against the situation it exists for, on a parked server bot
 * the test ticks by hand. Each test runs the same staging twice, setting off and then on, so the off arm says the
 * change the on arm reads was the reflex's and not the staging's.
 *
 * <p>The settings that are the client's alone ({@code autoRespawn}, which clicks the death screen, and
 * {@code autoTotem}, whose lethal hit needs a player that can be hurt; a server bot cannot) are in
 * {@link ClientBodyTest}. {@code autoFight} is in {@link MobFightTest}.
 */
@Scene("wd.scenes")
class ReflexTest {

    /** A 9x9 stone floor at plot y=0 and a parked bot standing on its centre. */
    private static SceneBot onFloor(Game game, Plot plot) {
        plot.fill(-4, 0, -4, 4, 0, 4, Blocks.STONE);
        return SceneBot.spawn(game, plot, 0, 1, 0);
    }

    /** A mob of {@code type} that neither moves nor despawns, standing in {@code feet}; gone when the test ends. */
    private static <T extends Mob> T still(Game game, EntityType<T> type, BlockPos feet) {
        T mob = game.onServer(s -> {
            ServerLevel level = s.overworld();
            T m = type.create(level);
            m.setNoAi(true);
            m.setPersistenceRequired();
            m.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0f, 0f);
            level.addFreshEntity(m);
            return m;
        });
        game.afterTest(s -> mob.discard());
        return mob;
    }

    // ---------------------------------------------------------------- autoHeal

    /**
     * Low health and a golden apple on the hotbar: the bot eats it. {@code AutoHeal.wants}: health at or below
     * {@code healHpThreshold} (12) and a golden apple or a healing potion on the hotbar. Food 17 keeps vanilla's own
     * regeneration off ({@code FoodData} heals only from 18), so nothing but the apple raises health.
     */
    @Test
    void autoHealEatsAGoldenAppleAtLowHealth(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game);
        SceneBot bot = onFloor(game, plot);
        bot.give(Items.GOLDEN_APPLE, 1);
        game.runOnServer(s -> {
            bot.player().setHealth(8f);
            bot.player().getFoodData().setFoodLevel(17);
        });

        float[] off = game.onServer(s -> {
            bot.tick(60);
            return new float[] {bot.player().getHealth(), bot.count(Items.GOLDEN_APPLE)};
        });
        settings.set("autoHeal", true);
        float[] on = game.onServer(s -> {
            int t = 0;
            for (; t < 200 && bot.player().getHealth() <= 8f; t++) bot.tick();
            return new float[] {bot.player().getHealth(), bot.count(Items.GOLDEN_APPLE), t};
        });
        reporter.publishEntry("off.health/apples", off[0] + "/" + (int) off[1]);
        reporter.publishEntry("on.health/apples/ticks", on[0] + "/" + (int) on[1] + "/" + (int) on[2]);
        assertEquals(8f, off[0], "with autoHeal off the bot's health changed");
        assertEquals(1, (int) off[1], "with autoHeal off the bot ate the apple");
        assertEquals(0, (int) on[1], "with autoHeal on the bot did not eat the golden apple in 200 ticks");
        assertTrue(on[0] > 8f, "with autoHeal on the bot's health did not rise above 8: " + on[0]);
    }

    // ---------------------------------------------------------------- autoEat

    /** Hungry with bread on the hotbar: the bot eats. {@code AutoEat.tick}: food at or below
     *  {@code autoEatFoodThreshold} (18) and food on the hotbar; it holds the use until food is 20. */
    @Test
    void autoEatEatsWhenHungry(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game);
        SceneBot bot = onFloor(game, plot);
        bot.give(Items.BREAD, 4);
        game.runOnServer(s -> bot.player().getFoodData().setFoodLevel(10));

        int off = game.onServer(s -> {
            bot.tick(60);
            return bot.player().getFoodData().getFoodLevel();
        });
        settings.set("autoEat", true);
        int[] on = game.onServer(s -> {
            int t = 0;
            for (; t < 200 && bot.player().getFoodData().getFoodLevel() <= 10; t++) bot.tick();
            return new int[] {bot.player().getFoodData().getFoodLevel(), bot.count(Items.BREAD), t};
        });
        reporter.publishEntry("off.food", String.valueOf(off));
        reporter.publishEntry("on.food/bread/ticks", on[0] + "/" + on[1] + "/" + on[2]);
        assertTrue(off <= 10, "with autoEat off the bot's food rose: " + off);
        assertTrue(on[0] > 10, "with autoEat on the bot did not eat in 200 ticks: food=" + on[0] + " bread=" + on[1]);
    }

    // ---------------------------------------------------------------- autoTool

    /**
     * Facing stone with dirt in hand and a pickaxe elsewhere on the hotbar: the bot changes to the pickaxe.
     * {@code AutoTool.tick} runs only with no task and no chain ({@code mayRun}), reads the block under the crosshair
     * ({@code lookingAtBlock}) and selects the hotbar slot that is the correct tool or digs fastest.
     */
    @Test
    void autoToolSelectsThePickaxeForStone(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game);
        SceneBot bot = onFloor(game, plot);
        plot.fill(2, 1, -1, 2, 3, 1, Blocks.STONE);
        bot.give(Items.DIRT, 1).give(Items.WOODEN_PICKAXE, 1);   // dirt in slot 0, which is selected
        bot.lookAt(plot.at(2, 2, 0));

        int off = game.onServer(s -> {
            bot.tick(10);
            return bot.player().getInventory().selected;
        });
        settings.set("autoTool", true);
        String on = game.onServer(s -> {
            bot.tick(10);
            return bot.player().getMainHandItem().getItem().toString();
        });
        reporter.publishEntry("off.selected", String.valueOf(off));
        reporter.publishEntry("on.mainHand", on);
        assertEquals(0, off, "with autoTool off the bot changed hotbar slot");
        assertEquals(Items.WOODEN_PICKAXE.toString(), on, "with autoTool on the bot did not take the pickaxe for stone");
    }

    // ---------------------------------------------------------------- autoSwim

    /**
     * Sunk to the floor of a pool while a task holds the bot: the bot swims up. {@code AutoSwim.tick} does nothing for
     * an idle bot (it must not move one with no order), so a slow smooth look holds the task slot without moving the
     * body; under a task it holds jump while the head is under water. Water breathing keeps the drowning reflexes out.
     */
    @Test
    void autoSwimRisesFromThePoolFloorUnderATask(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game).set("smoothLook", true).set("smoothLookDegPerTick", 1.0);
        plot.fill(-3, 0, -3, 3, 7, 3, Blocks.STONE);
        plot.fill(-2, 1, -2, 2, 6, 2, Blocks.WATER);
        plot.fill(-2, 7, -2, 2, 7, 2, Blocks.AIR);   // open above the water
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        double floor = plot.at(0, 1, 0).getY();

        double[] off = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            // About 180 ticks of turning at a degree a tick: the task outlasts both arms.
            double yaw = fp.getYRot() > 0 ? fp.getYRot() - 180 : fp.getYRot() + 180;
            Calls.started("look", bot.interact().lookAt(new InteractContracts.LookParams(bot.ref(), Optional.empty(),
                    Optional.of(yaw), Optional.of(0.0), 0)));
            double maxY = fp.getY();
            for (int t = 0; t < 40; t++) {
                bot.tick();
                maxY = Math.max(maxY, fp.getY());
            }
            return new double[] {maxY, bot.busy() ? 1 : 0};
        });
        settings.set("autoSwim", true);
        double[] on = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            double maxY = fp.getY();
            for (int t = 0; t < 100; t++) {
                bot.tick();
                maxY = Math.max(maxY, fp.getY());
            }
            boolean busy = bot.busy();
            bot.park();
            return new double[] {maxY, busy ? 1 : 0};
        });
        reporter.publishEntry("off.riseBusy", (off[0] - floor) + " busy=" + (off[1] > 0));
        reporter.publishEntry("on.riseBusy", (on[0] - floor) + " busy=" + (on[1] > 0));
        assertTrue(off[1] > 0 && on[1] > 0, "the look task did not hold the slot through both arms");
        assertTrue(off[0] < floor + 0.5, "with autoSwim off the bot rose from the pool floor: +" + (off[0] - floor));
        assertTrue(on[0] >= floor + 3, "with autoSwim on the bot did not swim up: rose only +" + (on[0] - floor));
    }

    // ---------------------------------------------------------------- autoSecureAtDusk

    /**
     * Night, under open sky, a zombie close by, nothing to do: the bot digs in. {@code DuskSecureChain.priority}:
     * {@code exposedAtNight} (the world is frozen at midnight, the plot open to the sky), not cornered, then 20 ticks of
     * debounce; with {@code duskUrgent} on (the default) the threat-radius veto is skipped and it bids 90, driving a
     * {@code BunkerProcess}.
     */
    @Test
    void autoSecureAtDuskDigsInAtNight(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game).set("allowBreak", true).set("allowPlace", true);
        plot.fill(-4, -4, -4, 4, 0, 4, Blocks.DIRT);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        bot.give(Items.DIRT, 16);
        still(game, EntityType.ZOMBIE, plot.at(4, 1, 3));
        double floor = plot.at(0, 1, 0).getY();

        boolean off = game.onServer(s -> chainSeen(bot, 60));
        settings.set("autoSecureAtDusk", true);
        boolean on = game.onServer(s -> chainSeen(bot, 60));
        double y = game.onServer(s -> {
            bot.tick(400);
            bot.park();
            return bot.player().getY();
        });
        reporter.publishEntry("chain.off/on", off + "/" + on);
        reporter.publishEntry("y.afterDig", String.valueOf(y - floor));
        assertFalse(off, "with autoSecureAtDusk off the dusk chain took the bot");
        assertTrue(on, "with autoSecureAtDusk on the dusk chain did not take the bot within 60 ticks");
        assertTrue(y <= floor - 1, "the dusk chain did not dig the bot in: feet at " + (y - floor) + " from the floor");
    }

    /** Whether {@code duskSecure} steered the bot on any of {@code ticks} ticks. Server thread. */
    private static boolean chainSeen(SceneBot bot, int ticks) {
        for (int t = 0; t < ticks; t++) {
            bot.tick();
            if (bot.status().reflexes().flatMap(r -> r.activeChain()).map("duskSecure"::equals).orElse(false)) return true;
        }
        return false;
    }
}
