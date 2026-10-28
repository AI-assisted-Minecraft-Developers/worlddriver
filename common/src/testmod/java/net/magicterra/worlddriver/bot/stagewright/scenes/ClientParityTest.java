package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Cell;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.CellsParams;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.CellsReading;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Side;
import net.magicterra.worlddriver.api.methods.debug.DebugBlockingApi;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.kit.ClientRig;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The two planners' views of one world, cell by cell. The shipped client plans through its client view; the headless
 * tests and the server ladder plan through a server-level view. Both are read through {@code mc.debug.cells}, once as
 * the client sees it ({@code self} on the client's registry) and once as the server sees the same player
 * ({@code player:<name>} with {@code side: server} on the server's), each from a freshly begun search. Wherever the two
 * disagree on a cell, a route the suite validated is not a route the client will emit, and the suite "ran" on a
 * different set of rules than the product. This asks both views the same questions over a palette of the terrains
 * the split is known to bite on and reports every disagreement as a row; the check is that there are none.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class ClientParityTest {

    private static final int GROUND = 0;
    private static final int SPACING = 2;
    private static final int COLUMNS = 8;

    /**
     * Relative foot cell of palette entry i. A grid hugging the player, not a row marching away from it: the client
     * only holds the chunks around its player, and a cell it never received reads as air on its side, which would show
     * up here as a disagreement about nothing.
     */
    private static BlockPos footOf(Plot plot, int i) {
        return plot.at((i % COLUMNS) * SPACING - (COLUMNS - 1), GROUND + 1, (i / COLUMNS) * SPACING + 2);
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

    @Test
    void clientWorldViewParity(Game game, Client client, Player player,
                               @Plot.Centered @Plot.Size(x = 32, y = 16, z = 48) Plot plot) {
        List<Entry> palette = palette();
        // A stone slab under the whole grid, air above it, one entry every SPACING cells.
        int gridRows = (palette.size() + COLUMNS - 1) / COLUMNS;
        plot.fill(-COLUMNS - 1, GROUND - 1, -2, COLUMNS + 1, GROUND, gridRows * SPACING + 2, Blocks.STONE);
        List<BlockPosition> cells = new ArrayList<>();
        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            for (int i = 0; i < palette.size(); i++) {
                Entry en = palette.get(i);
                BlockPos foot = footOf(plot, i);
                level.setBlockAndUpdate(foot.below(), en.under());
                if (en.behind() != null) level.setBlockAndUpdate(foot.north(), en.behind());
                level.setBlockAndUpdate(foot, en.foot());
                for (BlockPos cell : cellsOf(foot)) cells.add(Positions.position(cell));
            }
        });
        BlockPos start = plot.at(0, GROUND + 1, 0);

        ClientRig rig = ClientRig.adopt(game, client, player, start, 0f);
        // The same hand on both players: the server's copy is written here and the client's follows it by the
        // inventory sync, so a break price that differs is a rule that differs.
        rig.hold(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.DIRT, 16));
        rig.settings().set(Map.of("allowBreak", true, "allowPlace", true));
        // Let the client receive the palette before either view is asked.
        rig.sync(40);

        CellsReading server = game.api(DebugBlockingApi::of).cells(
                new CellsParams(Optional.of(player.ref()), Optional.of(Side.SERVER), cells, Optional.empty()));
        CellsReading clientView = rig.debug().cells(
                new CellsParams(Optional.of(ClientRig.SELF), Optional.empty(), cells, Optional.empty()));

        Map<BlockPosition, Cell> c = byPos(clientView), sv = byPos(server);
        List<String> rows = new ArrayList<>();
        int asked = 0;
        for (int i = 0; i < palette.size(); i++) {
            Entry en = palette.get(i);
            BlockPos foot = footOf(plot, i);
            for (BlockPos cell : cellsOf(foot)) {
                String where = cell.getY() < foot.getY() ? "below" : cell.getY() > foot.getY() ? "above" : "foot";
                Cell cc = c.get(Positions.position(cell)), sc = sv.get(Positions.position(cell));
                for (Question q : questions()) {
                    Object cvalue = cc == null ? "missing" : q.ask().apply(cc);
                    Object svalue = sc == null ? "missing" : q.ask().apply(sc);
                    asked++;
                    if (!String.valueOf(cvalue).equals(String.valueOf(svalue)))
                        rows.add(en.name() + "/" + where + " " + q.name() + " client=" + cvalue + " server=" + svalue);
                }
            }
        }
        game.check(rows.isEmpty(), "both views give the same answers for the same world (client=" + clientView.view()
                + ", server=" + server.view() + "; " + rows.size() + " of " + asked + " queries mismatched): " + rows);
    }

    private static BlockPos[] cellsOf(BlockPos foot) {
        return new BlockPos[] { foot.below(), foot, foot.above() };
    }

    private static Map<BlockPosition, Cell> byPos(CellsReading reading) {
        Map<BlockPosition, Cell> m = new HashMap<>();
        for (Cell cell : reading.cells()) m.put(cell.pos(), cell);
        return m;
    }
}
