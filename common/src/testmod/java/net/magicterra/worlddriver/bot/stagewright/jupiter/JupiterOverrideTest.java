package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.stagewright.test.DependsOn;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.world.level.GameRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Overrides: refused without their lock, held for the test that made them, and undone when it ends. */
@Scene("wd.jupiterSmoke")
class JupiterOverrideTest {

    private static boolean fireTickBefore;

    @Test
    void anOverrideWithoutItsLockIsRefused(Game game) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> game.override(GameRules.RULE_DOFIRETICK, false));
        assertTrue(e.getMessage().contains("@ResourceLock(Locks.WORLD_RULES)"), e.getMessage());
    }

    @Test
    @ResourceLock(Locks.WORLD_RULES)
    void anOverrideHoldsForTheTest(Game game) {
        fireTickBefore = game.onServer(server -> server.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK));
        game.override(GameRules.RULE_DOFIRETICK, !fireTickBefore);
        game.override(GameRules.RULE_DOFIRETICK, fireTickBefore);
        game.override(GameRules.RULE_DOFIRETICK, !fireTickBefore);
        assertEquals(!fireTickBefore, game.onServer(server -> server.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)));
    }

    @Test
    @DependsOn("anOverrideHoldsForTheTest")
    @ResourceLock(Locks.WORLD_RULES)
    void andIsUndoneWhenItEnds(Game game) {
        assertEquals(fireTickBefore, game.onServer(server -> server.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)));
    }
}
