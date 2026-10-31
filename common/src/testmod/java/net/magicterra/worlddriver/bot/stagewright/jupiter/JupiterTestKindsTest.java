package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.observe.ObserveBlockingApi;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The JUnit test kinds besides {@code @Test} run in the game with its parameters and soft checks, and a client method
 * takes the client's own player by the ref a test has for it.
 */
@Scene("wd.jupiterSmoke")
class JupiterTestKindsTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void aParameterizedTestTakesTheGameBesideItsArgument(int ticks, Game game) {
        long before = game.onServer(server -> (long) server.getTickCount());
        game.ticks(ticks);
        long after = game.onServer(server -> (long) server.getTickCount());
        game.check(after - before >= ticks, ticks + " ticks ran");
    }

    @TestFactory
    Stream<DynamicTest> dynamicTestsMakeSoftChecksOnTheirOwnThreads(Game game) {
        return IntStream.rangeClosed(1, 4)
                .mapToObj(i -> DynamicTest.dynamicTest(
                        "case " + i,
                        () -> game.check(
                                game.onServer(server -> server.overworld() != null), "case " + i + " sees the world")));
    }

    @Test
    @Requires(Requires.Capability.CLIENT)
    void aClientMethodTakesTheClientsOwnPlayerByItsRef(Client client, Player player) {
        ObserveContracts.Threats threats = client.api(ObserveBlockingApi::of)
                .threats(new ObserveContracts.ThreatsParams(8, Optional.of(player.ref())));
        // Before, the ref of the client's own player was refused as an unknown server bot.
        assertNotNull(threats.threats(), "answered as for self");
    }

    @Test
    @Requires(Requires.Capability.CLIENT)
    void theScenesClientFilesAreInTheJoinedClientsDirectory() throws IOException {
        Path file = Path.of(System.getProperty("stagewright.companion.dir"), "stagewright-client-files-check.txt");
        assertEquals("written for the client", Files.readString(file).trim());
    }
}
