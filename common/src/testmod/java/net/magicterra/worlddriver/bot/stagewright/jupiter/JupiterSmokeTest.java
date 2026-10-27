package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Perf;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.action.ActionBlockingApi;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.FillParams;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.world.WorldBlockingApi;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.BlockParams;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** The in-game JUnit runner end to end: blocking API calls, tick waits, and an await that runs out. */
@Scene("wd.jupiterSmoke")
class JupiterSmokeTest {

    @Test
    void aFilledBlockReadsBack(Game game) {
        // Above the spawn chunks, which stay loaded, and above anything the world generated there.
        BlockPos spawn = game.onServer(server -> server.overworld().getSharedSpawnPos());
        BlockPosition at = new BlockPosition(spawn.getX(), 300, spawn.getZ());
        ActionBlockingApi action = game.api(ActionBlockingApi::of);
        assertEquals(1, action.fill(new FillParams(at, at, "minecraft:stone", false)).placed());
        try {
            assertEquals("minecraft:stone", game.api(WorldBlockingApi::of).block(new BlockParams(at, false)).type());
        } finally {
            action.fill(new FillParams(at, at, "minecraft:air", false));
        }
    }

    @Test
    void ticksPassOnTheServer(Game game) {
        int before = game.onServer(server -> server.getTickCount());
        game.ticks(5);
        int after = game.onServer(server -> server.getTickCount());
        assertTrue(after - before >= 5, "ticks advanced by " + (after - before));
    }

    @Test
    @Requires(value = Requires.Capability.CLIENT, mods = "worlddriver")
    void theClientThisSceneJoinsIsOnTheServer(Game game) {
        int players = game.onServer(server -> server.getPlayerCount());
        assertTrue(players >= 1, players + " players on the server");
    }

    @Test
    void perfMeasuresEachPhaseAfterItsSetup(Game game) {
        int[] ran = {0};
        Map<String, Perf.Window> r = game.perf(Perf.phases().warmup(5).sample(20).repeat(1)
                .phase("baseline", () -> {})
                .phase("after", () -> ran[0]++));
        assertEquals(1, ran[0]);
        assertEquals(List.of("baseline", "after"), List.copyOf(r.keySet()));
        assertEquals(20, r.get("after").ticks());
        // A dedicated server has no client to count frames of; the integrated server does.
        assertTrue(r.get("after").cpu() > 0, "cpu " + r.get("after").cpu());
    }

    @Test
    void anAwaitThatNeverHoldsFails(Game game) {
        AssertionError e = assertThrows(AssertionError.class,
                () -> game.await().within(3).as("a condition that never holds").until(() -> false));
        assertTrue(e.getMessage().contains("a condition that never holds"), e.getMessage());
    }
}
