package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.Sample;

/**
 * The sampling loop of {@code bench.py run}, shared by every kind of course: one sample a tick until the course says
 * the run is over, the bot is found to have stopped, or the course's time runs out. What ends a run is the course's.
 */
public final class CourseLoop {

    private CourseLoop() {}

    /** What a course reads from each sample: the end of the run, or null to go on. Called on every sample. */
    @FunctionalInterface
    public interface Check {
        /** The run's end on this sample, or null. */
        String after(Sample s);
    }

    /** Whether the bot has stopped: the run's end, or null while it works. Asked every {@code every} ticks. */
    @FunctionalInterface
    public interface Stopped {
        /** The run's end because the bot stopped, or null. */
        String now();
    }

    /**
     * How the loop ended: the course's end, {@code max-s} when the time ran out, {@code no-samples} when it ran out
     * with no sample for a while, and the tick it ended on.
     */
    public record End(String end, int ticks) {}

    /**
     * Sample until {@code check} ends the run, {@code stopped} does, or {@code maxTicks} pass. {@code checkpoint} names
     * the split each sample is taken before, as the bench's {@code cp}. {@code stopped} is asked from tick {@code from}
     * on, every {@code every} ticks.
     */
    public static End run(
            Game game,
            CourseRecorder rec,
            int maxTicks,
            IntSupplier checkpoint,
            Check check,
            Stopped stopped,
            int from,
            int every) {
        return run(game, () -> rec.sample(checkpoint.getAsInt()), maxTicks, check, stopped, from, every);
    }

    /**
     * {@link #run(Game, CourseRecorder, int, IntSupplier, Check, Stopped, int, int)} with samples from {@code sampler}.
     */
    public static End run(
            Game game, Supplier<Sample> sampler, int maxTicks, Check check, Stopped stopped, int from, int every) {
        int ticks = 0;
        // Game ticks waited, for the time limit: ticks only moves on a sample, and a client that lost its player
        // returns none, which would keep the loop waiting for ever.
        int waited = 0;
        int lastSampled = 0; // waited when the last sample came
        int nextStatus = from;
        while (true) {
            Sample s = sampler.get();
            if (s != null) {
                ticks = s.tick();
                lastSampled = waited;
                String end = check.after(s);
                if (end != null) {
                    return new End(end, ticks);
                }
            }
            // The clock for the bot's status and the time limit runs on without samples, so neither stalls with them.
            int now = Math.max(ticks, waited);
            if (now >= nextStatus) {
                String end = stopped.now();
                if (end != null) {
                    return new End(end, now);
                }
                nextStatus = now + every;
            }
            if (now > maxTicks) {
                // Out of time with the samples long gone is a client that lost its player, not a slow bot.
                return new End(waited - lastSampled > every ? "no-samples" : "max-s", now);
            }
            game.ticks(1);
            waited++;
        }
    }
}
