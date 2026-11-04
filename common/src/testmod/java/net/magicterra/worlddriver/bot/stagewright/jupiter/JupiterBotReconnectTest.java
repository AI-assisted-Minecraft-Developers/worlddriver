package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.testcontent.TestPlayerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * A bot that disconnects leaves through each loader's logout event, saved, and one that reconnects joins through its
 * login event as the same player, from that save. In {@code wd.scenes}.
 */
@Scene("wd.scenes")
class JupiterBotReconnectTest {

    private static final String NAME = "wdReconnects";

    /** Outside every plot, and in the nether, so neither a fresh join nor the world spawn could put it there. */
    private static final BlockPos LEFT_AT = new BlockPos(-24, 90, 24);

    @Test
    void aBotThatReconnectsIsTheSamePlayerAsItsSaveLeftIt(Game game, @Bot.Name(NAME) @Bot.Parked Bot bot) {
        bot.give(Items.DIAMOND, 3);
        bot.moveTo(Level.NETHER, LEFT_AT);
        String uuid = bot.onServer(p -> p.getUUID().toString());
        ServerPlayer before = bot.onServer(p -> p);
        List<String> events = new CopyOnWriteArrayList<>();
        Consumer<ServerPlayer> left = p -> {
            if (NAME.equals(p.getGameProfile().getName())) {
                events.add("left with " + p.getInventory().countItem(Items.DIAMOND));
            }
        };
        Consumer<ServerPlayer> joined = p -> {
            if (NAME.equals(p.getGameProfile().getName())) {
                events.add("joined with " + p.getInventory().countItem(Items.DIAMOND));
            }
        };
        TestPlayerEvents.addLeaveListener(left);
        TestPlayerEvents.addJoinListener(joined);
        game.afterTest(s -> {
            TestPlayerEvents.removeLeaveListener(left);
            TestPlayerEvents.removeJoinListener(joined);
        });

        bot.disconnect();
        assertEquals(List.of("left with 3"), events, "the loader's logout event, once");
        assertTrue(game.onServerHolds(s -> s.getPlayerList().getPlayerByName(NAME) == null), "off the player list");

        bot.reconnect();
        assertEquals(List.of("left with 3", "joined with 3"), events, "then its login event, loaded from the save");
        assertEquals(uuid, bot.<String>onServer(p -> p.getUUID().toString()), "the same player");
        assertNotSame(before, bot.onServer(p -> p), "joined again, as a new player object");
        assertEquals(Level.NETHER, bot.dimension(), "in the dimension it left");
        assertEquals(LEFT_AT, bot.blockPos(), "where it left");
        assertEquals(3, bot.count(Items.DIAMOND), "with what it had, nothing reset");
    }
}
