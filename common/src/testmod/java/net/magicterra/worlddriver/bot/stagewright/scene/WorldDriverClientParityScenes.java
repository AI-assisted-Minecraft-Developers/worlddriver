package net.magicterra.worlddriver.bot.stagewright.scene;

import static net.magicterra.worlddriver.bot.stagewright.scene.SceneCalls.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Cell;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.CellsParams;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.CellsReading;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Side;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * The two planners' views of one world, cell by cell. The shipped client plans through its client
 * view; the {@code wd.*} suite and the server ladder plan through a server-level view. Both are read
 * through {@code mc.debug.cells} for self, once as the client sees it and once with
 * {@code side: server}, each from a freshly begun search. Wherever the two disagree on a cell, a route the suite validated is not
 * a route the client will emit, and the scene "ran" on a different set of rules than the product.
 * This scene asks both views the same questions over a palette of the terrains the split is
 * known to bite on and records every disagreement as a row; the check is that there are none.
 */
public final class WorldDriverClientParityScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(Scene.of("wd.clientWorldViewParity", 1_000, WorldDriverClientParityScenes::worldViewParity));
    }

    private static final int GROUND = 20;
    private static final int SPACING = 2;
    private static final int COLUMNS = 8;

    /** Foot cell of palette entry i. A grid hugging the player, not a row marching away from it:
     *  the client only holds the chunks around its player, and a cell it never received reads as
     *  air on its side, which would show up here as a disagreement about nothing. */
    private static BlockPos footOf(SceneContext ctx, int i) {
        return ctx.rel((i % COLUMNS) * SPACING - (COLUMNS - 1), GROUND + 1, (i / COLUMNS) * SPACING + 2);
    }

    /** One palette entry: the state in the foot cell, plus what the cell under it should be
     *  (stone unless the entry needs something else — water under a lily pad, sand under cactus). */
    private record Entry(String name, BlockState foot, BlockState under, BlockState behind) {
        static Entry of(String name, BlockState foot) { return new Entry(name, foot, Blocks.STONE.defaultBlockState(), null); }
    }

    private static List<Entry> palette() {
        List<Entry> e = new ArrayList<>();
        e.add(Entry.of("air", Blocks.AIR.defaultBlockState()));
        e.add(Entry.of("stone", Blocks.STONE.defaultBlockState()));
        e.add(Entry.of("dirt", Blocks.DIRT.defaultBlockState()));
        e.add(Entry.of("oak_log", Blocks.OAK_LOG.defaultBlockState()));
        e.add(Entry.of("oak_leaves", Blocks.OAK_LEAVES.defaultBlockState()));
        e.add(Entry.of("gravel", Blocks.GRAVEL.defaultBlockState()));
        e.add(Entry.of("sand", Blocks.SAND.defaultBlockState()));
        e.add(Entry.of("obsidian", Blocks.OBSIDIAN.defaultBlockState()));
        e.add(Entry.of("bedrock", Blocks.BEDROCK.defaultBlockState()));
        e.add(Entry.of("slab_bottom", Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)));
        e.add(Entry.of("slab_top", Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)));
        e.add(Entry.of("slab_double", Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE)));
        e.add(Entry.of("oak_fence", Blocks.OAK_FENCE.defaultBlockState()));
        e.add(Entry.of("cobblestone_wall", Blocks.COBBLESTONE_WALL.defaultBlockState()));
        e.add(Entry.of("soul_sand", Blocks.SOUL_SAND.defaultBlockState()));
        e.add(Entry.of("soul_soil", Blocks.SOUL_SOIL.defaultBlockState()));
        e.add(Entry.of("mud", Blocks.MUD.defaultBlockState()));
        e.add(Entry.of("snow_1", Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1)));
        e.add(Entry.of("snow_8", Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 8)));
        e.add(Entry.of("powder_snow", Blocks.POWDER_SNOW.defaultBlockState()));
        e.add(Entry.of("farmland", Blocks.FARMLAND.defaultBlockState()));
        e.add(Entry.of("dirt_path", Blocks.DIRT_PATH.defaultBlockState()));
        e.add(Entry.of("honey_block", Blocks.HONEY_BLOCK.defaultBlockState()));
        e.add(Entry.of("slime_block", Blocks.SLIME_BLOCK.defaultBlockState()));
        e.add(Entry.of("stone_stairs", Blocks.STONE_STAIRS.defaultBlockState()));
        e.add(Entry.of("oak_trapdoor", Blocks.OAK_TRAPDOOR.defaultBlockState()));
        e.add(Entry.of("iron_bars", Blocks.IRON_BARS.defaultBlockState()));
        e.add(Entry.of("glass_pane", Blocks.GLASS_PANE.defaultBlockState()));
        e.add(Entry.of("scaffolding", Blocks.SCAFFOLDING.defaultBlockState()));
        e.add(new Entry("lily_pad", Blocks.LILY_PAD.defaultBlockState(), Blocks.WATER.defaultBlockState(), null));
        e.add(Entry.of("pressure_plate", Blocks.STONE_PRESSURE_PLATE.defaultBlockState()));
        e.add(Entry.of("carpet", Blocks.WHITE_CARPET.defaultBlockState()));
        e.add(Entry.of("torch", Blocks.TORCH.defaultBlockState()));
        e.add(Entry.of("oak_sapling", Blocks.OAK_SAPLING.defaultBlockState()));
        e.add(Entry.of("short_grass", Blocks.SHORT_GRASS.defaultBlockState()));
        e.add(Entry.of("sweet_berry_bush", Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
        e.add(Entry.of("cobweb", Blocks.COBWEB.defaultBlockState()));
        e.add(Entry.of("water", Blocks.WATER.defaultBlockState()));
        e.add(Entry.of("lava", Blocks.LAVA.defaultBlockState()));
        e.add(Entry.of("magma_block", Blocks.MAGMA_BLOCK.defaultBlockState()));
        e.add(new Entry("cactus", Blocks.CACTUS.defaultBlockState(), Blocks.SAND.defaultBlockState(), null));
        e.add(Entry.of("campfire", Blocks.CAMPFIRE.defaultBlockState()));
        e.add(Entry.of("chest", Blocks.CHEST.defaultBlockState()));
        e.add(Entry.of("crafting_table", Blocks.CRAFTING_TABLE.defaultBlockState()));
        e.add(Entry.of("anvil", Blocks.ANVIL.defaultBlockState()));
        e.add(Entry.of("cauldron", Blocks.CAULDRON.defaultBlockState()));
        e.add(Entry.of("hopper", Blocks.HOPPER.defaultBlockState()));
        e.add(Entry.of("enchanting_table", Blocks.ENCHANTING_TABLE.defaultBlockState()));
        // Both hang on the stone placed north of their cell (behind), so they face south.
        e.add(new Entry("ladder", Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH),
                Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()));
        e.add(new Entry("vine", Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.NORTH, true),
                Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()));
        return e;
    }

    /** The questions, named the way the navigator asks them; a break cost no search can pay reads as Infinity. */
    private record Question(String name, Function<Cell, Object> ask) {}

    private static List<Question> questions() {
        return List.of(
                new Question("isSolid", Cell::solid),
                new Question("isPassable", Cell::passable),
                new Question("isHazard", Cell::hazard),
                new Question("isWater", Cell::water),
                new Question("isClimbable", Cell::climbable),
                new Question("isLeaves", Cell::leaves),
                new Question("isFallingBlock", Cell::fallingBlock),
                new Question("isBreakableObstruction", Cell::breakableObstruction),
                new Question("canStandOn", Cell::standOn),
                new Question("canStandAt", Cell::standAt),
                new Question("breakCost", c -> c.breakCost()
                        .map(v -> String.format(Locale.ROOT, "%.1f", v)).orElse("Infinity")));
    }

    private static void worldViewParity(SceneContext ctx) {
        ServerLevel level = ctx.level();
        List<Entry> palette = palette();
        // A stone slab under the whole grid, air above it, one entry every SPACING cells.
        int gridRows = (palette.size() + COLUMNS - 1) / COLUMNS;
        for (int dx = -COLUMNS - 1; dx <= COLUMNS + 1; dx++)
            for (int dz = -2; dz <= gridRows * SPACING + 2; dz++) {
                ctx.setBlock(dx, GROUND - 1, dz, Blocks.STONE);
                ctx.setBlock(dx, GROUND, dz, Blocks.STONE);
                for (int dy = 1; dy <= 3; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
        List<BlockPosition> cells = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            Entry en = palette.get(i);
            BlockPos foot = footOf(ctx, i);
            level.setBlockAndUpdate(foot.below(), en.under());
            if (en.behind() != null) level.setBlockAndUpdate(foot.north(), en.behind());
            level.setBlockAndUpdate(foot, en.foot());
            for (BlockPos cell : cellsOf(foot)) cells.add(Positions.position(cell));
        }
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        ctx.record("test setup", palette.size() + " terrain types, grid of " + COLUMNS + " columns, foot cell y=" + (GROUND + 1) + ", spacing " + SPACING + "; bot starts at " + start.toShortString());

        ClientHelm helm = ClientHelm.adopt(ctx, start, 0f);
        // The same hand on both players: the client's LocalPlayer and its ServerPlayer share one
        // inventory on an integrated server, so a break price that differs is a rule that differs.
        helm.hold(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.DIRT, 16));
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);

        // Let the client receive the palette before either view is asked.
        helm.sync(40, () -> {
            // The server side answers inline on this thread; the client's hops and answers a tick or more later.
            CellsReading server = Calls.now(DebugApi.of(api()).cells(
                    new CellsParams(Optional.of(ClientHelm.SELF), Optional.of(Side.SERVER), cells, Optional.empty())));
            // Not SceneCalls.ask: its record line would carry every cell of the reply.
            CompletableFuture<CellsReading> client = DebugApi.of(api()).cells(
                    new CellsParams(Optional.of(ClientHelm.SELF), Optional.empty(), cells, Optional.empty())).toCompletableFuture();
            ctx.await(client::isDone).within(40).then(() -> compare(ctx, palette, Calls.now(client), server));
        });
    }

    private static BlockPos[] cellsOf(BlockPos foot) {
        return new BlockPos[] { foot.below(), foot, foot.above() };
    }

    private static void compare(SceneContext ctx, List<Entry> palette, CellsReading client, CellsReading server) {
        ctx.record("views", "client=" + client.view() + ", server=" + server.view());
        Map<BlockPosition, Cell> c = byPos(client), s = byPos(server);
        List<String> rows = new ArrayList<>();
        int asked = 0;
        for (int i = 0; i < palette.size(); i++) {
            Entry en = palette.get(i);
            BlockPos foot = footOf(ctx, i);
            for (BlockPos cell : cellsOf(foot)) {
                String where = cell.getY() < foot.getY() ? "below" : cell.getY() > foot.getY() ? "above" : "foot";
                Cell cc = c.get(Positions.position(cell)), sc = s.get(Positions.position(cell));
                for (Question q : questions()) {
                    Object cv = cc == null ? "missing" : q.ask().apply(cc), sv = sc == null ? "missing" : q.ask().apply(sc);
                    asked++;
                    if (!String.valueOf(cv).equals(String.valueOf(sv)))
                        rows.add(en.name() + "/" + where + " " + q.name() + " client=" + cv + " server=" + sv);
                }
            }
        }
        ctx.record("queries", asked + " queries, " + rows.size() + " mismatched rows");
        ctx.record("differences", rows.isEmpty() ? "none" : String.join("; ", rows));
        ctx.check(rows.isEmpty()).as("both views give the same answers for the same world (" + rows.size() + " mismatched rows)").isTrue();
    }

    private static Map<BlockPosition, Cell> byPos(CellsReading reading) {
        Map<BlockPosition, Cell> m = new HashMap<>();
        for (Cell cell : reading.cells()) m.put(cell.pos(), cell);
        return m;
    }
}
