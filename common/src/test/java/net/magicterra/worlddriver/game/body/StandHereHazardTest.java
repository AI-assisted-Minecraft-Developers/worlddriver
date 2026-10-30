package net.magicterra.worlddriver.game.body;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.VanillaBoot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link BotUtil#canStandHereStatic} refuses a hazard in the floor, foot or head cell, as the planner's
 * {@code WorldView.canStandAt} does, so the stand pickers never pick a cell A* cannot expand.
 */
class StandHereHazardTest {

    private static final BlockPos FOOT = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        VanillaBoot.boot();
    }

    /** Three cells over a map; every other cell air. */
    private record Column(BlockState below, BlockState here, BlockState head) implements BlockGetter {
        @Override
        public BlockState getBlockState(BlockPos p) {
            Map<BlockPos, BlockState> m = new HashMap<>();
            m.put(FOOT.below(), below);
            m.put(FOOT, here);
            m.put(FOOT.above(), head);
            return m.getOrDefault(p, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos p) {
            return getBlockState(p).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos p) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    private static BlockState s(Block b) {
        return b.defaultBlockState();
    }

    @Test
    void theStandTable() {
        BlockState air = s(Blocks.AIR), stone = s(Blocks.STONE);
        BlockState wetSlab = s(Blocks.OAK_SLAB)
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                .setValue(BlockStateProperties.WATERLOGGED, true);
        Map<String, Object[]> rows = new LinkedHashMap<>();
        // refused for a hazard: each of these stood before the hazard clause
        rows.put("magma floor", new Object[] {new Column(s(Blocks.MAGMA_BLOCK), air, air), false});
        rows.put("lava foot", new Object[] {new Column(stone, s(Blocks.LAVA), air), false});
        rows.put("fire foot", new Object[] {new Column(stone, s(Blocks.FIRE), air), false});
        rows.put("berry bush foot", new Object[] {new Column(stone, s(Blocks.SWEET_BERRY_BUSH), air), false});
        rows.put("fire head", new Object[] {new Column(stone, air, s(Blocks.FIRE)), false});
        // unchanged
        rows.put("plain stand", new Object[] {new Column(stone, air, air), true});
        rows.put("waterlogged slab foot", new Object[] {new Column(stone, wetSlab, air), true});
        rows.put("air floor", new Object[] {new Column(air, air, air), false});
        List<String> wrong = new ArrayList<>();
        rows.forEach((name, row) -> {
            boolean got = BotUtil.canStandHereStatic((Column) row[0], FOOT);
            if (got != (boolean) row[1]) {
                wrong.add(name + "=" + got);
            }
        });
        assertEquals(List.of(), wrong, "rows that disagree with the table");
    }
}
