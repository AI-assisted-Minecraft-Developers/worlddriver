package net.magicterra.worlddriver.bot.arbitration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.bot.behavior.BotState;
import org.junit.jupiter.api.Test;

/** The reflex chains' small gates: death grace, the frail gate and the dusk urgent bid. */
class ChainGateMatrixTest {

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
     * already cornered/sheltered, the reflex escalates to DUSK_URGENT=90 unless the escalation flag is off.
     */
    @Test
    void anExposedBotAtNightBidsUrgently() {
        assertEquals(Priorities.DUSK_URGENT, DuskSecureChain.urgentBid(true, false, true), "exposed night urgent=90");
        assertEquals(Priorities.IDLE_SECURE, DuskSecureChain.urgentBid(true, false, false), "flag off stays 40");
        assertEquals(Priorities.IDLE_SECURE, DuskSecureChain.urgentBid(false, false, true), "daytime stays 40");
        assertEquals(0f, DuskSecureChain.urgentBid(true, true, true), "already cornered/sheltered = no bid");
    }
}
