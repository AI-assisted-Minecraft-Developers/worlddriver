package net.magicterra.worlddriver.bot.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The ascent machine's dead-zone watchdog: a body that makes no progress on an ascent edge gives the
 * edge up after {@link AscendMovement#DEADZONE_GIVEUP} ticks, an active dig never runs that clock, and
 * only a new height record resets it. The body is posed by hand; the machine reads nothing but its
 * position, the edge and the dig flag.
 */
class AscendMovementTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final int GIVE_UP = AscendMovement.DEADZONE_GIVEUP;
    /** The stand-cell node one up and two across from the feet, with nothing pushing the body toward it. */
    private static final BlockPos NODE = new BlockPos(2, 2, 0);
    private static final Move.Edge EDGE = new Move.Edge(NODE, 10, List.of(), List.of(), "stepUp");

    private final GridWorldView world = new GridWorldView(-3, 0, -3, 7, 8, 7);
    private final ArmorStand body = new ArmorStand(EntityType.ARMOR_STAND, null);
    private final AscendMovement machine = new AscendMovement();

    private MovementStatus tick(boolean digging) {
        return machine.updateState(new MovementContext(body, world, null, EDGE, body.blockPosition(), NODE, 1, 1,
                null, null, digging));
    }

    @Test
    void aStuckBodyGivesTheEdgeUpAndTheNextTickStartsAFreshEpisode() {
        body.setPos(0.5, 1, 0.5);
        assertEquals(MovementStatus.PREP, tick(false), "a fresh episode");
        for (int t = 1; t <= GIVE_UP; t++)
            assertEquals(MovementStatus.RUNNING, tick(false), "no-progress tick " + t + "/" + GIVE_UP);
        assertEquals(MovementStatus.UNREACHABLE, tick(false), "one tick past the budget");
        assertEquals(MovementStatus.PREP, tick(false), "the give-up clears the episode");
    }

    @Test
    void digTicksNeverCountAndTheClockRestartsInFullWhenTheDigEnds() {
        body.setPos(0.5, 1, 0.5);
        tick(false);
        for (int t = 0; t < GIVE_UP + 30; t++)
            assertEquals(MovementStatus.RUNNING, tick(true), "digging tick " + t);
        for (int t = 1; t <= GIVE_UP; t++)
            assertEquals(MovementStatus.RUNNING, tick(false), "post-dig tick " + t);
        assertEquals(MovementStatus.UNREACHABLE, tick(false), "one tick past the post-dig budget");
    }

    @Test
    void aRiseResetsTheClockAndReachingTheSameApexAgainDoesNot() {
        body.setPos(0.5, 1, 0.5);
        double y0 = body.getY();
        tick(false);
        for (int t = 0; t < GIVE_UP - 10; t++) tick(false);
        body.setPos(body.getX(), y0 + 0.6, body.getZ());
        assertEquals(MovementStatus.RUNNING, tick(false), "the rise tick");
        body.setPos(body.getX(), y0, body.getZ());
        for (int t = 1; t <= GIVE_UP; t++) {
            if (t == 20) body.setPos(body.getX(), y0 + 0.6, body.getZ());
            if (t == 21) body.setPos(body.getX(), y0, body.getZ());
            assertEquals(MovementStatus.RUNNING, tick(false), "post-rise tick " + t);
        }
        assertEquals(MovementStatus.UNREACHABLE, tick(false), "a bob back to the same apex must not reset the clock");
    }
}
