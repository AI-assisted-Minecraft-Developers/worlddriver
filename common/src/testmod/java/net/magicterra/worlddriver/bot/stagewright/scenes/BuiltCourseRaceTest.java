package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.List;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseAttempt;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseBody;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRace;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRun;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseTerrain;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseWorld;
import net.magicterra.worlddriver.bot.stagewright.kit.ElytraCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.TaskCourse;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A server bot against the client's bot on the benchmark's built courses: the route courses, the elytra course and the
 * task courses. Each bot gets its own copy of the course, the same structure placed in a plot of its own, and both
 * start on the same tick; the server bot, the joined server player {@code SceneBot} spawns, has to do the course in no
 * more than {@value CourseRace#TIME_SLACK} times the client's ticks with no more than {@value CourseRace#ERROR_SLACK}
 * errors more ({@link CourseRace}). The courses are {@link BuiltCourseTest}'s, {@link ElytraCourseTest}'s and
 * {@link TaskCourseTest}'s, at their plot sizes.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_RULES)
@ResourceLock(Locks.WORLD_CLOCK)
class BuiltCourseRaceTest {

    /** Gaps, jumps, stairs, a ladder and a vine, with breaking, placing and the water bucket off. */
    @Test
    void parkourRace(
            Game game,
            Client client,
            Player player,
            @Plot.Size(x = 96, y = 24, z = 16) Plot clientPlot,
            @Plot.Size(x = 96, y = 24, z = 16) Plot serverPlot) {
        route(game, client, player, clientPlot, serverPlot, BuiltCourseTest.PARKOUR, BuiltCourseTest.PARKOUR_CORNER);
    }

    /** A long swim, a dive under a wall, a drop into a pool and a wade. */
    @Test
    void swimmingRace(
            Game game,
            Client client,
            Player player,
            @Plot.Size(x = 100, y = 24, z = 12) Plot clientPlot,
            @Plot.Size(x = 100, y = 24, z = 12) Plot serverPlot) {
        route(game, client, player, clientPlot, serverPlot, BuiltCourseTest.SWIMMING, BuiltCourseTest.SWIMMING_CORNER);
    }

    /** Climbing out of water where no jump reaches the bank, breaking on. */
    @Test
    void digAshoreRace(
            Game game,
            Client client,
            Player player,
            @Plot.Size(x = 80, y = 24, z = 12) Plot clientPlot,
            @Plot.Size(x = 80, y = 24, z = 12) Plot serverPlot) {
        route(
                game,
                client,
                player,
                clientPlot,
                serverPlot,
                BuiltCourseTest.DIG_ASHORE,
                BuiltCourseTest.DIG_ASHORE_CORNER);
    }

    /** A wall to tunnel, a wall to pillar, a gap to bridge and a fall only a water bucket makes harmless. */
    @Test
    void digAndBuildRace(
            Game game,
            Client client,
            Player player,
            @Plot.Size(x = 60, y = 32, z = 12) Plot clientPlot,
            @Plot.Size(x = 60, y = 32, z = 12) Plot serverPlot) {
        route(
                game,
                client,
                player,
                clientPlot,
                serverPlot,
                BuiltCourseTest.DIG_AND_BUILD,
                BuiltCourseTest.DIG_AND_BUILD_CORNER);
    }

    /** Off the tower, through seven rings and onto the pad. */
    // Plots wider than the course: the glide's lookahead reaches about 60 blocks and must not meet the other copy.
    @Test
    void elytraRingsRace(
            Game game,
            Client client,
            Player player,
            @Plot.Size(x = 236, y = 48, z = 140) Plot clientPlot,
            @Plot.Size(x = 236, y = 48, z = 140) Plot serverPlot) {
        CourseWorld.noRandomTicks(game);
        CourseWorld.bench(game);
        ElytraCourse onClient = elytra(clientPlot);
        ElytraCourse onServer = elytra(serverPlot);
        CourseBody c = CourseBody.client(game, client, player);
        CourseBody s = CourseBody.server(game);
        onClient.stage(game, c);
        onServer.stage(game, s);
        List<CourseRun> runs = CourseAttempt.race(
                game,
                List.of(
                        onClient.begin(game, c, CourseRace.inPlot(clientPlot)),
                        onServer.begin(game, s, CourseRace.inPlot(serverPlot))));
        new CourseRace(onClient.name())
                .judge(game, runs.get(0), onClient.score(runs.get(0)), runs.get(1), onServer.score(runs.get(1)));
    }

    /** Ten ores out of a block of deepslate with an iron pickaxe. */
    @Test
    void oreBlockRace(
            Game game, Client client, Player player, @Plot.Size(32) Plot clientPlot, @Plot.Size(32) Plot serverPlot) {
        task(game, client, player, clientPlot, serverPlot, TaskCourseTest.ORE_BLOCK, TaskCourseTest.ORE_BLOCK_CORNER);
    }

    /** A volume of mixed blocks broken down to air. */
    @Test
    void clearAreaRace(
            Game game, Client client, Player player, @Plot.Size(32) Plot clientPlot, @Plot.Size(32) Plot serverPlot) {
        task(game, client, player, clientPlot, serverPlot, TaskCourseTest.CLEAR_AREA, TaskCourseTest.CLEAR_AREA_CORNER);
    }

    /** A hut of 46 blocks next to a copy of it. */
    @Test
    void buildHutRace(
            Game game, Client client, Player player, @Plot.Size(32) Plot clientPlot, @Plot.Size(32) Plot serverPlot) {
        task(game, client, player, clientPlot, serverPlot, TaskCourseTest.BUILD_HUT, TaskCourseTest.BUILD_HUT_CORNER);
    }

    /** Eighty ripe wheat harvested and replanted without trampling. */
    @Test
    void farmWheatRace(
            Game game, Client client, Player player, @Plot.Size(32) Plot clientPlot, @Plot.Size(32) Plot serverPlot) {
        task(game, client, player, clientPlot, serverPlot, TaskCourseTest.FARM_WHEAT, TaskCourseTest.FARM_WHEAT_CORNER);
    }

    private static void route(
            Game game,
            Client client,
            Player player,
            Plot clientPlot,
            Plot serverPlot,
            RouteCourse course,
            BlockPos corner) {
        CourseWorld.noRandomTicks(game);
        CourseWorld.bench(game);
        RouteCourse onClient = course.shifted(CourseTerrain.place(
                clientPlot, course.name(), corner, BuiltCourseTest.MARGIN, 0, BuiltCourseTest.MARGIN));
        RouteCourse onServer = course.shifted(CourseTerrain.place(
                serverPlot, course.name(), corner, BuiltCourseTest.MARGIN, 0, BuiltCourseTest.MARGIN));
        CourseBody c = CourseBody.client(game, client, player);
        CourseBody s = CourseBody.server(game);
        onClient.stage(game, c);
        onServer.stage(game, s);
        List<CourseRun> runs = CourseAttempt.race(
                game,
                List.of(
                        onClient.begin(game, c, CourseRace.inPlot(clientPlot)),
                        onServer.begin(game, s, CourseRace.inPlot(serverPlot))));
        new CourseRace(course.name())
                .judge(game, runs.get(0), onClient.score(runs.get(0)), runs.get(1), onServer.score(runs.get(1)));
    }

    private static void task(
            Game game,
            Client client,
            Player player,
            Plot clientPlot,
            Plot serverPlot,
            TaskCourse course,
            BlockPos corner) {
        CourseWorld.noRandomTicks(game);
        CourseWorld.bench(game);
        TaskCourse onClient = course.shifted(CourseTerrain.place(
                clientPlot, course.name(), corner, TaskCourseTest.MARGIN, 0, TaskCourseTest.MARGIN));
        TaskCourse onServer = course.shifted(CourseTerrain.place(
                serverPlot, course.name(), corner, TaskCourseTest.MARGIN, 0, TaskCourseTest.MARGIN));
        CourseBody c = CourseBody.client(game, client, player);
        CourseBody s = CourseBody.server(game);
        onClient.stage(game, c);
        onServer.stage(game, s);
        List<CourseRun> runs = CourseAttempt.race(
                game,
                List.of(
                        onClient.begin(game, c, CourseRace.inPlot(clientPlot)),
                        onServer.begin(game, s, CourseRace.inPlot(serverPlot))));
        new CourseRace(course.name())
                .judgeTask(game, runs.get(0), onClient.score(runs.get(0)), runs.get(1), onServer.score(runs.get(1)));
    }

    /** The elytra course placed in {@code plot}, where {@link ElytraCourseTest} places it. */
    private static ElytraCourse elytra(Plot plot) {
        ElytraCourse c = ElytraCourseTest.ELYTRA_RINGS;
        return c.shifted(CourseTerrain.place(plot, c.name(), ElytraCourseTest.ELYTRA_RINGS_CORNER, 2, 6, 2));
    }
}
