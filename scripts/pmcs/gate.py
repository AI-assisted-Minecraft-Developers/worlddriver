"""接受门:候选 flag 组合是否够格 flip default-ON。

spec §3 的"全集净正 + 零回归"门——直接对治本项目的 over-fit 教训(apw 在 0005/0006 调优
却在 0004 上 579→1935 灾难回归)。门只和 corpus 一样好(spec §9 风险2)。
"""
from dataclasses import dataclass


@dataclass
class GateVerdict:
    accepted: bool
    net_positive: bool
    regressions: list
    crossed_silky: list
    baseline_sum: int
    candidate_sum: int


def evaluate_gate(baseline: dict, candidate: dict, tol: float = 0.10, silky: int = 120):
    archives = sorted(baseline)
    regressions = [a for a in archives if candidate[a] > baseline[a] * (1 + tol)]
    crossed_silky = [a for a in archives if baseline[a] < silky <= candidate[a]]
    base_sum = sum(baseline[a] for a in archives)
    cand_sum = sum(candidate[a] for a in archives)
    net_positive = cand_sum < base_sum
    accepted = net_positive and not regressions and not crossed_silky
    return GateVerdict(accepted, net_positive, regressions, crossed_silky, base_sum, cand_sum)
