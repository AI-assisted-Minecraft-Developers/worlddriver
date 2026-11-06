package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.client.ClientBlockingApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenBlockingApi;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** The client a test reaches through Client: what it sees, and input it sends that the server receives. */
@Scene("wd.jupiterSmoke")
@Requires(Requires.Capability.CLIENT)
class JupiterClientTest {

    @Test
    void theClientSeesTheServersPlayerAsItsOwn(Client client, Player player) {
        ClientContracts.ClientPlayer seen = client.api(ClientBlockingApi::of).player(new NoParams());
        assertTrue(seen.present(), "a player is in the client's world");
        assertEquals(Optional.of(player.name()), seen.name());
        ScreenContracts.ScreenInfo screen = client.api(ScreenBlockingApi::of).screenInfo(new NoParams());
        assertTrue(screen.worldOpen(), "the client has the world open");
    }

    @Test
    @ResourceLock("wd.clientHotbar")
    void aHotbarSlotPickedOnTheClientReachesTheServer(Game game, Client client, Player player) {
        ScreenBlockingApi screen = client.api(ScreenBlockingApi::of);
        try {
            screen.setHotbarSlot(new ScreenContracts.HotbarParams(3, Optional.empty()));
            game.await()
                    .within(40)
                    .as("the server sees slot 3 selected")
                    .until(() -> player.<Integer>onServer(p -> p.getInventory().selected) == 3);
        } finally {
            screen.setHotbarSlot(new ScreenContracts.HotbarParams(0, Optional.empty()));
        }
    }
}
