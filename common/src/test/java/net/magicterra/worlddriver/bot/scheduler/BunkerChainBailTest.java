package net.magicterra.worlddriver.bot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.magicterra.worlddriver.bot.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The bunker reflex's bid reads only health and hostiles, and a wet or unbreakable site changes
 * neither, so giving up without leaving the bid re-bid {@link Priorities#BUNKER} on the next tick
 * and retreat (100) and combat (60) never got the body. Giving up must bail through the scheduler.
 */
class BunkerChainBailTest {

    @Test
    void givingUpTakesTheChainOutOfTheBid() {
        ProcessScheduler sched = new ProcessScheduler();
        BunkerChain bunker = new BunkerChain();
        sched.register(bunker);
        bunker.anchorForTest().beginIfIdle(0, 64, 0);

        bunker.giveUp("water");

        ProcessScheduler.Bail b = sched.bailOf(bunker);
        assertNotNull(b, "a bunker that gave up must leave the bid");
        assertEquals("water", b.reason());
        assertEquals(BunkerChain.BAIL_COOLDOWN_TICKS, b.ticksLeft());
        assertFalse(bunker.anchorForTest().active(), "the site it gave up on is forgotten");
    }

    @Test
    void aStandaloneChainStillResetsItsEpisode() {
        BunkerChain bunker = new BunkerChain();
        bunker.anchorForTest().beginIfIdle(0, 64, 0);
        bunker.giveUp("unbreakable");
        assertNull(bunker.episodePhase());
    }

    @Test
    void aShorelineIsAnUnsafeDigSite() {
        GridWorldView w = new GridWorldView(-4, 56, -4, 9, 12, 9);
        w.fill(-4, 56, -4, 4, 63, 4, GridWorldView.SOLID);
        BlockPos foot = new BlockPos(0, 64, 0);
        assertNull(BunkerChain.unsafeDigSite(w, foot), "dry ground is diggable");
        w.set(0, 64, 0, GridWorldView.WATER);   // standing in one block of water at the shore
        assertEquals("water", BunkerChain.unsafeDigSite(w, foot));
        w.set(0, 64, 0, GridWorldView.AIR);
        w.set(0, 63, 0, GridWorldView.WATER);   // the block below is water
        assertEquals("water", BunkerChain.unsafeDigSite(w, foot));
    }
}
