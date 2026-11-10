package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/**
 * A task course of {@code scripts/bench/courses/} — mining, clearing, building or farming — run by the bot on the real
 * client's player as {@code bench.py run --driver bot} runs it: the course's kit, the shipped settings plus its
 * {@code botSettings}, one order of the task's verb on "Go!", and the clock running to the sample that sees the task
 * done. What the task is, how far along a sample finds it, and its wrong blocks are the {@link CourseTask}'s.
 *
 * @param start the feet at the start
 * @param yaw the way the player faces at the start
 * @param kit what the player is given
 * @param task the work, and how it is judged
 * @param botSettings the course's own settings for the bot's runs
 * @param maxTicks the bench's {@code maxSeconds}, in ticks
 */
public record TaskCourse(
        Vec3 start, float yaw, CourseKit kit, CourseTask task, Map<String, Object> botSettings, int maxTicks) {

    /** The bench read a clearing, building or farming order's state every half second. */
    private static final int ACTION_EVERY = CourseRecorder.TICKS_PER_SECOND / 2;

    /** A mining run asked {@code mc.bot.status} as a route run does: once a second from the second second on. */
    private static final int STATUS_EVERY = CourseRecorder.TICKS_PER_SECOND;

    private static final int STATUS_FROM = 2 * CourseRecorder.TICKS_PER_SECOND;

    /** A clearing, building or farming run's splits: one at each quarter of its cells done. */
    private static final int QUARTERS = 4;

    /** The same course moved by {@code by}, with its terrain. */
    public TaskCourse shifted(Vec3i by) {
        return new TaskCourse(start.add(Vec3.atLowerCornerOf(by)), yaw, kit, task.shifted(by), botSettings, maxTicks);
    }

    /** The course's id. */
    public String name() {
        return task.name();
    }

    /**
     * This course's errors in {@code run}: stalls, bumps and damage, no backtracks, and the task's wrong blocks.
     * Building and farming stand still to place as mining does to break, so a placement is work there too.
     */
    public CourseErrors.Score score(CourseRun run) {
        CourseErrors.Rules rules = task.placesAreWork()
                ? CourseErrors.Rules.roaming("block.break", "block.place")
                : CourseErrors.Rules.roaming("block.break");
        return CourseErrors.score(run.samples(), rules).withWrong(task.wrong(run.samples()));
    }

    /**
     * Run the course once with the bot: stage the player, order the task, and record it until the task is done, the bot
     * stops, or {@code maxTicks}. Only from the test's own thread, in a class holding the locks
     * {@link CourseWorld#bench} needs.
     */
    public CourseRun runBot(Game game, Client client, Player player) {
        final ClientRig rig = kit.stage(game, client, player, start, yaw, botSettings);
        CourseRecorder rec = new CourseRecorder(game, client);
        rec.start();
        Map<String, Object> reply = Calls.byName(client.registry(), task.verb(), task.order());
        if (!"accepted".equals(reply.get("outcome"))) {
            rig.cancel();
            return CourseRun.refused(reply);
        }
        String actionId = ByName.actionId(reply);
        int target = task.target();
        List<Integer> marks = task.splitsEachCell() ? List.of() : quarterMarks(target);
        List<Integer> splits = new ArrayList<>();
        IntSupplier progress = () -> task.progress(game, player);
        final CourseLoop.End end = CourseLoop.run(
                game,
                rec,
                maxTicks,
                splits::size,
                s -> {
                    task.watch(game, s);
                    int done = progress.getAsInt();
                    int due = task.splitsEachCell()
                            ? Math.min(done, target)
                            : (int) marks.stream().filter(m -> done >= m).count();
                    while (splits.size() < due) {
                        splits.add(s.tick());
                    }
                    return done >= target ? task.doneName() : null;
                },
                () -> stopped(rig, client, actionId, progress, target),
                task.splitsEachCell() ? STATUS_FROM : ACTION_EVERY,
                task.splitsEachCell() ? STATUS_EVERY : ACTION_EVERY);
        rig.cancel();
        rec.finish();
        task.finish(game);
        // The run's time is the sample that saw the task done, the last split.
        int ticks = end.end().equals(task.doneName()) ? splits.getLast() : end.ticks();
        Map<String, Object> meta = new LinkedHashMap<>(task.meta());
        meta.put("start", List.of(start.x, start.y, start.z));
        return new CourseRun(
                end.end(), ticks, List.copyOf(splits), rec.samples(), rec.write(name(), meta, end.end(), ticks));
    }

    /**
     * Whether the bot has given up. A mining order is watched as a walk is, by the bot's own status; a clearing,
     * building or farming order by its action's state, which counts only when it ended with the task not done.
     */
    private String stopped(ClientRig rig, Client client, String actionId, IntSupplier progress, int target) {
        if (task.splitsEachCell()) {
            var status = rig.status();
            return status.busy() ? null : "bot-stopped: " + status.lastProcessEnd();
        }
        Map<?, ?> action = ByName.read(client.registry(), actionId);
        if ("running".equals(action.get("state")) || progress.getAsInt() >= target) {
            return null;
        }
        return "bot-stopped: " + action.get("state") + " " + action.get("reason") + " " + action.get("message");
    }

    /** The counts at which a run takes its splits: each quarter of its cells, rounded up. */
    private static List<Integer> quarterMarks(int cells) {
        List<Integer> marks = new ArrayList<>();
        for (int k = 1; k <= QUARTERS; k++) {
            marks.add((cells * k + QUARTERS - 1) / QUARTERS);
        }
        return marks;
    }
}
