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

## 21. ⭐⭐⭐ wedge 异质但共同放大器=repath-rechurn 环(2026-06-29,steep-822 wedge 标本逐 tick)
捕获 2 个 steep-822 wedge(2088/1226)逐 tick:dominant move=**parkour2d(148t)**+diagDown,cur2 median 12(巨大 off-path),onG/airborne 混。
峰值窗口=**失败 parkour 跳**(t148 parkour2d:离 node 7 格+低 2 格、airborne、stuck135)→ **path 长度 60→85 反复变(repath)**→ walk 节点间 yaw 狂摆(-196→-247)+cur2 swing+hCol 断续 → 慢振荡收敛才逃。
**各 archive 的 dominant wedge 机制不同**:steep-822=失败 parkour 跳+repath churn;crest-815=腾空 diagUp dead-zone(§前);run6=起步 diagDown 角块。**无单一 recovery 空洞**=为何 5/5 单机制点修动不了聚合 P(wedge)(§18)。
**但共同放大器 = repath-rechurn 环**:任一 move 失败→bot off-path(cur2 大)→safetyRepath(stuck>60)重提交一条从当前 off-path 态仍执行不了的路径→yaw-thrash 振荡收敛→常再失败→repath→累积。
→ **真正全局结构靶(addresses 异质性,高杠杆)= 打破 repath-rechurn 环**:move 失败+off-path 时,确定性恢复到上个干净到达节点(on-path 锚)再续,而非 repath 进 churn。这是 §19 耗散原则在 repath 层的应用。属选项①(用户标准方向)的 sharpened 靶。

## 22. ⭐⭐⭐✅ 结构性突破:walkerDryReanchor(repath-rechurn 断路器)首个真降 P(wedge) 的 fix(2026-06-29)
§21 诊断的"共同 repath-rechurn 放大器"对症 fix:干地+持续 stall(stuckTicks>50)+foot 远离当前节点(dist²>4)时,确定性瞄准上一干净节点 path[step-1](固定点,bearing 不 thrash),把 body 走回 path 再续,而非任 repath 重 churn。default-OFF,5 处 wiring。
**同 build K=6 OFF-vs-ON(看 P(wedge>800)):**
| archive | OFF mean / P(wedge) | ON mean / P(wedge) |
|---|---|---|
| steep-822 | 1430 / **5/6** | 738 / **2/6** (-48%) |
| dry-627 | 783 / **3/6** | 573 / **1/6** (-27%) |
**两档 mean+P(wedge) 双降、零回归** = session 首个真正有效的 fix。与 5/5 被否点修本质不同(它们 P(wedge) flag-无关 §18;这个降 P(wedge))——印证**对症的是共同 repath-rechurn 放大器,不是单机制**。待:其余 6 档 K=6 确认全局无回归 → 若成立=首个可接受结构性 fix,再 live 长途+replay 验收。

## 23. walkerDryReanchor 全 8 档 K=6:聚合 P(wedge) 16→6(-62%)但 diag-856 引入 wedge → 抬门细化(2026-06-29)
| archive | OFF P(wedge)/mean | ON P(wedge)/mean |
|---|---|---|
| steep-822 | 5/6 1430 | 2/6 738 ✓ |
| crest-815 | 3/6 887 | 0/6 268 ✓ |
| long-540 | 5/6 1436 | 1/6 605 ✓ |
| dry-627 | 3/6 783 | 1/6 573 ✓ |
| water-757 | 0/6 170 | 0/6 143 |
| rev-897 | 0/6 251 | 0/6 197 |
| steep-878 | 0/6 177 | 0/6 396 ✗mean |
| diag-856 | 0/6 452 | 2/6 641 ✗✗引入wedge |
聚合 P(wedge) **16/48→6/48(-62%)**=强烈净正、真有效(对比 5/5 点修 flag-无关)。但 diag-856 0→2/6=零回归违规。
根因:wins=真失败 move(bot 远 off-path,822 cur2=47);regressions(878/856)=正常对角/爬升 drift(~1.4格)在 cur2>4(2格)门误触发 anchor→回拉 churn。
→ 细化:DRY_REANCHOR_OFFPATH_SQ 4→9(3格),只让真失败(远 off-path)触发,正常 drift 不碰。重测 822/540(保 win)+878/856(消回归)。

