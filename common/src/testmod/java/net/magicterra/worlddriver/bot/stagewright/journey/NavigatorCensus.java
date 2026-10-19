package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.LinkedHashMap;
import java.util.Map;

import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;

/**
 * The EXECUTOR's side of a rung, as a delta against the rig that owns it — the counterpart to
 * {@code JourneyRig.futileGateLine}, which reports the SEARCH side.
 *
 * <p><b>The reading rung 6 could not produce.</b> Its timeout of 2026-08-26 spent 8040 ticks on
 * 5788 searches whose goal never moved off one cell, and the futile gate excused 5787 of them
 * under the skip reason "the search reached its goal". That exclusion is by design, and
 * {@code SearchGovernors.deadZoneRepeats} already describes the pattern: "it is gated on
 * {@code !res.goalReached()}, and a dead-zone happens when the search SUCCEEDS and the executor
 * then refuses the edge it produced". It was measured there as a bot that re-routed for the whole
 * 2400-tick budget of one movement task while three separate guards each correctly refused to
 * move it. Four correct answers and no legal move.
 *
 * <p>Naming WHICH refuser needs the executor's own counters, and every existing home for them
 * missed that rung. {@code death.strideGuard} writes only when the bot dies;
 * {@code body.leftTheWorld} only when it falls out of the world; {@link JourneyFlight}'s per-walk
 * deltas only on the {@code walkToColumn} path — while rung 6 hands a walk to {@code drive} and
 * ends in TIMEOUT. That intersection — driven directly, timed out, still standing — had no
 * executor-side row at all, so a rung could be pinned for its entire budget and the ledger could
 * say nothing about who pinned it.
 *
 * <p><b>Why a delta and why the snapshot is taken at construction.</b> The counters belong to
 * the run's bot and every rung of a run shares them, so a cumulative reading on rung 9 is
 * rungs 1–9 added together and no rung's row can be read alone. One rig per rung makes the rig's
 * birth the right baseline — the same reasoning, and the same instant, as {@code futileAtStart}.
 * On self the first reading lands a tick or two later, so the baseline is the first one that does
 * ({@link #baselineIfMissing}). A NEGATIVE number here would mean the baseline was taken after the
 * ticks it is subtracting, i.e. the instrument is broken and the row is not a reading.
 *
 * <p>Read through {@code mc.debug.navigator}, whose counters carry these by name. <b>Nothing here
 * branches on anything</b>; this class only says what the counters hold.
 */
final class NavigatorCensus {

    /** The counters this row reports, read at one instant; null before the run has a reading. */
    private record Counts(long fires, Map<String, Long> skips, long repaths, long keptPlans, long pinnedNoPlan) {
        static Counts now() {
            BotDebugContracts.NavigatorReading t = JourneyRig.navigatorOrNull();
            if (t == null) return null;
            return new Counts(t.counter("strideGuardFires"), JourneyRig.countersUnder(t, JourneyRig.STRIDE_SKIPS),
                    t.counter("guardForcedRepaths"), t.counter("guardKeptPlans"), t.counter("guardPinnedWithNoPlan"));
        }
    }

    private Counts atStart = Counts.now();

    /** Take the baseline from the first reading there is, when the rig's birth had none. */
    void baselineIfMissing() {
        if (atStart == null) atStart = Counts.now();
    }

    /**
     * One line naming who did and did not refuse to move the bot on this rung.
     *
     * <p><b>Fires plus skips IS the tick count the stride guard ran over</b> — its own doc promises
     * exactly one bucket moves per tick — so a total far below the rung's ticks is not a small
     * share, it is the guard never having been asked, and the line says which it is rather than
     * leaving a zero to be read as a verdict. That distinction is the whole point of the row: a
     * pinned bot with a silent stride guard means the refuser is one of the OTHER two
     * ({@code SearchGovernors} names the footing guard and the recovery hop), and the next round
     * should instrument those instead of re-reading this one.
     *
     * <p>The two navigator traces at the tail are instantaneous, not deltas, and they are labelled as
     * such: they are whatever the bot's navigator last did, which on a rung that ends while still
     * ticking is this rung's last tick, and on a rung that stopped ticking early is somebody else's.
     * The row must not be quoted as this rung's without checking that.
     */
    String line() {
        Counts now = Counts.now();
        if (now == null) return "no navigator reading yet (no bot, or self's navigator answers on the client thread and none had landed)";
        Counts base = atStart != null ? atStart : now;
        long skipSum = 0;
        Map<String, Long> skips = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : now.skips().entrySet()) {
            long v = e.getValue() - base.skips().getOrDefault(e.getKey(), 0L);
            skips.put(e.getKey(), v);
            skipSum += v;
        }
        StringBuilder sb = new StringBuilder();
        skips.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(k).append('=').append(v));
        long fires = now.fires() - base.fires();
        return "stride guard on this rung: fired " + fires + " times, let " + skipSum + " ticks pass"
                + (fires + skipSum == 0
                        ? " (total 0: the guard was never called on this rung, so it is not what held"
                          + " the bot in place; the other two refusers, the footing guard and the"
                          + " unstick hop, have no readings yet)"
                        : ", pass reasons: " + sb)
                + "; forced repaths " + (now.repaths() - base.repaths())
                + ", pinned at the limit with the plan still advancing "
                + (now.keptPlans() - base.keptPlans())
                + ", pinned at the limit with no plan " + (now.pinnedNoPlan() - base.pinnedNoPlan())
                + "; navigator's last tick (shared across the whole playthrough; when this rung is not"
                + " ticking it is another rung's)=" + JourneyRig.navigatorTrace("lastTickTrace")
                + "; last tick with support underfoot=" + JourneyRig.navigatorTrace("lastSupportedTrace");
    }
}
