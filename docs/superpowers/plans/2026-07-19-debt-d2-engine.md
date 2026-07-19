# 挂账修复 D2：引擎侧三债（task#86 / task#87 / task#91）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉三项引擎工程债：task#86（自竖井挖升 ~20 格 backslide，gap#53 卫兵盲区）、task#87（y≈−60 近世界底 leash 停走悬案 → 复现-或-结案）、task#91（开阔河岸 +5 陡壁攀出 wedge，结构修非点修）。每债以既有 dogfood 场景/新探针场景为确定性裁判，修复后翻转场景断言极性。

**Architecture:** task#86 的裁判 `ad.selfShaftDigUp` 已是字节级确定性复现器（签名门 PASS=缺陷仍在），修好后按场景 javadoc 预写的 flip-to-strict 指令翻严——这是天然 TDD。task#87 无现存复现器：先造低 Y 探针场景（ad.entityLeash 的绝对低 Y 副本），GREEN=裁定 legacy rig 病结案，RED=才有引擎修复对象。task#91 遵循 REGRESSION.md §17-24 的 K≥6+P(wedge) 判定纪律（点修范式在干地陡爬域已被定量证伪，水域同构照搬方法论）；修复必须让**默认 OFF 基线**下攀出成功（核心行为改进，不是再加一个被 pinnedBaseline 清零的 flag——新 flag 会被场景的 default-OFF 钉扎抵消）。

**Tech Stack:** Walker/PathFinder/WorldView（heavily-tuned，动前必读周边惯例）、common testmod scenes、t0 dogfood + live replay。

## Global Constraints

- **全 armor**（凡动 engine common Java）：三模块编译 + dogfood 双 loader（金三件 descentYaw 871°/53 · selfShaftDigUp（T1 后按新极性）· gearScope 四值不变；agentRpcSmoke 147/0；entityLeash within(180)）+ instrument.py 双 loader + t1.py GREEN。
- **诚实基线**：断言极性翻转必须伴随 ≥3 连跑字节级证据；禁调阈值救绿；RED→定性→如实上报。引擎修复一律 **HEAD~ vs HEAD A/B**（task#91 加严为 K≥6 每侧 + P(wedge)/wallPressTicks 主指标）。
- **Live/replay=真相**：task#86/#91 修复各须 ≥1 次 live 或 replay 验证（arena 只是回归卫士）；实验开 live-screen-watch（客户端可视试验时）。
- **水域/陡爬是 heavily-tuned 区**：改 Walker/PathFinder 共性逻辑后 grep 兄弟 move/兄弟 flag 拷贝；10 个水域场景全部不得回归。
- ⛔pkill；Bash timeout 参数；一次一服务器；被杀 run 世界目录必删；外部 Touhou 客户端（~284% CPU）不许动。

---

### Task 1: task#86 自竖井挖升 backslide（gap#53 卫兵盲区）

**Files:**
- Investigate/Modify: `common/src/main/java/net/magicterra/agent/bot/movement/Walker.java`（strideFloorGuard :572-640；嫌疑=:589 `h < 0.03` 早退把垂直运动整体豁免，回落自己挖出的柱洞不在「前向 stride」检测模型内）
- Modify: `common/src/testmod/java/net/magicterra/agent/bot/testkit/scene/AgentDriverScenes.java`（签名门翻严：javadoc :710-718 预写了 flip 指令；金值登记同步）
- Possibly: `common/src/main/java/net/magicterra/agent/bot/BotConfig.java`（若需新 tunable——默认值必须使修复默认生效）

**Interfaces:**
- Consumes: `ad.selfShaftDigUp` 确定性签名 `reached && worstBackslide > 15.0`（golden 20.252203415101263，跨 loader/跨槽位字节同）；gap#53 历史 A/B（TODO.md:834-836：卫兵 OFF=281.25，ON=1.25 于原窄验证）。
- Produces: 修复后 `worstBackslide` 显著小（预期 ≤ 数格），场景翻 strict 断言 + 保留 reached。

