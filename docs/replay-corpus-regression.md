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

## 12. telemetry 增强 + "贴墙卡住" root-cause 确认(2026-06-29)
给 [walker] telemetry 加 **yaw / bear(到节点) / yawErr / hCol / lastAim**(此前只有 pitch,"为何不闭合"全靠猜)。
立即在 corpus-dry-627 起点定位"贴墙卡住"真机制(totStuck 351-364≈18s):
```
yaw=91(死锁) bear=122→88 yawErr=31 hCol=true lastAim=92 cur2=0.89 |dY|=0
```
- bot body yaw 死锁正西(91°),节点 bearing 122°(偏北 31°),**hCol=true 撞西墙**,lastAim=92≈body yaw≠节点 bearing。
- **根因**:`walkerTangentAim` 让 aim 跟 path TANGENT(趋势),拐角处节点偏离趋势 + 趋势方向有墙时,body 撞墙不转弯朝节点
  → 350 tick 慢蹭 = "贴墙卡住"。(讽刺:tangent-aim 本为修 node-bearing 180°-flip 的 facing-wall 加的,却在拐角制造新撞墙。)
- **Fix**:`walkerWallCornerNodeAim`(default-OFF)——tangent-aim 时若 hCol + 节点偏离 tangent >20° → 让位给直接节点 bearing
  转离墙朝节点。gated on hCol(无墙趋势巡航 byte-identical)。验证:dry-627 K=3 快测 → 硬化 gate。
- **注**:churn 位置 bistable(同档跑1在 -722 巡航、跑2在 -628 起点 churn),但**撞墙 aim 机制跨位置同源**,故按机制而非位置修。

## 13. walkerWallCornerNodeAim REJECT + 4/4 局部 fix 全否的压倒性结论(2026-06-29)
**同 build 公平 A/B**(隔离 telemetry confound):dry-627 K=3 OFF median **265**(=robust 267,telemetry 无 confound)
vs ON **369**(+39%)→ **REJECT**。即便 telemetry 实锤了 root-cause("贴墙卡住"= tangent-aim 拐角撞墙),
针对性 aim-handoff fix 仍回归(强制 node-bearing 引入新振荡,正是 tangent-aim 当初要消的)。

