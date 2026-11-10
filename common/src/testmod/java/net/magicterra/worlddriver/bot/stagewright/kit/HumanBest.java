package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.Locale;
import net.magicterra.stagewright.test.Game;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The human's numbers a benchmark course is judged against, from the course's recorded human runs: the best time and
 * the fewest errors, each the best of any finished run, as {@code bench.py report} picks them. The criterion is
 * {@code docs/dev/benchmark.md}'s: the bot's run reaches the goal, is faster than the human's best, and has no more
 * errors than the human's fewest.
 *
 * @param course the course's id
 * @param seconds the human's best time, by the bench's clock
 * @param errors the fewest errors of any finished human run
 */
public record HumanBest(String course, double seconds, int errors) {

    private static final Logger LOG = LogManager.getLogger("worlddriver.course");

    /** The best time in ticks, the clock the course tests run on. */
    public int ticks() {
        return (int) Math.round(seconds * CourseRecorder.TICKS_PER_SECOND);
    }

    /** Check {@code run}, scored {@code score}, against the human; each part of the criterion fails on its own. */
    public void judge(Game game, RouteCourse.Run run, CourseErrors.Score score) {
        String summary = String.format(
                Locale.ROOT,
                "%s: %s in %d ticks (%.2f s; human best %d ticks, %.1f s), %s; human fewest errors %d; %s; splits %s;"
                        + " recording %s",
                course,
                run.end(),
                run.ticks(),
                run.seconds(),
                ticks(),
                seconds,
                score,
                errors,
                run.fps(),
                run.splits(),
                run.recording());
        LOG.info("[course] {}", summary);
        game.check(run.arrived(), "the bot reaches the goal: " + summary);
        game.check(run.arrived() && run.ticks() < ticks(), "the bot beats the human's best time: " + summary);
        game.check(score.errors() <= errors, "the bot makes no more errors than the human's fewest: " + summary);
    }
}
