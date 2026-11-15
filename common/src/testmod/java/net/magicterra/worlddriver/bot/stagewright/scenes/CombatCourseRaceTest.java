package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.CombatCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.CombatCourse.Mob;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseAttempt;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseBody;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRace;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRun;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseTerrain;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseWorld;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A server bot against the client's bot on the benchmark's combat courses, judged on the medians of the bench's five
 * runs each ({@link CourseRace}). The arena is built, so each bot fights in its own copy, placed in a plot of its own,
 * with its own wave summoned on the same tick. The cave is the world's own and cannot be copied, and two waves in it at
 * once would be one fight; so there the two bots take turns on the same cave: for each wave seed, the cave put back and
 * the client's run, then the cave put back and the server's run on the same wave, the bot whose turn it is not standing
 * aside as a spectator. The courses are {@link CombatCourseTest}'s.
 */
@Scene("wd.combatCourses")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_RULES)
@ResourceLock(Locks.WORLD_CLOCK)
class CombatCourseRaceTest {

    private static final int RUNS = 5;

    /** The tag of the server's wave in the arena; the client's keeps the course's own. */
    private static final String SERVER_TAG = CombatCourse.MOB_TAG + "_server";

    /** Three zombies, two skeletons with bows and a creeper, one wave in each bot's arena. */
    @Test
    void arenaRace(
            Game game, Client client, Player player, @Plot.Size(32) Plot clientPlot, @Plot.Size(32) Plot serverPlot) {
        CourseWorld.noRandomTicks(game);
        CombatCourse.world(game);
        BlockPos clientShift = place(clientPlot);
        BlockPos serverShift = place(serverPlot);
        CombatCourse onClient = CombatCourseTest.ARENA.shifted(clientShift);
        CombatCourse onServer = CombatCourseTest.ARENA.shifted(serverShift);
        List<Mob> clientWave = CombatCourse.shifted(CombatCourseTest.ARENA_WAVE, clientShift);
        List<Mob> serverWave = CombatCourse.shifted(CombatCourseTest.ARENA_WAVE, serverShift);
        CourseBody c = CourseBody.client(game, client, player);
        CourseBody s = CourseBody.server(game);
        List<CourseRun> clientRuns = new ArrayList<>();
        List<CourseRun> serverRuns = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            if (i > 0) {
                place(clientPlot);
                place(serverPlot);
            }
            onClient.stage(game, c, CombatCourse.MOB_TAG);
            onServer.stage(game, s, SERVER_TAG);
            List<CourseRun> runs = CourseAttempt.race(
                    game,
                    List.of(
                            onClient.begin(game, c, clientWave, CombatCourse.MOB_TAG, CourseRace.inPlot(clientPlot)),
                            onServer.begin(game, s, serverWave, SERVER_TAG, CourseRace.inPlot(serverPlot))));
            clientRuns.add(runs.get(0));
            serverRuns.add(runs.get(1));
        }
        onClient.clearArena(game, CombatCourse.MOB_TAG);
        onServer.clearArena(game, SERVER_TAG);
        new CourseRace(onClient.name())
                .judgeMedian(
                        game,
                        clientRuns,
                        clientRuns.stream().map(onClient::score).toList(),
                        serverRuns,
                        serverRuns.stream().map(onServer::score).toList());
    }

    /** The cave's five waves with a shield, the two bots taking turns. */
    @Test
    void caveWithShieldRace(Game game, Client client, Player player) {
        cave(game, client, player, CombatCourseTest.CAVE_SHIELD);
    }

    /** The same cave and waves without a shield. */
    @Test
    void caveBareRace(Game game, Client client, Player player) {
        cave(game, client, player, CombatCourseTest.CAVE_BARE);
    }

    private static void cave(Game game, Client client, Player player, CombatCourse course) {
        CombatCourseTest.generate(game);
        CombatCourse.world(game);
        String id = "race-" + course.name();
        Calls.byName(
                game.registry(),
                "mc.world.snapshot",
                Map.of(
                        "id",
                        id,
                        "from",
                        xyz(CombatCourseTest.CAVE_FROM),
                        "to",
                        xyz(CombatCourseTest.CAVE_TO),
                        "blockEntities",
                        true,
                        "entities",
                        false));
        Runnable reset = () -> Calls.byName(game.registry(), "mc.world.restore", Map.of("id", id));
        CourseBody c = CourseBody.client(game, client, player);
        CourseBody s = CourseBody.server(game);
        List<CourseRun> clientRuns = new ArrayList<>();
        List<CourseRun> serverRuns = new ArrayList<>();
        try {
            for (List<Mob> wave : CombatCourseTest.CAVE_WAVES) {
                reset.run();
                course.stage(game, c, CombatCourse.MOB_TAG);
                clientRuns.add(course.begin(game, c, wave, CombatCourse.MOB_TAG, e -> true)
                        .run(game));
                c.standAside();
                reset.run();
                course.stage(game, s, CombatCourse.MOB_TAG);
                serverRuns.add(course.begin(game, s, wave, CombatCourse.MOB_TAG, e -> true)
                        .run(game));
                s.standAside();
            }
        } finally {
            course.clearArena(game, CombatCourse.MOB_TAG);
            reset.run();
        }
        new CourseRace(course.name())
                .judgeMedian(
                        game,
                        clientRuns,
                        clientRuns.stream().map(course::score).toList(),
                        serverRuns,
                        serverRuns.stream().map(course::score).toList());
    }

    /** Place the arena in {@code plot}, where {@link CombatCourseTest} places it; answers how far that moves it. */
    private static BlockPos place(Plot plot) {
        return CourseTerrain.place(
                plot,
                CombatCourseTest.ARENA.name(),
                CombatCourseTest.ARENA_CORNER,
                CombatCourseTest.MARGIN,
                0,
                CombatCourseTest.MARGIN);
    }

    private static Map<String, Object> xyz(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }
}
