# 挂账修复 D1：testkit/仪表侧三债（task#88 / task#90 / task#92）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉三项 testkit/仪表侧工程债：task#88（harness 启动 tick 债 catch-up 突发使 within() 脆弱→根修+entityLeash 收紧）、task#90（仪表面三件套：持键回读 verb + world-use 输入 verb + exec() live 形状 pin）、task#92（agentRpcSmoke T1 拓扑 8-check 分歧→套件拓扑可移植，拆掉毯式 early-PASS 门）。

**Architecture:** task#88 选「arming 前排空 tick 债」方案（settle 屏障，保持 harness/within tick-pure 契约不动——wall-clock within 会破坏所有金字节场景的 tick 计数模型，弃）。task#90 新 verb 走 `mc.test.*` 命名空间 + `ToolCatalog.registerVerb` 配对入口（TestResetVerb 先例：新文件、hidden、不碰 driver-owned `mc.client.*` baseline）。task#92 先取证后修：毯式门只允许被「逐 check 具名豁免」取代，禁止静默缩编；JS 绑定 TypeError 若根在 driver client-face 缺失则修 driver，若根在脚本对 `{present:false}` 不设防则修脚本的拓扑鲁棒性。

**Tech Stack:** stagewright common (StageWrightCommon/StageWrightHarness)、worlddriver common testkit verbs、Rhino validation scripts、instrument_client.py、stagewright-junit。

## Global Constraints

- **全 armor**（凡动 worlddriver common Java 或 resources）：三模块编译 + dogfood 双 loader（130 场景金字节+3 传感器不变）+ `instrument.py` 双 loader 23/23 + `instrument_client.py` + `t1.py` GREEN。resources（JS 脚本）改动=烘进 dev jar，live 验证前必须 rebuild。
- **诚实基线**：禁止无 A/B 证据调阈值；per-check 豁免必须具名+引 task 编号；RED→定性→如实上报，不许静默调参。
- **tick-pure 契约不动**：`SceneContext.within()`/`advance()` 保持纯 tick 计数；task#88 修的是 arming 时机，不是计时单位。
- ⛔pkill（ps 列候选按显式 PID 杀）；Bash timeout 参数不用 sleep 外壳；一次一服务器；被杀 run 的世界目录必删再重跑。

---

### Task 1: task#88 harness settle 屏障 + entityLeash 收紧

**Files:**
- Modify: `stagewright/common/src/main/java/net/magicterra/stagewright/StageWrightCommon.java`（onServerTick 里加 settle 门：cadence 稳定前不调 `harness.tick()`）
- Modify: `common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/scene/WorldDriverScenes.java`（AWAIT-1 :1237 / AWAIT-2 :1275 两处 `within(180)`→`within(120)`+注释改写记录根修；class javadoc :99-113 tick-debt 段补根修落地记录）
- Modify: `docs/stagewright/orchestration-contract-v0.md`（附录记 settle 行为：done footer 语义不变，首场景开跑前多一条 settle 日志行）

**Interfaces:**
- Consumes: `StageWrightCommon.onServerTick`→`harness.tick()` 现行直通（StageWrightCommon.java:155-158）；催化证据=WorldDriverScenes.java:104-107 与 TODO.md:140 的机制记录（catch-up 突发 ~3ms/tick，wall-clock 绑定的实体晋升在突发窗内 2-2.3× 吃 tick）。
- Produces: settle 后的 harness 语义——所有场景（含 T1 integrated 拓扑）在真实 ~50ms tick 节奏下开跑。

- [ ] **Step 1**: StageWrightCommon 加 settle 门。冻结实现：`onServerTick` 记录每 tick `System.nanoTime()` 间距；**连续 10 个 tick 间距 ≥40ms** 判定 cadence 稳定，之后才开始转发 `harness.tick()`；settle 达成时打一条 INFO `testkit: tick cadence settled after <N> server ticks (tick debt drained)`；**安全阀**=若 1200 tick 仍未稳定，强制 arming 并打 WARN（永不无限挂起）。settle 计数与 harness 无关（PREP_BUDGET_TICKS 等 tick 预算全部从 settle 后起算，天然满足——harness.tick 根本没被调过）。
- [ ] **Step 2**: 编译 + dogfood 双 loader ×1，确认 130 场景金字节与 P4-final 基线 md5 一致（settle 只延迟开跑，不得改变任何场景指标字节）+ settle 日志行在两个 loader 的 server log 里都出现。
- [ ] **Step 3**: A/B 证据采集——**冷启动 dogfood ≥6 跑（neoforge 3 + fabric 3，每跑都是新 server 进程）**，从结果 JSONL/log 抓 `wd.entityLeash` AWAIT-1/AWAIT-2 实际消耗 tick 数入报告表格。收紧判据：**6/6 跑两处 await 实测 ≤60 tick** → 两处 `within(180)`→`within(120)`（保 2× 余量），注释改为「root fix task#88 landed <commit>; 120 = 2x post-settle worst; history 60→120→180→120」；若任一跑 >60，**保持 180 如实上报**（禁止为收紧而放宽判据）。
- [ ] **Step 4**: t1.py ×1（integrated 拓扑同样受益于 settle；entityLeash 在 T1 也必须 GREEN）。
- [ ] **Step 5**: Commit `fix(testkit): task#88 — drain startup tick debt before arming scenes (settle barrier), re-tighten entityLeash within`

