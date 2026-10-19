package net.magicterra.worlddriver.bot.arbitration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.behavior.BunkerProcess;
import net.magicterra.worlddriver.bot.behavior.RunAwayProcess;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * gap#72-②: mc.bot.cancel{process:"bunker"} returned ok while duskSecure's held BunkerProcess sat untouched.
 * "bunker" is BOTH BunkerChain's chain NAME and BunkerProcess's KIND: the old routing tried the user slot,
 * then the IDLE BunkerChain by name, and unconditionally said ok. A named cancel must resolve, in order:
 * user slot by kind → chain episode by NAME (only a LIVE one counts) → any chain-HELD process of that kind
 * — and the labels must say what was actually cancelled, empty on zero hits.
 */
class CancelRoutingTest {

    @Test
    void aNamedCancelReachesWhatIsActuallyRunning() {
        // ① the incident: duskSecure holds a SEALED-hold BunkerProcess, BunkerChain idle.
        BotState st = new BotState();
        DuskSecureChain dusk = new DuskSecureChain(st, new WorldModel());
        BunkerProcess held = new BunkerProcess(3);
        held.attach(st);
        st.bunker.endReason = "SEALED";
        st.bunker.goalReached = true;
        dusk.adoptProcessForTest(held);
        BunkerChain bunkerChain = new BunkerChain();
        RetreatChain retreat = new RetreatChain(st);
        List<Chain> chains = List.of(bunkerChain, retreat, dusk);
        assertEquals("bunker", dusk.heldProcessKind(),
                "duskSecure holding a BunkerProcess must expose heldProcessKind=bunker — got " + dusk.heldProcessKind());
        CancelRouting.Plan plan = CancelRouting.resolve("bunker", null, chains);
        assertFalse(plan.cancelUserProcess(), "incident: nothing in the user slot to cancel");
        assertTrue(plan.episodeTargets().size() == 1 && plan.episodeTargets().get(0) == dusk,
                "incident: the ONE target must be duskSecure (held process by kind), not the idle "
                        + "BunkerChain — got " + plan.episodeTargets());
        assertEquals(List.of("duskSecure/bunker-process"), plan.labels(),
                "incident: label must say what is actually cancelled — got " + plan.labels());
        // Execute the plan the way the brain does (the server-safe state half of cancelEpisode) and
        // assert the process REALLY cancels and the slot resets.
        dusk.cancelEpisodeState("user-cancel");
        assertNull(dusk.heldProcessForTest(), "incident: held process really dropped");
        assertTrue(!st.bunker.active && "CANCELLED".equals(st.bunker.endReason),
                "incident: slot reset with honest endReason — active=" + st.bunker.active + " endReason=" + st.bunker.endReason);

        // ② nothing active anywhere: an honest miss — the labels are what mc.bot.cancel answers as
        // `cancelled`, so an empty list is the miss.
        CancelRouting.Plan miss = CancelRouting.resolve("bunker", null, chains);
        assertTrue(!miss.cancelUserProcess() && miss.episodeTargets().isEmpty() && miss.labels().isEmpty(),
                "empty world: no targets — got " + miss.labels());

        // ③ USER-verb bunker (kind in the user slot) still routes to the user slot; an idle duskSecure
        // must not be dragged in.
        CancelRouting.Plan user = CancelRouting.resolve("bunker", "bunker", chains);
        assertTrue(user.cancelUserProcess(), "user-verb bunker: the user-slot target must be hit");
        assertTrue(user.episodeTargets().isEmpty(), "user-verb bunker: no chain episode to cancel — got " + user.episodeTargets());
        assertEquals(List.of("user/bunker-process"), user.labels(), "user-verb bunker: label — got " + user.labels());

        // ④ chain NAME with a LIVE episode + a held process of the same kind elsewhere: both are hit,
        // labels distinguish them (episode vs held process).
        bunkerChain.anchorForTest().beginIfIdle(0, 64, 0);   // BunkerChain mid-dig
        BunkerProcess held2 = new BunkerProcess(3);
        held2.attach(st);
        dusk.adoptProcessForTest(held2);
        CancelRouting.Plan both = CancelRouting.resolve("bunker", null, chains);
        assertEquals(List.of(bunkerChain, dusk), both.episodeTargets(),
                "name+kind: BunkerChain episode first, then duskSecure's held process — got " + both.episodeTargets());
        assertEquals(List.of("bunker-episode", "duskSecure/bunker-process"), both.labels(),
                "name+kind: distinguishable labels — got " + both.labels());
        bunkerChain.resetEpisodeState();
        dusk.cancelEpisodeState("cleanup");

        // ⑤ an IDLE chain matched by name is NOT a hit (② proves it); retreat's held flee is
        // reachable by KIND.
        retreat.adoptProcessForTest(new RunAwayProcess(new BlockPos(0, 64, 0), 16, st.retreat));
        assertEquals("runAway", retreat.heldProcessKind(),
                "retreat holding a flee must expose heldProcessKind=runAway — got " + retreat.heldProcessKind());
        CancelRouting.Plan flee = CancelRouting.resolve("runAway", null, chains);
        assertTrue(flee.episodeTargets().equals(List.of(retreat)) && List.of("retreat/runAway-process").equals(flee.labels()),
                "kind=runAway: retreat's held flee is the target — got " + flee.labels());
        // user runAway AND reflex flee at once: both targets are hit, both labelled.
        CancelRouting.Plan fleeBoth = CancelRouting.resolve("runAway", "runAway", chains);
        assertTrue(fleeBoth.cancelUserProcess()
                        && List.of("user/runAway-process", "retreat/runAway-process").equals(fleeBoth.labels()),
                "kind=runAway with user flee: both hits labelled — got " + fleeBoth.labels());
    }
}
