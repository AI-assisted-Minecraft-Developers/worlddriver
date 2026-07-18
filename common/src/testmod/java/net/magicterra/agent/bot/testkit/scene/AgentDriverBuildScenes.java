package net.magicterra.agent.bot.testkit.scene;

import java.util.List;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded agent-driver scenes — <b>P4b wave 5, the BuildBlock family</b>: the 2
 * {@code AgentGameTestBuildBlock} probes (interactive-block rejection + valuable-placement
 * matrix), migrated verbatim to testkit {@code ad.*} scenes and their legacy twin class
 * retired in the same commit.
 *
 * <p><b>Both are pure {@link BotConfig} predicate probes — planner/API-only, no world,
 * no avatar, no {@link net.magicterra.agent.bot.movement.Walker} drive.</b> Each builds
 * nothing in the level: it calls {@code BotConfig.isUsableBuildBlock} /
 * {@code isValuablePlacementBlock} / {@code isThrowawaySupportBlock} on a fixed block/item
 * matrix and asserts the classification. So there is no {@code SceneContext.origin()}
 * geometry, no {@code ServerPlayerAvatar}, no config pin, and the body resolves on the
 * first RUN tick (the harness restores {@code BotConfig} baseline between scenes, and these
 * scenes never mutate it). Porting substitutions (canonical pattern —
 * {@link AgentDriverTerrainScenes} class javadoc): {@code throw new GameTestAssertException}
 * → {@link SceneContext#fail}; {@code helper.succeed()} → return; the {@code gtSkip(...)}
 * probe line → deleted (the testkit gate self-reconciles).
 *
 * <p><b>Dedicated-server safety carried over verbatim.</b> {@code valuablePlacementBlockMatrix}
 * deliberately exercises the dist-neutral {@link BotConfig#isThrowawaySupportBlock} core rather
 * than {@code BotInteract.isThrowawaySupportBlock} — merely loading {@code BotInteract.class} on a
 * dedicated server fails (it mixes in client-only {@code LocalPlayer}/{@code Minecraft} methods,
 * refused by NeoForge's {@code RuntimeDistCleaner} for the DEDICATED_SERVER dist). The dogfood
 * harness runs on a plain dedicated server, so the same rule applies; the legacy comment is kept.
 */
public final class AgentDriverBuildScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("ad.buildBlockRejectsInteractive", 200, AgentDriverBuildScenes::buildBlockRejectsInteractive),
                Scene.of("ad.valuablePlacementBlockMatrix", 200, AgentDriverBuildScenes::valuablePlacementBlockMatrix));
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
        for (Block b : mustReject)
            if (BotConfig.isUsableBuildBlock(b))
                ctx.fail("buildBlockRejectsInteractive: interactive block accepted as build material: " + b);
        for (Block b : mustAccept)
            if (!BotConfig.isUsableBuildBlock(b))
                ctx.fail("buildBlockRejectsInteractive: plain block wrongly rejected: " + b);
        AgentDriverCommon.LOG.info("[ad.buildBlockRejectsInteractive] all {} interactive rejected, {} plain accepted",
                mustReject.length, mustAccept.length);
    }

    /** Ported from {@code AgentGameTestBuildBlock#valuablePlacementBlockMatrix} (gap #81): the
     *  value-awareness layer ON TOP of {@link BotConfig#isUsableBuildBlock} flags gathered-wood
     *  resources so the throwaway-only picker skips them, WITHOUT touching the gap #57 build-block
     *  predicate (OAK_PLANKS must stay a usable build block). */
    private static void valuablePlacementBlockMatrix(SceneContext ctx) {
        Block[] mustBeValuable = {
                Blocks.OAK_LOG, Blocks.ACACIA_LOG, Blocks.SPRUCE_LOG, Blocks.STRIPPED_OAK_LOG,
                Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS,
        };
        Block[] mustNotBeValuable = {
                Blocks.DIRT, Blocks.COBBLESTONE, Blocks.STONE, Blocks.GRAVEL, Blocks.SAND, Blocks.MUD,
        };
        for (Block b : mustBeValuable)
            if (!BotConfig.isValuablePlacementBlock(b))
                ctx.fail("valuablePlacementBlockMatrix: gathered-wood block wrongly NOT flagged valuable: " + b);
        for (Block b : mustNotBeValuable)
            if (BotConfig.isValuablePlacementBlock(b))
                ctx.fail("valuablePlacementBlockMatrix: plain block wrongly flagged valuable: " + b);

        // Regression guard: value-awareness must NOT touch the existing build-block predicate
        // (gap #57 — OAK_PLANKS stays a usable build block for explicit build/construct commands).
        if (!BotConfig.isUsableBuildBlock(Blocks.OAK_PLANKS))
            ctx.fail("valuablePlacementBlockMatrix: regression: OAK_PLANKS no longer a usable build block");
        if (!BotConfig.isUsableBuildBlock(Blocks.DIRT))
            ctx.fail("valuablePlacementBlockMatrix: regression: DIRT no longer a usable build block");

        // Exercised via BotConfig.isThrowawaySupportBlock (the dist-neutral core), NOT
        // BotInteract.isThrowawaySupportBlock — merely loading BotInteract.class fails on a
        // dedicated server (client-only LocalPlayer/Minecraft mixins; RuntimeDistCleaner refuses
        // the whole class for DEDICATED_SERVER dist). BotInteract delegates to this same core.
        if (!BotConfig.isThrowawaySupportBlock(new ItemStack(Blocks.DIRT.asItem())))
            ctx.fail("valuablePlacementBlockMatrix: DIRT wrongly rejected as throwaway support");
        if (BotConfig.isThrowawaySupportBlock(new ItemStack(Blocks.OAK_LOG.asItem())))
            ctx.fail("valuablePlacementBlockMatrix: OAK_LOG wrongly accepted as throwaway support (valuable)");
        if (BotConfig.isThrowawaySupportBlock(new ItemStack(Blocks.OAK_PLANKS.asItem())))
            ctx.fail("valuablePlacementBlockMatrix: OAK_PLANKS wrongly accepted as throwaway support (valuable)");

        AgentDriverCommon.LOG.info("[ad.valuablePlacementBlockMatrix] {} valuable, {} non-valuable, throwaway matrix all pass",
                mustBeValuable.length, mustNotBeValuable.length);
    }
}
