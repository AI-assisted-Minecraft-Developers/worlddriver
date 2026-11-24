package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.CombatCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.CombatCourse.Mob;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseErrors;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRun;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseTerrain;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseWorld;
import net.magicterra.worlddriver.bot.stagewright.kit.HumanBest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The bot against the human on the benchmark's combat courses ({@code docs/dev/benchmark.md}), on the real client's
 * player: a fixed wave in the built arena, placed in a plot, and waves drawn by seed in a natural cave of the world the
 * bench's Lab save was made from (seed 5471, the normal world), at the cave's own positions. A fight is partly luck, so
 * each test runs the bench's five bot runs and judges the median against the human's best run, as {@code bench.py
 * report} does for a combat course.
 */
@Scene("wd.combatCourses")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_RULES)
@ResourceLock(Locks.WORLD_CLOCK)
@ResourceLock(Locks.TICK_RATE)
class CombatCourseTest {

    /** The bench's five bot runs of each course. */
    private static final int RUNS = 5;

    private static final int FIVE_MINUTES = 300 * CourseRecorder.TICKS_PER_SECOND;

    static final int MARGIN = 2;

    private static final String ZOMBIE = "minecraft:zombie";

    private static final String SPIDER = "minecraft:spider";

    private static final String CREEPER = "minecraft:creeper";

    private static final String ZOMBIE_VILLAGER = "minecraft:zombie_villager";

    /** The minimum corner of the arena's setup commands in the Lab world: where its structure file starts. */
    static final BlockPos ARENA_CORNER = new BlockPos(987, 145, 1387);

    /** {@code scripts/bench/courses/combat.json}: the arena. */
    static final CombatCourse ARENA = new CombatCourse(
            "combat",
            new Vec3(1000.5, 150, 1410.5),
            180f,
            CombatCourse.kit(true),
            new BlockPos(989, 150, 1389),
            new BlockPos(1011, 155, 1411),
            false,
            FIVE_MINUTES);

    /** {@code combat}'s wave: three zombies, two skeletons with bows and a creeper. */
    static final List<Mob> ARENA_WAVE = List.of(
            Mob.of(ZOMBIE, 995.5, 150, 1394.5),
            Mob.of(ZOMBIE, 1000.5, 150, 1392.5),
            Mob.of(ZOMBIE, 1005.5, 150, 1394.5),
            Mob.archer(991.5, 150, 1390.5),
            Mob.archer(1009.5, 150, 1390.5),
            Mob.of(CREEPER, 1000.5, 150, 1390.5));

    /**
     * {@code combat}'s human baseline: the best of its three finished human runs, 16.704 s; the fewest errors of any, 3
     * (three hits taken), were the second run's.
     */
    static final HumanBest ARENA_HUMAN = new HumanBest("combat", 16.704, 3);

    static final BlockPos CAVE_FROM = new BlockPos(1030, -22, 1107);

    static final BlockPos CAVE_TO = new BlockPos(1090, 2, 1152);

    private static final Vec3 CAVE_START = new Vec3(1060.5, -16, 1130.5);

    /** {@code scripts/bench/courses/cave-combat-shield.json}. */
    static final CombatCourse CAVE_SHIELD = new CombatCourse(
            "cave-combat-shield",
            CAVE_START,
            45f,
            CombatCourse.kit(true).withNightVision(),
            CAVE_FROM,
            CAVE_TO,
            true,
            FIVE_MINUTES);

    /** {@code scripts/bench/courses/cave-combat-bare.json}: the same cave and waves, the off hand empty. */
    static final CombatCourse CAVE_BARE = new CombatCourse(
            "cave-combat-bare",
            CAVE_START,
            45f,
            CombatCourse.kit(false).withNightVision(),
            CAVE_FROM,
            CAVE_TO,
            true,
            FIVE_MINUTES);

    /**
     * The cave courses' waves for seeds 1 to 5, the seeds of the bench's five bot runs, as bench.py's {@code draw_wave}
     * draws them from the course file's pool and floor cells; both cave courses share them.
     */
    static final List<List<Mob>> CAVE_WAVES = List.of(
            List.of(
                    Mob.of(CREEPER, 1070.5, -9, 1115.5),
                    Mob.of(SPIDER, 1061.5, -13, 1141.5),
                    Mob.of(ZOMBIE, 1073.5, -13, 1124.5),
                    Mob.of(ZOMBIE, 1067.5, -13, 1140.5),
                    Mob.of(SPIDER, 1079.5, -13, 1128.5)),
            List.of(
                    Mob.of(ZOMBIE, 1075.5, -12, 1120.5),
                    Mob.archer(1070.5, -15, 1133.5),
                    Mob.of(ZOMBIE, 1074.5, -13, 1123.5),
                    Mob.of(SPIDER, 1052.5, -16, 1133.5),
                    Mob.of(ZOMBIE_VILLAGER, 1044.5, -16, 1142.5)),
            List.of(
                    Mob.of(CREEPER, 1042.5, -16, 1143.5),
                    Mob.of(ZOMBIE, 1059.5, -15, 1140.5),
                    Mob.of(ZOMBIE_VILLAGER, 1077.5, -12, 1137.5),
                    Mob.archer(1077.5, -12, 1123.5),
                    Mob.of(CREEPER, 1072.5, -15, 1135.5)),
            List.of(
                    Mob.of(ZOMBIE, 1078.5, -13, 1130.5),
                    Mob.of(SPIDER, 1061.5, -14, 1140.5),
                    Mob.archer(1071.5, -15, 1130.5),
                    Mob.of(ZOMBIE, 1041.5, -16, 1142.5),
                    Mob.of(ZOMBIE, 1047.5, -16, 1129.5)),
            List.of(
                    Mob.of(ZOMBIE, 1073.5, -14, 1129.5),
                    Mob.archer(1065.5, -11, 1120.5),
                    Mob.of(CREEPER, 1043.5, -16, 1131.5),
                    Mob.of(SPIDER, 1052.5, -15, 1127.5),
                    Mob.of(CREEPER, 1067.5, -13, 1123.5),
                    Mob.archer(1049.5, -16, 1128.5),
                    Mob.of(SPIDER, 1079.5, -13, 1130.5)));

