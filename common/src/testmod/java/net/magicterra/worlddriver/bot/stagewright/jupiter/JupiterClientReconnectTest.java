package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.client.ClientBlockingApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.testcontent.TestPlayerEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

/** The real client leaving the server and joining it again, as a player does, with what the server sends a joiner. */
@Scene("wd.jupiterSmoke")
@Requires({Requires.Capability.CLIENT, Requires.Capability.DEDICATED_SERVER})
class JupiterClientReconnectTest {

    @Test
    void whatTheServerSendsAtLoginReachesTheClientThatJoinsAgain(Game game, Client client, Player player) {
        String name = player.name();
        String run = UUID.randomUUID().toString();
        // What only the player's save brings back. A tag no other test reads, so one left by a failed reconnect,
        // which comes back after this test's end, harms nothing.
        String tag = "wd.reconnect." + run;
        String welcome = "welcome back " + run;
        // State the server takes on while the client is away, and sends it when it joins; bots are not greeted.
        Consumer<ServerPlayer> greet = p -> {
            if (name.equals(p.getGameProfile().getName())) {
                p.sendSystemMessage(Component.literal(welcome));
            }
        };
        game.afterTest(s -> {
            TestPlayerEvents.removeJoinListener(greet);
            ServerPlayer back = s.getPlayerList().getPlayerByName(name);
            if (back != null) {
                back.removeTag(tag);
            }
        });
        // The chat seq never rewinds, a reconnect included: only lines from here on.
        ClientBlockingApi seen = client.api(ClientBlockingApi::of);
        long since =
                seen.chatHistory(new ClientContracts.ChatHistoryParams(1, 0)).nextSeq();
        ServerPlayer before = player.onServer(p -> {
            p.addTag(tag);
            return p;
        });

        client.disconnect();
        TestPlayerEvents.addJoinListener(greet);
        client.reconnect();

        assertNotSame(before, player.onServer(p -> p), "joined again, as a new player object");
        assertTrue(player.<Boolean>onServer(p -> p.getTags().contains(tag)), "the player is back from its save");
        game.await()
                .within(100)
                .as("the client shows the line the server sent it at login")
                .until(() -> seen.chatHistory(new ClientContracts.ChatHistoryParams(256, since)).messages().stream()
                        .anyMatch(l -> l.text().contains(welcome)));
    }

    @Test
    void aTestMayEndWithTheClientDisconnected(Client client) {
        client.disconnect();
    }

    @Test
    @DependsOn("aTestMayEndWithTheClientDisconnected")
    void andTheNextTestFindsItBackInTheGame(Client client, Player player) {
        // The Player resolving at all says the server has the client's player again; this, that the client is in.
        assertEquals(
                Optional.of(player.name()),
                client.api(ClientBlockingApi::of).player(new NoParams()).name(),
                "the client is in its world as the server's player");
    }
}
