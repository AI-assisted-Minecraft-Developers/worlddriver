"""跨全 corpus 跑一个 flag 组合,产出 maxStuck 矩阵 + per-archive 发散表,可存盘 + 过接受门。

用法:
  python3 -m scripts.pmcs.sweep --flags '{}' --save baseline.json
  python3 -m scripts.pmcs.sweep --flags '{"walkerX":true}' --baseline baseline.json --timeout 150
"""
import argparse
import json
import time

from scripts.pmcs.corpus import load_corpus
from scripts.pmcs.run_case import run_case
from scripts.pmcs.gate import evaluate_gate


def sweep(corpus_path, flags, timeout):
    entries = load_corpus(corpus_path)
    results = []
    for e in entries:
        t0 = time.time()
        r = run_case(e.archive, flags, e.arrive_x, e.cmp, timeout=timeout)
        div = sorted([m for m, s in r.conformance.items() if s.churned > 0])
        print(f"[{int(time.time()-t0):3d}s] {e.archive:22s} maxStuck={r.max_stuck:5d} "
              f"arrived={int(r.arrived)} class={e.failure_class:18s} churned={div}",
              flush=True)
        results.append((e, r, div))
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="config/agent_driver/replays/corpus.json")
    ap.add_argument("--flags", default="{}")
    ap.add_argument("--baseline", default=None, help="过门:对比的 baseline matrix JSON")
    ap.add_argument("--save", default=None, help="把本次 matrix 存到此 JSON")
    ap.add_argument("--timeout", type=int, default=160)
    a = ap.parse_args()
    flags = json.loads(a.flags)
    results = sweep(a.corpus, flags, a.timeout)
    matrix = {e.archive: r.max_stuck for e, r, _ in results}
    union = sorted({m for _, _, div in results for m in div})
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
