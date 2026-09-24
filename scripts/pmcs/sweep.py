"""Run one flag combination across the whole corpus, producing a maxStuck matrix and a
per-archive divergence table, optionally saved to disk and checked against the acceptance gate.

Usage:
  python3 -m scripts.pmcs.sweep --flags '{}' --save baseline.json
  python3 -m scripts.pmcs.sweep --flags '{"walkerX":true}' --baseline baseline.json --timeout 150
"""
import argparse
import json
import statistics
import time

from scripts.pmcs.corpus import load_corpus
from scripts.pmcs.run_case import run_case
from scripts.pmcs.gate import evaluate_gate


def median_int(values):
    """Median (robust to a single warm-up cold-start outlier)."""
    return int(statistics.median(values))


def sweep(corpus_path, flags, timeout, repeat=1):
    """Run each archive `repeat` times and take the median maxStuck (steep churn is bistable and
    chaotic, plus warm-up cold-start outliers, so a single run swings ~8×; the median is robust to
    one outlier). Returns [(entry, median_maxStuck, samples, arrive_count, div_union)]."""
    entries = load_corpus(corpus_path)
    results = []
    for e in entries:
        t0 = time.time()
        samples, arrived, div = [], 0, set()
        for _ in range(repeat):
            r = run_case(e.archive, flags, e.arrive_x, e.cmp, timeout=timeout)
            samples.append(r.max_stuck)
            arrived += int(r.arrived)
            div |= {m for m, s in r.conformance.items() if s.churned > 0}
        med = median_int(samples)
        print(f"[{int(time.time()-t0):3d}s] {e.archive:22s} median={med:5d} "
              f"samples={sorted(samples)} arrived={arrived}/{repeat} class={e.failure_class:18s} "
              f"churned={sorted(div)}", flush=True)
        results.append((e, med, sorted(samples), arrived, sorted(div)))
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="config/worlddriver/replays/corpus.json")
    ap.add_argument("--flags", default="{}")
    ap.add_argument("--baseline", default=None, help="gate check: baseline matrix JSON to compare against")
    ap.add_argument("--save", default=None, help="save this run's matrix to this JSON file")
    ap.add_argument("--timeout", type=int, default=160)
    ap.add_argument("--repeat", type=int, default=1, help="runs per archive for the median (steep churn is bistable; 3 is recommended)")
    a = ap.parse_args()
    flags = json.loads(a.flags)
    results = sweep(a.corpus, flags, a.timeout, repeat=a.repeat)
    matrix = {e.archive: med for e, med, _, _, _ in results}
    union = sorted({m for _, _, _, _, div in results for m in div})
    print("\n=== matrix ===")
    print(json.dumps(matrix, indent=2))
    print("=== divergent moves (union across corpus) ===")
    print(union)
    if a.save:
        json.dump(matrix, open(a.save, "w"), indent=2)
        print(f"saved matrix -> {a.save}")
    if a.baseline:
        base = json.load(open(a.baseline))
        v = evaluate_gate(base, matrix)
        print(f"\n=== GATE: net_positive={v.net_positive} regressions={v.regressions} "
              f"crossed_silky={v.crossed_silky} → {'ACCEPT' if v.accepted else 'REJECT'} "
              f"(sum {v.baseline_sum}->{v.candidate_sum}) ===")


if __name__ == "__main__":
    main()
