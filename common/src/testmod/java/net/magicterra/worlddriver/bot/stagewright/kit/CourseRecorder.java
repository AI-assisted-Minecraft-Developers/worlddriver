package net.magicterra.worlddriver.bot.stagewright.kit;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.api.methods.event.EventBlockingApi;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * A run of a benchmark course, recorded as {@code scripts/bench/bench.py} records one: the body read once per sample
 * ({@link CourseBody#read}: the real client's player through {@code mc.client.player}, the client's own physics, which
 * the server's copy of the player does not report, {@code horizontalCollision} above all; a server bot's player on the
 * server, where its physics runs), and the blocks placed and broken read from the server's event ring every half
 * second. {@link CourseErrors} scores the samples.
 *
 * <p>Time is the server's tick count from {@link #start}, not the number of samples or the wall clock: a sample is a
 * hop to the client and one to the server, and a slow hop must not stretch the run. The bench timed its runs by the
 * wall clock on a client whose integrated server held 20 ticks a second, so a second of the bench is twenty ticks here.
 */
public final class CourseRecorder {

    /** Ticks in a second of the bench's clock. */
    public static final int TICKS_PER_SECOND = 20;

    /** The bench read the block events every half second, and gave them to the sample that read them. */
    private static final int EVENT_EVERY = TICKS_PER_SECOND / 2;

    /** The page size {@code SinceParams.after} asks for. */
    private static final int EVENT_PAGE = 256;

    /** Half the body's height gliding and upright: its middle above the feet. */
    private static final double FLY_HALF_HEIGHT = 0.3;

    private static final double STAND_HALF_HEIGHT = 0.9;

    /** One block placed or broken: the event's type, where, and the block id its payload names. */
    public record BlockEvent(String type, BlockPos pos, String block) {
        /** The payload's block id without a state suffix. */
        public String blockId() {
            int bracket = block.indexOf('[');
            return bracket < 0 ? block : block.substring(0, bracket);
        }
    }

    /**
     * One living mob of a combat course's wave at a sample, as the server has it: its type's id, its feet, and how far
     * a creeper's fuse has burnt ({@code getSwelling}: 0 unlit, past 1 at the blast; 0 for any other mob), and its
     * health. Two bodies' runs on one wave line up by these.
     */
    public record MobAt(String type, double x, double z, float swell, float health) {}

    /**
     * The body's guard as the server has it, which is what a hit is judged by: the ticks its shield has been raised (0
     * lowered; it blocks from 5) and how far its swing has recharged ({@code getAttackStrengthScale}: 0 just after a
     * swing, 1 full; a swing lands 0.2 + 0.8 of its square of the damage). Read with the wave, in the same server call,
     * not with the client's reading of the body. {@link #NONE} off a combat course.
     */
    public record Guard(int shieldTicks, float strength) {

        /** No guard read. */
        public static final Guard NONE = new Guard(-1, -1f);
    }

    /**
     * The client's player at one sample: {@code tick} after {@link #start}, the feet, the client's body flags and
     * health, whether it glides ({@code FALL_FLYING}), the client's frame rate, the checkpoint that was next when it
     * was taken, the block events read with it, and on a combat course the wave's living mobs and those within reach
     * (-1 elsewhere), where each of them is (empty elsewhere) and the body's guard ({@link Guard#NONE} elsewhere).
     */
    public record Sample(
            int tick,
            double x,
            double y,
            double z,
            boolean onGround,
            boolean inWater,
            boolean collided,
            double health,
            boolean flying,
            int fps,
            int checkpoint,
            List<BlockEvent> events,
            int alive,
            int near,
            List<MobAt> wave,
            Guard guard) {

        /** {@link #tick} in the bench's seconds. */
        public double seconds() {
            return (double) tick / TICKS_PER_SECOND;
        }

        /** This sample with {@code more} events after its own. */
        Sample withEvents(List<BlockEvent> more) {
            List<BlockEvent> all = new ArrayList<>(events);
            all.addAll(more);
            return new Sample(
                    tick,
                    x,
                    y,
                    z,
                    onGround,
                    inWater,
                    collided,
                    health,
                    flying,
                    fps,
                    checkpoint,
                    List.copyOf(all),
                    alive,
                    near,
                    wave,
                    guard);
        }

        /** The middle of the body: a gliding body is 0.6 tall, an upright one 1.8. */
        public Vec3 centre() {
            return new Vec3(x, y + (flying ? FLY_HALF_HEIGHT : STAND_HALF_HEIGHT), z);
        }
    }

    private final Game game;

    private final CourseBody body;

    private final EventBlockingApi events;

    private final List<Sample> samples = new ArrayList<>();

    /** Which of the server's block events are this body's: every one, unless another body works the same world. */
    private Predicate<BlockEvent> own = e -> true;

    private long startTick;

    private long cursor;

    private int nextEvents;

    /** A recorder of the real client's player in {@code game}; nothing is read until {@link #start}. */
    public CourseRecorder(Game game, Client client, Player player) {
        this(game, CourseBody.client(game, client, player));
    }

    /** A recorder of {@code body} in {@code game}; nothing is read until {@link #start}. */
    public CourseRecorder(Game game, CourseBody body) {
        this.game = game;
        this.body = body;
        this.events = game.api(EventBlockingApi::of);
    }

    /**
     * Keep only the block events {@code test} says are this body's. The server's event ring names no player, so two
     * bodies working one world each read every event and keep their own by where it was.
     */
    public CourseRecorder ownEvents(Predicate<BlockEvent> test) {
        this.own = test;
        return this;
    }

    /** Start the clock now, the bench's "Go!", and read the block events from here on. */
    public void start() {
        cursor = events.cursor(new EventContracts.CursorParams()).cursor();
        startTick = serverTick();
        nextEvents = 0;
    }

    /** Ticks since {@link #start}. */
    public int elapsed() {
        return (int) (serverTick() - startTick);
    }

    /**
     * Read the client's player once, with {@code checkpoint} the one next before this sample; null while the client has
     * no player in a world. The block events since the last read come with it every half second.
     */
    public Sample sample(int checkpoint) {
        return sample(checkpoint, -1, -1, List.of(), Guard.NONE);
    }

    /**
     * {@link #sample(int)} on a combat course, with the wave's living mobs, those in reach, where each is, and the
     * body's guard.
     */
    public Sample sample(int checkpoint, int alive, int near, List<MobAt> wave, Guard guard) {
        Optional<CourseBody.Reading> read = body.read();
        int tick = elapsed();
        if (read.isEmpty()) {
            return null;
        }
        List<BlockEvent> evs = List.of();
        if (tick >= nextEvents) {
            evs = readEvents();
            nextEvents = tick + EVENT_EVERY;
        }
        CourseBody.Reading r = read.get();
        Sample s = new Sample(
                tick,
                r.x(),
                r.y(),
                r.z(),
                r.onGround(),
                r.inWater(),
                r.collided(),
                r.health(),
                r.flying(),
                r.fps(),
                checkpoint,
                evs,
                alive,
                near,
                List.copyOf(wave),
                guard);
        samples.add(s);
        return s;
    }

    /**
     * Read the block events once more after the last sample and give them to it: the run ends on a sample, and what was
     * placed or broken since the last half-second read belongs to it.
     */
    public void finish() {
        List<BlockEvent> late = readEvents();
        if (late.isEmpty() || samples.isEmpty()) {
            return;
        }
        samples.add(samples.removeLast().withEvents(late));
    }

    /** Every sample so far, oldest first. */
    public List<Sample> samples() {
        return List.copyOf(samples);
    }

    /**
     * Write the run as a bench.py recording, so that {@code bench.py --out <dir> report <course>} reads it beside the
     * bench's own: a meta line ({@code meta} gives the course's fields), one line per sample, and the end line. The
     * file goes to {@code bench/<course>/runs/} under the game's working directory, where bench.py's default output
     * would put it. Answers the file written.
     */
    public Path write(String course, Map<String, Object> meta, String end, int ticks) {
        Map<String, Object> head = new LinkedHashMap<>(meta);
        head.put("meta", true);
        head.put("course", course);
        head.put("label", "scene");
        head.put("driver", "bot");
        head.put("body", body.label());
        head.put("hz", TICKS_PER_SECOND);
        final Gson gson = new Gson();
        final List<String> lines = new ArrayList<>();
        lines.add(gson.toJson(head));
        for (Sample s : samples) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", s.seconds());
            row.put("x", s.x());
            row.put("y", s.y());
            row.put("z", s.z());
            row.put("g", s.onGround());
            row.put("w", s.inWater());
            row.put("hc", s.collided());
            row.put("hp", s.health());
            row.put("cp", s.checkpoint());
            row.put("fps", s.fps());
            row.put("fly", s.flying());
            if (s.alive() >= 0) {
                row.put("n", s.alive());
                row.put("near", s.near());
            }
            if (!s.wave().isEmpty()) {
                row.put(
                        "mobs",
                        s.wave().stream()
                                .map(m -> List.of(
                                        m.type(),
                                        hundredths(m.x()),
                                        hundredths(m.z()),
                                        hundredths(m.swell()),
                                        hundredths(m.health())))
                                .toList());
            }
            if (s.guard().shieldTicks() >= 0) {
                row.put("shield", s.guard().shieldTicks());
                row.put("strength", hundredths(s.guard().strength()));
            }
            if (!s.events().isEmpty()) {
                row.put(
                        "ev",
                        s.events().stream()
                                .map(e -> List.of(
                                        e.type(),
                                        Map.of(
                                                "x", e.pos().getX(),
                                                "y", e.pos().getY(),
                                                "z", e.pos().getZ()),
                                        e.block()))
                                .toList());
            }
            lines.add(gson.toJson(row));
        }
        Map<String, Object> tail = new LinkedHashMap<>();
        tail.put("end", end);
        tail.put("t", (double) ticks / TICKS_PER_SECOND);
        lines.add(gson.toJson(tail));
        Path dir = Path.of("bench", course, "runs");
        // A server body's run ends on the same second as the client's it races, so its name says which it is.
        String suffix = body instanceof CourseBody.ClientBody ? "" : "-" + body.label();
        Path file = dir.resolve(String.format(
                Locale.ROOT,
                "%1$tY%1$tm%1$td-%1$tH%1$tM%1$tS-bot-scene%2$s.jsonl",
                System.currentTimeMillis(),
                suffix));
        try {
            Files.createDirectories(dir);
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the recording " + file.toAbsolutePath(), e);
        }
        return file.toAbsolutePath();
    }

    private List<BlockEvent> readEvents() {
        List<BlockEvent> out = new ArrayList<>();
        while (true) {
            EventContracts.Page page =
                    events.eventsSince(EventContracts.SinceParams.after(cursor, "block.place", "block.break"));
            for (EventContracts.Event e : page.events()) {
                if (e.pos().isEmpty()) {
                    continue;
                }
                String block = e.data()
                        .map(d -> d.json().isJsonPrimitive()
                                ? d.json().getAsString()
                                : d.json().toString())
                        .orElse("");
                BlockEvent ev = new BlockEvent(
                        e.type(),
                        new BlockPos(
                                e.pos().get().x(),
                                e.pos().get().y(),
                                e.pos().get().z()),
                        block);
                if (own.test(ev)) {
                    out.add(ev);
                }
            }
            cursor = page.cursor();
            // A full page may have more behind it.
            if (page.events().size() < EVENT_PAGE) {
                return out;
            }
        }
    }

    /**
     * Drop the ticks a stall left owed, which vanilla would otherwise run back to back into a course's clock: a
     * sprinted tick is due at once, so the next is due a tick from then. Only from a test thread holding
     * {@code Locks.TICK_RATE}.
     */
    public static void forgiveOwedTicks(Game game) {
        game.runOnServer(s -> s.tickRateManager().requestGameToSprint(1));
        for (int waited = 0; game.onServer(s -> s.tickRateManager().isSprinting()); waited++) {
            // A one-tick sprint ends on the tick after it; one that does not is on a server that is not ticking.
            if (waited >= TICKS_PER_SECOND) {
                throw new IllegalStateException("a one-tick sprint was still running a second's ticks later");
            }
            game.ticks(1);
        }
    }

    private long serverTick() {
        return game.onServer(MinecraftServer::getTickCount);
    }

    private static double hundredths(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
