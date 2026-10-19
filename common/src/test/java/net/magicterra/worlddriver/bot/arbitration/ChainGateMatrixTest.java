package net.magicterra.worlddriver.bot.arbitration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.bot.behavior.BotState;
import org.junit.jupiter.api.Test;

/** The reflex chains' small gates: episode reset, death grace, the frail gate and the dusk urgent bid. */
class ChainGateMatrixTest {

    /**
     * gap#68-R1a/⑦: BunkerChain's anchor re-bids priority 300 forever once sealed, and mc.bot.cancel had no
     * seam to reach it. The pure state reset is exercised here; the client key-release half is not.
     */
    @Test
    void cancellingAnEpisodeResetsASealedAnchor() {
        BunkerChain bc = new BunkerChain();
        // Build a sealed episode (the ⑦ deadlock shape: sealed anchor re-bids forever).
        bc.anchorForTest().beginIfIdle(0, 64, 0);
        bc.anchorForTest().sealed = true;
        assertEquals("SEALED", bc.episodePhase(), "sealed anchor reads as SEALED episode");
        bc.resetEpisodeState();
        assertNull(bc.episodePhase(), "resetEpisodeState clears the anchor");
        assertTrue(!bc.anchorForTest().active() && !bc.anchorForTest().sealed, "anchor fully reset");
    }

    /**
     * gap#68-③/⑧: death must gate autoFight re-engagement for a grace window, or a freshly-respawned naked
     * bot resumes hunting whatever killed it.
     */
    @Test
    void deathSuppressesAutoFightForAGraceWindow() {
        CombatChain cc = new CombatChain(new BotState());
        cc.suppressAutoFor(3);
        assertTrue(cc.autoSuppressed(), "suppressed right after death");
        cc.decayAutoSuppression(); cc.decayAutoSuppression(); cc.decayAutoSuppression();
        assertFalse(cc.autoSuppressed(), "suppression decays to zero");
    }

    /** gap#68-②: a bot at frail HP must not be walked into a fight unless forced. */
    @Test
    void aFrailBotIsKeptOutOfAFight() {
        assertTrue(CombatChain.frailBlocked(5f, 6f, false), "hp5<=thr6 without force must block");
        assertFalse(CombatChain.frailBlocked(5f, 6f, true), "force overrides the frail gate");
        assertFalse(CombatChain.frailBlocked(7f, 6f, false), "hp above threshold must not block");
    }

    /**
     * gap#68-④⑨: a user task (USER=50) must not suppress dusk shelter all night — exposed at night and not
     * already cornered/sheltered, the reflex escalates to DUSK_URGENT=90 unless the escalation flag is off
     * or a dry-run canary holds it at the legacy 40.
     */
    @Test
    void anExposedBotAtNightBidsUrgently() {
        assertEquals(Priorities.DUSK_URGENT, DuskSecureChain.urgentBid(true, false, true, false), "exposed night urgent=90");
        assertEquals(Priorities.IDLE_SECURE, DuskSecureChain.urgentBid(true, false, true, true), "dry-run stays 40");
        assertEquals(Priorities.IDLE_SECURE, DuskSecureChain.urgentBid(true, false, false, false), "flag off stays 40");
        assertEquals(Priorities.IDLE_SECURE, DuskSecureChain.urgentBid(false, false, true, false), "daytime stays 40");
        assertEquals(0f, DuskSecureChain.urgentBid(true, true, true, false), "already cornered/sheltered = no bid");
    }

    /**
     * The dry-run canary used to sit behind priority()'s THREAT_RADIUS veto, so a threat near the bot at
     * night — the case the escalation exists for — starved it. wouldEscalate gates the emit instead and
     * takes no threat-distance or idle-debounce input at all.
     */
    @Test
    void theDryRunCanaryIgnoresThreatDistance() {
        assertTrue(DuskSecureChain.wouldEscalate(true, false, true, true),
                "exposed+not cornered+urgent-on+dry-run -> canary would fire (the fixed case)");
        assertFalse(DuskSecureChain.wouldEscalate(true, false, true, false),
                "not dry-run -> nothing to observe, the real escalation happens instead");
        assertFalse(DuskSecureChain.wouldEscalate(true, false, false, true),
                "escalation flag off -> nothing would have escalated");
        assertFalse(DuskSecureChain.wouldEscalate(true, true, true, true),
                "cornered -> never (bunker/real dig-in owns the channel, not the canary)");
        assertFalse(DuskSecureChain.wouldEscalate(false, false, true, true), "daytime -> never");
    }
}