**4/4 局部 executor fix 全被硬化 gate 否决**(FBA net-NEG / DryWedgeFootY +21% / arcProgressWedge 中性 /
WallCornerNodeAim +39%)。**压倒性结论**:执行器调得极精 + churn 双稳态 → **任何局部改动的交互效应压过本意**,
flag-tuning / 点修在此系统上系统性失败。这不是"还没找对 fix",是**架构层结论**:点修范式已死。
- **真正前路(需用户 greenlight,都是大工程)**:① 执行器 recovery/aim **整体重基**(解耦纠缠的消费者/aim 驱动,
  task #55/56,多 session);② 规划器层惩罚执行器做不可靠的几何(Class A,治本但需 planner 改造);
  ③ **重新校准 #47 目标**——混沌双稳态系统上"每次随机旅途都丝滑"可能不可达,改为"P(卡死)大幅下降 + 绝大多数丝滑"。
- **keeper**:telemetry yaw/bear/yawErr/hCol 增强(诊断价值)+ pathfinder budget setters。rejected flag 全 default-OFF dormant。

## 14. 真实随机长途 characterization(2026-06-29,#47 协议 + 视频+telemetry 交叉)
当前 build(validated stack,无新 fix)跑 -629,218 → -350,480(~370格),视频 live-screen-watch + yawErr telemetry:
- **推进 ~230 格到 -399,480(近终点),6191 tick**。巡航大部分地形 OK(长段 totStuck<45)。
- **峰值 totStuck=502(≈25s)在陡崖爬升 stepUp -550,y92**(y62→92 ~30格上升);churn>120 集中在该崖 -550~-580。
- **沼泽水段**:视频报"停滞/横跳"但 walker 800 tick 在移动、totStuck 仅 0-3 = **水中游泳 yaw-thrash**(yawErr 100+°,inW),
  **totStuck 完全没捕捉到** → ⚠️ **gate 的 totStuck 判据对水中横跳失明**(水中 horizontal-only/bob-免疫)。
- yawErr>60 占 24%(水)/37%(干),但 yaw≈lastAim≠node-bearing = **tangent-aim 设计(body 跟趋势非节点)**,
  故 yawErr-vs-node 被 tangent-aim 混淆、非干净 jank 指标;真 jank = 复合硬点。
- **结论印证**:#47"每次随机旅途都丝滑"失败点 = 每条 journey ~1-2 个复合硬点(陡崖爬升 + 水沼泽 thrash)。
  典型地形之间顺畅。两类 keystone jank:① 陡崖长爬升(totStuck 高,gate 可见);② 水中 yaw-thrash(totStuck 失明,需 yawErr/视频)。
- **gate 增强 TODO**:加水中 yaw-thrash 指标(inW & |Δaim| 或 yawErr-vs-lastAim),否则水域 fix 无判据(正是"绿了又破"水域版根源)。

## 15. ⭐ dominant jank 画像实锤:撞墙+朝向错(2026-06-29,journey.log 全 stall 分析)
真实 370 格 journey 全 stall-tick(totStuck>120)签名分布:**hCol=84%、|yawErr|>60=81%、inW=43%**。
两个最长 stall(walk -580 水中 274t / walk -558 干地 216t)均 hCol=100%+yawErr>60=100%。
→ **#47 的 dominant jank 实锤 = "撞墙时 body 朝向错"(ram-while-facing-wrong)= "贴墙卡住"**,占 stall 时间 84%。
干地+水中都有(43% 水)。stepUp/diagUp 崖爬升 hCol/yawErr 较低(更像慢爬非死锁)。
- **修复语义清晰**:撞墙(hCol)且 body 偏离节点 bearing(yawErr>60)时,aim 应转向节点解墙。但两次此类 aim-override
  (WallCornerNodeAim REJECT +39% / OvershootReaim 验证中)证明:**朝向错的成因是 aim 驱动器本身(tangent/carrot/冻结
  heading 在拐角/过冲/近节点给出偏向),改一处触发新振荡**——即纠缠的 aim 驱动,与纠缠的 recovery 同构 = 需 aim 驱动重基,非点 override。

## 16. walkerOvershootReaim = 首个 NET-POSITIVE fix + hCol 细化 backfire + bistability 限制门(2026-06-29)
**非-hCol 版全 8-corpus 硬化 gate**:net_positive=**TRUE**(sum 4811→4104,**-15%**),`long-540 1212→231(-81%)`、822 -11%、897/856 也降;但 dry-627/878/757 回归 >10% → **零回归门 REJECT**。**首个 net-positive fix**。
**hCol 细化 REVERTED**:要求 horizontalCollision 反而更差——rev-897(稳定档,样本紧 [306,380,380])161→380(比 baseline 204 还差)。hCol 子集=撞墙该推过去而非后退,收窄移除了有益的非撞墙 firing。
**⚠️ bistability 限制 gate**:822/878/815/540 四硬档样本双峰(如 822=[629,1225,1229]=escape~600 vs wedge~1200),
**K=3 median 被双峰主导、gate-run 间剧烈摆动** → 这 4 档信号不可信;可信信号只在稳定档(627/897/856/部分757)。
连续两次全 gate 还在第 3/6 档"死"(nohup 进程中断,疑 client replay 偶发断)——measurement infra 也受 bistability+flakiness 拖累。
**结论/决策点**:walkerOvershootReaim(非-hCol)是迄今唯一 net-positive fix(-15%,540 大胜),但**严格零回归在双峰 bistable 档上可能根本不可达**——
这把球踢回**门标准**:是否接受"净正 + 不在稳定档回归"(放宽对 bistable 档的零回归),还是坚持严格零回归(则该 fix 及大概率任何 fix 都过不了)。是用户最初"全集净正+零回归"标准的现实性再校准。

## 17. ⭐⭐ 关键方法论反转:K=3 baseline 本身被双峰噪声污染 + overshootReaim 实为 HARMFUL(2026-06-29)
同 build K=6 OFF-vs-ON A/B(对 bistable 用 P(wedge>800)+mean,非单 median):
- **long-540 OFF**: [45,125,141,141,158,295] mean=150 median=141 **P(wedge)=0/6**(真实 baseline 干净!)
- **long-540 ON** : [45,117,140,191,826,1964] mean=547 median=165 **P(wedge)=2/6**(fix **引入** wedge!)
→ **§16 的"540 -81% win"是假象**:baseline_robust 的 540=1212 是 **K=3 坏 bistable 抽样**;真实 OFF median 141/0 wedge。
→ **连 K=3"robust"baseline 都不可信**——bistable 档(822/878/815/540)样本双峰,K=3 中位被一次坏抽样支配。
→ **walkerOvershootReaim 实为第 5 次 REJECT**(引入 wedge,非 net-positive)。
**方法论铁律升级**:bistable 档**任何**判定必须 **K≥6 同 build OFF-vs-ON + 主看 P(catastrophic wedge)**;
单 median(哪怕 K=3)会因双峰把噪声当信号——这是"绿了又破"的更深一层根。baseline_robust.json 作废待 K≥6 重建。

## 18. ⭐⭐⭐ 决定性结论:灾难 wedge 是执行器固有双稳态、flag-无关 → 点修范式彻底证伪(2026-06-29)
完整 K=6 同 build A/B:
| archive | OFF mean/med/P(wedge) | ON mean/med/P(wedge) | 判定 |
|---|---|---|---|
| long-540 | 150/141/**0/6** | 547/165/**2/6** | ON 有害(引入 wedge) |
| dry-627  | 606/314/**1/6** | 550/314/**1/6** | 中性(中位 314=314) |
**dry-627 OFF 自带 1/6 wedge**([104,259,261,367,369,2280])——此前信的"OFF≈265"单跑是幸运抽样。
**整个 corpus 皆 bistable**:每 ~6 run 偶发 1-2 次灾难 wedge,**P(wedge) 大体与 flag 无关**——点-flag 改不动它(甚至 540 被 overshootReaim 搞 worse)。
**∴ 灾难性卡死=执行器极限环/双稳态的固有行为,不是某条 aim/recovery 分支的局部 bug**;
5/5 点修(FBA/DryWedgeFootY/arcProgressWedge/WallCornerNodeAim/OvershootReaim)无一降 P(wedge)=**点修范式定量证伪**。
**#47"丝滑"的唯一路径 = 消除双稳态来源本身**(用户最初方向"结构性消除 bistability / recovery 重基"被定量证实),
而非再加 flag。评估侧:K=3 中位作废,bistable 判定一律 **K≥6 + P(catastrophic wedge)**。
**下一步(需用户拍板的结构选项,已问多次未答)**:① 执行器 aim+recovery 统一重基消双稳态 ② planner 路由惩罚绕开触发 wedge 的地形类(steep-diagUp/high-crest)③ #47 rescope 为"P(wedge)↓ + 大体丝滑"+ 把 P(wedge) 设为正式验收度量。

## 19. ⭐⭐ 分岔诊断:wedge 是非局域的随机放大,不是坏节点(2026-06-29,long-540 多 run telemetry)
同 archive/path 多次 escape run,stuck 累积在**完全不同节点**:run1(max386)卡 `walk -675,64,339`;run3(max94)卡 `parkour3 -570,78,283`。
**stall 位置 run-to-run 非确定;stall 深度(94/386/…/1900)是随机变量**——偶尔小 stall 自我强化放大成灾难 wedge。
→ **wedge 非局域**(可在任意节点涌现)= 为何针对特定节点/条件的点-flag 必败(§18 已定量证伪,这里给机制)。
→ **结构性根因假说 = stall-recovery 反馈环偶尔非耗散(自我强化)**。结构性解方向 = 让 recovery 严格耗散:
   每个 anti-stuck/recovery 动作必须**单调减小 stall 度量**(沿 path 的弧长进展),绝不增大;瞬态 stall 则总衰减、永不放大成极限环。
   (验证需 wedge 标本看放大环逐 tick——捕获中。)

## 20. ⭐⭐ planner-budget(maxMs)实用范围内不是 lever + partial-path 质量才是(2026-06-29,steep-822 maxMs 扫描 K=6)
真默认 DEFAULT_MAX_MS=**1500**ms(+ maxNodes 100k)。steep-822 maxMs 扫描 P(wedge>800)/mean:
| maxMs | P(wedge) | mean |
|---|---|---|
| 1500(默认) | 3/6 | 1125 |
| 30000 | 4/6 | 1377 |
| 60000 | 5/6 | 986 |
| 120000(80×) | 1/6 | 590 |
**非单调**:1500/30000/60000 全 3-5/6(噪声同档)——**调高 maxMs 20-40× 无用**。仅 120000 降到 1/6(可能 K=6 幸运抽样;mean 清楚降=极端预算下搜索完整找到 goal 才有效);120s 搜索不实用。
→ **planner-budget 在实用范围被证伪为廉价 fix**;唯一启示=committed **partial-path 质量**(紧预算下提交 wedge-prone 部分段)才是关键,属 best-effort 段选择改进(planner 层选项②,非调参)。
**本 session 严格排除清单**:① 5/5 执行器点修(§8/10/13/16/18)② planner 实用 maxMs 预算(本节)。
**剩余唯一方向**(需用户结构性拍板):陡对角爬升 wedge 是**执行器内生双稳态**(§18/19)——要么深度重构陡爬 mount/climb 执行动力学(选项①执行器重基),要么改进紧预算下的 best-effort partial-path 选择避开 wedge-prone 段(选项②planner)。
