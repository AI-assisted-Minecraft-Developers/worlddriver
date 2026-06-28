from scripts.pmcs.gate import evaluate_gate

# 真实 baseline(all-OFF)与候选(apw-stack),来自 REGRESSION.md lever 矩阵
OFF = {"replay-0004": 579, "replay-0005": 840, "replay-0006": 1814}
APW = {"replay-0004": 1935, "replay-0005": 649, "replay-0006": 829}


def test_apw_rejected_due_to_0004_regression():
    v = evaluate_gate(OFF, APW)
    assert v.accepted is False
    assert "replay-0004" in v.regressions


def test_genuine_improvement_accepted():
    cand = {"replay-0004": 560, "replay-0005": 700, "replay-0006": 900}
    v = evaluate_gate(OFF, cand)
    assert v.accepted is True
    assert v.net_positive is True
    assert v.regressions == [] and v.crossed_silky == []


def test_net_negative_rejected_even_without_regression():
    base = {"a": 100, "b": 100}
    cand = {"a": 109, "b": 109}  # 各 +9%(<10% 容差)但聚合 200→218
    v = evaluate_gate(base, cand)
    assert v.net_positive is False and v.accepted is False


def test_crossing_silky_threshold_rejected():
    base = {"a": 100, "b": 1000}
    cand = {"a": 130, "b": 400}   # 聚合净正,但 a 从 100(silky)跨到 130
    v = evaluate_gate(base, cand)
    assert "a" in v.crossed_silky and v.accepted is False
