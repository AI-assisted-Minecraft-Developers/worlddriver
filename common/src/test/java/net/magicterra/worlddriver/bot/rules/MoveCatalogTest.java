package net.magicterra.worlddriver.bot.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.magicterra.worlddriver.game.body.Locomotion;
import org.junit.jupiter.api.Test;

/**
 * The per-mode catalogs are a partition of {@link Move#ALL}, and a walking-and-swimming body gets all
 * of it in its original order — the search breaks ties by insertion, so a reordered catalog would be a
 * routing change, not a refactor.
 */
class MoveCatalogTest {

    @Test
    void aPlayersCatalogIsEveryMoveInOrder() {
        assertEquals(Move.ALL, MoveCatalog.forModes(Locomotion.WALK_AND_SWIM));
    }

    @Test
    void theModesPartitionEveryMove() {
        int total = 0;
        for (Locomotion.Mode mode : Locomotion.Mode.values()) {
            for (Move m : MoveCatalog.of(mode)) assertEquals(mode, m.mode(), m.name());
            total += MoveCatalog.of(mode).size();
        }
        assertEquals(Move.ALL.size(), total);
    }

    @Test
    void swimmingHoldsTheMovesThatStartInWater() {
        List<String> names = new ArrayList<>();
        for (Move m : MoveCatalog.of(Locomotion.Mode.SWIM)) if (!names.contains(m.name())) names.add(m.name());
        assertEquals(List.of("swimUp", "swimDown", "climbOutPlace", "swimDownSurface", "swimAshore",
                "swimAshoreClimb", "swimTraverseBreak", "swimUpBreak"), names);
    }

    @Test
    void flyingAndRidingAreEmptyForNow() {
        assertTrue(MoveCatalog.of(Locomotion.Mode.FLY).isEmpty());
        assertTrue(MoveCatalog.of(Locomotion.Mode.RIDE).isEmpty());
        assertEquals(MoveCatalog.of(Locomotion.Mode.WALK), MoveCatalog.forModes(EnumSet.of(Locomotion.Mode.WALK, Locomotion.Mode.FLY)));
    }
}
