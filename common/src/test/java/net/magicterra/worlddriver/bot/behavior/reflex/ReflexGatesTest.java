package net.magicterra.worlddriver.bot.behavior.reflex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pure decisions behind three reflexes: when to float a drowning idle bot ({@link DrowningFloatGate}), when the
 * active-process drowning escape latches, holds and lets go ({@link DrownEscapeGate}), and which damage the contact
 * escape steps away from ({@link ContactEscapeGate}).
 */
class ReflexGatesTest {

    @Test
    void drowningFloatFloatsOnlyUnderwaterAtLowAirWhenEnabled() {
        assertTrue(
                DrowningFloatGate.shouldFloat(true, 100, 100, true),
                "(a) underwater with air==threshold and enabled must float");
        assertFalse(
                DrowningFloatGate.shouldFloat(true, 300, 100, true),
                "(b) underwater with air well above threshold must NOT float");
        assertFalse(
                DrowningFloatGate.shouldFloat(false, 50, 100, true), "(c) low air but not underwater must NOT float");
        assertFalse(
                DrowningFloatGate.shouldFloat(true, 50, 100, false),
                "(d) autoFloatWhenDrowning=false must suppress even underwater+critical air");
    }

    @Test
    void drownEscapeLatchesHoldsThroughItsBandAndLetsGo() {
        // -------- entry --------
        assertTrue(
                DrownEscapeGate.next(false, true, 100, 101, 100, 280, true),
                "(a) underwater with air==enter threshold must latch");
        assertTrue(
                DrownEscapeGate.next(false, true, 40, 41, 100, 280, true),
                "(b) underwater well below the threshold must latch");
        assertFalse(
                DrownEscapeGate.next(false, true, 101, 102, 100, 280, true),
                "(c) air just above the threshold must NOT latch (planned crossings keep their reserve)");
        assertFalse(
                DrownEscapeGate.next(false, false, 50, 51, 100, 280, true),
                "(d) head out of water never ENTERS (nothing to escape)");
        assertFalse(
                DrownEscapeGate.next(false, true, 50, 51, 100, 280, false),
                "(e) autoDrownEscape=false must suppress entry");
        // -------- hold (the hysteresis band) --------
        assertTrue(
                DrownEscapeGate.next(true, true, 101, 100, 100, 280, true),
                "(f) latched + air back above the ENTRY threshold must STAY latched (no flap at 100)");
        assertTrue(
                DrownEscapeGate.next(true, true, 279, 278, 100, 280, true),
                "(g) latched underwater one below release must stay latched");
        assertTrue(
                DrownEscapeGate.next(true, false, 150, 150, 100, 280, true),
                "(h) head momentarily out but air NOT yet recovering must stay latched (surface bob)");
        // -------- release --------
        assertFalse(
                DrownEscapeGate.next(true, true, 280, 279, 100, 280, true),
                "(i) air >= release lets go even with the head still underwater");
        assertFalse(
                DrownEscapeGate.next(true, false, 154, 150, 100, 280, true),
                "(j) head out + air recovering releases early");
        assertTrue(
                DrownEscapeGate.next(true, false, 72, 68, 100, 280, true),
                "(n) root-pocket bob — head out + air climbing but still in the entry band must STAY latched "
                        + "(released at 68 → resumed dive → drowned)");
        assertTrue(
                DrownEscapeGate.next(true, false, 140, 136, 100, 280, true),
                "(o) head out + climbing exactly at enter+margin boundary stays latched");
        assertFalse(
                DrownEscapeGate.next(true, false, 141, 137, 100, 280, true),
                "(p) head out + climbing just past enter+margin releases");
        assertFalse(
                DrownEscapeGate.next(true, true, 60, 60, 100, 280, false),
                "(k) flipping autoDrownEscape off drops an existing latch");
        assertFalse(
                DrownEscapeGate.next(true, true, 300, 299, 100, 999, true),
                "(l) releaseAir above vanilla max 300 clamps (a full-air latch can't hold forever)");
        // -------- oscillation guard: jitter AROUND the entry threshold stays latched --------
        boolean latch = DrownEscapeGate.next(false, true, 100, 101, 100, 280, true);
        int prev = 100;
        for (int air : new int[] {99, 101, 100, 102, 98, 103}) {
            latch = DrownEscapeGate.next(latch, true, air, prev, 100, 280, true);
            assertTrue(latch, "(m) latch must hold through entry-threshold jitter (air=" + air + ")");
            prev = air;
        }
    }

    @Test
    void contactEscapeStepsAwayOnlyFromFreshBlockDamage() {
        // -------- fires: fresh damage from a step-away-able block --------
        assertTrue(
                ContactEscapeGate.shouldTrigger("cactus", 9, true),
                "(a) fresh cactus damage must trigger (the killer case)");
        assertTrue(ContactEscapeGate.shouldTrigger("sweetBerryBush", 5, true), "(b) berry bush contact must trigger");
        assertTrue(ContactEscapeGate.shouldTrigger("inFire", 8, true), "(c) standing in a fire block must trigger");
        assertTrue(ContactEscapeGate.shouldTrigger("hotFloor", 3, true), "(d) standing on magma must trigger");
        // -------- must NOT fire --------
        assertFalse(
                ContactEscapeGate.shouldTrigger("onFire", 9, true),
                "(e) burning AFTER leaving fire must NOT trigger (stepping away cannot help)");
        assertFalse(
                ContactEscapeGate.shouldTrigger("mob", 9, true),
                "(f) entity damage must NOT trigger (belongs to hurt-entry retreat attribution)");
        assertFalse(
                ContactEscapeGate.shouldTrigger("cactus", 0, true),
                "(g) stale attribution (hurtTime 0, ~40t last-damager tail) must NOT trigger");
        assertFalse(ContactEscapeGate.shouldTrigger(null, 9, true), "(h) no damage source must NOT trigger");
        assertFalse(ContactEscapeGate.shouldTrigger("cactus", 9, false), "(i) contactDamageEscape=false must suppress");
    }
}
