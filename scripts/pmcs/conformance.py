"""把 telemetry tick 流聚合成 per-Move conformance 表。

一次 Move "execution" = telemetry 里 step 指针的一段连续占用(step 变化 = 进入下一个 Move)。
该段内 totStuck 峰值 >= silky 即判 churned(executor 在这个 Move 上卡了)。
跨 corpus 聚合后,churned>0 的 Move = planner 谓词比 executor 真实能力宽松的发散点。
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