    /**
     * {@code cave-combat-shield}'s human baseline: the best of its three finished human runs, 17.144 s on seed 1, which
     * is also the one with the fewest errors, 4.
     */
    static final HumanBest CAVE_SHIELD_HUMAN = new HumanBest("cave-combat-shield", 17.144, 4);

    /**
     * {@code cave-combat-bare}'s human baseline: the best of its three finished human runs, 20.719 s on seed 1; the
     * fewest errors of any, 5, were the seed-2 run's.
     */
    static final HumanBest CAVE_BARE_HUMAN = new HumanBest("cave-combat-bare", 20.719, 5);

    /**
     * Three zombies, two skeletons with bows and a creeper in a closed arena 23 blocks square, with an iron sword, a
     * shield and full iron armour: the bot's median run is faster than the human's best and makes no more errors than
     * the human's fewest.
     */
    @Test
    void arenaBeatsTheHumanBest(Game game, Client client, Player player, @Plot.Size(32) Plot plot) {
        CourseWorld.noRandomTicks(game);
        BlockPos shift = CourseTerrain.place(plot, ARENA.name(), ARENA_CORNER, MARGIN, 0, MARGIN);
        CombatCourse placed = ARENA.shifted(shift);
        List<Mob> wave = CombatCourse.shifted(ARENA_WAVE, shift);
        List<CourseRun> runs = placed.runBot(
                game,
                client,
                player,
                Collections.nCopies(RUNS, wave),
                () -> CourseTerrain.place(plot, ARENA.name(), ARENA_CORNER, MARGIN, 0, MARGIN));
        ARENA_HUMAN.judgeMedian(game, runs, runs.stream().map(placed::score).toList());
    }

    /** Five to seven mobs drawn from a cave's pool, in a natural deepslate passage, with a shield. */
    @Test
    void caveWithShieldBeatsTheHumanBest(Game game, Client client, Player player) {
        cave(game, client, player, CAVE_SHIELD, CAVE_SHIELD_HUMAN);
    }

    /** The same cave and waves without a shield. */
    @Test
    void caveBareBeatsTheHumanBest(Game game, Client client, Player player) {
        cave(game, client, player, CAVE_BARE, CAVE_BARE_HUMAN);
    }

    /**
     * Run a cave course at its own positions. The cave is generated first and snapshotted, and put back before each run
     * but the first and once more at the end, as the bench's {@code terrain --reset}: a creeper's blast changes the
     * cave, and the other cave course runs in it next.
     */
    private static void cave(Game game, Client client, Player player, CombatCourse course, HumanBest human) {
        generate(game);
        String id = "course-" + course.name();
        Calls.byName(
                game.registry(),
                "mc.world.snapshot",
                Map.of("id", id, "from", xyz(CAVE_FROM), "to", xyz(CAVE_TO), "blockEntities", true, "entities", false));
        Runnable reset = () -> Calls.byName(game.registry(), "mc.world.restore", Map.of("id", id));
        List<CourseRun> runs;
        try {
            runs = course.runBot(game, client, player, CAVE_WAVES, reset);
        } finally {
            reset.run();
        }
        List<CourseErrors.Score> scores = runs.stream().map(course::score).toList();
        human.judgeMedian(game, runs, scores);
    }

    /** Generate the cave's chunks, one per hop to the server thread. */
    static void generate(Game game) {
        for (int cx = SectionPos.blockToSectionCoord(CAVE_FROM.getX());
                cx <= SectionPos.blockToSectionCoord(CAVE_TO.getX());
                cx++) {
            for (int cz = SectionPos.blockToSectionCoord(CAVE_FROM.getZ());
                    cz <= SectionPos.blockToSectionCoord(CAVE_TO.getZ());
                    cz++) {
                int x = cx;
                int z = cz;
                game.runOnServer(s -> s.overworld().getChunk(x, z, ChunkStatus.FULL, true));
            }
        }
    }

    private static Map<String, Object> xyz(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }
}
