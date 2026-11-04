package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * A bot's player does what a player does, as Carpet's fake player does: it is hurt, dies, drops what it carries and
 * respawns, is made invulnerable only as any player is, keeps the knockback another player's hit gives it, and is
 * updated by the level when no driver steps it.
 */
@Scene("wd.scenes")
class BotBodyTest {

    /**
     * A parked bot on the floor, stepped past the 60 ticks of protection vanilla gives a player that has just joined; a
     * parked bot counts them only as it is stepped.
     */
    private static SceneBot hurtable(Game game, Plot plot, int x) {
        SceneBot bot = SceneBot.spawn(game, plot.at(x, 1, 0));
        bot.step(61);
        return bot;
    }

    @Test
    void aBotIsHurtDiesAndDropsWhatItCarriesAsAPlayerDoes(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        SceneArena.buildFloor(plot, 0, 0, 0);
        SceneBot bot = hurtable(game, plot, 0);
        bot.give(Items.DIAMOND, 3);
        game.runOnServer(server ->
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server));

        float hurt = game.onServer(server -> {
            ServerPlayer p = bot.player();
            assertTrue(p.hurt(p.damageSources().generic(), 4.0F), "a generic hit lands on the bot");
            return p.getHealth();
        });
        assertEquals(16.0F, hurt, "the hit took 4 of 20 health");

