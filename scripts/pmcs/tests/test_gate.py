from scripts.pmcs.gate import evaluate_gate

# Real baseline (all-OFF) and candidate (apw-stack) values, from the REGRESSION.md lever matrix
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
    cand = {"a": 109, "b": 109}  # each +9% (<10% tolerance), but the aggregate goes 200→218
    v = evaluate_gate(base, cand)
    assert v.net_positive is False and v.accepted is False


def test_crossing_silky_threshold_rejected():
    base = {"a": 100, "b": 1000}
    cand = {"a": 130, "b": 400}   # aggregate is net positive, but a crosses from 100 (silky) to 130
    v = evaluate_gate(base, cand)
    assert "a" in v.crossed_silky and v.accepted is False
