package net.magicterra.worlddriver.bot.arbitration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.behavior.BunkerProcess;
import net.magicterra.worlddriver.bot.world.HazardField;
import net.magicterra.worlddriver.bot.world.SurvivalFacts;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * duskSecure holds a BunkerProcess (SEALED-hold: active=true endReason=SEALED, "holding position") and a
 * higher chain preempts it. If onInterrupt only cleared {@code process}, the slot's only reset point
 * (BunkerProcess.finish) would become unreachable and mc.bot.status would report a stale bunker
 * indefinitely. Interrupt/cancel of a chain-held process therefore goes through the same finish/slot-reset
 * lifecycle as a natural completion, with a distinguishable endReason (INTERRUPTED vs CANCELLED vs SEALED).
 * The client key release is not exercised here.
 */
class DuskSecureLifecycleTest {

    @Test
    void interruptingASealedHoldResetsTheSlot() {
        BotState st = new BotState();
        DuskSecureChain chain = new DuskSecureChain(st, new WorldModel());
        BunkerProcess held = new BunkerProcess(3);
        held.attach(st);                       // active=true, exactly what tick() does
        st.bunker.endReason = "SEALED";        // what the SEALED-hold branch stamps in place
        st.bunker.goalReached = true;
        chain.adoptProcessForTest(held);
        chain.interruptEpisodeState("retreat");
        assertNull(chain.heldProcessForTest(), "interrupt drops the held process");
        assertFalse(st.bunker.active, "interrupt: bunker slot must not stay active (orphan) — active=" + st.bunker.active);
        assertEquals("INTERRUPTED", st.bunker.endReason,
                "interrupt: endReason must be INTERRUPTED, not stale SEALED — got " + st.bunker.endReason);
        assertEquals(Boolean.TRUE, st.bunker.goalReached,
                "interrupt: the sealed verdict (goalReached=true) is history, keep it readable");
    }

    @Test
    void cancellingAMidDigBunkerRecordsWhy() {
        BotState st2 = new BotState();
        DuskSecureChain chain2 = new DuskSecureChain(st2, new WorldModel());
        BunkerProcess held2 = new BunkerProcess(3);
        held2.attach(st2);
        chain2.adoptProcessForTest(held2);
        chain2.cancelEpisodeState("agent cancel");
        assertNull(chain2.heldProcessForTest(), "cancel drops the held process");
        assertFalse(st2.bunker.active, "cancel: bunker slot must not stay active");
        assertEquals("CANCELLED", st2.bunker.endReason, "cancel: endReason must be CANCELLED — got " + st2.bunker.endReason);
        assertEquals("agent cancel", st2.bunker.lastError, "cancel: reason recorded on lastError — got " + st2.bunker.lastError);
    }

    /** st.bunker is SHARED with the user verb mc.bot.bunker: with no held process, duskSecure must not stomp it. */
    @Test
    void withNothingHeldAUserBunkerSlotIsLeftAlone() {
        BotState st3 = new BotState();
        DuskSecureChain chain3 = new DuskSecureChain(st3, new WorldModel());
        new BunkerProcess(3).attach(st3);      // user-verb bunker owns the slot
        st3.bunker.endReason = "SEALED";
        chain3.cancelEpisodeState("stray cancel");
        assertTrue(st3.bunker.active, "no held process: cancel must NOT reset the user-verb bunker's slot");
        assertEquals("SEALED", st3.bunker.endReason, "no held process: user slot endReason untouched — got " + st3.bunker.endReason);
        chain3.interruptEpisodeState("retreat");
        assertTrue(st3.bunker.active, "no held process: interrupt must NOT reset the user-verb bunker's slot");
    }

    /**
     * The shared helper itself: onCancelled fires with the detail (the finalize hook UserTaskChain.cancel
     * runs), a null process is a no-op, and an already-inactive slot is left untouched.
     */
    @Test
    void dropRunsTheFinalizeHookAndStampsOnlyALiveSlot() {
        BotState st4 = new BotState();
        AtomicReference<String> cancelledWith = new AtomicReference<>();
        BotProcess probe = new BotProcess() {
            @Override public String kind() { return "probe"; }
            @Override public String failure() { return null; }
            @Override public void attach(BotState s) {}
            @Override public boolean tick(Body a, WorldView w, BotState s) { return false; }
            @Override public void onCancelled(String reason) { cancelledWith.set(reason); }
        };
        st4.bunker.active = true;
        BotProcess dropped = ChainProcessLifecycle.drop(probe, st4.bunker,
                ChainProcessLifecycle.INTERRUPTED, "preempted by retreat");
        assertNull(dropped, "drop returns null for assignment");
        assertEquals("preempted by retreat", cancelledWith.get(),
                "drop must run the process's onCancelled finalize hook — got " + cancelledWith.get());
        assertTrue(!st4.bunker.active && "INTERRUPTED".equals(st4.bunker.endReason), "drop stamps+resets a live slot");
        assertNull(ChainProcessLifecycle.drop(null, st4.bunker, ChainProcessLifecycle.CANCELLED, "x"), "null process is a no-op");
        assertFalse("x".equals(st4.bunker.lastError), "null process must not stamp the slot");
        BotState st5 = new BotState();          // inactive slot: verdict of a FINISHED run
        st5.bunker.endReason = "DONE";
        ChainProcessLifecycle.drop(probe, st5.bunker, ChainProcessLifecycle.CANCELLED, "late cancel");
        assertEquals("DONE", st5.bunker.endReason, "inactive slot keeps its finished verdict — got " + st5.bunker.endReason);
    }

