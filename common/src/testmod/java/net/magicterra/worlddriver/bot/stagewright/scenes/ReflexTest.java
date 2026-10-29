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
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
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

    // ---------------------------------------------------------------- autoShield

    /**
     * An arrow on its way in: the shield goes up. {@code AutoShield.wants}: a shield in the offhand or on the hotbar,
     * and a projectile {@code ThreatScanner} predicts will hit within 30 ticks. {@code ThreatWatch} takes an arrow's
     * velocity from where it was one refresh ago, so the arrow is moved by hand between the bot's ticks and never
     * ticked itself (it would hit). {@code isBlocking} is vanilla's: five ticks into the use of a shield.
     */
    @Test
    void autoShieldRaisesTheShieldAgainstAnIncomingArrow(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                                                         TestReporter reporter) {
        Settings settings = Settings.pin(game);
        SceneBot bot = onFloor(game, plot);
        game.runOnServer(s -> bot.player().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD)));

        boolean[] off = arrowIn(game, bot);
        settings.set("autoShield", true);
        boolean[] on = arrowIn(game, bot);
        reporter.publishEntry("off.usingItem/blocking", off[0] + "/" + off[1]);
        reporter.publishEntry("on.usingItem/blocking", on[0] + "/" + on[1]);
        assertFalse(off[0], "with autoShield off the bot used an item while the arrow came in");
        assertTrue(on[1], "with autoShield on the shield was not raised against the incoming arrow: usingItem=" + on[0]);
    }

    /** An arrow ten blocks out flying at the bot's chest, closed by half a block per bot tick for twelve ticks;
     *  whether the bot was using an item, and blocking, at the end. */
    private static boolean[] arrowIn(Game game, SceneBot bot) {
        return game.onServer(s -> {
            ServerPlayer fp = bot.player();
            ServerLevel level = bot.level();
            Vec3 chest = fp.position().add(0, 1.0, 0);
            Vec3 from = chest.add(10, 0, 0);
            Vec3 step = chest.subtract(from).normalize().scale(0.5);
            Arrow arrow = new Arrow(level, from.x, from.y, from.z, new ItemStack(Items.ARROW), null);
            arrow.setDeltaMovement(step);
            arrow.setNoGravity(true);
            level.addFreshEntity(arrow);
            try {
                for (int t = 0; t < 12; t++) {
                    bot.tick();
                    arrow.setPos(arrow.position().add(step));
                }
                return new boolean[] {fp.isUsingItem(), fp.isBlocking()};
            } finally {
                arrow.discard();
            }
        });
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

    // ---------------------------------------------------------------- autoEquip

    /**
     * A fight with armour in the bag: the bot puts it on. {@code BotBrain.act} runs {@code AutoEquip.tick} only while
     * the combat chain is engaged, so the fight is ordered ({@code mc.bot.combat}) against a zombie that cannot be hurt
     * and so never ends.
     */
    @Test
    void autoEquipPutsOnArmourInAFight(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game);
        SceneBot bot = onFloor(game, plot);
        bot.give(Items.IRON_SWORD, 1).give(Items.IRON_HELMET, 1).give(Items.IRON_CHESTPLATE, 1)
                .give(Items.IRON_LEGGINGS, 1).give(Items.IRON_BOOTS, 1);
        var zombie = still(game, EntityType.ZOMBIE, plot.at(2, 1, 0));
        game.runOnServer(s -> zombie.setInvulnerable(true));

        int off = game.onServer(s -> {
            Calls.started("combat kill zombie", bot.tasks().combat(new CombatContracts.Params(bot.ref(),
                    CombatContracts.Mode.KILL, Optional.of(new CombatContracts.FoeType("minecraft:zombie")), false, 0)));
            bot.tick(20);
            return armourWorn(bot.player());
        });
        settings.set("autoEquip", true);
        int on = game.onServer(s -> {
            bot.tick(20);
            bot.park();
            return armourWorn(bot.player());
        });
        reporter.publishEntry("armourWorn.off/on", off + "/" + on);
        assertEquals(0, off, "with autoEquip off the bot put armour on");
        assertEquals(4, on, "with autoEquip on the bot did not put on all four pieces in its bag");
    }

    private static int armourWorn(ServerPlayer fp) {
        int n = 0;
        for (ItemStack s : fp.getInventory().armor) if (!s.isEmpty()) n++;
        return n;
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

    // ---------------------------------------------------------------- lavaProximityEscape

    /**
     * Out of a lava pool and still burning, with water a few blocks off: the bot walks into the water, the fire goes
     * out, and the reflex hands back, so an order then moves the bot. Lava sets a bot burning for fifteen seconds
     * ({@code Entity.lavaHurt}), which leaving it does not end. The bot stands in the pool's east edge, where every
     * cardinal is lava or wall, so the escape takes its fallback east; the water lies to the south of that line, so
     * the escape's own walk away (some eight blocks) passes it by. With {@code lavaEscapeWaterRadius} 0 the bot is
     * left burning on the stone.
     */
    @Test
    void lavaEscapeWalksABurningBotIntoWater(Game game, @Plot.Centered @Plot.Size(x = 24, y = 16, z = 16) Plot plot,
                                             TestReporter reporter) {
        Settings settings = Settings.pin(game).set("lavaProximityEscape", true).set("lavaEscapeWaterRadius", 0);
        plot.fill(-7, -1, -6, 9, 0, 7, Blocks.STONE);
        plot.fill(-5, 0, -1, -3, 0, 1, Blocks.LAVA);
        plot.fill(-1, 0, 4, 1, 0, 6, Blocks.WATER);
        SceneBot bot = SceneBot.spawn(game, plot, -3, 0, 0);
        BlockPos inLava = plot.at(-3, 0, 0);

        double[] off = burnAndRun(game, bot, inLava, 120);
        settings.set("lavaEscapeWaterRadius", 8);
        double[] on = burnAndRun(game, bot, inLava, 200);
        reporter.publishEntry("off.inLava/inWater/burning/x", off[0] + "/" + off[1] + "/" + off[2] + "/" + off[4]);
        reporter.publishEntry("on.inLava/inWater/burning/ticksToOut/x", on[0] + "/" + on[1] + "/" + on[2] + "/" + on[3]
                + "/" + on[4]);
        assertTrue(off[0] > 0 && on[0] > 0, "the bot was never in the lava, so nothing set it burning");
        assertTrue(off[2] > 0 && off[1] == 0, "with lavaEscapeWaterRadius 0 the bot was not left burning out of the water: "
                + "inWater/burning/ticksToOut/x=" + off[1] + "/" + off[2] + "/" + off[3] + "/" + off[4]);
        assertTrue(on[1] > 0, "with lavaEscapeWaterRadius 8 the bot did not reach the water in 200 ticks: x/z="
                + on[4] + "/" + on[5]);
        assertTrue(on[2] == 0, "with lavaEscapeWaterRadius 8 the bot was still burning after 200 ticks");

        // Handed back: a goto out of the water arrives, which a reflex still holding the bot in it would not let happen.
        BlockPos dry = plot.at(3, 1, 2);
        double gap = game.onServer(s -> {
            bot.goTo(Targets.block(dry));
            bot.tickUntilIdle(300);
            bot.park();
            return bot.player().position().distanceTo(Vec3.atBottomCenterOf(dry));
        });
        reporter.publishEntry("goto.gap", String.valueOf(gap));
        assertTrue(gap < 1.0, "after the fire was out a goto did not take the bot out of the water: " + gap + " blocks short");
    }

    /**
     * Stand the bot in {@code inLava} and tick it {@code ticks} times. Answers 1/0 for: it was in lava, it ended in
     * water, it ended burning; then the tick its fire went out (-1 if it did not) and its final x and z relative to
     * the cell.
     */
    private static double[] burnAndRun(Game game, SceneBot bot, BlockPos inLava, int ticks) {
        return game.onServer(s -> {
            ServerPlayer fp = bot.player();
            fp.setDeltaMovement(Vec3.ZERO);
            fp.setPos(inLava.getX() + 0.5, inLava.getY(), inLava.getZ() + 0.5);
            boolean wasInLava = false, burned = false;
            int outAt = -1;
            for (int t = 0; t < ticks; t++) {
                bot.tick();
                wasInLava |= fp.isInLava();
                burned |= fp.isOnFire();
                if (burned && outAt < 0 && !fp.isOnFire()) outAt = t;
            }
            return new double[] {wasInLava ? 1 : 0, fp.isInWater() ? 1 : 0, fp.isOnFire() ? 1 : 0, outAt,
                    fp.getX() - inLava.getX(), fp.getZ() - inLava.getZ()};
        });
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
