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

## 7. ⚠️ 重大发现:steep churn 是**双稳态混沌**,单跑 maxStuck 无法 gate(2026-06-28)
FBA(walkerFellBelowAlign)首跑 gate:全集 7288→3398(砍半!4 硬 blocker 全大降)**但 REJECT**(dry-627
194→280 回归)——门当场抓 over-fit。我加 depth-gate(只压 node≥2-above 的真 fell-below)再 gate,结果**自相矛盾**
(diag-856 59→330、long-540 238→799,更保守却更差)→ 触发 determinism 调查:

**diag-856 同 flag 连跑 3 次 = 2974 / 364 / 371**(8× 摆动!);另一批 = 1353 / 119 / 129。
- 根因:① pathfinder `sliceMs=6` **逐 tick 时间切片** + `maxMs=30000` → 搜索跨多少 tick 随 CPU 负载变 = 执行时序非确定;
  ② **warmup**:每批 run1 冷启动(JIT/chunk/GC)= 巨高离群;③ churn 本身是**双稳态**(逃脱 vs 永久 wedge 在边缘翻转)。
- **`pathfinder.sliceMs` 点号嵌套 key 经 mc.bot.setting 设不进**(仍 6)——需另查 setter。

**后果**:单跑 peak-maxStuck gate **会在噪声上 over-fit**(正是"绿了又破"的根源)。机制已硬化:`sweep --repeat K`
取**中位数**(对 1 个 warmup 离群鲁棒,commit)。但实测中位数 batch 间仍 ~3× 摆动(371 vs 129)→ 双稳态需
**多跑统计**(P(永久wedge) over K runs),不是单点。

**领域级洞察**:这类 steep churn 跨多 session 难修,正因它**不是确定性 bug 而是混沌边缘稳定性**——flag-tuning 只能
移动逃脱**概率**,要彻底 silky 可能需消除 bistability 的**执行器结构性改动**(recovery 重基),非调参。

## 8. ⚠️⚠️ 最重大发现:FBA 硬化 gate REJECT + 验证方法论假阳性(2026-06-28)
鲁棒 K=3-median gate(`baseline_robust.json` vs `fba_robust.json`):
| 归档 | baseline median | FBA median | |
|---|---|---|---|
| steep-822 | 1224 | 651 | ✓ 唯一真赢 |
| steep-878 | 421 | 707 | ✗ |
| crest-815 | 1048 | 1413 | ✗ |
| water-757 | 287 | 441 | ✗ |
| dry-627 | 267 | 367 | ✗ |
| rev-897 | 204 | 222 | ✗ |
| long-540 | 1212 | 2455 | ✗ 翻倍 |
| diag-856 | 148 | 172 | ✗ |
| **sum** | **4811** | **6428** | **net-NEGATIVE +33%** |
**GATE: net_positive=False, 6/8 回归 → REJECT。**

**单跑 gate 曾说 FBA "7288→3398 砍半"(§7),鲁棒中位数说 "4811→6428 恶化 33%"。** FBA 只帮 steep-822,伤其余全部。

**方法论级结论**:此前全项目"validated fix"都用**单跑 live A/B** 验——单跑是 8× 噪声 → 相当一部分"已验证 fix"
可能是**噪声假阳性,实则不鲁棒**。这是用户最初"缺乏评估机制无法快速发现问题"的**根**:不是缺 fix,是**验证方法
量产假阳性**(= "绿了又破"真因)。**纠正**:今后只信硬化(K≥3 median + 全 corpus + 净正零回归)gate;
现有 40-flag stack 本身需在硬化 gate 下 vs all-OFF 重新审计。FBA 已关。

→ 没有单一 flag 组合全赢;apw 在 0004 灾难回归。这正是"全集净正零回归"门要挡的(已编码进
`scripts/pmcs/gate.py` + `test_gate.py` 用这组真实数据做 fixture)。

## 9. churn 定位诊断(corpus-steep-822,2026-06-28)
totStuck>250 的 churn 点频次:stepUp -812,66(227)+ swimUp -814,63(178)+ stairUpBreak -814,61(168)+
walk -817/-816,60(187)+ stepUp -811,65(46)。**760+ 次全挤在起点 -811~-817 / y60→66**,峰值 1459 的
step40 walk -573 仅 7 次(罕见远端 wedge)。
- **重新定性**:swimUp@y63 = 起点紧邻水 → corpus-steep-822 是**水边陡岸 climb-out churn**(出水+stairUpBreak+stepUp
  叠在 waterline),非纯干地陡坡。模式 = "卡在节点前 ~0.8 格(0.45<cur2<1.0)+ onG + arcProgStall + 原地 bob/镜头摆"。