### Task 2: task#90 仪表三件套

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/stagewright/TestInputVerbs.java`（两 verb 一文件，TestResetVerb 先例：hidden schema、`ToolCatalog.registerVerb` 配对注册、挂进现有 testkit verb 注册链——TestResetVerb.register() 被调用的同一处）
- Modify: `scripts/stagewright/instrument_client.py`（reset.behavior 的 keys 子断言升真断言）
- Modify: `stagewright/junit/src/test/java/net/magicterra/stagewright/junit/ui/ContainerFurnaceTest.java`（去 @Disabled，用新 verb 实装）
- Modify: `docs/stagewright/instrument-contract-v0.md`（附录记两 verb 契约）

**Interfaces:**
- Consumes: `ToolCatalog.registerVerb`（ToolCatalog.java:151-182；`mc.test.*` 命名空间授权 :184-197）；`BotInteract.releaseKeys()` 遍历的 8 个 `KeyMapping`（BotInteract.java:131-143）；`mc.bot.useItem` 已用的 `gameMode.useItemOn` 射线机械。
- Produces: **`mc.test.input.heldKeys`**（无参；返回 `{ok:true, keys:{up,down,left,right,jump,sprint,attack,shift:bool}}`，client 线程读 `KeyMapping.isDown()`；专服上大声报错 client-only）；**`mc.test.input.useOnBlock`**（参数 `{x:int,y:int,z:int, hand?:"main"|"off"}`；client 线程对指定方块坐标合成 BlockHitResult 走 `gameMode.useItemOn`（仪表级：不移动、不瞄准、不经行为面）；返回 `{ok:bool, result:string}`（InteractionResult 名）；专服大声报错）。两 verb hidden（不进 tools/list），与 mc.test.reset/mc.test.yaml 同惯例。
- 第三件：`StageWright.exec()` 的 ok/success 解析从未被 live 驱动（StageWright.java:108-120，grep 证零调用）——ContainerFurnaceTest 实装即首个 live `tk.exec("setblock ...")`，**必须显式断言一次成功路径不抛 + 一次 `success:false` 命令（如 `/damage @p 0` 无效目标形）抛 StageWrightRpcException**，pin 回复形状。

- [ ] **Step 1**: TestInputVerbs 实现+注册（heldKeys/useOnBlock 冻结形状如上）。编译过。
- [ ] **Step 2**: instrument_client.py keys 子断言升级：`mc.client.input.key`(W down)→`mc.test.input.heldKeys` 断 `up==true`→`mc.test.reset`→heldKeys 断全 false（抓 `releaseKeys()` no-op 回归——今日 `reset.add("keys")` 是无条件 token（BotApiImpl.java:882-883）＝空断言，本步闭掉）。检查数+1，脚本头部计数与契约文档同步。
- [ ] **Step 3**: ContainerFurnaceTest 实装：`tk.exec("setblock <x> <y> <z> minecraft:furnace")`（live exec pin 双向断言在此）→`mc.test.input.useOnBlock` 开炉→`screen.info` 断 FurnaceScreen→`mc.client.screen.close`→teardown `setblock air`。@Disabled 删除，@EnabledIfEnvironmentVariable(TESTKIT_ENDPOINT) 保留。
- [ ] **Step 4**: 全 armor（common Java 动了）+ live 验收：`t1.py --hold` 起 endpoint→`TESTKIT_ENDPOINT=... ./gradlew :stagewright-junit:test` ContainerFurnaceTest GREEN + instrument_client.py 新检查 GREEN。
- [ ] **Step 5**: 契约文档附录 + Commit `feat(testkit): task#90 — instrument-grade heldKeys/useOnBlock verbs, live exec shape pin, containerFurnace enabled`

### Task 3: task#92 agentRpcSmoke 拓扑可移植

**Files:**
- Modify: `common/src/main/resources/data/worlddriver/scripts/validation/*.js`（按取证清单：13_set_hotbar_slot/21_blocks_to_avoid/25_phase_d3/40_scheduler/41_defense/42_combat/44_craft/45_equip/57_replay 中分歧成员）
- Modify: `common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/scene/WorldDriverCoreScenes.java:139-146`（毯式 early-PASS 门拆除）
- Possibly modify: `common/src/main/java/net/magicterra/worlddriver/api/ObserveApi.java` / client-face 绑定（仅当取证证明分歧根在 driver 侧缺失）
- Modify: `docs/stagewright/migration-log.md`（task#92 裁决更新，append-only）

