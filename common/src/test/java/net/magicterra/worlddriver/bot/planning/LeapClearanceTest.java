package net.magicterra.worlddriver.bot.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.magicterra.worlddriver.VanillaBoot;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.rules.moves.DiagonalAscend;
import net.magicterra.worlddriver.bot.rules.moves.Parkour2;
import net.magicterra.worlddriver.bot.rules.moves.Parkour2Diagonal;
import net.magicterra.worlddriver.bot.rules.moves.Parkour3;
import net.magicterra.worlddriver.bot.rules.moves.Parkour3Diagonal;
import net.magicterra.worlddriver.bot.rules.moves.Parkour4;
import net.magicterra.worlddriver.bot.rules.moves.ParkourAscend;
import net.magicterra.worlddriver.bot.rules.moves.ParkourDescend;
import net.magicterra.worlddriver.bot.rules.moves.ParkourPlace;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.RulesConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What every leaping move asks of its launch: the cell two above the launch foot passable and not a hazard, which a
 * rising body sweeps on any leap. Each move is staged as a legal leap first, so a refusal comes from the one cell
 * changed and not from the staging.
 */
class LeapClearanceTest {

    private static final BlockPos FROM = new BlockPos(0, 65, 0);

    private BotConfig.ConfigPin pin;

    @BeforeAll
    static void boot() {
        VanillaBoot.boot();
    }

    @BeforeEach
    void pinBaseline() {
        pin = BotConfig.pinnedBaseline();
        RulesConfig.allowParkour4 = true;
        RulesConfig.pathfinderParkourAscendNeedRunway = true;
        WorldConfig.allowParkourPlace = true;
    }

    @AfterEach
    void restore() {
        pin.close();
    }

    /** A move, how to stage its landing, and its direction of travel for the run-up behind the launch. */
    private record Leap(Move move, int sx, int sz, Consumer<BlockMapView> landing) {
        String name() {
            return move.name();
        }
    }

    private static Consumer<BlockMapView> padAt(int x, int y, int z) {
        return w -> w.set(x, y, z, Blocks.STONE);
    }

    private static List<Leap> leaps() {
        List<Leap> l = new ArrayList<>();
        l.add(new Leap(new Parkour2(1, 0), 1, 0, padAt(2, 64, 0)));
        l.add(new Leap(new Parkour3(1, 0), 1, 0, padAt(3, 64, 0)));
        l.add(new Leap(new Parkour4(1, 0), 1, 0, padAt(4, 64, 0)));
        l.add(new Leap(new Parkour2Diagonal(1, 1), 1, 1, padAt(2, 64, 2)));
        l.add(new Leap(new Parkour3Diagonal(1, 1), 1, 1, padAt(3, 64, 3)));
        // Parkour-place lands on a block it places: the cell under the landing stays open, with a
        // neighbour to place it against.
        l.add(new Leap(new ParkourPlace(1, 0), 1, 0, padAt(3, 64, 0)));
        l.add(new Leap(new ParkourAscend(1, 0, 2), 1, 0, padAt(2, 65, 0)));
        l.add(new Leap(new ParkourAscend(1, 0, 3), 1, 0, padAt(3, 65, 0)));
        l.add(new Leap(new ParkourDescend(1, 0, 2, 1), 1, 0, padAt(2, 63, 0)));
        l.add(new Leap(new ParkourDescend(1, 0, 3, 1), 1, 0, padAt(3, 63, 0)));
        l.add(new Leap(new DiagonalAscend(1, 1), 1, 1, padAt(1, 65, 1)));
        return l;
    }

    /**
     * A floor three below the launch, so no gap is bottomless; the launch pad; {@code runUp} cells of standable ground
     * behind it; the move's landing.
     */
    private static BlockMapView stage(Leap leap, int runUp) {
        BlockMapView w = new BlockMapView();
        w.fill(-4, 62, -4, 6, 62, 6, Blocks.STONE);
        w.set(0, 64, 0, Blocks.STONE);
        for (int i = 1; i <= runUp; i++) w.set(-leap.sx() * i, 64, -leap.sz() * i, Blocks.STONE);
        leap.landing().accept(w);
        w.give(new ItemStack(Items.COBBLESTONE, 64));
        return w;
    }

    /**
     * Names of the leaps whose {@code valid} differs from {@code want}, staged with {@code runUp} cells behind the
     * launch and {@code overhead} (if not null) two above the launch foot.
     */
    private static List<String> disagreeing(int runUp, Block overhead, boolean want) {
        List<String> wrong = new ArrayList<>();
        for (Leap leap : leaps()) {
            BlockMapView w = stage(leap, runUp);
            if (overhead != null) w.set(FROM.above(2), overhead.defaultBlockState());
            if (leap.move().valid(w, FROM) != want) wrong.add(leap.name());
        }
        return wrong;
    }

    @Test
    void everyStagedLeapIsLegal() {
        assertEquals(List.of(), disagreeing(2, null, true), "the staging is not a legal leap for these");
    }

    @Test
    void fireTwoAboveTheLaunchRefusesEveryLeap() {
        assertEquals(List.of(), disagreeing(2, Blocks.FIRE, false), "these leap with fire over the launch head");
    }

    @Test
    void powderSnowTwoAboveTheLaunchRefusesEveryLeap() {
        assertEquals(
                List.of(),
                disagreeing(2, Blocks.POWDER_SNOW, false),
                "these leap with powder snow over the launch head");
    }

    /**
     * Cells of standable ground each leap needs behind its launch. A 2-block gap is inside a standing jump, so the
     * short leaps need none; a rising 2-block leap needs one; every leap longer than 2 needs {@link Move#RUNUP_CELLS},
     * the sprint run-up its price assumes.
     */
    private static int runUpNeeded(String name) {
        return switch (name) {
            case "parkour2", "parkour2d", "parkourPlace2", "parkourDescend2d1", "diagUp" -> 0;
            case "parkourAscend2" -> 1;
            case "parkour3", "parkour4", "parkour3d", "parkourAscend3", "parkourDescend3d1" -> Move.RUNUP_CELLS;
            default -> throw new IllegalArgumentException("no run-up rule pinned for " + name);
        };
    }

    @Test
    void eachLeapNeedsItsRunUpAndNoMore() {
        List<String> wrong = new ArrayList<>();
        for (Leap leap : leaps()) {
            int need = runUpNeeded(leap.name());
            if (!leap.move().valid(stage(leap, need), FROM)) wrong.add(leap.name() + " refused with " + need);
            if (need > 0 && leap.move().valid(stage(leap, need - 1), FROM))
                wrong.add(leap.name() + " allowed with " + (need - 1));
        }
        assertEquals(List.of(), wrong, "run-up cells behind the launch");
    }
}
