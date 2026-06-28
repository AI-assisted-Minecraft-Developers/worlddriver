from scripts.pmcs.run_case import CaseResult
from scripts.pmcs.conformance import MoveStat
from scripts.pmcs.run_corpus import build_matrix, divergent_moves


def test_build_matrix():
    results = [CaseResult("a.json", 579, True, {}), CaseResult("b.json", 840, True, {})]
    assert build_matrix(results) == {"a.json": 579, "b.json": 840}


def test_divergent_moves_union():
    r1 = CaseResult("a.json", 0, True, {
        "walk": MoveStat("walk", 3, 0, 10),
        "stepUp2": MoveStat("stepUp2", 1, 1, 300)})       # churned
    r2 = CaseResult("b.json", 0, True, {
        "swimAshoreBreak": MoveStat("swimAshoreBreak", 2, 1, 400)})  # churned
    assert divergent_moves([r1, r2]) == ["stepUp2", "swimAshoreBreak"]
