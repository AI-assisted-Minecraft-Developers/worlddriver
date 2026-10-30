package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.stagewright.test.methods.StructuresContracts;
import net.magicterra.stagewright.test.methods.TestQueriesBlockingApi;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** mc.test.structures: registration, what covers a block, and a bounded locate. */
@Scene("wd.jupiterSmoke")
class JupiterStructuresTest {

    @Test
    void aVillageIsRegisteredAndANonsenseIdIsNot(Game game) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        assertTrue(assertInstanceOf(
                        StructuresContracts.IsRegistered.class,
                        queries.structures(new StructuresContracts.Registered("minecraft:village_plains")))
                .registered());
        assertFalse(assertInstanceOf(
                        StructuresContracts.IsRegistered.class,
                        queries.structures(new StructuresContracts.Registered("minecraft:no_such_structure")))
                .registered());
    }

    @Test
    void nothingCoversThePlot(Game game, Plot plot) {
        BlockPos at = plot.at(2, 1, 2);
        var covering = assertInstanceOf(
                StructuresContracts.Covering.class,
                game.api(TestQueriesBlockingApi::of)
                        .structures(new StructuresContracts.At(
                                new BlockPosition(at.getX(), at.getY(), at.getZ()), Optional.empty())));
        assertTrue(covering.structures().isEmpty(), "structures at the plot: " + covering.structures());
    }

    @Test
    void locateReportsItsTimeEvenWhenItFindsNothing(Game game, Plot plot) {
        BlockPos at = plot.at(2, 1, 2);
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        var located = assertInstanceOf(
                StructuresContracts.Located.class,
                queries.structures(new StructuresContracts.Locate(
                        "minecraft:village_plains",
                        new BlockPosition(at.getX(), at.getY(), at.getZ()),
                        2,
                        Optional.empty())));
        assertTrue(located.ms() >= 0, "ms");
        assertTrue(located.found() == located.pos().isPresent(), "pos present exactly when found");
        assertThrows(
                RuntimeException.class,
                () -> queries.structures(new StructuresContracts.Locate(
                        "minecraft:no_such_structure", new BlockPosition(0, 64, 0), 2, Optional.empty())));
    }
}
