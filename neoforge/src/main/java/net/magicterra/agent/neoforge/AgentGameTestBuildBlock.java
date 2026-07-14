package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
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
}