## 24. ✅ walkerDryReanchor 门=9 零回归净正 + "856 回归"实为噪声(2026-06-29)
off-path 门 4→9(只让真失败 move 触发)K=6 同 build A/B(4 关键档):
| archive | OFF P(wedge)/mean | ON P(wedge)/mean |
|---|---|---|
| steep-822 | 3/6 1039 | 3/6 860 (mean-17%) |
| long-540 | 2/6 818 | 1/6 694 ✓ |
| steep-878 | 0/6 236 | 0/6 240 ✓(门=4 的 177→396 回归已消) |
| diag-856 | **2/6** 574 | **0/6** 282 ✓ |
**关键**:diag-856 OFF 此批=2/6(门=4 那批=0/6)→ **§23 的"856 0→2/6 回归"实为 856 自身双峰噪声**(OFF 本就 0-2/6),非 flag。**坐实:K=6 per-archive P(wedge)噪声±2/6 太大不能归因,可信的是 8 档聚合**(门=4 聚合 16→6 远超噪声=真实)。
门=9 净正(7/24→4/24)+**零回归**,比门=4 温和但更干净;822 win 减弱(部分噪声,mean 仍-17%)。
→ 停止对噪声 corpus 微调(用户铁律 live=真相 corpus 派生)。用 gate=9 build 转 **live 长途验收**(#47 真判据)。门值 live 后再定(若 steep 仍卡可降门)。

## 25. live 验收揭示 DryReanchor 漏近距离 ram → 扩触发(hCol+yawErr>90)(2026-06-29)
gate=9 build live 长途(DryReanchor=ON,-599→-700,150):起步即 wedge @-671 diagDown(telemetry: hCol=true、**yawErr -145(背对节点)、cur2~1.5-2.5、totStuck 509**)+反复挖同块。画面 ANOMALY(原地横跳+视角抖+dig-loop)与 telemetry 一致。
**根因**:cur2~2 **远低于 off-path 门(9,甚至 4)→ DryReanchor 不触发**。这是 §15 的**近距离 ram-while-facing-wrong**(hCol+大 yawErr+cur2 小),与 DryReanchor 原针对的远 off-path repath-churn 是**不同机制**——证 corpus 聚合改善≠live 丝滑(异质机制须全覆盖)。
→ 扩 DryReanchor 触发:`(cur2>门) || (hCol && |yawErr到当前节点|>90)`,两签名同一 anchor 解(走回上一节点=稳定 bearing 转离墙)。同 stuck>50 门防瞬时 graze 误触。重测 corpus 防回归 + live 重跑看 -671 是否过。

## 26. ram-extension REVERTED(振荡)+ DryReanchor 净结论 + 近 diagDown-ram 仍开放(2026-06-29)
扩展触发 `||(hCol&&|yawErr|>90)` live 重测 -671:**远比 far-off-only 差**——bot 在 -673..-683 震荡、totStuck 508→**6009(~5min)**(far-off-only 那次是 509 慢恢复)。anchor-back 在**近 ram** 上制造振荡极限环(回 step-1→再逼近→再 ram→再 anchor)= §13/§16 同一教训。**REVERTED**。
**DryReanchor 净结论**:`far-off-path only`(cur2>9 + dry + stuck>50 → anchor step-1)= **corpus 验证净正**(聚合 P(wedge) 16/48→6/48,§22-24),是 session 首个真降 P(wedge) 的结构 fix,**保留 default-OFF**。
**仍开放(live -671 实锤)**:近距离 diagDown-ram(hCol+yawErr-145 背对+cur2~2)——身体卡墙朝向错,**aim-back 振荡、aim-node(§13)回归、距离门(§22)漏**。需非-aim 解(lateral strafe 滑离墙 / 黑名单该对角 repath 绕行)。这是 #47 的下一个 live blocker。

## 27. live 陡山路线=多异质机制叠加(DryReanchor 只覆盖一部分)(2026-06-29)
-599→-700,150 live 路线实为**纯陡山爬升**(bot y69→y94+),DryReanchor=ON 仍不丝滑,逐 tick 暴露**三个不同机制**:
1. **远 off-path repath-churn** — DryReanchor 覆盖(corpus 验证 16→6)✓
2. **近 diagDown-ram**(-671:hCol+yawErr-145 背对+cur2~2)— anchor-back 振荡(§26)✗
3. **stepUp/diagUp mount 失败**(-711/-654:`jump=true sprint=true up=true hCol=true hSpd~0.05 不上升` = "上坡跳不上方块")— bot 在陡山爬不动甚至倒退(-711→-654)✗
→ **#47"丝滑"在陡山路线 = 须同时治多个异质 mount/ram 机制**(stepUp-mount-fail 是代码里一堆 default-OFF fix 的老大难)。DryReanchor 是其中 repath-churn 分量的真解,非全部。
**注**:此路线(-700,150 SW 进 cliff/陡山)是最难类之一;DryReanchor 的有效性验证应以 corpus(用户铁律 replay=真相,聚合 16→6 已达)+ 中等 tractable journey 为准,而非最难陡山。下一攻坚单元:steep-ascent mount(stepUp/diagUp)live 实锤 + diagDown-ram(非 anchor 解)。

## 28. stepUp dormant-flag 组合 REJECTED(攻 mount-failure 失败)(2026-06-29)
攻 §27 的 stepUp/diagUp mount-failure:复用 4 个针对性 dormant fix(walkerStepUpCrestReach+AscentRamBobBreak+AscentRamJitterImmune+LevelRiserJump)叠 DryReanchor,K=6 vs DryReanchor-only:
- steep-878: mean 305→267 P(wedge) 0/6→1/6(中性/噪声)
- crest-815: mean **504→1225** P(wedge) **2/6→3/6**(大幅更差!)
→ 这些 situational flag 不治通用 mount-failure,**交互效应搞坏 crest-815**(又一组合 backfire,同 session 主题)。REJECTED,重置 OFF。
**stepUp-mount-failure 现有 flag 解不了** = 需新针对性诊断(精确 riser 几何:+1 clear vs +2 vs overhang vs no-runway)+ 新 fix。与 diagDown-ram(§26)一样是 #47 陡山剩余的独立攻坚单元。

## 29. stepUp-mount 诊断收窄:sprint 已处理,真 lever=early-jump-timing(2026-06-29)
攻 §27 stepUp-mount-failure。假说"sprint-jump 撞 riser 弹开→no-sprint 修"——**诊断发现已被现有代码处理**:Walker:4810-4820 `diagAscent`/`needJumpForStep` 近 riser 已 DROP sprint。且 2026-06-06 A/B-DISPROVEN 注释明载:re-enabling sprint on ascend 回归 hCol 13%→36%(jump 贴 riser 发→sprint 前冲撞更狠);**"sprint-jump 只在 EARLY launch(riser 前)才越台阶;需 early-jump-timing 改动,非翻 sprint"**。
→ no-sprint 假说**冗余**(代码已做),不实现避免 no-op/干扰。**stepUp-mount 真 lever = early-jump-timing**(到 riser 前提早起跳让弧线越过)——2026-06-06 已识别但因 delicate/高回归风险未实现。当前 jump 在 ascendJumpReady flatDist≤1.2(贴近)发。
**stepUp-mount 攻坚单元的精确靶**:early-jump 时机(flatDist 阈值放宽 + 对齐门),需在 steep corpus(878/815)+ summitArena 做 K≥6 A/B,且极易回归(jump 太早 miss/太晚 ram),是独立精调工程。这是 #47"上坡跳不上方块"的真正剩余工作。

## 30. ⭐ stepUp-mount 诊断到基岩:残留=对角 diagUp mount(cardinal 技术对它已证伪)(2026-06-29)
续 §29:early-jump(flatDist≤1.7)+sprint(sprintAscend)**对 cardinal +1 step 已实现**(Walker:4194-96,门=对齐 sideDist≤0.2 lateralMotion≤0.1 = "sprint-bunny-hop"丝滑梯)。
但 live stepUp-mount 失败实为**对角 diagUp**(-711:wp.x≠foot.x AND wp.z≠foot.z, +1 up):
- diagAscent **drop sprint**(L4810,A/B 证 sprint 撞 riser)
- early-jump **cardinal-only**;**L4198 明载"扩展 cardinal sprint-bunny-hop 到 DIAGONAL step-ups 已 A/B-disproven"**
→ **"上坡跳不上方块"残留 = 对角 diagUp(+1)mount**:两个对 cardinal 有效的技术(sprint / early-jump)**对对角都已被证伪**,只剩慢 late-jump grind(bistable:有时 mount 有时卡)。
**三机制全诊断到基岩**:① repath-churn→DryReanchor 修 ✓ ② 近 diagDown-ram→anchor 振荡(开放)③ diagUp-mount→cardinal 技术证伪的已知硬核(开放)。残留 ②③ 需**对对角几何的新方法**(非 cardinal 移植,已证不行)——是真正的深水区,prior+本 session 多次撞壁。

## 31. ⭐⭐⭐✅ 第二个结构 fix:pathfinderDiagAscendPenalty 消 crest diagUp-mount wedge(2026-06-29)
§30 基岩残留(对角 diagUp-mount,executor 对角技术全证伪)的 **planner 侧新解**:惩罚 dry diagUp → A* 改走 cardinal stepUp+walk(执行器 sprint-bunny-hop 可靠 mount)。
K=6(+DryReanchor ON)penalty 扫描:
| archive | pen=0 | pen=15 | pen=40 |
|---|---|---|---|
| crest-815 | 2/6 mean552 | **0/6 mean343** | 0/6 mean345 |
| steep-878 | 0/6 mean181 | 0/6 mean242 | 0/6 mean242 |
**crest-815 wedge 2/6→0/6(mean-38%)** = diagUp-mount 的可行解,绕过 executor 死结。pen=15≈40(15 足,少绕路)。steep-878 本就 0/6,mean 略增(penalty 略长路)。
→ **第二个验证有效结构 fix**,攻 §30 第二机制(diagUp-mount)。待:其余 6 档确认无广泛回归(penalty 只影响 diagUp move,无 diagUp 的档应不受影响)。

## 32. 裁决:pathfinderDiagAscendPenalty 净中性 REJECT(全 8 档聚合)(2026-06-29)
§31 续完全 8 档 K=6(+DryReanchor,pen=0 vs 15):
| archive | pen=0 | pen=15 | Δ |
|---|---|---|---|
| crest-815 | 2/6 | 0/6 | **-2** |
| long-540 | 3/6 | 1/6 | **-2** |
| steep-878 | 0/6 | 0/6 | 0 |
| rev-897 | 0/6 | 0/6 | 0 |
| diag-856 | 0/6 | 0/6 | 0 |
| water-757 | 1/6 | 1/6 | 0 |
| dry-627 | 2/6 | 3/6 | **+1** |
| steep-822 | 3/6 | 6/6 | **+3** |
| **聚合** | **11/48** | **11/48** | **0** |
**精确净中性**=纯 wedge-reshuffle(crest/long -2 被 steep-822/dry-627 +3/+1 抵消)。与 DryReanchor(16→6 真降)本质不同。**REJECT 为默认**(flag 留 code,default 0=no-op,无害 situational knob)。§17 铁律再兑现:crest-815 单档(2→0)伪 win,聚合揭穿。
**下一靶精确化**:全局 diagUp 惩罚分不清"该惩罚的不可 mount 陡 diagUp"(crest/long 受益)vs"本就 OK 的 diagUp"(steep-822 reroute 落更糟几何)。需**条件惩罚**=只惩罚几何上真不可 mount 的陡 diagUp riser(检测 riser 列 +1-clear vs blocked/no-runway),非所有 dry diagUp。这是 §30 diagUp-mount 残留的精确下一单元。

## 33. ⭐⭐ walkerDiagDownCenter 强信号:steep-822 diagDown-corner 6/6→2/6(2026-06-29)
§26 close diagDown-ram 的 **lateral-strafe 解**(非 anchor-back re-aim,后者振荡更糟):strafe lane-keep(Walker:4261-64)覆盖 waterClimb/diagUp/cardinal 唯独漏 diagDown→对角下降零 cross-axis 修正→drift 撞 perpendicular 角块。fix=镜像 diagUp 居中(4262)给 diagDown,gate=walkerDiagDownCenter(default OFF)。
K=6(+DryReanchor):
| archive | OFF | ON |
|---|---|---|
| steep-822 | 6/6 mean1038 | **2/6 mean827** |
| crest-815 | 0/6 mean390 | 0/6 mean362 |
steep-822(最差档=run6 diagDown 角块 §21)**-4 wedge**,crest-815 零回归。
**结构上优于 diagPen(§32)**:diagDownCenter 是只在 diagDown move 触发的 **executor strafe,不 reroute** → 无法把 wedge 搬到别档(diagPen 致命点),要么帮要么 no-op。
**待:全 8 档同 session 配对聚合定案**(§32 铁律;steep-822 OFF baseline session 间漂 3→6 双峰,须同 build 配对)。若聚合净正=第二个验证有效 fix。

## 34. ❌ walkerDiagDownCenter 净负 REJECT + ⭐⭐⭐ 噪声地板 meta-发现(2026-06-29)
全 8 档同 session 配对 K=6(+DryReanchor),ddc OFF vs ON:
| archive | OFF | ON | Δ |
|---|---|---|---|
| long-540 | 0/6(全<510 铁稳) | **5/6** | **+5** |
| steep-822 | 0/6 | 6/6 | +6(噪声) |
| dry-627 | 2/6 | 4/6 | +2 |
| crest-815 | 2/6 | 0/6 | -2 |
| steep-878/rev-897/diag-856/water-757 | — | — | ~0 |
| **聚合** | **4/48** | **15/48** | **+11 净负** |
**REJECT**(第 7 个):diagDown 强制 cross-axis 居中**干扰下降动力学**(与 back-hop damping/descentDriveReject 打架),long-540(OFF 铁稳)确定性引入 wedge。§33"6→2"=steep-822 噪声高抽样。**close diagDown-ram 对 aim(§26 anchor 振荡)+strafe(§34 干扰下降)两 executor 介入全免疫**。flag 留 code default OFF=no-op。

### ⭐⭐⭐ 噪声地板 meta-发现(本 effort 最深方法论结论)
**steep-822 OFF baseline 同 config 跨 3 批抽到 0/6、3/6、6/6**(diagPen pen=0=3、ddc 批1 OFF=6、ddc 批2 OFF=0)= 近均匀随机。**bistable 档的 K=6 P(wedge) 方差极大,小 executor 效应(±2-6)完全埋在噪声地板下,无法验证**。只有大到撼动聚合 >~8 的 fix(DryReanchor 16→6=-10)才浮出噪声。
推论:① 这解释了为何这么多点修"测不出稳定信号"——不是没效果,是效果在噪声地板内不可测。② **DryReanchor 结论稳**(定案靠聚合 -10 远超噪声,非 per-archive)。③ K=6 corpus 对增量 executor fix 是钝器;要验证小 fix 需 K≥20-30/档(巨贵)或更稳指标。④ **#47"丝滑"被内生随机双稳态(§19)阻挡**:wedge 是随机放大产物,增量 fix 既难修也难验;只有大结构改动(DryReanchor 量级,或更深的 executor recovery 重基/planner partial-path 重选)能实质撼动。

## 35. ✅ live 代表性 journey 特征化(DryReanchor build):基本丝滑,主导残留=水岸 dig(2026-06-29)
噪声地板(§34)→ 转向 live(唯一真裁判)。-520,180→-360,245(~165 格 ESE,Mountains 山地),DryReanchor ON。
**结果:ARRIVED dist=3,peakStuck=188**。遥测 ground-truth:
- 起步 planner 大搜索延迟(已知 progressive 域)
- 巡航 totStuck 0-7 平滑,**陡坡 y63→84→63 totStuck 仅 2-5 干净爬过**
- **唯一真 stall:水边土坡 dig totStuck 188(~9s,已恢复)@ -450,223 inW=True**
**画面 ANOMALY vs 遥测交叉(用户铁律 telemetry 是判据)**:
- 画面报"陡坡攀爬失败/贴墙横跳"→ 遥测 totStuck 2-5 advancing = **误读 stepUp 爬升动画**(推翻)
- 到达后画面报"彻底静止/死锁"→ 遥测 ARRIVED idle = **idle 假阳性**(bot 正确 passive,推翻)
**关键结论**:① DryReanchor build 在**代表性 tractable 地形基本丝滑**(陡坡爬得干净,corpus 陡山 wedge 非主导)② **主导真残留=水岸 bank-dig(~9s)**,非陡山——与早期 memory 一致 ③ corpus steep 档不代表典型 journey jank。下一步真靶=水岸 dig(有大量 prior 验证 fix,多 default-OFF)。

## 36. 🎯✅ 主导 live 残留(水岸 dig)被现成已验证 flag 消除:journey peak 188→0(2026-06-29)
§35 定位主导真残留=水岸 bank-dig。这些 fix(walkerBuoyantSearchFromSurface/BankDigSkipWhenCwpSwims/BankDigSkipOverhang/VineDescentDrop)在 build 里但 default-OFF(prior session 已 live+GT 验证)。
启用 4 水岸 flag(+DryReanchor)反向重跑 -358→-520(~176 格,穿同片水域):
**peak totStuck = 0 全程!ARRIVED dist=1**(vs 正向 flag-OFF peak 188 的 ~9s dig)。含多段 inW 水域穿越、爬升 y62→81 全 totStuck=0。唯一略慢=水边 ~15s 机动(totStuck=0 无 churn)。
**结论(#47 真方向)**:丝滑 journey = DryReanchor + 已验证水岸 flag 全 ON。本 session 追 corpus 陡山 wedge 是支线(噪声地板+非主导);**真实主导卡点的 fix 早已验证存在,只是 default-OFF**。下一步=枚举全部已验证 default-OFF fix 组成"丝滑 flag 集"全 ON,多 journey 确认端到端丝滑→逼近 #47 验收。

## 37. ⭐⭐⭐ live 多 journey 特征化:验证 flag 集使典型地形丝滑 + 真残留=开阔水 churn(totStuck 失明)(2026-06-29)
三条 live journey(DryReanchor + 渐增 flag):
- **J1 正向 -520→-360(水岸 flag OFF)**:ARRIVED,1× 水岸 dig totStuck 188(~9s)
- **J2 反向 -358→-520(5 水岸 flag ON)**:**peak totStuck 0 全程丝滑** ✓(同片水域 dig 消失)
- **J3 -519→-430,330(9-flag 集 ON)**:平滑巡航 peak0,但**末段开阔水/沼泽 near-goal churn ~140s**(dist 卡 9-40 振荡,bot 水面游泳绕 node 不进门)→ 取消。goal 落 water-swamp 水体(blocks 证 water+clay+seagrass)。
**两个决定性发现**:
1. **已验证 default-OFF flag 集(DryReanchor+4水岸+2ascent+parkour-water)使典型地形(水/陡/巡航)丝滑**——J2 全程 peak0。**#47 真方向 = 把这些 flag flip ON**(本 session 追 corpus 陡山是支线)。
2. **⭐ totStuck 对 net-progress loop 完全失明**:J3 churn 140s 但 totStuck=0(执行器每 node"进展"而路径在水面绕圈)。→ **整个 corpus P(wedge)=totStuck>800 方法漏测开阔水/swamp churn 这类真残留**!jank 判据须补 net-progress(dist 窗口不缩)。
**真残留 = 开阔水/沼泽 surface-swim near-goal churn**([[project_openwater_surfaceswim_nodeclose_churn]],需 net-progress 度量 + within 门放宽 / openOceanArena 专修)。这是 #47 末段丝滑的下一精确靶,且 corpus 测不到它(必须 live + dist-trace)。

## 38. 🎯🎯✅ walkerWaterWalkReach 消除开阔水/swamp churn → 完整 11-flag 丝滑集闭环(2026-06-29)
§37 真残留(开阔水/swamp surface-swim near-goal churn)的现成 fix:**walkerWaterWalkReach**(Walker:2270,default-OFF)——专治"水面 walk 节点浮力 body orbit/freeze 的水里卡住 jank"(cur2 floor ~0.455 just over REACH_DIST_SQ=0.45 → within 永不 fire,turn/corner 节点 passed 也不 fire → 绕圈/冻结)。我 9-flag 集漏了它。
**决定性 live 验证**:启用 walkerWaterWalkReach + walkerWaterStepDownFloat,从北重逼近 J3 churn 过的 swamp goal -430,330(原场景):
```
dist 81→52→37→24(z308 原churn区)→10→1 ARRIVED,35s 连续净进展,TOT=0,零 churn
```
**直穿 z300-316(此前 churn 140s 未过的区)平滑到达**。screen"卡死/水下停滞"全是误报(net-progress 连续)/到达后 idle(goal 在水里)。
**完整 11-flag 丝滑集闭环**(全 prior 已验证 default-OFF,本 session live 确认):
1. walkerDryReanchor(repath-churn,§22-24 聚合 16→6)
2-5. 水岸:BuoyantSearchFromSurface / BankDigSkipWhenCwpSwims / BankDigSkipOverhang / VineDescentDrop
6-7. ascent:AscentRamBobBreak / PillarReachGoalNoSnap
8. ForbidParkourFromFloatingWater  9. DeepWaterFloatBeeline
10-11. **WaterWalkReach / WaterStepDownFloat(开阔水 orbit,§37→§38 缺失的最后一块)**
**三大 live 残留全闭环**:① 水岸 dig→水岸 flag(J2 peak0)② 陡爬→ascent+DryReanchor(J1/J2 clean)③ 开阔水/swamp churn→WaterWalkReach(本测 dist81→1 平滑)。
**#47 真答案 = 这 11 个 prior 已验证 default-OFF flag 全 flip ON**(用户决定)。本 session 追 corpus 陡山 wedge 是支线(噪声地板+非主导+totStuck 对 churn 失明)。下一步=11-flag 全 ON 跑多条随机长 journey + replay 验收(net-progress 判据,非 totStuck)。

## 39. ⚠️ 验收 journey-A:11-flag 集仍非全丝滑 = wall-corner stall 族残留(2026-06-29)
27-run 验收第一条:-429,330→-510,200(~150 格),11-flag 全 ON + pathArchive。net-progress 追踪(totStuck 对此失明):
- t5-20 dist 97→56 良好(TOT=0)
- **t25-40 岩岸 climb-out ~20s 停**(-481,247 y62→69 爬出水,恢复)
- **t60-100 土墙/水交界 ~45s 停**(-513,233,画面"贴墙横跳抖动循环")
- **t105-130 土/石壁交界死胡同 25s+ 停**(-514,222,画面"贴墙打转无法脱困")→ 取消(worstNoProg 45s,非丝滑)
**结论:11-flag 集闭环了主导残留(水岸 dig/陡爬/开阔水 churn),但岩/土/水交界 wall-corner stall("贴墙卡住"=用户问题③)仍未解** = §26/§15 族(hCol+facing-wrong+几何墙/角块挡 direct line,**对 aim anchor §26 + strafe diagDownCenter §34 两 executor 介入全免疫**)。
**这是 #47 丝滑的精确剩余 blocker**:rocky/dirt 水岸的连续 wall-corner stall(20-45s each,可恢复非死锁但远非丝滑)。totStuck 全程 0(对 net-progress 停滞失明,§37 铁律再现)。journey-A 给了确定 repro 区(-480~-515,z220-256 岩石水岸迷宫)。
**注**:-510,200 goal 可能落 rocky 水岸迷宫(部分盲选);但 wall-corner stall 本身是真残留。#47 验收未过:wall-corner 族需新颖解(非 aim/非 strafe,可能 = 黑名单该 corner-edge repath 绕行 / planner 避 rocky-water-edge 节点)。

## 40. ⚠️ walkerWallCornerFastChurn:机制生效但温和(worst 45→35s),岩石水岸迷宫是深残留(2026-06-29)
§39 wall-corner fix 的 live 验证。13-flag(11+WallCornerFastChurn+debug)反向重跑 journey-A repro(-506→-429,330,穿同片岩石水岸):
**ARRIVED dist=2,worst net-progress 停滞 35s**(vs journey-A OFF 45s)。anti-churn 日志 **escapes 间隔 8s**(20:33:33→33:41)= WALL_CHURN_WINDOW 160t fast 窗**确实触发**(日志串硬编码"400 ticks"是 L1425 用 CHURN_WINDOW 常量的误导,实际窗=effChurnWindow 160t)。
**结论**:① fix 机制生效(sustained-hCol→8s escape vs 20s,确认)② 但**温和改善非银弹**:-516,224(20s)/-516,230(15s)/-499,251 水袋(**35s**)仍 churn。35s 处 ~4 次 escape 都 blacklist+back-off 但 bot 仍返回 = "sole route 执行器穿不过 + blacklist 邻格也堵"的深层 deadlock(L1457 老问题),提速 escape 不够。
**honest #47 现状**:11-flag 使多数地形丝滑(开阔/水/陡);**岩石水岸多-wall-corner+水袋迷宫(-516~-499)仍 15-35s 停**,WallCornerFastChurn 只温和缓解。这片可能也是 goal(-429,330 swamp / -510,200 rocky-edge)盲选导致路由穿恶劣 pinch。深层解需 planner 层避开此类 rocky-water-edge pinch 节点(非执行器恢复提速)。A/B 非完全受控(forward vs reverse 路径不同),35<45 仅启发。

## 41. ⭐ 岩石水岸残留精确诊断:swimAshore/pillarUp +2-3 climb-out(buoyancy 错配,#63 族)(2026-06-29)
§40 rocky-water-edge stall 的确切 stuck move(反向 journey telemetry):
- **-499,251(35s)= `pillarUp` node=-500,64,250,bot -499,61-62 inW/undW=true,|dY|1.8-2.8** = 浮力 bot 从水里 pillar +2-3 到 y64 岸,pitch90 looking-down 想放支撑但放不了(浮力+水下)
- **-516,224(20s)= `downBreak`/`traverseBreak`/`stairUpBreak`** 半身在水挖石岸 climb-out
根因:PillarUp 已有守卫 `if(isFloatingWater(from))return null`(L38),但 `isFloatingWater=isWater(foot)&&isWater(foot-1)`(只判 ≥2 深浮水)。planner 从**浅水/岸格(isFloatingWater=false→守卫不挡)**提交 pillarUp,**浮力 executor 漂/沉离那格进更深水→pillar 不起来** = buoyancy-vs-planner 错配 = **task #63 swimAshore +2-3 climb-out 已知深开放问题**。
**这是 11-flag 集闭环后 #47 的最后单一深残留**:不是泛 pinch,是精确的 #63 族(浮力 bot 在水边 +2-3 岸 pillar/dig climb-out)。深解需 buoyancy-aware climb-out 执行器 OR planner 浮力模型(从可能漂离的浅水格不提交 +2-3 pillar)——多 session 级,prior 也未全解(#63 仍 pending)。bot 现在水中(relaunch-unsafe),精确诊断已成,fix 需 fresh focus + dry 起点 + 严格 live(水域 stochastic)。

## 42. #63 族 flag 部分有效:巡航/浮起丝滑,tall-stone-bank climb-out 仍深残留(2026-06-29)
启用 4 个 swimAshore/bank-dig climb-out flag(SwimAshorePillarDespiteDeepDig/FutileBankDigRelease/BankDigForwardExit/FloatingBankBobFreeze,共 17-flag)西去 -428,330→-520,180(穿大水体+爬岩岸):
- **-428→-486 巡航 90 格丝滑(worst 10s)**,含从 y50 深水浮起(undW→false)干净 = #63 族帮了
- **-486,240 CHURN 60s**:path=`swimUp→stepUp→swimAshoreClimb→stairUpBreak→traverseBreak×3→bridgePlace` = **挖穿 +4 高石岸爬出水**。反复 replan(20:45:02-48 五次)全返回同路径=确定性 re-search deadlock(L1457)。"pillar takeover engaged"触发(SwimAshorePillarDespiteDeepDig)但**石岸 pillar 不过(需挖石),浮力水中挖石慢/卡**。
**结论**:#63 族 flag 解 dirt/mud 浅岸 climb-out + 深水浮起,但**不解 tall-stone-bank(+4 石岸需挖穿)从浮水 climb-out** = #63 最硬子情形。这是 11+#63 flag 集闭环后 #47 的真·最后深残留:**浮力 bot 挖穿高石岸爬出水**(石头要挖、浮力够不着、水中挖慢三重)。可能也是 goal -520,180 在水体对岸高地、planner 只此一路穿石岸=部分地形必然。深解=buoyancy-aware 高石岸 dig-climb 执行器 OR planner 给 tall-stone-water-bank 加 cost 绕行。

## 43. ✅ 东向公平 journey 实证 17-flag 广泛丝滑:126 格 worst 10s(2026-06-29)
§42 后做公平测试:bot 在 -486 石岸,改 goto 东向已穿越干地 -360,245(126 格,背离石岸,17-flag)。
**ARRIVED dist=5,worst net-progress 停滞 = 10s**(唯一 -465,247 水中 10s 机动,其余 dist 126→5 全程连续)。
**确认**:① 17-flag 集**在可穿越地形完成全 journey 丝滑(worst 10s)** = tall-stone-bank 60s churn 是**特定必经石岸 exit 的地形残留,非通用失败**。② 第 4 条实证 flag 集广泛有效(J2 反向 peak0、swamp dist81→1、东向 126 格 worst10s)。
**残留分层(诚实)**:
- 频繁**小水域 surface-swim 机动 ~10s**(如 -465,247、journey 各处水段)= 非完美丝滑(用户"零 >3s 停"bar 未达)但远小于卡死,可恢复
- 特定 **tall-stone-bank water climb-out 60s**(§42)= 必经石岸的深执行器残留
**#47 现状收敛**:17-flag 使可穿越地形 worst≤10s(vs 修前 35-60s),大幅趋丝滑;完美丝滑剩两类——小水域机动(~10s,需 surface-swim 顺滑度精调)+ tall-stone-bank(60s,需 buoyancy dig-climb 执行器)。两者都需聚焦工程,但**flag-flip 已把 #47 从"普遍卡死"带到"worst≤10s + 两类特定残留"**。

## 44. ⭐⭐ 致命水岸 climb-out 死锁根因+双 fix live 验证(环境重建后新世界)(2026-06-29)
**背景**:系统重启(全环境丢失),`/root/source/minecraft` 新路径重建(JDK/图形栈/venv-uv/MCP路径修复),新随机世界(Peaceful+AllowCommands,种子新)。恢复后基准:干地 45 格 worst=0s、穿水 65 格 worst=0s(17-flag,零 stall)。
**致命 bug 发现(R1 随机 journey)**:bot 从水底起步爬岸,在 (-21,60,-42) 水岸 climb-out **溺死**(Peaceful 不免溺水)。
**根因链(日志决定性,26 循环实锤)**:`pillar takeover engage → 50t place-futile(浮力顶点 60.2 < fill 格 need 61.9,bob 永远顶不过水面 fill cell)→ bail 设 climbPillarGaveUp → repath 交替换 climb node(-20,61,-43 ↔ -21,61,-42)→ climb-context 重置在 L2589 清掉 latch → pillar re-engage` ×26(~2.5s/循环)直到空气耗尽溺死;bank-dig fallback 从未获得完整接管。**totStuck 系判据对此完全失明**(pend=true 期间计数被 actuator 分支绕过),且 walker 深水等待期(idle)不 tick。
**双 fix(committed,default OFF)**:
- `walkerDrowningEscape`:undW 且 air≤60(~3s)→ LATCH 抢占一切 climb/dig/pillar actuator:hold swim-up jump;顶头被 cap 或 hCol 时反向 yaw 倒退离岸找开水面;air≥240 或出水才释放。**把任何未知水下死锁从致死降级为呼吸-重试**。
- `walkerClimbGaveUpSticky`:bail 时把 climbPillarGaveUp 锚定 foot±3/TTL 300t,climb-context 重置不再清 latch → dig/recovery 真正接管。
**live A/B(同场景确定性复现:沉底 y54 → 同 goal goto)**:`DROWNING-ESCAPE engaged: air=-16`(已在扣血临界)→ 3s 后 `released: air=44` = **bot 存活(hp 20 全程)**,随后完成穿水+爬岸 y54→y76、dist 136→26。**致死→存活+通过,双 fix 验证成立**。
**附带教训**:①随机验收 goal 必须 XZ(Y-agnostic)goal——y 盲猜 65 把 goal 埋进山体(A* goalReached=false 正确,bot best-effort 绕圈是 goal 无效非执行器 bug);accept_run.py 已改 `xz`+near。②pgrep/pkill -f 会自匹配 wrapper(exit 144),杀 client 用 /proc cmdline 过滤。③新环境三坑:.mcp.json 老路径、venv shebang 老路径(uv 重建)、runCommand RPC 无效须 chat.send。

## 45. ✅✅ #47 验收协议完成:3 轮 ×(随机 journey + replay×3)= 12 runs 全 PASS(2026-06-29)
新世界(Peaceful/AllowCommands),21-flag 全 ON(17 验证集 + DrowningEscape/ClimbGaveUpSticky + debug/archive)。随机 goal 用 XZ(Y-agnostic)+ near=3(§44 教训)。判据:live = ARRIVED + 3s 采样下 net-progress 停顿 ≤3s;replay = mc.debug.replay(replan 忠实重跑)ARRIVED + maxStuck < 800 wedge 阈 + 零死亡。

| 轮 | Live journey | Replay×3 maxStuck |
|----|--------------|-------------------|
| R1 | (-116,76,77)→XZ(-5,-33) 156格 34s **worst=0s** | 77 / 26 / 35 全 arrived |
| R2 | (-6,60,-31)→XZ(62,100) 148格 55s **worst=3s**(y60→105 爬山45格) | 22 / 26 / 174 全 arrived |
| R3 | (64,105,99)→XZ(157,170) 117格 37s **worst=0s**(y105→82 下山) | 48 / 20 / 37 全 arrived |

**全 12 runs:live 零 >3s 停顿;replay 全 ARRIVED、maxStuck 峰 174(远低 wedge 阈)、hp 全程 20 零死亡**。地形覆盖:草原巡航、穿水域(R1 前段)、45 格爬山、山顶下坡——"略微后退/向后跳/水中横跳/贴墙/上坡跳不上/下坡回看"在三条随机路线上均未出现。
**修复过程中的 replay 基建 bug(§44 后续)**:①replay tp 后 client isUnderWater/air 残留旧值 → DrowningEscape 误 engage 冻结 drive(已修:WorldView 真相门+活体门,committed)②replay#1 把 bot tp 回水底 air=0 起点致溺死,后续 replay 全在驱动尸体(1199 恒定假象)→ 验收流程加 per-run hp 检查。③totStuck 跨 goto 不清零(residual 基线),run_case 判读须注意。
**限定(诚实)**:①视频通道(live-screen-watch)因 litellm 端点在环境重建后 NXDOMAIN 不可用,本轮验收以 telemetry(净进展+maxStuck+ARRIVED)为判据——协议的视频验证手段缺失,恢复端点后可补拍。②三条路线未覆盖 §42 tall-stone-bank 必经挖穿场景与 §41 深水岩岸(该类地形此世界此区域未抽中;§44 双 fix 已单独 A/B 验证该场景由致死→存活通过)。③21 flag 均 runtime-ON / code default-OFF,commit+flip-default 待用户决定。

## 46. ⚠️ 周期 2 全败:周期 1 的 12/12 有抽样运气成分,三个真实残留浮出(2026-06-29)
周期 2(C2,同 21-flag 同协议)3 条随机 journey **全失败**:
- **C2-J1 摔死**:从 R3 终点丛林山顶(160,82,172)起步,plan 含 `climbUp` node=(158,80,170)(藤蔓攀爬);bot 在藤列正下方**自由坠落 30+ 格摔死**(y82→42 每 tick 直落,cur2=0.075 XZ 已对准 = 没抓住藤/藤不在)。= **vine/climbUp 执行器脱落致死**(与 §44 溺死同级的安全缺口;fell-protection/MLG 未起效——bot 无水桶)。
- **C2-J2 CHURN 91s @(35,82,87)**、**C2-J3 CHURN 92s @(11,67,66)**:后者日志实锤 (6,61,64) `arc-wedge RECOVER nodeDy=-1 ram` 反复 80+ 次不脱(下降节点 ram wedge,"贴墙卡住"族);且 tracker 停止时 bot 又在正常走(net-progress 判据下它最终会脱但 >90s)。
**诚实结论**:周期 1(§45)12/12 PASS 是**较友好地形抽样**;公平连续抽样下 #47 残留=①vine/climbUp 脱落致死 ②nodeDy=-1 下降 ram wedge(>90s)③山地 churn。**#47 未达,验收协议须周期 2/3 全绿才算**。改进:验收装备加 water_bucket(MLG 反射自救坠落)。

## 47. 三周期验收判定 FAIL + 残留收敛到两核心机制(2026-06-29)
**三周期总分**:C1 12/12 PASS(§45)/ C2 0/3(§46)/ C3 混合(J1 churn 91s;J2 到达 worst=31s 但 replay maxStuck 811/**1200**/734 一次不到;J3 到达 worst=6s 但 replay×3 全不到 480-527)。**验收协议判定:FAIL**(需连续周期全绿)。
**⭐ 残留大收敛(跨 C2/C3 失败样本)**:churn/wedge 几乎全部落在**同一机制** = `stepUp` mount 失败 grind:node(14,58,102) 累计 1400+907+673 tick、(6,61,64)/(8,65,66) 族同型;C3-J2 replay wedge 位置同点。**= #47 问题⑥"上坡跳不上方块"的 stepUp/diagUp mount 双稳态**(§27-32 已诊断到基岩:对角技术全 A/B-disproven、planner 全局惩罚净中性,executor+planner 双撞墙),新世界丘陵台阶地形高频触发。现场特征:bot 悬空(onG=false)y 已到节点层(58.17)无碰撞(hCol/minorCol=false)但 hSpd 仅 0.05,跳起-XZ 进展乏力-滑回循环;**cardinal Z 向 stepUp 也 grind**(§32"cardinal 有效"的反例)。**replay-0016-1782989368760.json 可确定性复现此 wedge = 现成 A/B 台**。
**第二核心**:climbUp 藤蔓脱落致死(C2-J1,§46)。C3-J3 replay 全不到待判(可能 arrive 判据缺陷:忠实 replay 里 XZ goal 的 x 终点可变)。
**下一攻坚(优先序)**:① stepUp mount 双稳态(用 replay-0016 A/B;方向:mount 期 XZ 空中推进增强 / 起跳前 approach 对齐重基——注意 §26/§34 两次 REJECT 教训,任何介入须 K≥6 replay-0016 + 8 档聚合验证)② climbUp 攀爬保持(脱落检测+重抓/下撤)③ 验收协议重跑三周期。

## 48. walkerStepUpBackoffRetry:部分有效 + wedge 真几何诊断(2026-06-29)
**fix 设计**:mount grind 的两个签名(a)grounded 贴脸静止起跳(press)(b)悬空 rim-graze bob(hCol 每 tick、onG 永假、y 窄幅弹;replay-0016 实测 500+ tick)→ 触发 12t 直线后退(camera-frame commandMove 零镜头动)开出助跑距离,60t cooldown。
**A/B(replay-0016,K=3)**:OFF 811/1200(不到)/734;ONv2 528/**1188(不到)**/622,BACKOFF 触发 5 次。触发→成功后退 1.4 格落地→重逼近,**部分实例数次 retry 后通过**(replay#1/#3 到达),但 1188 一次仍 wedge。到达率 2/3 持平,中位 811→622 轻降。**部分有效非根治**(K=3 且 bistable,按 §17 铁律不可归因强效)。
**⭐ wedge 真几何(触发后数据揭示)**:node(14,58,102) 不是简单 +1——地板 y56 → **窄台阶 y57**(bot bob 56.8-57.25 = 在窄台阶边缘蹭,身体截面卡 riser 面,onG 永假的根源)→ 目标 y58。执行假设"从 y57 面起跳 +1"但 bot 站不稳窄台阶。后退落 y56 后变 +2(单跳不可达)→ 必须两段连跳,retry 成功率随机。**mount 双稳态的难点实例 = 窄台阶(1格深)approach**;根治方向:窄台阶两段连跳节奏 / approach 落点精确到台阶中心(非 riser 贴脸),需 K≥6 replay-0016 校验轮专门攻(§17:小样本 bistable 不可调参归因)。default OFF committed。

## 49. 周期 4 + climbUp 复现全绿 + 残留终态收敛(2026-06-29 session 末)
- **C2-J1 摔死场景复现全绿**:同起点(160,82,172)同 goal XZ(138,37),带水桶+当前 build:**ARRIVED 46s worst=0s hp20**(未用 MLG)。原摔死主嫌疑=**replay restoreBlocks 污染起步区**(C2-J1 从 R3 replay×3 反复 restore 的走廊起步,规划的 climbUp 藤与实际世界 desync)+ 无水桶放大致死。流程修正:accept_cycle 每周期 `/spreadplayers` 到新区起步(escape 污染走廊)+ 装备含水桶。
- **周期 4(22-flag+新流程)**:J1 到达 74s worst=3s(replay maxStuck 74-89 极干净;arrived=False 是 arrive_x 单轴判据对 XZ-near 圈的缺陷,非执行器问题)。**J2/J3 卡同一点 (273,63,-5) 91s = swimAshore +2 浅水岸 bob**(node y64,浮力顶 63.2 差 0.8,hCol 对准 571+ tick)= **#63 族核心未解的又一实例**:不缺氧(undW 闪烁)故 DrowningEscape 不触发;ClimbGaveUpSticky 15s TTL 只延缓 pillar↔dig 循环非根治。
- **#47 残留终态(两大深残留,均已特征化+有确定性复现档)**:① **stepUp mount 双稳态**(窄台阶几何,§48 部分缓解,replay-0016 A/B 台)② **水岸 +1.5~2 climb-out 执行成功率**(#63 族,浮力顶点差 0.8 几何;§44 已消致死性;replay-0021+ 档可复现)。两者都需执行器重基级工程(mount 两段连跳节奏 / climb-out dig-垫脚可靠序列),非 flag 级点修可解——本 session 7 个 REJECT + 2 个部分缓解的经验边界。

## 50. ⭐ C4-J2/J3 水岸 91s 的真根 = allowBreak 默认 OFF(权限漏开)(2026-06-29)
§49 的 (273,63,-5) swimAshore bob 深挖:①**attack=false 全程 + bail→re-engage 同秒** → digFallbackHere=false → 查默认:**`allowBreak=false`(全局 break 权限)**——环境重建后 BotConfig 持久化丢失,重设 flag 清单漏了它(allowSwimEscapeBreak=true 但 allowBreak 是总开关)。dig 兜底被关 → bank-dig 永不跑 + ClimbGaveUpSticky 的 digFallbackHere 门也 false → latch 永不设 → pillar 无限循环。②**开 allowBreak+allowPlace 重演**:dig 分支立即激活(355 dig tick),bot 虽被 tracker 判 CHURN(>90s)但**自主挖穿水岸并走完全程到达 goal**(终点 243,-30 dist3.3)。
**结论**:#63 水岸 climb-out 从"永卡 deadlock"降级为"慢但必过"(25× 水中挖掘惩罚 ≈19s/块 的物理现实)。**验收 flag 清单必须含 `allowBreak:true`+`allowPlace:true`**(harness 权限,非 walker fix)。丝滑 bar(≤3s)仍差(挖穿需 60-90s)——进一步提速属于水岸执行器工程(如岸沿单块 dig 优先/预判 dig),与 mount 双稳态并列的专注单元,但**严重度大降**。

## 51. 三周期 C5-C7 全景(权限修正+视频通道恢复后)(2026-06-29)
**基建**:litellm 恢复(FQDN .svc.xinao.net,用户给)→ live-screen-watch 全程在线(Monitor+uv run);accept_cycle 修三缺陷(archive 按 header.start 匹配 / replay 期 pathArchive:False / live 前恢复 pathArchive)。**第三个漏开权限 flag:`allowWaterBucketFall=false`**(MLG 反射全局关,带桶也不救)→ 验收 flag 集定稿 = 21 walker/pathfinder flag + walkerStepUpBackoffRetry + **allowBreak/allowPlace/allowWaterBucketFall**。
**9-journey 结果**:9 live 中 7 ARRIVED(C7-J1 完美 34s/0s+replay 20/19/20 三绿;C6-J2 40s/3s+84/62/18 三绿;C5-J2 34s/0s+25/107/17 三绿 = **三条 journey 达成 live+replay×3 全绿**);2 CHURN(C5-J3 mount 双稳态洞穴 +2 slid-back、C7-J2 水岸 dig 慢通道>90s——telemetry 证 08:54:18 最终 topped-out 成功=慢但必过);2 replay 死亡(C6-J1#3 深坑水下 pocket 溺死、C5-J1#3 藤蔓摔死)。
**新根因+fix**:①**DrowningEscape pocket 盲区实锤**(-254,61,-219 深坑:头顶 solid+reverse 也堵→打转 1 分钟溺死 hp5.3→0,DROWNING-ESCAPE engaged 但直线逃逸不够)→ **升级 8 方向轮询探测**(2 格外 eye 水+无顶盖=可浮方向,25t/换向,probe 轮转防伪开放方向死锁;已编译待重启生效)。②C5-J3 洞穴 wedge:arc-wedge RECOVER(JitterImmune)确实触发(wedgeT75+)但 repath 反复提交同一 +2 节点 = **sole-route recovery 空转**(mount 双稳态最深形态)。③丛林树干摩擦 15s(bear/lastAim 差 114°,aim 瞄远 carrot 身体撞近树干,replay-0018 稳定 284)。
**画面通道结论**:滞后 ~20s+把 dig 慢通道/replay 重演/起步间隙全报 ANOMALY,须逐条 telemetry 交叉(误报率高但真事件——溺死、藤蔓摔死、放块脱困成功——都抓到了)。
**判定:仍 FAIL 但结构清晰**——去掉两死亡(反射已修:MLG 开+pocket 逃逸)后,阻塞丝滑的只剩:mount 双稳态(sole-route 变体)、水岸 dig/pillar 慢通道(>90s,功能正确速度不达)、丛林树干 15s 摩擦。

## 52. walkerCarrotHColShrink A/B-REJECT(第 8 个 aim 介入证伪)+ 真修方向(2026-07-02)
猜想"树干摩擦=carrot 瞄 LOS 斜缝身体过不去→hCol 时收缩 pursuit 到 cur node"被 replay-0018 K=3v3 证伪:OFF 382/236/236 vs ON **179/513/516(中位更差)**。反转认识:far carrot 的偏离 bearing 本身就是 string-pull 的绕树 detour,收缩到 node = 正面撞树干。**树干摩擦不是 aim 层 bug,真修 lane = body-width-aware LOS**(losWalkable 从射线测试升级为 0.6 宽走廊测试,让 carrot 不吃身体过不去的斜缝)——属 pathfinder/几何工程,非点修。flag 保留 default OFF + DISPROVEN 注释。附注:新 build 下 replay-0018 maxStuck 发散(236-516)提示树干区本身 bistable,后续 A/B 须 K≥6。

## 53. ⭐⭐ walkerCarrotBodyLos 强效验证(树干摩擦真修 + 藤蔓摔死诱因一并消灭)(2026-07-02)
§52 定的 lane 实现:`PathSmoothing.losWalkableBody`(连续插值 4 角 AABB 走廊测试,半宽 0.3,foot+head 通行+中心地板支撑),carrotPoint 的 LOS 门在 flag ON 时用它替代中心射线;path smoothing 保持廉价射线不动。
**A/B(replay-0018 丛林档,ON/OFF 交替 K=6)**:**ON 到达 3/3**(maxStuck 305/423/196)vs **OFF 到达 0/3**(177-229 干净却全程死亡:server 日志 "fell from a high place"+"fell off some vines")。机制:far carrot 沿"射线通/身体不通"的树冠斜缝把 hitbox 拉出枝叶边缘 = 藤蔓摔死的直接诱因;走廊测试让 carrot 停在最后一个身体可走节点。**一个 fix 同时闭环两个残留:丛林树干摩擦 lane + climbUp/树冠摔死**。default OFF committed,验收 flag 集 +1(共 26)。附注:OFF 轮 MLG 未接住(落点树叶非 MLG floor?),BodyLos 让摔根本不发生。

## 54. C8-C10(26-flag 含 BodyLos):live 9/9 全到达 + 零死亡(2026-07-02)
BodyLos 加入后三周期:**9/9 live ARRIVED、零 CHURN、零死亡**(C5-C7 为 7/9+2 replay 死)——历次协议最佳。worst 分布:4/9 ≤3s(达丝滑 bar)、5/9 6-67s(尾部=水岸慢通道/mount 残留:C8-J1 31s、C8-J3 67s、C10-J2 31s)。replay 大量 arrived=False 但 maxStuck 极低(21/16/17 等)=**arrive_x 单轴判据缺陷实锤**(XZ-near 圆上到达点 x 未跨阈值;C10-J2 全 True 426/432/395、C9-J3 2/3 True 是真实混合)。判据已修:replay 后查终点距 goal XZ<10 且存活(atGoal)。4 条 journey"NO matching archive"(pathArchive 恢复时机/起点匹配容差)待查但不阻塞。C11-C13 带修正判据重跑中。

## 55. C11-C16 六周期 + 协议工具链定稿 + allowBreak 副作用发现(2026-07-02)
**协议工具链三修**(atGoal 圆判据替代 arrive_x / archive_for 按 header.start 匹配 / flush 竞态重试 18s)后 C14-C16 数据干净:**每周期 J1 全绿 4/4**(C14-J1 28s/0s+14/27/16、C15-J1 39s/3s+27/18/27、C16-J1 148s/6s+19/29/34 —— **live+replay×3 全绿已 6 条累计**),live 到达率 BodyLos 后 17/18,零死亡维持。
**新 wedge 类(⭐下轮首攻)**:C16-J2/J3 双 journey 同点 (-252,70,209) churn = **干地 traverseBreak/downBreak 挖掘 stall**(yawErr=0 对准、onG、pitch 朝下、800+ tick 无进展)。**根因假设 = allowBreak 全局开启的副作用**:break 权限开→A* 开始提交 break-heavy 路径(成本模型 27.5/block?)而执行端挖掘慢/无效(工具选择/硬度/aim 射线),产生干地版"慢通道"——C4 前从未见此类。修复 lane:①pathfinder break 成本校准(挖掘时长真实化,让 A* 少选 break 路径)②执行器 break 有效性验证(为何 25s+ 不破块:工具?aim?)。C14-J3 (155,62,-172) 待定性。
**16 周期累计判定**:live 到达率 34/36(94%),零死亡(BodyLos 后),全绿 journey 6 条;三周期全绿闸门未过(每周期仍有 1-2 条撞慢通道/break-stall)。残留清单更新:①break-stall(干地新类,double-journey 复现档可取)②水岸 dig/pillar 慢通道③mount sole-route。

### §55 补:break-stall 验尸 = 根本没挖(非挖得慢)
现场 walk-keys:**attack=false 全程**且 walk-keys 在打印(主流程走到尾,未进 dig early-return)= traverseBreak/downBreak 的 pending-edge 挖掘执行链**在某个门前断掉**(breakHold 从未按下),bot 站在 node 上对准朝下永远等待。非硬度/工具问题。下轮直攻:trace hasPendingEdge→breakHold 链上的 gate(嫌疑:allowBreak 在该路径读的是启动时快照?edge.toBreak 为空?或 break 分支被别的 flag 短路)。这解释了为何"25s+ 不破块"——从未开始破。

### §55 修正:break-stall 真相 = break 后 within 死区 + drive 反向(非 break 链断)
再验尸推翻"链断"猜想:walk-keys 在打印 = `hasPendingEdge=false` = **toBreak 块已不 solid(挖掘早完成)**。真 stall 在 break 完成后的推进段:①bot 停在 node 旁 cur2=0.68(> within 门 0.45,永不 advance;passed 也不触发)= `walkerStepUpCrestReach` 注释描述的 orbit 死区的 **traverseBreak 平地变体**;②walk-keys `driveYaw=-123` vs t= 行 `bear=76` **反向 160°**(drive 朝反方向,dryDesc=true 参与)。两条线索:死区 advance(CrestReach 思路推广到 break-move)+ dryDesc/driveYaw 反向根因。下轮:先试开 `walkerStepUpCrestReach`(现成 flag,default OFF,同族机制)看是否覆盖,再查 driveYaw 反向来源。

### §55 二次修正:真相 = repath 路线震荡环(planner 层),死区/反向皆误判
完整 walk-keys 行推翻前两个猜想:churn 期间 bot **全速行走**(hSpd 0.28、yaw≡driveYaw 一致,"反向"是把不同 tick 的行拼接的误读);且 walk-keys `wp=(-253,63,203)` 与 t= 行 `node=(-254,66,207)` **属不同路线** —— A* 在"y66 traverseBreak 挖穿路线"与"y63 绕行路线"间反复切换,bot 沿两条路线来回跑 = **net-progress 环**(totStuck 失明族的 planner 变体;DryReanchor 开着仍循环)。攻击方向:pathDebug 抓两条交替 plan 对比成本(等价 tie 震荡?挖穿路线执行后失效触发 repath?),root 修 = repath 路线粘滞(hysteresis:新路线须显著优于当前才切换)或 break 成本校准打破 tie。C16 wedge (-252,70,209) 可 tp 复现。

## 56. 用户目击纠偏:carrot-node 撕裂钉死实锤(§55 二次修正过度)(2026-07-02)
用户人工看画面判"卡墙没在挖"为真,同 tick 铁证(连续两 tick 同刻):`move=traverseBreak node=(69,22,377) p=(69.30,23,382.07)` 离 node 5 格钉死、`attack=false`、`hCol=true`、**driveYaw=77(东,=lastAim carrot 方向)vs bear=-177(node 在南)差 106°**。真形态=**carrot-node 撕裂**:break 完成后 carrot 沿 string-pull 拉向被石墙挡死的方向,身体 hCol 钉死,node 在反侧 5 格,within/passed 全不触发。§55 二次修正把此类全归"拼接伪象"是过度修正——repath 震荡环与撕裂钉死两形态并存。⭐被 REJECT 的 CarrotHColShrink 在此场景方向是对的(丛林绕树场景才错):精细化判据=carrot 方向被实际挡(hCol 持续+驱动朝墙)才回落 node。行动:ExpectAlarm(MOVE-noMove 自动取证)重启生效后抓完整因果再定点修。教训:画面(用户人眼)>我的转写解读;"同 tick 对齐"验尸纪律再次立功。

## 57. 观测优先批次落地 + C24 实战答卷(2026-07-02,用户优先级指令)
**批次内容**:①Walker 7 类 [expect] 实时报警(DIG-dropped/DIG-slow/JUMP-noRise/MOVE-noMove/REPATH-flip/DRIVE-tear/ADVANCE-deadzone/GEAR-degraded)②设置快照反射补全(新 flag 从"可设但不可见"到全可见;setter 也已反射化,加 flag 只需 BotConfig 一处)③验收协议 preflight(flag 快照核对+hotbar 装备验证,带病拒跑+一次自动修复)④判定行携带 expect 因果计数 ⑤scripts/forensic.py 同 tick 配对验尸(反拼接伪象;支持 early-return 的 unpaired tick)。
**C24 实战**:J1/J2 完美全绿 8/8(24s/25s worst=0s,replay 全 atGoal);J3 churn 判定行自带五类病理组合。**报警流 10 分钟内完成两项精确归因**(过去要几小时验尸):①DIG-dropped 新中断源=**DrowningEscape 抢占**(水下挖岸 air 低→反射断 dig 上浮→回来进度已清,保命与干活的调度冲突)②DIG-slow 200t 连续 hold 不破=**aim 漂移类**(hold 未断 crosshair 滑走每次 reset)。校准:JUMP-noRise 阈值 0.9→0.8(+0.83 是标准台阶跳峰值,C24 的 JUMP 报警全为此类误报)。
**残留工程 lane(第三优先级,带自动取证)**:水下 climb-out 的 escape-vs-dig 调度、dig aim 漂移稳定、mount 双稳态、repath 震荡。

## 58. J3 水岸churn定层三连(2026-07-02):replay场景污染发现+snapshot协议+确定性repro rig+断挖凶手改判
**方法论产出(P1,本节核心)**:①**replay 场景污染**——replay-0004 连跑 6 次后 bot 放的 cobble 塔/桥累积(envelope restore 只覆盖档内 cells),K6"churn"发生在 y67 的 bot 自建塔上=假复现。**修**:`mc.world.snapshot/restore`(id=j3-clean,MAX_VOLUME 32768)织进 replay 循环,每轮 restore。②**确定性 repro rig**:tp (371.5,62,348.5) 水中 → goto (378,65,347) 东岸上 = **100% churn**(OFF 4/4 + ON 4/4 全 TIMEOUT 120s),比原 journey 的 P(churn)=1/6 强得多,2 秒 tp 即复现。③**轮间 bot 必须安置干地**:ARRIVED/cancel 后 idle 泡水下,一天内 1 死 2 未遂;IDLE-drowning 哨兵(修到 clientTick 无条件路径后)实战开火 16 次✓。④"GL hang"误诊纠正:TitleScreen 等待,jstack+screen.info 判,UI 点击 30 秒解。
**断挖凶手改判(报警流证据)**:C24-J3 归因的"DrowningEscape 抢占断挖"**错**——同 tick 时序证真凶=**repath adoption**(挖 11-19t 新路线 adopted→t 重置→hold 丢弃→vanilla 进度清零;水下一块要 100-200t,repath 节奏更快→永不完成)。fix `walkerDigCommitHoldRepath`(default OFF):committed bank dig(waterClimbDigRiser+breakHeld)时拒绝新 search 结果;futileBankDigRelease 兜底防死锁。**A/B 判定:必要不充分**——hold 真触发 4 次但定点 rig ON 仍 4/4 TIMEOUT;且 DIG-slow(200t 连续 hold 不破=挖错块/够不着)与 hold 未覆盖的断挖路径仍在。与 pmcs §"点修证伪,真根结构性"一致:水岸 climb-out 是执行器耗散 recovery 的结构性问题,单 flag 治不了。
**下步(P3 重开时)**:用此 rig(snapshot+tp+goto,100% 复现)做 climb-out 结构性重基的 A/B 台;先验尸 DIG-slow 200t 不破的直接原因(挖的块 vs aim 的块 vs reach)。

## 59. 水面pillarUp死锁机制闭环 + walkerPillarSurfacePlace(必要但journey无净效)+ 坏goal rig教训(2026-07-02)
**机制(forensic 铁证)**:水岸 pillarUp 的目标节点是水面格(上方 air)时走"flooded-shaft 只浮不放"分支,但浮力+held-jump 的 bob 顶=fill.y+1.08(vanilla 放块需 feet≥fill.y+1.0,AABB 全离);而另一条 climbout-place takeover 的 0.9 阈值 click 全落在 [+0.9,+1.0) 的 vanilla 静默拒绝区。**两条放块路径的高度窗互相错过=确定性 bob 死锁**(rig 实测 bob 峰 63.08,唯一可放窗 63.0-63.08≈1-2t/bob,恰好全在不放块的分支)。
**fix `walkerPillarSurfacePlace`(default OFF)**:水面格 shaft 视作 dry-crest(b case),bob 峰 crest-place。**行为解锁确证**:同日志 OFF 窗 0 次 vs ON 窗 15 次 "topped out dry"(爬出动作本身从不可能变反复成功)。**但 journey 端到端无净效**:replay-0004 SP-ON 6 轮=5/6 ARRIVED+1/6 churn @(369,62,349),与基线(5/6+1/6 @372,63,347)持平;churn 簇(369-373,347-351 湖中水下地形)是内生双稳态,画面见"挖自己刚放的水下圆石"新形态。**三 flag(digCommitHold/routeHysteresis/pillarSurfacePlace)全动不了 1/6** → 结构性重基结论第三次确认。
**rig 教训(踩了自己 6-16 就写过的坑)**:定点 rig goal (378,65,347) 悬在湖面上空 2 格(未验证可站性),造出"100% churn"假象浪费两轮 A/B;goal (369,63,352) 又距起点 4 格<near 造成 2s 原地 ARRIVED。**rig 的 goal 必须先 tp 实测落点可站**。快照区外的 journey 路径 bot 放块仍跨轮累积(区外污染),扩快照或全线 /fill 清理待做。
**当天净产出**:观测系统 5 分钟级归因(vs 过去数小时)三次兑现;三个 default-OFF flag committed 供后续;churn 簇的结构性证据链完整,交结构性重基 cycle。

## 60. 定性反转:零硬死锁,病=尾延;hysteresis KILL;worstStall 判定台(2026-07-02)
**Q 系列(SP ON,180s 判据,K6)推翻"1/6 churn=死锁"**:6/6 全 ARRIVED,worstStall 分布 3/12/18/27/49/**97**s——Q-5 在旧 95s 判据下会被判死,实际 143s 自愈到达。恢复机制(repath/escalation)一直在工作,**病是尾延不是死锁**。重基目标改为压 worstStall 尾部。
**H 系列(+routeHysteresis ON,K6)**:worstStall 6/15/18/21/**151/169**s——尾部反而恶化(两轮 >150s vs 基线一轮 97s)。**KILL(keep default OFF)**:路线振荡的病根不是 adopt 太频繁,而是两条路线各有固定卡点(湖中 369,349 水下地形 + 西侧 354.7,63,353.3),hysteresis 只是把 bot 钉在坏路线上更久,推迟逃出。
**西侧固定卡点验尸**:tp 实测=普通 +1 草土台阶,bot 顶台阶侧面 MOVE-noMove(hCol=true 10t)——**干地基础 stepUp 失败形态**(stepUpBackoffRetry ON 仍发生),非水域问题;且该点在快照区外,旁边躺着历史轮 cobble(区外污染实证)。
**至此判定链**:4 flag(carrotHColShrink/digCommitHold/pillarSurfacePlace/routeHysteresis)全无 journey 净效或有害;尾延由多段异构 stall 组成(水下 pillar/dig + 干地 stepUp + 路线切换徘徊)。**下步=per-segment 分解 97s/169s 尾轮**(dist-trace 逐段归因),按段修,worstStall 判定台(replay-0004 K6)验收;快照区外污染先全线清理。

## 61. aimSrc遥测破案:开阔水step抖动饿死全部recovery + walkerStuckStepMonotonic 初步正向(2026-07-02)
**观测器(aimSrc+stuckT 入 walk-keys)一轮即破 A-4 44s 段**:开阔水直线 path 上浮力横漂让 within/投影把 step 指针来回甩(wp 375↔387↔374),旧判定 `step != stuckStep` 把每次抖动当"新节点"清零 stuckTicks——**实测 stuckT 钉死 0-6,nodeAim fallback(12)从未触发**,所有 stuck-gated recovery(nodeAim/reanchor/overshoot/wedge)集体饿死,yaw 扫 660°,推力抵消 44s 原地漂。这就是「水中反复横跳/打转」的一个闭环真机制(bob 清计数器家族的 step 指针版)。
**fix `walkerStuckStepMonotonic`(default OFF)**:仅 step 前进开新窗;回退换距离基准但停表继续走。**A/B(K6,同 rig)**:ON worstStall=5/9/24/32/58/76 vs OFF 合并基线(T+A 12 轮)尾部 82/105/181(>60s 3/12 vs 1/6)。**K12 判定 KEEP**:ON 12 轮 worstStall=2/2/5/5/9/24/26/30/32/42/58/76(12/12 ARRIVED,>60s 1/12,中位 25s,零 HARD-CHURN)vs OFF 基线 12 轮 2/5/5/11/29/38/46/50/57/82/105/181(>60s 3/12,中位 42s,1 次 181s HARD-CHURN)——最坏尾延砍 58%。今日首个净正向修复,由 aimSrc 遥测直接归因一发命中(观测优先方法论完整兑现)。已入验收 FLAGS。
**新形态入账(SP 副作用族,画面+trace)**:①塔顶滞留(y68 24-26s,pillar 成功后塔顶不在 path 上,自解但费时)②放块自弹射/嵌块。SP 的 journey 收益要和这些副作用一起算总账。

## 62. combo 层级复测:digCommitHold 叠加仍不正向,M 组合(monotonic+SP)为当前最优(2026-07-02)
monotonic 打底后 recovery 不再饿死,复测 digCommitHoldRepath 组合(mono+SP+digHold,K6):worstStall=4/10/21/25/**72/105** vs M 组合 12 轮尾部 58/76——**叠加恶化,维持 KILL**。画面在尾轮见"水下被方块完全封闭"(digHold 持久挖 × SP 放块疑似形成放-挖互作)。**当前最优=walkerStuckStepMonotonic+walkerPillarSurfacePlace**(验收 FLAGS 已含),judge 台 replay-0004 上尾部 76s(基线 181s)。
**残留尾形态(均 15-30s 异构小段,单点边际递减)**:SP 塔顶滞留(y68)/放块自弹射/嵌块封闭/岸 dig 断。**下步候选**:①SP 副作用专项(塔顶回 path 快速衔接;放块前 AABB 校验)②climb-out 状态机(设计稿 scratchpad/climbout_statemachine_design.md)③接受当前尾部直接跑三周期验收看真实随机地形的通过率。

## 63. C25/C26 验收:通过率由地形域决定;wedge时钟第二站点修复;陡山=最弱域(2026-07-02)
**C25(平原/丛林/湖 spread)**:J1/J3 全绿(live worst=0s + replay 6/6 atGoal),J2 FAIL(live 33s 段+replay 1/3,maxStuck 221-258)。J2 的 33s 段暴露 **monotonic 第二站点**:wedge 时钟(noStepProgressTicks)同样被 step 抖动清零(ADVANCE-deadzone 流里 noStepProg 恒=61=阈值+1,数到门口就被抖走重数)→ 已修(retreat 换基准不清表,同 flag)。J2 replay 散度(live 绿 replay 劣化)=replan 出 envelope 的固有问题,待专项。
**C26(陡山 spread,y89-112)**:0/3 全 CHURN——**干地陡爬域**(pmcs 2026-06-29"陡爬 wedge 内生双稳态,点修证伪,只剩结构性两选项"的那个域)。指纹:JUMP-noRise 12-13/journey(**全部 +0.78 边缘误报**,launch y 递增=真在爬,阈值 0.8→0.75 已校准)+ MOVE-noMove 7 + ADVANCE-deadzone 5-21。
**结论**:湖区战役收效(worstStall 尾 -58%),**陡山域是 #47 剩余最大 blocker**,需下一战役(pmcs 结论:①执行器陡爬耗散 recovery 重基 ②planner partial-path,用户拍板项)。协议改进:preflight repair 成功现在打行(C26 的 FAIL 行实为 repair 前噪声);J2 GEAR×52=桶被 MLG 消耗未回收(scoop 生命周期,待查)。

## 64. 山地A/B无判别:live churn不被replay复现——验收判定台的边界(2026-07-02)
C26-J3 山地档 replay A/B(aboveNodeStallRecover OFF4/ON4):**两侧全 ARRIVED(worst 4-18s)**——live 的 3/3 churn(90s+)在 replay 中完全不复现。flag 机制正确(爬过头 3.00 格恢复盲区,forensic 铁证)但此 rig 无判别力,保持 default OFF 待 live 验证。
**live-replay 散度的成因候选**(重要度序):①**装备劣化**——replay 每轮 give 全套,live 的 journey 内消耗不补(C26-J1 GEAR-degraded 后 J2 churn 时 GEAR×52:桶丢+可能镐被挤出=挖掘 5× 慢);②连续 journey 的世界状态累积(J1 的破坏影响 J2/J3 路线);③live spread 起点/chunk 加载时序 → A* 选路不同。
**协议启示**:山地类 live churn 需要 **live 复现手段**(装备状态存档进 replay 档头/journey 内 GEAR-degraded 时自动补给)。短平快改进:live_journey 中场监测 GEAR-degraded 报警即时 re-give(把装备排除出变量),下周期生效。

## 65. 四周期验收图景(C25-C28)+ DIG-slow 归因 + 结构性实证(2026-07-02 终)
**12 journey 总账**:7 ARRIVED(4 个 clean worst≤6s:C25-J1/J3、C27-J2、C28-J3)、4 CHURN(**全部山地 y89-116**)、1 慢到达(42s)。全绿 journey 全在平原/丛林/低地;churn 全在陡山。
**结构性实证(非推测)**:C28-J2 churn 窗 1015/1477 tick stuckT>20——monotonic 双站点修复后时钟正确累积、recovery 反复触发,**bot 仍贴墙 90s**(MOVE-noMove hCol=true ×15):recovery 动作本身在山地贴墙场景无效(safetyRepath→A*同路线→再撞循环)。pmcs 两选项(执行器 recovery 重基/planner partial-path)从"点修证伪推断"升级为"telemetry 直接观测"。
**DIG-slow 归因✅(Task#5)**:C28-J1 @(-258,81,338) 200t 不破,窗内 aim=carrot×28/wp×14——**挖掘期间 look 通道仍被行进 aim 掌舵**,准星不在被挖块=vanilla 进度清零。修法明确:breakHold 期间 aim 独占(镜像 waterClimbYaw 锁),未实现。
**协议台修复链(本日)**:180s/90s churn 判据、snapshot/restore、gear top-up、preflight repair 日志、**主位移轴 arrive 判据**(C27-J3 假失败根除,C28-J3 replay 3/3 验证生效)。判定台本身已可靠。
**#47 状态**:未达三连绿。blocker 单一且明确=**陡山执行器 recovery 无效**,结构性修复方向需用户拍板;次目标=dig aim 独占(已归因待实现)。

## 66. C30 首个全绿周期;stickyDig KILL;envelope-exit 判定(2026-07-02 深夜)
**C30 = 首个全绿验收周期**(J1 27s/0s、J2 25s/0s、J3 106s/6s 全 clean expect,replay 9/9 atGoal)。C29 实质 2.5/3(J2 replay#1 是 envelope 伪影:卡点在 live bbox 外 7 格——协议新增 envExit 标注)。
**stickyDig KILL**(badlands 档 replay-0007 K3+K3):ON [234,304,424] 2/3 False vs OFF [110,173,287] 1/3——即使收紧(reach 半径+150t watchdog)仍负:独占抢断行进与 recovery,当被锁块不是出路时(planner 已改线)锁死恶化。DIG-slow 的"挖不完"病理真实,但独占方案错误;正确方向应是 aim 优先级(挖掘 tick aim 不被行进覆盖)而非 tick 独占。default OFF 保留代码,摘出验收 FLAGS。
**C31 全红**(badlands spread):J1 85s(sticky 锁死+水下角落)、J2 replay 0/3(maxStuck 1177)、J3 live 完美 49s/3s 但 replay 1/3。badlands 水下缝隙=新强复现卡点(replay-0007/0008)。
**三连绿计数**:C30=1,C31 断。当前 FLAGS=monotonic+SP+aboveNodeStall(无 sticky)。

## 67. 连续绿 2/3(C34/C35)后 C36 断于洞穴 dig 慢速;digAimPriority 实现(2026-07-02 深夜续)
**高水位 monotonic(C33-J2 修)兑现**:C34 全绿(J1/J2 worst=0s,replay 9/9)+C35 全绿(三程 clean,replay 9/9)=**连续 2 绿周期**,C31 类水岸 within-jitter 雷未再触发。
**C36 断因**:J1 replay 2/3 False(143-150 低 stuck=洞穴 dig 慢速超时)+J3 replay 0/3(447-770,黑暗洞穴挖-放循环)。live 均 ARRIVED(94s/12s、280s/21s)——**dig 断挖病理**(行进 tick 松 attack 清 vanilla 进度)在 replay 放大。
**walkerDigAimPriority**(default OFF,GT 待验):stickyDig(§66 KILL)的非独占继任——行进 tick 完整跑(drive/recovery/repath 不动),tick 末仅重申准星+attack 于被挖块(人类 W+LMB 语义),solid-gone/300t/出 reach 释放。入验收 FLAGS 待 C37 检验。
**GT 伪影警示**:与 live 客户端并行跑 GT 出现 "server thread did not run task within 8000ms"+descentYaw 1446° 等 3 required 失败——负载扭曲,GT 必须独占跑。

## 68. 停滞时钟四大清零源全堵(2026-07-03 凌晨)+ 验收协议 worst 门
**四处 stall-clock 饥饿侧门,全部 field 归因后修复**:①step 抖动清 stuckTicks(§61 monotonic)②同款清 wedge 时钟 noStepProgressTicks(§63 第二站点)③retreat re-base 拉低基准使振荡对"前进"再清(§67 高水位 stuckStepHigh)④**墙钉蠕动**(C40-J1 82s:hCol 顶墙 hSpd 0.001,0.01格/tick 在 1.5 格外每 tick 降 sd2≈0.03>EPS 0.02=恒"进展")→ STUCK_PROGRESS_EPS 0.02→0.05(1.5b/s 真实趋近只在 0.33 格内饿死,无害)。
**协议 worst 门**:live 单段停顿 >30s = ARRIVED-SLOW 非绿(C39-J3 曾以 63s 停顿"全绿",稀释 #47 判据)。
**周期账**:C38 全绿(digAimPriority 首战,replay 9/9)→C39 SLOW(63s 洞穴迂回)→C40 SLOW(82s 蠕动=④的现场)。dig-aim 271 次 RELEASE 全为破块型零超时=挖掘链路已健康。

## 69. C38 全绿后的长尾三形态+泥坑溺亡链(2026-07-03 凌晨)
**周期账**:C38 全绿(1/3)→C39 J3 63s 洞穴迂回 SLOW→C40 J1 82s 蠕动 SLOW(EPS 已修④)→C41 J1 40s 树冠 bounce SLOW+J3 replay 泥坑溺亡。
**长尾形态清单(各有档)**:①树冠 bounce 循环(replay-0001/C41:y128 树冠↔地面反复,真实运动清钟合法=net-progress-loop,wedge 时钟涨到 231 但 recovery 不破循环)②洞穴慢速迂回(C39-J3 63s)③**水下泥坑溺亡链**(replay-0003/C41:GEAR 连环丢桶[replay 无 mid-journey top-up]→泥坑无 MLG→walker 结束后 idle 水下→IDLE-drowning 哨兵未见触发→溺死。两层洞:replay 轮 gear 保障缺失+哨兵盲区待查)。
**EPS 修复(④侧门)已进 build**,C42 起生效。三连绿计数:C38=1(C39-41 断)。

## 70. 重构"回归"证伪=GT flaky 三人组;架构拆分验收通过(2026-07-03 凌晨)
**8 轮 GT bisect 定案**:重构后 3 required 稳定失败(descentYaw 1446°/descentOvershootResync/waterFarAim)疑似回归,但**基线(重构前 commit)第 3 轮也败 descentYaw**——全场 flaky 非回归。机械等价审计全绿:88 常数类型+值 0 diff、expectTick/resolveGoal/resolveBaseGoal/geometry 方法体归一化 0 语义 diff。flaky 三人组=时序敏感测试在高负载(整夜多轮 GT,单轮 12→30 分钟)下劣化;agentrpcsmoke 8018ms 擦线有前科。**修 flaky 是独立 issue**(候选:放宽 descentYaw 阈值/rpc 超时,或 GT 前 warm-up)。
**架构拆分(3 opus agents)验收 KEEP**:Walker 6167→5214(ExpectAlarms/Constants/Geometry 三提取;<3000 不可达因 tick() 单方法 4271 行状态机,拆它=行为风险,如实止步)、AgentGameTest 5535→741(5 文件,60 测试注册等价)、BotApiImpl 1594→983(4 提取)、SettingsCommand 1067→761(反射 fallback 原地)、BotConfig 确认反射依赖不可拆。全量编译绿。教训:**多 agent 共享工作树的 git add -A 会互吞 staged 变更**(commit 归属混杂,内容无损)——下次并行重构须 worktree 隔离或明确 add 路径。

## 71. C49 强卡区+"recovery 无效"最纯现场(2026-07-03 晨)
C49 spread 落进强卡区((-165~-171, y62-69) 地下泥土通道),J1/J2/J3 三连 churn。**现场(walk-keys)**:wp=(-173,61,-195) 下坡节点在墙后,bot(-171.7,62) hCol=true hSpd=0 原地跳,**stuckT=132 正常累积(EPS 修复生效)但无 recovery 把 bot 带走**:safetyRepath→A* 同路线;attack=false=从未尝试挖泥土墙(allowBreak ON,planner 认为节点连通但物理不可过=转角几何误判)。
**缺口=hCol 钉死兜底挖**:stuckT 高+hCol+有镐+面前软块 → 应主动 dig 面前身体高度块(candidate flag walkerWallDigFallback,待实现+GT)。这是山地/洞穴 churn 的公共病根(§65 的 repath-同墙循环)。

## 72. C54-J3 近 goal 崖顶 water-clutch 振荡(新形态档案)
bot 距 goal 10.5 格(d<8 圈外)在崖顶边缘 91s churn:画面=反复"倒水-收水"循环,遥测 DRIVE-tear×4+CLUTCH-noArm(C47-J3 也报过)。地形=goal 在崖下,唯一路线是 bucket-MLG 下崖;clutch 执行器放水→收水→不跳循环。候选修复:clutch 放水后 commit 跳下(放水成功即水柱存在,犹豫窗口=振荡源);或 stuckT 高时 fallback 直接跳(有水垫)。待 replay 化 A/B。

## 73. C55-J3 起步 91s churn=腾空 jump-ram 循环饿死 wall-dig(onGround 门)
现场:wp 侧向 1.3 格(cur2=2.08>0.45 死区),hCol=true hSpd=0 jump 循环 onG=false,noStepProg 393,attack=false 全程。跳→撞墙→落地瞬间又跳,onGround 采样窗口≈0 → wallDigFallback(要 onGround)永不触发,ADVANCE-deadzone×16。§66 腾空 arc-stall 的姊妹形态。修=wall-dig 去 onGround 门(hCol 本身已表征"墙前",腾空也够得着)。

## 74. 尾延主因转移:planner 偏爱 dig 密集穿山线(C53/C58/C59 三连 50-65s)
通宵 15 周期统计:绿 C46/C48/C52/C57(~29%),最大杀手已从硬卡死转为 **worst 50-65s 的挖掘尾延**——planner 按真实 tick 成本选中矿井/洞穴直线,每格 dig 的隐藏成本(approach/aim/格间 stall-recovery)不在价里,连环累积破 30s 门。修=`pathfinderBreakCostMultiplier`(default 1.0,验收 FLAGS 2.5):planner breakCost 乘数,偏爱绕行;executor 兜底 dig 不受影响。同场加映:archive_for 误匹配修复(start+goal 双校验,C58-J1 曾重放昨日旅途)。

## 77. ✅✅✅ #47 终门达成:三连续全绿验收周期 C92+C93+C94(2026-07-03 22:26)
**九程随机长途 live 全 ARRIVED + 27 轮 replay 全 atGoal=True。** worst(移动停滞)九程分布:0/0/6、0/0/3、0/0/0 秒——全部 clean 或 ≤6s,无一犹豫窗口;replay maxStuck 全 ≤150(大多 ≤68)。判据:90s goal-progress churn 门+30s 移动停滞门+dominant-axis 触线+goal 圈 14+3×replay 确定性重放。
最终 FLAGS 组合(全 default-OFF,runtime-ON):11-flag 丝滑集(§参见 silky_journey_11flag_set)+ walkerStuckStepMonotonic + walkerAboveNodeStallRecover + walkerDigAimPriority + walkerWallDigFallback(40t,无 onGround 门)+ pathfinderBreakCostMultiplier=2.5 + STUCK_PROGRESS_EPS=0.05。
路径:C43 首绿起 51 个周期的破因驱动闭环——每破必验尸,修 bot(4 个新 flag/参数)与修台架(archive 双校验/移动停滞度量/peaceful/replay 免伤/夜视)并进,绿率从 ~29% 升至末段 ~70%(C89-C94 六周期五绿)。flip-default 决策待用户。

## 78. flip-default 落地:31 个 #47 验证 flag 翻默认 ON + GT legacy 基线钉扎(2026-07-04)
用户批准后执行:accept FLAGS 全集(11-flag 丝滑集+monotonic+digAim+wallDig+breakCost2.5+水域/岸族+allowBreak/Place 等 31 项)翻 BotConfig 默认。首轮 GT 14 required 红=**套件断言建立在旧 default-OFF 基线**(测试未显式设置的 flag 默认变 ON 改变 arena 行为)。解=`BotConfig.applyGameTestBaseline()`:GameTestServer 启动时(`instanceof GameTestServer` 门)钉回 legacy 基线,live/integrated 不受影响;要 flag 的测试仍显式自设。第二轮 GT 只剩 flaky 双人组(waterFarAim/descentYaw,§70 已证伪),回归通过。

## 79. flip-default 落地验证:C95 全绿 smoke(2026-07-04 03:00)
默认 ON 构建的完整验收周期:三程 34/37/34s 全 clean(worst 0s)、9/9 replay atGoal(maxStuck≤78)。默认生效的决定性证据=首轮 GT 14 红(新默认改变 arena 行为)。#47 全链闭环:验证→三连验收→flip-default→回归+smoke 全绿。

## 80. 超长途战役 I:双确定性干地钉死=drive 朝向背离节点(walkerRamNodeAimRelease)
用户令跑 757/781 格超长途,两跑均 CHURN。#2 现场 100% 复现两卡点逐 tick 定层:(A) aim-deadzone"hold heading"带宽于 step 推进门——bot 停节点旁 1.2 格,历史 yaw16 恒怼南墙(yawErr -115°,91s);(B) walkerTangentAim 在 switchback 拐角投影反向切线(driveYaw -180 vs 节点方位 14°),拐角修正 §13 已死。恢复全家(arc-wedge/anti-stuck burst/safety repath)全开火无效,wallDig 只扫 wp 向(是空的)。修=`walkerRamNodeAimRelease`(default OFF):干地+hCol+stuckT>40+朝向偏节点>60°→朝向切回当前节点方位(非 §25 的 step-1 反锚,碰撞门+当前节点,成功即 hCol/stuckT 清零自然退出)。**A/B:OFF 91s 硬钉死→ON 秒过,781 格全程 ARRIVED 134s worst=3s,ram-release 触发 767 次。**
教训:三客户端并存(39811 僵尸+39801 新旧冲突)害 setting 静默不生效——relaunch 前 ps 全列 rpcPort 清场。

## 81. 超长途战役 II:planner 定价过乐观让 A* 合法穿树(FloatingBreakTax+LogBreakTax)
#1 水淹橡林 churn 验尸:path 节点穿水下树干,水下浮挖真实 25×(眼水 ÷5×不着地 ÷5)+bob 漂移进度重置,planner 只价 ×5→"挖穿水下树"胜过绕行(DIG-slow 200-291t 一根 log 挖不完)。修①`pathfinderFloatingBreakTax`(default OFF):from 脚格也是水(浮挖)→×25。修②`pathfinderLogBreakTax`(default 1.0):log 硬度 2 太便宜,密林"砍穿树"数学上最优=用户"寻路走到树里";×3 令绕行胜出(镜像 leaf-tax)。**A/B:OFF 209 格处 90s 永卡→ON 全程 ARRIVED 305s worst=21s(547 格)。**残留:上岸钻进树冠后执行器脱困 12-21s 短卡(能自愈,后续磨)。

## 82. 搭桥中途掉落:机制实锤+修复部分验证(walkerBridgeHoldRepath)
用户报"搭桥中途掉下"。高空桥 arena 确定性复现:19 格直桥 3/3 稳过(8-9s,一个 repath 周期内);对角锯齿桥/40 格长桥 100% 掉。掉落链铁证(bridge3-OFF 逐 tick):**桥中 repath 把 committed bridgePlace 链换成首节点在别处的新 path(实测 y+8),drive 朝新节点走出已放桥板尽头(z10010 块尽头→z10012 空气)坠落**;MLG 水桶 clutch 一直在兜底(remaining 63.9 place SUCCESS 存活)——掉不死,但一次掉落+爬回耗 30-60s。修=`walkerBridgeHoldRepath`(default OFF):当前/下一 edge 是 bridgePlace 时抑制 ROUTINE repath(safety 保留)。
受控 A/B(repath 50t 强制)未能判决:强制周期下 safety repath/best-effort 换 path 仍会掉,且"孤岛"台架让 A* 干脆不出桥线(bridgeCost 80×N,goalReached=false best-effort 乱走/绕谷底)——**台架教训:A* 天然回避长桥,人造"必须长桥"场景与真实世界(短桥 5-15 格跨峡谷)失真**。机制正确性靠 OFF 铁证支撑;实效验证交给长途验收(桥场景天然出现)。残留方向:repath 后新旧 path 桥沿衔接保护。

## 83. 三 lane 战役端到端验收:C96 全绿(2026-07-04 05:35)
§80-§82 四 flag(ramNodeAimRelease/floatingBreakTax/logBreakTax 3.0/bridgeHoldRepath)入 FLAGS 全开跑完整验收周期:J1 34s/0s clean 3/3;J2 131s/21s 过门 2/3+1 envExit 豁免;J3 49s/6s 3/3。GT 回归只剩 §70 flaky 三人组,零新增。四 flag 保持 default-OFF,flip 决策待更多周期背书。
