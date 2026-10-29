package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.action.ActionBlockingApi;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.CommandParams;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.CommandResult;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.control.ControlBlockingApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Attack;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.worlddriver.testcontent.TestPlayerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The server body as a player: two agents are two players, the pieces of {@code Player.tick()} it runs (eating,
 * cooldowns, the recharge bar), the attack cooldown {@code mc.observe.player} shows, elytra flight, and a join that a
 * mod's unnegotiated payload must not stop.
 */
@Scene("wd.scenes")
class AvatarTest {

    /** Two agents must be two players: driving A leaves B where it stands. */
    @Test
    void serverAgentDistinctBodies(Game game, @Plot.Size(x = 32, y = 16, z = 32) Plot plot) {
        BlockPos a0 = plot.at(5, 1, 5), b0 = plot.at(25, 1, 25);
        plot.fill(2, 1, 2, 8, 1, 8, Blocks.STONE);
        plot.fill(22, 1, 22, 28, 1, 28, Blocks.STONE);
        Settings.pin(game).set("navigatorDebug", false);
        SceneBot a = SceneBot.spawn(game, a0.above());
        SceneBot b = SceneBot.spawn(game, b0.above());

        game.runOnServer(s -> {
            ServerPlayer fpA = a.player(), fpB = b.player();
            if (fpA == fpB) {
                fail("both server agents are literally the same entity (" + System.identityHashCode(fpA) + "): the "
                        + "roster handed two bots one shared player, so agents (and concurrent tests) fight over one player");
            }
            // B is parked. A walks. Vanilla-obvious, and the whole point of having two agents.
            Vec3 bStart = fpB.position();
            a.press(a.keys().move(0, 1));
            a.step(20);
            double bDrift = bStart.distanceTo(fpB.position());
            double aMoved = fpA.position().distanceTo(new Vec3(a0.getX() + 0.5, a0.getY() + 1, a0.getZ() + 0.5));
            TestMod.LOG.warn("[wd.serverAgentDistinctBodies] A walked {} blocks, B (idle) drifted {}", aMoved, bDrift);
            if (aMoved < 0.5) fail("rig broken: agent A did not walk at all (" + aMoved + ")");
            if (bDrift > 0.01) {
                fail("driving agent A dragged idle agent B " + bDrift + " blocks: the two agents are sharing one player");
            }
        });
    }

    /**
     * A bot is the name it was spawned with in the game: its profile has that name and the UUID an offline-mode
     * server gives it, a command finds it by name, and the name cannot join a second time.
     */
    @Test
    void aSpawnedBotJoinsUnderItsOwnName(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        String profile = bot.onServer(p -> p.getGameProfile().getName());
        UUID uuid = bot.onServer(Entity::getUUID);
        assertEquals(bot.name(), profile, "the bot's player is not named as the bot");
        assertEquals(UUIDUtil.createOfflinePlayerUUID(bot.name()), uuid, "the bot's UUID is not the offline one for its name");

        CommandResult byName = game.api(ActionBlockingApi::of)
                .runCommand(new CommandParams("data get entity " + bot.name() + " Pos", false, Optional.empty()));
        assertTrue(byName.success(), "data get entity " + bot.name() + " did not find the bot: " + byName.feedback());

        Responses.ImmediateResponse again = game.api(ControlBlockingApi::of)
                .roster(new ControlContracts.Spawn(bot.name(), Optional.empty(), Optional.empty()));
        assertTrue(again instanceof Responses.Refused<?> refused && refused.reason() == RefusalReason.INVALID_WORLD_TARGET,
                "a second bot under the name " + bot.name() + " was not refused: " + again);
    }

    /**
     * Removing a bot saves its player as a disconnect does, so spawning the name again with no {@code pos} finds it
     * where it left, in the dimension it left from; a {@code pos} still moves it.
     */
    @Test
    void aBotSpawnedAgainByNameStandsWhereItLeft(Game game, @Plot.Centered Plot plot,
                                                 @Plot.In("minecraft:the_nether") Plot nether) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        nether.fill(0, 0, 0, 2, 0, 2, Blocks.STONE);
        Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        BlockPos away = nether.at(1, 1, 1);
        bot.moveTo(Level.NETHER, away);
        ControlBlockingApi control = game.api(ControlBlockingApi::of);
        assertTrue(control.roster(new ControlContracts.Remove(bot.ref())) instanceof Responses.Rostered, "remove");