- [ ] **Step 1 取证**: 先别改引擎。给场景加临时逐 tick 日志（或用既有 walkerDebug/walk-keys 遥测）跑 3 次，定位跌落的确切 tick 窗口：跌落瞬间 bot 的水平速度、所在 cell、strideFloorGuard 是否被调用/为何未拦（:589 早退？stride cell 判定？plug 失败？）。跌落机制一句话入报告（哪个 move/哪次转身导致踏空自己的洞）。
- [ ] **Step 2 修**: 按 Step 1 证据选修法（候选：垂直挖升期间加脚下柱空洞检测的兄弟卫兵；或 stride 模型补当前 foot 列检查；或 dig-up 序列的 plug/落点纪律）。修必须默认生效（不是新增默认 OFF flag）。改后 grep 兄弟分支拷贝。
- [ ] **Step 3 A/B + 翻严**: HEAD~ vs HEAD 各 3 跑 `ad.selfShaftDigUp`（solo 单名跑法照旧）：HEAD~ 复现 20.25 签名，HEAD 的 worstBackslide 记录实测值；3 跑字节同 → 场景按预写指令翻 strict（`reached && worstBackslide <= <实测上界+安全裕度，javadoc 记推导>`），withRequired 保持 true，javadoc 记录修复 commit + 新金值。
- [ ] **Step 4 live 验证**: replay 或 live 一次真实挖升路线（Mountains 存档 goto YLevel 上升，或 mc.debug.replay 既有档）确认无回归 + 无新踏空。全 armor。
- [ ] **Step 5**: Commit `fix(engine): task#86 — self-shaft dig-up backslide (gap#53 guard blind spot), scene flipped to strict`

### Task 2: task#87 低 Y leash 悬案（复现-或-结案）

**Files:**
- Create: `ad.entityLeashLowY` 探针场景（加入 `common/src/testmod/.../scene/AgentDriverScenes.java` 同 provider；`scripts/testkit/expected-scenes-*.txt` 双清单 +1）
- Modify: `docs/testkit/migration-log.md`（task#87 裁决 append）

**Interfaces:**
- Consumes: `ad.entityLeash`（y=200 GREEN）为模板；task#87 档案=legacy arena 在 y≈−60 phase2 anchor 挪动后 bot 未 ARRIVE（void-fall rig 病家族嫌疑，取证已证引擎无任何 world-min 分支代码）。
- Produces: 低 Y 引擎行为的确定性证词。

- [ ] **Step 1**: 场景实现——几何/断言与 `ad.entityLeash` 逐字同构，唯一差异：石道地板铺在**绝对 y=−59**（近 −64 世界底，场景体内以绝对坐标 fill，位于本场景 origin slot 的 XZ 柱内防撞车；cleanup 恢复原 blockstate 全足迹）。首落 `withRequired(false)`（调查传感器）+ javadoc 引 task#87。
- [ ] **Step 2**: 双 loader 各 3 跑 solo + 全量各 1 跑。**GREEN ×6** → 裁定=task#87 是已删 legacy rig 的环境病（GameTest empty 模板贴世界底放置），引擎无低 Y 缺陷：场景翻 `withRequired(true)`（永久低 Y 回归卫士），migration-log 记结案。**RED** → 如实入档失败签名，定性根因（挖掘预算贴底截断？despawn？索引？），修复后再翻 required；若修复超出本 phase 预算则 BLOCKED 上报 controller 裁决（禁静默留 optional 不管）。
- [ ] **Step 3**: Commit `test(engine): task#87 — low-Y leash probe scene, verdict recorded`（+修复另 commit 若走 RED 线）

### Task 3: task#91 开阔河岸陡壁攀出 wedge（结构修）

**Files:**
- Investigate: `common/src/main/java/net/magicterra/agent/bot/movement/Walker.java`（水逃逸执行树 ~:2501-2900、floatingBankRam :4120-4148）、`common/src/main/java/net/magicterra/agent/bot/pathfinder/PathFinder.java`（bestAshore/bestClimb 承诺 :818-825）、`pathfinder/moves/SwimAshoreBreak.java`/`SwimBankClimbBreak.java`
- Modify: 按取证定位（执行层 lateral-slide 或规划层 lateral exit 承诺）
- Modify: `common/src/testmod/.../scene/AgentDriverWaterBankScenes.java`（:114-115 翻 required + javadoc 更新）、`mc-testkit/README.md:170-174`、`docs/testkit/migration-log.md`（append）

