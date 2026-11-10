package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.BlockEvent;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.Sample;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The work of a {@link TaskCourse}, one kind per course kind of {@code bench.py}: the verb the bot is ordered with, how
 * far along a sample finds the work (read on the server, so it is never a frame behind a break or a placement), when it
 * is done, and the wrong blocks, by bench.py's {@code wrong_blocks}, {@code wrong_built} and {@code wrong_farmed}.
 * Positions are the course file's; {@link #shifted} moves them with the terrain.
 */
public sealed interface CourseTask permits CourseTask.Mine, CourseTask.Clear, CourseTask.Build, CourseTask.Farm {

    /** The tag of a JSON union, an order's target or a recording's course kind. */
    String KIND = "kind";

    /** A mining order's blocks; the {@code kind} of a {@code blocks} schematic. */
    String BLOCKS = "blocks";

    /** The course's id. */
    String name();

    /** The method the bot is ordered with. */
    String verb();

    /** Its parameters. */
    Map<String, Object> order();

    /** How many ores, cells or crop cells make the work done. */
    int target();

    /** How many are done now. */
    int progress(Game game, Player player);

    /** The run's end when the work is done. */
    String doneName();

    /** A mining run takes a split at each ore; the others at each quarter of their cells. */
    default boolean splitsEachCell() {
        return false;
    }

    /** Whether a placement is work, not a stall, as a break is. */
    default boolean placesAreWork() {
        return false;
    }

    /** The task's wrong blocks in a run's samples. */
    int wrong(List<Sample> samples);

    /** The same task moved by {@code by}. */
    CourseTask shifted(Vec3i by);

    /** The meta line's task fields, for bench.py's {@code analyse}. */
    Map<String, Object> meta();

    /** Called on every sample, before {@link #progress}. */
    default void watch(Game game, Sample s) {}

    /** Called once after the last sample. */
    default void finish(Game game) {}

    /** The id of a block, as the block events name it. */
    static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** Whether {@code p} is in the box between {@code a} and {@code b}, both included. */
    static boolean in(BlockPos p, BlockPos a, BlockPos b) {
        return p.getX() >= Math.min(a.getX(), b.getX())
                && p.getX() <= Math.max(a.getX(), b.getX())
                && p.getY() >= Math.min(a.getY(), b.getY())
                && p.getY() <= Math.max(a.getY(), b.getY())
                && p.getZ() >= Math.min(a.getZ(), b.getZ())
                && p.getZ() <= Math.max(a.getZ(), b.getZ());
    }

    /** A box as bench.py writes one, {@code [[x0, y0, z0], [x1, y1, z1]]}. */
    static List<List<Integer>> box(BlockPos a, BlockPos b) {
        return List.of(List.of(a.getX(), a.getY(), a.getZ()), List.of(b.getX(), b.getY(), b.getZ()));
    }