        String back = game.onServer(s -> where(s, control, bot.name(), Optional.empty()));
        assertEquals(Level.NETHER.location() + " " + Vec3.atBottomCenterOf(away), back,
                "spawned again with no pos, the bot is not where it was removed");
        assertTrue(control.roster(new ControlContracts.Remove(bot.ref())) instanceof Responses.Rostered, "remove");

        BlockPos asked = plot.at(1, 1, 1);
        String moved = game.onServer(s -> where(s, control, bot.name(), Optional.of(Targets.pos(asked))));
        assertEquals(Level.OVERWORLD.location() + " " + Vec3.atBottomCenterOf(asked), moved, "a pos did not move it");
    }

    /** A bot left somewhere in one game, for {@link #andIsThereAfterARestart}. */
    @Test
    void aBotIsLeftSomewhere(Game game, @Plot.Centered Plot plot) {
        // Not a SceneBot name: those count from 1 again in every game, and the next game must not reuse this one.
        String name = "wdLeftBehind";
        ControlBlockingApi control = game.api(ControlBlockingApi::of);
        String left = game.onServer(s -> where(s, control, name, Optional.of(Targets.pos(plot.at(2, 1, -2)))));
        assertTrue(control.roster(new ControlContracts.Remove("player:" + name)) instanceof Responses.Rostered, "remove");
        game.store().put("leftBot", name);
        game.store().put("leftAt", left);
    }

    /** The save outlives the game: a restarted server spawns the name where the last game left it. */
    @Test
    @DependsOn(value = "aBotIsLeftSomewhere", afterRestart = true)
    void andIsThereAfterARestart(Game game) {
        String name = game.store().get("leftBot", String.class);
        ControlBlockingApi control = game.api(ControlBlockingApi::of);
        String back = game.onServer(s -> where(s, control, name, Optional.empty()));
        control.roster(new ControlContracts.Remove("player:" + name));
        assertEquals(game.store().get("leftAt", String.class), back, "after a restart the bot is not where it was left");
    }

    /** Spawn {@code name} and read where it stands in the same server task, before any tick can move it. */
    private static String where(MinecraftServer server, ControlBlockingApi control, String name,
                                Optional<CommonTypes.BlockPosition> pos) {
        Responses.ImmediateResponse r = control.roster(new ControlContracts.Spawn(name, Optional.empty(), pos));
        if (!(r instanceof Responses.Rostered)) fail("mc.bot.roster spawn " + name + " answered " + r);
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p.level().dimension().location() + " " + p.position();
    }

    /**
     * Gap #47: the server body mirrors the pieces of {@code Player.tick()} a player relies on — a held use completes
     * (eating), an item cooldown expires, swapping weapons empties the recharge bar. Golden outcomes.
     */
    @Test
    void serverAvatarTickFidelity(Game game, @Plot.Centered Plot plot) {
        plot.fill(-1, 0, -1, 1, 0, 1, Blocks.STONE);
        Settings.pin(game).set("navigatorDebug", false);
        SceneBot driver = SceneBot.spawn(game, plot.at(0, 1, 0));

        game.runOnServer(s -> {
            ServerPlayer fp = driver.player();
            // (A) A HELD USE COMPLETES: eating.
            fp.getInventory().clearContent();
            fp.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF, 2));
            fp.getInventory().selected = 0;
            fp.getFoodData().setFoodLevel(6);              // hungry, so the meal has somewhere to go
            driver.press(driver.keys().use(true));
            driver.step(40);                               // cooked beef = 32 ticks to eat
            driver.press(driver.keys().use(false));
            int beefLeft = fp.getInventory().getItem(0).getCount();
            int food = fp.getFoodData().getFoodLevel();
            if (beefLeft != 1 || food <= 6) {
                fail("holding USE on food never finished the bite (beefLeft=" + beefLeft + " foodLevel=" + food + ", "
                        + "vanilla: 1 left, food 6 -> 14): startUsingItem arms a countdown that nothing advances, so "
                        + "eat/drink/bow-draw are all silent no-ops on the server body");
            }

            // (B) AN ITEM COOLDOWN EXPIRES.
            fp.getCooldowns().addCooldown(Items.ENDER_PEARL, 10);
            if (!fp.getCooldowns().isOnCooldown(Items.ENDER_PEARL)) fail("rig broken: the cooldown did not even register");
            driver.step(15);
            if (fp.getCooldowns().isOnCooldown(Items.ENDER_PEARL)) {
                fail("a 10-tick item cooldown had not expired after 15 body ticks: nothing calls cooldowns.tick(), so "
                        + "any item that goes on cooldown stays there forever");
            }

            // (C) SWAPPING WEAPONS EMPTIES THE RECHARGE BAR.
            fp.getInventory().clearContent();
            fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            fp.getInventory().selected = 0;
            driver.step(30);                               // bar fills on the sword
            float charged = fp.getAttackStrengthScale(0.0f);
            if (charged < 1.0f) fail("rig broken: bar not full after 30 ticks (" + charged + ")");
            fp.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));   // swap: different item
            driver.step();
            float afterSwap = fp.getAttackStrengthScale(0.0f);
            if (afterSwap > 0.5f) {
                fail("swapping to a different weapon did not reset the recharge bar (scale still " + afterSwap + "): "
                        + "the body would swing the new weapon at full strength immediately, and "
                        + "observe.player.attack would advertise a bar vanilla says is empty");
            }
        });
    }

    /**
     * Gap #45: the melee attack cooldown is visible on {@code mc.observe.player}, and it is the real one: an iron
     * sword recharges in {@code ceil(20/1.6) = 13} ticks, the bar fills monotonically, {@code ready} flips exactly when
     * the scale reaches 1.0 (the gate CombatProcess swings on), and an axe recharges slower.
     */
    @Test
    void serverAttackCooldown(Game game, @Plot.Centered Plot plot) {
        plot.fill(-1, 0, -1, 1, 0, 1, Blocks.STONE);
        Settings.pin(game).set("navigatorDebug", false);
        SceneBot driver = SceneBot.spawn(game, plot.at(0, 1, 0));

        game.runOnServer(s -> {
            ServerPlayer fp = driver.player();
            fp.getInventory().clearContent();
            equipMainHand(fp, new ItemStack(Items.IRON_SWORD));
            // Let the weapon SWAP land before timing anything (gap #47 empties the bar on swap).
            driver.step();

            fp.resetAttackStrengthTicker();               // just swung: bar empty
            Attack a0 = attackOf(driver);
            float s0 = (float) a0.strengthScale();
            int period = a0.fullCooldownTicks();
            // (A) Right after a swing the bar is empty: a hit sent NOW would land for ~0 damage.
            if (s0 > 0.01f || a0.ready()) fail("bar must read empty right after a swing: " + a0);
            if (period != 13 || a0.cooldownTicks() != 13) {
                fail("iron sword recharge should be ceil(20/1.6)=13 ticks, got period=" + period + " cooldownTicks="
                        + a0.cooldownTicks());
            }

            // (B) The bar fills monotonically, and `ready` flips exactly when the scale reaches 1.0.
            int readyAt = -1;
            float prev = s0;
            for (int t = 1; t <= period + 2; t++) {
                driver.step();                            // the one tick-clock a parked bot gets
                Attack a = attackOf(driver);
                float sc = (float) a.strengthScale();
                int cd = a.cooldownTicks();
                boolean ready = a.ready();
                if (sc < prev) fail("scale went backwards at t=" + t + ": " + a);
                if (ready != (sc >= 1.0f)) fail("`ready` disagrees with scale>=1.0 at t=" + t + ": " + a);
                if (ready && cd != 0) fail("ready but cooldownTicks!=0 at t=" + t + ": " + a);
                if (!ready && cd <= 0) fail("not ready but nothing left to wait at t=" + t + ": " + a);
                if (ready && readyAt < 0) readyAt = t;
                prev = sc;
            }
            if (readyAt != period) {
                fail("full strength should arrive after exactly the reported " + period + " ticks (that IS the contract "
                        + "the agent waits on), arrived at " + readyAt);
            }

            // (C) The number tracks the HELD weapon, not the player: an axe recharges slower.
            equipMainHand(fp, new ItemStack(Items.IRON_AXE));
            int axePeriod = attackOf(driver).fullCooldownTicks();
            if (axePeriod <= period) {
                fail("an axe swings slower than a sword; got axe=" + axePeriod + " sword=" + period
                        + " — the field is not reading the held weapon");
            }
        });
    }

    /** The server ElytraProcess enters fall-flying from a glide takeoff without crashing the tick. */
    @Test
    void serverElytra(Game game, @Plot.Size(x = 16, y = 48, z = 16) Plot plot) {
        BlockPos pad = plot.at(8, 1, 8);
        // A small pad far BELOW so the bot is airborne (onGround=false → can fall-fly).
        plot.fill(7, 1, 7, 9, 1, 9, Blocks.STONE);
        Settings.pinUntimed(game).set("navigatorDebug", false).set("elytraDebug", false);
        SceneBot driver = SceneBot.spawn(game, Level.OVERWORLD, pad.getX() + 0.5, pad.getY() + 40, pad.getZ() + 0.5);

        game.runOnServer(s -> {
            ServerPlayer fp = driver.player();
            fp.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));   // fresh wing (full durability)
            // No fireworks: pure glide takeoff. Reactive flight toward a cell 400 east, 2000 ticks at most, done within
            // 3 blocks: the ElytraProcess this test always started.
            Calls.started("elytraFly", driver.tasks().elytraFly(new MoveContracts.ElytraParams(driver.ref(),
                    Optional.of(Targets.pos(new BlockPos(pad.getX() + 400, pad.getY() + 40, pad.getZ()))),
                    Optional.empty(), Optional.empty(), Optional.of(true), Optional.of(false), 40, Optional.of(2000), 3.0,
                    false, 0, 0)));
            // Ticked one bot at a time, which hands a tick that throws back to the caller instead of dropping the bot
            // the way the server's own tick does: a crash fails the tick call, so it cannot pass as a bot that stopped.
            boolean flew = false;
            String crashed = null;
            for (int t = 0; t < 60 && driver.busy(); t++) {
                try {
                    driver.tick();
                } catch (RuntimeException e) {
                    crashed = String.valueOf(e);
                    break;
                }
                if (fp.isFallFlying()) flew = true;
            }
            TestMod.LOG.info("[wd.serverElytra] flew={} pos=({},{},{}) status={} crashed={}",
                    flew, fp.getX(), fp.getY(), fp.getZ(), crashed == null ? driver.status() : "-", crashed);
            if (crashed != null) fail("server ElytraProcess crashed the tick: " + crashed);
            if (!flew) fail("server ElytraProcess never entered fall-flying (startFallFlying failed)");
        });
    }

    /** A payload on a channel no client negotiated. Never encoded: the bot drops it first. */
    private record ProbePayload() implements CustomPacketPayload {
        static final Type<ProbePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "bot_payload_probe"));

        @Override public Type<ProbePayload> type() { return TYPE; }
    }

    /**
     * A mod's own payload, sent to a bot at login and after, neither stops the join nor throws. NeoForge refuses a
     * payload on a channel the client did not negotiate, and a bot negotiates none; L2Core's login sync made every
     * bot creation fail.
     */
    @Test
    void serverBotJoinsPastModdedPayload(Game game, @Plot.Centered Plot plot) {
        BlockPos at = plot.at(0, 0, 0);
        Settings.pin(game);
        // Sent from the login event, as L2Core sends its sync: inside placeNewPlayer. Armed only while the roster
        // spawns the bot, so the player that joins in that window is the bot.
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger sentAtJoin = new AtomicInteger();
        Consumer<ServerPlayer> onJoin = player -> {
            if (!armed.get()) return;
            player.connection.send(new ClientboundCustomPayloadPacket(new ProbePayload()));
            sentAtJoin.incrementAndGet();
        };
        TestPlayerEvents.addJoinListener(onJoin);
        game.afterTest(s -> TestPlayerEvents.removeJoinListener(onJoin));

        SceneBot bot;
        armed.set(true);
        try {
            bot = SceneBot.spawn(game, Level.OVERWORLD, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        } catch (RuntimeException | AssertionError e) {
            fail("the bot did not join past a modded payload sent at login: " + e.getMessage(), e);
            return;
        } finally {
            armed.set(false);
        }
        // Without this the test would pass on a run where the payload was never sent at all.
        if (sentAtJoin.get() != 1) fail("the login event sent the probe " + sentAtJoin.get() + " times, expected once");

        // And after the join, the path a mod's PacketDistributor.sendToPlayer takes.
        game.runOnServer(s -> {
            try {
                bot.player().connection.send(new ClientboundCustomPayloadPacket(new ProbePayload()));
            } catch (RuntimeException e) {
                fail("a modded payload sent to a joined bot threw: " + e);
            }
        });
    }

    /**
     * Put a weapon in the main hand AND apply its attribute modifiers (attack speed/damage) WITHOUT stepping the body,
     * keeping the gap #45 observation independent of gap #46's sync fix.
     */
    private static void equipMainHand(ServerPlayer fp, ItemStack weapon) {
        ItemStack prev = fp.getMainHandItem();
        if (!prev.isEmpty()) {
            prev.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
                var inst = fp.getAttributes().getInstance(attr);
                if (inst != null) inst.removeModifier(mod.id());
            });
        }
        fp.getInventory().setItem(0, weapon);
        fp.getInventory().selected = 0;
        weapon.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            var inst = fp.getAttributes().getInstance(attr);
            if (inst != null) { inst.removeModifier(mod.id()); inst.addTransientModifier(mod); }
        });
    }

    /** A tower places blocks, and a player that places a block swings its arm, as {@code handleUseItemOn} does. */
    @Test
    void serverPlacingSwingsTheArm(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        Settings.pin(game).set("allowPlace", true).set("allowBreak", false).set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        bot.give(Items.COBBLESTONE, 16);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            bot.tower(fp.blockPosition().getY() + 3, "minecraft:cobblestone", false);
            int swung = 0, t = 0;
            for (; t < 200 && bot.busy(); t++) {
                bot.tick();
                if (fp.swinging) swung++;
            }
            int placed = 16 - bot.count(Items.COBBLESTONE);
            if (placed < 1) fail("rig broken: the tower placed nothing in " + t + " ticks");
            if (swung < 1) fail("the tower placed " + placed + " blocks and the arm never swung");
        });
    }

    /** A dig tick swings the arm, as the client's {@code continueDestroyBlock} does. */
    @Test
    void serverDiggingSwingsTheArm(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        plot.place(1, 1, 0, Blocks.STONE);
        BlockPos cell = plot.at(1, 1, 0);
        Settings.pinUntimed(game).set("allowBreak", true).set("allowPlace", false).set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(-1, 1, 0));
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            bot.mineCell(cell);
            int swung = 0, t = 0;
            for (; t < 200 && bot.busy(); t++) {
                bot.tick();
                if (fp.swinging) swung++;
            }
            if (!bot.level().getBlockState(cell).isAir()) fail("rig broken: the cell was not dug in " + t + " ticks");
            if (swung < 1) fail("the bot dug " + cell.toShortString() + " and the arm never swung");
        });
    }

    /** The guard on the other side: a hit still swings, from the attack's call site. */
    @Test
    void serverAttackStillSwingsOnce(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        Settings.pin(game).set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            net.minecraft.world.entity.monster.Zombie z =
                    new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, fp.serverLevel());
            z.setNoAi(true);
            z.setPos(fp.getX() + 1.5, fp.getY(), fp.getZ());
            fp.serverLevel().addFreshEntity(z);
            bot.tick(20);
            float before = z.getHealth();
            var hit = bot.attack(z.getId());
            boolean swingingNow = fp.swinging;
            z.discard();
            if (!(hit instanceof net.magicterra.worlddriver.api.methods.shared.Responses.Attacked) || z.getHealth() >= before)
                fail("rig broken: the hit was refused or did no damage: " + hit);
            if (!swingingNow) fail("a hit no longer swings the arm: " + hit);
        });
    }

    /**
     * A creative bot whose block sits only in the bag takes it into the hand through the body's own swap, and ends
     * holding it in a hotbar slot. The tower's held-item scan used to call {@code Inventory.pickSlot} instead.
     */
    @Test
    void serverCreativeTowerTakesTheBlockFromTheBag(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        Settings.pin(game).set("allowPlace", true).set("allowBreak", false).set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            fp.getInventory().clearContent();
            fp.getInventory().items.set(20, new ItemStack(Items.COBBLESTONE, 16));
            int startY = fp.blockPosition().getY();
            bot.tower(startY + 1, "minecraft:cobblestone", false);
            bot.tickUntilIdle(200);
            int sel = fp.getInventory().selected;
            String at = "selected=" + sel + " holding=" + fp.getInventory().getSelected() + " feetY=" + fp.blockPosition().getY()
                    + " lastError=" + bot.status().slots().builder().lastError().orElse(null);
            fp.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            if (!fp.getInventory().getSelected().is(Items.COBBLESTONE)) fail("the block never reached the hand: " + at);
            if (fp.blockPosition().getY() <= startY) fail("the tower did not rise: " + at);
        });
    }

    /** {@code mc.observe.player}'s attack readiness for the bot's player, which joined the player list. Server thread. */
    private static Attack attackOf(SceneBot bot) {
        String name = bot.player().getGameProfile().getName();
        ObserveContracts.PlayerReading r = bot.observe().player(new ObserveContracts.PlayerParams(Optional.of(name)));
        return r.player().orElseThrow(() -> new AssertionError("mc.observe.player does not find " + name
                + " in the player list")).attack();
    }
}