**Interfaces:**
- Consumes: `ad.riverSheerBank` 确定性 RED（wallPressTicks=51、step=FAILED、跨 loader 字节同）；wedge=顶着 +5 不可爬壁压墙不横移，低岸出口在东 24 格；default-OFF 基线钉扎（pinnedBaseline 清零 walkerBankDig* 家族）。
- Produces: default-OFF 基线下 bot 横移找到低岸攀出（ashore 断言过），场景翻 required。

- [ ] **Step 1 取证**: 场景加临时遥测跑 3 次：A* 每轮 re-solve 实际返回的 committed path 端点（有没有向东横移的 lateral route？还是每轮都 commit 压墙点）；执行层压墙期间的 step 状态机。裁定 wedge 根在规划（A* 从不给 lateral exit）还是执行（给了但压墙状态不释放）。一句话机制入报告。
- [ ] **Step 2 修**: 按根因结构修（规划根→escape/ashore 候选纳入沿壁 lateral 走廊评分或 pinch-escalation 复用；执行根→压墙 stall 检测（wallPressTicks 同源信号）触发 lateral 释放/repath）。**默认生效**，不新增会被基线清零的 flag；若必须 flag 则默认 ON 且不入 applyGameTestBaseline 清零表（javadoc 说明为何）。改后 grep 兄弟 move/兄弟 stall 检测拷贝。
- [ ] **Step 3 K≥6 A/B**: HEAD~ vs HEAD 各 ≥6 跑 solo `ad.riverSheerBank`，主指标 P(ashore) 与 wallPressTicks 分布入表：HEAD~ 6/6 wedge（≈51），HEAD 须 6/6 ashore 才许翻 required（任何 <6/6=如实上报不翻，禁调场景）。
- [ ] **Step 4 live + 回归**: 10 个水域场景全量双 loader 无回归（金值字节比对）+ 1 次 live/replay 真河岸验证（Mountains 存档水岸线路）。全 armor。
- [ ] **Step 5**: Commit `fix(engine): task#91 — open-river sheer-bank climb-out wedge (structural), riverSheerBank promoted to required`

### Task 4: 验收 + 文档收官

**Files:** `TODO.md`、`docs/testkit/migration-log.md`、`mc-testkit/README.md`（传感器清单变化：riverSheerBank 出列、entityLeashLowY 入列）、`scripts/testkit/expected-scenes-*.txt` 复核
- [ ] **Step 1**: 终验矩阵——dogfood 双 loader（场景数 130→131，expect-file 同步；金值含 T1/T3 新极性）+ instrument.py ×2 + instrument_client.py + t1 + t2 各 ×1 GREEN；`./gradlew :testkit-junit:test --rerun-tasks` GREEN。
- [ ] **Step 2**: 文档 + Commit `docs(engine): D2 — task#86/#87/#91 closed`（TODO 三债收案；README 治理面=withRequired(false) 传感器清单更新；migration-log append 三裁决）。

---

## Self-Review（计划自检）

1. **覆盖**: 三债各一 task；每债的裁判场景、翻转极性路径、A/B 纪律全冻结。
2. **占位符**: 无 TBD；task#86 新金值上界由实测推导（javadoc 记推导过程），非预设数。
3. **一致性**: 「修复必须默认生效」与 riverSheerBank default-OFF 钉扎/selfShaftDigUp 无 flag 布局一致；场景计数 130→131 已在 T2/T4 两处同步。
4. **风险**: Walker/水域=heavily-tuned——A/B+全水域回归+live 三重网；task#87 低 Y 绝对坐标 fill 的世界污染→cleanup 恢复全足迹+落在本场景 XZ 柱内；task#91 若 6/6 不达标→不翻 required 如实收场（诚实传感器保留是合法结局，但须 controller 裁决非 implementer 自决）。
