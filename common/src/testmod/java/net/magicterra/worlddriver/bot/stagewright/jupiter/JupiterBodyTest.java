package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.BotTaskBlockingApi;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/** Bodies handed to tests: a bot joined for one test and gone after it, and the real client's player. */
@Scene("wd.jupiterSmoke")
class JupiterBodyTest {

    private static volatile UUID lastBot;

    @Test
    void aBotJoinsFresh(Game game, Bot bot) {
        lastBot = bot.onServer(p -> p.getUUID());
        assertEquals("player:" + bot.name(), bot.ref());
        assertTrue(game.<Boolean>onServer(s -> s.getPlayerList().getPlayer(lastBot) != null), "on the player list");
        assertEquals(GameType.SURVIVAL, bot.onServer(p -> p.gameMode.getGameModeForPlayer()));
        boolean empty = bot.onServer(p -> p.getInventory().isEmpty());
        assertTrue(empty, "empty inventory");
        bot.give(Items.RAW_IRON, 3);
        assertEquals(3, bot.count(Items.RAW_IRON));
    }

    @Test
    @DependsOn("aBotJoinsFresh")
    void andLeavesWhenItsTestEnds(Game game) {
        UUID left = lastBot;
        assertNull(game.onServer(s -> s.getPlayerList().getPlayer(left)), "the bot is still on the server");
    }

    @Test
    void aBotJoinsUnderTheNameAsked(@Bot.Name("steve") Bot steve) {
        assertEquals("player:steve", steve.ref());
        float health = steve.onServer(p -> p.getHealth());
        assertEquals(20.0F, health);
    }

    @Test
    void aBotWalksWhereWorldDriverSendsIt(Game game, Plot plot, Bot bot) {
        plot.fill(0, 0, 0, 11, 0, 2, Blocks.STONE);
        bot.moveTo(plot.at(1, 1, 1));
        game.await().within(40).as("the bot lands on the floor").until(() -> bot.<Boolean>onServer(p -> p.onGround()));
        BlockPos target = plot.at(10, 1, 1);
        GotoContracts.Params walk = new GotoContracts.Params(bot.ref(),
                Optional.of(new GotoContracts.Position(new BlockPosition(target.getX(), target.getY(), target.getZ()),
                        0, GotoContracts.GoalMode.IN)),
                false, Optional.empty(), Optional.empty(), false, Optional.empty(), 0);
        assertInstanceOf(Responses.Accepted.class, game.api(BotTaskBlockingApi::of).goTo(walk));
        game.await().within(400).as("the bot reaches " + target).until(() -> bot.blockPos().equals(target));
    }

    @Test
    @Requires(Requires.Capability.CLIENT)
    void theClientsPlayerTakesWhatItIsGiven(Player player, Bot bot) {
        assertNotEquals(bot.name(), player.name());
        int before = player.count(Items.DIAMOND);
        player.give(Items.DIAMOND, 1);
        try {
            assertEquals(before + 1, player.count(Items.DIAMOND));
        } finally {
            player.runOnServer(p -> p.getInventory().clearOrCountMatchingItems(
                    stack -> stack.is(Items.DIAMOND), 1, p.inventoryMenu.getCraftSlots()));
        }
    }
}