- **含义**:validated-stack 的水岸 fix(walkerBankDigSkip*/buoyant*/swimEscape* 等)全 ON,此 climb-out **仍 churn 1224**
  → 那些 fix 要么噪声验证(不鲁棒)要么不覆盖此几何。需在硬化 gate 下重审水岸 fix 族。

## 10. walkerDryWedgeFootY REJECT + 纠缠恢复结构发现(2026-06-29)
假设:干地 wedge timer 被 +1 节点的 bob 打败(3D wd2 用连续 p.getY())→ recovery 永不触发 → churn。
Fix:above-node 时 wd2 垂直项用量化 foot.getY()(只算真实爬升)。**steep-822 K=3 快测 = 495/1483/1513
median 1483 vs baseline 1224 → 更差 +21% → REJECT**(快测 8min 抓到,省 50min 全 gate)。
- **为何更糟**:bob-免疫 timer 让 recovery 触发**更多**,但 recovery=repath **re-commit 同一够不到的爬升** → 更多 churn。
  **证实:recovery 不是杠杆,越触发越糟。** 真卡点 = **+1 stepUp MOUNT 执行不了**(ram riser 不起跳)。
- **深层结构根因**:stepUp mount 已极度工程化(mis-aim ram→cut+jump 转身、stalled 后 force 接地 jump ~1.2s、
  lateral-bank-follow)。但"force-jump"门**也 gate 在 noStepProgressTicks**(同一 bob-defeated timer)。
  DryWedgeFootY 同时放出**有益 force-jump + 有害 repath-recommit**,有害占上风。**多个 recovery 消费者
  (force-jump/ramSlide/repath/pitch-pivot)共用同一被 bob 打败的 timer,有益与有害纠缠** = "绿了又破 + 跨 session
  难修"的结构本质 = task #55/56 的"recovery 重基/拆 Walker 巨兽"。surgical 单 flag 必然顾此失彼。
- **结论**:此 blocker 的真解是**结构性 recovery 重基**(解耦消费者:让有益 force-jump 能触发而不放出有害 repath),
  非调 flag。需用 writing-plans 严谨规划 + 硬化 gate 逐步验证。两次 disciplined fix(FBA/DryWedgeFootY)均被门正确
  拦截 = 机制兑现价值,但也证明 flag-tuning 在此纠缠系统上无效。

## 11. 三连 recovery-trigger fix 全失败 → 确定性结论(2026-06-29)
| 候选 | 机制 | steep-822 K=3 median | vs 1224 |
|---|---|---|---|
| FBA (walkerFellBelowAlign) | settle 落地 | (全集 net-NEG +33%) | REJECT |
| walkerDryWedgeFootY | bob-免疫 wedge timer | 1483 | +21% REJECT |
| walkerArcProgressWedge | 振荡 limit-cycle repath | 1280 | +5% 中性无效 |

**确定性结论**:三个独立的 recovery-trigger 改动全部无效/更糟。根因已锁死:**触发 recovery(repath)更多 → repath re-commit 同一够不到的 +1 爬升 → 更糟**。
- **真杠杆只有两个,都是结构性、深、需 greenlight**:
  1. **执行器 mount 重基**:让 +1 stepUp/stairUpBreak 在水边可靠 MOUNT(已极度工程化,多 recovery 消费者共用 bob-defeated
     timer 纠缠;需解耦 = task #55/56 "recovery 重基/拆 Walker 巨兽")。
  2. **规划器 routing**(对应用户架构关切 Class A):若存在更缓出口,让 A* cost model 惩罚执行器做不可靠的水边 +1 mount →
     绕到可 mount 的岸。需先查 -815 局部几何(是否存在更缓出口)才能定可行性。
- **flag-tuning 在此纠缠系统上已证无效(3/3)。下一步必须是 scoped 结构性项目**(writing-plans + 硬化 gate 逐步验证),
  非 ad-hoc flag。机制的价值:用 3 个 8min 快测 + 鲁棒 gate 把"该往哪使劲"从猜测变成了实证排除。
