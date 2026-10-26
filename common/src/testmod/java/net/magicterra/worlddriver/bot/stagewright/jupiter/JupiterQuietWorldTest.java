package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.world.level.GameRules;
import org.junit.jupiter.api.Test;

/** A scene that declares no world gets the quiet one: midnight with the clock stopped, clear, nothing spawning. */
@Scene("wd.jupiterSmoke")
class JupiterQuietWorldTest {

    @Test
    void anUndeclaredWorldIsTheQuietOne(Game game) {
        game.ticks(20);
        long time = game.onServer(s -> s.overworld().getDayTime() % 24000);
        assertEquals(18000L, time, "midnight, and still midnight twenty ticks later");
        boolean raining = game.onServer(s -> s.getWorldData().overworldData().isRaining());
        assertFalse(raining);
        boolean spawning = game.onServer(s -> s.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING));
        assertFalse(spawning);
    }
}
