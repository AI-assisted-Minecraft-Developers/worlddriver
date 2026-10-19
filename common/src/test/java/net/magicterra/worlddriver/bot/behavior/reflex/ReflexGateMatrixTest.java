package net.magicterra.worlddriver.bot.behavior.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The anti-suffocation trigger and the hotbar picker's grace for an external slot change, row by row. */
class ReflexGateMatrixTest {

    @Test
    void antiSuffocateTriggersOnEitherSignal() {
        // (a) THE death-#16 case: client geometry says NOT in a wall, but the server
        // damage attribution says we ARE taking inWall damage — must trigger.
        assertTrue(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true),
                "gap#69(a): isInWall=false but lastDamage=inWall must trigger (death #16 desync)");
        // (b) existing behavior preserved: client geometry alone still triggers.
        assertTrue(AntiSuffocateGate.shouldTrigger(true, null, true, true),
                "gap#69(b): isInWall=true (no damage signal yet) must still trigger");
        // (c) negative control: neither signal present → must NOT trigger.
        assertFalse(AntiSuffocateGate.shouldTrigger(false, null, true, true),
                "gap#69(c): no isInWall and no inWall damage must NOT trigger");
        // (d) allowBreak gate preserved: even with the damage fallback firing, a
        // disabled allowBreak must still suppress the reflex (it mines the block).
        assertFalse(AntiSuffocateGate.shouldTrigger(false, "inWall", true, false),
                "gap#69(d): allowBreak=false must suppress even the damage fallback");
        // (e) antiSuffocate config gate preserved: cfg off must suppress everything.
        assertFalse(AntiSuffocateGate.shouldTrigger(true, "inWall", false, true),
                "gap#69(e): antiSuffocate=false must suppress even isInWall+damage both true");
        // (f) an unrelated damage source (e.g. a mob hit) must NOT trigger the reflex.
        assertFalse(AntiSuffocateGate.shouldTrigger(false, "mob", true, true),
                "gap#69(f): a non-inWall lastDamage msgId must NOT trigger");

        // M1: resolveHead's foot/horizontal fallback branches additionally require hurtTime>0 —
        // shouldTrigger stays a coarse ~40t damage-window gate, but the proximity fallback must go
        // quiet as soon as the bot is actually freed, well before the 40t window itself lapses.
        // (g) a real, ongoing desync burial: damage signal AND hurtTime hot → fallback stays armed.
        assertTrue(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true)
                        && AntiSuffocateGate.allowProximityFallback(10),
                "M1(g): damage-signal fresh (death-#16 desync) AND hurtTime=10 (still being hurt) must arm the fallback branches");
        // (h) THE M1 case: damage signal still fresh but hurtTime already 0 (freed) → the fallback
        // must NOT arm, even though shouldTrigger itself is still true.
        assertTrue(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true)
                        && !AntiSuffocateGate.allowProximityFallback(0),
                "M1(h): damage-signal fresh but hurtTime==0 (freed) must NOT arm foot/horizontal fallback (eye/above-only)");
    }

    @Test
    void autoToolYieldsToAnExternalSelection() {
        // gap#68-⑪: an external setHotbarSlot call (or a human scroll) must not be clobbered on the
        // very next tick — if the live selection differs from the slot AutoTool itself last wrote,
        // honor the external change for a grace period instead of overwriting it.
        assertTrue(AutoTool.shouldYield(2, 7, 0), "selected(2) != lastAuto(7) = external change -> yield");
        assertFalse(AutoTool.shouldYield(7, 7, 0), "no external change, no grace -> proceed");
        assertTrue(AutoTool.shouldYield(7, 7, 10), "grace still counting -> yield");
        assertFalse(AutoTool.shouldYield(2, -1, 0), "first tick (no lastAuto yet) -> proceed");
        // Arm/decrement/expire sequence (stepGrace): an armed grace of N yields N ticks total, and a
        // zero/negative config must clamp to a single-tick grace that EXPIRES (returns 0), not load 0
        // and decrement to -1 (which skips the ==0 expiry forever and wedges AutoTool in a permanent yield).
        assertEquals(99, AutoTool.stepGrace(0, 100), "fresh arm with config 100 -> 99 left after this tick");
        assertEquals(0, AutoTool.stepGrace(1, 100), "last armed tick -> 0 = expired, re-arms cleanly");
        assertEquals(0, AutoTool.stepGrace(0, 0), "config 0 clamps to single-tick grace that expires (no -1 wedge)");
        assertEquals(0, AutoTool.stepGrace(0, -7), "negative config clamps to single-tick grace that expires (no wedge)");
    }
}
