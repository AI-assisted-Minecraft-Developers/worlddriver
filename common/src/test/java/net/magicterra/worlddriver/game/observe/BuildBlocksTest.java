package net.magicterra.worlddriver.game.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.magicterra.worlddriver.VanillaBoot;
import net.magicterra.worlddriver.config.GameConfig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which blocks the bot may place, and which it must not spend: the three readings {@code mc.debug.cells} reports for a
 * block ({@code buildBlock}, {@code valuable}, {@code throwaway}) are these predicates, and a body's placeable-block
 * count ({@code mc.debug.body} hands) sums its stacks through the first one. Tags are bound, because the valuable test
 * reads {@code BlockTags.LOGS} / {@code PLANKS}.
 */
class BuildBlocksTest {
    private Set<String> whitelistBefore;

    @BeforeAll
    static void boot() {
        VanillaBoot.boot();
    }

    @BeforeEach
    void keep() {
        whitelistBefore = GameConfig.buildBlockWhitelist;
        GameConfig.buildBlockWhitelist = Set.of();
    }

    @AfterEach
    void restore() {
        GameConfig.buildBlockWhitelist = whitelistBefore;
    }

    /** The foothold a floating bot may place to climb a low bank: a falling block would drop out from under it. */
    @Test
    void fallingBlocksAreNoFoothold() {
        assertFalse(BuildBlocks.isUsableBuildBlock(Blocks.SAND), "sand falls");
        assertFalse(BuildBlocks.isUsableBuildBlock(Blocks.GRAVEL), "gravel falls");
    }

    /** Mud, though not a full cube, has a top the bot stands on. */
    @Test
    void mudIsAFoothold() {
        assertTrue(BuildBlocks.isUsableBuildBlock(Blocks.MUD), "mud is standable and does not fall");
    }

    /**
     * Interactive blocks (block-entity holders and the menu-opening work-station family) are resources, not scaffold —
     * the build-block picker must reject them no matter how sturdy. Placed as filler, one both spends a crafted station
     * and booby-traps every later place-click against it, since the click opens its screen instead of placing.
     */
    @Test
    void buildBlockRejectsInteractive() {
        Block[] mustReject = {
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.CHEST,
            Blocks.BARREL,
            Blocks.CRAFTING_TABLE,
            Blocks.SMITHING_TABLE,
            Blocks.CARTOGRAPHY_TABLE,
            Blocks.FLETCHING_TABLE,
            Blocks.LOOM,
        };
        Block[] mustAccept = {Blocks.COBBLESTONE, Blocks.DIRT, Blocks.MUD, Blocks.OAK_PLANKS};
        for (Block b : mustReject) {
            assertFalse(BuildBlocks.isUsableBuildBlock(b), "interactive block accepted as build material: " + b);
        }
        for (Block b : mustAccept) {
            assertTrue(BuildBlocks.isUsableBuildBlock(b), "plain block wrongly rejected: " + b);
        }
    }

    /**
     * The value-awareness layer ON TOP of the build-block predicate flags gathered-wood resources so the throwaway-only
     * picker skips them, WITHOUT touching the build-block predicate (OAK_PLANKS must stay a usable build block for
     * explicit build/construct commands).
     */
    @Test
    void valuablePlacementBlockMatrix() {
        Block[] mustBeValuable = {
            Blocks.OAK_LOG,
            Blocks.ACACIA_LOG,
            Blocks.SPRUCE_LOG,
            Blocks.STRIPPED_OAK_LOG,
            Blocks.OAK_PLANKS,
            Blocks.BIRCH_PLANKS,
        };
        Block[] mustNotBeValuable = {
            Blocks.DIRT, Blocks.COBBLESTONE, Blocks.STONE, Blocks.GRAVEL, Blocks.SAND, Blocks.MUD,
        };
        for (Block b : mustBeValuable) {
            assertTrue(
                    BuildBlocks.isValuablePlacementBlock(b), "gathered-wood block wrongly NOT flagged valuable: " + b);
        }
        for (Block b : mustNotBeValuable) {
            assertFalse(BuildBlocks.isValuablePlacementBlock(b), "plain block wrongly flagged valuable: " + b);
        }

        // The item-level throwaway predicate: a usable build block that is not a gathered resource.
        assertTrue(throwaway(Blocks.DIRT), "DIRT wrongly rejected as throwaway support");
        assertFalse(throwaway(Blocks.OAK_LOG), "OAK_LOG wrongly accepted as throwaway support (valuable)");
        assertFalse(throwaway(Blocks.OAK_PLANKS), "OAK_PLANKS wrongly accepted as throwaway support (valuable)");
    }

    /**
     * The build-block predicate and the placeable-block count that delegates to it: bamboo (thin, no footing) and sand
     * (falls over gaps) rejected by default; the {@code buildBlockWhitelist} setting overrides the shape test for both.
     */
    @Test
    void buildBlockWhitelist() {
        assertFalse(
                BuildBlocks.isUsableBuildBlock(Blocks.BAMBOO),
                "bamboo must NOT be a usable build block (thin, no footing)");

        // The count delegates to the predicate: a dirt+bamboo bag → only the dirt counts.
        List<ItemStack> bag = List.of(new ItemStack(Items.DIRT, 10), new ItemStack(Items.BAMBOO, 20));
        assertEquals(10, BuildBlocks.placeableCount(bag), "placeableBlockCount should ignore bamboo");

        // Whitelist override: bamboo only → dirt now rejected, bamboo allowed by explicit id.
        GameConfig.buildBlockWhitelist = Set.of("minecraft:bamboo");
        assertTrue(
                BuildBlocks.isUsableBuildBlock(Blocks.BAMBOO),
                "whitelisted bamboo must be usable when explicitly listed");
        assertFalse(BuildBlocks.isUsableBuildBlock(Blocks.DIRT), "dirt must be rejected when whitelist excludes it");
        assertEquals(20, BuildBlocks.placeableCount(bag), "whitelist=[bamboo] → count should be the 20 bamboo");
    }

    /** What {@code mc.debug.cells} reports as {@code throwaway} for a block standing in a cell. */
    private static boolean throwaway(Block b) {
        return BuildBlocks.isThrowawaySupportBlock(new ItemStack(b.asItem()));
    }
}
