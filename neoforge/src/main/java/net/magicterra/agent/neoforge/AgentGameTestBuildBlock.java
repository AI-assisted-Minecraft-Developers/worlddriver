package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Gap #57: the walker's build-block picker treated ANY sturdy full block as bridge
 * material and spent the bot's freshly-crafted FURNACE as a plug (live 2026-07-13);
 * the follow-up place-click against that furnace then opened its GUI and paralysed
 * the engine (gap #58's trigger). Interactive blocks — block-entity holders and the
 * menu-opening work-station family — are resources, not dirt: the picker must
 * reject them no matter how sturdy they are.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTestBuildBlock {
    private AgentGameTestBuildBlock() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void buildBlockRejectsInteractiveArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtSkip(helper, "buildBlockRejectsInteractiveArena")) return; // gt-filter
        Block[] mustReject = {
                Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CHEST,
                Blocks.BARREL, Blocks.CRAFTING_TABLE, Blocks.SMITHING_TABLE,
                Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.LOOM,
        };
        Block[] mustAccept = { Blocks.COBBLESTONE, Blocks.DIRT, Blocks.MUD, Blocks.OAK_PLANKS };
        for (Block b : mustReject)
            if (BotConfig.isUsableBuildBlock(b))
                throw new GameTestAssertException("interactive block accepted as build material: " + b);
        for (Block b : mustAccept)
            if (!BotConfig.isUsableBuildBlock(b))
                throw new GameTestAssertException("plain block wrongly rejected: " + b);
        AgentDriverCommon.LOG.info("[buildBlockRejectsInteractiveArena] all {} interactive rejected, {} plain accepted",
                mustReject.length, mustAccept.length);
        helper.succeed();
    }

    /**
     * Gap #81: the walker's ROUTINE pillar-up actuator has zero value-awareness and will
     * spend a bot's gathered wood (logs/planks) as disposable scaffold filler over open air
     * (live 2026-07-15: an acacia_log — the bot's only wood — got pillared away). Value
     * awareness is a layer ON TOP of {@link BotConfig#isUsableBuildBlock} /
     * {@link BotConfig#isUsablePillarBlock} (unchanged, gap #57 depends on them — OAK_PLANKS
     * must stay a usable build block) that additionally flags gathered-wood resources so the
     * throwaway-only picker can skip them.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void valuablePlacementBlockMatrix(GameTestHelper helper) {
        if (AgentGameTestSupport.gtSkip(helper, "valuablePlacementBlockMatrix")) return; // gt-filter
        Block[] mustBeValuable = {
                Blocks.OAK_LOG, Blocks.ACACIA_LOG, Blocks.SPRUCE_LOG, Blocks.STRIPPED_OAK_LOG,
                Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS,
        };
        Block[] mustNotBeValuable = {
                Blocks.DIRT, Blocks.COBBLESTONE, Blocks.STONE, Blocks.GRAVEL, Blocks.SAND, Blocks.MUD,
        };
        for (Block b : mustBeValuable)
            if (!BotConfig.isValuablePlacementBlock(b))
                throw new GameTestAssertException("gathered-wood block wrongly NOT flagged valuable: " + b);
        for (Block b : mustNotBeValuable)
            if (BotConfig.isValuablePlacementBlock(b))
                throw new GameTestAssertException("plain block wrongly flagged valuable: " + b);

        // Regression guard: value-awareness must NOT touch the existing build-block predicate
        // (gap #57 — OAK_PLANKS stays a usable build block for explicit build/construct commands).
        if (!BotConfig.isUsableBuildBlock(Blocks.OAK_PLANKS))
            throw new GameTestAssertException("regression: OAK_PLANKS no longer a usable build block");
        if (!BotConfig.isUsableBuildBlock(Blocks.DIRT))
            throw new GameTestAssertException("regression: DIRT no longer a usable build block");

        // Note: exercised via BotConfig.isThrowawaySupportBlock (the dist-neutral core), NOT
        // BotInteract.isThrowawaySupportBlock — merely loading BotInteract.class fails on a
        // dedicated server (it mixes in unrelated client-only LocalPlayer/Minecraft methods;
        // NeoForge's RuntimeDistCleaner refuses the whole class for DEDICATED_SERVER dist).
        // BotInteract.isThrowawaySupportBlock delegates to this same core for production use.
        if (!BotConfig.isThrowawaySupportBlock(new ItemStack(Blocks.DIRT.asItem())))
            throw new GameTestAssertException("DIRT wrongly rejected as throwaway support");
        if (BotConfig.isThrowawaySupportBlock(new ItemStack(Blocks.OAK_LOG.asItem())))
            throw new GameTestAssertException("OAK_LOG wrongly accepted as throwaway support (valuable)");
        if (BotConfig.isThrowawaySupportBlock(new ItemStack(Blocks.OAK_PLANKS.asItem())))
            throw new GameTestAssertException("OAK_PLANKS wrongly accepted as throwaway support (valuable)");

        AgentDriverCommon.LOG.info("[valuablePlacementBlockMatrix] {} valuable, {} non-valuable, throwaway matrix all pass",
                mustBeValuable.length, mustNotBeValuable.length);
        helper.succeed();
    }
}
