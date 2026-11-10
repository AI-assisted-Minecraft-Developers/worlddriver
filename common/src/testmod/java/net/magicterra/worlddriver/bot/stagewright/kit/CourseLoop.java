package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.function.IntSupplier;
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

    /** How the loop ended: the course's end, {@code max-s} when the time ran out, and the tick of the last sample. */
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
        int ticks = 0;
        int nextStatus = from;
        while (true) {
            Sample s = rec.sample(checkpoint.getAsInt());
            if (s != null) {
                ticks = s.tick();
                String end = check.after(s);
                if (end != null) {
                    return new End(end, ticks);
                }
            }
            if (ticks >= nextStatus) {
                String end = stopped.now();
                if (end != null) {
                    return new End(end, ticks);
                }
                nextStatus = ticks + every;
            }
            if (ticks > maxTicks) {
                return new End("max-s", ticks);
            }
            game.ticks(1);
        }
    }
}
