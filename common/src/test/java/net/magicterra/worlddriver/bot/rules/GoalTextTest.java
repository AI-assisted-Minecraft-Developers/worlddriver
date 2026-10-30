package net.magicterra.worlddriver.bot.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.magicterra.worlddriver.game.observe.CellText;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A goal's text is what {@code mc.bot.goto} and the process slots answer as {@code goal}; it must print positions as
 * plain numbers, never through {@code BlockPos#toString}, which names the class and so reads {@code class_2338{x=6, …}}
 * in a Fabric production game.
 */
class GoalTextTest {

    private static final BlockPos AT = new BlockPos(6, 64, -3);

    @Test
    void aCellIsItsThreeCoordinates() {
        assertEquals("6 64 -3", CellText.of(AT));
        assertEquals("null", CellText.of(null));
    }

    @Test
    void everyGoalHoldingACellPrintsItAsNumbers() {
        assertEquals("Block[target=6 64 -3]", new Goal.Block(AT).toString());
        assertEquals("Near[target=6 64 -3, radius=2]", new Goal.Near(AT, 2).toString());
        assertEquals("RunAway[source=6 64 -3, minDist=16]", new Goal.RunAway(AT, 16).toString());
        assertEquals("GetToBlock[target=6 64 -3]", new Goal.GetToBlock(AT).toString());
        assertEquals("TwoBlocks[target=6 64 -3]", new Goal.TwoBlocks(AT).toString());
        assertEquals("StrictDirection[origin=6 64 -3, dx=1, dz=0]", new Goal.StrictDirection(AT, 1, 0).toString());
        assertEquals("Inverted[origin=Block[target=6 64 -3]]", new Goal.Inverted(new Goal.Block(AT)).toString());
    }

    @Test
    void aCompositePrintsItsChildrenNotTheArray() {
        Goal both = new Goal.Composite(new Goal[] {new Goal.Block(AT), new Goal.XZ(1, 2)});
        assertEquals("Composite[Block[target=6 64 -3], XZ[x=1, z=2, radius=0]]", both.toString());
    }
}
