"""Acceptance gate: is a candidate flag combination good enough to flip to default-ON?

The "net positive across the whole set + zero regressions" gate. It exists to
prevent over-fitting: apw was tuned on 0005/0006 and regressed catastrophically on 0004
(579→1935). The gate is only as good as the corpus.
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
