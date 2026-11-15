package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.List;
import java.util.function.Function;
import net.magicterra.stagewright.test.Game;

/**
 * A run of a course started on one body: its {@link CourseLoop.Lane}, stepped by whoever runs it, and how the run is
 * wound up into a {@link CourseRun} once the lane has ended (the bot cancelled, the last block events read, the
 * recording written). {@link #run} steps one alone; {@link #race} steps several on the same ticks.
 *
 * @param body the body it runs on
 * @param lane the run's loop
 * @param finish winds the run up from how its lane ended
 */
public record CourseAttempt(CourseBody body, CourseLoop.Lane lane, Function<CourseLoop.End, CourseRun> finish) {

    /** An attempt that ended before it started: the order was refused. */
    public static CourseAttempt refused(CourseBody body, Object reply) {
        CourseRun run = CourseRun.refused(reply);
        CourseLoop.Lane over = new CourseLoop.Lane(() -> null, 0, s -> null, () -> run.end(), 0, 1);
        return new CourseAttempt(body, over, end -> run);
    }

    /** Step this attempt alone until it ends, and wind it up. */
    public CourseRun run(Game game) {
        return finish.apply(CourseLoop.run(game, lane));
    }

    /**
     * Step every attempt on the same ticks until each has ended, winding each up the moment its own lane ends, so that
     * a bot done first is stopped there and not sampled again while the others run on. The runs come back in the order
     * of {@code attempts}.
     */
    public static List<CourseRun> race(Game game, List<CourseAttempt> attempts) {
        CourseRun[] runs = new CourseRun[attempts.size()];
        while (true) {
            boolean running = false;
            for (int i = 0; i < runs.length; i++) {
                if (runs[i] != null) {
                    continue;
                }
                CourseLoop.End end = attempts.get(i).lane().step();
                if (end == null) {
                    running = true;
                } else {
                    runs[i] = attempts.get(i).finish().apply(end);
                }
            }
            if (!running) {
                return List.of(runs);
            }
            game.ticks(1);
        }
    }
}
