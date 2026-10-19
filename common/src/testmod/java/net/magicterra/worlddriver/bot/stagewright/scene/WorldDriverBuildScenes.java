package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded worlddriver scenes — <b>P4b wave 5, the BuildBlock family</b>: the 2
 * {@code AgentGameTestBuildBlock} probes (interactive-block rejection + valuable-placement
 * matrix), migrated to testkit {@code wd.*} scenes and their legacy twin class retired in the
 * same commit.
 *
 * <p><b>Both are block-classification probes — no bot, no walk.</b> Each stands one block of
 * every kind in its matrix in a row of cells and reads how {@code mc.debug.cells} classifies
 * them: {@code buildBlock} (the build-block picker's predicate), {@code valuable} (a gathered
 * resource) and {@code throwaway} (filler the bot spends first). The row is put back to air when
 * the scene ends. The settings are never written, so the classification is the baseline's.
 */
public final class WorldDriverBuildScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.buildBlockRejectsInteractive", 200, WorldDriverBuildScenes::buildBlockRejectsInteractive),
                Scene.of("wd.valuablePlacementBlockMatrix", 200, WorldDriverBuildScenes::valuablePlacementBlockMatrix));
    }

    /** Ported from {@code AgentGameTestBuildBlock#buildBlockRejectsInteractiveArena} (gap #57):
     *  interactive blocks (block-entity holders + the menu-opening work-station family) are
     *  resources, not scaffold — the build-block picker must reject them no matter how sturdy. */
    private static void buildBlockRejectsInteractive(SceneContext ctx) {
        Block[] mustReject = {
                Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CHEST,
                Blocks.BARREL, Blocks.CRAFTING_TABLE, Blocks.SMITHING_TABLE,
                Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.LOOM,
        };
        Block[] mustAccept = { Blocks.COBBLESTONE, Blocks.DIRT, Blocks.MUD, Blocks.OAK_PLANKS };
        Map<Block, BotDebugContracts.Cell> read = classify(ctx, mustReject, mustAccept);
        for (Block b : mustReject)
            if (read.get(b).buildBlock())
                ctx.fail("buildBlockRejectsInteractive: interactive block accepted as build material: " + b);
        for (Block b : mustAccept)
            if (!read.get(b).buildBlock())
                ctx.fail("buildBlockRejectsInteractive: plain block wrongly rejected: " + b);
        TestMod.LOG.info("[wd.buildBlockRejectsInteractive] all {} interactive rejected, {} plain accepted",
                mustReject.length, mustAccept.length);
    }

    /** Ported from {@code AgentGameTestBuildBlock#valuablePlacementBlockMatrix} (gap #81): the
     *  value-awareness layer ON TOP of the build-block predicate flags gathered-wood resources so
     *  the throwaway-only picker skips them, WITHOUT touching the gap #57 build-block predicate
     *  (OAK_PLANKS must stay a usable build block). */
    private static void valuablePlacementBlockMatrix(SceneContext ctx) {
        Block[] mustBeValuable = {
                Blocks.OAK_LOG, Blocks.ACACIA_LOG, Blocks.SPRUCE_LOG, Blocks.STRIPPED_OAK_LOG,
                Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS,
        };
        Block[] mustNotBeValuable = {
                Blocks.DIRT, Blocks.COBBLESTONE, Blocks.STONE, Blocks.GRAVEL, Blocks.SAND, Blocks.MUD,
        };
        Map<Block, BotDebugContracts.Cell> read = classify(ctx, mustBeValuable, mustNotBeValuable);
        for (Block b : mustBeValuable)
            if (!read.get(b).valuable())
                ctx.fail("valuablePlacementBlockMatrix: gathered-wood block wrongly NOT flagged valuable: " + b);
        for (Block b : mustNotBeValuable)
            if (read.get(b).valuable())
                ctx.fail("valuablePlacementBlockMatrix: plain block wrongly flagged valuable: " + b);

        // Regression guard: value-awareness must NOT touch the existing build-block predicate
        // (gap #57 — OAK_PLANKS stays a usable build block for explicit build/construct commands).
        if (!read.get(Blocks.OAK_PLANKS).buildBlock())
            ctx.fail("valuablePlacementBlockMatrix: regression: OAK_PLANKS no longer a usable build block");
        if (!read.get(Blocks.DIRT).buildBlock())
            ctx.fail("valuablePlacementBlockMatrix: regression: DIRT no longer a usable build block");

        // The item-level throwaway predicate: a usable build block that is not a gathered resource.
        if (!read.get(Blocks.DIRT).throwaway())
            ctx.fail("valuablePlacementBlockMatrix: DIRT wrongly rejected as throwaway support");
        if (read.get(Blocks.OAK_LOG).throwaway())
            ctx.fail("valuablePlacementBlockMatrix: OAK_LOG wrongly accepted as throwaway support (valuable)");
        if (read.get(Blocks.OAK_PLANKS).throwaway())
            ctx.fail("valuablePlacementBlockMatrix: OAK_PLANKS wrongly accepted as throwaway support (valuable)");

        TestMod.LOG.info("[wd.valuablePlacementBlockMatrix] {} valuable, {} non-valuable, throwaway matrix all pass",
                mustBeValuable.length, mustNotBeValuable.length);
    }

    /**
     * Every block of {@code groups} stood in its own cell of a row twenty above the origin, and each one as
     * {@code mc.debug.cells} reads it from the server's level; the row is air again when the scene ends.
     */
    static Map<Block, BotDebugContracts.Cell> classify(SceneContext ctx, Block[]... groups) {
        ServerLevel level = ctx.level();
        if (level.dimension() != Level.OVERWORLD)
            ctx.fail("mc.debug.cells without a bot reads the overworld, and this scene's level is " + level.dimension());
        List<Block> blocks = new ArrayList<>();
        for (Block[] g : groups) for (Block b : g) if (!blocks.contains(b)) blocks.add(b);
        BlockPos start = ctx.origin().above(20);
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos at = start.east(i);
            cells.add(at);
            // A floor under the row, so sand and gravel stay where they were put instead of falling away.
            level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(at, blocks.get(i).defaultBlockState());
        }
        ctx.cleanup(() -> {
            for (BlockPos at : cells) {
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(at.below(), Blocks.AIR.defaultBlockState());
            }
        });
        BotDebugContracts.CellsReading reading = Calls.now(DebugApi.of(Calls.api()).cells(new BotDebugContracts.CellsParams(
                Optional.empty(), cells.stream().map(Targets::pos).toList(), Optional.empty())));
        Map<Block, BotDebugContracts.Cell> out = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) out.put(blocks.get(i), reading.cells().get(i));
        return out;
    }
}
