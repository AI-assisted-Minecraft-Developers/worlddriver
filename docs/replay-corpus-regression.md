# Replay-Corpus Regression Harness (living doc)

补上 planner/executor 两世界模型间缺失的一致性校验机制。设计见
`docs/superpowers/specs/2026-06-28-pathfinding-conformance-loop-design.md`,
实现计划见 `docs/superpowers/plans/2026-06-28-pathfinding-conformance-loop.md`。

## 1. 用法
1. 设 flags(经 RPC port 39801,**不是** MCP 工具——schema 在 session 启动冻结会 strip 新 key)。
2. `mc.debug.replay {file:"<corpus 归档>", restoreBlocks:true}`(replan=true FAITHFUL:恢复 envelope+teleport+重规划)。
3. `python3 -m scripts.pmcs.run_corpus --flags '...' --baseline baseline.json`(或单档 `scripts/replay_regression_track.sh`)。

- **maxStuck** = `[walker]` telemetry 行 `totStuck=` 峰值(ticks;/20 ≈ 秒)。
- **silky** < 120(≈6s)。**容差** 10%。

## 2. Corpus(权威源 = `corpus.json`)
从 fabric/run runtime 精选、按 header.start/goal 验证。覆盖各故障类型(门只和多样性一样好,spec §9 风险2)。

| 归档 | 故障类 | 起点→目标 | arrive |
|---|---|---|---|
| corpus-steep-822 | steep-diagUp | -822,63,196 → -520,180 | x≥-525 |
| corpus-steep-878 | steep-diagUp-far | -878,61,299 → -520,180 | x≥-525 |
| corpus-crest-815 | high-crest | -815,80,165 → -520,180 | x≥-525 |
| corpus-water-757 | water-corridor | -757,62,231 → -520,180 | x≥-525 |
| corpus-dry-627 | dry-mid-traverse | -627,62,218 → -880,300 | x≤-875 |
| corpus-rev-897 | reverse-far | -897,61,470 → -540,250 | x≥-545 |
| corpus-long-540 | long-success-guard | -540,65,250 → -900,470 | x≤-895 |
| corpus-diag-856 | diagonal-long | -856,62,539 → -560,310 | x≥-565 |

目标 ~10-15:待补深水穿越/水岸 climb-out/树冠/峡谷 pinch 专门归档。

## 3. 当前已接受 flag stack(default-ON)
(空 —— 机制刚建好;任何 flip 由接受门 + 用户决定。)

## 4. Baseline 矩阵(all default-OFF)
(待首次 `run_corpus --flags '{}'` 采集后填入,存 `baseline.json`。)

## 5. 发散清单(per-Move conformance)
(待首次发现运行 `divergent_moves` 产出后填。预期复现:steep 段 stepUp2/diagUp churn、water 段
swimAshoreBreak/swimBankClimb churn ——即用户举的 +2坡/+1岸/悬空藤类。)

## 6. Lever 历史(候选 flag 组合 × archive · 历史数据 from -815 sub-corpus)
本 session 早期在 -815 sub-corpus(旧 runtime 归档 replay-0004/0005/0006)实测,**证明接受门必要**:

| 组合 | 0004 | 0005 | 0006 | 门裁决 |
|---|---|---|---|---|
| OFF(baseline) | 579 | 840 | 1814 | — |
| apw-stack | 1935 冻 | 649 | 829 | **REJECT**(0004 灾难回归,over-fit) |
| horizon=128 | 822 | 1438 | 1664 | REJECT(净负) |

→ 没有单一 flag 组合全赢;apw 在 0004 灾难回归。这正是"全集净正零回归"门要挡的(已编码进
`scripts/pmcs/gate.py` + `test_gate.py` 用这组真实数据做 fixture)。
