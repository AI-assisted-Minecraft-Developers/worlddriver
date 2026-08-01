package net.magicterra.agent.bot;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot the agent can poll via {@code mc.bot.status} + {@code wait.condition}.
 * Deliberately flat field structure so dotted-path matching works directly:
 * {@code field:"goto.active"} or {@code field:"goto.lastError"}.
 *
 * Mutated only by the bot tick (single thread); status calls produce a defensive
 * copy via {@link #snapshot()}.
 */
public final class BotState {
    public final ProcessSlot mc_goto = new ProcessSlot("goto");
    public final ProcessSlot mine = new ProcessSlot("mine");
    public final ProcessSlot builder = new ProcessSlot("builder");
    public final ProcessSlot follow = new ProcessSlot("follow");
    public final ProcessSlot explore = new ProcessSlot("explore");
    public final ProcessSlot runAway = new ProcessSlot("runAway");
    /** Dedicated slot for the AUTO-retreat reflex (RetreatChain), kept SEPARATE
     *  from {@link #runAway} (the user-invoked mc.bot.runAway verb). They used to
     *  share one slot, so the reflex's flee status stomped a user flee's status and
     *  — because the reflex never reset the slot when it stopped — left it stuck
     *  active:true forever (the "stale runAway" the agent saw survive every cancel).
     *  One owner per slot; the reflex clears this when it deactivates. */
    public final ProcessSlot retreat = new ProcessSlot("retreat");
    public final ProcessSlot look = new ProcessSlot("look");
    public final ProcessSlot elytra = new ProcessSlot("elytra");
    public final ProcessSlot craft = new ProcessSlot("craft");
    public final ProcessSlot smelt = new ProcessSlot("smelt");
    public final ProcessSlot combat = new ProcessSlot("combat");
    /** mc.bot.escape (EscapeProcess). Escape used to be slot-less, so its four
     *  silent-stall modes (weak canWalkOut gate, sealed-roof STEP_UP ping-pong)
     *  ended with active:false and NO lastError — indistinguishable from success.
     *  EscapeProcess now reports goal/target/lastError here every tick. */
    public final ProcessSlot escape = new ProcessSlot("escape");
    /** mc.bot.bunker (BunkerProcess). gap#68-⑩: the verb used to be a bare start
     *  ack with no slot at all — a zero-action bail (water at the dig site, dig
     *  timeout, …) reported ok:true indistinguishable from a real shelter. Now
     *  BunkerProcess stamps goalReached/endReason here at every terminal exit
     *  (goalReached = block-level enclosure ground truth, NOT hazardSummary.cornered). */
    public final ProcessSlot bunker = new ProcessSlot("bunker");

    /** Phase C combat telemetry (mutated by CombatProcess on the tick thread,
     *  read by status()). {@code wellTimed} counts swings issued at full attack
     *  cooldown (scale ≥ 1.0); the ratio wellTimed/swings is the timing-quality
     *  metric the 42_combat validation asserts. Reset by CombatChain.engage(). */
    public volatile int combatSwings;
    public volatile int combatWellTimed;
    public volatile int combatCrits;
    public volatile int combatKills;

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goto", mc_goto.snapshot());
        out.put("mine", mine.snapshot());
        out.put("builder", builder.snapshot());
        out.put("follow", follow.snapshot());
        out.put("explore", explore.snapshot());
        out.put("runAway", runAway.snapshot());
        out.put("retreat", retreat.snapshot());
        out.put("look", look.snapshot());
        out.put("elytra", elytra.snapshot());
        out.put("craft", craft.snapshot());
        out.put("smelt", smelt.snapshot());
        out.put("escape", escape.snapshot());
        out.put("bunker", bunker.snapshot());
        // Combat slot carries the per-engagement telemetry alongside the standard
        // slot fields so a single status read covers both liveness and timing.
        Map<String, Object> combatSnap = combat.snapshot();
        combatSnap.put("swings", combatSwings);
        combatSnap.put("wellTimed", combatWellTimed);
        combatSnap.put("crits", combatCrits);
        combatSnap.put("kills", combatKills);
        out.put("combat", combatSnap);
        return out;
    }

    /**
     * Name of the first slot reporting {@code active}, or null when every slot is idle.
     * Ordered so the slot a human would call "what the bot is doing" wins: movement
     * first, then the station work, then the reflex-owned slots.
     *
     * <p>Deliberately covers ALL slots — unlike the screen-watchdog's hand-listed subset
     * in {@code BotApiImpl}, which excludes craft/smelt because those legitimately hold a
     * station screen. This one answers "is the bot driving the body at all?", so a
     * crafting bot counts. Slot fields are volatile; no lock needed for this read.
     */
    public String activeName() {
        if (mc_goto.active) return mc_goto.name;
        if (mine.active) return mine.name;
        if (builder.active) return builder.name;
        if (follow.active) return follow.name;
        if (explore.active) return explore.name;
        if (runAway.active) return runAway.name;
        if (retreat.active) return retreat.name;
        if (escape.active) return escape.name;
        if (bunker.active) return bunker.name;
        if (combat.active) return combat.name;
        if (elytra.active) return elytra.name;
        if (craft.active) return craft.name;
        if (smelt.active) return smelt.name;
        if (look.active) return look.name;
        return null;
    }

    /** True when any process slot is running. See {@link #activeName()}. */
    public boolean anyActive() { return activeName() != null; }

    /** Per-process slot. All fields read+written under {@link BotState}'s monitor. */
    public static final class ProcessSlot {
        public final String name;
        public volatile boolean active;
        public volatile String goal;       // human-readable
        public volatile BlockPos target;   // current goal target if any
        public volatile int pathLen;       // remaining nodes
        public volatile int pathStep;      // current node index
        public volatile long startedAtMs;
        public volatile String lastError;  // null if last run ok or in-progress
        /** gap#68-R2: honest terminal verdict of the LAST run. Null/−1 until a run ends.
         *  Kept across reset() (like lastError) so awaitable/wait.condition can read it. */
        public volatile Boolean goalReached;
        public volatile String endReason;
        public volatile double finalDist = -1;

        ProcessSlot(String name) { this.name = name; }

        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("active", active);
            if (goal != null) m.put("goal", goal);
            if (target != null) m.put("target", Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ()));
            m.put("pathLen", pathLen);
            m.put("pathStep", pathStep);
            if (startedAtMs > 0) m.put("startedAtMs", startedAtMs);
            if (lastError != null) m.put("lastError", lastError);
            if (goalReached != null) m.put("goalReached", goalReached);
            if (endReason != null) m.put("endReason", endReason);
            if (finalDist >= 0) m.put("finalDist", finalDist);
            return m;
        }

        public void reset() {
            active = false;
            goal = null;
            target = null;
            pathLen = 0;
            pathStep = 0;
            startedAtMs = 0;
            // keep lastError so the agent can read it after wait.condition fires
        }
    }
}