        ServerPlayer dead = game.onServer(server -> {
            ServerPlayer p = bot.player();
            p.hurt(p.damageSources().generic(), 100.0F);
            return p;
        });
        game.await().within(5).as("the bot is dead").until(() -> game.onServerHolds(s -> dead.isDeadOrDying()));
        BlockPos at = plot.at(0, 1, 0);
        game.await()
                .within(40)
                .as("the bot's diamonds lie where it died")
                .until(() -> game.onServerHolds(
                        s -> dead.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3)).stream()
                                .anyMatch(i -> i.getItem().is(Items.DIAMOND))));
        game.ticks(30);
        assertTrue(
                game.onServerHolds(s -> s.getPlayerList().getPlayer(dead.getUUID()) == dead),
                "a dead bot stays on the player list, as a player stays on the death screen");
    }

    @Test
    void aDeadBotRespawnsAndItsRefDrivesThePlayerThatReplacedIt(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        SceneArena.buildFloor(plot, 0, 0, 0);
        SceneBot bot = hurtable(game, plot, 0);
        ServerPlayer dead = game.onServer(server -> {
            ServerPlayer p = bot.player();
            p.hurt(p.damageSources().generic(), 100.0F);
            return p;
        });
        game.await().within(5).as("the bot is dead").until(() -> game.onServerHolds(s -> dead.isDeadOrDying()));

        bot.respawn();
        ServerPlayer back = bot.player();
        assertNotSame(dead, back, "a respawn replaces the player, as vanilla's does");
        assertFalse(game.onServerHolds(s -> back.isDeadOrDying()), "it is alive");
        float health = game.onServer(s -> back.getHealth());
        assertEquals(back.getMaxHealth(), health, "at full health");
        assertSame(back, game.onServer(s -> s.getPlayerList().getPlayer(back.getUUID())), "and on the player list");
        // The bot drives the player that replaced the dead one: a step moves it.
        bot.press(k -> k.move(0, 1));
        double z0 = game.onServer(s -> back.getZ());
        bot.step(10);
        assertNotEquals(z0, game.onServer(s -> back.getZ()), "the bot's steps move the player that replaced it");
    }

    @Test
    void aRespawnedBotPlansWithTheBlocksItCarriesNow(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 12) Plot plot) {
        Settings.pin(game).set("allowPlace", true).set("allowBreak", false);
        // Two pads with a three-block gap, a pit under it with no way back up: the far pad is reached only by
        // bridging, which takes blocks. The dead player dropped everything; only the new one is given any.
        plot.fill(-8, 0, -1, -1, 0, 1, Blocks.STONE);
        plot.fill(3, 0, -1, 8, 0, 1, Blocks.STONE);
        plot.fill(-10, -10, -5, 10, -10, 5, Blocks.STONE);
        SceneBot bot = hurtable(game, plot, -4);
        game.runOnServer(server ->
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server));
        // A brain that has run once has a view of the world through the player it drove then.
        bot.tick(5);
        ServerPlayer dead = game.onServer(server -> {
            ServerPlayer p = bot.player();
            p.hurt(p.damageSources().generic(), 100.0F);
            return p;
        });
        game.await().within(5).as("the bot is dead").until(() -> game.onServerHolds(s -> dead.isDeadOrDying()));
        bot.respawn();
        bot.moveTo(plot.at(-4, 1, 0));
        bot.give(Items.COBBLESTONE, 16);

        BlockPos goal = plot.at(5, 1, 0);
        bot.goTo(Targets.block(goal));
        bot.tickUntilIdle(600);
        BlockPos at = game.onServer(s -> bot.player().blockPosition());
        assertTrue(
                at.getY() == goal.getY() && at.getX() >= plot.at(3, 0, 0).getX(),
                "the respawned bot bridged to the far pad with the blocks it was given; it stands at "
                        + at.toShortString() + ", the gap is x "
                        + plot.at(0, 0, 0).getX() + ".." + plot.at(2, 0, 0).getX());
        assertTrue(bot.count(Items.COBBLESTONE) < 16, "and it spent some of them");
    }

    @Test
    void aRespawnedBotHoldsNoneOfTheKeysTheDeadOneHeld(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        SceneArena.buildFloor(plot, 0, 0, 0);
        SceneBot bot = hurtable(game, plot, 0);
        bot.press(k -> k.move(0, 1));
        bot.step(2);
        ServerPlayer dead = game.onServer(server -> {
            ServerPlayer p = bot.player();
            p.hurt(p.damageSources().generic(), 100.0F);
            return p;
        });
        game.await().within(5).as("the bot is dead").until(() -> game.onServerHolds(s -> dead.isDeadOrDying()));
        bot.respawn();
        bot.moveTo(plot.at(0, 1, 0));
        double z0 = game.onServer(s -> bot.player().getZ());
        bot.step(10);
        assertEquals(
                z0,
                game.onServer(s -> bot.player().getZ()),
                1.0E-6,
                "a client's keys are up after the death screen: the new player does not walk on");
    }

    @Test
    void aKickedBotLeavesOnTheNextTaskNotInsideTheCallThatKickedIt(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        SceneArena.buildFloor(plot, 0, 0, 0);
        SceneBot bot = hurtable(game, plot, 0);
        ServerPlayer kicked = bot.player();
        // PlayerList.removeAll walks the list by index while it disconnects each player; a player that left inside
        // the call would shift the list under it and the next one would be skipped.
        boolean stillListed = game.onServer(server -> {
            kicked.connection.disconnect(Component.literal("kicked by a test"));
            return server.getPlayerList().getPlayers().contains(kicked);
        });
        assertTrue(stillListed, "the kicked bot is still on the list inside the call that kicked it");
        game.await()
                .within(5)
                .as("the kicked bot leaves the game")
                .until(() ->
                        game.onServerHolds(s -> !s.getPlayerList().getPlayers().contains(kicked)));
    }

    @Test
    void abilitiesMakeABotInvulnerableAsTheyDoAnyPlayer(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        SceneArena.buildFloor(plot, 0, 0, 0);
        SceneBot bot = hurtable(game, plot, 0);
        boolean landed = game.onServer(server -> {
            ServerPlayer p = bot.player();
            p.getAbilities().invulnerable = true;
            p.onUpdateAbilities();
            return p.hurt(p.damageSources().generic(), 4.0F);
        });
        assertFalse(landed, "an invulnerable player takes no generic damage");
    }

    @Test
    void aBotKeepsTheKnockbackOfAnotherPlayersHit(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        SceneArena.buildFloor(plot, 0, 0, 0);
        SceneBot attacker = hurtable(game, plot, -1);
        SceneBot struck = hurtable(game, plot, 1);
        double pushed = game.onServer(server -> {
            ServerPlayer a = attacker.player();
            ServerPlayer b = struck.player();
            a.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, b.getEyePosition());
            a.attack(b);
            return b.getDeltaMovement().horizontalDistance();
        });
        assertTrue(pushed > 0.1, "the hit pushed the struck bot (horizontal speed " + pushed + ")");
    }

    @Test
    void aParkedBotIsMovedOnlyByItsStepsAndAnUnparkedOneDiesAsTheServerTicksIt(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        SceneArena.buildFloor(plot, 0, 0, 0);
        // Spawned parked: hand ticked, so the server leaves it where it was put, five blocks up.
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 6, 0));
        double y0 = game.onServer(s -> bot.player().getY());
        game.ticks(10);
        assertEquals(y0, game.onServer(s -> bot.player().getY()), "a parked bot does not fall");

        // Unparked and dead: its driver stands down, and the server runs its death, 20 ticks to leave the level.
        bot.step(61);
        bot.unpark();
        ServerPlayer dead = game.onServer(server -> {
            ServerPlayer p = bot.player();
            p.hurt(p.damageSources().generic(), 100.0F);
            return p;
        });
        game.await()
                .within(40)
                .as("the dead bot leaves its level as a dead player does")
                .until(() -> game.onServerHolds(s -> dead.isRemoved()));
        assertSame(
                dead,
                game.onServer(s -> s.getPlayerList().getPlayer(dead.getUUID())),
                "still on the player list, waiting to respawn");
        bot.respawn();
        assertFalse(game.onServerHolds(s -> bot.player().isDeadOrDying()), "and it comes back");
    }
}