**Interfaces:**
- Consumes: 记录的 8 分歧（TODO.md:41）：5 硬 JS `TypeError: … of undefined`（observe…player / Driver.bot.tunnel / blocks_to_avoid 客户端面）+2 行为（scheduler retreat 通道归属）+1 flaky 名额（41_defense 箭矢/57_replay 形）；最可疑机制=integrated 拓扑下 `mc.observe.player` 合法返回 `{present:false}`（ObserveApi.java:120-134 永不抛）而脚本无防御直接解引用。
- Produces: 259-check 套件在 T0 专服与 T1 integrated 双拓扑可跑；`agentRpcSmoke` 场景在两拓扑都真跑套件。

- [ ] **Step 1 取证**: `t1.py --hold` 拉起 integrated 拓扑，裸 RPC 触发 `WorldDriverCommon.runValidation()`（scripts/.claude/skills/worlddriver-rpc/rpc.py 走 mc.script.eval 或等价入口），抓 8 失败的**完整错误文本+check 名**入报告（现有记录只有归类没有原文）。逐一定性：脚本不设防 / driver client-face 真缺失 / 真行为分歧 / flaky。
- [ ] **Step 2 修**: 按定性分流——(a) 脚本对 `{present:false}` 等拓扑合法形不设防→脚本加拓扑鲁棒断言（断言目标改为对**avatar/driver 身体**的观察而非 PlayerList 玩家，或显式两拓扑分支）；(b) driver client-face 绑定真缺失→修 driver（全 armor）；(c) 真行为分歧（scheduler retreat 通道）→root-cause，若语义合法差异则该 check 改为拓扑感知断言（两拓扑各断各的正确值），**不许删 check**；(d) flaky 名额→复跑定性，真 flaky 则该 check 内部收敛（等待条件化），禁调阈。
- [ ] **Step 3 拆门**: WorldDriverCoreScenes.agentRpcSmoke 删除 `!isDedicatedServer` 毯式 early-PASS；场景在两拓扑都跑套件断 `FAIL==0`。若 Step 2 后仍有**具名**不可移植 check（必须引 task 编号+机制一句话），允许套件内 per-check 拓扑 skip 清单（数量进断言：`TOTAL==259 ∧ FAIL==0 ∧ skipped⊆具名清单`），passNote 报 skip 数——毯式门禁止回来。
- [ ] **Step 4**: 全 armor（resources 改动→rebuild 后 live）+ T0 双 loader dogfood agentRpcSmoke GREEN（259/259 不得回归）+ t1.py GREEN（场景真跑，非 early-PASS——从 passNote/log 证实）。
- [ ] **Step 5**: migration-log append 裁决更新 + Commit `fix(testkit): task#92 — agentRpcSmoke validation suite topology-portable, blanket guard removed`

### Task 4: 验收 + 文档收官

**Files:** `TODO.md`、`docs/stagewright/migration-log.md`（如 Task 3 未覆盖）、`stagewright/README.md`（传感器/门清单变化）
- [ ] **Step 1 终验矩阵**: dogfood 双 loader ×1（金字节 md5 与 P4-final 基线比对——entityLeash within 变化不影响其它 129 场景字节）+ instrument.py 双 loader + instrument_client.py + t1.py + t2.py 各 ×1 全 GREEN；`./gradlew :stagewright-junit:test`（含 ContainerFurnaceTest）GREEN。
- [ ] **Step 2 文档**: TODO 三债收案条目（每债：根因一句话+修法+证据指针）；README 治理面更新（agentRpcSmoke 拓扑门条目改写/移除）；Commit `docs(testkit): D1 — task#88/#90/#92 closed`

---

## Self-Review（计划自检）

1. **覆盖**: 三债各一 task+终验；task#88 两个 within 站点都列了行号；task#90 三件套（双 verb+exec pin）全落；task#92 毯式门拆除是显式 Step。
2. **占位符**: 无 TBD；verb 形状/settle 判据/收紧判据全冻结数值。
3. **一致性**: settle 门在 StageWrightCommon（不动 SceneContext tick-pure）与 Task 1 Interfaces 一致；mc.test.* 命名空间与 ToolCatalog 政策一致。
4. **风险**: settle 门若写进 harness 内部会打乱 PREP 预算——冻结为 StageWrightCommon 转发层实现；useOnBlock 若走行为面=违反仪表纪律——冻结为合成 BlockHitResult 直调 gameMode；task#92 修脚本可能掩盖 driver 真缺陷——Step 1 强制先取证定性再分流。
