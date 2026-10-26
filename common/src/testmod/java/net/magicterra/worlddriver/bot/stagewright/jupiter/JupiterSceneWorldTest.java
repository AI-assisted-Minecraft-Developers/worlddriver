package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/** The world wd.jupiterDeclaredWorld.yaml declares, setting by setting, as the game ended up with it. */
@Scene("wd.jupiterDeclaredWorld")
class JupiterSceneWorldTest {

    @Test
    void theWorldWasCreatedAsDeclared(Game game) {
        long seed = game.onServer(s -> s.getWorldData().worldGenOptions().seed());
        assertEquals(5471L, seed);
        boolean structures = game.onServer(s -> s.getWorldData().worldGenOptions().generateStructures());
        assertFalse(structures, "structures were turned off");
        Difficulty difficulty = game.onServer(s -> s.getWorldData().getDifficulty());
        assertEquals(Difficulty.HARD, difficulty);
        // The flat preset's default layers: bedrock, two of dirt, grass on top.
        boolean bedrock = game.onServer(s -> s.overworld().getBlockState(new BlockPos(0, -64, 0)).is(Blocks.BEDROCK));
        boolean grass = game.onServer(s -> s.overworld().getBlockState(new BlockPos(0, -61, 0)).is(Blocks.GRASS_BLOCK));
        assertTrue(bedrock && grass, "the overworld is flat");
        boolean pack = game.onServer(s -> s.getPackRepository().getSelectedIds().contains("trade_rebalance"));
        assertTrue(pack, "the experimental trade rebalance pack is on");
    }

    @Test
    void theLoadedWorldHasTheDeclaredRulesClockAndWeather(Game game) {
        boolean keepInventory = game.onServer(s -> s.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY));
        assertTrue(keepInventory);
        int randomTickSpeed = game.onServer(s -> s.getGameRules().getInt(GameRules.RULE_RANDOMTICKING));
        assertEquals(0, randomTickSpeed);
        game.ticks(20);
        long time = game.onServer(s -> s.overworld().getDayTime() % 24000);
        assertEquals(6000L, time, "noon, and still noon twenty ticks later");
        boolean raining = game.onServer(s -> s.getWorldData().overworldData().isRaining());
        assertTrue(raining);
        boolean weatherCycle = game.onServer(s -> s.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE));
        assertFalse(weatherCycle, "the weather is frozen");
    }
}
