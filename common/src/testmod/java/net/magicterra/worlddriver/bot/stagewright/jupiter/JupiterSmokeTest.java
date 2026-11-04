package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Perf;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.action.ActionBlockingApi;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.FillParams;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.world.WorldBlockingApi;
import net.magicterra.worlddriver.api.methods.world.WorldContracts.BlockParams;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** The in-game JUnit runner end to end: blocking API calls, tick waits, and an await that runs out. */
@Scene("wd.jupiterSmoke")
class JupiterSmokeTest {

    @Test
    void aFilledBlockReadsBack(Game game) {
        // Above the spawn chunks, which stay loaded, and above anything the world generated there.
        BlockPos spawn = game.onServer(server -> server.overworld().getSharedSpawnPos());
        BlockPosition at = new BlockPosition(spawn.getX(), 300, spawn.getZ());
        ActionBlockingApi action = game.api(ActionBlockingApi::of);
        assertEquals(
                1, action.fill(new FillParams(at, at, "minecraft:stone", false)).placed());
        try {
            assertEquals(
                    "minecraft:stone",
                    game.api(WorldBlockingApi::of)
                            .block(new BlockParams(at, false, Optional.empty()))
                            .type());
        } finally {
            action.fill(new FillParams(at, at, "minecraft:air", false));
        }
    }

    /** The Java form of the script test that freezes the world: a wait then steps it by exactly the ticks waited. */
    @Test
    @ResourceLock(Locks.TICK_RATE)
    void aFrozenWorldMovesByTheTicksWaited(Game game) {
        game.freezeTicks();
        // The overworld's game time advances only on the ticks the world runs, not on every server tick.
        long before = YamlJavaSteps.gameTime(game, null);
        game.ticks(3);
        assertEquals(3, YamlJavaSteps.gameTime(game, null) - before);
    }

    @Test
    void aCheckInsideAServerTaskCountsForTheTest(Game game) {
        // Would fail "only from a test's own thread" if the task did not run as part of this test.
        game.runOnServer(server -> game.check(server.isSameThread(), "the task runs on the server thread"));
        assertTrue(game.onServerHolds(server -> server.isSameThread()));
        // What the test must undo when it ends is still only for its own thread.
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> game.runOnServer(server -> game.afterTest(s -> {})));
        assertTrue(refused.getMessage().contains("own thread"), refused.getMessage());
    }

    @Test
    @DependsOn(scripts = "stagewright/tests/jupiter-handles.js#a Java test depends on this")
    void runsAfterTheScriptTestItDependsOn() {
        assertTrue(YamlJavaSteps.marked("a Java test depends on this"), "the script test had not taken its step");
    }

    @Test
    void perfMeasuresEachPhaseAfterItsSetup(Game game) {
        int[] ran = {0};
        Map<String, Perf.Window> r = game.perf(Perf.phases()
                .warmup(5)
                .sample(20)
                .repeat(1)
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
        AssertionError e = assertThrows(
                AssertionError.class,
                () -> game.await().within(3).as("a condition that never holds").until(() -> false));
        assertTrue(e.getMessage().contains("a condition that never holds"), e.getMessage());
    }

    @Test
    void anAwaitWhoseConditionWaitsOnTheServerThreadFailsSayingSo(Game game) {
        CompletableFuture<Boolean> answered = new CompletableFuture<>();
        long started = System.nanoTime();
        AssertionError e = assertThrows(
                AssertionError.class,
                () -> game.await().within(400).as("the late answer").until(() -> {
                    // A task queued for the server thread, which the condition then holds waiting for it; tell,
                    // since execute on the server thread runs the task there and then.
                    game.runOnServer(s -> s.tell(new TickTask(s.getTickCount(), () -> answered.complete(true))));
                    try {
                        return answered.get(8, TimeUnit.SECONDS);
                    } catch (InterruptedException | ExecutionException | TimeoutException x) {
                        return false;
                    }
                }));
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        assertTrue(e.getMessage().contains("kept the server thread waiting"), e.getMessage());
        assertTrue(e.getMessage().startsWith("the late answer"), e.getMessage());
        assertTrue(seconds < 8, "told before the condition's own wait ran out: " + seconds + " s");
        assertTrue(
                Arrays.stream(e.getStackTrace())
                        .anyMatch(f -> f.getClassName().equals(CompletableFuture.class.getName())),
                "the server thread's stack: " + Arrays.toString(e.getStackTrace()));
        // The server is back once the condition's wait ends; the tests after this one start from there.
        game.ticks(1);
    }
}
