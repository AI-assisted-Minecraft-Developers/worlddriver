package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;

/**
 * What a recipe plan is planned against when nobody's bag can be read (gap #44). The bag itself, read when
 * {@code have} is omitted for a bot, is held by the scene {@code wd.serverPlanHaveDefaultsToBag}.
 */
class RecipeServiceHaveTest {

    @Test
    void nobodyToReadABagFromPlansAgainstNothing() {
        assertTrue(RecipeService.resolveHave(Optional.empty(), (Player) null).isEmpty());
    }

    @Test
    void anExplicitHaveIsUsedVerbatimEvenWithNobody() {
        assertEquals(Map.of("minecraft:cobblestone", 3),
                RecipeService.resolveHave(Optional.of(Map.of("minecraft:cobblestone", 3)), (Player) null));
        assertTrue(RecipeService.resolveHave(Optional.of(Map.of()), (Player) null).isEmpty());
    }
}