    /**
     * gap#75-b: a preemption must ARM a re-try. The dropped dig leaves the bot standing in its own half-dug
     * 1×1 shaft, which reads cornered=true in the HazardField — without a pending re-arm the cornered
     * start-gate self-vetoes the reflex for the REST of the night (live 2026-07-14: a user goto preempted
     * the dusk dig, was itself cancelled, and the bot idled exposed in an unsealed 2-deep pit all night).
     * Only the process's own verdict, dawn or an explicit cancel consume the pending re-arm.
     */
    @Test
    void aPreemptedDigArmsARetry() {
        BotState st6 = new BotState();
        DuskSecureChain chain6 = new DuskSecureChain(st6, new WorldModel());
        BunkerProcess held6 = new BunkerProcess(2);
        held6.attach(st6);
        chain6.adoptProcessForTest(held6);
        assertFalse(chain6.rearmPendingForTest(), "no re-arm pending before any preemption");
        chain6.interruptEpisodeState("user");
        assertTrue(chain6.rearmPendingForTest(),
                "gap#75-b: INTERRUPTED must not consume the dusk episode — re-arm must be pending");
        // The armed gate waives ONLY the cornered self-veto, and only inside the window.
        assertFalse(DuskSecureChain.startGateBlocks(true, true, true, true),
                "armed: the own-shaft cornered reading must not block the re-dig");
        assertTrue(DuskSecureChain.startGateBlocks(true, true, true, false),
                "unarmed: genuine cornered still sits the reflex out (BunkerChain's case)");
        assertTrue(DuskSecureChain.startGateBlocks(true, false, true, true), "armed but day/dawn: window closed, gate blocks");
        assertTrue(DuskSecureChain.startGateBlocks(false, true, true, true), "armed but absent: gate blocks");
        assertFalse(DuskSecureChain.startGateBlocks(true, true, false, false),
                "plain exposed night, not cornered: gate open (legacy path unchanged)");
        // An explicit cancel stands down — it consumes the pending re-arm.
        BunkerProcess held6b = new BunkerProcess(2);
        held6b.attach(st6);
        chain6.adoptProcessForTest(held6b);
        chain6.cancelEpisodeState("agent cancel");
        assertFalse(chain6.rearmPendingForTest(), "cancel consumes the pending re-arm");
        // A stray interrupt with NOTHING held must not arm (no dig was preempted).
        BotState st7 = new BotState();
        DuskSecureChain chain7 = new DuskSecureChain(st7, new WorldModel());
        chain7.interruptEpisodeState("retreat");
        assertFalse(chain7.rearmPendingForTest(), "no held process: interrupt must not arm a re-dig");
    }

    /**
     * The incident's root cause as geometry: a bot standing one block down in its own 1×1 shaft in a dirt
     * slab reads cornered, so only the pending re-arm lets the start gate open again. The scene
     * {@code wd.duskSecureHeldProcessLifecycle} drives the dig and the re-dig in the world.
     */
    @Test
    void aHalfDugShaftReadsCorneredAndOnlyTheArmedGateOpens() {
        int floorY = 64;
        GridWorldView w = new GridWorldView(-4, floorY - 8, -4, 9, 17, 9);
        w.fill(-3, floorY - 6, -3, 3, floorY + 1, 3, GridWorldView.SOLID);   // the slab
        w.set(0, floorY + 1, 0, GridWorldView.AIR);                          // the standing slot
        w.set(0, floorY, 0, GridWorldView.AIR);                              // one block dug
        BlockPos foot = new BlockPos(0, floorY, 0);
        HazardField hf = HazardField.compute(w, foot, 1, SurvivalMath.survivableFall(20f), 2);
        assertTrue(SurvivalFacts.cornered(hf), "the half-dug 1×1 shaft must read cornered=true (that reading IS the live self-veto)");

        BotState st = new BotState();
        DuskSecureChain chain = new DuskSecureChain(st, new WorldModel());
        BunkerProcess dig = new BunkerProcess(2);
        dig.attach(st);
        chain.adoptProcessForTest(dig);
        assertTrue(DuskSecureChain.startGateBlocks(true, true, true, chain.rearmPendingForTest()),
                "before the preemption nothing is armed, so the cornered reading blocks");
        chain.interruptEpisodeState("user");
        assertFalse(DuskSecureChain.startGateBlocks(true, true, SurvivalFacts.cornered(hf), chain.rearmPendingForTest()),
                "gap#75-b: duskSecure preempted mid-dig never re-arms — its own unsealed shaft reads cornered and the"
                        + " start-gate self-vetoes for the rest of the night (rearmPending=" + chain.rearmPendingForTest() + ")");
    }
}
