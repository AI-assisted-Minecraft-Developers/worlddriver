"""跨全 corpus 跑一个 flag 组合,产出 maxStuck 矩阵 + per-archive 发散表,可存盘 + 过接受门。

用法:
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
    """中位数(对单个 warmup 冷启动离群鲁棒)。"""
    return int(statistics.median(values))


def sweep(corpus_path, flags, timeout, repeat=1):
    """每归档跑 repeat 次取 maxStuck 中位数(steep churn 是双稳态混沌 + warmup 冷启动离群,
    单跑摆动 ~8×;中位数对 1 个离群鲁棒)。返回 [(entry, median_maxStuck, samples, arrive_count, div_union)]。"""
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
    ap.add_argument("--baseline", default=None, help="过门:对比的 baseline matrix JSON")
    ap.add_argument("--save", default=None, help="把本次 matrix 存到此 JSON")
    ap.add_argument("--timeout", type=int, default=160)
    ap.add_argument("--repeat", type=int, default=1, help="每归档跑几次取中位数(steep churn 双稳态,建议 3)")
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
