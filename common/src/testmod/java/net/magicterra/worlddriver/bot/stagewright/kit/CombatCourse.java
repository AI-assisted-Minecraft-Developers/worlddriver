package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A combat course of {@code scripts/bench/courses/} ({@code kind: combat}), run by the bot as {@code bench.py run
 * --driver bot} runs it: the wave summoned on "Go!", tagged and kept from despawning, one {@code mc.bot.combat} engage
 * order with {@code force: true} once the client sees the wave, sent again up to five times while tagged mobs are left,
 * and the clock stopping on the sample that sees the last one dead. A drawn wave is won when none is left within 32
 * blocks of the player. The course runs at noon on normal difficulty, as its {@code world} commands set.
 *
 * @param name the course's id
 * @param start the feet at the start
 * @param yaw the way the player faces at the start
 * @param kit the course's kit
 * @param arenaFrom the inside of the arena, one corner; everything but players in it is cleared before a run
 * @param arenaTo the other corner
 * @param drawn whether the wave was drawn from a pool by a seed, and so is won when none is left in range
 * @param maxTicks the bench's {@code maxSeconds}, in ticks
 */
public record CombatCourse(
        String name,
        Vec3 start,
        float yaw,
        CourseKit kit,
        BlockPos arenaFrom,
        BlockPos arenaTo,
        boolean drawn,
        int maxTicks) {

    /** The tag on every mob of the wave. */
    public static final String MOB_TAG = "bench_mob";

    /** A slow spell with a mob this close is fighting in place. */
    private static final double FIGHT_REACH = 4.0;

    /** A drawn wave is cleared when no mob is left this close: the bot's threat scan radius. */
    private static final double WORK_RANGE = 32.0;

    private static final int REISSUE_MAX = 5;

    /** An engage order sent before the client has the new mobs finds the area clear; the bench waited this long. */
    private static final int SEEN_WITHIN = 3 * CourseRecorder.TICKS_PER_SECOND;

    private static final int ACTION_EVERY = CourseRecorder.TICKS_PER_SECOND / 2;

    /** The combat courses' {@code time set 6000}. */
    private static final long NOON = 6000;

    /** One mob of a wave: its type, where it is summoned, and the data the summon adds. */
    public record Mob(String type, Vec3 pos, String nbt) {
        /** A mob summoned with no data of its own. */
        public static Mob of(String type, double x, double y, double z) {
            return new Mob(type, new Vec3(x, y, z), "");
        }

        /** A skeleton with its bow: a mob summoned with data gets no random equipment. */
        public static Mob archer(double x, double y, double z) {
            return new Mob("minecraft:skeleton", new Vec3(x, y, z), "HandItems:[{id:\"minecraft:bow\",count:1},{}]");
        }
    }

    /**
     * The combat kit: an iron sword and sixteen cooked beef in the hotbar, full iron armour, and a shield in the off
     * hand when {@code shield}.
     */
    public static CourseKit kit(boolean shield) {
        CourseKit k = CourseKit.of(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.COOKED_BEEF, 16))
                .withIronArmour();
        return shield ? k.withOffhand(new ItemStack(Items.SHIELD)) : k;
    }

    /** The same course moved by {@code by}, with its terrain. */
    public CombatCourse shifted(Vec3i by) {
        return new CombatCourse(
                name,
                start.add(Vec3.atLowerCornerOf(by)),
                yaw,
                kit,
                arenaFrom.offset(by),
                arenaTo.offset(by),
                drawn,
                maxTicks);
    }

    /** {@code wave} moved by {@code by}. */
    public static List<Mob> shifted(List<Mob> wave, Vec3i by) {
        return wave.stream()
                .map(m -> new Mob(m.type(), m.pos().add(Vec3.atLowerCornerOf(by)), m.nbt()))
                .toList();
    }

    /** This course's errors in {@code run}: stalls (a spell with a mob in reach is fighting), bumps and damage. */
    public CourseErrors.Score score(CourseRun run) {
        return CourseErrors.score(run.samples(), CourseErrors.Rules.combat());
    }

    /**
     * Run each of {@code waves} once with the bot, calling {@code reset} before each run but the first to put the
     * terrain back, as the bench's {@code terrain --reset} between runs. The course's day and difficulty hold for the
     * whole test. Only from the test's own thread, in a class holding the locks {@link CourseWorld#bench} needs.
     */
    public List<CourseRun> runBot(Game game, Client client, Player player, List<List<Mob>> waves, Runnable reset) {
        world(game);
        CourseBody body = CourseBody.client(game, client, player);
        List<CourseRun> runs = new ArrayList<>();
        for (int i = 0; i < waves.size(); i++) {
            if (i > 0) {
                reset.run();
            }
            stage(game, body, MOB_TAG);
            runs.add(begin(game, body, waves.get(i), MOB_TAG, e -> true).run(game));
        }
        clearArena(game, MOB_TAG);
        return runs;
    }

    /** The course's day and difficulty, for the rest of the test. */
    public static void world(Game game) {
        CourseWorld.bench(game, NOON);
        CourseWorld.difficulty(game, Difficulty.NORMAL);
    }

    /** Stand {@code body} at the start with the kit, and clear the arena of everything but players. */
    public void stage(Game game, CourseBody body, String tag) {
        body.stage(game, kit, start, yaw, Map.of());
        clearArena(game, tag);
    }

    /**
     * Start the clock and summon {@code wave}, its mobs tagged {@code tag}, then order the fight on {@code body} once
     * its bot sees the wave or three seconds have passed, as the bench waited; the run is sampled from that order on,
     * keeping the block events {@code own} says are its own. Only from the test's own thread.
     */
    public CourseAttempt begin(
            Game game, CourseBody body, List<Mob> wave, String tag, Predicate<CourseRecorder.BlockEvent> own) {
        CourseRecorder rec = new CourseRecorder(game, body).ownEvents(own);
        rec.start();
        // The wave appears on "Go!", not before, so that neither driver can start on it early.
        game.runOnServer(s -> wave.forEach(m -> command(s, summon(m, tag))));
        String[] action = {null};
        List<Integer> splits = new ArrayList<>();
        int[] reissues = {0};
        int[] inRange = {wave.size()};
        CourseLoop.Lane lane = new CourseLoop.Lane(
                () -> {
                    if (action[0] == null) {
                        if (rec.elapsed() < SEEN_WITHIN && !seesThreats(body)) {
                            return null;
                        }
                        action[0] = engage(body);
                    }
                    WaveReading n = mobs(body, tag);
                    inRange[0] = n.inRange();
                    return rec.sample(splits.size(), n.alive(), n.near(), n.mobs());
                },
                maxTicks,
                s -> {
                    while (splits.size() < wave.size() - s.alive()) {
                        splits.add(s.tick());
                    }
                    if (s.alive() == 0 || (drawn && inRange[0] == 0 && !splits.isEmpty())) {
                        return "cleared";
                    }
                    return s.health() <= 0 ? "died" : null;
                },
                () -> {
                    if (action[0] == null) {
                        return null;
                    }
                    Map<?, ?> a = ByName.read(body.registry(), action[0]);
                    if ("running".equals(a.get("state"))) {
                        return null;
                    }
                    if (reissues[0] >= REISSUE_MAX) {
                        return "bot-stopped: " + a.get("state") + " " + a.get("reason") + " " + a.get("message");
                    }
                    reissues[0]++;
                    action[0] = engage(body);
                    return null;
                },
                0,
                ACTION_EVERY);
        return new CourseAttempt(body, lane, end -> {
            body.cancel();
            rec.finish();
            final int ticks = "cleared".equals(end.end()) ? splits.getLast() : end.ticks();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("kind", "combat");
            meta.put("mobs", wave.size());
            meta.put("reissues", reissues[0]);
            return new CourseRun(
                    end.end(), ticks, List.copyOf(splits), rec.samples(), rec.write(name, meta, end.end(), ticks));
        });
    }

    /**
     * The wave's living mobs anywhere, those within reach of the body, those within the threat scan's radius, and where
     * each living one is.
     */
    private record WaveReading(int alive, int near, int inRange, List<CourseRecorder.MobAt> mobs) {}

    /** The wave as the server has it now. A killed mob stays through its death animation, so dying counts as dead. */
    private static WaveReading mobs(CourseBody body, String tag) {
        return body.onServer(p -> {
            int alive = 0;
            int near = 0;
            int inRange = 0;
            List<CourseRecorder.MobAt> at = new ArrayList<>();
            for (Entity e : p.serverLevel().getAllEntities()) {
                if (!e.getTags().contains(tag) || !(e instanceof LivingEntity l) || l.isDeadOrDying()) {
                    continue;
                }
                double d = e.distanceTo(p);
                alive++;
                near += d <= FIGHT_REACH ? 1 : 0;
                inRange += d <= WORK_RANGE ? 1 : 0;
                float swell = e instanceof Creeper c ? c.getSwelling(1f) : 0f;
                at.add(new CourseRecorder.MobAt(
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), e.getX(), e.getZ(), swell));
            }
            return new WaveReading(alive, near, inRange, at);
        });
    }

    /**
     * No wave left from an earlier run, none of its drops, experience or arrows, and no other mob in the arena: a cave
     * can hold a bat or a mob that spawned before spawning was turned off. Removed outright rather than killed, so
     * nothing drops and there is no death animation to wait out.
     */
    public void clearArena(Game game, String tag) {
        AABB box = AABB.encapsulatingFullBlocks(arenaFrom, arenaTo);
        game.runOnServer(s -> {
            List<Entity> gone = new ArrayList<>();
            for (Entity e : s.overworld().getAllEntities()) {
                if (!(e instanceof ServerPlayer) && (e.getTags().contains(tag) || box.intersects(e.getBoundingBox()))) {
                    gone.add(e);
                }
            }
            gone.forEach(Entity::discard);
        });
        game.ticks(1);
    }

    private static String summon(Mob m, String tag) {
        String extra = m.nbt().isEmpty() ? "" : "," + m.nbt();
        return String.format(
                java.util.Locale.ROOT,
                "summon %s %.1f %.1f %.1f {Tags:[\"%s\"],PersistenceRequired:1b%s}",
                m.type(),
                m.pos().x,
                m.pos().y,
                m.pos().z,
                tag,
                extra);
    }

    private static void command(MinecraftServer server, String command) {
        server.getCommands()
                .performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static boolean seesThreats(CourseBody body) {
        return body.call("mc.observe.threats", Map.of()).get("threats") instanceof List<?> l && !l.isEmpty();
    }

    private static String engage(CourseBody body) {
        return ByName.actionId(body.call("mc.bot.combat", Map.of("mode", "engage", "force", true)));
    }
}
