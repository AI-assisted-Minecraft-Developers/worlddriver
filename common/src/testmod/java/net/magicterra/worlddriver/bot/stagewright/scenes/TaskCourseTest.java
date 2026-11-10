package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseKit;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRun;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseTask;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseTerrain;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseWorld;
import net.magicterra.worlddriver.bot.stagewright.kit.HumanBest;
import net.magicterra.worlddriver.bot.stagewright.kit.TaskCourse;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The bot against the human on the benchmark's task courses ({@code docs/dev/benchmark.md}): mining, clearing, building
 * and farming, on the real client's player. Each course's terrain is the structure the bench built in the Lab world,
 * placed in a plot; the run is one order of the task's verb, judged as {@code bench.py report} judges it against the
 * human's best recorded run, wrong blocks included.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_RULES)
@ResourceLock(Locks.WORLD_CLOCK)
class TaskCourseTest {

    private static final int FIVE_MINUTES = 300 * CourseRecorder.TICKS_PER_SECOND;

    private static final int MARGIN = 2;

    /** Facing north, towards the work, as every task course starts. */
    private static final float NORTH = 180f;

    /** The {@code botSettings} of the mining and clearing courses: the navigator places nothing. */
    private static final Map<String, Object> NO_PLACING =
            Map.of("allowPlace", false, "allowParkourPlace", false, "allowWaterBucketFall", false);

    /** The {@code botSettings} of the building and farming courses: the navigator breaks and places nothing. */
    private static final Map<String, Object> NO_BREAKING_OR_PLACING =
            Map.of("allowBreak", false, "allowPlace", false, "allowParkourPlace", false, "allowWaterBucketFall", false);

    /** The hut's rows, west to east: a plank post, cobblestone, a plank post. */
    private static final String END_WALL = "pcccp";

    private static final String SIDE_WALLS = "c...c";

    private static final String WINDOWS = "g...g";

    private static final String DOOR = "pc.cp";

    private static final BlockPos ORE_BLOCK_CORNER = new BlockPos(988, 140, 1488);

    /** {@code scripts/bench/courses/ore-block.json}. */
    private static final TaskCourse ORE_BLOCK = new TaskCourse(
            new Vec3(1000.5, 150, 1509.5),
            NORTH,
            CourseKit.of(new ItemStack(Items.IRON_PICKAXE)),
            new CourseTask.Mine(
                    "ore-block",
                    List.of(
                            "minecraft:coal_ore",
                            "minecraft:deepslate_coal_ore",
                            "minecraft:iron_ore",
                            "minecraft:deepslate_iron_ore"),
                    List.of(Items.COAL, Items.RAW_IRON),
                    10,
                    16,
                    new BlockPos(996, 150, 1496),
                    new BlockPos(1004, 156, 1504)),
            NO_PLACING,
            FIVE_MINUTES);

    /**
     * {@code ore-block}'s human baseline: the best of its three finished human runs, 30.217 s, with 7 errors (seven
     * bumps, no wrong block), the fewest of any; the first run also had seven.
     */
    private static final HumanBest ORE_BLOCK_HUMAN = new HumanBest("ore-block", 30.217, 7);

    private static final BlockPos CLEAR_AREA_CORNER = new BlockPos(991, 140, 1591);

