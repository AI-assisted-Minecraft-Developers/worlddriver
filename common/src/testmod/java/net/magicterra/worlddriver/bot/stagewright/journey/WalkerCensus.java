package net.magicterra.worlddriver.bot.stagewright.journey;

import net.magicterra.worlddriver.bot.movement.Walker;

/**
 * The EXECUTOR's side of a rung, as a delta against the rig that owns it — the counterpart to
 * {@code JourneyRig.futileGateLine}, which reports the SEARCH side.
 *
 * <p><b>The reading rung 6 could not produce.</b> Its timeout of 2026-08-26 spent 8040 ticks on
 * 5788 searches whose goal never moved off one cell, and the futile gate excused 5787 of them
 * under「搜索到达了目标」— by design, and {@code SearchGovernors.deadZoneRepeats} already names
 * the shape in prose: 「it is gated on {@code !res.goalReached()}, and a dead-zone happens when
 * the search SUCCEEDS and the executor then refuses the edge it produced」, measured there as a
 * body that re-routed for a whole 2400-tick leg budget while three separate guards each
 * correctly refused to move it. Four right answers and no legal move.
 *
 * <p>Naming WHICH refuser needs the executor's own counters, and every existing home for them
 * missed that rung. {@code death.strideGuard} writes only when the body dies;
 * {@code body.leftTheWorld} only when it falls out of the world; {@link JourneyFlight}'s per-leg
 * deltas only on the {@code walkToColumn} path — while rung 6 hands an {@code IntentProcess} to
 * {@code drive} and ends in TIMEOUT. That intersection — driven directly, timed out, still
 * standing — had no executor-side row at all, so a rung could be pinned for its entire budget
 * and the ledger could say nothing about who pinned it.
 *
 * <p><b>Why a delta and why the snapshot is taken at construction.</b> The counters are static
 * for the whole JVM and every rung of a run shares them, so a cumulative reading on rung 9 is
 * rungs 1–9 added together and no rung's row can be read alone. One rig per rung makes the rig's
 * birth the right baseline — the same reasoning, and the same instant, as {@code futileAtStart}.
 * A NEGATIVE number here would mean the baseline was taken after the ticks it is subtracting,
 * i.e. the instrument is broken and the row is not a reading.
 *
 * <p><b>Nothing here branches on anything.</b> These counters are write-only breadcrumbs and
 * their own docs require that; this class only says what they hold.
 */
final class WalkerCensus {

    private final int firesAtStart = Walker.strideGuardFires;
    private final long[] skipsAtStart = skipSnapshot();
    private final int repathsAtStart = Walker.guardForcedRepaths;
    private final int keptPlansAtStart = Walker.guardKeptPlans;
    private final int pinnedNoPlanAtStart = Walker.guardPinnedWithNoPlan;

    private static long[] skipSnapshot() {
        long[] v = new long[Walker.STRIDE_SKIP_REASONS.length];
        for (int i = 0; i < v.length; i++) v[i] = Walker.strideGuardSkips.get(i);
        return v;
    }

    /**
     * One line naming who did and did not refuse to move the body on this rung.
     *
     * <p><b>Fires plus skips IS the tick count the stride guard ran over</b> — its own doc promises
     * exactly one bucket moves per tick — so a total far below the rung's ticks is not a small
     * share, it is the guard never having been asked, and the line says which it is rather than
     * leaving a zero to be read as a verdict. That distinction is the whole point of the row: a
     * pinned body with a silent stride guard means the refuser is one of the OTHER two
     * ({@code SearchGovernors} names the footing guard and the recovery hop), and the next round
     * should instrument those instead of re-reading this one.
     *
     * <p>The two walker traces at the tail are instantaneous and JVM-wide, not deltas, and they
     * are labelled as such: they are whatever the walker last did, which on a rung that ends
     * while still ticking is this rung's last tick, and on a rung that stopped ticking early is
     * somebody else's. The row must not be quoted as this rung's without checking that.
     */
    String line() {
        long skipSum = 0;
        StringBuilder sb = new StringBuilder();
        String[] names = Walker.STRIDE_SKIP_REASONS;
        for (int i = 0; i < names.length; i++) {
            long v = Walker.strideGuardSkips.get(i) - skipsAtStart[i];
            skipSum += v;
            sb.append(i == 0 ? "" : "，").append(names[i]).append('=').append(v);
        }
        int fires = Walker.strideGuardFires - firesAtStart;
        return "stride 守卫本级点火 " + fires + " 次、放行 " + skipSum + " tick"
                + (fires + skipSum == 0
                        ? "（合计 0 —— 守卫本级一次都没被调用到，所以钉住身体的不是它；"
                          + "另外两个拒绝者（落脚守卫、解卡跳）此刻还没有读数）"
                        : "，放行理由：" + sb)
                + "；强制重规划 " + (Walker.guardForcedRepaths - repathsAtStart)
                + " 次、钉满但计划仍在前进 " + (Walker.guardKeptPlans - keptPlansAtStart)
                + " 次、钉满且手里没计划 " + (Walker.guardPinnedWithNoPlan - pinnedNoPlanAtStart)
                + " 次；walker 最后一 tick 在做（⚠️整趟共享，本级不 tick 时是别级留下的）="
                + Walker.lastTickTrace
                + "；最后一个还有支撑的 tick 在做=" + Walker.lastSupportedTrace;
    }
}
