package net.magicterra.worlddriver.bot.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import net.magicterra.worlddriver.VanillaBoot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Which default states {@link PlaceSupport#holds} accepts as a support for a placed block. */
class PlaceSupportTest {

    @BeforeAll
    static void boot() {
        VanillaBoot.boot();
    }

    @Test
    void theSupportTable() {
        Map<Block, Boolean> table = new LinkedHashMap<>();
        table.put(Blocks.STONE, true);
        table.put(Blocks.OAK_LEAVES, true);
        table.put(Blocks.DIRT_PATH, true);
        table.put(Blocks.FARMLAND, true);
        table.put(Blocks.OAK_SLAB, true);
        table.put(Blocks.CHEST, true);
        table.put(Blocks.OAK_FENCE, true);
        table.put(Blocks.LILY_PAD, false);
        table.put(Blocks.WHITE_CARPET, false);
        // Vanilla registers pressure plates with forceSolidOn, so they count though they have no collision.
        table.put(Blocks.STONE_PRESSURE_PLATE, true);
        table.put(Blocks.OAK_PRESSURE_PLATE, true);
        table.put(Blocks.OAK_BUTTON, false);
        table.put(Blocks.DANDELION, false);
        table.put(Blocks.TORCH, false);
        table.put(Blocks.AIR, false);
        table.put(Blocks.WATER, false);
        StringBuilder wrong = new StringBuilder();
        table.forEach((b, want) -> {
            boolean got = PlaceSupport.holds(b.defaultBlockState());
            if (got != want) {
                wrong.append(' ').append(b).append('=').append(got);
            }
        });
        assertEquals("", wrong.toString(), "rows that disagree with the table");
    }
}