    /** {@code scripts/bench/courses/clear-area.json}. */
    private static final TaskCourse CLEAR_AREA = new TaskCourse(
            new Vec3(1000.5, 150, 1607.5),
            NORTH,
            CourseKit.of(
                    new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.IRON_AXE)),
            new CourseTask.Clear("clear-area", new BlockPos(998, 150, 1598), new BlockPos(1002, 152, 1602)),
            NO_PLACING,
            FIVE_MINUTES);

    /**
     * {@code clear-area}'s human baseline: the best of its three finished human runs, 52.363 s, which is also the one
     * with the fewest errors, 6 (six bumps).
     */
    private static final HumanBest CLEAR_AREA_HUMAN = new HumanBest("clear-area", 52.363, 6);

    private static final BlockPos BUILD_HUT_CORNER = new BlockPos(989, 140, 1689);

    private static final BlockPos HUT_ORIGIN = new BlockPos(998, 150, 1698);

    /** {@code scripts/bench/courses/build-hut.json}; its copy is in the terrain. */
    private static final TaskCourse BUILD_HUT = new TaskCourse(
            new Vec3(1000.5, 150, 1707.5),
            NORTH,
            CourseKit.of(
                    new ItemStack(Items.COBBLESTONE, 36),
                    new ItemStack(Items.OAK_PLANKS, 16),
                    new ItemStack(Items.GLASS, 4),
                    new ItemStack(Items.IRON_PICKAXE)),
            new CourseTask.Build(
                    "build-hut",
                    HUT_ORIGIN,
                    CourseTask.Build.blueprint(
                            HUT_ORIGIN,
                            Map.of(
                                    'c', "minecraft:cobblestone",
                                    'p', "minecraft:oak_planks",
                                    'g', "minecraft:glass",
                                    '.', "minecraft:air"),
                            new String[][] {
                                {END_WALL, SIDE_WALLS, SIDE_WALLS, SIDE_WALLS, DOOR},
                                {END_WALL, SIDE_WALLS, WINDOWS, SIDE_WALLS, DOOR},
                                {END_WALL, SIDE_WALLS, SIDE_WALLS, SIDE_WALLS, END_WALL}
                            })),
            NO_BREAKING_OR_PLACING,
            FIVE_MINUTES);

    /**
     * {@code build-hut}'s human baseline: the best of its three finished human runs, 44.746 s, which is also the one
     * with the fewest errors, 2 (a stall and a wrong block).
     */
    private static final HumanBest BUILD_HUT_HUMAN = new HumanBest("build-hut", 44.746, 2);

    private static final BlockPos FARM_WHEAT_CORNER = new BlockPos(991, 140, 1791);

    /** {@code scripts/bench/courses/farm-wheat.json}. */
    private static final TaskCourse FARM_WHEAT = new TaskCourse(
            new Vec3(1000.5, 150, 1809.5),
            NORTH,
            CourseKit.of(
                    new ItemStack(Items.WHEAT_SEEDS, 64),
                    new ItemStack(Items.WHEAT_SEEDS, 16),
                    new ItemStack(Items.IRON_HOE)),
            new CourseTask.Farm(
                    "farm-wheat",
                    new BlockPos(996, 150, 1796),
                    new BlockPos(1004, 150, 1804),
                    "minecraft:wheat",
                    Set.of(new BlockPos(1000, 149, 1800))),
            NO_BREAKING_OR_PLACING,
            FIVE_MINUTES);

    /**
     * {@code farm-wheat}'s human baseline: the best of its three finished human runs, 45.138 s, with 1 error (a stall);
     * every human run had one.
     */
    private static final HumanBest FARM_WHEAT_HUMAN = new HumanBest("farm-wheat", 45.138, 1);

    /**
     * Ten ores out of twelve in a block of deepslate under stone, at least four of them dug for, with an iron pickaxe
     * and placing off: the bot is faster than the human's best run and makes no more errors than the human's fewest.
     */
    @Test
    void oreBlockBeatsTheHumanBest(Game game, Client client, Player player, @Plot.Size(32) Plot plot) {
        run(game, client, player, plot, ORE_BLOCK, ORE_BLOCK_CORNER, ORE_BLOCK_HUMAN);
    }

    /**
     * A volume 5 by 3 by 5 of dirt, gravel, stone, cobblestone, logs and planks, with a pickaxe, a shovel and an axe.
     */
    @Test
    void clearAreaBeatsTheHumanBest(Game game, Client client, Player player, @Plot.Size(32) Plot plot) {
        run(game, client, player, plot, CLEAR_AREA, CLEAR_AREA_CORNER, CLEAR_AREA_HUMAN);
    }

    /** A hut 5 by 3 by 5 of 46 blocks from a kit with four spare of each, next to a copy of it. */
    @Test
    void buildHutBeatsTheHumanBest(Game game, Client client, Player player, @Plot.Size(32) Plot plot) {
        run(game, client, player, plot, BUILD_HUT, BUILD_HUT_CORNER, BUILD_HUT_HUMAN);
    }

    /** Eighty ripe wheat round a water source, harvested and replanted, without trampling the farmland. */
    @Test
    void farmWheatBeatsTheHumanBest(Game game, Client client, Player player, @Plot.Size(32) Plot plot) {
        run(game, client, player, plot, FARM_WHEAT, FARM_WHEAT_CORNER, FARM_WHEAT_HUMAN);
    }

    private static void run(
            Game game, Client client, Player player, Plot plot, TaskCourse course, BlockPos corner, HumanBest human) {
        CourseWorld.noRandomTicks(game);
        TaskCourse placed = course.shifted(CourseTerrain.place(plot, course.name(), corner, MARGIN, 0, MARGIN));
        CourseRun run = placed.runBot(game, client, player);
        human.judge(game, run, placed.score(run));
    }
}