    /** A position as JSON. */
    static Map<String, Object> xyz(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    /**
     * Collecting {@code count} ores out of a block of stone ({@code kind: mine}). An ore counts when its drop reaches
     * the bag. A wrong block is one broken that is not a target ore and lies outside {@code workVolume}.
     */
    record Mine(
            String name,
            List<String> blocks,
            List<Item> items,
            int count,
            int radius,
            BlockPos volumeFrom,
            BlockPos volumeTo)
            implements CourseTask {

        @Override
        public String verb() {
            return "mc.bot.mine";
        }

        @Override
        public Map<String, Object> order() {
            return Map.of(BLOCKS, blocks, "quantity", count, "radius", radius);
        }

        @Override
        public int target() {
            return count;
        }

        @Override
        public int progress(Game game, Player player) {
            return player.onServer(p ->
                    items.stream().mapToInt(i -> p.getInventory().countItem(i)).sum());
        }

        @Override
        public String doneName() {
            return "collected";
        }

        @Override
        public boolean splitsEachCell() {
            return true;
        }

        @Override
        public int wrong(List<Sample> samples) {
            return brokenOutside(samples, Set.copyOf(blocks), volumeFrom, volumeTo)
                    .size();
        }

        @Override
        public CourseTask shifted(Vec3i by) {
            return new Mine(name, blocks, items, count, radius, volumeFrom.offset(by), volumeTo.offset(by));
        }

        @Override
        public Map<String, Object> meta() {
            return Map.of(KIND, "mine", "target", count, BLOCKS, blocks, "workVolume", box(volumeFrom, volumeTo));
        }
    }

    /**
     * Breaking every block of a volume until it holds only air ({@code kind: clear}). A wrong block is one broken
     * outside the volume, or any cell a block was placed in.
     */
    record Clear(String name, BlockPos from, BlockPos to) implements CourseTask {

        @Override
        public String verb() {
            return "mc.bot.clearArea";
        }

        @Override
        public Map<String, Object> order() {
            return Map.of(
                    "target", Map.of(KIND, "box", "from", xyz(from), "to", xyz(to)),
                    "edit", Map.of(KIND, "clear"));
        }

        @Override
        public int target() {
            return (Math.abs(to.getX() - from.getX()) + 1)
                    * (Math.abs(to.getY() - from.getY()) + 1)
                    * (Math.abs(to.getZ() - from.getZ()) + 1);
        }

        @Override
        public int progress(Game game, Player player) {
            return game.onServer(s -> {
                int air = 0;
                for (BlockPos p : BlockPos.betweenClosed(from, to)) {
                    air += s.overworld().getBlockState(p).isAir() ? 1 : 0;
                }
                return air;
            });
        }

        @Override
        public String doneName() {
            return "cleared";
        }

        @Override
        public int wrong(List<Sample> samples) {
            Set<BlockPos> placed = new HashSet<>();
            for (Sample s : samples) {
                for (BlockEvent e : s.events()) {
                    if ("block.place".equals(e.type())) {
                        placed.add(e.pos());
                    }
                }
            }
            return brokenOutside(samples, Set.of(), from, to).size() + placed.size();
        }

        @Override
        public CourseTask shifted(Vec3i by) {
            return new Clear(name, from.offset(by), to.offset(by));
        }

        @Override
        public Map<String, Object> meta() {
            return Map.of(
                    KIND,
                    "clear",
                    "cells",
                    target(),
                    BLOCKS,
                    List.of(),
                    "workVolume",
                    box(from, to),
                    "placeAllowed",
                    false);
        }
    }

    /**
     * Building a blueprint ({@code kind: build}). A wrong block is a placement outside the blueprint or of another
     * block than the blueprint's there, or a break of a block not placed during the run; each cell counts once.
     *
     * @param cells the blueprint: every non-air cell and its block id, bottom layer up, then by z, then by x
     */
    record Build(String name, BlockPos origin, Map<BlockPos, String> cells) implements CourseTask {

        @Override
        public String verb() {
            return "mc.bot.build";
        }

        @Override
        public Map<String, Object> order() {
            List<String> palette = cells.values().stream().distinct().sorted().toList();
            int w = 0;
            int h = 0;
            int d = 0;
            List<List<Integer>> data = new java.util.ArrayList<>();
            for (Map.Entry<BlockPos, String> c : cells.entrySet()) {
                BlockPos rel = c.getKey().subtract(origin);
                w = Math.max(w, rel.getX() + 1);
                h = Math.max(h, rel.getY() + 1);
                d = Math.max(d, rel.getZ() + 1);
                data.add(List.of(rel.getX(), rel.getY(), rel.getZ(), palette.indexOf(c.getValue())));
            }
            return Map.of(
                    "origin", xyz(origin),
                    "schematic", Map.of(KIND, BLOCKS, "w", w, "h", h, "d", d, "palette", palette, "data", data));
        }

        @Override
        public int target() {
            return cells.size();
        }

        @Override
        public int progress(Game game, Player player) {
            return game.onServer(s -> (int) cells.entrySet().stream()
                    .filter(c -> c.getValue().equals(id(s.overworld().getBlockState(c.getKey()))))
                    .count());
        }

        @Override
        public String doneName() {
            return "built";
        }

        @Override
        public boolean placesAreWork() {
            return true;
        }

        @Override
        public int wrong(List<Sample> samples) {
            Set<BlockPos> placed = new HashSet<>();
            Set<BlockPos> wrong = new HashSet<>();
            for (Sample s : samples) {
                for (BlockEvent e : s.events()) {
                    if ("block.place".equals(e.type())) {
                        placed.add(e.pos());
                        if (!e.blockId().equals(cells.get(e.pos()))) {
                            wrong.add(e.pos());
                        }
                    } else if (!placed.contains(e.pos())) {
                        wrong.add(e.pos());
                    }
                }
            }
            return wrong.size();
        }

        @Override
        public CourseTask shifted(Vec3i by) {
            Map<BlockPos, String> moved = new LinkedHashMap<>();
            cells.forEach((p, b) -> moved.put(p.offset(by), b));
            return new Build(name, origin.offset(by), moved);
        }

        @Override
        public Map<String, Object> meta() {
            return Map.of(
                    KIND,
                    "build",
                    "blueprint",
                    cells.entrySet().stream()
                            .map(c -> List.<Object>of(
                                    c.getKey().getX(),
                                    c.getKey().getY(),
                                    c.getKey().getZ(),
                                    c.getValue()))
                            .toList());
        }

        /**
         * The blueprint of a course file: {@code layers} bottom-up, each layer's rows north to south and each row's
         * characters west to east, {@code palette} naming each character's block, air left out.
         */
        public static Map<BlockPos, String> blueprint(
                BlockPos origin, Map<Character, String> palette, String[][] layers) {
            Map<BlockPos, String> out = new LinkedHashMap<>();
            for (int dy = 0; dy < layers.length; dy++) {
                for (int dz = 0; dz < layers[dy].length; dz++) {
                    String row = layers[dy][dz];
                    for (int dx = 0; dx < row.length(); dx++) {
                        String block = palette.get(row.charAt(dx));
                        if (!"minecraft:air".equals(block)) {
                            out.put(origin.offset(dx, dy, dz), block);
                        }
                    }
                }
            }
            return out;
        }
    }

    /**
     * Harvesting a plot of ripe crops and planting each cell again ({@code kind: farm}). Random ticks are off, so a
     * crop at age 0 was harvested and replanted. A wrong block is a break that is not the crop, a placement outside a
     * crop cell or of another block, and a crop cell whose farmland was trampled to dirt, read on every sample and once
     * more after the last; each cell counts once, a trample as its farmland's cell.
     */
    final class Farm implements CourseTask {

        private final String name;

        private final BlockPos from;

        private final BlockPos to;

        private final String crop;

        private final Set<BlockPos> water;

        private final Set<BlockPos> trampled = new HashSet<>();

        /** The plot box of the crop layer, the farmland a block below it; {@code water}: farmland-layer water cells. */
        public Farm(String name, BlockPos from, BlockPos to, String crop, Set<BlockPos> water) {
            this.name = name;
            this.from = from;
            this.to = to;
            this.crop = crop;
            this.water = Set.copyOf(water);
        }

        /** The crop cells: every cell of the crop layer not over the water. */
        public List<BlockPos> plot() {
            List<BlockPos> out = new java.util.ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(from, to)) {
                if (!water.contains(p.below())) {
                    out.add(p.immutable());
                }
            }
            return out;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String verb() {
            return "mc.bot.farm";
        }

        @Override
        public Map<String, Object> order() {
            return Map.of("from", xyz(from), "to", xyz(to), "crops", List.of(crop), "replant", true);
        }

        @Override
        public int target() {
            return plot().size();
        }

        @Override
        public int progress(Game game, Player player) {
            ResourceLocation id = ResourceLocation.parse(crop);
            return game.onServer(s -> (int) plot().stream()
                    .map(c -> s.overworld().getBlockState(c))
                    .filter(b -> BuiltInRegistries.BLOCK.getKey(b.getBlock()).equals(id)
                            && b.getBlock() instanceof CropBlock cb
                            && cb.getAge(b) == 0)
                    .count());
        }

        @Override
        public String doneName() {
            return "farmed";
        }

        @Override
        public boolean placesAreWork() {
            return true;
        }

        @Override
        public void watch(Game game, Sample s) {
            readTrampled(game);
        }

        @Override
        public void finish(Game game) {
            readTrampled(game);
        }

        private void readTrampled(Game game) {
            trampled.addAll(game.onServer(s -> {
                ServerLevel level = s.overworld();
                return plot().stream()
                        .map(BlockPos::below)
                        .filter(f -> !level.getBlockState(f).is(Blocks.FARMLAND))
                        .toList();
            }));
        }

        @Override
        public int wrong(List<Sample> samples) {
            Set<BlockPos> plot = Set.copyOf(plot());
            Set<BlockPos> wrong = new HashSet<>(trampled);
            for (Sample s : samples) {
                for (BlockEvent e : s.events()) {
                    boolean isCrop = crop.equals(e.blockId());
                    if ("block.break".equals(e.type()) && !isCrop) {
                        wrong.add(e.pos());
                    }
                    if ("block.place".equals(e.type()) && (!plot.contains(e.pos()) || !isCrop)) {
                        wrong.add(e.pos());
                    }
                }
            }
            return wrong.size();
        }

        @Override
        public CourseTask shifted(Vec3i by) {
            Set<BlockPos> moved = new HashSet<>();
            water.forEach(w -> moved.add(w.offset(by)));
            return new Farm(name, from.offset(by), to.offset(by), crop, moved);
        }

        @Override
        public Map<String, Object> meta() {
            return Map.of(
                    KIND,
                    "farm",
                    "crop",
                    crop,
                    "plot",
                    plot().stream()
                            .map(c -> List.of(c.getX(), c.getY(), c.getZ()))
                            .toList());
        }
    }

    /** The cells broken outside the box from {@code a} to {@code b} that were not one of {@code targets}. */
    private static Set<BlockPos> brokenOutside(List<Sample> samples, Set<String> targets, BlockPos a, BlockPos b) {
        Set<BlockPos> out = new HashSet<>();
        for (Sample s : samples) {
            for (BlockEvent e : s.events()) {
                if ("block.break".equals(e.type()) && !targets.contains(e.blockId()) && !in(e.pos(), a, b)) {
                    out.add(e.pos());
                }
            }
        }
        return out;
    }
}
