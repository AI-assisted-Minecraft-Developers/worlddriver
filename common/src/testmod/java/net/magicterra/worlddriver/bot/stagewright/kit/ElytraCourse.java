package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.Sample;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;

/**
 * An elytra course of {@code scripts/bench/courses/} ({@code kind: elytra}), run by the bot as {@code bench.py run
 * --driver bot} runs it: an elytra and sixteen rockets of flight duration 1, one {@code mc.bot.elytraFly} order to the
 * pad's middle with the rings' middles as {@code via}, sent again with the rings still ahead when it ends before the
 * landing, and the clock stopping at the touchdown on the pad. The rings, misses, landing and errors are bench.py's
 * {@code ring_crossing}, {@code ring_step}, {@code on_pad} and {@code flying_contacts}.
 *
 * @param name the course's id
 * @param start the feet on the tower
 * @param yaw the way the player faces at the start
 * @param rings flown through in order
 * @param goal the middle of the pad, which the bot is sent to
 * @param padFrom the pad's top layer, one corner
 * @param padTo the other corner
 * @param floorY a body below it has fallen
 * @param maxTicks the bench's {@code maxSeconds}, in ticks
 */
public record ElytraCourse(
        String name,
        Vec3 start,
        float yaw,
        List<Ring> rings,
        Vec3 goal,
        BlockPos padFrom,
        BlockPos padTo,
        double floorY,
        int maxTicks) {

    /** A crossing outside the opening but this close to its middle is a miss; further out the flyer was not trying. */
    private static final double RING_REACH = 32.0;

    /** After a miss, the ring counts as missed again only once the body has been back this far in front of it. */
    private static final double RING_REARM = 2.0;

    /** Samples kept after the touchdown, so that the damage of a hard landing is read. */
    private static final int LAND_SETTLE_TICKS = CourseRecorder.TICKS_PER_SECOND;

    /** The bench re-sent an order only once it had been over this long. */
    private static final int REISSUE_AFTER = 30;

    private static final int REISSUE_MAX = 5;

    /** The bench asked the order's state every quarter second. */
    private static final int ACTION_EVERY = CourseRecorder.TICKS_PER_SECOND / 4;

    private static final int ROCKETS = 16;

    /**
     * An upright ring: {@code pos} the middle of its opening, {@code normal} the level way through it, {@code half}
     * half the opening's width and height.
     */
    public record Ring(Vec3 pos, Vec3 normal, double half) {}

    /** What one sample's move did against the next ring. */
    private record Step(boolean passed, boolean missed, boolean armed) {}

    /** The same course moved by {@code by}, with its terrain. */
    public ElytraCourse shifted(Vec3i by) {
        Vec3 d = Vec3.atLowerCornerOf(by);
        return new ElytraCourse(
                name,
                start.add(d),
                yaw,
                rings.stream()
                        .map(r -> new Ring(r.pos().add(d), r.normal(), r.half()))
                        .toList(),
                goal.add(d),
                padFrom.offset(by),
                padTo.offset(by),
                floorY + by.getY(),
                maxTicks);
    }

    /** The kit: an elytra on the chest and sixteen rockets of flight duration 1 in the first slot, no armour. */
    public static CourseKit kit() {
        ItemStack rockets = new ItemStack(Items.FIREWORK_ROCKET, ROCKETS);
        rockets.set(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
        return new CourseKit(
                List.of(rockets),
                ItemStack.EMPTY,
                List.of(ItemStack.EMPTY, new ItemStack(Items.ELYTRA), ItemStack.EMPTY, ItemStack.EMPTY),
                false);
    }

    /**
     * This course's errors in {@code run}: the wall contacts in flight up to the touchdown, the rings missed and the
     * damage, a hard landing's included. Nothing stands still in the air, so there are no stalls and no grazes.
     */
    public CourseErrors.Score score(CourseRun run) {
        List<Sample> rows = run.samples();
        int bumps = 0;
        boolean touching = false;
        for (Sample r : rows) {
            boolean hit = r.collided() && (r.flying() || !r.onGround()) && r.tick() <= run.ticks();
            bumps += hit && !touching ? 1 : 0;
            touching = hit;
        }
        int misses = 0;
        boolean armed = true;
        int hurts = 0;
        double hurt = 0;
        for (int i = 0; i + 1 < rows.size(); i++) {
            Sample a = rows.get(i);
            Sample b = rows.get(i + 1);
            if (b.checkpoint() < rings.size()) {
                Step st = step(rings.get(b.checkpoint()), a, b, armed);
                misses += st.missed() ? 1 : 0;
                armed = st.armed();
            }
            if (b.health() < a.health()) {
                hurts++;
                hurt += a.health() - b.health();
            }
        }
        return new CourseErrors.Score(-1, 0, bumps, -1, -1, hurts, hurt, 0, 0, 0, 0, -1, misses);
    }

    /**
     * Run the course once with the bot. Only from the test's own thread, in a class holding the locks
     * {@link CourseWorld#bench} needs.
     */
    public CourseRun runBot(Game game, Client client, Player player) {
        CourseWorld.bench(game);
        CourseBody body = CourseBody.client(game, client, player);
        stage(game, body);
        return begin(game, body, e -> true).run(game);
    }

    /** Stand {@code body} on the tower with the elytra kit. */
    public void stage(Game game, CourseBody body) {
        body.stage(game, kit(), start, yaw, Map.of());
    }

    /**
     * Start the clock and order the flight on {@code body}, keeping the block events {@code own} says are its own. Only
     * from the test's own thread.
     */
    public CourseAttempt begin(Game game, CourseBody body, Predicate<CourseRecorder.BlockEvent> own) {
        CourseRecorder rec = new CourseRecorder(game, body).ownEvents(own);
        rec.start();
        String[] action = {order(body, 0)};
        if (action[0] == null) {
            body.cancel();
            return CourseAttempt.refused(body, "elytraFly");
        }
        List<Integer> splits = new ArrayList<>();
        int[] landedAt = {-1};
        int[] endedAt = {-1};
        int[] reissues = {0};
        boolean[] armed = {true};
        Sample[] prev = {null};
        CourseLoop.Lane lane = new CourseLoop.Lane(
                () -> rec.sample(splits.size()),
                maxTicks,
                s -> {
                    String e = afterSample(s, prev[0], splits, landedAt, armed);
                    prev[0] = s;
                    return e;
                },
                () -> {
                    if (landedAt[0] >= 0) {
                        return null;
                    }
                    Map<?, ?> a = ByName.read(body.registry(), action[0]);
                    int now = rec.elapsed();
                    if ("running".equals(a.get("state"))) {
                        endedAt[0] = -1;
                        return null;
                    }
                    if (endedAt[0] < 0) {
                        endedAt[0] = now;
                        return null;
                    }
                    if (now - endedAt[0] < REISSUE_AFTER) {
                        return null;
                    }
                    if (reissues[0] >= REISSUE_MAX) {
                        return "bot-stopped: " + a.get("state") + " " + a.get("reason") + " " + a.get("message");
                    }
                    reissues[0]++;
                    endedAt[0] = -1;
                    action[0] = order(body, splits.size());
                    return null;
                },
                0,
                ACTION_EVERY);
        return new CourseAttempt(body, lane, end -> {
            body.cancel();
            rec.finish();
            int ticks = "landed".equals(end.end()) ? landedAt[0] : end.ticks();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("kind", "elytra");
            meta.put("reissues", reissues[0]);
            return new CourseRun(
                    end.end(), ticks, List.copyOf(splits), rec.samples(), rec.write(name, meta, end.end(), ticks));
        });
    }

    /** The run's end on sample {@code s}, or null: bench.py's elytra branch of {@code run}. */
    private String afterSample(Sample s, Sample prev, List<Integer> splits, int[] landedAt, boolean[] armed) {
        if (landedAt[0] < 0 && splits.size() < rings.size() && prev != null) {
            Step st = step(rings.get(splits.size()), prev, s, armed[0]);
            armed[0] = st.armed();
            if (st.passed()) {
                splits.add(s.tick());
            }
        } else if (landedAt[0] < 0 && splits.size() == rings.size() && onPad(s)) {
            landedAt[0] = s.tick();
        }
        if (s.health() <= 0) {
            return "died";
        }
        if (landedAt[0] >= 0 && s.y() < padTo.getY() + 0.5) {
            return "overshot";
        }
        if (landedAt[0] >= 0 && s.tick() - landedAt[0] >= LAND_SETTLE_TICKS) {
            return "landed";
        }
        if (landedAt[0] < 0 && s.y() < floorY) {
            return "fell";
        }
        return null;
    }

    /** Send the order for the rings from {@code reached} on; answers its action id, or null when refused. */
    private String order(CourseBody body, int reached) {
        Map<String, Object> params = Map.of(
                "pos", RouteCourse.cell(goal),
                "via",
                        rings.subList(reached, rings.size()).stream()
                                .map(r -> RouteCourse.cell(r.pos()))
                                .toList());
        return ByName.actionId(body.call("mc.bot.elytraFly", params));
    }

    /** Whether a sample stands on the pad: on the ground, over its top layer. */
    private boolean onPad(Sample s) {
        return s.onGround()
                && Math.abs(s.y() - (padTo.getY() + 1)) < 0.5
                && s.x() >= Math.min(padFrom.getX(), padTo.getX()) - 0.3
                && s.x() <= Math.max(padFrom.getX(), padTo.getX()) + 1.3
                && s.z() >= Math.min(padFrom.getZ(), padTo.getZ()) - 0.3
                && s.z() <= Math.max(padFrom.getZ(), padTo.getZ()) + 1.3;
    }

    /**
     * The move from {@code a} to {@code b} against {@code ring}: passed when the body's middle crossed the ring's plane
     * inside the opening, either way; missed, while armed, when it crossed it going the ring's way outside the opening
     * within reach of the middle; armed again once the body is back in front of the plane.
     */
    private static Step step(Ring ring, Sample a, Sample b, boolean armed) {
        double norm = Math.hypot(ring.normal().x, ring.normal().z);
        double nx = ring.normal().x / norm;
        double nz = ring.normal().z / norm;
        Vec3 ca = a.centre();
        Vec3 cb = b.centre();
        double sa = (ca.x - ring.pos().x) * nx + (ca.z - ring.pos().z) * nz;
        double sb = (cb.x - ring.pos().x) * nx + (cb.z - ring.pos().z) * nz;
        boolean forward = sa < 0 && 0 <= sb;
        boolean backward = sb < 0 && 0 <= sa;
        String crossing = null;
        if (forward || backward) {
            double f = sa / (sa - sb);
            Vec3 p = ca.add(cb.subtract(ca).scale(f));
            double across = (p.x - ring.pos().x) * -nz + (p.z - ring.pos().z) * nx;
            double up = p.y - ring.pos().y;
            if (Math.abs(across) < ring.half() && Math.abs(up) < ring.half()) {
                crossing = "pass";
            } else if (forward && Math.abs(across) <= RING_REACH && Math.abs(up) <= RING_REACH) {
                crossing = "miss";
            }
        }
        if ("pass".equals(crossing)) {
            return new Step(true, false, true);
        }
        boolean missed = "miss".equals(crossing) && armed;
        boolean nowArmed = armed;
        if (missed) {
            nowArmed = false;
        } else if (!armed && -sb >= RING_REARM) {
            nowArmed = true;
        }
        return new Step(false, missed, nowArmed);
    }
}
