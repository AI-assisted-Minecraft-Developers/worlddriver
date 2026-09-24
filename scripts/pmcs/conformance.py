"""Aggregate the telemetry tick stream into a per-Move conformance table.

One Move "execution" is one contiguous run of the same step pointer in the telemetry (a step
change means the next Move has started). If the peak totStuck within that run is >= silky, the
execution counts as churned (the executor got stuck on this Move).
Aggregated across the corpus, a Move with churned>0 marks a divergence where the planner's
predicate is more permissive than what the executor can actually do.
"""
from dataclasses import dataclass


@dataclass
class MoveStat:
    move: str
    executions: int
    churned: int
    worst_tot_stuck: int


def conformance_table(ticks, silky: int = 120):
    table: dict[str, MoveStat] = {}
    seg_move = None
    seg_peak = 0
    prev_step = None

    def flush():
        nonlocal seg_move, seg_peak
        if seg_move is None:
            return
        st = table.get(seg_move) or MoveStat(seg_move, 0, 0, 0)
        st.executions += 1
        if seg_peak >= silky:
            st.churned += 1
        st.worst_tot_stuck = max(st.worst_tot_stuck, seg_peak)
        table[seg_move] = st
        seg_move = None
        seg_peak = 0

    for tk in ticks:
        if prev_step is None or tk.step != prev_step:
            flush()
            seg_move = tk.move
            seg_peak = tk.tot_stuck
        else:
            seg_peak = max(seg_peak, tk.tot_stuck)
        prev_step = tk.step
    flush()
    return table
