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

## 4. Baseline 矩阵(= 当前 validated fix-stack,**非** all-OFF)
**重要**:runtime 有 ~20 个往期 validated walker/pathfinder fix 是 ON(快照 = `baseline-flags.json`):
walkerParkourAscendHold / walkerArcLength{Advance,Wedge} / walkerTangentAim / walkerDeepWater{DriftBrake,FloatBeeline} /
walkerWaterStepDownFloat / walkerDescentFlipHold / walkerFutileBankDigRelease / walkerBankDigSkipOverhang /
walkerVine{FreeHangClimb,LandGrab,DescentDrop} / pathfinder{ForbidParkourIntoDeepWater,FloatingSurfaceCross,VineOverWaterTax,PadOverWaterTax,PadClusterTax} 等。
所以下表是**这套 stack 之上**的残留 churn(候选新 fix 是它们的 delta;门对比同此 base)。

全 8-corpus baseline(2026-06-28,`baseline.json`,timeout=150s;**maxStuck 是判据**,arrived=0 是 300格旅途超时假象):
| 归档 | maxStuck | 主 churn moves |
|---|---|---|
| corpus-steep-822 | 1940 | walk/diagUp/stepUp/diagDown/fall4/parkour2d |
| corpus-steep-878 | 1949 | diag/diagUp/stepUp/walk |
| corpus-crest-815 | 1273 | diagUp/stepUp/walk/fall3 |
| corpus-water-757 | 1281 | swim+walk+diag+parkour(最杂) |
| corpus-dry-627 | 194 | walk |
| corpus-rev-897 | 354 | pillarUp/stepUp/walk |
| corpus-long-540 | 238 | stepDown/stepUp/walk |
| corpus-diag-856 | **59** | (clean) |
| **sum** | **7288** | |

**头号发散**:`walk` 在 7/8 churn(+diagUp/stepUp)= steep diagUp limit-cycle 跨 corpus 主导。telemetry 实见
`move=diagUp node=-810,88,189 |dY|=15.25` = 节点在脚上方 15 格的极端 fell-below(bot 滑下崖底够不到高节点)。

## 5. 发散清单(per-Move conformance)
首张发散表 from corpus-steep-822(validated-stack baseline;churned = 某 step 段 totStuck 峰值 ≥120):
| Move | exec | churned | worst_totStuck |
|---|---|---|---|
| walk | 35 | **15** | **403** |
| stepUp | 21 | 2 | 286 |
| stepDown | 11 | 1 | 257 |
| fall4/fall3/fall2 | 各1-2 | 各1 | 228-256 |
| parkour2 | 2 | 1 | 153 |
| diagDown/diagUp/parkourAscend2/swimUp/bridgePlace | — | 0 | <75 (clean) |

**解读**:主导 stall 在 **walk 节点**(worst 403≈20s)——不是 stepUp2/diagUp(我原先猜的),而是 limit-cycle
里 bot 腾空够不到 walk waypoint,与已知 fellBelow diagUp 根因(`reference_steep_mountain_limit_cycle_revisit_detection`)
吻合。机制**自动定位**了它,推翻了我的先验猜测——这正是评估机制的价值(spec §1)。
下一步:全 corpus sweep 补齐发散表 → 按频率×严重度排序 → 三件套定 class A/B → 修复过接受门。

## 6. Lever 历史(候选 flag 组合 × archive · 历史数据 from -815 sub-corpus)
本 session 早期在 -815 sub-corpus(旧 runtime 归档 replay-0004/0005/0006)实测,**证明接受门必要**:

| 组合 | 0004 | 0005 | 0006 | 门裁决 |
|---|---|---|---|---|
| OFF(baseline) | 579 | 840 | 1814 | — |
| apw-stack | 1935 冻 | 649 | 829 | **REJECT**(0004 灾难回归,over-fit) |
| horizon=128 | 822 | 1438 | 1664 | REJECT(净负) |

→ 没有单一 flag 组合全赢;apw 在 0004 灾难回归。这正是"全集净正零回归"门要挡的(已编码进
`scripts/pmcs/gate.py` + `test_gate.py` 用这组真实数据做 fixture)。
