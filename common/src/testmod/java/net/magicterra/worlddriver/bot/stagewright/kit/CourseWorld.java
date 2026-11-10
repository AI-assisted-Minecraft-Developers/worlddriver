package net.magicterra.worlddriver.bot.stagewright.kit;

import net.magicterra.stagewright.test.Game;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/**
 * The world as {@code scripts/bench/bench.py} ran its courses in it, for the length of a course test: day ({@code time
 * set 1000} before each run) and the bench client's view and simulation distance ({@link #bench}); random ticks off on
 * a built course ({@link #noRandomTicks}: each built course's setup turns them off, so vines do not spread and crops do
 * not grow); a combat course's difficulty ({@link #difficulty}). A test calling them holds
 * {@code @ResourceLock(Locks.WORLD_CLOCK)} and {@code @ResourceLock(Locks.WORLD_RULES)}. Everything is put back when
 * the test ends.
 */
public final class CourseWorld {

    /** The bench's {@code time set 1000}. */
    private static final long BENCH_TIME = 1000;

    /** The view and simulation distance of the bench's client, in chunks. */
    private static final int BENCH_CHUNKS = 12;

    private CourseWorld() {}

    /** Put the bench's day and view distance in place for the rest of the test. */
    public static void bench(Game game) {
        game.overrideTime(BENCH_TIME);
        viewDistance(game);
    }

    /**
     * Random ticks off for the rest of the test, as a built course's setup turns them off in the Lab world; a course on
     * natural terrain keeps the world's own.
     */
    public static void noRandomTicks(Game game) {
        game.override(GameRules.RULE_RANDOMTICKING, 0);
    }

    /**
     * Set the difficulty for the rest of the test, as a combat course's setup does ({@code difficulty normal}): the
     * scene's own difficulty comes back when the test ends.
     */
    public static void difficulty(Game game, Difficulty difficulty) {
        Difficulty was = game.onServer(s -> s.getWorldData().getDifficulty());
        game.afterTest(s -> s.setDifficulty(was, true));
        game.runOnServer(s -> s.setDifficulty(difficulty, true));
    }

    /**
     * The bench's client rendered 12 chunks and hosted its own server, which then sends and simulates 12 too; a
     * dedicated server sends 10 unless told otherwise, and the client bot plans on the chunks it was sent.
     */
    private static void viewDistance(Game game) {
        record Was(int view, int simulation) {}

        Was was = game.onServer(s ->
                new Was(s.getPlayerList().getViewDistance(), s.getPlayerList().getSimulationDistance()));
        game.afterTest(s -> {
            s.getPlayerList().setViewDistance(was.view());
            s.getPlayerList().setSimulationDistance(was.simulation());
        });
        game.runOnServer(s -> {
            s.getPlayerList().setViewDistance(BENCH_CHUNKS);
            s.getPlayerList().setSimulationDistance(BENCH_CHUNKS);
        });
    }
}
