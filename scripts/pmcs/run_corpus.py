"""Run one flag combination across the whole corpus → maxStuck matrix → acceptance gate result."""
from __future__ import annotations
import argparse
import json

from scripts.pmcs.corpus import load_corpus
from scripts.pmcs.run_case import run_case
from scripts.pmcs.gate import evaluate_gate


def build_matrix(results):
    return {r.archive: r.max_stuck for r in results}


def divergent_moves(results):
    """Merged across the corpus: the sorted union of Move types with churned>0 in any archive."""
    moves = set()
    for r in results:
        for move, stat in r.conformance.items():
            if stat.churned > 0:
                moves.add(move)
    return sorted(moves)


def run_corpus(corpus_path: str, flags: dict, baseline):
    entries = load_corpus(corpus_path)
    results = [run_case(e.archive, flags, e.arrive_x, e.cmp) for e in entries]
    matrix = build_matrix(results)
    verdict = evaluate_gate(baseline, matrix) if baseline else None
    return matrix, verdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="config/worlddriver/replays/corpus.json")
    ap.add_argument("--flags", default="{}")
    ap.add_argument("--baseline", default=None, help="baseline matrix JSON file (omit to collect only)")
    a = ap.parse_args()
    baseline = json.load(open(a.baseline)) if a.baseline else None
    matrix, verdict = run_corpus(a.corpus, json.loads(a.flags), baseline)
    print("matrix:", json.dumps(matrix, indent=2))
    if verdict:
        print(f"net_positive={verdict.net_positive} regressions={verdict.regressions} "
              f"crossed_silky={verdict.crossed_silky} → "
              f"{'ACCEPT' if verdict.accepted else 'REJECT'} "
              f"(sum {verdict.baseline_sum}->{verdict.candidate_sum})")


if __name__ == "__main__":
    main()
