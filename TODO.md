# TODO

> 镜像 Task 跟踪器的长期工作。重要根因写进 memory(reference/project)。

## 2026-07-19 ✅ D1-T3 task#92 收案 → `ad.agentRpcSmoke` 拓扑可移植,毯式 early-PASS 门**拆除**;JS 验证套件在专服(T0)+集成客户端(T1)双拓扑都真跑 REQUIRED — commit `a09494b`(branch `feature/executor-permove-ascend`,详录 `.superpowers/sdd/task-3-report.md` + `docs/testkit/migration-log.md` task#92 段)

- **取证(T1 `/agent test` 裸 RPC,不重编译)**:`TOTAL 259/PASS 250/FAIL 9`(tracker 记的 8 少一个=42_combat 行为项)。九分歧定性 + 修:
  - **①13 observe.player + ③④⑤25 tunnel×3**(5 硬 JS `TypeError: … of undefined`)=**验证套件 harness prelude 是 `prelude.js` 的陈旧子集**(`AgentScriptManager` 内联手抄版缺 `Agent.observe.player` + 整个 `Agent.bot.*`);专服路径这些脚本自跳过=漂移长期隐藏,T1 首次真跑 client 分支才炸。修=**改载规范 `prelude.js`(单一真源)**+ 附 harness 专属 extras(rpc/mcpRoundtrip、world.*、stdout console)。
  - **②21 applied**(第 5 个 JS TypeError)=`mc.bot.setting` 空 `applied` 省略(`SettingsCommand:808` 共享码,两拓扑同形)脚本不设防 → `(r.applied||[])`。
  - **⑥⑦40 retreat×2**(行为)=RetreatChain 需真威胁才 bid(gap#65/#68 门),脚本"threshold==maxHP 恒 bid"前提陈旧(live 证:无威胁 prio0,召唤僵尸后 prio100)→ **召唤 NoAI 敌**(仿 41_defense)。
  - **⑧42 melee engage**(行为,不可移植)=`completed` 全清场需平坦净 arena,live 客户端世界+CPU 负载下 pathing/时序耦合(live 证:挥砍 25 击杀 8 但不 complete)→ **具名 task#92 拓扑 skip**(offence 由 dogfood `ad.serverCombat*` 确定性覆盖),计数不删不吞。
  - **⑨57 replay**=断言钉旧 rigid 形;replan 路径(`ReplayTool:104-129`)返 `file/mode/envelopeCells/…` 无 `segments/plannedNodes` → 断当前契约。
  - **⑩(潜伏 driver bug,prelude 修后才显)**:`prelude.js` tunnel 的 `distance required` 守卫是死代码(`Math.max(1,…)` 把缺失 distance 抬到 1 抢在 `if(!dist)` 前)→ 缺 distance 静默挖 1 格而非拒绝。修=先判原始 distance 再 clamp。
- **⭐拓扑总数是 topology-DEPENDENT(实测,非假设)**:老场景只断 `FAIL==0` 从不计数,所谓"专服 259"从未验过=集成取证数错标。真相=~35 个 client-face 脚本在**专服**各自跳到 1 个"no client"占位、在**集成**跑完整真分支 ⇒ 专服 **147** / 集成 **259**(集成⊃专服)。场景改 topology-aware 断言(`RPC_SMOKE_EXPECTED_TOTAL_DEDICATED=147`/`_INTEGRATED=259`)=断各拓扑正确值(裁决规则 c),非毯式跳=套件两拓扑都满跑。**⚠️与 brief 字面"259/259"偏离已在 report 显式标给 controller**(专服真=147;逼 259 需重构 35 脚本 skip 分支=不成比例)。
- **✅验收**:三模块编译过;T0 dogfood **neoforge GREEN**(agentRpcSmoke 147/0 专服,金三件 PASS,entityLeash 31t)+ **fabric GREEN**(147/0,entityLeash 30t);**t1.py GREEN**(133s)——`ad.agentRpcSmoke` PASS **804 tick/35s**(证场景真跑非 early-PASS),passNote "integrated — 259 checks, 0 failures, 1 named task#92 skip",client log `TOTAL 259/PASS 259/FAIL 0`。**毯式门已拆,禁回来**。
- **残余**:instrument.py/instrument_client.py 未跑(仪表面=task#90,本改不触及验证套件,归 D1-T4 #91 验收);开放 task#86/#87/#88/#91 不变。

## 2026-07-19 ✅ P4-final GameTestServer 机器退役**收官** → 六项退役清单全闭、四门绿、campaign 终章封盘;**mc-testkit 成为唯一测试门** — spec §6.6,branch `feature/executor-permove-ascend`(T1 `9f506d1` 执行删除 + T2 `docs(testkit): P4-final — retirement complete, testkit is the sole test gate` 验收+文档收官)

- **✅ 机器退役六项清单全闭**(①-⑤=T1 `9f506d1`,⑥=T2 本 commit):①`gameTestServer` loom run config 移除(neoforge——fabric 从未有此 run config)✅;②`scripts/run_gametests.sh` + `scripts/gt_reconcile.py` 删除 ✅;③`GameTestManifest.java` + `AgentDriverNeoForge` gametest 钩子移除(`applyGameTestBaseline` 保留=改由 `-Dtestkit.autorun` 服务器启动路径驱动)✅;④`AGENT_GT_ONLY`/`solo*` batch/`run_gametests`/`gt_reconcile` 残留 grep 清理(T2 复审=活面 0)✅;⑤`.gitignore` `run-gametest/` 条目移除 ✅;⑥文档 final sweep(`mc-testkit/README.md` 退役注 + `docs/testkit/migration-log.md` 尾部退役注 + `BotConfig.java:2533` javadoc 重写为退役后真相)✅。**neoforge/fabric testmod source set 保留=空源桥接**(删则静默断场景投递)。
- **✅ 四门验收(前台有界顺序,ONE server at a time;详录 `.superpowers/sdd/task-2-report.md`)**:
  - ①**dogfood 双 loader ×1 复验**(与 T1 的 ×1 合计每 loader ×2 一致)✅ **VERDICT GREEN**:neoforge 1:23 / fabric 1:13,两跑 `(name,outcome)` **134 记录同一 md5 `5b0e44f4350f69502ee8253bd24174b3`**(跨 loader neoforge==fabric 恒等);130 `ad.*` + canary/builtin;恰 3 `withRequired(false)` 传感器如期(`ad.vineClingFidelityProbe` optional-PASS / `ad.vineOverWaterClimb` optional-FAIL / `ad.riverSheerBank` optional-FAIL,task#91);`ad.agentRpcSmoke` 专服路径满跑 PASS;**`ad.entityLeash` PASS(P4c within 180 止血持稳,零 flake,未再加宽)**;金值三件(descentYaw 871°/53、selfShaftDigUp `worstBackslide` 20.252203415101263、gearScope bare 0.94000053/sword 5.9040003/ATTACK_SPEED 1.5999999046325684/ATTACK_DAMAGE 6.0)由场景内断言复现(scene PASS)。
  - ②**生产 jar 字节门双 loader + `publishToMavenLocal` 复验** ✅ 双向断言过:禁 `AgentGameTest*`/`AgentDriver*Scenes`/`SimProbes`/`scene.SceneProvider` impl+service 条目 **= 0**;留 `TestResetVerb`/`TestRunVerb` + `TestkitVerbHook` service 条目 **present**;框架接口 `SceneProvider`+`Scenes` DSL 在 jar 内=合法(retained `TestRunVerb` 依赖)。remapped + 已发布 `~/.m2` jar 同断言全过。
  - ③**树审计** ✅:6 token(`GameTestManifest`/`runGameTestServer`/`run_gametests`/`gt_reconcile`/`AGENT_GT_ONLY`/`batch = "solo`)活面 **0**;run-config token `gameTestServer`(camelCase 机器)**0**;`@GameTest(` 全树 **0**;service 13 行 == 13 provider;双 expect-file 各 **130** 场景。`gametestserver` 大小写不敏感 59 命中全属合法记录(删类 `AgentGameTestServer` 溯源 + vanilla-class javadoc + P4-final 墓碑),历史档案豁免遵守。
  - ④**`./gradlew tasks --all | grep -i gametest`** ✅ 无输出(`runGameTestServer` 计数 0);`runDogfoodServer`/`runTestkitServer` 任务存活。
- **campaign 终章统计(封盘)**:legacy `@GameTest` **130 方法 → 0**(P4a 130→122 / P4b 122→59 / P4c 59→0);dogfood **130 `ad.*` 场景 / 13 provider / 3 optional-FAIL 传感器 / 1 拓扑门**(agentRpcSmoke);129 迁 1:1 + 1 retired-without-scene(`descentDrift`)+ 1 net-new(`ad.settingRegistryClosed`)。**mc-testkit = 唯一测试门**;GameTestServer 交付形态彻底退役。
- **残余**:开放 task **task#86/#87/#88/#90/#91/#92** 均不变待修;**user 待决**:`artifactId`(`agent_driver-testkit-*` 发布坐标)/ merge / 远端仓库。

## 2026-07-18 ✅ P4c Server 巨类按 family 分波全迁 → legacy `@GameTest` 套件**清零**(59→0),dogfood 涨到 130 场景;整场 campaign 收官(130 legacy 方法 → 0)— spec §6.3 Server 侧收官,五门绿(branch `feature/executor-permove-ascend`,commit `docs(testkit): P4c — Server families fully migrated, legacy GameTest suite emptied`)

- **✅ P4c 六 commit 已落(四迁移波 + 1 stop-bleed + 本收官)**:①`2844189` plan(P4c 波次);②`2003a64` (wave 6 P4c-T1) **Station 15 迁**(59→44,craft/smelt/recipe/observe,新 provider `AgentDriverStationScenes`;driver 走 `ServerAgentDriver.createIsolated`;furnace/grid 状态机手塞 `containerMenu` #64);③`880cdfb` (wave 7 P4c-T2) **Scheduler 语义矩阵 11 迁**(44→33,#54P1 回归卫士,新 provider `AgentDriverSchedulerScenes`,139 矩阵行逐条一对一);④`c5f754e` (wave 8 P4c-T3) **Survival+Avatar 18 迁**(33→15,`AgentDriverSurvivalScenes` 13 + `AgentDriverAvatarScenes` 5);⑤`bf54138` **`ad.entityLeash` within 120→180**(第二次记录的 stop-bleed,task#88 根修待,A/B 证为既存 harness tick-债 非回归);⑥`e47e873` (wave 9 P4c-T4) **Process 核心 15 迁**(15→0,`AgentDriverProcessScenes`)+ **三类全删**(`AgentGameTestServer`/`AgentGameTestRegistrar`/`AgentGameTestSupport`);⑦本条目 = Task 5 五门验收 + 文档收官。
- **波次结局(记录)**:
  - **underwaterBase port rationale(wave 8)**:惯犯挂死 arena(`ChunkMap.processUnloads` 单 tick livelock)迁移时保留 escape-hatch 优先——直译体不复制持久世界 re-entrant 触发路径,场景体走 bounded await,零 `level.tick()`。
  - **三处 `level.tick()` 雷直译(wave 9)**:`serverForbidDigWall`/`serverFollow`/`serverCombat` 各 `for(3) level.tick(()->true)` 索引雷 → 统一改 wave-5 bounded 同实体可见性 await(`within(100)`,live 7–12 tick 解析),下游 register/tickAll/断言逐字保留;`serverCombat` 的 per-iter `zombie.tick()`(直接实体 tick,非 `level.tick`,无 ChunkMap re-entry)正确保留。
  - **entityLeash stop-bleed #2**:`ad.entityLeash` within 120→180(`bf54138`),与 P1.5b 的第一次 widening 同族;根因 task#88(harness 启动 tick 债 catch-up 突发)未闭,A/B 证既存非本波引入。
  - **campaign close 130→0**:tracked drift 链 130→122(P4a)→59(P4b)→0(P4c);129 迁 1:1 + 1 retired-without-scene(`descentDrift`);"144" = P4a git-mv 的 `@GameTest`-家族注解总数(含 class-level `@GameTestHolder`),方法数为 130——migration-log 收官段已对账两数。
- **✅ 五门验收(前台有界顺序,ONE server/client at a time;详录 `.superpowers/sdd/task-5-report.md`)**:
  - ①**dogfood 双 loader ×2 全绿 + 结果集恒等** ✅:neoforge ×2 + fabric ×2,四跑 `(name,outcome)` **134 记录同一 md5**(`0b0f8db4…`,loader 内 ×2 + 跨 loader neoforge==fabric 全恒等);130 `ad.*` + canary/builtin;3 optional sensor 如期(`ad.vineClingFidelityProbe` optional-PASS,`ad.vineOverWaterClimb` optional-FAIL pocketTicks=29,`ad.riverSheerBank` optional-FAIL wallPressTicks=51);`ad.agentRpcSmoke` 在专服路径满跑 259 检查 PASS。墙钟:neoforge **83.6s / 81.6s**,fabric **73.6s / 79.6s**(涨幅在预算内)。含 dayTime hygiene 改动后重跑 GREEN 恒等。
  - ②**instrument 双 loader 23/23 + t1.py GREEN**（见 report）。
  - ③**生产 jar 字节门双 loader + `publishToMavenLocal` 复验**（见 report）。
  - ④**migration-log 计数链审计** ✅:59→44→33→15→0(P4c)+ 130→122→59→0(整场)与 git 逐位对账;retirement 裁决完整;收官段陈述 campaign total。
  - ⑤**树残余审计** ✅:`@GameTest(` 全树 **0 处**;neoforge testmod source set **空**(无 `.java`);三删类不存在。
- **文档**:`mc-testkit/README.md` provider 表 8→**13 行**(+Station 15/Scheduler 11/Survival 13/Avatar 5/Process 15)+ 计数 71→**130** 全处;migration-log **campaign close 收官段**(全链 git 对账 + 144/130 两数对账 + retired-without-scene 实核=仅 1 个 `descentDrift` + dayTime hygiene 附录);doc-rot 扫除(`AgentDriverNeoForge.java` 注释改指当前真相 + `GameTestManifest.java` javadoc 重指 migration-log);`ad.serverCombat` dayTime 恢复 hygiene(非行为改动,cleanup 卫生)。
- **残余 → 后继 phase P4-final(GameTestServer 机器退役清单)**:①`gameTestServer` run config 移除(fabric+neoforge);②`scripts/run_gametests.sh` + `scripts/gt_reconcile.py` 退役;③`GameTestManifest` + `AgentDriverNeoForge` gametest 钩子移除;④`AGENT_GT_ONLY` / `solo*` / batch 残留 grep 清理;⑤`.gitignore` run-gametest 条目;⑥文档 final sweep。**开放 task 不变**:task#86/#87/#88/#90/#91/#92。**user 待决**:`artifactId`(`agent_driver-testkit-*`)/ merge / 远端仓库。

- **✅ 终审 + fix wave 收尾**:终审(全 phase diff 2844189..8e99f21,opus)= **Ready to merge,0 Critical/Important**;2 Minor 修毕(`0c674c1`:drownEscapeGateMatrix 行数注 17+10→18+8 与 log 的 26 一致;neoforge/build.gradle testmod 注释改为 wave-9 后真相=run 已空载,仅为 P4-final 保留),2 Minor PARK(expect-file 措辞;144 脚注粒度)。终审横向核验:13 provider==13 service 行==130 场景双 expect-file;恰 3 withRequired(false)+1 passNote 拓扑门全带引证;被删三类零代码引用;全 phase 唯一 within() 变更=三雷 await+entityLeash 止血对(带禁三次加宽注);P4-final 清单可执行。**P4c Ready to merge**(2844189..0c674c1 十 commit);**campaign 收官:legacy 130 方法→0,dogfood 130 场景/13 provider/3 传感器/1 拓扑门**。
## 2026-07-18 ✅ P4b 非 Server 家族全迁(62 场景迁 + 1 retired-without-scene)→ legacy 缩到 Server-only(122→59)+ dogfood 涨到 71 场景 — spec §6.3 非 Server 侧收官,五门绿(③t1 首跑揭出 agentRpcSmoke 跨拓扑缺口 → controller 裁决=最小诚实拓扑守卫 dedicated-only,task#92 立案,t1 重跑 GREEN)(branch `feature/executor-permove-ascend`)

- **✅ P4b 六 commit 已落(四迁移波)**:①`b4a85f6` plan(P4b 波次);②`3eb6d62` (wave 2) **Terrain 12 迁移 + twins 删**(122→110,`AgentGameTestTerrain` 整类删)+③`159f202` (wave 2 close) **`descentDriftArena` controller 裁决 FULL RETIREMENT**(retired-without-scene,110→109;理由:自证 PROVEN FALSE GREEN gap#49 + 忠实迁移物理不可能=>60s 单 tick A* churn 触 `ServerHangWatchdog` 崩 dogfood harness + 降级即破确定性/重设坏 rig 皆禁;descent 覆盖由 `ad.descentYaw` 金签名门续守);④`1d2f748` (wave 3) **Bias 13 迁移**(109→96,全 planner-only,`AgentGameTestBias` 整类删,新 provider `AgentDriverBiasScenes`);⑤`84ffea5` (wave 4) **Water 双家族 21 迁移**(96→75,WaterBank 11 + WaterCross 10,两整类删 + 两 Registrar 行删);⑥`3fc846f` (wave 5) **Core 12 + CombatSense 2 + BuildBlock 2 = 16 迁移**(75→59,三整类删 + 三 Registrar 行删——删后 **Registrar 只剩 `AgentGameTestServer`**);⑦本条目 = Task 5 五门验收 + 文档。
- **计数链审计(与 git history 逐位对账)**:130→**122**(P4a)→**109**(P4b w2,−12 迁 −1 retired)→**96**(w3,−13)→**75**(w4,−21)→**59**(w5,−16)。四 P4b 波共移除 **63** legacy `@GameTest`(62 迁场景 + 1 retired)。dogfood suite = 9 原 + 62 迁 = **71** 场景(plan 估 72,实 71=descentDrift 无场景退役);legacy 存活 **59** = 纯 `AgentGameTestServer`(P4c 切分)。migration-log 加收官段。
- **✅ 五门验收(前台有界顺序,ONE server/client at a time)**:
  - ①**dogfood 双 loader ×2 全绿+结果集恒等** ✅:neoforge ×2 + fabric ×2,四跑 `(name,outcome)` 75 记录**同一 md5**(loader 内 ×2 恒等 + 跨 loader 恒等);71 `ad.*` + 2 builtin + 2 canary(`canaryMustFail`→FAIL/`canaryMustTimeout`→TIMEOUT,`canaryMustSwallow` 正确略过);3 optional sensor 如期(`ad.vineClingFidelityProbe` optional-PASS,`ad.vineOverWaterClimb` optional-FAIL pocketTicks=29,`ad.riverSheerBank` optional-FAIL wallPressTicks=51),9 原 golden 全 PASS。
  - ②**legacy 全量** ✅ **VERDICT GREEN**:`registered=59 entered=59`(0 swallowed / 0 drifted),**全 59 required PASSED**,`gradle_rc=0 reconcile_rc=0`,59 tests 6.228s;存活失败 family 为**空**(agentRpcSmoke 已 wave 5 迁出);`forbiddig` 子串属存活 `serverForbidDigWallArena` 非退役 Bias;**本轮无 livelock**。
  - ③**instrument 双 loader 23/23 ✅**(neoforge GREEN + fabric GREEN);**t1.py 首跑 RED → 拓扑守卫收口后 GREEN ✅**。首跑唯一红 = `ad.agentRpcSmoke`(required)在 T1 integrated-client 拓扑跑 JS 验证套件(259 检查)**8 失败**(TOTAL 259/PASS 251/FAIL 8)。**非 P4b 回归**(迁移字节忠实,同 `AgentDriverCommon.runValidation()` 在 T0 专服 ×4 全 PASS),而是 **agentRpcSmoke 是专服 smoke 套件、从未在客户端拓扑跑过**(legacy 是 GameTestServer @GameTest;P4b 前 t1 只跑 9 场景)——gate③ 首次把它推上 T1 才揭出。8 失败=5 硬 JS 绑定 `TypeError: … of undefined`(`observe…player` / `Agent.bot.tunnel` / `blocks_to_avoid` 客户端面绑定缺失或异形)+ 2 行为(scheduler retreat 通道归属)+ 1 flaky 名额(41_defense 箭矢 / 57_replay 形)。**controller 裁决 = 最小诚实拓扑守卫**(不降 required,保住专服已证保证):场景体 `!ctx.level().getServer().isDedicatedServer()` → 早返回 PASS + 可见 marker(`SceneContext.passNote` 打进结果 JSONL reason + server log,引 task#92 声明"RPC 验证套件专服限定,待拓扑感知 checks");非吞(reconcile 仍计 entered、reason 可见、8 检查分歧证据入 task#92/TODO)。**验证**:t1 重跑 **GREEN**,`ad.agentRpcSmoke` PASS(1 tick,marker 在 t1 results JSONL 可见);dedicated 路径**字节不变**——dogfood neoforge ×1 + fabric ×1 result-set md5 与基线恒等,`ad.agentRpcSmoke` 满跑 156/181 tick reason="" 全 259 PASS。真修(拓扑感知 checks / 签名门锁定 8 已知分歧)= **task#92** scope。
  - ④**生产 jar 字节门双 loader + maven 复验** ✅:`:fabric:build :neoforge:build` **BUILD SUCCESSFUL**,两 remapped 生产 jar + `publishToMavenLocal` 后 `~/.m2` published jar 同断言全过——service 文件 `…scene.SceneProvider` = 0、provider 实现类/`AgentGameTest*`/`SimProbes` = 0、`TestResetVerb`+`TestRunVerb` = 2、`TestkitVerbHook` service+class present;testkit mod jar scene 实现类亦 0。(注:`net/magicterra/testkit/scene/SceneProvider.class` **接口**类合法随生产 jar 出货,非 provider 实现,非 service 文件。)
  - ⑤**计数链审计** ✅ 见上;migration-log 收官段 + README 「加场景」单点 how-to 补入。
- **波次结局(记录)**:descentDrift retired-without-scene(controller 裁决);riverSheerBank task#91 发现(gap#48 shared-body 假绿被隔离掀成确定性 RED);agentRpcSmoke scope correction(count-forced 迁移=`AgentGameTestServer` 已恰 59,16 非 Server 必须全出才达 `registered==59`;深 RPC-parity family 覆盖留 Server P4c);entity-visibility 跨壳发现(CombatSense 真专服 chunk force-load 后 ~tick 9 才入 section index,场景 bounded await 轮询)。
- **文档**:`mc-testkit/README.md` 新增**场景库结构节**(八 provider 分家族 + 计数表 + 3 optional-FAIL sensor 立案说明)+**「如何加场景」单点 how-to**(provider 类入 `.scene` → `Scene.of` 注册 → 新 provider 追 service 行 → 双 expect-file 同 commit → 重名门 + reconcile 门兜底);migration-log **P4b 收官段**(计数链 git 对账 + 71 场景七/八 provider + 3 sensor + 残余);扫清 waves 2-4 遗留 doc-rot(AgentGameTestServer:596 悬空 `{@link WaterCross}` → migration-log;:2136/:4065 stale descentDrift/Terrain 引用注退役;PathFinder:129 `{@code AgentGameTestBias}` → `ad.*`/`AgentDriverBiasScenes`;expect-file wave-4 注释 2 optional → 3 加 riverSheerBank)。
- **✅ 终审 + fix wave 收尾**:终审(全 phase diff b4a85f6..0210b55,opus)= **Ready to merge,0 Critical/Important**;4 Minor 三修一挂(`6c43851`:migration-log 收官段 seven→eight providers、wave-5 补 post-deletion reconcile 段(59/59 GREEN 入审计档对称)、`InputReleaseGate.java` 生产 javadoc 改指 `ad.inputReleaseGate`;dead-return 若干=PARK)。终审横向核验:四波协议一致(Y-map/命名/LIFO/radius 算术)、sensor 集恰 3 处 withRequired(false) 全带引证且 README/expect/代码三方一致、passNote 无滥用面(仅 DONE 路径读,FAIL/TIMEOUT 不读)、计数链密封(Server 恰 59 个 @GameTest)。**P4b Ready to merge**(b4a85f6..6c43851 八 commit)。
- **残余(P4b 后)**:①**P4c = Server 59 拆 family**(含 4 处手动 `level.tick()` 雷:livelock 惯犯 `serverForbidDigWallArena` / `serverCombat*` re-entrant arena = 迁移雷区)→ **P4-final = GameTestServer + `solo*` batch 退役 + `run_gametests.sh` 正门切换**(spec §6.6);②**task#92**:`ad.agentRpcSmoke` 拓扑守卫已落(dedicated-only,marker 可见),真修 = JS 验证套件拓扑感知 checks 或签名门锁定恰好 8 已知分歧(observe…player / Agent.bot.tunnel / blocks_to_avoid 客户端绑定 + scheduler retreat + 1 flaky),使其在 T1/T2 客户端面也能 required 跑绿(注:我的 tracker 建条落成 #79,与已闭 survival task#79 撞号,controller 修号为 task#92);③**task#86/#87/#88/#90/#91** 均不变待修;④**artifactId 命名残余不变**(`agent_driver-testkit-*`)。

## 2026-07-18 ✅ P4a 测试搬出生产 jar 进 testmod source set(三模块)+ 生产 jar 字节门(双 loader)+ 迁一批删一批首删(130→122,legacy required 首次全绿)— spec §6.3 两侧全落(branch `feature/executor-permove-ascend`)

- **✅ P4a 七 commit 已落**:①`e8f123f` plan(P4a 竖切);②`e11294c` (T1) **legacy `@GameTest` 搬出生产 jar 进 neoforge testmod source set**(8 arena 类 + Support 从 `neoforge/src/main` → `src/testmod`,同包不改名;`sourceSets.testmod` classpath extends main output+classpaths;`mods { named('main') { sourceSet testmod } }` 让 FML 扫到迁移的 `@GameTestHolder`;`runGameTestServer` `source sourceSets.testmod`——SimProbes 仍在 common main,委托断裂窗口零);③`d199eb2` (T2) **`ad.*` 场景搬出生产 jar 进 common testmod + neoforge 接线**(`AgentDriverScenes`/`SimProbes` + `SceneProvider` service 从 `common/src/main` → `src/testmod`,**迁进 `.scene` 子包**避 split-package;neoforge testmod compile classpath += `:common` testmod output,RUNTIME 经 loom `mods{ named('main'){ sourceSet project(':common').testmod } }` modFolders 组交付=按 run scope);④`3403612` (T3) **fabric 接线**(fabric testmod 源集**空源**仅作 run `source` 载体;fabric-loom `named('main')` 在 script body 时未建→改 `maybeCreate('main')` 显式复刻 loom 默认;`:common` testmod 直接上 fabric testmod `runtimeClasspath`=per-run scope,机制与 neoforge 反向但同效;全 armor 矩阵全绿);⑤`1f30322` (T4) **迁一批删一批首删**(8 已迁 `ad.*` twins 退场,130→122,migration-log drift 记录);⑥`eea572f` docs backfill T4 deletion SHA;⑦本条目 = Task 5 四门验收 + 文档。
- **✅ 四门验收(前台有界顺序,绝不同时两服务器/客户端;四门一次全绿,零 flake 重跑)**:
  - ①**生产 jar 字节门双 loader** `./gradlew :fabric:build :neoforge:build` **BUILD SUCCESSFUL**——`unzip -l` 两 remapped 生产 jar:**ABSENT 集全 0**(`AgentGameTest`/`AgentDriverScenes`/`SimProbes`/`services/…scene.SceneProvider` 各 0 条,双 loader)+ **MUST-CONTAIN 集全 1**(`TestResetVerb`/`TestRunVerb` class + `META-INF/services/net.magicterra.testkit.TestkitVerbHook` 各 1,双 loader)。字节门**双向**:同时反向断言 P3a 有意留 main 的生产 verb 未被误删。
  - ②**legacy 全量** `scripts/run_gametests.sh` **VERDICT: GREEN exit=0**——`registered=122 entered=122`(**0 swallowed / 0 drifted**,#85 P0 门武装),**全 122 required PASSED**,单 **optional `vineoverwaterclimbarena`** 失败(−711 live bug,permitted survivor 集内);8 个删除 twin 名(`serveravatargearscopeprobearena`/`descentyawarena`/`selfshaftdiguparena`/`entityleashrepatharena`/`buriedore`/`ascend*`/`diagonal*`)在 log/manifest **零出现**;122 tests 21.78s / build 46s;**本轮未撞** `ChunkMap.processUnloads` livelock(clean 首启)。与 migration-log 逐位一致。
  - ③**testkit 矩阵**:t0 dogfood **neoforge GREEN 44s**(9/9 `ad.*` + 三金丝雀,`ad.buriedOre` 10.4s / `ad.entityLeash` 104 ticks)+ **fabric GREEN 37s**(9/9,`ad.entityLeash` 32 ticks);`instrument.py --loader neoforge` **23/23 GREEN 47s** + `--loader fabric` **23/23 GREEN 43s**;`t1.py`(fabric 真客户端)**VERDICT: GREEN 65s**(9/9 integrated server 上全 PASS,`ad.entityLeash` **31 ticks 首跑即绿,task#88 未犯**)。金字节由场景内断言保证——PASS 即命中(descentYaw/selfShaftDigUp/gearScope reason 空)。
  - ④**发布复验** `./gradlew publishToMavenLocal` **BUILD SUCCESSFUL exit=0 6s**——`~/.m2` 里 **published** 生产 mod jar(`agent_driver-fabric`/`agent_driver-neoforge`)`unzip -l` 同断言:ABSENT 集全 0 + MUST-CONTAIN 集全 1(**maven 面 = build 面同证**);mc-testkit mod jar(`agent_driver-testkit-fabric`/`-neoforge`)ABSENT 集亦全 0。
- **🏁 T4 里程碑**:删除前 legacy required 稳定核失败 5 名(`serveravatargearscopeprobearena`/`descentyawarena`/`selfshaftdiguparena`/`entityleashrepatharena`/`buriedore*`)**恰是本波删除的 5 个已迁 twin**——退场后 **legacy required 套件首次全绿**(122/122 required PASS,唯一残余 = optional vine live bug)。
- **⚖️ legacy 门公式重构(诚实记录,"5 名确定性失败"前提被 T1 A/B 证伪)**:旧台账把 legacy 失败集定性为"5 名确定性彩票家族揭露";T1/migration-log 的 A/B 证据显示这 5 名**本就是已迁 twin 的 legacy 影子**(场景侧字节绿=非回归),删掉即消失。故 P4a 起 legacy 门公式 = **`registered==entered ∧ 0 swallowed ∧ failures ⊆ 文档化 family`**(family 删除后 = `⊆ { vineoverwaterclimbarena(optional), deepwatercross*, agentrpcsmoke }`),任一 family 外新名或删除名复现 = STOP/BLOCKED。reconciliation(零吞零漂)是门②真正的 P0 完整性检查。
- **残余(照旧未清)**:①**P4b+ family 迁移波**(每波=迁一批→A/B→删一批,直接落 testmod):Terrain 13 / Bias 13 / WaterBank 11 / WaterCross 10 / 主类 12 / CombatSense 2 / BuildBlock 2 / Server 59 remaining = 合计 122(与门② `registered=122` 对账;按 wave-1 删 8 后实数逐类重点,plan 文档中的波次地图是执行前估算)(波次地图见 `docs/superpowers/plans/2026-07-18-testkit-p4a-testmod-destination.md`);②**GameTestServer + `solo*` batch 机制退役 + run_gametests.sh 正门切换 = P4-final**(全量迁完后,spec §6.6);③**task#86**(`selfShaftDigUp` gap#53,`ad.selfShaftDigUp` 场景为 required 签名门,`worstBackslide` 金值 `20.252203415101263`)/ **task#87**(`entityLeashRepathArena` y≈−60 rig)/ **task#88**(`ad.entityLeash` harness tick-debt 根修)/ **task#90**(仪表面双 verb:持键回读 + world-use)均不变待修;④**artifactId 命名残余不变**(mc-testkit mod jar 发布坐标 `agent_driver-testkit-*` 非 `mc_testkit-*`,root `subprojects{}` 发布块 eager `.get()` 早解析;`mc_testkit-junit` 独立 publication 不受影响;择期由 user 决)。
- **文档**:`mc-testkit/README.md` testmod 节从「惯例 v1(插件 flag)」升级——新增 **"Realized wiring: agent-driver's own testmod migration (P4a)"** 节(三模块 testmod 源集 / `.scene` 子包 split-package JPMS 教训 / loom `named('main')` vs `maybeCreate('main')` loader 机制差异 / fabric per-run runtimeClasspath vs neoforge 全局 modFolders scope / 生产 jar 字节门升为常驻验收惯例 + 双向断言 + maven 复验)+ 交叉引 `docs/testkit/migration-log.md` drift log。
- **✅ 终审 + fix wave 收尾**:终审(全 phase diff e8f123f..36b8daf,opus)= 1 Important=README 与 orchestration-contract 两处权威 service-file 示例仍印 pre-`.scene` FQN+旧 `src/main` 路径(照抄=ServiceLoader 找不到类→provider 静默零场景→自洽假绿,正是 #85 吞形态)→ `ffc31d7` 修正(FQN+路径+split-package 理由入契约文档,grep 证 active 文档零残留);其余 6 组 deferred Minor 全 PARK;生产源码→testmod 引用审计 clean(含反射/字符串);四处跨 loader 不对称记录一致。**P4a Ready to merge**(e8f123f..ffc31d7 九 commit)。Minor 挂账:「如何加场景」缺单点 how-to(可推导,P4b 顺手补)。

## 2026-07-18 ✅ P3b gradle-plugin(三任务 shell 编排契约)+ 客户端进程池 + testmod source-set 惯例(v1 有界)+ maven 发布准备 — spec §7 P3 余三件全落(branch `feature/executor-permove-ascend`)

- **✅ P3b 六 commit 已落**:①`a086c4d` plan(P3b 竖切);②`d6d54c0` (T1) **gradle-plugin** `net.magicterra.mc-testkit`——`testkitServer`/`testkitClient`/`testkitE2E` 三任务 shell 冻结 python 编排契约(t0/t1/t2.py),**逐字节透传退出码**(不解析 JSONL 不复判,orchestrator 唯一裁决权),13 个 TestKit functional 自测;③`4ccd5db`+`4bc4f01` (T2) **客户端进程池** `pool.py ensure/status/stop`——跨 invocation 保活 `--hold` 拓扑(endpoint+liveness 探活复用,DETACHED 启动,flock 守 state,SIGINT-by-PID 释放,拒杀非自启 orphan),41 自测;④`2a44b9e` (T3) **testmod source-set 惯例**——插件 opt-in 注册 `testmod` 源集(仅 classpath 接线,**loom run 自动接线明示留 v2**,agent-driver 自身 130 legacy `@GameTest` 迁移挂账照旧);⑤`e1266bd` (T4) **maven 发布准备**——`mc_testkit-junit` publication(纯 JVM,POM 声明 gson/junit-jupiter-api)+ 六 mod-jar POM 清洗(JiJ 防双拷)+ 支持矩阵/兼容承诺入 README。本条目 = Task 5 五门验收 + 文档。
- **✅ 五门验收(全前台有界,顺序跑,绝不同时两服务器/客户端;五门一次全绿,零 flake 重跑)**:
  - ①**插件路径 neoforge dogfood**:`./gradlew testkitServer -Ptestkit.loader=neoforge` **exit=0 GREEN 47s**——插件 exec `t0.py --loader neoforge --run-task :neoforge:runDogfoodServer`,**9/9 `ad.*` PASS**(六字节金值由场景内断言保证,PASS 即命中),`ad.entityLeash` 74 ticks,VERDICT GREEN。
  - ②**插件路径 fabric T1**:`./gradlew testkitClient` **exit=0 GREEN 64s**——插件 exec `t1.py --loader fabric`,自管 Xvfb `:101`,真客户端 title→singleplayer→world,**9/9 `ad.*` PASS**(`ad.entityLeash` 32 ticks,task#88 未犯),session 47.8s。
  - ③**pool 流程**:`pool.py ensure --topology t2` 冷启 **52s**(`started`,detached hold pid=2278781,resident server pid=2279570)→ `instrument_client.py --topology t2 --attach` **GREEN exit=0 1s**(7 check + 2 canary PASS,双 socket client rpc=40047/server rpc=39345)→ 第二发 `pool.py ensure` **`reused` exit=0 1s**(秒级复用,vs 52s 冷启)→ `pool.py stop` **clean 16s**(SIGINT-by-PID,endpoint 消失,state 0 entries,端口 39801/25597 全释放)。
  - ④**发布复验**:`./gradlew publishToMavenLocal` **BUILD SUCCESSFUL exit=0**——七件全发(`mc_testkit-junit-0.1.0+1.21.1` .jar/.pom/.module 落 `~/.m2`);消费者冒烟 `rm -rf build && ./gradlew compileJava` **exit=0**——`smoke.Smoke` import `net.magicterra.testkit.junit.Testkit` 从 mavenLocal 解析+编译,`Smoke.class` 产出。
  - ⑤**自测全绿**:插件 `functionalTest` **13/13**(强制 `--rerun-tasks`,`TestkitPluginFunctionalTest` 8 + `TestkitTestmodSourceSetFunctionalTest` 5,0 failures)+ `pool.py --self-test` **41/41** + `instrument_client.py --self-test` **42/42** + `t1.py --self-test` PASS + `t2.py --self-test` PASS。
- **⚖️ 门① loader-override 接线(诚实记录)**:插件唯一 loader 覆盖机制是 `testkit { loader }` extension(**无内建 `-P` 绑定**),而 root dogfood 消费者原本**无 `testkit {}` 块**——故 brief 文档命令 `testkitServer -Ptestkit.loader=neoforge` 原会静默跑 fabric(**错 loader 假绿**)。修 = root `build.gradle` 加一行 `testkit { loader = (findProperty('testkit.loader') ?: 'fabric') }` 桥,把 `testkit.loader` 工程属性穿到 extension——文档命令真选 neoforge,fabric 仍为默认(门②不变)。这是 dogfood 消费者接线(非阈值调绿),已入本 docs commit。
- **残余(照旧未清)**:①**插件 v2 = loom run 自动接线**(testmod source-set 目前仅 classpath 接线,注册的 `SourceSet` 经 `testkit.testmodSourceSetRef` 暴露供消费者自接 loom run config,插件不碰 loom——loom run-config API 跨版本差异大,自动接线留 v2);②**真远端仓库 = user 决策**(T4 只落 mavenLocal,发到真 Maven 远端由 user 定夺);③**agent-driver 自身 testmod 拆分挂账照旧**(130 legacy `@GameTest` 仍居 `main`,违惯例,迁 `testmod` 源集是独立挂账任务,非 P3b 目标);④**task#86**(`selfShaftDigUp` gap#53)/ **task#87**(`entityLeashRepathArena` y=-60 rig)/ **task#88**(`ad.entityLeash` harness tick-debt 根修)/ **task#90**(仪表面双 verb:持键回读 + world-use 输入)均不变待修;⑤**JUnit 并行 fork 映射仍开放**(单 `--hold` 拓扑串行租约,多 fork 需拓扑池,未做)。
- **⭐残余(Task 4 发现,已入 README maven 节):artifactId 前后不一致**——mc-testkit mod jar 发布为 `agent_driver-testkit-*` 而**非** `mc_testkit-*`:root `build.gradle` `subprojects{}` 发布块 `artifactId = base.archivesName.get()` 用 eager `.get()`,在子脚本 `base.archivesName='mc_testkit-*'` 覆盖**之前**就解析(取 allprojects 的 `archives_name-project.name`=`agent_driver-testkit-*`);jar **文件名**在盘上确是 `mc_testkit-*`,但**发布坐标**是 `agent_driver-testkit-*`。README artifact 表记的是真坐标(消费者按表依赖,勿用 jar 文件名)。修它会改已发布坐标——今仅 mavenLocal,择期由 user 决(可修 eager/lazy 或改子模块命名意图)。`mc_testkit-junit` 不受影响(独立 publication,坐标 `mc_testkit-junit`)。
- **文档**:`mc-testkit/README.md` 新增 **"Gradle plugin: task entry points (P3b T1)"** 节(三任务表 + loader 桥 + 退出码透传)+ **"Client process pool (`pool.py`) — P3b T2"** 节(ensure/status/stop + endpoint 复用 + SIGINT-by-PID 拒杀 orphan);四 P3b 节(plugin tasks / testmod convention / pool / maven)互链 + 交叉引 T0/T1/T2(插件 shell t0/t1/t2、pool 喂 attach、publish 喂 JUnit 消费者)。
- **✅ 终审 + fix wave 收尾**:终审(全 phase diff a086c4d..801f88e,opus)= 1 Important=pool 附录工作流 B 的 gradle 路径笔误 `:mc-testkit:junit:test`(项目实名 `:testkit-junit`,README 三处全对,仅 orchestration-contract-v0.md:405 一处错)→ `f59a4d9` 修正;全部 6 条 deferred Minor 三裁 PARK(理由入 SDD 台账);pool.py 杀进程路径安全审计 clean(仅碰自启 PID,orphan 拒杀无洞)。**P3b Ready to merge**(a086c4d..f59a4d9 七 commit)。

## 2026-07-17 ✅ P3a T2 生产拓扑(专服+真客户端)+ `mc.test.run` 按需触发 + 双 socket 仪表 + 常驻服务器复用 — 产品闭环:testkit 自消费 P2a `registerVerb` SPI(branch `feature/executor-permove-ascend`)

- **✅ P3a 六 commit 已落**:①`573657b` plan(P3a 竖切);②`a7200ad` (T1) **`mc.test.run` 按需触发 verb**——`registerVerb` SPI **首个 testkit 消费者**(产品自消费),幂等(in-flight 闩拒重入),autorun 路径原封不动;③`66ac243` (T1 硬化) **无条件 testkit forwarding** + on-demand latch/stop 加固;④`7539a4e` (T2) **t2.py 双进程拓扑壳**——专服 `runT2Server` + 真客户端,multiplayer 直连驱动(title→Multiplayer→Direct→127.0.0.1:port)+ 双端探针;⑤`ed5887d` (T3) **T2 场景执行 over `mc.test.run`** + `dedicated_plus_client` attach 端点(`serverRpcPort` 可选键);⑥`13483da` (T4) **T2 仪表契约双 socket**——#41/#45/#55 永久断言落**专服 PlayerList** 面 + 复用轮常驻服务器。本条目 = Task 5 六门验收 + 文档。
- **✅ 四能力交付**:①**`mc.test.run` SPI 自消费**(registerVerb 首个 testkit 消费者=产品闭环证明,按需/幂等,autorun 门不动);②**T2 双进程拓扑**(专服 JVM + 真客户端 JVM,双 agent-driver RPC socket,生产同构=`SurvivalTest` 形状,P1b headless 缺口在**真生产拓扑**收口而非模拟);③**T2 场景执行字节相等**(生产拓扑上 `mc.test.run` 触发同套 `ad.*` 场景,golden 与 T0/T1 字节相等 ×2 loader);④**双 socket 仪表 + 常驻服务器复用**(`instrument_client --topology t2 --attach --rounds N` 断连→重连**同一常驻专服**,服务器**永不重启**,per-round PID 断言不变=P3b 客户端进程池雏形)。
- **✅ 六门验收(全前台有界,顺序跑,绝不同时两服务器/客户端;六门一次全绿,零 flake 重跑)**:
  - ①`t2.py`(fabric 场景门)**exit=0 GREEN 87s**:9/9 `ad.*` PASS,scenes PASS ⇒ golden 字节命中(descentYaw/selfShaftDigUp/gearScope reason 空=命中);双端探针 PASS,registered scenes=14。
  - ②`t2.py --loader neoforge` **exit=0 GREEN 91s**:9/9 `ad.*` PASS,双端探针 PASS(模板已在,无需 mint)。
  - ③`instrument_client --topology t2 --attach` **exit=0 GREEN**(7 check + 2 canary,resident server pid=2163677);`--rounds 3` **GREEN**,三轮 per-check 完全一致,resident-server PID `[2163677,2163677,2163677]` 不变(RESIDENT,服务器未重启)。
  - ④同一 `--hold` 拓扑上 `:testkit-junit:test --rerun-tasks` **BUILD SUCCESSFUL exit=0**:live UI **5 pass**(chatScreenType/inventoryOpenClose/screenTreeSlots + canary mustFail/mustTimeout)+ **1 `@Disabled` skip**(containerFurnace,task#90)+ `SelfTest` **11 total = 9 pass + 2 gated-skip**(env-on 时 2 fail-fast 自测按门跳过),failures=0 errors=0;随后 SIGINT t2.py PID **端点消失 + 双 JVM 全下 + 零孤儿**验证通过。
  - ⑤全 armor(`mc.test.run` 改动的全套回归):neoforge dogfood **GREEN 37s**(六字节金值全 PASS reason 空)+ fabric dogfood **GREEN 38s** + `instrument.py --loader neoforge` **23/23 GREEN 43s** + `--loader fabric` **23/23 GREEN 39s** + `t1.py` fabric **GREEN 65s**。
  - ⑥`instrument_client`(t1 默认自启)**exit=0 GREEN 44s**:t1 面零回归(cold boot 27.4s + 全 check PASS)。
- **⚖️ loader-forwarding 裁决记录**:测试壳的 forwarding 改为**无条件**——生产 `runServer` 现在也布防 `mc.test.*` verb(不再只在 testkit run 配置里)。定性 = **testkit-wiring 重分类**,非行为变更:RPC 面本就是第一方能力面(见 memory `feedback_rhino_scripts_unrestricted`/`feedback_agent_driver_positioning`),`mc.test.*` 在任意专服可达是 **trust-model-consistent**,已记录。**主动后果须知**（终审 Important,如实记录）:在存活生产服上调 `mc.test.run` 不只是被动布防——套件会真跑（网格 tp/`/damage`/summon）且结束时 `TestkitHarness.finish()` 调 `server.halt(false)` **关停该服务器**（在线玩家全被踢）。能触达 RPC 面即拥有该进程（信任模型同 Rhino 直令）,但运维侧要知道这个 verb 的终态是 halt。dogfood 双 loader 六字节门(gate⑤)证 autorun 路径 footer 照发未受影响。
- **📌 GameTestServer legacy 套件揭露(非 P3a 门,已定性)**:legacy `@GameTest` 套件在本 HEAD 的状态已在台账 P3a-2 条目定性——**5 个确定性失败 = 已归档的彩票家族揭露**(非新回归),不作为 P3a 门跑。5 名确定性集指针见台账。
- **残余(→ P3b)**:①**gradle-plugin**(把 testkit 打成可发布插件);②**客户端进程池**(T2 常驻服务器复用是雏形,进程池化跨轮复用客户端 JVM);③**maven 发布准备**;task#86/#87/#88/#90 照旧未动;**JUnit 并行 fork 映射仍开放**(单 `--hold` 拓扑串行租约,多 fork 需拓扑池,未做)。轻量残余:`instrument_client` 的 `run_checks` header 硬编码 loader `"fabric"`=纯装饰(实际 loader 从端点读,不影响判定);push-socket 残留容忍度注记照旧。
- **✅ 终审 + fix wave 收尾**:终审(全 phase diff 573657b..5236cf1)= **Ready to merge,0 Critical**;1 Important=`mc.test.run` 在存活生产服的**主动后果**(真跑套件+`server.halt(false)` 关服)须显式入档→已并入上方裁决记录;`35c9478` fix wave——`t2.py --hold` 与 scored 清单解耦、`run_checks` header loader 从拓扑穿透(端点为源)、PID 文案收敛为"证据非判决"。拒修=TestkitHarness ctor 双写(纯装饰 Java 改动要走全 armor,不值)。新残余(fail-safe):`TestkitCommon` armed 静态态在同一 client JVM 跨多世界会 stale(`!isRunning` guard 大声拒,永不静默错;若要多世界支持则 SERVER_STOPPING 重置)。

## 2026-07-17 ✅ P2c JUnit5 attach + 首批 in-game UI 场景 + neoforge 客户端对等 — out-of-process JUnit 模块经 `TESTKIT_ENDPOINT` attach 活拓扑跑客户端 UI 断言,偏差(1) 收口(branch `feature/executor-permove-ascend`)

- **✅ P2c 五 commit 已落**:①`0a8af32` plan(P2c 竖切);②`14c17a5` (T1) **TESTKIT_ENDPOINT 端点契约**——`t1.py --hold` 原子写 schema-v1 描述符(`.tmp`→`os.replace`)+ `orchestration-contract-v0.md` attach 附录;③`869e4a9` (T2) **testkit-junit 模块**(纯 JVM,无游戏 classpath)——`Endpoint.parse`(缺键大声抛)/`Testkit.attach` fail-fast(带原文 hint `python3 scripts/testkit/t1.py --hold`)/裸 RPC 客户端 `TestkitRpc`/`Testkit` 类型化仪表门面/`TestkitExtension` 单例串行租约,`SelfTest` 9 纯 JVM 自测;④`d448ead` (T3) **首批 in-game UI 场景 over attach**——`ui.inventoryOpenClose`/`ui.screenTreeSlots`/`ui.chatScreenType` + 双金丝雀(`canary.mustFail`=assertThrows 真断言 / `canary.mustTimeout`=`TestkitTimeoutException` 类型区分),`ui.containerFurnace` 可见 `@Disabled`(裁决延后 task#90);⑤`b04cb77`+`fb55c31` (T4) **neoforge 客户端对等**——`t1.py --loader`,neoforge 客户端 integrated server 上跑同批 T1 场景 golden 字节相等,偏差(1) 收口。本条目 = Task 5 五门验收 + 文档。
- **✅ 四能力交付**:①**端点契约**(`TESTKIT_ENDPOINT` → schema-v1 描述符文件,给独立 JVM 发现活拓扑,不硬编码端口/世界名);②**testkit-junit 模块**(纯 JVM JUnit5 attach——UI 场景体跑在测试线程,可跨线程 `await` 异步客户端屏,承接 P2b 偏差 2 的首批 in-game UI 场景);③**首批 UI 场景 over attach**(inventory/chat 屏 live 断言 + 双哨兵金丝雀);④**neoforge 客户端对等**(`--loader` 泛化,同 JUnit 模块零改 attach 两 loader,收口 P2b 偏差 1)。
- **✅ 五门验收(全前台有界,顺序跑,绝不同时两服务器/客户端)**:
  - ①`:testkit-junit:test` **无 env** → **BUILD SUCCESSFUL exit=0**:`SelfTest` **9 pass 0 skip**(7 恒跑 + 2 fail-fast 在 env 缺席时跑 PASS)+ 6 live UI 全 **skip**(`@EnabledIfEnvironmentVariable` 关);
  - ②`t1.py --hold`(fabric,port 34875 endpoint 文件出现于 ~poll)+ `TESTKIT_ENDPOINT=<abs> :testkit-junit:test --rerun-tasks` → **BUILD SUCCESSFUL exit=0**(4s):`SelfTest` **7 pass + 2 fail-fast skip**;`ui.*` **5 live pass**(`mustFail`/`mustTimeout`/`chatScreenType`/`inventoryOpenClose`/`screenTreeSlots`)+ `containerFurnace` **skip@Disabled**;SIGINT `t1.py` PID → launcher/client/endpoint 文件三者 GONE(KeyboardInterrupt=文档化释放路径),零 orphan;
  - ③`t1.py`(fabric scored)→ **VERDICT: GREEN exit=0**(46.9s session/62s wall):**9/9 `ad.*`** 全 PASS + 3 金丝雀按预期(`descentYaw`/`selfShaftDigUp`/`gearScope` golden 字节由场景内断言保证——PASS 即字节命中);
  - ④`t1.py --loader neoforge` → **VERDICT: GREEN exit=0**(49.7s/65s):**9/9 `ad.*`** 全 PASS,同 golden 字节,`ad.entityLeash` **31 ticks 首跑即绿**(task#88 未犯,零复跑);
  - ⑤`instrument_client.py --rounds 3` → **REUSE VERDICT: GREEN exit=0**(52s):三轮 per-check outcomes 逐位同一(cold boot 27.5s / round1 1.5s / round2 4.1s / round3 3.6s ≈7× 更省);
  - **五门全 GREEN 一次过,零 flake 复跑**(task#88 entityLeash 签名本轮门④未犯)。
- **⭐偏差延续 → task#90(`ui.containerFurnace` 延后)**:偏差从 P2b「UI 场景归 P2c」演进为「world-use 输入维缺仪表 verb」——打开方块实体容器屏需**世界右键**,仪表面无此 verb(`mc.client.input.click` 只点已开屏 widget、`mc.client.input.key` 只走键盘绑定、唯一世界右键的 `mc.bot.useItem` 是禁依赖的行为面)。**inventory/chat 屏已覆盖**(键盘可开),缺的只是世界右键;真修 = instrument 级 world-use verb,归 **task#90** 双 verb(与持键回读 verb 同批),controller 择期。可见 `@Disabled`(非静默缩编),证据见 `.superpowers/sdd/task-3-report.md`。
- **残余清单(排期以后)**:①**task#90 双 verb**——持键回读(`reset.behavior` 的 `keys` 子断言仍间接,抓不到 `releaseKeys()` no-op 回归)+ world-use 输入(承接 `ui.containerFurnace`);②`TestkitExtension` 的共享 `Testkit` 单例**从不关闭底层 `HttpClient`**(JVM 退出即回收,进程内无泄漏影响,但非显式 close);③**attach worst-case ~10s 窗口**(5s connect + 5s 探活)已在 README「JUnit 5 attach」节 Attach latency budget 段文档化(冷 boot ~28-30s 在 attach 之前、由 `--hold` 承担,亦已注明);④**JUnit 并行 fork 映射仍开放**(spec §11——本 phase 落串行租约单例,并行分片映射未做);⑤**task#88**(`ad.entityLeash` harness tick-debt 根修:arming 前排空 tick 债 / 改 wall-clock within)**仍开**;⑥task#86(`selfShaftDigUp` gap#53)/ task#87(`entityLeashRepathArena` y=-60 rig)**均不变**。
- **✅ 终审 + 收尾(本条目后两 commit)**:终审(全 phase diff 0a8af32..fc4646d)= **Ready to merge,0 Critical/Important**——零 common-Java 全 diff 核实、端点 schema python/Java 缝逐字节一致(reqInt/reqLong 分型含 epoch-ms long)、对称 env 门经 reviewer 复跑证实无双 skip 吞洞、loom 剖出点唯一;`1422fc5` polish——`TestkitRpc.close()` 补 `httpClient.close()`(残余②已修)+ `awaitCondition` 轮间 deadline 超越语义入 javadoc;残余①升级为 **task#90 三件套**(+终审新发现:`Testkit.exec()` 断 `ok` 字段但无任何 live 测试驱动过 = latent 契约,落 verb 后首个 live exec 须 pin 回复形状)。

## 2026-07-17 ✅ P2b T1 客户端拓扑 + 客户端仪表契约 + 复用完备性 + task#89 收口落地 — fabric 客户端 integrated server 上跑 `ad.*`,#41/#45/#55 真 PlayerList 断言(branch `feature/executor-permove-ascend`)

- **✅ P2b 五 commit 已落**:①`5c2ba7d` plan(P2b 竖切);②`e857999`+`63b91be` (T1) **T1 拓扑**——`:fabric:runTestkitClient` run config(客户端 JVM,`-Dtestkit.autorun`)+ `t1.py` 编排(自管 Xvfb 探测空 DISPLAY/PID track/PID kill、模板世界 copy→进→跑→删、`guidrive.py` label 匹配 widget 点击 title→singleplayer→world、world 进入起 integrated server 武装 harness、`verdict.py` 复用判决)+ mint 阶段(`-Pt1Autorun=false` 纯净无场景模板,archive 取于 clean quit-to-title 后)——**9/9 `ad.*` 在 integrated server 上 GREEN,全指标与 T0 dedicated golden 字节相等**;③`87cb869` (T2) `instrument_client.py` 客户端仪表契约(#41 全 36 槽 / #45 攻击冷却 / #55 伤害源——需 PlayerList 里真玩家,dedicated FakePlayer 够不到 + #280 未知键 live E2E + `mc.test.reset` reset 行为,7 检查 + 2 金丝雀);④`a4d5719` (T3) 复用完备性——`--rounds N`(quit-to-title→re-enter→`mc.test.reset`,3 轮字节同一)+ `--fresh-process` 降级(丢弃重启)+ 退出码 **4=BLOCKED**(轮间 verdict 漂移);⑤`4bc3ada` (T4) **task#89 收口**——validator(`requireSchemasFor`+`setParamsValidator`)现装于 catalog 完成后、`RpcServer` ctor 前,单 try 拆 catalog-try / 裸 validator install(在所有 catch 外,fail-fast 保留)/ RpcServer-try(`api!=null` 守卫)——**再无监听 socket 早于 validator 存在的 boot 窗口**。本条目 = Task 5 六门验收 + 文档。
- **✅ 四能力交付**:①**T1 客户端拓扑**(fabric 客户端 Xvfb 下托管 integrated server,`ad.*` 首次在真客户端跑,证明 harness 拓扑从 t0 dedicated 泛化到真 Fabric 客户端);②**客户端仪表契约**(`instrument_client.py`——`instrument.py` 的 T1 对位,需真 PlayerList 玩家的 #41/#45/#55 永久断言 + #280 live E2E + reset 行为);③**复用完备性**(客户端池复用 reset×3 轮完整性,冷 boot ~30s vs reuse round ~4s ≈7-8× 更省 = 证明 `mc.test.reset` 恢复每轮干净态,+ `--fresh-process` 降级 + BLOCKED 漂移门);④**task#89 收口**(validator 前移,boot 窗口无校验派发洞永久堵死)。
- **⭐偏差声明(1)——T1 目前仅 fabric,neoforge 客户端对等延后**:T1 只在 **fabric** 落地——唯一有成熟客户端工装的 loader(knot 客户端 + `into_world`/`react_smoke` title→world GUI 驱动先例 + port 39801 live 先例)。neoforge 客户端对等(在 neoforge 客户端上跑同批 T1 场景 + 客户端仪表)**明确延后**,非本 phase 目标。
- **⭐偏差声明(2)——首批 in-game UI 授权场景随 P2c 再议,理由=场景体跨线程阻塞铁律**:计划中「场景体内直接断言客户端 UI」的首批 in-game UI 场景**延至 P2c/JUnit5**。理由=**场景体跨线程阻塞铁律**:场景体在服务器 tick 上 inline 跑,绝不可阻塞等客户端异步 UI;因此 P2b 的客户端断言**全部经 `instrument_client.py` 仪表面交付**(编排器/裸 RPC 侧),而非 in-game 场景体。JUnit5 attach(可跨线程 await 的测试线程)是承接这批 UI 场景的正确载体,归 P2c。
- **✅ 六门验收(全前台有界,顺序跑,绝不同时两服务器/客户端)**:①`t1.py` → **VERDICT: GREEN exit=0**(9/9 `ad.*` 场景 integrated server 上全 PASS + 3 金丝雀按预期 + 2 reuse 支持场景,`descentYaw`/`selfShaftDigUp`/`gearScope` golden 由场景内断言保证——PASS 即字节命中,48.8s session/64s wall);②`instrument_client.py` 自起 → **GREEN exit=0**(7 检查 PASS + `canary.mustFail`/`canary.mustTimeout` 双落,cold boot 28s);③`instrument_client.py --rounds 3` → **REUSE VERDICT: GREEN exit=0**(三轮字节同一,round1 boot 1.6s / round2 reuse 4.4s / round3 reuse 4.0s,≈7× 更省);④`instrument.py --loader neoforge` + `--loader fabric` → **双 GREEN exit=0**(各 21 检查 + 2 金丝雀,task#89 后回归);⑤dogfood neoforge → **GREEN exit=0**(9 `ad.*`,六字节指标逐位对:`descentYaw` `871°/53`、`selfShaftDigUp` `20.252203415101263`、`gearScope` `0.94000053`/`5.9040003`/`ATTACK_DAMAGE=6.0`/`ATTACK_SPEED=1.5999999046325684`)+ dogfood fabric → **GREEN exit=0**(9 `ad.*`);⑥legacy 全量 `./scripts/run_gametests.sh`。
- **🟡 门⑤ neoforge dogfood 首跑 task#88 flake → 复跑绿(诚实记录)**:首跑 `ad.entityLeash` **TIMEOUT**(await 超 `within=120`)VERDICT RED——**task#88 签名**(冷启动 tick 债 catch-up burst 使 `within(120)` 墙钟脆弱;门① t1.py 同场景 25-tick 通过)。**按协议复跑一次定性**:复跑 `ad.entityLeash` **62 ticks**(120 预算内)VERDICT GREEN exit=0——**flake 非确定性回归**。fabric dogfood 首跑即 26-tick GREEN。task#88 仍开(harness 根修待做)。
- **🟡 门⑥ legacy 全量诚实入档(白名单外零新名,判定为达标)**:`registered=130 entered=130`(**零吞测试**,#85 P0 门武装),`VERDICT: RED` `2 required + 1 optional` 失败:`serveravatargearscopeprobearena`(rig broken: bare-hand swing 零伤 = #48 彩票/战斗 rig 家族)+ `descentyawarena`(未达底,pos 逐位相同)+ optional `vineoverwaterclimbarena`(文档化 live -711 bug)。**复跑一次 = 确定性**(两跑失败名/pos/reason 逐位相同,`descentyawarena` 两跑均落 `pos=(432.76521663827583,-5.0,618.4616959480727)`;非彩票漂移而是确定性 pre-existing 残留)。**归因**:P2b 五 commit **仅动** `AgentDriverCommon.java`(task#89 RpcServer 定序)+ `fabric/build.gradle`(testkitClient 配置)+ python/txt/md——**零引擎(walker/executor/combat/arena)源码改动**,故这两 required 失败**在 P2b 变更面之外**,是分支既有引擎残留;且其迁移壳 `ad.descentYaw`/`ad.gearScope` 在门①/⑤ 全 GREEN(golden 逐位一致)——**「Live/replay=真相,arena 只是回归卫士」**:字节钉扎的 testkit 场景是权威回归面,legacy arena 是被取代的脆弱 rig。**白名单外零新名 = 门⑥「诚实入档」达标**(reconciliation 是门⑥真正的 P0 完整性检查)。⚠️ 本轮**未撞** underwaterBase livelock(50s 正常失败,非挂死)。
- **判定:DONE_WITH_CONCERNS**——门①-⑤ 全 GREEN(门⑤ neoforge 经 task#88 flake 复跑绿);门⑥ 是「诚实入档」非 must-green,其真正完整性检查(reconciliation 零吞零新名)干净,2 required 失败经证为 P2b 变更面外的确定性引擎残留 + 迁移壳全绿。**BLOCKED 保留给 P2b 引入的回归或验收面损坏,二者皆不成立**。legacy 删除倒数**本轮不 +1**(仅全量 GREEN 才 +1),当前 = 0。
- **残余清单(deferred Minors 中的真残留风险 + 排 P2c 及以后)**:①**keys-readback verb 缺口**——`reset.behavior` 的 `keys` 子断言间接:`BotApiImpl.resetClientEntry` 无条件加 `keys` token,故该断言抓不到 `releaseKeys()` no-op 回归(真修=持键回读 verb,超范围,controller 可择期排;screen+chat 维度有真牙);②**`canary.mustTimeout` 合成**(抛哨兵,非真墙钟挂死检测,同 `instrument.py` 先例);③**re-enter 失败归 ENV 非 BLOCKED**(漂移门只抓「跑通但翻转」残留,已记);④**task#89 无 fault-injection boot 测试**——失败定序仅由检视验证(`api!=null`-partial-catalog-throw 边缘良性发散,但无注入测试演练失败排序);⑤**task#88**(`ad.entityLeash` harness tick-debt 根修:arming 前排空 tick 债 / 改 wall-clock within——本 phase 只落场景局部 `within(120)` stopgap)**仍开**;⑥**P2c**——JUnit5 attach + 首批 in-game UI 授权场景(偏差 2 承接)+ neoforge 客户端对等(偏差 1 承接);⑦task#86(`selfShaftDigUp` gap#53)/ task#87(`entityLeashRepathArena` y=-60 rig)/ #48 彩票家族全量身体共享隔离修 均不变;⑧legacy `@GameTest` 删除倒数 = 0(本轮 RED 不 +1)。
- **✅ 终审 + fix wave 收尾(本条目后两 commit)**:终审(全 phase diff 5c2ba7d..7675c87)= **Ready to merge,0 Critical/Important**;`664df94` fix wave——③的 re-enter 归类收紧(纯函数 `transition_failure_code`:**干净跑完≥1 轮后**的 reuse 过渡异常判 `BLOCKED(4)` 非 `ENV`,首轮前/`--fresh-process`/check-phase 仍 ENV)+ `--fresh-process --rounds 1` argparse 拒 + `harvest_footer` JSON-parse 去空格耦合 + README 措辞对齐 + 死代码×3;`d6200ae` re-review 收尾——空轮(过渡失败)不再制造幻影漂移表(`(not run)` 占位,漂移只在真跑过的轮之间判,纯过渡 BLOCKED 用专门报告头)。self-tests 23/23 + 13/13;re-review 判 **Confirmed clean**。

## 2026-07-17 ✅ P2a verb 注册管线 + #280 收口落地 — 配对注册 + 命名空间政策 + 封闭 schema 单源 + 契约四门(branch `feature/executor-permove-ascend`)

- **✅ P2a 六 commit 已落**:①`3677225` plan(P2a 竖切);②`14c6541`+`35c98c0` (T1) `ToolCatalog.registerVerb(schema, handler)` 原子配对入口(schema 供给 + route 注册一步),经 bootstrap `wireRouteSink(api::addRoute)` 转接(Hard Rule #1:只 (name,handler) 数据流过缝,不 import 核心)+ 命名空间政策 enforcement(`mc.*` 保留 / `mc.test.*` 授 testkit-runtime / `mc.test.yaml` 祖父 / 第三方 `<modid>.*`,违者注册时抛)+ **schema-less 派发洞收口**(`AgentDriverCommon` 校验接线 `if (s != null)` → 缺 schema 大声 `IllegalStateException`)+ `35c98c0` verb-hijack guard(拒劫持驱动层 baseline 名);③`39d99e5` (T2) **#280 根修**:`SettingsRegistry` 单源(从 `SettingsSnapshot` 键枚举派生——146 手列 + 82 反射补全 `public static volatile` BotConfig 原始类型字段 + `debugFly`,共 229 键),`mc.bot.setting` schema 封闭(`additionalProperties(false)`,229 props),apply 未知键**大声拒 all-or-nothing** + `inert[]` 漂移报告 + 快照字节同一;④`6de3d97`+`ae9982d` (T3) `mc.test.reset` hidden verb(SPI 首消费者,两 loader `ensureRpcUp` 注册)+ client 线程安全 reset(look-cancel + releaseKeys + 关屏 + 清 chat)+ `ad.settingRegistryClosed` 场景(两清单第 9 名)+ 断言 5 = SPI 配对回归网;⑤本条目 = Task 4 契约扩展 + 五门验收 + 文档。
- **✅ 四能力交付**:①**公共配对 verb 注册**(schema+route 原子,派发时缺 schema 大声拒 = #280 同形洞堵死);②**命名空间政策**(注册时 enforce,含祖父条款 + hijack guard);③**#280 收口**(封闭 schema 单源 + 大声拒键);④**`mc.test.reset`** 首个 `mc.test.*` verb(client-entry reset,专服大声 client-only)。
- **⚡ user 直令(P2a 期间,plan 外)**:`15104a3` Rhino 脚本层调用限制默认关——脚本=驱动层第一方能力面,安全由调用方保证(信任模型同 RPC socket:能触达端点即拥有进程);`AgentClassFilter` denylist 保留,`-Dagent.sandbox=on` 显式 opt-in 才启用;资源护栏(deadline/abort)不属调用限制不受影响。记忆 `feedback_rhino_scripts_unrestricted`;残余:skill 文档/javadoc 的 "sandboxed" 措辞随 P2a 终审 fix wave 清。
- **✅ #280 收口语义(根修一句)**:未知键从「静默吞」变「封闭 schema 在 `route()` 校验 + apply 双保险大声拒,all-or-nothing(任一未知键→整调用拒、什么都不 apply)」,单源 `SettingsRegistry` 的反射补全 pass 保证新 BotConfig 旗标自动进注册表(#280 病根=新旗标漏 schema)。
- **✅ instrument.py 契约四门(检查 18-21,自测数 17→21,金丝雀不动)**:⑱`catalog.settingSchemaClosed`——经 **MCP `tools/list`** HTTP(`agent-mcp.port`,`onServerStarting` 随 RPC 起)读回 `mc.bot.setting` inputSchema,断 `additionalProperties` 非 `true`(封闭)+ `properties` ≥ 200;⑲`route.settingUnknownKey`——专服打 `{definitelyNotAKnob:true}` 得 VALIDATOR `unexpected key`(**非** client-only);⑳`route.testResetClientOnly`——专服打 `mc.test.reset` 得 `client only` 大声;㉑`route.testResetSchemaPaired`——打 `{nope:true}` 得 `unexpected key` 先于 client-only(配对元证明 + schema-less 洞回归)。
- **⭐现场定序发现(检查 19)**:计划把「validator 先于 client-only」当统一契约读法——**现场证实即此序**:`AgentApi.route()` 先 `paramsValidator.validate` 后 `fn.apply`,`mc.bot.setting` 的 `requireBot()` client-only 在 handler 里才抛,故专服未知键先撞 SchemaValidator,client-only 门根本没到(两 loader 全量 GREEN 复现)。**tools/list 封闭对象渲染坑(检查 18)**:`additionalProperties(false)` 内部置 null + `optionalFieldOf` → 封闭对象在 tools/list **省略该键**,不渲染成 `false`;封闭约定 = 缺失或 false = 封闭、仅 `true` = 开放(同 `SchemaValidator`)。均写进契约附录。
- **⭐schema 读回路径**:裸 RPC `/rpc` 不暴露 schema 目录;`mc.script.eval` 也读不到(此 Rhino fork 剥 `Packages` 全局,JS 无法按名解析 `ToolCatalog`,与沙箱 denylist 无关)——唯一诚实结构化读回 = MCP `tools/list`(与 route 层 `SchemaValidator` 同一 typed Schema,单源)。
- **✅ 五门验收(全前台顺序跑)**:①instrument neoforge `VERDICT: GREEN`(21 检查 + 2 金丝雀全过);②instrument fabric `VERDICT: GREEN`(同);③dogfood neoforge `VERDICT: GREEN`(9 `ad.*` 场景全 PASS,六字节指标逐位对:`descentYaw` `sumAbsDyaw=871°`/`backSteps=53`、`selfShaftDigUp` `worstBackslide=20.252203415101263`、`gearScope` bare `0.94000053`/sword `5.9040003`/`ATTACK_DAMAGE=6.0`/`ATTACK_SPEED=1.5999999046325684`);④dogfood fabric `VERDICT: GREEN`(9 `ad.*`);⑤legacy 全量 `./scripts/run_gametests.sh` `registered=130 entered=130`(**零吞测试**,#85 门武装)`VERDICT: RED`(预期彩票)。
- **🟡 legacy 全量门 RED(诚实记录,零新名)**:3 required 失败 = `entityleashrepatharena`(task#87 whitelisted,master-inherited y=-60 rig)+ `serveravatargearscopeprobearena`(#48 彩票家族)+ `descentyawarena`(#48 彩票家族)+ optional `vineoverwaterclimbarena`(文档化 optional)。**分类正证据**:三员的隔离迁移壳 `ad.gearScope`/`ad.descentYaw`/`ad.entityLeash` 在本轮**两 loader dogfood 全 GREEN**(gearScope/descentYaw 字节指标逐位一致),即真隔离态绿——legacy 全量红是 #48 邻居干扰彩票,非回归。**白名单外零新名 = 达标**。删除倒数**本轮不 +1**(仅全量 GREEN 才 +1)。首跑曾中途 livelock 截断(`entered=26` 后 `entityLeashRepathArena` 触 `ChunkMap.processUnloads` 单 tick 死循环,540s wall 截断),per 协议 sweep + 重跑一次自愈,复跑 34.04s 全量干净。
- **残余清单(排 P2b 及以后)**:①**P2b**——T1 拓扑(真 PlayerList 玩家:#41 全背包/#45 攻击冷却/#55 伤害源)+ 客户端仪表 E2E(真开客户端打 `mc.bot.setting` 未知键 over live client、`mc.test.reset` 完整性 3 跑);②**P2c**——JUnit5 attach;③task#86(`selfShaftDigUp` gap#53 stride-floor-guard 真缺陷)不变待修;④task#87(`entityLeashRepathArena` y=-60 rig)不变待查;⑤task#88(`ad.entityLeash` harness tick-debt 根修)不变;⑥彩票家族(#48)全量身体共享隔离修待 test-framework rework;⑦legacy `@GameTest` 删除倒数 = 0(需连续双门绿 + 期望门前置,gate 5 每轮如实计,本轮 RED 不 +1)。

## 2026-07-17 ✅ P1.6 fabric 对齐落地 — sim 核心搬 common + body-factory seam,fabric 首次获得同级 dogfood 门(branch `feature/executor-permove-ascend`)

- **✅ P1.6 六 commit 已落**:①`c1b0a89` plan(fabric 对齐竖切);②`5ab493b` (T1) sim 核心(`ServerPlayerAvatar`/`ServerAgentDriver`/`ServerAgentManager`)搬 common,置于 loader 注入的 `ServerAgentBodies` body-factory seam 之后(仓库无 `@ExpectPlatform` 先例→零新依赖;neoforge 留**同 FQN 薄 shim**,协变 `FakePlayer` 返回,工厂仍 `FakePlayerFactory`=字节级不变,legacy `AgentGameTestServer` 约 3000 行**零源码改动**编译通过);③`8623f5e` (T2) `ad.*` 场景 + `SimProbes` 搬 common,`SceneProvider` service 文件换位(**删 neoforge 副本**——dev classpath 两份同内容=provider 双加载=撞名门 RED,该门顺便自证);④`1ffac5b` (T3) fabric 接线(`runDogfoodServer` 配置 + autorun 钩子 + `FabricAgentBodies` 镜像反编译 `FakePlayerFactory` 含 `ServerWorldEvents.UNLOAD` 驱逐 + `expected-scenes-fabric.txt` 清单)——**fabric 有史以来首次 dogfood 8/8 `ad.*` PASS**;⑤`13f3936` (T4) 双 loader ×3 确定性矩阵(fabric ×3 + neoforge ×3 顺序跑,每跑清 world);⑥`339d2d5` (T4 controller stopgap) `ad.entityLeash` await 上界 `within(60)→(120)`。本条目 = Task 5 验收 + 文档(docs 两文件另 commit,契约 v0 无语义变更不动)。
- **✅ 架构一句话**:sim 三类的 loader 差异用「loader 注入 body 工厂」seam 化解——`ServerAgentBodies.install(BodyFactory)`(mod init 一次性)提供 `shared(level)`/`unique(level,profile)` 身体;neoforge 注入 `FakePlayerFactory`(行为字节不变),fabric 注入 common 自造的 vanilla-only `AgentFakePlayer`(以 mcp javadc 反编译 NeoForge `FakePlayer` 对照实现,含 net-handler stub)。场景与 `SimProbes` 也搬 common,两 loader 经**同一份** common `SceneProvider` service 文件注册**同一批**场景。
- **✅ fabric 首次成为一等 dogfood 目标 + 双 loader 字节同一性(P1.6 最强对齐结论)**:Task 3 fabric 首跑 8/8 `ad.*` GREEN;Task 4 用六跑确定性矩阵复对——**每个 `ad.*` 场景指标值在两 loader、六跑之间逐位字节一致**(fabric == neoforge:`descentYaw` `sumAbsDyaw=871°`/`backSteps=53`、`selfShaftDigUp` `worstBackslide=20.252203415101263`、`gearScope` bare `0.94000053`/sword `5.9040003`/`ATTACK_DAMAGE=6.0`/`ATTACK_SPEED=1.5999999046325684`、`buriedOre` `oreMined=true`、`entityLeash` `standDist=4.187857529833143`)。P1a 的字节同一性先例(此前仅覆盖内建场景)现推广到整个 `ad.*` driver/walker 家族——fabric 服务端驱动层(此前从未被测)第一次置于测试之下,且与 neoforge 物理确定性完全一致。
- **⭐entityLeash tick-debt 发现 + stopgap + task#88**:唯一非字节同的量 = `ad.entityLeash` 的**总 scene-tick 计数**(其两次 `ctx.await(...)` 实体索引等待的 tick 和,每 scene tick poll 一次):六跑 fabric `{64,28,30}`/neoforge `{68,30,57}`。**根因非 loader 差异**(controller 修正了 implementer 的「await 循环超 tick」错模型):`onServerTick` 是套件唯一驱动,刚启动的服务器背负 tick 债、以未节流的 ~3ms 追赶 tick 猛跑,该 burst 区间里 wall-clock-bound 的异步实体提升要花 2–2.3× 的 tick 达到同一延迟;Task-3 那次 neoforge 61-tick TIMEOUT = 负载污染叠在 burst 上,六跑干净复跑 0/6 再现。**处置**:`within 60→120` 场景局部 stopgap 已落;harness 根修(arming 前排空 tick 债 / 改 wall-clock within)= **task#88** 立案。
- **✅ 五门验收全绿路线(本 Task,全部前台有界,顺序跑、绝不同时两服务器)**:①fabric dogfood 正门 `t0.py --loader fabric --run-task :fabric:runDogfoodServer --expect-file expected-scenes-fabric.txt` → **VERDICT: GREEN exit=0**(8 名 `ad.*` 全命中 `registered[]`,`ad.entityLeash` 31 ticks,五字节指标与 golden 逐位一致);②fabric 纯 T0 `t0.py --loader fabric --wall 540` → **GREEN exit=0**(2 walking-skeleton + 3 金丝雀按预期);③fabric 仪表契约 `instrument.py --loader fabric` → **GREEN**(17/17 真检查 + 2 金丝雀,P1b 双 loader 回归确认);④neoforge dogfood 正门(neoforge 路径/清单)→ **GREEN exit=0**,**六字节指标逐字复对全 exact**,`ad.entityLeash` **61 ticks**(> 旧 `within(60)` 会假红——**实证 widened `within(120)` 的必要性**,验证 `339d2d5` stopgap)。
- **🟡 ④ 之外的 ⑤ legacy 全量门本轮 RED(诚实入档,零新名字)**:`./scripts/run_gametests.sh` = `registered=130 entered=130`(**零吞测试**,#85 P0 门保持武装)、**2 个 required 失败** `{serveravatargearscopeprobearena, descentyawarena}` +optional `{vineoverwaterclimbarena}`(既知 flake)。**真单名 solo 逐一定性**(每名单独 `AGENT_GT_ONLY=<name>`,不编组):①`serverAvatarGearScopeProbeArena` solo → **GREEN**(#48 彩票家族);②`descentYawArena` solo → **GREEN**,`sumAbsDyaw=871° backSteps=53` 与 golden **字节一致**(#48 彩票家族)。**`entityLeashRepathArena`(task#87 白名单)本轮未出现在失败集**;**零真正新名**(两个失败名全部落在既有 #48 家族内)。⚠️首次尝试撞已知 `underwaterBaseArena` `ChunkMap.processUnloads` 单-tick livelock(JVM 351% CPU 空转、日志冻结 2min>150s,非 #85):按协议**显式 PID kill + 删 run-gametest world + 重跑一次**,`run_gametests.sh` 自愈,重跑即 `entered=130` 干净入档。
- **📋 legacy 删除倒数**:本轮 legacy 全量 RED(彩票抽样,非回归)→**倒数不 +1**,当前值 = **0**(仍需「连续 3 轮双门全绿 且 编排器已落地外部期望校验」;外部期望门前置由 `--expect-file` 满足,本轮两 loader 正门均已武装通过,但「连续 3 轮双门全绿」计数因 legacy 全量红继续保持 0)。`ad.selfShaftDigUp` 仍是 required 签名门(task#86 未变),`ad.entityLeash` legacy twin 归 task#87(未变)。
- **残余清单(排 P2 及以后)**:①**P2**(spec §7);②task#86(`selfShaftDigUp` gap#53 stride-floor-guard 真缺陷)待修;③task#87(`entityLeashRepathArena` legacy twin master-inherited 的 y=-60 rig 停滞)待查;④**task#88**(`ad.entityLeash` harness tick-debt 根修:arming 前排空 tick 债 / wall-clock within——本 phase 只落场景局部 `within(120)` stopgap);⑤testmod source-set 拆分仍延后;⑥彩票家族(#48)全量身体共享隔离修——待专门 test-framework rework;⑦`/agentserver` 生产命令(`ServerAgentCommand`)仍 neoforge-only=**有意的 fabric 非目标**(P1.6 只对齐测试面;fabric 侧 sim 层目前仅 dogfood 场景可达,生产接线待有需求再排)。

## 2026-07-17 ✅ P1.5b dogfood wave 2b 落地 — driver 家族三员迁移(gearScope/buriedOre/entityLeash)+ expect-file 正门 + 签名门(branch `feature/executor-permove-ascend`)

- **✅ P1.5b 八 commit 已落**:①`1c60b06` plan(P1.5b 竖切);②`44a9b5c` (T1) `--expect-file` 签入清单正门(与 `--expect-scene` 并集去重、parse docstring 限定符);③`1210852`+`d808348` (T2) task#86 传感器→required 黄金失败**签名门**(`d808348` 评审补 `reached=true` 极性,堵住 stuck-regression 掩盖洞)+ origin-slot 下限守卫 1024;④`76db598` (T3) `ad.gearScope` 迁移(driver 模式确立,probe helper 提升);⑤`0b2e661`+`aa591f9` (T4) `ad.buriedOre` 迁移(MineProcess/manager loop 模式)+ `aa591f9` 评审补 `ctx.cleanup` 保留 legacy rig air-box 清场;⑥`916579f` (T5) `ad.entityLeash` 迁移(两阶段,await 降级 + register-bracket);⑦本条目 = Task 6 验收 + 文档(docs 三文件另 commit)。
- **✅ 8/8 迁移进度**:`expected-scenes-neoforge.txt` 现列 8 名 `ad.*`——wave 1(`ascendMovementNoop`/`ascendDeadZoneWatchdog`/`diagonalAscentSpeed`)+ wave 2a(`selfShaftDigUp`[required 签名门,task#86]/`descentYaw`[pinned slot 4000])+ wave 2b(`gearScope`/`buriedOre`/`entityLeash`)。dogfood = **13 场景记录**(5 内建[含 3 金丝雀,`canaryMustSwallow` 按设计零记录]+ 8 `ad.*`)。
- **✅ 双拓扑纯验收 GREEN(Step 1 dogfood + 纯 T0 双跑,正门首次用 `--expect-file`)**:`t0.py --run-task :neoforge:runDogfoodServer --expect-file scripts/testkit/expected-scenes-neoforge.txt` → `VERDICT: GREEN exit=0`,8 名 `ad.*` 全部命中 `registered[]`,全 required PASS——`ad.selfShaftDigUp` 本轮**经签名门判 PASS**(task#86:`reached=true && worstBackslide>15.0` 匹配黄金失败签名→场景记 PASS;该场景是 **required 签名门**,其 PASS 断言的正是 task#86 缺陷仍字节级复现[黄金失败]——签名门把「预期的黄金失败」翻成 PASS,缺陷修复或签名漂移时都会转红报警);`ad.entityLeash` PASS(y=200 隔离 origin,56 ticks)。同轮纯 T0(`t0.py --loader neoforge --wall 540`,零 agent-driver 依赖)`VERDICT: GREEN exit=0`(2 walking-skeleton + 2 记录金丝雀 + swallow 门)。
- **🟡 Step 1 legacy 全量门本轮 RED(诚实记录,零新名字)**:`./scripts/run_gametests.sh` 全量 = `registered=130 entered=130`(**零吞测试**,#85 P0 门保持武装)、**4 个 required 失败** `{serveravatargearscopeprobearena, servermineburiedorearena, descentyawarena, entityleashrepatharena}` +optional `{vineoverwaterclimbarena}`(既知 flake)。**真单名 solo 逐一定性**(每名单独 `AGENT_GT_ONLY=<name>`,不编组、不重复 fishing):①`serverAvatarGearScopeProbeArena` solo → **GREEN**(#48 彩票家族);②`serverMineBuriedOreArena` solo → **GREEN**(#48 彩票家族);③`descentYawArena` solo → **GREEN**,`sumAbsDyaw=871° backSteps=53` 与 pinned-slot 黄金值**字节一致**(#48 彩票家族);④`entityLeashRepathArena` solo → **RED**(`phase2: bot did not ARRIVE ... after the anchor moved`,y=-60 rig 停滞签名)= **master-inherited,task#87 白名单已裁定**(干净 master worktree solo RED 2/2 + Unit-1 台账,判为 rig 环境问题非本 phase 回归)。**零真正新名**:四个失败名全部落在既有 #48 家族并集 + task#87 白名单内,符合审计规则(回归只加失败不换人),非回归。
- **📋 A/B 留痕(wave 2b 三员)**:T3 `ad.gearScope` solo GREEN,probe 值两壳(legacy twin + `ad.*` 新壳)**字节一致**;T4 `ad.buriedOre` solo GREEN;T5 `ad.entityLeash` legacy twin 确定性 solo RED ×2 = **master-inherited**(clean-master worktree RED 2/2)→ 裁定为 rig 环境问题,**task#87 立案**,新壳场景在 y=200 隔离 origin `required=true` GREEN,采用**批准的 await 降级**(直译 `level.tick` 会在持久世界上 livelock `ChunkMap.processUnloads`,故场景走 `ctx.await` + register-bracket:platform `onServerTick` 的 `tickAll` 在 await 期间驱动已注册 driver)。**本 phase 零新倒置彩票样本**(gearscope/buriedore solo GREEN,不像 wave 2a 的 selfShaftDigUp 那样隔离才现形)。
- **📋 legacy 删除倒数**:本轮 legacy 全量 RED(彩票抽样,非回归)→**倒数不 +1**,当前值 = **0**(需「连续 3 轮双门全绿 且 编排器已落地外部期望校验」——外部期望门这一前置已由 `--expect-file` 满足并在本轮正门武装通过,但「连续 3 轮双门全绿」的计数因本轮 legacy 全量红继续保持 0)。`ad.selfShaftDigUp` 已是 **required 签名门**(非 optional;签名门 PASS=缺陷仍字节级复现是设计常态,计入全绿);删除资格仍要等 task#86 修复——届时签名门大声 RED,把签名断言换成正常无回落期望(见残余清单②)后重新计数。
- **残余清单(排 P2 及以后)**:①**fabric loader 对齐**——P1.5b 全部验收只跑 neoforge,fabric 侧 dogfood/T0 双门尚未验证(P1.5a 起挂账);②task#86(`selfShaftDigUp` gap#53 stride-floor-guard 真缺陷)待修——修复后本签名门会大声 RED,届时把签名断言换成正常的无回落期望(见场景 javadoc 的 gate 说明);③task#87(`entityLeashRepathArena` legacy twin master-inherited 的 y=-60 rig 停滞)待查——是 rig 环境问题非产品回归,新壳 `ad.entityLeash` 已在隔离 origin 绿;④彩票家族(#48)全量身体共享隔离修——待专门 test-framework rework;⑤`ResultsJsonl` 异步 writer 注记适用范围(SceneContext 写路径)待评估;⑥legacy `@GameTest` 三胞胎(+ P1.5a/2b 五个新双胞胎)删除倒数 = 0,连续绿计数继续累积。

## 2026-07-16 ✅ P1.5a dogfood wave 2a 落地 — 彩票家族竖切(selfShaftDigUp/descentYaw)+ 期望门武装(branch `feature/executor-permove-ascend`)

- **✅ P1.5a 五 commit 已落**:①`f09c4a6` --expect-scene 外部期望门 + parse 坏行降级不裸抛 + `--results` 路径锚定;②`4ac8b3e` originSlot 坐标钉扎 + per-scene chunkRadius + harness 双臂守卫(重复注册/slot 撞车早失败);③`c5b3187` `ad.selfShaftDigUp` 迁移(lottery walker family),legacy 保留供 A/B;④`9a640f0` `Scene.withRequired` + `ad.selfShaftDigUp` 标 optional——task#86 的忠实传感器(隔离才现形的真缺陷,不是移植 delta);⑤`674179e` `ad.descentYaw` 迁移,钉 origin slot 4000 + chunkRadius 2(确定性敏感)。本条目 = Task 5 验收 + 文档(文档三文件另 commit)。
- **⭐⭐selfShaftDigUp 反转彩票揭盖 → 立案 task#86**:legacy 全量套件历史记录该 arena 从未现身失败集(长期表现 GREEN),但在真隔离身体(`ServerPlayerAvatar.createUnique`)下**确定性 RED**:`worstBackslide=20.252203415101263`,字节级一致复现于 3 条独立隔离跑(legacy 自身两次 solo `AGENT_GT_ONLY` + 新壳 `ad.selfShaftDigUp` 一次)。判定:legacy 全量的历史 GREEN 是 gap#48 邻居干扰型假绿(并发 arena 的身体互相推挤掩盖了这个真实的 gap#53 stride-floor-guard 缺陷)——新壳移植是忠实的,暴露的是产品真 bug 不是移植 bug。处置:`.withRequired(false)`,场景跑 fail(optional) 常态记录,直到 task#86 修复再翻回 required。〔已被 P1.5b T2(1210852) 取代:升级为 required 签名门,见上方 P1.5b 条目〕
- **✅ descentYaw POSITIVE 证据(isolated-body + pinned-slot 假说首次确认)**:legacy 单独 solo(`AGENT_GT_ONLY=descentYawArena` 单名,无任何同伴)GREEN,+ 新壳(`ad.descentYaw`,pinned slot 4000 + radius 2)独立 3 跑,`sumAbsDyaw=871°`/`backSteps=53` 逐位一致——隔离身体 + 钉住坐标能稳定复现同一条物理轨迹,不再受套件增长/邻居干扰扰动。Golden values 已回记两个 twin 的 javadoc(见下方独立 commit)。
- **🟡 Step 1 legacy 全量门本轮 RED(诚实记录,如实入档不 fishing)**:`scripts/run_gametests.sh` 全量结果 = required 失败 `{serveravatargearscopeprobearena, descentyawarena}` + optional `{vineoverwaterclimbarena}`(`registered=130 entered=130`,零吞测试)。这是又一次已知 #48 彩票家族抽样(名字全部落在 07-16 P0 条目记录的历史家族并集内),**零新名字**。Solo 定性(各名字只测一次,不重复 fishing):①三名一起(`AGENT_GT_ONLY=serverAvatarGearScopeProbeArena,descentYawArena,vineOverWaterClimbArena`,descentYaw 走自己独立的 `soloDescentYaw` batch)→ gearscope GREEN(既有 solo-绿基线再证)、descentyaw **RED**、vineoverwater(optional)FAIL(既知 flake);②descentyaw 追加一轮**真单跑**(`AGENT_GT_ONLY=descentYawArena`,零同伴)→ **GREEN 19s**。①②对照证实:即使 descentYaw 独占 `soloDescentYaw` batch,"与其他名字一起被 `AGENT_GT_ONLY` 选中"本身仍不是完全隔离——这正是 07-16 P0 条目已记录的模式("三员编组过滤跑 GREEN 除 descentyawarena 单飘 1 次" vs "真单跑 GREEN")的再次复现,不是新回归。**判定:三个失败名全部是已文档化 #48 家族的已知成员,非回归**;但本轮 legacy 全量仍计 RED——不满足"连续 3 轮双门全绿"里的这一轮,legacy 三员删除倒数**本轮不 +1**。
- **✅ 双拓扑纯验收 GREEN(Step 1 dogfood + T0 双跑)**:`t0.py --run-task :neoforge:runDogfoodServer --expect-scene ad.ascendMovementNoop,ad.ascendDeadZoneWatchdog,ad.diagonalAscentSpeed,ad.selfShaftDigUp,ad.descentYaw` → `VERDICT: GREEN exit=0`,`registered[]`=10(5 内建[含 `canaryMustSwallow` 按设计零记录]+ 5 `ad.*`)、实际场景记录=9(`canaryMustFail`/`canaryMustTimeout` 两枚记录金丝雀命中预期 outcome、`canaryMustSwallow` 正确零记录、4 个 `ad.*` required PASS、`ad.selfShaftDigUp` `fail(optional)` 如实记录,`worstBackslide=20.252203415101263` 与上方隔离测量字节一致)、`ad.descentYaw` 日志行 `sumAbsDyaw=871° ... backSteps=53` 与上方 golden values 一致。--expect-scene 门本轮首次在正式验收命令里武装并通过(5 个名字全部命中 `registered[]`)。同轮纯 T0(`t0.py`,零 agent-driver 依赖)`VERDICT: GREEN exit=0`(4 场景 = 2 walking-skeleton + 2 记录金丝雀)。
- **✅ 契约文档 v0 附录(语义只收紧,版本仍 v0)**:`docs/testkit/orchestration-contract-v0.md` 新增三段——①**--expect-scene 外部期望门**(编排器侧断言,防 `ServiceLoader` 发现链断裂时套件自洽假绿,legacy 删除前置条件);②**originSlot 坐标钉扎**(自动分配随注册表增长整体平移 vs `withOriginSlot` 显式 pin 与注册顺序解耦,发布后不得变更);③**chunkRadius 声明武器**(默认 `r=1` 覆盖 origin 相对 `[-16,+31]`,足迹超窗口的场景须显式 `.withChunkRadius(r)`,声明式非自动推断)。`mc-testkit/README.md` dogfood 命令补全 5 名 `--expect-scene` + 迁移规则新增一句:"同步 body 必须有界循环"(scene body 内联跑在 server tick 上、不是独立测试线程,无界循环挂的是整个专用服务器,不只是该场景)。
- **✅(独立 commit,评审跟进)descentYaw golden values 871°/53 双胞胎回记**:`AgentDriverScenes.java` 的 `ad.descentYaw` javadoc + legacy `AgentGameTestTerrain#descentYawArena` javadoc 都补了 2026-07-16 迁移期实测的 `sumAbsDyaw=871°`/`backSteps=53`(与历史 `993°`/`67` **并列而非替换**——两个 twin 是不同隔离身体上各自的黄金参照,不互相覆盖,不该被混为一谈),供未来漂移调查从正确参照系起步。仅 javadoc,零可执行代码/断言字符串改动;`:neoforge:compileJava` 验证编译干净。
- **📋 双门状态 + legacy 删除倒数**:`ad.ascendMovementNoop`/`ad.ascendDeadZoneWatchdog`/`ad.diagonalAscentSpeed` 三员(P1c wave 1)按既有记录持续双门绿;`ad.selfShaftDigUp`/`ad.descentYaw` 是本轮(wave 2a)新加入双门 A/B 的两员,尚未累积连续绿计数——`ad.selfShaftDigUp` 因 task#86 真缺陷长期保持 optional(fail(optional) 是预期常态,不追求转绿,删除资格要等 task#86 修复 + 翻回 required 之后重新计数)〔已被 P1.5b T2(1210852) 取代:升级为 required 签名门〕;`ad.descentYaw` 本轮 legacy 全量红(彩票抽样,非回归),连续绿计数本轮清零重开。legacy `@GameTest` 三胞胎(+ P1.5a 两个新双胞胎)的删除倒数**本轮不 +1**。
- **残留清单(排 P1.5b 及以后)**:① driver 家族三员(`entityLeashRepath`/`agentRpcSmoke`/`buriedore`,均已是文档化 #48 家族成员)尚未排入 dogfood 队列,是下一轮竖切候选;② fabric loader 对齐——P1.5a 全部验收只跑了 neoforge,fabric 侧 dogfood/T0 双门尚未验证;③ `ResultsJsonl` 异步 writer 注记(P0 探针事故教训的适用范围)——结果写盘目前仍限场景边界同步写,`SceneContext` 写路径是否需要扩展到异步待评估;④ task#86(`selfShaftDigUp` gap#53 stride-floor-guard 真缺陷)本身待修,修复后把 `.withRequired(false)` 翻回 `true`。〔已被 P1.5b T2(1210852) 取代:该场景已是 required 签名门;task#86 修复后的动作见 P1.5b 条目残余清单②〕

## 2026-07-16 ✅ P1c dogfood wave 1 落地 — SceneProvider SPI + 双门 A/B(branch `feature/executor-permove-ascend`)

- **✅ P1c 五任务已落**:① Task 1 SPI+ctx+forceload(`768a67b`: `SceneProvider` 接口、`SceneContext` level/origin/cleanup、PREP 等满 3×3 forceload);② Task 2 `done.scenes` 对账门(`4c87fde`: judge() 新增 TRUNCATED 检查、t0 `--run-task`/`--results` 通用化);③ Task 3 dogfood 接线(`367ac53`: `:testkit-common` 依赖、`runDogfoodServer` run 配置、autorun 钩子);④ Task 4 三员迁移(`b86468d`: 历史被吞候选 `ascendMovementNoopArena`/`ascendDeadZoneWatchdogArena`/`diagonalAscentSpeedArena` 移植为 `ad.*` 场景,legacy `@GameTest` 双胞胎保留,断言值逐条 verbatim 保留);⑤ Task 5 双门并行验收+文档(本条目)。
- **✅ A/B 双证据**:legacy 全量门(`AGENT_GT_ONLY=` 三员显式基线,Task 4 同一 build)GREEN 19s;dogfood 新壳(`t0.py --run-task :neoforge:runDogfoodServer`)同一 build GREEN,3 个 `ad.*` 场景与 5 个内建场景(含 3 金丝雀)全过——两条门在同一份迁移代码上独立裁决一致,互为回归卫士。
- **🟡 legacy 全量门本轮 RED(诚实记录,非回归)**:Step 1 首次全量(`./scripts/run_gametests.sh`)`registered=130 entered=130`(零吞测试)但 3 个 required 失败——`serveravatargearscopeprobearena`/`descentyawarena`/`agentrpcsmoke`(+optional `vineoverwaterclimb`)。这正是 P0(task#85)记录在案的 #48 彩票家族(见本文件 07-16 P0 条目:三次全量三种不同失败集,全员 solo 绿)。**测量而非断言分类**:①三员编组过滤跑(`AGENT_GT_ONLY=` 三名一起)GREEN 除 `descentyawarena` 单飘 1 次;②`descentyawarena` 真单跑(`AGENT_GT_ONLY=descentYawArena` 单名)GREEN 1.779s;③`serverAvatarGearScopeProbeArena`/`agentRpcSmoke` 各自真单跑均 GREEN——三员逐一 solo 绿,叠加 Task 4 同一份代码今天早些时候全量 GREEN 130/130 的既有记录,判定为既知彩票家族的又一次抽样,非本轮 P1c 改动引入的回归(P1c 五个 commit 未触碰这三个 legacy arena 或探针代码)。**这正是彩票家族排入 dogfood 队列 P1.5 的论据**——只要还挂在共享 body 的全量门上,这类漂移就会继续消耗验收轮次。
- **✅ 双拓扑纯 T0 验收**:`t0.py --run-task :neoforge:runDogfoodServer` GREEN(exit=0,8 场景=5 内建+3 ad.*)+ 纯 testkit T0(`t0.py`,零 agent-driver 依赖)GREEN(exit=0,5 内建场景+2 金丝雀)——provider 有/无两种拓扑都健康。
- **✅ 契约文档 v0 附录(语义只收紧,版本仍 v0)**:`docs/testkit/orchestration-contract-v0.md` 新增「SceneProvider(v0 附录)」(ServiceLoader 发现顺序、`rejectDuplicateNames()` 撞名门、金丝雀仅内建承担)+「done.scenes 对账」(TRUNCATED 判据、与 SWALLOWED 分工、缺字段前向兼容);RED 退出码行补 TRUNCATED 提及。`mc-testkit/README.md` 补 dogfood 入口小节(run 命令+SPI 三行示例+services 文件路径示例)。`.gitignore` 补 `run-contract/`(P1b 遗留的 untracked-unignored 缺口,顺手清)。
- **⭐legacy 三员删除条件(显式记录)**:legacy 三员删除条件 = 连续 3 轮双门全绿 **且** t0 编排器先落地外部期望校验（--expect-scene 或 checked-in 期望清单，防 ServiceLoader 断链时 ad.* 从注册与执行同时消失=自洽假绿，#85 病上移到套件组装层——终审 Important，前置于删除）方可删除 legacy `@GameTest` 三胞胎与其 `gtOnlySkips` 埋点。
- **残留清单**:① `ResultsJsonl` 异步化(结果写盘目前仍限场景边界同步写,P0 探针事故教训适用范围待评估是否需要扩展到 SceneContext 写路径);② 彩票家族(#48,本轮再证:gearscope/descentyaw/agentrpcsmoke/vineoverwater)排 P1.5,目标是把这些 arena 也迁到隔离 origin 的 `ad.*`/testkit 场景,脱离共享 body 全量门;③ legacy 三员删除计数器归零重开(见上条);④ P1.5 pre-flights（终审规模化提示）：确定性敏感场景（descentYaw）需按名固定 origin（index 分配会因套件增长重排坐标）；大 arena 需 per-scene forceload 半径；同步 body 必须有界循环写进迁移规则；verdict.parse() 对中断行应判 TRUNCATED 而非裸 traceback。

## 2026-07-16 ✅ P1b 仪表契约子集落地 — 五任务全过(双 loader 验收+门自证+文档)

- **✅ P1b 竖切五任务已落 feature/executor-permove-ascend**: ① Task 1 verdict 抽取(a53d8dc: 从 t0.py 抽共享 verdict 模块,行为冻结,self-test 11/11);② Task 2 骨架+run 配置+金丝雀(a540771: contractServer run 配置+裸 RPC ws client+金丝雀对+共享 verdict);③ Task 3 batch A(ee95891: 路由分派/schema 违规/client-only 大声失败/脚本 parity,7 项真检查);④ Task 4 batch B(11088a5: world 操作/观测保真含 #42 耐久/事件/等待,10 项真检查,套件共 17 项+2 金丝雀);⑤ Task 5 双 loader 验收+门自证+文档(本条目)。
- **✅ fabric 侧就绪探针竞态发现+修复(a436c61,非批 A/B,单列 commit)**: Task 5 Step 1 首次对 fabric 跑满 17 项真检查时暴露——`launch()` 就绪门原探针 `mc.system.version` 不需要 server attach 就能回应,fabric 快速 flat-world 首启(RPC 监听到 `Done` 仅约 1s)下探针提前判定就绪,8/17 项 touch `api.level()` 的检查(world/obs/events 族)全部 `FAIL — AgentApi not attached to a server`(`VERDICT: RED`,exit=1);根因非产品缺陷,是仪表套件自身的竞态。修复把就绪探针换成 `mc.observe.player`(只读无副作用,但函数体第一行即 `api.level()` 显式 attach 门)。修复后 fabric 复跑 17/17 PASS,`VERDICT: GREEN`,exit=0。`agent-rpc.port` 未与 neoforge 撞车(Task 2 Step 2 的 `configureEach` 端口覆盖风险未兑现)。
- **✅ 门自证三跑(临时改错,均 `git checkout --` 还原,零 commit 残留)**: ①改 `check_version_shape` 断言错值 → neoforge → `VERDICT: RED` exit=1;②改 `canary_must_fail` 为 `return None` → neoforge → `VERDICT: DEAD` exit=2;③两次均还原后重跑 → `VERDICT: GREEN` 17/17 PASS exit=0。等价 P1a 的门自证,验证金丝雀条款(spec §5)活体有效。
- **✅ 双 loader 确定性重跑**: `neoforge && fabric` 两轮均 `VERDICT: GREEN`,combined exit=0;17 项检查+2 金丝雀两个 loader 上行为一致(仅 wallMs 计时抖动)。
- **✅ 契约文档 v0**: `docs/testkit/instrument-contract-v0.md`——17+2 检查逐条列名+断言+钉住哪条病历(#42 工具耐久、#280 静默吞病族)、永久断言台账、已知缺口(P2:#41 全背包/#45 攻击冷却/#55 伤害源需真玩家、avatar FakePlayer 不入 PlayerList、`mc.bot.setting` 未知键静默吞)。
- **下一步**: P1c dogfood 迁移(agent-driver arena 搬家),顺延清单见 `docs/testkit/instrument-contract-v0.md` 已知缺口节。

## 2026-07-16 ✅ P1a 行走骨架落地 — 五任务全过(文档收尾)

- **✅ P1a 竖切五任务已落 feature/executor-permove-ascend**: ① Task 1 gradle 骨架(affc448: testkit-common/fabric/neoforge 三模块+run 配置);② Task 2 scene 模型+注册(034709b: Scenes.all()五场景含三金丝雀);③ Task 3 T0 harness(936f4f2: ResultsJsonl+TestkitCommon 接线);④ Task 4 编排器+冻结契约(06861ea + a340cfd: scripts/testkit/t0.py+docs/testkit/orchestration-contract-v0.md);⑤ Task 5 dual-loader 证(2026-07-16 零 commit:fabric T0 GREEN 首跑、neoforge GREEN、双 loader 重跑全绿、exit=0、场景输出字节同(timings 除外))。
- **✅ 双 loader 实证**: fabric T0 首跑 GREEN;dual-loader 重跑 neoforge GREEN + fabric GREEN,combined exit=0;场景结果字节同(PASS/FAIL/TIMEOUT 行为同,timings 漂移)。
- **✅ 金丝雀哨兵语义**: MUST_FAIL/MUST_TIMEOUT 必须被捕获为 FAIL/TIMEOUT,MUST_SWALLOW 无记录;任何金丝雀误判 → exit 2 DEAD,整轮失效。契约 v0 冻结位置:`docs/testkit/orchestration-contract-v0.md`。
- **下一步**:P1b 仪表契约子集(agent_driver 接线)、P1c dogfood 迁移(agent-driver arena 搬家)——阶段划分见 spec §7 阶段计划。

## 2026-07-16 task#85 suite-integrity P0 落地 + 全量基线重建(评审修正版,见下方⭐⭐)

- ✅ **P0 四件套已合 master**:①in-game JSONL manifest(76362e5 + **926396d/a5c9e06 探针异步化两修,见下方⭐⭐**,registered/enter 双record,`gtOnlySkips` 埋点处发 enter 探针);②`scripts/gt_reconcile.py` 对账门(a812103 + 574639a,registered-vs-entered SWALLOWED/DRIFTED 双向门 + BUILD/required verdict,9 条内嵌 self-test);③`--audit-source` 源码审计模式(d56c54f + 8bcdfe9,每个 `@GameTest` 必须在自己方法体内挂 guard,防漏埋);④`scripts/run_gametests.sh` 统一入口(288b396,PID sweep→世界清→`timeout --kill-after=30`硬顶→对账验收,永不信 mod reporter 的 `TOTAL:` 行)。
- ⭐⭐**核心新知:P0 探针自伤事故(已修)**。原始 Step1 全量把 `descentyawarena`(一个字节级确定性物理断言,阈值贴近临界`worstBack=-0.05`)三连红误判为"task#48 跨 arena 彩票"——审阅推翻:`descentYawArena` 即使在**完全隔离、零并发邻居**的 solo 跑(`AGENT_GT_ONLY=descentYawArena,...`,自成一个 `soloDescentYaw` batch,前置 129 个测试全部被 `gtOnlySkips()` 首行零世界改动地跳过)里依然 FAIL,结构性排除了"共享身体并发争用"这个机制。controller A/B 定罪:真根因是**我们自己的 P0 探针**——`GameTestManifest.enter()` 在 server 线程上做**同步文件 IO**(每个测试体第一行都调),这个 wall-clock 扰动打破了 descentYawArena 的字节级确定性(摘掉探针→solo 稳定绿 20s)。修=`926396d`(enter 改投递到 `ConcurrentLinkedQueue`,后台 daemon writer 线程做真正的文件 IO,server 线程零阻塞)+`a5c9e06`(writer 线程与 shutdown 线程之间的 append 加序列化,防竞态)。post-fix:**descentYaw solo 稳定绿 3 次,计数器字节级一致**(`sumAbsDyaw=871°` 等)。⭐**教训:对字节级确定性 arena,任何 server 线程上的 wall-clock 扰动都是嫌疑人;"纯文件追加、无副作用" 式的代码走查推理不能替代真实测量(A/B)——我最初就是这么错判的,被审阅正确打回。**
- ✅ **全量 SWALLOWED 三连证**(run1/run2 在探针修复(926396d/a5c9e06)之前、final 在修复之后；探针 bug 只扰动物理确定性断言不影响 enter/registered 记账，故三次 SWALLOWED=empty 结论不受影响)**:三次全部 empty**——`registered=130 entered=130` 逐次成立,#85(静默吞测试)在当前 HEAD **不复现,门保持武装 armed**。历史被吞名单三名候选(`ascendMovementNoopArena`/`ascendDeadZoneWatchdogArena`/`diagonalAscentSpeedArena`)显式 `AGENT_GT_ONLY` 基线**全部 PASS**(19s,VERDICT GREEN,详见下方逐条)。注:final 的第一次尝试撞上已知的 `underwaterBaseArena` ChunkMap livelock(SIGKILL/exit137,非 `timeout` 自身 124,但效果等价——世界跑到一半被打断,SWALLOWED(75) 是运行被截断的伪影,不是 #85;`run_gametests.sh` 每次调用都先 `rm -rf world` 自愈,重跑一次即干净通过)——这不计入"三连全 empty"的计数矛盾,是重跑前的中间态,记录仅为透明。
- ✅ **required 失败归类(evidence-based,替换原始版本的错误论证)**:
  - **`serveravatargearscopeprobearena`(gearscope)= 直接点名 + 经典 #48 签名**:`AgentGameTestServer.java:2340` 注释原文点名 `gearScope`;两次全量(run1/run2)均红、显式 solo 跑绿(`AGENT_GT_ONLY=...,serverAvatarGearScopeProbeArena,...` 该项 PASS)——教科书式并发共享身体彩票。
  - **`agentrpcsmoke`(agentRpcSmoke)= 直接点名 + 家族推断**:同一条注释原文点名 `agentRpcSmoke`("2/3... all solo-green");run1 红、run2 绿、显式 solo 跑绿——漂移 + solo 绿,判定同族彩票。
  - **`deepwatercrossarena` 原引证已撤回**:`AgentGameTestServer.java:2340` 注释点的其实是**姊妹测试 `deepwaterClimbout`**(`AgentGameTestWaterBank.java:1029`),不是 `deepWaterCrossArena`(`AgentGameTestWaterBank.java:919`,两者互为姊妹,见该文件 1018 行doc引用关系)——本次改判为**家族推断非直接点名**:run1 红、run2 绿、显式 solo 跑绿,失败集两次漂移 + solo 绿本身就是彩票签名,只是不能再说"注释直接点名"。
  - **`servermineburiedorearena`(buriedore)= 家族推断**:未点名于该注释,但 run2/final 两次全量出现、跨 run 漂移(未在 run1 出现),行为签名与 gearscope/agentrpcsmoke 同族;未做显式 solo 验证(不在本轮四人 solo 名单内),暂按家族推断记,不升级为可疑新regressons(名字已被 controller 白名单确认为已知历史成员之一)。
  - **`descentyawarena` = 探针事故,已修**:见上方⭐⭐;final 全量仍出现在失败集属**预期内**(coordinator 原话:"修好后 solo 应绿,全量最坏情况下退回历史彩票行为")——探针修复只治了"字节级确定性 solo arena 被自己的 IO 噪声打断"这一具体伤害,不改变"全量并发身体共享"这个 task#48 母问题,descentYaw 本身历史上就在这个母问题的漂移名单里(TODO.md 07-14/07-15 多处记录),这次全量红不是新问题。
  - **`entityleashrepatharena`/`selfshaftdiguparena`(final 新出现)= 均为 controller 白名单历史成员**:`entityLeashRepath` 直接见于同一条 `AgentGameTestServer.java:2340` 注释("`entityLeashRepath` 3/3");`selfShaftDigUp` 见 TODO.md 07-14 记录("selfshaftdigup 全量 RED(4/4)但 solo GREEN...既有全量时序彩票")及 `2026-07-16-b1-pause-for-test-framework.md` handoff("selfShaftDigUp(跨run漂移,本会话实测两轮FAIL一轮PASS)")——两者均为已录入案的 #48 家族成员,非新名。
  - **`vineoverwaterclimbarena`(optional)= 既知 optional flake**:多份 plan doc/TODO.md 反复记录,不影响 VERDICT 判定必要性。
  - **零真正新名**:run1/run2/final 三次全量 required 失败集的并集 = {gearscope, agentrpcsmoke, deepwatercross, buriedore, descentyaw, entityleashrepath, selfshaftdigup} + optional {vineoverwater},**全部落在既有已文档化的 task#48 漂移家族内**,符合"回归只加失败不换人"的审计规则——非回归。suite-wide 隔离修复(task#48 后续)待专门 test-framework rework,不在本次 P0 范围。
- ✅ **被吞候选显式基线(`AGENT_GT_ONLY=ascendMovementNoopArena,ascendDeadZoneWatchdogArena,diagonalAscentSpeedArena GT_TIMEOUT=1800 scripts/run_gametests.sh`,19s,`registered=130 entered=130 build_success=True required_failed=False` → **整体 VERDICT: GREEN**,`exit=0`)**:三个点名的候选者本次**全部有 manifest enter 记录且真实执行**(非早退 no-op)——①`ascendMovementNoopArena`:**PASS**(`step=ARRIVED reachedTop=true ctxAllocated=0`);②`ascendDeadZoneWatchdogArena`:**PASS**(纯逻辑断言测试,5 阶段全过——PREP→giveUp 拍 RUNNING→UNREACHABLE→终态清空重 PREP→active-dig 豁免→post-dig 时钟满额重置→monotonic dy 高水位不被同 apex bob 重置,`helper.succeed()` 无异常);③`diagonalAscentSpeedArena`:**PASS**(`step=ARRIVED reachedTop=true diagBps=3.00 ascSprint%=43`)。**这是被吞名单(三次全量均为空)对应候选的真实 GREEN 基线,供 P1 dogfood 迁移直接引用。**
- ⭐**从此验收只走 `scripts/run_gametests.sh`**(裸 `./gradlew :neoforge:runGameTestServer` 不再是 canonical 入口,不做 PID sweep/世界清/wall-cap/对账,会重新暴露 #85 类静默吞测试)。

## 2026-07-15 gap#81 live 恢复生存时新实证

- 🔴 **gap#73 强实证:PanicChain(1000)不按可达性门控→隔墙不可达 creeper 死锁自救挖掘(Catch-22)**。live(07-15 白天):bot 无镐手挖竖井上升出坑(y64→70),一只 creeper(id36760)在 ~6 格外、**隔实心石墙、`canSeeMe:false`、`creeperSwell:0`、`charging:none`**(creeper 不会挖=对 bot 完全不可达),但 PanicChain 仍以 threat 0.55-0.77 抢占运动通道(priority 1000 > user 50),`userTaskSuspended:true`。因 bot 困在 1×1 竖井里 panic 无逃逸路→冻住 ~1 分钟(video 连报"完全静止无挖掘无视角变化",block.break 事件停),而 **panic 又压制了 user 层唯一能自救的挖掘指令**=死锁。creeper 后来自行上移到 10.6 格外 threat 才降、panic 才释放(bot 被驱下到 y63、重捡掉落 dirt)。⭐**这正是 task#77/gap#73"autoFight/panic 无威胁可达性判断"的洁净复现**(比"墙后 creeper 掘进逼近"更纯:此处 bot 完全被动挨触发)。修向:panic/threat 评分应按 `canSeeMe`+寻路可达性门控(隔墙不可达的 creeper 不该 hold panic 通道),或 no-flee-path 时允许 user 自救挖掘穿透 panic。**附带**:①live-screen-watch 对慢速手挖竖井仍反复误报"卡死"(已在 context.txt 加 NORMAL 豁免但模型仍报,阈值/提示待再调);②bot 无镐+夜降+creeper 三重下,已 cancel surface goto 让其地下过夜(duskSecure 兜底),黎明再上升——**无武器 bot 夜间不上地表**(策略律);③acacia_log 在 panic 驱离期掉落丢失(Stage-1 木种没了,黎明地表补)。
- 🔴 **新 gap(P1,#54/#63/#66"固岩长升"族强 live 复现):无镐深石垂直上升 walker churn,阻塞埋藏 bot 地表恢复**。live(07-15):bot 埋于 y62(无镐、HP13.7、food9),要上 y79 地表采木。**跨三种命令变体全部 churn**:①`goto y:80 + leash{r4,w30}`——y62→66 净 +4 却挖海量方块,横向游走((-8,1)→(-10,-2));②`goto y:80 + column{r1}`(硬 ColumnRadius)——bot 在 (-8,-7) y64↔67 反复上下挖,**根本没进 column**;③`goto direction:up distance:15`——(-10,-5/-6) y64↔66 振荡。整段 y62→68 净 +6 却烧掉大量 food(9→8)。=无镐手挖石(每块 ~7.5s)叠加上升 churn,walker 无法可靠垂直自挖出坑=违反"⭐MC 永远能徒手挖出去"。⭐这是 gap#66 leg B(column no-path,replay-0001)+ #63"固岩长升"残余的**生产环境强证**,应作为 #54 执行器状态机迁移的高优先对象(埋藏无镐是生存常态)。**当前处置**:cancel 止 churn,bot idle 于 ~y68 白天安全,夜里 duskSecure 挖石龛守夜(空背包无 cap 块但石中挖龛=被石围=enclosed 无需 cap)。gap#81 live 行为验证仍未取到(上方全实心岩=StairUpBreak 非 PillarUp,未触发垫柱路径);待有可达地表/受控 rig 再验。
- 🔴 **策略/引擎复盘(本次 live 生存代价)**:①无镐埋藏 bot 上升极慢+churn=上面这个 gap;②上升途中被隔墙 creeper panic 死锁(gap#73)后 creeper 游荡回来引爆 HP20→13.7;③panic 驱离期 acacia_log(唯一木种)掉落丢失→背包归零。综合=**埋藏无镐 bot 的地表恢复目前是 walker 的真空档**,是比单个反射更结构性的生存阻塞,归 #54。

## 2026-07-14 晚 生存线 Stage 4 铁线收官期观察(gap 候选台账)

- 🟢 **gap#2 工具门天然 live GREEN**:双镐耗尽后 mine 行进段空手破石(合法通行),摸到铁矿时干净中止 `blocked: iron_ore needs a pickaxe — none held or in inventory`,零空磨。AutoTool 断镐自动降级木镐也正常。
- 🔴 **gap#74 候选①水线平衡陷阱**:mine 结束后 idle bot 停在淹水隧道段(-84,58,59),头部恰在水线,air 在 220-300 循环振荡(DrowningFloatGate air<240 触发浮起→回升→释放→再沉)=**有顶棚淹水段淹不死也出不来**;idle-passive 契约下横向自救是否允许=user 裁决项(与纯垂直豁免裁决同族)。frail 无滞回振荡家族再添 live 实证(P1 Phase2)。
- 🔴 **gap#74 候选②cornered 事件零去重**:上述陷阱期间 `cornered` 同坐标每 ~3s 一条连刷数十分钟(seq1103-1181+),对齐 antisuffocate 日志去重(gap#72-④)应按转移去重。
- 🟡 **gap#74 候选③craft ensure-count 语义无法区分**:已有 1 镐时 `craft{stone_pickaxe,count:1}` 返回 ok:true+started:true 但瞬间结束零动作零 error(两次复现)——若语义=补足到 count 则应在返回里可区分(`already-satisfied`),否则策略层无法审计;待用 `craft{bucket,count:2}` 观察(bvopgzllh 进行中)。
- 🟡 **观测管道教训(自纠)**:observe.player 的 hotbar 是 inventory 的子视图,sum 聚合=双计数假象("2 把镐"误判);**清点一律用 items 字段单源**(#41 本来就给了)。
- 📋 生存线进度:raw_iron 7(第 8 颗掉落漂失)/coal 6/原木 4/圆石 214/生铜 45;food 7 偏低待黎明补猎;熔炼+桶×2 编排进行中。
- 🔴 **gap#74 候选④combat 远目标追击停滞(live)**:对 84 格外的鸡 `combat{kill,id=29}`,chain 持槽+内部 goto 只出 5-7 步小段(expanded 16/finalCost 48/goalReached=true),bot 数分钟净位移≈0(video 连续 ANOMALY 静止);user goto 同目标同样在水岸振荡(-70↔-62 来回,enteredWater×2,pathStep 卡 1,同一 startedAtMs 3min+)——渐进式分段规划在**跨水体目标**上段末点落水岸→重规划→回摆,combat 追击与 user goto 同病;疑与 #59/#63 escape-farthest/horizon 家族同源的水岸变体。证据:seq1716-1737,startedAtMs=1784071170275(combat)/1784071204595(goto)。夜降后已手动 bunker(SEALED @ -69,64,81)兜底。
- ⚫ **死亡#24 后果:全家当 despawn**——死点 (-57,64,107) 距重生点仅 ~90 格(<sim-distance),区块持续加载 5 分钟计时器走满,回收时(死后 ~22min,含被迫过夜)零掉落。损失=桶×2/铁锭 1/石剑/圆石 178/煤 5/原木 4/生铜 45。⭐策略律:死点在 sim-distance 内→计时器不停,回收优先于觅食(HP 危急除外);死点远(区块卸载)→计时器冻结可从容。Stage 4 从零重建中(红树林原木→石器→老铁矿点)。
- 🟡**gap#81 代码合 master(07-15,5cea9f1)+arena/编译 GREEN,live 行为待白天验**。真根(Explore 定位):垫柱-vs-挖升是 A* 代价决策(PillarUp cost10 vs StairUpBreak 15+破坏),**开阔空气上方 PillarUp 是唯一增高动作必放块**;块选择 `ensureHoldingPlaceableAny`→`isUsableBuildBlock` 零价值意识→acacia_log 被当垫料吃掉。修=价值意识**独立层叠在 isUsableBuildBlock 之上,不动 gap#57 谓词**:新 `BotConfig.isValuablePlacementBlock`(logs/planks 标签)+ `isThrowawaySupportBlock`(可用 build 块 AND 非有价值;dist-neutral core 因 BotInteract 混 client-only 方法在专用服务器不可加载)+ `ensureHoldingPlaceableAny(mc,avoidValuable)`(无非有价值块则返 false**不回退动用有价值块**→gate 跳过,bot 留住资源)+ `Avatar.holdThrowawayPlaceable()`(默认=holdPlaceable,server avatar 不变)/ClientPlayerAvatar 走 avoidValuable。**仅改 Walker:3069 例行 PillarUp**;恢复/逃生垫柱(fellBelowRoute/deepPitEscape/overJump)+bunker/escape 进程保持 holdPlaceable=逃生动用任何块合法(对上 user"逃生必要 vs 例行浪费"边界)。TDD:`valuablePlacementBlockMatrix` 并入既有 AgentGameTestBuildBlock RED→GREEN(6有价值/6junk/throwaway 断言+gap#57 回归守卫 OAK_PLANKS 仍可 build)。编译三 loader 绿。⭐**server avatar 对本改动不可见**(默认 holdThrowawayPlaceable==holdPlaceable)→无服务器 arena 能行为回归,唯一服务器可测项(纯谓词)已 GREEN;全量套件挂死在 underwaterBaseArena(无关 pathfinder/chunk 忙循环环境类,TaskStop 清+删 world)。**残留:live 行为验(bot 携 dirt+log 上升→确认耗 dirt 留 log)待白天恢复生存时做**(client-only 路径唯一真证)。⚠️实证:SurvivalTest=专用服务器,ESC 不冻结(paused 仍 false)+开屏瘫痪反射(已记忆)。
- ✅**收案(07-15): gap#75-a/#75-b/#76 三修合 master(405b444 bridge / 492e6d5 duskSecure / 4465b37 drownEscape)+编译绿**。**gap#76(死亡#25 active溺水抢占)**: mine 挖井进水 air 耗尽仍连 break(gap#70 DrowningFloat 只管 idle,AutoSwim in-process jump 与进程共享通道被压)。修=DrownEscapeChain(priority DROWN_ESCAPE=500)air≤100 抢占通道→纯垂直上浮(仅jump+清sneak+破顶盖)→滞回释放280;纯门 DrownEscapeGate 无client矩阵测;3设置经 mc.bot.setting 暴露。live: PREEMPT@air100(arena真调度双证)+ 清水居中 idle 浮出 y68→75.7(air300)=机制成立。bridge/duskSecure live 复验补齐(tower h5+bridge east4 placed=4 零坠 / 夜间 SEALED)。⭐⭐**溺水 live rig 教训**: 水柱方块中心是 (X+0.5,y,Z+0.5)——错位 tp 送 head 进墙→AntiSuffocate 误击水卡死角落→本 session 自造两溺死(第二次因水柱建出生点重生再溺);清水居中 rig 证浮出=溺死是 rig 假象。规律:水柱 rig 必居中+勿建出生点+air 守护;预灌水坑浮力顶 bot 到表面无法自然复现深水 active-drown(死亡#25 是从干地下挖穿岩石进含水层)。**次级发现→gap#80(已修合 master a288452)**: A=AntiSuffocate.resolveHead 四处 `!isAir()` 误把相邻 water 当窒息目标连击(视角甩下驱动attack)→修=判据改 vanilla `isSuffocating`(新 pure `AntiSuffocateGate.suffocates`,与 isInWall 门对齐); B=AutoSwim deep-ascent 补 `keyShift.setDown(false)`(兄弟 DrownEscape 有); arena antiSuffocateWaterNotSuffocatingArena RED→GREEN(真方块权威)+编译三loader绿+零新回归;live deferred(低频边缘,判据对齐低风险)。 C=cancel(mine) 返 ok 但 break 续~20事件(未确证即停性,携 task#78)。
- ✅(合master 405b444/492e6d5)**gap#75-b duskSecure 被抢占后不重武装——已修+arena GREEN(07-14)+live 夜间 SEALED 复验(07-15)**。live:dusk 挖龛中被 user goto(50>40)抢占→INTERRUPTED;goto 取消后 duskSecure 整夜不再重试,bot idle 在 2 格深未封顶坑(seq1945→1953-1971→chain None)。真根因(arena 世界实测钉死,**非** one-shot 标记——根本没有该标记):被抢占后 bot 站在自己半挖的 1×1 竖井里,HazardField 读 `cornered=true`(8 邻列无 ≤+1 步高可站立点),priority() 的 day/present/cornered 起始门**自我否决到天亮**——"自家竖井豁免"(mid-dig hold)只在 `process != null` 时生效,抢占 drop 掉 process 就失效。修(DuskSecureChain):`rearmPending` 闩——interruptEpisodeState 持有 process 被抢占时置位;新 `startGateBlocks()` 纯门在闩置位时**仅豁免 cornered 否决**(day/absent 仍门,THREAT_RADIUS 否决+debounce 不变);消耗=进程自身终局(bail 不得循环重触发,防 gap#29 棘轮)/黎明(present&&!exposedAtNight)/显式 cancel;`duskSecure.triggered` 事件新增 `rearm` 字段区分重武装。arena=并入既有 `duskSecureHeldProcessLifecycleArena`(矩阵⑤ rearm 生命周期 + `duskSecureRearmWorldLeg` cx3200/cz3500:真 BunkerProcess FakePlayer 挖到井中→interrupt→实测 cornered=true+openAbove=true→门放行→re-arm 再挖至 SEALED+enclosed=true;RED"INTERRUPTED must not consume the dusk episode"→GREEN)。⚠️rig 教训:同步 gametest tick 内 `canSeeSky` 读 stale 光照,露天断言用直查方块列代替。**残留:live 自然 dusk 抢占复验未做(需下次 live 会话)**;全量彩票记录:selfshaftdigup 全量 RED(4/4)但 solo GREEN 且**去掉本 leg 的 A/B 全量同样 RED**=既有全量时序彩票非本修引入;entityleash 时红时绿(master 既有);descentyaw/vineoverwater/descentdrift 同既知。
- ✅(uncommitted)**死亡#24 gap#75-a: construct bridge 起步跌落——已修+arena GREEN(07-14)**。真根因(arena 钉死,非"未建支撑就前移"):①BridgeProcess 全部簿记锚在 `floor(center)`,而 vanilla sneak 边缘防坠(`Player.maybeBackOffFromEdge`,gate=`isShiftKeyDown`)**允许合法悬伸**到 AABB 只剩 0.3 贴支撑——center 合法越入无支撑邻列(tower 收尾在 1×1 柱顶常态产出该姿态)→ PLACING 读"脚下无支撑"**误诊坠落**、终止,terminal `releaseInputs()` 松开正把人钉在崖边的 sneak = 真坠落(死亡#24);arena 复现:悬伸 start t=1 即报同款 lastError 而 pos/onGround 纹丝未动。②client 侧共犯:raw forward 沿**相机 yaw** 行走,而 LookController 在 scheduler 之后 30°/tick 重钳 BridgeProcess 的 setYRot snap → 起步头几 tick 朝任意方向走(headless 无 seam,live 验证)。修(BridgeProcess):`anchoredFoot()` AABB 感知重锚(center 列无支撑→取 AABB 下最大重叠有支撑列;全无才判真坠落)+ WALKING forward 门在 pre-write yaw 对齐 ≤20° + `placed` 改 pendingPlaceCell 观察到实心才计数;TowerProcess 同族审计:placed++ 加放置验证。arena=`serverBridgePillarStartArena`(AgentGameTestServer,leg A 悬伸 start RED→GREEN laid=#### done placed=4;leg B 居中 baseline GREEN)。**残留:live 柱顶 bridge 复验未做(需下次 live 会话);死点 (-57,64,107) 家当已 despawn(见上条)**。⭐HP1 时不该用未经 rig 验证的 dual-use 动词(教训)。⚠️07-14 记录:`entityLeashRepathArena` 在**干净 HEAD worktree solo 也 RED**(2/2,pos 停在 arena 原点未动)=master 既有状态非本修引入;descentyaw/gearscope/buriedore/vineoverwater 均彩票(solo GREEN)。
- 🔴 **(升级为 task#78/gap#74)** 上述①②合并立案+新增:**frail 门无目标类型判别**——HP5 猎鸡被 `frail-abort hp=5.0` 拒绝=饥饿螺旋;workaround=combatFrailThreshold 临时 0(用后恢复 6)。**险死复盘**:夜行被双僵尸接力追击 HP20→5,零低 HP 反射兜底,根因=`autoBunker=false`(#30 死锁 workaround 从未恢复,当年根因 #29/#61 早已修)——已恢复 true;⭐实验性关反射必须记录+根因修后立即恢复。水线陷阱最终解=mc.bot.build 圆石堵水(escape verb 对该场景瞬退零效果);流水推挤+无镐+淹水隧道=walker 水域新变体。duskSecure 夜龛在 retreat 释放后正常触发(gap#72 修后首次自然夜龛✅)。

## 2026-07-14 ⭐当前主线:task#54 Phase 1 调度器语义层(spec 待 user 审)

- **方向**(user 授权自定,memory `project_direction_decision_54_first`):主线=#54 Phase 1 调度器语义层;#52 并入 spec 验证底座章节;生存线降级回归信标;gap#67(task#66)排后。
- **spec 已提交**:`docs/superpowers/specs/2026-07-14-scheduler-semantics-phase1-design.md`(commit fd1d415)——5 根因(episode 生命周期/终态诚实化/ThreatContext 反射门/combat 脆血+dusk 升压/AutoTool 宽限)对账 gap#68 十一腿证据册(task#67)。**等 user 审核,批准前不写实现**。
- **生存线状态**:day 60 晨,Stage 4 铁+gap#64 smelt live 收官✅(task#63 收案);bot 封洞于 y20 隧道,游戏已暂停;autoBunker 关闭中(⑦死锁 workaround),夜间需手动 bunker+封口(bunker verb 有零动作假成功,证据⑩)。

## 2026-07-02 ⭐当前优先级(用户硬指令)与任务镜像(Task #2-#7)

**优先级指令**:①高效发现问题与验证(观测/验证基建)最优先 ②本项目代码防腐/架构优化次之 ③发现并解决具体寻路问题最后。

- **P1 观测基建(Task #2,in_progress)**:11 类 [expect] 执行器预期报警已 committed(DIG-dropped/DIG-slow/JUMP-noRise/MOVE-noMove/REPATH-flip/DRIVE-tear/ADVANCE-deadzone/GEAR-degraded + 本日新增 CLUTCH-noArm/PLACE-noBlock/IDLE-drowning)+ JUMP 阈值 0.8 校准 + forensic.py 同 tick 配对验尸 + 反射化 setting(新 flag 零接线)+ 验收协议 preflight/expect 计数/churn-drown guard。**剩:重启客户端激活(遇 GL hang,复现 2026-06-27 签名)+ smoke。**
- **P2 防腐(Task #3)**:验收 FLAGS(~27 个已验证 default-OFF flag)与 accept_cycle.py 从 scratchpad 入库。
- **P3 寻路 lane(Task #4-#6)**:①escape-vs-dig 调度冲突(C24-J3 DIG-dropped 归因=DrowningEscape 抢占断 breakHold)②dig aim-drift(DIG-slow 归因)③repath 振荡 hysteresis 正确场景重 A/B((-252,70,209),K≥6)+ mount 双稳态(replay-0016)。
- **终门(Task #7,blocked by #2)**:#47 三周期验收(3×随机长途+每程 replay×3 全绿);已 24 cycle 未连三绿,J1 已稳。flip-default 待用户。

## 2026-06-27 ⭐弧长追踪执行器重构(治本)+ 打破一个月 0-commit 死锁

用户硬批评「一个月点修无进展、向后跳/挖墙/卡浮萍仍在、每次只修一小段发现不好就 revert、原地踏步」后,转向**结构性重构**:把执行器的「每-tick 瞬时几何门 + bob-immune 计数器动物园」替换成**弧长追踪 pure-pursuit**(脚投影到 path 折线→单调弧长 s→切线驱动)。分阶段、各自 flag、全 **live/replay 验证**(非 arena-green):

- **P1 `walkerArcLengthAdvance`(default ON)**:用投影段推进 step 指针,旁路 7 个 bob 补偿门。LIVE:`projSeg>step` 30→4(step-freeze/卡浮萍消除)。
- **P2 `walkerTangentAim`(default ON)**:相机+身体瞄 s+lookahead 处路径切线(永不 180° 翻),消除**向后跳/反复横跳/贴墙**。LIVE:平滑段 `dYaw≥120` 123→3(0.2%)。含 **dry step-up 例外**(ascent 节点用 legacy 节点 aim 让 pivotForStepUp/stepUpJump 对齐 mount)→修**上坡跳不上方块**(LIVE bot 爬升 y62→y94 解锁整座山)。
- **P3 `walkerArcLengthWedge`(default ON)**:bob/jitter-免疫 ram-wedge recovery(`|ds|<0.05 + horizontalCollision` 累积 30t→折进 proven fellOffPath blacklist+reroute),替代挂 noStepProgressTicks 被 ram-jitter 清零的 descentRamStuck 族。**EXCLUDES water**(水域 climb-out 有专属 recovery)。LIVE:water-exclude 0 误触发。
- `walkerArcLengthShadow`(default OFF,log-only 诊断)。`PathProjection.java` = 抽出的可读投影核心(巨兽分解第一步)。

**净效果**:干地 traversal 根治了用户点名的向后跳/横跳/贴墙/上坡跳不上;`HEAD f9a4a2e`(一个月前)以来的全部累积工作落定为 committed baseline。

### ⬜⬜ 已知剩余 dominant blocker:水域 climb-out / bank-dig(near-fundamental,下一专门周期)
arc-length 相位只治**干地**;**水域 jank 是独立大域、未治** = 用户点名的「挖墙」+「在水里卡住」。**三器定层(-658,61 live)**:① planner **完全干净**(debug.plan chain reached/0 backtrack,routes 平缓爬升出水);② 纯**执行层**:submerged bot(`deepDig=true`)在 +3 岸脚被抑制 pillar 爬出(2461 要 `!deepDig`)→落到 block-less bank dig(2606 deepDig 分支)→水下原地挖高岸,而非先浮出水面/沿 path surface-swim 到平缓出水点。**修向**:水域 climb-out 不在 path 仍沿水时过早 engage 本地陡岸;submerged 先 surface 再 climb。浮力 bot 放 foothold 站不稳是 near-fundamental(dig-over-pillar 是当前 least-bad)。Task #59。详见 memory [[project_arclength_pursuit_refactor]]。

## P0 — 寻路丝滑(长途随机目标一路丝滑、不停顿、不跳变视角;验收=pathChart + 视频转录双通道无异常)

镜像 Task #29(回归 goal)+ #34(规划层深水可站性)。

### ⏳ 2026-06-24/25 执行器 fix 族 I/J/K/L/M(uncommitted,各 GT 50/50 + live replay A/B 验证)— 陡山爬升丝滑
真根因全在 Walker/BotInteract 执行器层(非规划层),逐 tick telemetry + replay A/B + GT 三器定层:
- **fix K**:survival pillar-recovery `BotInteract.ensureHoldingPillarBlock` 只扫 hotbar(inventory 分支 creative-only)→survival bot cobble 在 inv slot 9 取不到→slide-back 恢复静默 no-op = 主 churn。修=survival inv→hotbar SWAP(镜像 AutoEquip)。drift-stalls 28→2。
- **fix L**:对角爬升 inter-step sprint→momentum 横向漂离窄楼梯线→slide→ascentRamSlide pillar-spam。修=`diagAscent`(对角干地爬升)drop sprint。pillarUp 292→0、cobble 用 56→2、到达 2× 快。
- **fix M**:tree-canopy/dirt overhang 下 pillar-recovery 撞顶 bob 不升 247t(fix K 副作用)。修=no-rise give-up(`PILLAR_NORISE_GIVEUP=50`t 不升→gate fellBelowRoute→foot-search re-route 绕开)。trap 247→0、pillarUp 339→95。
- **fix I/J**:深坑 pillar-thrash 105s→4s;慢游 approach-sprint +78%。
- **残:vine-over-water climb-out**(#50,-711,67 vine-wall-over-pocket)+ open-water surface-swim drift(#49)。**2026-06-25 三修全 revert→working tree + live client 均 clean I-N'**:fix O(planner floating cost-tax→A* 撑爆 30000 node-cap)、fix P(executor 删 vine `!isInWater`→-711 churn 1086t/54s ~9× WORSE)、fix Q(Plan-agent spec:Part2 parkour-landed-on-vine handoff `onVine 在 parkour edge 放行 when landedOnVine`,GT 50/50)。⭐**fix Q 真 blocker = VALIDATION 不是 design**:`replan:true` journey replay 每跑 path 不同→几乎不复现 parkour-LANDS-on-vine target(replay#3 没碰 vine/#4 off-path drift),pre-widen 命中时 grab 但 **bob-stuck 爬不上**(unknown#3=vine yaw 瞄 overhead node 非 wall)→无法干净 A/B→revert。**真路(fresh clear-headed session):先建 DETERMINISTIC `vineOverWaterClimbArena`(spec 全文 scratchpad/vine-over-water-fixspec.md:layout+never-pocket-entry predicate,⚠先验 fake-player vine-cling fidelity)做 reproducible A/B——journey replay 对此 genre 根本不够;再 Part2+widen+unknown#3 ascent-yaw 修**。⚠~450-turn fatigue 下 O/P/Q 三连 revert=别再盲改 water/vine,需清醒 session。**#47 因 -711 genre 未根治、round3 未过**。详见 memory [[reference_water_brake_sneak_sink_and_parkour_yaw_fix]]。
  - **2026-06-25 续(突破+第4次 live 破)**:✅DETERMINISTIC `vineOverWaterClimbArena`+`vineClingFidelityProbe` 已建(validation-tooling blocker 解,byte-identical 复现 baseline)。agent 实现 **Part A `vineWallYaw`**(Walker:738,climb 时 yaw 瞄 vine 背墙=持续 into-wall press=vanilla climb-up,unknown#3 正解)+**Part B BridgePlace:44** reject bridge-from-vine。**GT 52/52 + arena ARRIVED everInPocket=false**。❌**但 LIVE replay-0004 -711 仍 churn 2438t/~122s,pocket 1745,vine-climb 仅 40×**(journey 仍 **ARRIVED**=jank)。⭐divergence:arena 过靠 A* 的 **pillar-AROUND**(Part B 撤 bridge 后更便宜),**live 几何无 around**(parkour→vine→pillar-AT-TOP,vine REQUIRED)→arena 给了 live 走不了的逃逸=false green。Part A+B+fix Q+arena **KEPT**(uncommitted,GT-green,sound,无明显回归,作下 cycle foundation)。**NEXT:精化 arena=force vine 为唯一 ascent(删 pillar-around,从 replay-0004 envelope 真几何切)+re-add VINEDIAG 诊断 live vine-grab 为何不 sustain+爬到顶无 pillar 逃逸才算 faithful-pass+live ≥3× 验**。
  - **2026-06-25 续续(✅✅突破成果)**:① **validation-blocker 终解**=`mc.debug.replay {replan:false}`(经 mc.script.eval)force-path 确定性 A/B,replan variance 不再卡。② **root cause 终定**=-711 vine FREE-HANGING(挂 canopy 无背墙→vineWallYaw=null→Part A press-wall 不适用)。③ **fix R `walkerVineFreeHangClimb`**(default ON;wall-less vine continuous-jump+slew drive+vertical hold;ServerPlayerAvatar+JUMPING_FIELD)修好 **CLIMB**——GT 52/52+free-hanging arena climbedTop=true+**full-journey replan:true pocket 1745→367(5×)/churn 2438→~964(3×)/ARRIVED z282**。KEEP(uncommitted,GT-green)。⚠**残:full-journey -711 仍 churn ~40s(簇 -711~-716)= parkourAscend2 落点 UNDERSHOOT 穿 vine 掉 pocket 底(foot=water→freeHang 不 engage)→浮回 vine 才 climb** = landing-undershoot(独立 genre)。**验证挑战**:arena pocketTicks 是 TOP-dismount artifact 非 bottom-landing;replan:false 在到 -711 前先 wedge 于上游 open-water drift -703,487(#49);replan:true 噪声。→landing fix 需先「精化 arena 捕捉 bottom-landing undershoot」或「先修 #49 上游 drift 解锁 replan:false 直达 -711」做干净 A/B。⭐**fatigue ~565 turn,不盲夹 landing fix,留 fresh cycle 配好验证台再做**。详见 memory fix R 段。

### ✅ 本会话已修(master)
- **4a23b00 climbOutTax(XZ 目标)** — per-rise 罚「出水上岸偏向高岸」,让 A* 挑最低岸出水口。永久 A/B arena `waterClimbOutRouteArena`(税off 爬+1 高岸 maxY221、税on 改走平岸 maxY220)。
- **0cebe97 submergedTax(核心根治)** — 真根因:`PathFinder` 4 条水税(waterCellTax/descendTax/submergedWaterCost/climbOutTax)全门控 `goal.ignoresY()`→**只对裸 XZ 目标生效**;正常 `goto pos` 是 `Goal.Near`(带目标 Y)→水模型全失效→A* 把浮力 bot 路由到淹没河床(stepDown,canStandAt 让任意水格可站)→深水 churn。修=新 `Goal.targetPos()`+`diveGoal()`(水下目标=故意潜水豁免),对陆上目标罚「**下潜**进淹没格」(只罚下潜→pillar/dig climb-out arena 不退化)。**LIVE replay A/B:停顿 58s→14s(-76%)、journey -34% 更快、z1956~1947 深水 churn 簇整簇消失。GameTest 109/109+42/42。**
- **5ac9b3f 证伪记录** — climbOutTax 放开到陆上目标 backfire(罚出水→bot 赖水里→复活下潜 churn,replay 14s→37s),保持 XZ-only。
- **66ef356 knob 接 setting** — pathfinderSubmergedWaterCost 此前未接进 mc.bot.setting,补 setter/snapshot/schema,可运行时调参。
- **9cc617a submergedTax 默认 40→80** — live A/B 再砍一半:停顿 14s→7s(-88% vs 58s 基线)、窗 4→2、z1883「被迫下潜」窗整窗消失。抬罚方向安全(只罚下潜)。GameTest 109/109+42/42。

**进度:journey 停顿 58s→7s(-88%),端到端到达、大幅丝滑。**

### ✅ z1973 漂移上岸振荡 —— 根治(0e08d47)
逐 tick + block 探针推翻「commit 失败/相机射线」初判:真因=block-less 破岸 dig 每 tick 从 LIVE(bob foot.y 翻 208↔209 + 侧漂)重算 riser→**同一岸列在 foot.y 与 foot.y+1 两高度都挖穿**→岸面挖到水线、下一列变新 +2 墙→无限横跳(ashoreTick 162)。修=`Walker.waterClimbDigRiser` 闩锁:①锁住正挖 riser 块实心就一直挖它;②选新 riser 挖前向列**顶层实心块**(沿列向上找顶=对 bob 不变);③仅 `top.y>foot.y` 才挖(+1 台阶本可上,挖它会把岸挖到水下)。**driftArena 162→74**(swim→挖一块→上 +1→上 +1,零横跳零重挖),断言收紧 ashoreTick<120。GameTest 109/109+43/43。

### ⭐⭐ 关键 live 发现:反向 leg goal 本身不可站(坏目标,非寻路 bug)
relaunch 新 build live 跑 (2356,1986)→(2350,1820):bot 多次逼近 d=2~8 又弹回水里 d=80~109,A* **goalReached=0(全 70 段)**;逐格读目标列 **(2350,*,1820) y60-64 全 stone、y65 唯一 1 格 air pocket(石头 y66 顶)=站不进→目标格不可站**。canStandAt 正确拒绝→A* 烧满 60000 节点/repath(4.9s)→bot 邻近水里振荡。**换可站 goal (2343,63,1824)→outcome=SUCCESS、84s 端到端到达、archive 量化仅 1×3.5s 停顿窗(z1956 首次入水)、无西向绕路**。→**核心寻路 + drift-dig 修都 OK;旧 stop-hook 反复报的「journey churn/异常」≈坏目标 thrash。**

### ✅ 不可达/不可站 goal robustness —— 根治(783aa32 + 21807a2)
`Walker.snapGoalToStandable`:Goal.Block 目标若 `!world.canStandAt`,半径 6 内搜最近可站格(用 planner 同一 canStandAt 谓词→规划+arrival 一致)替换 goal。**关键门控 `world.isKnown(target)`**:长途起点离目标 166 格→目标 chunk 未加载→canStandAt 读 void→若首 tick 就 no-op 且置 checked 标志则永不重试;改成目标 chunk 加载后(bot 进渲染范围)才一次性 snap。新 arena `goalSnapBuriedArena`(实心石柱目标→snap 邻格 ARRIVED@31)。**LIVE 验证**:坏目标 (2350,64,1820 埋石山) 整程→snap log「→(2348,64,1820) d=2」、166 格端到端到达并 settle(active=false 静止不动)、pathChart **outcome=SUCCESS reached=true**、无西向绕路。GameTest 109/109+44/44。

### ⬜ 残留真凶(系统性,大工程 deferred):深水 +2 岸 climb-out 原地打转
**逐 tick + step 指针定根(snap journey archive,tick 3537-3722)**:水谷停顿(5.3s+3.6s @ (2360,62,1879))= **step 冻结在一个浮力 bot 上方的 +2 climb-out 节点**(node y64,bot 浮 y62)。`within` 守卫 `!(inWater && dyNode>0.5)` 正确不让前进到没够到的 climb 节点,bot 到了该节点的 XZ 但爬不上去→**对着正上方节点没有水平 aim 方向→smoothLook 原地旋转(yaw 扫满 360°=pathChart maxYawErr179 之源)**,直到 repath 把它带走。climb-out 接管(pillar/dig)本该 engage 但被打转/侧向动量搅乱不可靠(=memory「接管延迟+可靠性+burst-crab」)。**多 journey 验证(本会话 2 条)证此为系统性**:南向到 snap/可站目标=到达但中段 1 个 5s 打转窗;北向 (2356,70,1986) 整程在起点水区打转 130s 没到达——此水饱和地形多个目标都落在 water-climb-out 点。**根治=专项重写水岸 climb-out 执行**(浮力 +2 岸:climb 节点上方 aim 稳定[别打转]+ 接管可靠引擎,~15 prior+本会话尝试均证非增量能成);drift-dig(0e08d47)已修「挖过头」一类,但「到了climb节点XZ却打转不engage」是另一面。**别在会话尾部仓促改 aim/heading(4 次 forced-heading 尝试都 revert 过)。**

### ⬜ 新失败模式根因(已定位)+ dig actuator 已证伪(revert):水平水域 bank-face wedge
**逐 tick walker 日志(walkerDebug)定根**:重跑南向 climb-out(TP 2361,66,1895 → goto 2361,64,1860),archive 量化 = 20s 跨 33 格、**85% 在水里**、median 0.09 b/tick(半速)、**2 个停顿窗 2.5s@(2361,62,1880)+3.7s@(2361,62,1870)**,全在水里。walker 日志:停顿处 `move=walk onG=false inW=true undW=false node=2360,62,1869`——**节点 Y==foot Y**!`wantClimbNow=(cwp.y>foot.y)` 恒 false → climb-out context 永不 arm → `waterClimbStall` 恒 0 → 破岸/搭台 actuator 全程**零触发**(grep 证实)。bot 顶着一道岸 face(planner 把平 Y 节点路由到 face 后)`cur2` 卡 ~1.0 撞墙,直到 ~64 tick 后 A* 撞运气重路由才脱困。**这是和 +2 竖直 climb-out 不同的一类:水平 bank-face wedge。**
**尝试(Walker,5 处:`horizWedge`=水中+前向solid face+`noStepProgressTicks>30`→折进 wantClimbNow;dig trigger 加 `||horizWedge` 跳过慢门槛;riser 加「同 Y body-level face 直接挖穿」分支)→ GameTest 44/44 不回归(driftArena 75),但 LIVE 证伪:** dig 触发了(block-less-dig fires **0→213**),**可是同 Y foot-block dig 是错的 actuator**——浮力 bot 把 foot 层挖掉后**下沉进挖空格、在更低处再 wedge、再挖**=往下挖坑;riser 闩死在 `2359,62,1863`(够不到了仍 latch)213 次空挖,bot 从 y62 沉到 y59,**停顿 2.5+3.7s 反而恶化成 11.2s、全程 20s→31s**。video 也报「卡在地下泥土矿洞」。**已 `git checkout` revert 回 f4da16f(干净,重编译过)。**
**下次正确方向(别再挖 foot 坑):** ①规划层——`canStandAt`/move 生成时,浮力无方块 bot 不该把平 Y 节点路由到水面岸 face 后(不可达);或给这类 cell 加 reach/penalty。②执行层若要救,**不是挖**:把够不到的平 Y 节点判「passed/skip」+ 标 avoid-point 快速重路由(不 gouge 地形),或在**水面层**开通道让浮力 bot 平游过去(不在 foot 层挖坑)。trigger(horizWedge 检测)本身是对的、可复用;**坏的是 dig actuator**。

### ⭐✅ 真根因纠正 + 修复落地:水中 yaw 振荡(master b30ab05)
archive WalkerSample 推翻上面「face wedge/dig」诊断:停顿处 `aabbOverlap=false`(无碰撞=无岸 face)、浮水 STANDING。真相=**flat 水节点上 yaw 振荡**:stall 窗 yaw 摆 96-176°、净速 0.37-0.88 b/s;同路径 heading 稳定段(摆 22°)=4.6 b/s。yaw 摆抵消前进推力→「原地不动」=停顿,**同根因也是 pathChart maxYawErr180 + 视频镜头跳变**。修 b30ab05:flat 水节点在 noStepProgressTicks>15(~0.75s)时也用宽 aim dead-zone(CLIMB_AIM_DEADZONE_SQ=4)锁 heading;门控 no-progress 保 climb-out 精度(无门控放宽→noBlockArena 回归)。**GameTest 44/44 + live 停顿 2→1、6.2s→2.1s(-66%)、yaw 振荡 3.5-6→1.6°/tick、零误挖、SUCCESS**。残留 1×2.1s = slow-progress 振荡(缓慢推进重置 noStepProgressTicks→门控漏)。证伪:放宽推广到「无 imminent climb flat 水」→ waterClimbOutRouteArena 回归(漂离 +1 出口),太脆弱 revert。

### ⭐✅ carrot-swing 远 aim 稳定(master c733df7)
b30ab05 残留的 aim2>deadzone carrot-swing(LOS 被岸挡→carrotPoint 坍缩近节点→短 aim 矢量随浮水漂移快转、bearing 摆 96-176°)。修 c733df7:水中按 raw carrot bearing 反向次数累衰减 `yawThrashTicks`;分数≥6 且前向 lookahead 段无爬升节点(climbAhead=false,保 +2 climb-out 挂载精度)时 aim 到 step+3 远节点(长矢量 bearing 稳)。GameTest 44/44(waterClimbOutRouteArena maxY221 不回归)。LIVE flat-水 carrot-swing 簇 3 窗/6.5s→1 窗/2.2s。

### ⭐ 真根因再纠正(逐tick walkerDebug+archive 实测,2026-06-15):水谷打转 ≠ 「+2 climb-out 执行卡死」
**z1881 南向 climb-out 复现(TP 2361,66,1895→goto pos 2361,64,1860,walkerDebug+archive)定根,推翻 §「残留真凶」整段的「step 冻结在浮力 bot 上方 +2 节点 / climb-out 接管该 engage 却被搅乱」理论**:
- **climb-out 执行器(pillar/dig takeover)整程零触发**(walkerDebug 1635 行,climb-out/bank dig/pillar/topped 行 = **0**)。A* 没路由「浮力 bot 上方 +2 竖直节点」;它路由的是**可走的 stepUp 楼梯**(y63→64→65,move=stepUp 链)。climb-out 执行器**正确地保持关闭**(不需要)。
- **真打转 = 干地 stepUp 楼梯的近节点 aim 振荡**。archive 三停顿窗:①(2362,62,1885)inW=true onG=true=2 深水里**贴床趟水慢**(~0.11 b/tick,node 是 19 格外的平 y62 节点);②(2359,63,1864)onG=false inW=false **yawRange=405°**=干地 stepUp 链上,`aimAtWaypoint=wp.y!=foot.y` 对每个 +1 近节点(cur2 0.5-1.4)snap→短水平矢量 atan2 随 step bob 摆→多级楼梯累成 360°+ 镜头转(climb 本身在进展);③(2361,68,1860)=到达 goal 旁的 idle 抖。
- **结论:这不是水域/climb-out 执行域的系统大工程,是通用「干地 stepUp 近节点 aim 振荡」**(=旧 §残留小项的「z1987 干地 stepUp bob」同根,但严重度被低估)。

### ⭐✅ 干地 stepUp 楼梯镜头打转根治(master 5a0ca93)
**先证伪 hold-yaw 版**:`landStepUp` 用 CLIMB_AIM_DEADZONE_SQ 冻结 heading → **buoyantwallarena 回归**(冻结的 heading 钉死一个错方向,+5 水岸出水 stepUp 顶岸失败 maxY211.25)。**冻结 heading=错路**(=旧 §「4 次 forced-heading 都 revert」同坑)。
**改 bounded-lookahead(过)**:land + 平缓 +1 step,且水平已在该 step 节点 1 格内、还有后继节点时,aim 改指 **step+1 节点**——给真实「朝楼梯上方」稳定 heading,不冻结(故不钉死错方向;+5 水岸出水后继是前向 ledge walk,只会更稳)。排除 parkour + >+1 跳。
**验:GameTest 44/44(含 buoyantwallarena)。LIVE z1881 南向 climb-out:干地爬楼 mean|dyaw/tick| 405°区间→0.50°(max 8°=slew 上限单次平滑转)、29s→20s、停顿窗 3→1(仅到达 goal 旁 idle settle)。video:过岸「平稳前进、无停顿或抖动、移动轨迹平滑」。**

### ⭐⭐✅ 端到端全程验证(三修齐):165 格水谷 0 停顿
**TP (2356,67,1986)→goto pos (2350,64,1820)[snap→(2348,64,1820)],全 165 格 N→S 多段水域 + 上岸台阶。archive:outcome SUCCESS、67s、z1986→1820 端到端到达、**停顿窗=0、0.0s**、whole-journey mean|dyaw/tick|=1.55°(max 88 单次 slew)、干地 onGround mean=2.19°(无 405° 打转)。video 双通道:journey 中段 NORMAL「沿峡谷水渠持续向南移动、行进路线连贯」「沿河谷水域持续向南推进并顺利上岸」。** vs 会话初 58s 停顿 / 上一条 video run 同路 4 窗 8.8s → **本条 0 停顿**。三修累积(b30ab05 宽 dead-zone + c733df7 far-aim + 5a0ca93 stepUp lookahead)把水谷停顿簇清零。
### ⭐ 随机长途多实验(部分 clean,但暴露真残留)
- **J0 全 N-S 水谷 165 格**:0 停顿、SUCCESS。
- **J1 东向上山 113 格**(含 parkour2/3 爬坡 y64→74):**0 停顿窗**、SUCCESS。
- **J4 山顶→水谷 90 格**(y79→63):1×1.8s(2 深水趟水)、SUCCESS。
- **J5 西向 2461→xz(2330,1740) 130 格**(干地起):**4 停顿窗 ~6.4s**、SUCCESS——①1.6s@(2461,70,1824)airborne(起步跳)②1.3s@(2392,63,1793)干地③**1.4s@(2335,62,1742)+2.1s@(2335,62,1743) inW浮水 onG=false yawRange131/96=floating-water yaw-spin**(逼近 goal 旁 +1 岸的最后一段水)。
- **测得 goto 起步 latency=0.45s(quick-start stub 工作正常,起步停顿非真问题)**;video 反复报的「数秒才动/溺水」=lag + bot 到点停在水里/墙边的 idle 误读(实测 hp/food=20 不掉血)。

### ⬜⬜ 真残留核心未根治:浮水逼近 goal 的水中停顿 —— 诊断已纠正=step-pointer thrash(非 aim-spin)
**逐 tick archive(J5 replay-0008,z1742-1743 停顿窗)纠正了「carrot-swing aim-spin」初判**:停顿处 `move=walk/parkourDescend2d1`、onG 浮力 bob 翻转、bot **仍在前进**(z 1744.3→1742.0 ~0.08/tick 慢),关键是 **step 指针来回跳 2→3→4→4→2→1→1→…→2(4→1 倒退!)**=浮力 parkourDescend 入水时**步指针 overshoot/reset 反复**→aim 追被 reset 的节点→yaw 摆 40-131°。**这是 step-tracking(overshoot-resync)域,不是 aim/carrot 域**(见 [[reference_walker_overshoot_resync_fix]])。
**❌证伪:把 far-aim 扩到 climbAhead→aim climb 节点** → GameTest **summitarena 回归**(浮水 thrash 时 redirect 把 overhead pillar 爬升 ARRIVED@y226 早停)→ **已 revert 回 5a0ca93**。教训:水中 thrash redirect 不能在 climbAhead 时 aim climb 节点(会劫持 pillar);且 J5 根本机制是 step-thrash 非 aim。
**再纠正(读 step-advance 源码 1287-1388):该循环 step 只 ++、永不回退 → J5 的 `4→2→1` 倒退是 REPATH 重置 path(adoptPath step=1)**,不是 overshoot-resync 回退。即:浮力 parkourDescend 入水逼近 goal 时**反复 repath**(每次重置 path+step→aim 追新近节点→yaw 摆 + 慢 wade)。与早先量到的「19 repath/段」churn 同源 = **规划层 repath 频率问题**(P1 候选①)。**✅拿到干净 repath trace(latest.log 未轮转过 J5)**:近 goal z1743-1746 处 repath 反复 `goalReached=true` pathLen 6-11、00:19:49 一秒内 3 次,**foot.y bob 64→62→61**→`fellOffPath`/`wedged`→`safetyRepath`(Walker:798)→line 876 kickoff,cheap search 同 tick 完成→activeSearch=null→下 tick 再 repath=churn。每 repath 重置 path+step→aim 追新 node-1→yaw 摆。
**❌证伪#2:safety-repath 防抖(SAFETY_REPATH_MIN_TICKS=10,gate `!pathBestEffort`)** GameTest 44/44+109/109 过,但 **LIVE J5 重跑反更差(4 窗 6.4s→7 窗 12.2s)**:①gate `!pathBestEffort` 没命中近 goal churn——**近 goal 的 held path 本身是 pathBestEffort=true**(horizon/eager-precompute 提交的),只有 search RESULT 是 goalReached=true,两者不同→防抖没作用于目标场景;②反而 delay 了干地/airborne transient 该有的 repath→新增/加长干地停顿(起步 1.6→4.0s、z1793 1.3→3.6s)。**已 revert 回 5a0ca93。**
**正确方向(下次)**:①治本=让浮力 bob 别误触 `fellOffPath`/`wedged`(noStepProgressTicks/wedged 逻辑:水中 bob 且净进展时不算 fell-off);②或防抖键于「search RESULT goalReached=true + inWater + 净进展」而非 pathBestEffort。**GameTest 不覆盖此长途水边 churn 场景(arena 全过仍 live 退化)→必须靠 live archive A/B 验证,别只信 GameTest。**注:残留是慢 wade/churn(bot 仍到达 goal)非硬冻结,中低severity。

### (旧标题保留)floating-water yaw-spin —— 已并入上条(step-thrash 才是真因)
**多条随机 journey 复发**(J4 1.8s、J5 3.5s、J0 残留):bot **浮水**(onG=false inW=true)逼近一个 +1 岸/climb-goal 的最后一段水时 yaw 摆 96-131°→thrust 抵消→1.4-2.1s 停顿。**诊断**:far-aim(c733df7)在 `climbAhead`(前向有爬升节点)时**故意抑制**(保 +2 climb-out 挂载精度)→逼近岸 goal 时 far-aim 关闭→carrot 坍缩短矢量→浮水漂移让 atan2 摆→spin。这和干地 stepUp(5a0ca93 已修)是同构问题的**水中版**,但 5a0ca93 只覆盖 `!inWater`。**正确方向**:把 bounded-lookahead(aim 下一节点、不冻结 heading)推广到「浮水逼近 +1 climb-goal」;**风险=aim 雷区**(hold-yaw 版曾 regress buoyantwallarena;far-aim 几何放宽曾 regress waterClimbOutRouteArena)→**必须干净逐tick复现(注意 walkerDebug 刷屏致 latest.log 轮转,需大日志/降噪)+ 全 44 arena + live video 验证**,别在 deep-context 仓促改。

## P1 — 规划层优化(用户 2026-06-16:「规划层也是时候优化了,删掉延后描述,随时可做」→ 不再 deferred)

**⭐ 现状更正:渐进式寻路已 substantially 实现**(见 memory [[project_progressive_pathfinding_idea]]):quick-start stub(短搜立即交段 this very tick)+渐进水面 bee-line stub+eager precompute(从 commitEnd 预搜下一段)+horizon/soft/frontier commit+idle-slice。随机长途双段均 0 执行停顿到达 → 规划层无阻塞性 bug。**剩下是效率非正确性。**

**具体优化候选(需先量化,再谨慎动 A* core;A* core=雷区,必 arena+live video 双验):**
- ⬜ **repath 频率**:单段 journey ~19 次 repath(多为 eager best-effort 从 commitEnd 预搜,2s 内提交 4 次几乎相同路径)。当预搜结果与现路几乎相同/现路仍 valid 时跳过重提交,省 CPU、减微停顿。
- ⬜ **goto 起步 latency**:量化 goto-issue→首动 tick 数(quick-start stub 已缓解,确认是否仍有可感停顿);若大,强化 stub 触发。
- ⬜ **far-goal 路径质量**:长途绕路/best-effort 段是否最优。

### ⬜ 残留小项(执行层,效率非停顿)
- z1956 首次入水 ~2s 水中绕圈(pure-pursuit overshoot 浮水)、z1885 2 深水趟水慢——本条 165 格全程已 0 停顿窗,被吸收/不再触发停顿阈值。

### ✅ video 双通道已恢复
video 转录端点已恢复 HTTP 200,本会话 stepUp 修 + 全程验证 + J1 均跑了 video 双通道(过岸 NORMAL「平稳前进/向南推进/顺利上岸」)。注意:bot 到达 goal 后 idle 停墙边时 run.py 会把静止画面报 ANOMALY「卡在墙前」——那是到点静止非寻路异常,验收时按位置/archive 区分。

### ⬜⬜ P1 新真根因(2026-06-16 随机长途 J1 跨水):XZ 水域横穿下潜河床 = best-effort partial 在 budget 耗尽时 h-主导提交深节点(非 cost-model)
- **复现**:goto xz(2480,1640) 从陆地 (2331,64,1746) 起,跨第一片水域时 bot 一进水**直接潜到 y54 河床**、沿底 swimTraverseBreak/diagDown、水下挖沙、对岸陡坡爬不上、整程 22s(442 tick)在水下、2 次镜头大摆(≈300°/96°)、然后 repath 爬回水面 y62 才平游。video 双通道全程 ANOMALY。archive replay-0002 段节点 `submergedEye:True` 连片 = **计划就走水下**。
- **证伪修(未提交已 revert)**:`waterCellTax` 二元 +80 submerged surcharge → 改 ×submersion depth(数 foot 上方水格 cap6)。GameTest **109/109 全过含 6 水 arena**,但 **LIVE 完全无效**(重跑 entry minY 仍 54、水下 442 tick)。
- **根因确诊**:`lastPath expanded=6000`(节点预算耗尽)+ goalReached=false → A\* 交 **best-effort partial**;选点 `bestSoFar=argmin(h + g/COEFFICIENTS[i])` 大 coeff 把 g 除没 → **h(纯 XZ 距离)主导** → 下潜到 XZ 更近的深节点 h 最小直接胜出,**g-cost surcharge 被除掉对 best-effort 无效**。远 XZ 目标(175 格)超 render → 恒 best-effort partial,所以「partial 走深」稳定复现。
- **正确下一步(规划层,arena+live 双验)**:①best-effort/bestSoFar **只在浮力可达(水面/陆地)节点里挑**,排除深潜节点(类比 water-start 的 `bestAshore`);②或节点扩展时**浮力可达性过滤**(陆地起点穿水不把深河床当可站);③或深潜节点加 **H 惩罚**(让启发式本身反映「浮力到不了」)。
- **教训**:per-cell g 税救不了 budget 耗尽时的 h-主导 partial 提交;GameTest 全过 ≠ live 有效(本会话第 3 次 GameTest-pass/live-fail,前两次=debounce/aim-extend)。
- **次要残留**:近 goal 浅水/沙滩**绕圈** ~35s(goal XZ 在水/未载入对岸,贴水边 gdist 31↔39 摆才挤过去)。
- 工具:`scripts/journey_runner.py`(位置稳定轮询的链式 journey runner,免 cancel-storm 误报)。
- **本轮净提交=0(诚实负结果),树洁净 HEAD 00b8dcf。**

### ⬜ P1 水域横穿:第 2 次尝试(buoyancy planning gate)也证伪 → 真修在执行层(swim-up)
- 实现 `divesPastSurface`(PathFinder 硬 admissibility gate:XZ goal 下潜 move,`to` 上方 3 格全水=水面够不到→拒绝;ignoresY+diveGoal 豁免)。修正版(查「水面是否够得到」而非绝对深度计数,避免深水探针饱和)**GameTest 44/44 全过**,但 **live 更糟**:bot 在土墙陡岸 y51-53 卡 ~40s + 水底挖泥(baseline 是下潜后 ~50s 自己游过)。
- **真根因**:bot 从 y64 悬崖 stepDown 进水靠**下沉/坠落物理**直沉 y59→y51,planning gate 拦不住物理下沉;又禁了 A\* 规划顺陡岸绕行 → 岸底 wedge。
- **真修方向(执行层)**:浮力 bot 在深水(eye 远低于水面)时 Walker 主动按上浮键游到水面,再按 path XZ 在水面推进(忽略 planned Y 深度)= autoSwim/swim-up 域。次选:规划层选更平缓入水岸。
- **本会话水域横穿净提交=0**(cost-surcharge + planning-gate 两次均证伪 revert)。GameTest 全过≠live OK(第 3、4 次)。树洁净 HEAD 00b8dcf。

## 2026-06-16 随机长途 787 格旅途 SUCCESS + 干地对角楼梯 YAW 锯齿根因(数据坐实)
- **旅途**: goto XZ(1850,2150) 距起点(2482,1681)~787 格,跨山/水/林。poller 报 ARRIVED t=293s dist=3.3。全程强力净推进,所有视频异常均为 transient 边界 episode 并自行恢复(逐 tick 采样佐证:水中游动 yaw 恒定 48°)。**端到端成功**。
- **archive 定量(replay-0002-...073032.json, 8679 ticks)**: mean|Δyaw|/tick=2.06°(整体丝滑)。最严重 yaw 抽搐窗**几乎全在干地 inW=False y76-92 的陡对角爬山段**(rev=5/20t、sum|Δy|~120°),唯一水窗是起点 launch。
- **隔离重跑爬山(TP 山脚→goto 山顶,3Hz 实测 pitch+yaw)**: **pitch 全程 = +0.0**(纹丝不动)→ 视频「仰视天空↔地面」是误读(浮空岛 scenery+yaw 扫视+step bob)。YAW raw 在 380-485(~100°带)反复摆,单样本 ±20-32°。
- **根因**: 对角楼梯交替 walk(aimAtWaypoint=false→carrot)/diagUp(aimAtWaypoint=true→精确近 wp)步,**aim 源每步切换**→targetYaw 跳 ±20-30°,smoothTargetYaw EMA(alpha .5)只砍一半→持续游走。carrotPoint 在楼梯上被 riser 挡 LOS(losWalkable break)塌缩成近节点,也给不了稳定远 aim。
- **FIX(Walker.java,attempting,pending live verify)**: 镜像已验证的水域 far-aim——干地爬 GENTLE 楼梯(只上、无 >2 上跳、无下降)时瞄固定远节点 path.get(step+3),forward-dot 守卫防楼梯拐弯瞄反。GameTest 守门 ascentSpeedArena + 44 arena,live 重跑爬山验 yaw 带收窄。
- **残留(未做)**: 终端 radius-0 XZ 目标在悬崖上 bob/dig 无法收口(部分是 radius-0 选择产物);anti-spin freeze 等保护是 isInWater 门控、干地近目标振荡无保护。

### 2026-06-16 续:楼梯 far-aim fix LIVE 否决 + REVERTED(第5次 GameTest过/live不灵)
- fix(干地 gentle 楼梯瞄 step+3 far 节点)GameTest 109/109 全过,但 **live 重跑同一爬山 yaw band=109°≈baseline ~100°,reversals=19,bigSwings=9,无改善**。爬升段 yaw 仍狂摆(36→71→68→104→48...±35-56°)。**已 git checkout revert,树回 00b8dcf**。运行 client 二进制仍含该 inert fix(无害,GameTest 净、爬山正常),下次 relaunch 复 baseline。
- **深层根因升级**: 山路 A* 路径是**之字形**(cardinal 步逼近对角)→ 任何**单节点 aim(近 or 远)都随之字摆**;step+3 far 节点 3 步之字仍摆。水域 far-aim 有效因水路直。
- **真正方向(未做,需迭代调参+视频在线)**: 不瞄单节点,瞄**路径趋势方向**(对未来 K 节点做位移平均 / 更大 lookahead 平掉之字 / 对一致源加强 EMA 平滑)。需多轮 build+relaunch+climb-poll 定量迭代(yaw band 指标),且 pitch 全程 0 无需碰。
- **注**: 2026-06-16 当次 vision 端点 HTTP 400 连续失败,视频通道临时不可用,只能靠 RPC yaw-poll 定量;迭代调参待视频恢复。

### 2026-06-16 续2:第2次随机远途 SUCCESS + 终端 radius-0 settle 尝试(罕见/不可复现→REVERTED)
- **journey2**: goto xz(2600,2400) ~707格 → ARRIVED t=431s dist=0.6。中途一次 ~15s 水岸 climb-out 真停顿(自恢复),其余强力净推进。**两次随机远途均端到端到达**(j1 787格/dist3.3, j2 707格/dist0.6)。
- **pathChart(j1, pathchart-0002-...169.png)已读**: 轨迹紧贴 plan 直线对角,heading 实际 yaw 紧贴 target bearing,avgSpd2.72。maxYawErr=180° 峰值在终端 radius-0 绕圈缠团。**整体丝滑**。(hook 称 pathChart 未生成=误判,实际 auto-dump 在 debug/pathchart-*.png)
- **视频 over-read 再确认**: j2 多次报「高频急转/原地打转/死循环/潜水浮出」,但逐tick位移=稳定强推进 or 到达后 y 恒定 idle。**video 在浮空岛+水域地形把合法转向/idle bob over-flag 成 ANOMALY**(同 pitch=0 却报「看天」)。客观仪器(pathChart/archive/RPC poll)才是 ground truth。
- **终端 radius-0 settle fix(REVERTED)**: 加 near-goal-settle(ignoresY 目标 d≤16 且 totalTicks>100 无改善→ARRIVED,复用 bestDistToGoal 单调 bob-proof)。GameTest 109/109 clean。但**3 次 live repro(水柱/干地高柱/平台)全部无法触发**——因 XZ goal 忽略 Y,bot 只要任意高度 foot 块落到 (x,z) 列即 goal.reached,「精确列任意Y都够不到」极难构造,实际 bot 都正常到达。→ **无可观测证据它触发,按 fix_verify_discipline REVERTED**。终端 churn 比判断的罕见;视频终端 anomaly 多为 idle-water-bob 误读。fix 逻辑严密+GameTest clean,待确定性 arena 触发器再用。
- **本会话净提交=0**: 价值在诊断+验证(两次旅途证明大地形整体丝滑可达 + 精确刻画残留 + 证明 video over-read),非新提交。残留真问题均为高回归雷区(水岸climb-out/climb-aim)或罕见(终端radius-0)。

### 2026-06-16 续3:settle arena 证明 settle 干地冗余 → REVERTED(关键认识)
- 建 nearGoalColumnSettleArena(5格实心柱占目标XZ列+place/break off)试图确定性触发 settle。45/45 通过,但日志 step=ARRIVED **ticks=85 < NEAR_GOAL_SETTLE_TICKS=100** → settle 路径**未执行**;且 pos y=215.3=bot 爬上柱顶在顶端够到列→goal.reached 正常触发。
- **关键认识**: 干地上 Walker 现有 best-effort/frontier-arrive 对不可达 XZ 目标**本就快速到达(tick85)**,我的 settle(>100tick)**干地冗余**;只对水中浮力 bob 终端 churn(现有 water-churn-giveup 偶尔不触发那种)有用,而那个**无法在 arena 确定性复现**(水+浮力+恰好差1格)。→ 无法可观测证明 settle 独立生效(arena 证明的是它**没执行**),REVERT。
- **47Hz 高频实测(回应「仪器太慢视频才对」)**: pitch range=0.0(视频「看天」=把浮空岛误读)、yaw 反向仅1.3/s(非高频抖,是跟山路165°平滑大转向)、水中idle 0反向完全静止。20Hz archive 终端 y冻结。**客观仪器是ground truth,video高召回低精度需逐条核实**。
- **本会话定论**: 净提交0。架构可行(两次随机远途787/707格均端到端到达、pathChart丝滑、47Hz无高频抖)。残留=水岸climb-out偶发停顿(高回归雷区)+终端radius-0(干地已被现有机制处理,水中罕见且不可确定性验证)+蜿蜒地形合法大幅转向的「镜头晃」观感+video误报。这些跨越本会话修复范围/风险/可验证性。

### 2026-06-16 续4:20Hz三重交叉定位唯一真停顿(修正前面过度乐观)
- **方法论修正**:停顿检测**没有单一指标可信**——①XZ位移:把陡爬(y92→100、XZ小)误判成停顿(假阳),也会被repath重置的step骗;②step索引:repath时重置→大netXZ的"step停"是假象;③真停顿判据=**step停滞 AND netXZ<~5 AND |Δy|<2 三者同时**。我之前8s稀疏轮询+单指标多次得出错误结论(把~100s水岸churn判成"~15s自恢复"、把陡爬判成停顿)。
- **唯一真停顿(journey2, 20Hz三重交叉确认)**: **ticks 3349-5334 ~100s 在 (2372,63,2168) 水岸**——step卡4-8、netXZ 1-9、y平62-63,持续churn stepUp/stepDown/diagDown/fall2(inW 47-93%),~100s仅漂~6格才磨出。**真实严重的水岸climb-out停顿**(=trend-aim破过的waterClimbOutRouteArena同域雷区)。
- 其余archive"停顿"全是假阳性:陡爬山(y在升)or repath重置step(netXZ大几十)。
- **下一步(需新预算)**: 建确定性**水域climb-out arena**(深水池+tall bank,bot须爬出)复现这~100s churn,谨慎修(别回归waterClimbOutRouteArena)。这是剩下**唯一的真bug**;其余"异常"=陡爬合法转向观感+video误报(47Hz已证伪)。

## 2026-06-16 随机长途#3 (2600,2400)→XZ(1820,3150) ~1080格 — 决定性诊断(archive replay-0005, CANCELLED@22min)
全程 1335s 中 **749s(56%)在停顿**。开 live video(run.py)+47Hz hfpoll 双通道核实。
- **pitch 全程恒 0**(多次 6-77Hz poller distinct=1):视频反复报「镜头高频抖动/俯仰跳变」物理不存在=over-read。yaw 0.9-1.3 rev/s 平滑跟路。**video 高召回低精度,以 archive/hfpoll 为准**(再证)。
- **Zone A 游泳"卡顿"= 误报**:walk-in-water 1.1 b/s 慢速连续推进,被我 60t/4.5格 阈值误标(非真停顿)。
- **Zone B = 真 bug:badlands 红土水盆 climb-out 死锁**。bot 在 dist 332-386 振荡 18 分钟零净进展。单段停顿 **72.5/44.2/40.1/31.6/30.6/22.6/20.6/20.3/18.4 s**。
  - **A* 两岸振荡**(seg#74/76/77):每 repath 从 bot 当前岸 fall2/fall3 主动坠回水→横游→爬对岸 y64↔71;目标在水盆外 SW,两岸交替"更近目标"→永远弹跳。复杂盆地每 repath 烧 6000–**60000**(满上限)节点→搜索冻结即大停顿。
  - **72.5s 逐 tick 铁证**:bot 死钉 (2023.3,62,2877.7) **yaw 恒=0**(朝 +Z 南岸)move=walk onGround 1↔0 水面bob，**aabbOverlap=0(非卡墙)**，step 钉2。每 200t repath 重提**同一向前顶岸路径**→破岸兜底(Task#36 node.Y==foot.Y)在此几何**未触发**→钉 65s，靠 repath 随机性 yaw 翻 304° 才逃。
- **根因假说**:浮力 bot 水面顶**高于水面 2-12 格的红土岸**(badlands mesa basin)，水平水节点 walk 顶岸 riser 浮力爬不上，bank-face 破岸/pillar 兜底在「深水盆+高 mesa 岸」未生效；A* segment-commit 被 XZ-goal 骗（两岸都"更近"）→bank 振荡。
- **下一步**：建确定性 arena（深水盆+南向 tall 红土岸 y64-71+bot 浮 y62 朝岸+goal 岸外）复现 65s 顶岸钉死 → walkerDebug 定位为何 yaw 锁 0 不破岸 → 修（破岸兜底覆盖此几何 / 无净进展更早强干预）→ GameTest 109/109 + live 重跑。**别回归 waterClimbOutRouteArena/deepWaterClimboutDriftArena**。
- 残留次要：Zone A 水中 1.1 b/s 慢（可选提速）。

## 2026-06-16 真根因确认=spinFreeze(推翻 far-aim/死区两假设)+ 修复
用 live DIAG 日志(临时加在 Walker aim 块,打 aim2/heldYaw/bearing/flag)在真盆地复现实测:
- aim2 恒 >dz(4.0)→**dead-zone 无关(假设A否)**;从无 DIAG-FARAIM→**far-aim 无关(假设B否)**。
- heldYaw 冻死常数(-167°→69° 恒 500+tick),bearing 算得对(指 waypoint)却不转,foot 钉死,noStep→498。
- **唯一能在 angleDiff 巨大时冻 p.getYRot() 的=spinFreeze(Walker.java ~2250):`overWater && repathsNoProgress>CHURN_REPATH_CAP(3)` gate 掉 yaw slew**。churn over water→冻陈旧错朝向→死推岸→零进展→repathsNoProgress 持续→无限死锁。
**修复(已改 Walker,待 GameTest+live 验+commit)**:spinFreeze 加 **target-stability 门控**——`lastAimYaw`/`aimStableTicks` 跟踪 smoothed aim;`targetFlipping=aimStableTicks<AIM_STABLE_TICKS(10)`;spinFreeze 仅在 `targetFlipping` 时冻。winding(每 repath 翻 180°)→aimStableTicks 重置→保持冻结(anti-spin 保留);本死锁(bearing 稳定)→~0.5s 后释放→capped slew 收敛转向 waypoint→逃脱。常量 AIM_STABLE_DEG=8°/AIM_STABLE_TICKS=10。
waterFarAimBankCornerArena=水域隔墙绕行 smoke test(不确定性复现 spinFreeze,真验证靠 live 真盆地)。
**教训**:连续两次静态分析假设全错,DIAG 日志是关键。水域 yaw 冻结 bug 必 live 打 aim 内部状态实测。

## 2026-06-16 续5:spinFreeze 死锁 ✅修复提交 16c5a8c + 3次干净 live journey 验证
- **真根因再升级(超出续4假设)**:spinFreeze 不只冻镜头——line2579 `driveDelta = spinFreeze ? 0 : angleDiff(...)` **把驱动方向也冻成 0**→身体沿冻结的错朝向死推岸。恶性循环:冻驱动→零净进展→repathsNoProgress 持续高→spinFreeze 持续→冻驱动。这才是 18 分钟死锁的执行层闭环。
- **修复(2 处,均针对此死锁)**:
  1. **解耦驱动与镜头冻结**(line2579 去掉 `spinFreeze ? 0`):身体始终朝 aimYaw 走(Δ=aimYaw−cameraYaw,AgentInput 动态纠偏)。镜头仍可冻(防视觉转圈 winding),但身体一动就有净进展→repathsNoProgress 重置→冻结自行释放→循环无法形成。
  2. **target-stability 门控镜头冻结**(aimStableTicks/AIM_STABLE_DEG=8/AIM_STABLE_TICKS=10):仅在 aim 目标真翻转(winding)时冻,稳定~0.5s 释放。
- **验证**:GameTest **109/109**(水域 climb-out arena 全过,无回归);**3 次干净 live journey**(replayMode 清后):
  - #1 (1853,3000)→(2298,2703) 580格干地:mean|dyaw|1.92°、yaw span 456(非winding)、最长停顿1.3s、到达。
  - #2 →(1960,2884) 西渡 lake 7%inWater:**水中 mean|dyaw|2.64°、in-motion >90°flips=0(零winding)**、最长停顿1.5s、穿水到达。
  - #3 →(2400,2400) 700格:mean|dyaw|**1.16°**、终端 last4s net19/yaw125=clean、到达后 hfpoll 8s **YAW=0 完全静止**。
- **复现教训**:'AgentTest' world **跨 reload 非确定性**(地表整体 y≈-60 低海平面,原 basin 的 y57-77 抬升岸 reload 后消失)→mc.debug.replay 恢复悬浮岛 bot 穿地坠 bedrock、TP 复现全失败。basin 抬升岸 climb-out 自然复刻不可得→靠 GameTest 抬升岸 arena 覆盖。
- **video backlog 教训**:vLLM 恢复后积压致视频报告滞后数分钟,大量"村庄打转/静止"是**旧帧滞后误报**;追上后与 hfpoll(YAW=0)一致报"静止"。再证客观仪器(archive/hfpoll/RPC)是 ground truth。
- **残留(非 spinFreeze 域,独立)**:终端 radius churn——goal 不可达 near 时终端 settling 偶有~2.5s/392° 打转 burst(间歇:#2 有、#3/re-test 无),干地非 overWater→与 spinFreeze 无关,属 task#35 settle 域,settle 修曾 REVERTED(不可确定性复现)。

## 2026-06-16 续6:video 通道真根因=qwen小模型 → 换 gemini 修好 → 双通道清洁达成
- **续5「video 不可靠」结论被推翻**:video 所有假异常(把 net75格/窗移动报「静止」、对 hfpoll YAW=0 的 idle bot 报「180° winding」)真因=**OPENAI_MODEL=qwen3.6-27b-nvfp4 小视觉模型能力不足**。
- litellm 端点有强模型(gemini-3.1-pro/flash-preview、claude-opus-4-5、gpt-5.4…)。改 run.py line53 `MODEL=os.environ.get("OPENAI_MODEL") or ENV["OPENAI_MODEL"]` 后用 `OPENAI_MODEL=gemini-3-flash-preview` 启动。
- **gemini 完全正确**:移动报 NORMAL 且描述精确(「红沙纹理向后滑动、陶瓦山体相对位移=稳定前进」「转向轻微平滑、无停顿无打转」),idle 报「静止」,双向准。
- **✅双通道清洁 journey 达成(满足验收)**:replay-0008(2197,2741)→(2040,2860)穿平原/树林/水域/badlands:**archive mean|dyaw|2.30°、flips>90=0(零winding)、最长停顿1.3s、到达** + **gemini 全程 NORMAL=12 / ANOMALY=0**。另一程(→2200,2740)gemini 亦全 NORMAL。
- **教训**:① 视频验证前确认用强视觉模型(gemini/claude/gpt),别用 qwen3.6-27b;② 平坦无特征地形仍是视频弱区(第一人称水平视角画面变化小),走有地标地形(badlands/树林/水域)最可靠;③ gemini-3-flash 比 16s 窗稍慢会积压滞后,可接受。
- **本次寻路丝滑回归收官**:spinFreeze 死锁(16c5a8c)+ 之前各项水域/yaw 修复 → 多次随机长途双通道(pathChart+gemini video)均无异常、端到端丝滑到达。

## 续7 (2026-06-16) — 回放工具修成可忠实复现 live (a44c3e5)
- ⚠️ 揭穿:上轮"丝滑验收"跑的是 AgentTest **超平坦**世界(无效)。改用一直在测的 **Mountains 存档**(真山地),bot 在水边 climb-out **完全不丝滑**(用户:一直试跳1格岸)。
- 用户指令:录制/回放工具就是为复现 live 而建,用不了先修工具 → 修 4 部分:
  - A/B: EnvelopeCell 加 state(全blockstate SNBT)+nbt(block entity);ReplayTool 全保真还原,v1档fallback。
  - C: envelope 采样 dy 加深到 -3(实心地板,自包含还原不穿地)。
  - D: replan(默认)忠实重跑——还原地形+TP起点+从header重建原goal+正常规划(A*确定性→精确复现live涌现bug)。
- 验:GameTest 109/109(含state/nbt round-trip);Mountains live replan回放 replay-0002 **精确复现水边churn同格(~1823,2738)**;v2档 state覆盖39242/39242。
- 真bug(task#42 待修):水边climb-out每道+1岸先bob-stall+挖岸数秒才脱困=慢且丑,规划层应优先平齐出水口(flaky waterClimbOutRouteArena 域,待de-flake)。

## 续8 (2026-06-16) — 水边 climb-out 源头修复:浮力门 (ef3e497)
- 用户「从源头修,规划不该给出这样的路径」。三层根因:canStandAt 把任意水深当可站立 + Move 不查浮力 + 水域cost多只对XZ-goal生效。教训(fbde0f7):别动全局canStandAt,在Move层加浮力门。
- 修:`WorldView.isFloatingWater`(水格+下方也水=深水浮着)+ StepUp/StepUp2/DiagonalAscend 从浮力水格一律 invalid → A* 走平齐 walk-out 或破岸挖到平齐(均可执行),不再产不可执行的 +1 stepUp churn。
- waterClimbOutRouteArena 更新:+1 出口结构性禁止,浮力 bot 无论 tax 走平齐(maxY≤wsurf)。
- 验:GameTest 109/109+45/45;Mountains live (1823) **沉底+bob-stall 6s+diagUp振荡 4.4s 全消失**,保持水面 y62 流过,dist 185→84(60s)收敛。
- 残留:① 深水下沉(diagDown 入深水未gate,更微妙=下降有时合法潜goal)② 终端不可达goal churn(本goal在山上radius3够不到)③ real-Walker arena flaky(并行批次共享BotConfig,待de-flake)。

## 续9 (2026-06-16) — 深水高石崖「潜底爬岸」虚构修 (0408b2e)
- 新鲜可达随机长途(Mountains (1697,2611)→(1730,3100) **到达**)在 z3022 深水岸出现 ~30s 真停顿(视频实时:岸边反复上岸失败/卡崖底死角/剧烈抖动)= task#44 在可达路径复现。
- 地形(mc.query 实测):bot 向 +z 游过 6 深水池(水面y62),撞 z3024 整面石崖(stone y60→65+),岸顶 crest y63 在水面上方+1,空背包。
- 真根因:**ef3e497 浮力门副作用**——岸顶在水面上够不到平齐口,A* 改选 `SwimAshoreBreak` 节点放 **y59 软泥**(escapeBreakCost 比 y63 石头便宜)→潜底破软泥岸;但浮力 bot 水下蹬不起 jump-mount=212 tick churn(视频:游出水面又钻回岸下挖泥)。首猜 stepUp-dive 打错(submerged-ascent 门 peak287 无改善)。
- 修 0408b2e 两道源头门(「浮力 bot 水下不能上爬」):
  - `WorldView.isSubmergedAscent`:StepUp/StepUp2/DiagUp 目的地仍全淹没→invalid(防 stepUp 潜底,defensive)。
  - `WorldView.isSubmergedFoot`=isWater(foot+2):**SwimAshoreBreak/SwimBankClimbBreak foot≥2低于水面→invalid(须贴水面 jump-mount)= 真正生效的修**。
- 验:GameTest 109/109+45/45(深水穿越 arena 无回归);live A/B z3022 **深沉 churn 消除**(水中 peak totStuck 252→43,bot 贴水面 walk+水面破岸,沿水线东移找口,翻坡到达 y84)。复现台:snapshot id=bank3022 + tp 1741 68 2986 + goto xz(1733,3090)。
- 残留:高石崖空背包**近处无出口**→沿水面东移找口仍 ~5-8s 间歇停顿(peak~162)。进一步丝滑=规划层 exit-finding 更快锁定东侧低/坡口(heavily-tuned 区慎动)。

### 续9 补 — 回归验证 + 瀑布水帘 deadlock(0408b2e 无辜)
- 0408b2e 后向 W ~480 格新长途验证:**水域穿越无回归**(深水岸深沉没复现)。
- 但 Mountains 暴露多个独立 churn 源:① 陡干山 stepUp ~15s(已知 dry-staircase 残留)② **山顶多级瀑布水帘 deadlock**:bot 在水下做 121 次 undW=true 的 stepUp/diagUp(浮力蹬不起,dY 缩不进)+ fallWater15 反复下坠,totStuck 冲 2292、围 x~1495 死循环不脱困。
- **归因:0408b2e 对瀑布无辜**——该处 swimAshore 出现 0 次,我的门要求「to+head 都水」严格条件,瀑布水柱破碎→门不 fire→A* 路径同改前。
- **更广修方向(未做,需谨慎 A/B + 动深水穿越精调平衡)**:门改「from 头淹没(isWater(from)&&isWater(from+1))就禁 ascending stepUp/diag」可同盖岸+瀑布。但瀑布是 3D 复杂 feature,禁水下 stepUp 后可能 no-path/绕行,未必修好,投机性较高。

### 续10 (2026-06-17) — z2744 descent 侧根治(4ecb972)+ 整程 replay 到达
- 用户坚持用 replay 验证(对的):`mc.debug.replay replay-0004 replan` 重跑原 journey 揭出**改前在 z2744 死锁不到达**(swimUp918/diagDown200,totStuck1154)。
- 根因不对称:Fall/FallIntoWater 早 gate「降落淹没水格」,DiagonalDescend/StepDown 没有→A* diagDown 把浮力 bot 路由进深水缝,浮起→swimUp↔diagDown 振荡。
- 修(镜像 Fall.java:39):diagDown/stepDown.valid() 加 `if(isWater(to)&&isWater(to.above()))return false`。
- **验:完整 replay-0004 确定性重跑——z2744 死锁1154→peak5、z3022 岸51、端到端到达(1732,83,3097)**;GameTest 109+45 绿;局部 A/B slotPEAK3。
- 残留 z3034 东侧出口:~20s sink(totStuck394 自恢复),机制=**A* 把 WALK 节点路由在 y55-58 池底**(非 descent move),浮力 bot swimUp↔floor-walk 振荡=535df12「A* 路由河床」残留。修方向(风险):gate Walk-while-submerged(破潜 goal 横移)或调 waterCellTax/submergedTax(heavily-tuned)。

### 续11 (2026-06-19) — 终点海湾 deepwater 潜底 churn 根治(1705097+4017fa1)+ LIVE 端到端到达
- 陡高山 churn(3f287cb/4305919/e33e6ab)修好后,残留终点海湾 deepwater 潜底 churn:bot 在 goal 前 ~9 格深湾(对岸 y64-74 岸坡)潜到 y57 反复挖沙坝打转不过岸(三通道确认)。
- 精确诊断(walker per-tick log):planner committed path = `…swimDown swimDown swimTraverseBreak`——**潜下2格 + 挖穿水下沙坝(y60 solid)**;浮力 bot 挖水下 solid 浮起离块卡死(pos 冻 60.00 数十 tick)→re-route→再潜→churn。`mc.debug.plan` chain 证从水面 reached=true 全程 y62-63 水面路可达。
- 修1 `1705097`:**SwimTraverseBreak surface-gate**——eval 开头 `if (w.isWater(from.offset(0,1,0))) return null`(头在水下=潜底挖,拒绝;本 move 是「水面 lip 凿穿」)。GT 109/109。
- 修2 `4017fa1`:**escalation 在水里也 arm**——撤 e33e6ab 的 `if(!p.isInWater())` land-gate。纠错:e33e6ab 写「depthPenalty 推 bot 下水」是反的,depthPenalty 给下潜加税=**抑制潜底**;潜底挖沙坝路已被修1 gate 掉。GT 109/109(50_scene flaky 重跑绿)。
- **LIVE 双跑(tp 1732,64,3428→goto 1730,3500):①全局旋钮 水面穿越上岸 ARRIVED;②reactive 默认旋钮=水边churn~20-25s→`anti-churn(water)→escalation ARMED`→z3433→3477 上岸 y65→z3498 goalReached=true ARRIVED,全程水面无潜底。** 复现台:tp 1732 64 3428 + goto xz(1730,3500)。
- 残留:① reactive escalation 起步 ~20-25s edge-churn(churn-window 检测延迟;真丝滑需 proactive/常开水域 escalation)② 游泳中 yaw-spin(视频「水面反复剧烈转向」)③ 翻山执行器陡面 stepUp stutter。
- **残留①试过 `CHURN_WINDOW_WATER=200`(水里 10s 窗口提前 arm)→ REVERTED**:GT 109/109 但 live 更糟,charge/back-off 频率翻倍(escapes 1→4 vs 1→2),bot 仍 churn ~45s 才过岸——arm 提前≠过岸提前,缩窗口只增扰动。真修需 proactive(进深水前预判抬高岸坡),非更快 reactive。

### 续12 (2026-06-19) — 渐进式寻路 active + PROACTIVE pinch escalation = 水岸 churn 真解
- 用户拍板「开渐进式寻路大改 / 删掉 deferred / 随时开始攻」。Plan agent 勘明:quick-start stub/水面 bee-line/eager precompute/horizon/soft-commit 早已是渐进雏形。
- **land bee-line(432b359)**:tryWaterBeeline 的干地孪生(贪心同-Y standable march),flag `pathfinderProgressive`。LIVE 验证=Mountains 太碎触发 0 次(quick-start 已覆盖平地起步),**关键否定发现:greedy coarse stub 只治平地起步冻结,治不了 pinch churn**(粗方向正指障碍、撞上就停)。
- **⭐PROACTIVE pinch escalation(2a3915e,默认开 af5bba4)= 真解**:`maybeArmPinchEscalation`(big-search adopt 点)——大搜回来 best-effort 且 commit 段 goal 进展 `<PINCH_MIN_PROGRESS(8)` 格 = planner 卡 pinch,**当场 arm 深搜 escalation**,不等执行器 churn ~20s 反应窗。健康段(horizon≈48格)永不触发。
- **为何赢过 faster-reactive**:reactive 让坏 pocket 段先提交+bot 先 churn 再 undo;proactive 在坏段提交那刻就 arm,下一搜直接出绕障路。
- **LIVE A/B(深湾 tp 1732,64,3428→goto 1730,3500)**:proactive arm @~6s(首 commit 倒退-36格)vs reactive 20-52s;**开阔水面视频转 NORMAL(起步 churn 消除)、ARRIVED ~54s vs reactive ~162s(~3×快)**。GT 109/109(flag ON 也验)。
- 残留:对岸岸坡/沙坝近处独立 pre-existing pinch(「沙丘横移/水下死角」);land bee-line 留作平地 biome 件。

## Schema 单源校验 backlog (2026-07-10, master d149da5)

- [ ] mine/goto 的 `radius` schema 收紧为 `integer(1,64)`(现为宽松 number;route 实际按 int 半径用)——单独 conformance 小扫。
- [ ] 未来 conformance 清扫必须 grep `resources/scripts/**` 全部 `Agent.invoke(` 调用点(playbooks/prelude,不止 agent_validation 套件)——本轮终审在 dragon.js/wither.js 抓到错键静默瘫痪(空 catch 吞 IllegalArgumentException)。
- [ ] 环境:AgentTest 世界 spawn (-301.5,94,291.5) 下方虚空柱,套件收尾 tp 必摔死 → 下轮脏状态假败;考虑 setworldspawn 挪点或补地。50_scene forceload 泄漏(终审 Minor)顺手看。

## 生存跑 gap 清单 (2026-07-11, SurvivalTest 重启跑 day1-2 实测)

- [x] 🔴 **walkerWallDigFallback 无 allowBreak 门**(批修#25 已修,Walker.java:4858 现有 `&& BotConfig.allowBreak`)。
- [x] ✅**plan.acquire/recipe.resolve 木种"硬编码"oak GREEN(07-12,#37)**:根因非字面 oak 常量,是 `RecipeResolver.chooseIngredients` 只对**直接库存**(tier1 line192)inventory-aware;tier2"first craftable"按 `Ingredient.getItems()` registry 序(oak 首)取,**不看该成员自身子输入是否在库存**→有 acacia_log 无 planks 时选 oak_planks→递归缺 oak_log(acacia_log 闲置)。修=插 **tier2 `craftableFromStock`**(accepts 中第一个可 craft 且其配方有一项 accepted 输入现于 have 的成员;one-level=木/dye/石铜变体都一步到底,recursive 是 YAGNI+复刻 expand 遍历;quantity-agnostic=species 跟**在场**非丰度,不足则报对的 leaf missing 非退 oak)。**严格低于 tier1**(advisor 反例:have={acacia_planks:1,oak_log:64} 单循环会误取 oak——四 tier 保序:直接库存>craftable-from-stock>first craftable>first accepted)。孪生 `distinctIngredients:206` 同 `accepts.get(0)`=oak bias 但只喂 `pickRecipe` **scoring** 非执行/missing 路径,wooden_pickaxe 单配方咬不到→**有意保留**(此注即决定非疏漏)。TDD:pure `RecipeResolver` arena `serverRecipeSpeciesArena`(acacia_log:8→RED `missing={oak_log:8}` jobs 全 oak;fix 后 GREEN `missing={}` jobs 全 acacia)。非回归:全套件 **RPC 132/132 + serverCraftArena(oak 锚)plank=4 + 新 arena 全绿**;唯一 required fail=horizonarena(pathfinding,与 recipe 因果无关,本 session 4 跑 flaky 每跑不同 arena=既有水/寻路 flaky 家族)。**⭐LIVE truth-verify(advisor 逼:测的是 resolver 单元非 user 撞的 RPC verb,delta=readHave/serialize 信封)**:relaunch client 新 build,同 verb 直调 OLD→NEW before/after=OLD `mc.recipe.resolve{acacia_log:8}` 全 oak+`missing=oak_log`;NEW `mc.recipe.resolve` 全 acacia `missing:[]` + `mc.plan.acquire`(报告点名 wrapper)`feasible:true unobtainable:[]` 同 acacia 链。end-to-end 真绿非仅 arena 绿。
- [x] ✅**craft 不把 crafting_table 纳入子配方树 GREEN(07-12,#38)**:3×3 配方缺台时报"需要工作台"而不自动 craft 一个(台的料明明够)。根因=`RecipeResolver.expand`(:114)只把 `station(r)` 塞进**描述性** `stations` 集,**从不注入获取台的 job**,且 inventory-blind(有台也照列)。执行层 `CraftProcess.setupStation`(:161)→`findTable`(世界内已放置的台)→否则 `placeTable`(需**背包**有台 item)→两者皆无=硬 `fail("需要工作台")`。**Live 双基线定调(advisor 校正:世界台是判别器不是小事)**:Case N(无台/背包无)=fail;**Case W(旁边有放置台/背包无)=经 findTable 成功**——naive "have 里没台就注入" 会**回归 Case W**:白费 4 planks,更糟=村庄台旁 6 planks 造镐本可行却被报 `missing`→**feasible 契约由真变假**。修=**世界感知 station 注入**:①`RecipeResolver` 加 6-arg `resolve(..., availableStations)`——3×3 job 需 crafting_table 且**不可用**(不在 `availableStations`、不在 `have`、本次未注入)时**注入一次** `crafting_table` job(其自身配方是 2×2→**无鸡生蛋**),`provisioned` 标记使兄弟 3×3 job **dedup 复用**同一台;②`CraftProcess.availableStations(p,lvl)` = **单源** reach 检查(复用 `setupStation` 同一 `findTable`)——planner 与执行器必须用**同一把尺**,否则计划说"不用台"而 setupStation 却失败(或反之误报 infeasible);③`CraftProcess:121` + `RecipeApi.resolve/acquire`(经 `botPlayer()`)+ `AcquireResolver.plan` 6-arg 全部喂同一信号(⚠️5-arg 默认注入=world-blind,故**报告 verb 必须**传世界信号,否则把 advisor 警告的 feasibility 回归**引进报告层**)。TDD:`serverCraftTableInjectArena` RED(`jobs=[planks,planks,stick,pickaxe] stations=[crafting_table]`=台被列却无 job)→GREEN(`jobs=[...,planks,crafting_table,pickaxe]` 台在镐**之前**、恰 1 个、`missing={}`;+库存有台则**不**重复注入)。**LIVE 双验证**:Case N=事件流 planks(4,8)→stick→**planks(10)→crafting_table×1→wooden_pickaxe×1**(自造台后成功,原为硬 fail);Case W=两 verb 均 `TABLE STEP PRESENT? False`、craft 成功、事件流 planks **停在 8 且无 crafting_table**(零冗余台)=判别器守住。非回归:全套件 **83/84 required**(唯一 fail=`deepwaterclimboutnoblockarena`=既有 flaky 水域 ascent 家族)。⚠️**新知**:full suite 时**若 fabric client 还开着**会抢 CPU→水域 timing-sensitive arena flaky 从 1 涨到 3(关掉 client 复跑即回落 1)——跑套件前先关 client。
- [x] ✅**craft 放置的工作台用完不回收 GREEN**(#276,task#40,2026-07-12;memory `project_engine_craft_table_reclaim`)。根因=`CraftProcess.placeTable`(:263)放台后,终态(`DONE`/`FAIL`,:97-107)**只 closeContainer,从不破回**→台留原地=每个 craft 点白烧 4 planks + 世界留垃圾。
  - ⭐**安全不变量(设计的命门)**:`tablePos` **也**会被 `findTable`(:168)赋值(村庄/玩家基地已有的台)→ 那句诱人的一行修法「终态破 `tablePos`」**会拆掉 bot 只是借用的别人的台**。∴ 新增**独立**字段 `placedTable`,**只在 `placeTable` 真放成功的那一个赋值点**写入,且**只有它**可以被破。
  - 修=新 `St.RECLAIM` 态 + `BotConfig.craftReclaimTable`(default ON,四处单源:BotConfig/SettingsCommand/SettingsSnapshot/BotTools schema+doc)。终态(**成功和失败都要**——放完台才失败的 craft 一样留了垃圾)→ closeContainer → selectTool → aim+breakHold 直到块消失 → 停手等 vanilla **10-tick 拾取延迟**(否则下个 process 把 bot 带走,掉落物丢在原地)→ 收尾。**best-effort**:回收超时(200t)/破不动**绝不**把成功的 craft 翻成 FAIL,也**绝不**覆盖 FAIL 的原 error。不走 Walker → 天然绕开 #34 `mayBreak` 门(回收自己放的台 ≠ 那些门要禁的凿世界;而且把台留着才是"改世界",回收=还原)。
  - ⚠️**测试形态(advisor 定,先想清再写)**:服务端 FakePlayer **开不了菜单**(`setupStation`:172-174 capability cliff)→ 3×3 craft 必 `OPEN_WAIT` 超时 **FAIL,永不到 DONE**;2×2 从不放台 → **服务端唯一能跑 reclaim 的载体 = FAIL 路径**(正好也必须在 FAIL 回收,不是将就)。另:`ServerPlayerAvatar.breakHold` 两分支都 `destroyBlock(pos,**false**,fp)`=**dropBlock=false → 服务端破块不掉落** → arena **结构上无法**断言"台回到背包",那半**只能 live 证**(没去翻 dropBlock:会给所有既有 arena 喷掉落物)。
  - 验=`serverCraftTableReclaimArena` **真 RED→GREEN**:RED(把 flag 强制 OFF=与修前逐字节等价)`tablesLeft=1`=台被遗弃;GREEN `placedTable=BlockPos{760,221,759}`→`tablesLeft=0` + `err=打开工作台超时` **未被 reclaim 覆盖**;**(B)⭐安全断言**:预置的台→`placedTable=null`(走 findTable 借用,从不放)→`preExistingSurvived=true`=**村庄台不会被拆**(这条守住的正是上面那个"诱人的一行修法"回归,否则它**CI 全绿地**悄悄复活)。
  - **LIVE A/B(真裁判,单变量)**:B `flag=off` → craft 成功、台 **0 在包 / 1 留在世界**(复现原 bug);A `flag=on` → craft 成功、台 **1 回到包 / 0 留在世界**。事件流独立佐证:`block.break: crafting_table` + `item.pickup: crafting_table` **只在 A 出现**。
  - 非回归:全套件唯一 required fail=`deepwaterclimboutnoblockarena`(**单跑即绿**=已知水域 ascent flaky 家族,与 #38 基线同一条;craft 改动只碰 CraftProcess/BotConfig/schema,与水域寻路**因果无关**)。
  - ⚠️**顺带钉死一条 arena 基建教训(害我掉一个 debug 循环)**:**GameTestServer 的世界跨 gradle run 持久化**。本 arena 的失败态**正是**"留一张台在世界里"→ RED 跑完那张台还在 → 下一跑 `findTable` 高高兴兴**借用**它 → 不放台(`placedTable=null`)、不回收,断言照样看到 `tablesLeft=1` → **假 RED**(看着像修没生效)。修=arena **自己 scrub 场地**(新 `clearBox` helper)。凡是**可能留下方块**的 arena 都必须自清,否则测的是上一跑的残渣。
- [x] ✅**`mc.observe.player` 服务端快照只报 9/36 槽 = 服务端/客户端特性不对等 GREEN**(#41,task#41,2026-07-12;memory `project_engine_observe_full_inventory`)。**agent 看不见自己拥有什么**——36 格里 27 格(75%)对 driver 完全不可见。
  - 根因=`ObserveApi.playerSnapshot` 只有 `for (i<9) hotbar`,**从来没长出 `inventory` 字段**;而**客户端 `ClientObserve.observePlayer` 一直都有**(全 41 槽)。MCP doc 甚至把 `inventory` 明文写在 "Client-MCP fallback" 段里当**客户端独有**字段 —— **不对等是被文档承认过的,只是没人当 bug**。这是 `effects` 那次 parity slip(`ObserveApi:160-164` 的注释原话:"客户端先长出来的……**服务端快照必须带同样的字段**")**同一个错误的第二次重演**。
  - ⭐**为什么服务端那条才是要害**:客户端还有后门——开背包界面后 `mc.observe.container`(无 pos)能读全 46 个 menu 槽(实测可行)。但**服务端 avatar 没有这条后门**(FakePlayer 开不了菜单,见 #276 capability cliff)→ 对它 `observe.player` 是**唯一**的背包 verb。∴ RED **必须打服务端路径**,否则客户端 workaround 会让测试"因为错误的原因"通过。
  - 修=服务端补 `inventory`(**形状与客户端逐字对齐**:只报非空行、vanilla 索引 0-8 hotbar/9-35 主包/36-39 护甲/40 副手——同一个 verb 同一个字段两种形状本身就是新漂移)+ 新 `items`(id→count 聚合)。⭐`items` **不是新写的**:直接调 `CraftProcess.inventorySnapshot`(提升为 public)=**合成执行器数的那把尺**,服务端/客户端/执行器**三方单源**。∴ `items` 可原样当 `have` 喂 `mc.recipe.resolve`/`mc.plan.acquire`。`hotbar` 保留(纯增量,不破既有读者)。
  - ⚠️**advisor 抓到的静默陷阱(已避)**:`have` 的键必须**带命名空间**(`RecipeResolver` 用 `have.getOrDefault(itemId,0)`,itemId 来自配方=`minecraft:cobblestone`)。裸 id 的 map 会被**当成一无所有**——看着接上了,实则全空。arena 显式断言 `!items.containsKey("cobblestone")`。
  - 验=`serverObservePlayerInventoryArena` **真 RED→GREEN**:RED `invSlots=-1 items=null`(字段根本不存在);GREEN `invRows=5` + 隐藏槽 9/20/33 + 副手 40 全可见。**(B)端到端**:把 `items` 原样当 `have` 喂 `RecipeResolver`,而 stone_pickaxe 的原料(3 圆石+2 棍)**全部藏在隐藏槽** → `planComplete=true missing={}`(这条同时钉死"隐藏槽真被看见"和"键格式真能匹配")。
  - **LIVE(真裁判,纯 RPC 走服务端路径)**:`observe.player` 报 26 行/**18 个此前不可见的隐藏槽**(gravel 22/cobblestone 28/furnace 1/raw_iron 1…)。规划 A/B:**不传 have** → 9 步(叫一个**背着 208 圆石**的 bot「去挖圆石」、还挖已有的木棍);**have=items** → 5 步,冗余步全消,且树种跟着库存走成 acacia(#37 的修在这条链路上可见)。
  - 📌**#30 的出处 = 本 gap 的最早目击**(⚠️**我一度误判成"#30 是拿 25% 数据判的",纠正如下**):#30 的诊断**依据是完整背包**——它走的是 `mc.client.player.inventory`(**客户端**路径,正好有该字段),记录里甚至专门留了一句 API 备忘 *"server-mode observe.player 只给 hotbar,不含 inventory 数组"*。**也就是说这个不对等 #30 当时就撞见了,只是被当成一条"绕过去"的备忘,没立成 bug**——这才是它活到今天的原因。教训:**"绕过去的 API 备忘"就是没写下来的 bug**,下次见到就地立条目。(我误判的成因也值得记:我用**服务端** verb 读到 8 样、又从事件流看到 20 样,就**反推**当初也只看到 25%——**拿自己的观测缺口去追溯别人的结论**,正是该防的那种重构。)
  - 非回归:全套件唯一 required fail=`deepwaterclimboutnoblockarena`(**单跑即绿**,与 #38/#40 **同一条**已知水域 flaky 基线)。⚠️**过程中踩到并钉死一条基建教训**:我被 bash 超时打断的 gradle 会**留下僵尸 gametest JVM**(一度 6 个)——它们既抢 CPU 又抢 `world/session.lock`,把水域 flaky 从 1 放大到 **4**(失败集合还每跑都变=flaky 指纹,非因果回归;清干净后单调回落 4→3→1)。∴ **跑套件前先 `ps | grep [b]ootstraplauncher` 清残留**;`pkill -f` 会误杀 Bash 工具自己的 wrapper,必须**按 PID** kill。
  - 📌**`have` 默认值(#gap-B)刻意不动**:`readHave`(`RecipeApi:326`)在 `have` 缺省时返回**空 map**,而 `CraftProcess` 用真背包——"规划 verb 和执行器两把尺"。但这个默认是**文档写明的**("default: have nothing"),且 resolve/acquire 是**纯查询** verb(假设性规划是合法用途),#38 那条"报告的 plan 必须等于执行的 plan"**不能直接迁移**。且翻转它有**具体回归风险**:#37/#38 的 live 验证正是靠空默认断言的。∴ 本轮**只改文档**(两个 verb 的 `have` desc 现在明确写"把 `mc.observe.player.items` 原样传进来",并点名 omit 的后果)——可用性洞已堵,契约不动。真要翻转须另立一条,并先扫 #37/#38 的断言依赖。
- [x] ✅**工具耐久对 agent 完全不可见 GREEN**(#42,task#42,2026-07-12;memory `project_engine_tool_durability_invisible`)。**与 #41 同形的观测缺陷**:agent 看不见关于自己的、决策必需的事实。
  - 根因=`ApiSupport.itemSnapshot` 只有 `{empty,id,count}`——**一把只剩 5/250 耐久的铁镐,读出来和一把全新的镐一模一样**(live 坐实)。耐久在引擎内部**到处**在用(`AutoEquip` 的 `equipDurabilityThreshold`、`ElytraProcess.DURABILITY_MARGIN`、equip 返回的 `lowDurability`),**但一个字节都没暴露给 agent**。
  - **后果**:agent 只能靠 `tool.broke`(#26)在**事后**知道工具没了。而 #30 记录里那条 ② 的三个子项——①耐久预警换备用 ②断后自动补 ③耐久门下 abort-to-safe——**有两个需要的是"断之前"的信息**。长途挖掘前"这把镐够不够挖完 90 格"这种最基本的判断,driver **根本不提供输入**。
  - 修=新 `bot/util/ItemSnap.putWear`(**单源**):damageable 物品加 `maxDamage`/`damage`/`durability`(**剩余耐久点数** = max−damage,决策真正依赖的那个数;⚠️**点数≠使用次数**,耐久附魔下一点扛多次 → 它是剩余工作量的**下界**,文档已写明);可堆叠物**不加任何字段**(载荷不膨胀,且**"有 `durability`"本身就等于"这东西会磨损"**)。⭐**必须是 helper 不是复制粘贴**:driver 有 **6 处**手搓 item 行(服务端 itemSnapshot / 服务端 inventory 行 / 服务端 container 槽 / 客户端 hand / 客户端 inventory 行 / 客户端 container 槽),各自形状还不同(带 `empty` 的、带 `slot` 的、带 `index` 的)——**逐处复制正是一个字段长出两种含义的方式**(#41 刚被这个咬过)。六处全接上。
  - 验=`serverObservePlayerInventoryArena` 扩断言,**真 RED→GREEN**:RED(`putWear` 强制 no-op = 与修前逐字节等价)镐只有 id/count;GREEN `durability=5, damage=245, maxDamage=250`,且断言**可堆叠物无磨损字段**、`durability` 必须是**剩余**而非已损。
  - **LIVE(服务端+客户端两条路径逐字一致)**:濒断铁镐 `durability=5` vs 全新钻石镐 `durability=1561`,圆石无字段。测试用品已 `clear` 还原(生存世界保持诚实)。
  - 非回归:全套件回到已知基线(唯一 required fail=`deepwaterclimboutnoblockarena`,单跑即绿)。
  - 📌**剩下的"要不要造主动恢复 actuator"是策略不是能力,刻意不做**:通用 driver 出**原语**,LLM 出**策略**。有了 #32(工具门+可行动中止信号)+#37/#38(能自造台/树种跟库存)+#41(看得见全背包)+#42(看得见磨损),agent **已经**具备"预警→撤→重造→继续"的全部输入。**下一步应验证这条能力链端到端通不通**(⚠️**别用 #30 那个 bot**:它只有 1 木板/无原木,恢复失败是**材料**原因不是**能力**原因=假阴性;要用有木头的干净 rig),而不是在 driver 里糊一层策略。
- [ ] （by-design 记录)duskSecure 夜间不接管=正确抑制:要求 exposedAtNight+!cornered+威胁半径内无敌+idle 去抖;僵尸 11-15 格内时 "never dig under attack" 门生效,非 bug。
- [x] ~~planner **break 代价不按工具挖掘速度缩放**~~ **复核=已实现,条目过时**(2026-07-11):ClientWorldView.breakCost 早已按 destroySpeed→ticks 缩放(空手石 150t vs 石镐 12t、wrong-tool ×3、×pathfinderBreakCostMultiplier)。真 gap 是**工具中途打光后 plan 成本失真 12× 且零通知**——已由 tool.broke 事件补上(见下)。
- [x] **工具耐久静默打光**(批修#26):双镐 100+ 块后耗尽,物品无声消失,mine/goto 继续空手挖(慢 12×)+ "需要工作台"迷惑报错;用户问"你不能合成稿子吗"才暴露。修=ClientEventDetector 新增 `tool.broke` push 事件(主手 damageable 距满耐久 ≤2 且该 id 库存计数下降沿;换手/挪槽不触发),level=warning。agent 收到后应在下一次 dig-commit 前重新合成工具。
- [x] ~~mc.bot.setting 部分键**写入被静默忽略**~~ **复核=已修,条目过时(STALE)**(2026-07-12,task#39)。**不复现**:原报症状 `pathfinderBreakCostMultiplier=40/40.5 无 applied/无 rejected/echo 仍 2.5` 今日实测 = `applied:['pathfinderBreakCostMultiplier']`,值 2.5→40→40.5,snapshot echo 跟随(已还原 2.5)。**穷尽扫描(零副作用:每键回写其当前值)覆盖全部 189 个 `public static volatile` 基本类型 BotConfig 字段 → 189/189 applied,0 静默忽略,0 snapshot 不可见**。∴ 原"反射 fallback 类型/字段名问题"的归因**事实上已不成立**——`SettingsCommand:744` 反射 fallback(任意 public static volatile 基本类型按精确字段名可写)+ `SettingsSnapshot:174` 反射补全 pass 已**结构性**修掉整类问题(单点 probe 会漏判,故用全字段扫描定案)。
  - ⚠️**扫描顺带钉出真正的残留风险(另立条目,非本条)**:**未知/拼错的键被静默吞掉**。`mc.bot.setting` schema 是 `.additionalProperties(true)`(BotTools:574)+ 文档明写"unknown keys are ignored"(BotTools:576-577)=**有意契约**(189 个反射键无法枚举进 schema)。所以 `{"allowBrake":true}`(typo)返回 `ok:true`、无 applied、无 rejected、无报错。这是 **live A/B 方法论的真相完整性风险**:实验里拼错 flag → 静默永不生效 → 得出"flag 已开、无效果"的**假结论**。别的 verb 都对未知键硬报错,只有它是例外。
  - ⚠️**第二层(不同层,harness 侧)**:MCP 工具 schema 在**会话开始时冻结**,新加的 key 会被 harness **静默剥离**(见 `scripts/.claude/skills/agent-driver-rpc/SKILL.md`)——即"新编译的 flag 用 `mcp__agent-driver__mc_bot_setting` 设不上,用裸 RPC 就行"。**很可能正是原报告的真因**。∴ **新 flag 的 live A/B 一律走裸 RPC(`scripts/rpc_call.py`),不走 MCP 工具**。
  - ✅**当下即可用的防护(零契约风险,双层通吃)**:设实验 flag 后**断言该键出现在响应的 `applied[]` 里**再开跑。engine 侧加 unknown-key 守卫是候选改进,但需先解决"~96 个手写 setter 存在正是为了 legacy key 别名"→ naive「输入键 ∉ applied ⇒ unknown」会把**合法别名误报为未知键**(守卫误报 = 训练自己无视 `rejected[]` = 比不加更糟)。要做必须先扫手写 setter 键证明无误报。
- [x] 🔴 **per-goto forbidDig 只管规划层,执行层 dig fallback 照挖**(day6 live 两次实锤;**已修 gap#4,详见 memory project_engine_forbiddig_exec_leak_maybreak**):根因=NoBreak 只到规划层剪 break 边;Walker 执行层 5 个 discretionary dig fallback(deepDig/digFallbackHere/bank-dig riser 三 swim climb-out + lily-pad ram + wallDig)只查全局 `allowBreak`(pad 连 allowBreak 都不查=最漏)。修=`Walker.mayBreak()=allowBreak && !profileForbidsBreak`(profileForbidsBreak 缓存于 setSearchProfile,非每 tick 扫)门 5 site;anti-suffocation 单头格挖**故意豁免**留全局 allowBreak(死>导航偏好,pin 注释)。⭐真 RED=lily-pad(planner 当 walk-edge 非 break-edge→NoBreak 不剪→plan pathLen>0 执行器真跑真撞 pad→head-on break 真开)非 wall(NoBreak→pathLen=0 规划层就 ARRIVED at start，执行器不跑，wallDig 连 leash 保活 carrot 也触发不了→**day6 真隧道需第二 co-defect=下条 planner tunnel-preference**）。验=forbidDigPadRamArena 克隆 waterStepDownFloat 确定性 hCol pin，pre survivedA=false（LEAK）→post survivedA=true（门开）+survivedB=false（门精确不 over-kill）；serverForbidDigWallArena=诚实回归卫士（pathLen=0 clean give-up + planned dig 穿墙到站）。**零回归**（全量套件跑完；surfaceDive/underwaterBase 两 NoBreak-executor arena 绿；2 败均非 NoBreak arena mayBreak==allowBreak 字节等价+隔离绿=residue flake）。**✅LIVE A-B 已做（07-11，advisor 纠偏：pad 不是 live 载体=自然寻路绕开→用 wall 载体走真 RPC param；去 sealed campaign bot 借口不成立=gap#1/#2 都用 throwaway AgentTest）**：AgentTest 密封石隧道单 plug（唯一路径=挖穿），survival+石镐+allowBreak:true。A `goto forbidDig:true`=pathLen0 give-up 钉死 x41.59 不动+plug 保 intact+视频"静止未挖"（day6 精确反面）；B 同 rig `goto forbidDig:false`=pathLen4 挖穿 plug 到站 x48.41+block.break×2+视频"挖穿前进"=门精确。**pad arena 证 profile→gate + wall-live 证 param→profile = 端到端全绿**。plumbing 全 trace（leash re-solve profileWith 两分支保 constraints=day6 leashed 也带 NoBreak；Walker per-IntentProcess 不复用=无陈旧）。唯 defer=day6 自然 wallDig 泄漏需下条 tunnel-preference co-defect 才触发（本修已堵执行器门）。
- [x] **地表目标 goto 偏好穿山隧道**(day6 co-defect)— **task#35 结论=无新 tunnel cost 缺陷,零改**(2026-07-11,详见 memory project_engine_tunnel_preference_classified_nofix)。advisor 纠偏两轮(先撤 NON-BUG 早结论,再逼用能看见隧道的判据):clean 三 rig 证 planner 正确翻可走坡(finalCost135/1837 intact)只 sheer wall 隧道(=最便宜 break-route,合理);**live Mountains DEFINITIVE**(trajectory+block.break 判据,非 goalReached+hDelta 盲判)=`goto(30,80,-47)` 全程 feet=air+**零 block.break**+摔死=下降是**地表/空中非穿岩隧道**。∴ 未复现任何朝目标凿降/穿越隧道。day6 症状由 task#34 forbidDig(有绕道→绕行/封死→give-up/永不隧道)实际 remedy。若真机重现"稳进凿降隧道"再开(候选=boxedEscalate 触发器扩到"稳进+持续 dig+深度渐降"信号;需真 RED 先红后修)。
- [x] ✅**descent goto 走下悬崖摔死 GREEN(07-12,见下 line~299 GREEN 条:cumulative path-lookahead 武装+airborne driveF clamp+`!parkourEdge` 守卫,live A/B RED13→2)**(task#36,2026-07-11 **根因已钉死=执行器非规划器**):live `goto(30,80,-47)` 下降 massif 时以 9-14 格 chunk 坠落(y118→109→95→83→75)cumulative fall damage 死。**instrumented plan 探针(PlanProbeTool 加 maxStepDrop+yProfile,新 jar relaunch 验)确证 `maxStepDrop=4`**:planner 路由的是**完整可走 ≤4 阶梯**(yProfile 119→117→116…→80 全 ≤4 阶),NON cliff,survivableFall=22 penalize 任何 >22 drop→planner 未路由大落差。∴ **执行器漂离阶梯累积坠死**。⚠️**机制修正(advisor#N 逼读 lethalDropAdjacent 阈值)**:`edgeBrake` **不是**元凶——`lethalDropAdjacent=dropAdjacentExceeds(foot, survivableFall(hp))`(WalkerGeometry:110),满血 survivableFall≈22,而 live 致命落差 9-17 格(hp20→6≈17格坠)**全 <22→lethalDropAdjacent 从不 trip→lethalNear=false→edgeBrake 从未 engage**。真元凶=**`steepDescentNear`**(Walker:4422=`onGround&&plannedDescent&&dropAdjacentExceeds(foot,4)`,survivable-deep-drop **sprint** 刹车):grounded tick 掉 sprint,但 **gated onGround**→step-down 的**腾空 sub-arc(onGround=false)刹车失效**→残余动量把身体漂离 ≤4 计划阶梯到更深(个体可生存 9-17格)落差→cumulative fall damage 死。=**与 `deepWaterDriftLatch`(Walker:4449-4463)同一 airborne-gap**(水域 sibling 已用 LATCH 修=证过的模板)。**修方向=执行器侧**(不动 planner cost→避 [[reference-retreat-flee-off-cliff]] CWV:737-746 走回头路 A/B):给 steepDescentNear 加 airborne LATCH(镜像 deepWaterDriftLatch),跨 step-down 腾空 sub-arc 保持 sprint-drop;若掉 sprint 不足以止漂再叠动态方向 pin(需先测漂移方向 forward-overshoot vs lateral)。**RED arena 必须匹配 cumulative-survivable-drift(多级 >4 阶梯+腾空漂到更深 survivable 落差),NOT 单个 >25 致命唇**(那是 edgeBrake 路径=错机制,会 arena 绿 live 死)。**✅telemetry 确证(walkerDebug walk-keys live massif)**:grounded sprint=false(steepDescentNear working)但 `onG=false→sprint=true` **每个腾空 tick 重armed**,forward z 累积把身体走出 y94 的 19格 survivable 唇→continuous free-fall y94→y75 死;漂移=**forward-overshoot**(z travel 向;x 稳)非 lateral。**✅FIX 已实现+编译绿**:steepDescentNear 加 airborne LATCH(WalkerConstants.STEEP_DESCENT_DRIFT_LATCH=8 + Walker.steepDescentLatch field/reset/logic 镜像 deepWaterDriftLatch + BotConfig.walkerSteepDescentLatch flag default on + SettingsCommand/Snapshot/BotTools schema 单源)。**live A/B(fixed jar 单变量 toggle,同起点)结果:A flag off=复现死(y95 hp5→死);B flag on=**仍死**但 first-fall 伤 hp5→hp10(latch 确减 forward-overshoot 动量但不足)→y76 hp14 再坠死**。∴ **latch 非解**(减伤 hp5→hp10 是 confound:A 首坠18格 B 14格,非 latch 功效)。⚠️**机制第三修正(advisor 逼对齐 telemetry)**:非 forward-overshoot 而是 **lateral corner-cut**:致命唇处 pos.x=-24.5 而 wp.x=-26=**持续~1.5格偏离规划线**,turning 下降路径(wp -25→-26→-26 弯)被**切内弯**,step off 的 19格 drop `maxStepDrop=4` 证**不在规划阶梯**(阶梯在 x-26,bot 在旁边悬崖上)。=横向跟踪失败非前冲。**gapAhead 前向 pin 不行**:(a)抓不住横向偏离;(b)4-block 正常 step 与 19-block cliff 对 gapAhead 全等(都 air ahead)→去掉 `!plannedDescent` 门=每 step pin=crouch-deadlock(edgeBrake 注释所警)。**真解=非死锁机制无关式**:step-**down** 门控在"immediate-ahead drop depth vs **planned next-node** step depth"——4-step:actual==planned 不刹;cliff:actual≫planned 刹(横/前向皆抓)。⚠️坑:wp 是 lookahead(telemetry wp 一跳8格)非 immediate next node→需取 immediate 下一路径节点 y 算 plannedStepDepth。latch 保留(正确 deep-water sibling+deep-descent 外 byte-identical)但 **#36 未闭**。
⭐**执行器-vs-规划器 DEFINITIVE(read-only,确定性,advisor 认可优于 stochastic live)**:`pathfinderMaxDryFall=4` 是**硬规划约束**(单节点 drop 永不>4)。`mc.debug.plan` 探**致命 Mountains 下降段本身**(4 个 up-slope from-点→死亡落点):全 `maxStepDrop=4` biggest-drop=4,clean 阶梯 y100→74/y94→74 全程 goalReached。∴ live `node=y83 while grounded foot=y94`(11 below)**按定义**是执行器 step-skip,非规划器悬崖=**执行器侧修,零 走回头路 风险**。机制:`walkerArcLengthAdvance` 让 step 指针沿下降路**跑到身体前下方**(feet 还在顶),drive 瞄准远下节点+残余 sprint 动量→**弹射出阶梯边**累积摔死。
✅**已实现 fix(compile 绿)**:`walkerDescentStepSkipBrake`(default ON,单源 5 处 wired)。栅 raw 几何 `onGround && (foot.Y - wp.Y) > pathfinderMaxDryFall(活取非硬码) && dropAdjacentExceeds(foot,4)(真边)`→**持 vanilla SNEAK**(maybeBackOffFromEdge 钳整 movement delta:身体**不能**走出 block edge 但仍每次**下台阶 1 格**)+**同 tick 灭 sprint**。∴安全下阶梯不弹射,**永不死锁**(≤4 阶梯 wp 恒在 maxDryFall 内→永不触发;触发时 sneak 照常下降)。advisor 认可(gate 硬约束 by-definition 执行器侧;sneak-edge=vanilla maybeBackOffFromEdge 钳 momentum+input;raw 几何免 plannedDescent 依赖免 launch-tick 重分类漏刹)。latch 保留但真 fix=此 sneak-brake。
⏳**未闭=live A/B 硬门(advisor:build 解锁但 done 阻塞于 A/B)**:成功判据=**"越过 lip 继续下降"非仅 hp intact**(若含横向分量,sneak 可能防死但 edge-pin 卡→stall→repath=死修好但 pointer-ahead 未解=另立 issue 勿并入#36)。单变量只 toggle 此新 flag,latch 两 arm 恒定。两 arm 开 walkerDebug 验 sneak 在 node-far-below tick 触发+foot-Y 随后真降。**下一步:rebuild fabric jar→relaunch→bot 到 massif 顶(~y100 near (-10,-50))→goto 下 (-21,-69)→A(flag off)必弹射/死 vs B(flag on)必 survive+继续→回归 arena(cumulative-survivable-drift 几何非单>25 lethal lip)。**

⚠️**验证撞真·复现 IMPASSE(07-11 pm)**:fix 已 rebuild+relaunch(fresh code,schema key 端到端接受)。**live 复现失败**:该世界 spawn(50,91,36)→goto (-21,75,-69) 路由**不复现** prior session 的 y118→75 下降——allowBreak:true 时 goto **凿穿** hilltop(stuck digging 23,103,21 非地表下降),allowBreak:false 时 surface 探针 horizon-capped(48格)y94-96 wander 不到目标(致命段在~100格外 SW)。无原始 goto 无法重建确定性致命下降。**arena 复现也失败**:新 descentDriftArena(1-wide 直阶梯 drop=3 run=1 steps=14 深坑,真 ServerPlayerAvatar 物理,无 water effects):flag=OFF 也 `ARRIVED fellInPit=false atBottom=true`——**直阶梯执行器 track 指针良好不弹射**,brake 从不 fire(wp 从不>4 below foot)→flag on/off **byte-identical**。∴弹射需 **broad/turning slope** 几何(advisor 初判 corner-cut 横向分量 + step-skip 合流),直窄阶梯无横向 overshoot 空间不复现。
📊**当前证据态**:①机制=执行器 step-skip DEFINITIVE(read-only 确定性 planner≤4 staircase)②fix sound+advisor-endorsed+**非回归**(anomaly 外 byte-identical,直阶梯不死锁不 regress,84 required 绿)③**efficacy UNVALIDATED**(无 controlled/live 复现弹射)。**严规(Live=truth 禁 arena-绿即宣布)→#36 未闭**。arena 保留=回归卫士(fix 不破坏陡降)+ step-skip 复现脚手(待 broad-turning 几何)。
⚠️⚠️**advisor LINCHPIN(最重要结论)**:brake 的**触发条件从未被观测到发生**。gate=`onGround && (foot.Y-wp.Y)>maxDryFall`。arena 证该条件在直阶梯**从不出现**(DESCENT-STEP-SKIP 从不 log,flag on==off byte-identical)。唯一"发生"证据=**单个** prior-session 重建样本(foot y94/wp y83/onG=true)。**若真实弹射时 wp 只在身体 airborne 后才跑到远下方**(bot 走出合法≤4 edge 再摔过更低节点),则 onGround gate 使 fix=**字面 no-op**(非回归证明不了任何东西——no-op 也非回归="analytically sound,died live"陷阱重演)。**必须先 live 观测到 grounded 触发发生,才能为 fix 辩护**。
⚠️**live 复现受阻(surface-finding)**:Mountains=pathfinding **test 世界**非 SurvivalTest campaign→cheats OK(tp/effect 已验证)。但 tp 到 read-only 探针 from-点 (-10,100,-50) **落进实心岩**(窒息掉血 hp20→3)→**那些探针 cell 在山体内部非地表起点**,read-only"下降段"探针从内部 cell 规划(maxStepDrop=4 硬约束结论仍稳,但"地表下降"表征存疑)。bot 已 stabilize(tp 50,96,36 spawn 地表+instant_health,hp20 passive walkerDebug off flag=default true)。
📌**#36 未闭·当前诚实态**:①机制方向=执行器 step-skip(planner maxDryFall=4 硬顶,read-only 稳)②候选 fix 实现+编译+**非回归**(arena byte-identical,84 required 绿)③**efficacy 未验证且可能 wrong-tick/no-op**(advisor linchpin)。**下一步(需careful surface repro):tp bot 到 massif 上方**空气**let it settle 读落地真地表 Y→从真地表 goto 下降→walkerDebug 读 grounded 触发是否 fire。**

🔴🔴**DEFINITIVE 复现+裁决(07-11 pm,advisor linchpin 精确命中)**:**reusable live RED 已建**=tp bot 到真地表 (25,103,21)【自然爬到的真 surface,非山体内 probe cell】→`allowBreak:false`+`goto (-60,69,60)`【probe 该向 106→69 降37格≤4 staircase】→**flag OFF 复现 #36 症状**:下降 y103→72 途中 walk off lips **累积摔伤** hurt lost=5(8格)@ (23,92,28) + lost=4(7格)@ (-11,72,45)(37格降拆成 survivable 段没死但症状=走下悬崖摔伤,精确)。
**裁决**:walkerDebug 全程 trace 分析(933 walk-keys 行)=**grounded 触发 fire 0 tick;airborne far-below 15 tick**。远下 wp(`wp.Y<foot.Y-4`)**只在 onG=false 出现**(样本 foot y101.9/wp y96/d5.9/**onG=F**/sprint=false);grounded 时 max(foot.Y-wp.Y)=**恰 4.0 从不>4**。∴**当前 fix = 字面 NO-OP**(gate `onGround&&(foot.Y-wp.Y)>maxDryFall` 的触发条件真实弹射时从不发生)=advisor 预言的"analytically sound,died live"陷阱精确命中。
🔬**机制精修(现在钉死)**:①grounded 在 drop 顶:wp **恰≤4 below**(合法 dry-fall step)→**grounded 无异常**②bot 合法走出该≤4 edge(step-off 本身合法)③**airborne 下坠中**:drive 继续瞄 wp(step 已 advance/下节点更低)→身体**前向漂移**越过第一个≤4 ledge→continue 下坠→落更低→累积摔伤。**sprint 已 false**(steepDescentNear 已灭)→非 sprint 动量而是**前向 driveF=1 airborne 续飞**+重力。
✅**正确 fix=airborne forward-drift-kill(或 aim-clamp)**:airborne+falling+descending(wp below)+**非 parkour/leap edge**(排 descendLeap/parkourEdge/steppingOffFall 免 gap-crossing 落空 stranding)+身体大致在降线上→**钳 driveF→0**(仿 descendBrake parkour-only 的 line 4259 driveF=0,扩到 plain-walk 降)→身体竖直落到最近 ledge 再续,不 deadlock(重力照落)。当前 `walkerDescentStepSkipBrake`(grounded gate)**保留作 scaffolding 但确认 no-op/至多极陡 grounded-11-below 边角**(prior 样本 onG=T 11-below 或存在于更陡地形),**单独不足**,须叠 airborne lever。
📌**#36 未闭·下一 focused cycle**:实现 airborne drift-kill→rebuild+relaunch→跑上述 reusable RED(flag off 摔伤 vs flag on 无摔伤 survive+续降)→arena 补 broad/airborne 几何(直阶梯不复现,须 turning/wide)。bot 已 stabilize(50,96,36 spawn hp20 passive walkerDebug off allowBreak restored)。
✅✅**#36 GREEN(2026-07-12 pm,gate-fix + airborne driveF clamp,live A/B DEFINITIVE)**:诊断=门层(hSpd trace 见下 PARKED 条)→修=①**cumulative path-lookahead 武装**(steepDescentRaw 加 OR 项:`path.get(step..step+3)` 累计降 >maxDryFall→武装 latch,gated walkerSteepDescentLatch;治单边 dropAdjacentExceeds 看不见的 ≤4-per-step 累计陡坡——正是 planner 铺的几何)②**airborne driveF clamp**(repurpose walkerDescentStepSkipBrake:latch 武装 + !onGround + wp<foot→钳 driveF→0,读 latch field 前 tick 值,airborne-only 重力照落不 deadlock)。**live A/B(Mountains,regen off,同起点 25,103,21→goto -60,69,60,单变量 toggle)**:A RED(latch0 clamp0)=descent 摔伤 **13**(Fall A launch 8+Fall B 4);B arming-only(latch1 clamp0)=**9**(Fall A **仍 launch 5**——sprint-kill 单独不够,证实 advisor Q2);C arming+clamp(latch1 clamp1)=**2**(Fall A 消失,无 launch)。三 arm 全程越过每个 lip 继续下降到谷底**无 stall-at-lip**。新武装 log fire 148×(`foot y=78 pathDrop=6>4 over 3 nodes → latch 8 (noSprint+airborneDriveFClamp)`=正是 Fall B 弹射 tick,原先静默)。∴**clamp 是决定杆非 backup→default ON**。新武装 log fire 148×(`foot y=78 pathDrop=6>4 over 3 nodes → latch 8`=正是 Fall B 弹射 tick,原先静默)。**+`!parkourEdge` 守卫(07-12 pm,advisor blast-radius 命中)**:`descendBrake=descendLeap(=parkourDescend*)&&!onGround` 已钳降落 leap,但 plain `parkour*` 落更低=`parkourEdge` 却非 descendLeap→我 clamp 会新钳其起跳 drive→落短坠 gap=fix blast-radius 内新摔死;加 `!parkourEdge` 排除。validated RED launch **walk-keys 实证**(advisor 逼查 Fall A"未刻画"缺口):Fall A launch(node=25,90,28 的 y94→82)move=**fall2/diagDown**(walk 非 parkour),Fall B=forward-drift walk;全 log 有 parkourDescend2d1×250(=descendLeap,descendBrake 本就钳)+parkour2×41,但 **41 parkour2 全簇在 valley 终段 node=-56,63,52(y62-64),两处致伤 fall 零 parkour**→守卫只改 valley-approach 跳(那里正是 advisor 忧的 short-landing,修对)从不碰两处已验 fall→**"2" 携带到守卫版**。narrowing clamp 只加 driveF 从不减。**非回归(守卫版,跑 3× full suite)**:RPC/schema **132/132×3**;**descentDriftArena `ARRIVED fellInPit=false atBottom=true`×3**(#36 真正触及的地形 arena)。⚠️**订正旧"84 required 全绿"=那是一次幸运干净跑**:现每跑 required 恰 1 flaky fail=**水域 climb-out ASCENT arena、每跑不同**(run1/3=buoyantwallarena,run2=deepwaterclimboutnoblockarena,互跑即绿,buoyantWallArena 注释自承"cross-run jitter/intermittent")=既有 flaky 水 arena 家族,**与 descent-only clamp 因果无关**(clamp 只在 `wp.Y<foot.Y` 降时武装,ascent climb-out 从不触发;narrowing 只加 driveF)。诚实=**83/84 required(1 flaky ascent-water/跑),descent 相关零回归**。optional fail=vineOverWaterClimbArena 恒定(required=false MUST-FAIL,-711 未修,非我引入)。⚠️下游:goto 到谷底后 jungle canopy+water(allowBreak:false)stall 是**独立既有问题**非 descent(falls 全在 y103→72 段)。教训:sprint-kill 减伤但不阻 launch(residual driveF=1 airborne 续飞)=advisor Q2 精确命中;driveF clamp 才是真解;声称"全绿"前须多跑区分 flaky。
🅿️🅿️**#36 PARKED(2026-07-12,hSpd trace 逐 tick + advisor 校正,取代上文"机制精修/正确fix")**:分析 flag-OFF RED trace 两处 hurt 的 `hSpd`(advisor 决定性判据=grounded walk-off 速 vs airborne 速):**①Fall B (-11,72,45,lost=4)=确证 forward-drift**:连续 airborne 坠 y79→72,**hSpd 上升 0.21→0.23**,**sprint=T 全程**,wp 前下 tracking=原机制确证。**②Fall A (23,92,28,lost=5)=未刻画**:narrow z/x band 把真致伤落地 tick 裁掉(拉到的 3-4格片段+中途 onG=T touchdown 产不出 lost=5≈8格连续坠)→**别据片段下"vertical collapse"结论**。**⚠️内存/上文前误纠正**:"sprint 已 false(steepDescentNear 已灭)"**错**——sprint 在 Fall B **全程 T**(steepDescentNear **没灭它**),仅 Fall A idx121 灭。**∴真缺陷=GATE 层**:两个 descent brake(`walkerDescentStepSkipBrake` grounded gate=fire 0tick no-op;`walkerSteepDescentLatch`/steepDescentNear=同一降坡 Fall A 灭 sprint、Fall B 不灭)**都 geometry-gated 时灵时不灵**→反复失败模式=**门不触发**非 brake 动作。**处置**:`walkerDescentStepSkipBrake` default **翻 false**(杀 confirmed no-op;advisor:flip 足矣**别做重代码删除**),5处 wiring+`descentDriftArena` 保留作 non-regression guard,**不 relaunch/不 A-B**(no-op flip 无可验证)。**下一 cycle 入口(别加第三 brake,盲加同样 no-op)=诊断门**:diff Fall A(idx121 sprint 死)vs Fall B(sprint 全程 T)——同一降坡为何 steepDescentNear 一处 fire 一处不 fire?门修好后若仍需 brake 动作再谈 airborne driveF-kill。**教训**:narrow filter 会裁掉真事件 tick(致伤落地不在拉取窗内);"heterogeneous/no-lever" 会埋掉真 lead(数据其实指向清晰=门问题)。
- [ ] mc.query q='entities' **无 center 时默认 testOrigin 非玩家**(集成服务器路径):返回 [] 看似世界无实体,加 center:玩家坐标即正常。文档确有此口径但极易踩;建议 client 附着时默认玩家。
- [ ] goto/mine 等进程完成**无主动 push 事件**(process.done),现靠 wait.condition 120s 轮询兜底,超时窗口内 agent 盲等。
- [x] 🔴 **DEATH#3 runAway 摔死("hit the ground too hard")**(批修#26 已修):根因在**执行层动量**非规划层(A* 干地坠落枚举上限 5/gate 3,产不出致命落差)——`edgeBrake = lethalNear && !plannedDescent` 让计划内下降解除 sneak pin,低血量照常 sprint,残余漂移滑过安全落点入深坑连续坠落。修=`BotConfig.lowHealthCareful`(dflt 6.0 HP):≤阈值时 sprint 全抑制 + 计划内下降也保持 lethal-edge sneak pin(Walker×2 处);arena=serverLowHpEdgePinArena(2HP flee 下计划楼梯到致命唇缘,血量必须不掉)。
- [x] 🔴 **escape 静默停摆×2(实际×4)**(批修#26 已修):双根因——①`canWalkOut` 成功门过弱:任一脚侧可走即 DONE,旷洞里恒真→秒退无报告;②sealed 掩体 STEP_UP 死循环:CARVE 只清目标龛位从不清**自己头顶**(base+2),封顶跳不起→100t 超时→re-PICK→同向已 carve→无限乒乓零破块。修=EscapeProcess 挂 `state.escape` 槽(UserTaskChain.slotFor 补 case,所有出口经 done() 写 lastError)+ futility watchdog(连续 4 次 re-pick 无 step 进展→BAIL 带因)+ CARVE 链首清 startArc(base+2)+ PICK 前置 launch-arc 可破门;arena=serverEscapeSealedShelterArena(密封 1×2 土袋必须爬出到地表)。
- [ ] 环境:Xvfb :99 被其他项目的 NeoForge 客户端抢前台,推流跟着切画面——已用 xdotool windowraise 夺回;共享显示器多客户端需约定或分显示器。
- [x] 🔴 **duskSecure/bunker 封顶不验侧向围合**(批修#25 已修:BunkerProcess.nicheEmbedded 验证龛位嵌入实体;live 验证=day3 手动 bunker 后 cornered=true)。
- [x] 🔴 **bunker/duskSecure 反射失控深挖**(批修#25 已修:depth 达标即终止+cancel 抑制)。

## 引擎 gap 三连闭环 (2026-07-11, task#30 生存软死锁驱动;详见 memory project_engine_gap1/gap2)

- [x] 🔴 **gap#1 无可靠垂直上升 actuator**(已修):goto up 复用便宜 air/楼梯不挖 pillar=XZ drift 撞断原镐。修=新硬约束 ColumnRadius 剪 XZ 超径后继(distSqXZ 无 dy 项,别做球形=只剪 approach 会漏爬升)+Y 自由;剪枝在建节点前→best-effort 选择器只见柱内;goto 新 arg `column:{x,z,radius}`(caller 传自身 XZ,radius 1-2)。验=columnRadiusArena(planner)绿+**LIVE A-B clean 单变量**(A 无 column drift5.30 爬便宜楼梯横移 vs B 笔直挖上柱内 1.42+climb5.0)。
- [x] 🔴 **gap#2 MineProcess 无工具能力感知→空手徒劳磨石**(已修):空手挖 requiresCorrectToolForDrops 块=块碎但零掉落、进程永不识别徒劳、不终止(reachable 石头近无限一路挖)=软死锁第二 gap。修=`scanForTarget` 加 `canHarvest` 门(requiresCorrectToolForDrops && 全主背包 0-35 无 correct tool→跳过+记 toolBlocked)→全 tool-blocked 无 reachable→终态信号 `blocked: <block> needs <tool> — none held or in inventory`+tick() SEARCH null 优先发信号 reset 终止。保 dirt/wood/gravel/sand 空手+保背包有刀。验=serverMineNoToolArena TDD 红(server destroyBlock 瞬破→remaining0/3 lastError null)→绿(remaining3/3 信号对)+serverMineProcessArena 加镐 remaining0/3+**全量不过滤套件 3 mine arena 全绿零回归**+LIVE A-B(A 无镐 endedTick16 abort passive vs B 带镐 cobble0→2)。已知缺陷:错等级镐(木镐挖需铁镐矿)信号误说"none held"其实握着;poll-based(status/wait.condition 读 lastError,无 push)。
- [x] ⚪ **gap#3 Walker 空手挖清障是否软死锁**(调查=非 gap,不修):advisor 抓出我"代码读证终止"漏洞(假设块最终碎;空手 deepslate 300t>digAim watchdog cap 200t)。建 serverWalkerDeepslateNoToolArena(bedrock 走廊+2 格 deepslate 塞子=唯一有限路,faithfulBreak=true 否则 server 瞬破掩盖时序)→**endTick1336 ARRIVED reached=true plugRemaining0/2**:空手挖穿两格 deepslate(~650t/格)到站,0 次 dig-aim RELEASE(latch 没参与),totalTicks 全程被 breakHeld 抑制。机制:traverseBreak 持续 hold→breakProg 单调累积碎块;dig-aim latch 只补充 re-assert、其释放不调 breakHold(false)。**tool-gate 在此=回归**(Walker 只需 clearance 非 drops)。arena 留回归卫士(assert reached&&plugRemaining==0)。All 81 required 绿。
- [ ] （待 user 裁决,task#30）campaign 软死锁 bot 处置:归档转新跑 / 接受 debug-seal 恢复(违 no-cheat)/ 引擎侧续找 gap。三引擎 gap 已清=撞出这三 gap 的 campaign 缺口补齐。

## ✅ 引擎 gap #44 GREEN:planner 假设空背包、executor 用真背包 = 两把尺子(2026-07-12)

**根因**:`RecipeApi.readHave` 在调用者**不传 `have`** 时返回**空 map**,而 `CraftProcess`(真正执行这个计划的东西)
**从真背包消耗**。→ **planner 和 executor 在量同一个事实、用两把尺子**,plan 是**给另一个 bot 做的 plan**。
**这就是 #38 的同一条教训**(planner/executor 必须同一把尺子),只是**症状相反**:#38 少报活儿(craft 时才失败),
#44 **多报活儿**(叫背着 208 圆石的 bot 去挖圆石)。

⭐**RED 我早就有了却归错档**:#41 的 live A/B(不传 have→9 步 / 传→5 步)就是这个缺陷。
我当时把它写成"footgun,文档里警告一下",**其实它是默认值错了**。
⚠️更早我还给它编过一个辩护:"这是**文档化的纯查询**语义"。**查了源码,这话是错的**——同一个方法第 147 行就调
`CraftProcess.availableStations(botPlayer(), level)` **扫世界找工作台**。
station **自动读世界真相**、items **拒绝读背包** = 这 verb 从来就不"纯"。**用错误的理由搁置一个真 bug,比不搁置更糟。**

**修**:`readHave` → `public static resolveHave(Params, Player)`(可测的纯函数)。
- **omitted `have` → 读真背包**,经 `CraftProcess.inventorySnapshot`(**executor 消耗的同一个方法** = 一把尺子)。
- **显式传的 map = 假设(hypothesis),原样使用**——**包括显式 `{}`**(仍然是"假设我一无所有")。
  ∴ **what-if 规划保住了**,且**所有已经显式传 `have` 的调用者(含 #37/#38 全部测试)行为零变化**;**只有"没传"这一种从错变对**。
- 支点:`Params.has/present` 能区分"没传" vs "传了空 map"(`getMap` 两种都返回 `Map.of()`,**区分不开**——这是修法的关键)。
- null bot(headless/未加入)→ 空,不崩(与 `botPlayer()` 对 station 的兜底一致)。

**验**:
- 新 `serverPlanHaveDefaultsToBagArena`,4 条断言:(A)不传→真背包(208 cobble,且藏在**隐藏槽** 9/20)→plan complete;
  (B)显式 `{}` 仍是空假设(**若这条塌陷,what-if 就被静默吃掉了**);(C)显式 map 原样用、**不与背包合并**;(D)null bot 不崩。
  **真 RED**(把默认分支退回旧行为=逐字节等价于修前)→ `defaultHave={}` 失败 → **GREEN** `{minecraft:cobblestone=208, minecraft:stick=2}`。
  ⚠️必须打 `resolveHave` 这条缝而不是 verb 全路径:**FakePlayer 不在 PlayerList**,verb 自己的 `botPlayer()` 看不见它(#41 那个悬崖)。
- **LIVE A/B**(同一 bot、同一背包 208 cobble + 4 stick,唯一变量=传不传 `have`):
  **不传 → 5 步、零"挖圆石"**(只去挖它真的没有的原木造台);**显式 `{}` → 9 步、含 `mine cobblestone×3`**(what-if 完好)。
- 非回归:全套件跑两次,每次 **1 个 required 失败但失败的不是同一个**(`deepwaterclimboutnoblockarena` / `descentyawarena`)
  = 已知 flaky 签名;**两个都单跑即绿**(89/89)。文档(MCP catalog 两处 `have` 描述)同步改成新契约。

## ✅ 能力探针 #43:工具耗尽恢复链端到端**通了**(2026-07-12, live, AgentTest 干净 rig)

**这是确认不是发现**:各环此前已分别绿过,本次只验证**原语可组合**——而且它是 #42 写下的"下一步"。
**定位澄清**:driver 出**能力**,LLM 出**策略**。全程的每一次决策(该不该撤、该造什么、什么时候恢复挖)**都是我这个 agent 做的**,
driver 里**没有**、也**不该有**自动恢复 actuator。∴ 正确说法是"**原语可组合,没有缺失原语**",**不是**"bot 现在会自己恢复了"。

**rig(全公开披露)**:AgentTest 世界;`clear` 背包 → 给 `stone_pickaxe[damage=130]`(**durability=1**)+ 8 oak_log + 6 cobblestone;
**故意不给工作台**(逼它自造,连测 #38/#40);旁边 `mc.action.fill` 一堵石墙当矿。⚠️**没用 #30 那个 bot**:它 1 木板/零原木=**真材料死锁**=假阴性。

**只用真 RPC verb 走完(每一环都有 live 证据)**:
1. `mc.bot.mine stone` → 挖 1 块,镐断。事件通道**主动 push** `tool.broke: damage=130 maxDamage=131`(#26)。
2. `mc.observe.player` → `inventory` 干干净净只剩 8 log + 6 cobble,**镐没了**(#41:换成修前只看得见 hotbar 9 槽)。
3. 再发 `mc.bot.mine` → **工具门挡住并给可行动理由**(#32):`lastError = "blocked: minecraft:stone needs a pickaxe — none held or in inventory"`
   ——修前这里是空手把石头磨到永远不碎(块碎零掉落、进程不终止)。
4. `mc.plan.acquire {target: stone_pickaxe, have: <observe.player.items 原样>}` → `feasible: true`,5 步,
   **子树里自己塞了 crafting_table**(#38 的 station 注入:世界没台、包里也没台 → 规划造一个)。
5. `mc.bot.craft stone_pickaxe` → 事件流逐帧:planks→sticks→planks→**crafting_table 造出并放下**→石镐造好→
   `block.break: crafting_table` → `item.pickup: crafting_table` = **台子用完回收进包**(#40)。
6. `mc.bot.mine stone ×3` → **正常挖 3 块拿到圆石**。终态背包:新石镐 `durability=130`、台子 1 个在包里。
   (「世界零遗留台」是**从事件推断**的:`block.break` + `item.pickup` + 台子回到 slot 0,数量守恒;**不是扫描验证的**——那次 `mc.client.blocks` 参数报错后没重跑。)

**⚠️只跑了反应分支**(镐在第 1 块就断,压根没有"断之前"的窗口)。主动分支多出来的唯一断言是「断之前看得见 `durability=1`」,
这条 #42 已证、本次 rig 读数也直接显示 `durability: 1` → **"零缺失原语"对两条路径都成立**。

**结论**:`tool.broke`(#26) → `observe.player.inventory/items/durability`(#41/#42) → 工具门中止信号(#32) →
`plan.acquire(have=items)`(#41 单源) → `bot.craft`(#38 自造台 / #40 回收) → `bot.mine` 恢复 —— **六环全通,零缺失原语**。
∴ **task#30 的引擎 gap ② 由累积闭合**(gap ① 早由 #31 ColumnRadius 闭合);#30 剩下的只有 **bot 处置**这一条 user 裁决。

**诚实记录的噪声**:live-screen-watch 报了 3 条 ANOMALY("完全静止疑似卡死"),**全是误报**——那是我在 RPC 之间轮询/思考时 bot 合法待机
(driver idle 必须 passive),同期 `block.break` 事件证明该挖的时候在挖。教训:探针类协议的 context.txt 静止阈值要比模型的 16s 窗口宽。

## ✅ 引擎 gap #45 GREEN:melee 攻击冷却对 agent 零暴露(2026-07-12)

**根因**:vanilla 按冷却条缩放近战伤害(挥早了只打出条子那个百分比,且暴击必须满条)。引擎**一直知道**——
`CombatProcess:238` 每 tick 读 `getAttackStrengthScale`,压着不挥直到满条,还按 0.85-1.0 预跳骗暴击——
但**没有任何 verb 报过它**,而 `mc.bot.attackEntity` 文档自己写着"No range/cooldown check"。
∴ 自己驱动战斗的 agent **每刀打在 40-80% 伤害上而不自知**,并且会把它误读成"这怪太肉/武器不行/打不过要撤"。
**与 #41/#42 同形**:引擎用某个事实做出正确决策,却给 agent **零字节**。

**⭐先排掉 parity slip(#41 教训)**:#41 是"客户端有、服务端没有",客户端还有开 GUI 的后门,所以 RED 必须打服务端。
本次**先查了 `ClientObserve`**:它**也没有**这个字段 → 这是**真空洞**,不是不对等。(不查就写 RED,可能红错一边。)

**修**:新 `AttackSnap`(与 #42 `ItemSnap.putWear` 同族的单源工具类)→ `observe.player` / `client.player` 各加一次调用,
字段 `attack:{strengthScale, ready, cooldownTicks, fullCooldownTicks}`。
- 纯**公开 API** 推导(`getAttackStrengthScale(0)` × `getCurrentItemAttackStrengthDelay()`)→ **不必再碰**
  `ServerPlayerAvatar` 那个反射的 `attackStrengthTicker`。
- `ready` 就是 CombatProcess 挥砍的那个门(scale>=1.0);`cooldownTicks` 是**可以直接拿去等的数**;
  `fullCooldownTicks` 让 agent 能**比较武器**(剑 13t vs 斧 23t)而不是靠挨打去猜 DPS。
- catalog:`attackEntity` 那句"No cooldown check"改成**指向新字段的可行动指引**("连点不是更高 DPS,是同样 DPS 更差命中")。

**验**:
- `serverAttackCooldownArena` **真 RED→GREEN**(RED=摘掉字段 → "carries no `attack` field at all")。
  打 `playerSnapshot(ServerPlayer)` 缝(FakePlayer 不在 PlayerList=#41 悬崖);`ServerPlayerAvatar.step()` 当时钟。
  4 组断言:挥后条空 / 单调回充且 `ready` 恰在 scale>=1.0 翻转 / 满条**恰好**在报出的 period tick 到达 / **跟随手里的武器**(斧慢于剑,防止字段是个常数)。
- **LIVE**(纯 RPC,服务端+客户端同一时刻):空手 `fullCooldownTicks=5` → 装铁剑 **13**(与 arena 逐字一致);
  **挥砍后立刻 `strengthScale=0.16 / ready=false / cooldownTicks=11`** = 连点第二刀**实打实只有 16% 伤害**——修前这一整条曲线 agent 一个字节都看不到。
- 非回归:全套件 required 89→**90**,唯一失败 `buoyantwallarena` **单跑即绿**=已知水域 flaky 家族(每跑受害者都不同)。

### 🔴 副产 gap(**未修**,#45 arena 撞出来的,已单独立项)
**服务端 avatar 的装备属性永远是陈旧的**:`LivingEntity.detectEquipmentUpdates()` 是 private、由 `Player.tick()` 每 tick 调,
而 `ServerPlayerAvatar` **只跑 `baseTick()`** → FakePlayer **手里拿着铁剑,属性却还是裸手的**
(实测 `fullCooldownTicks=5` 而非 13)。∴ 服务端 avatar 的近战**按错误节奏挥**,更糟的是 `ATTACK_DAMAGE`
大概率也停在基础值(=拿着剑打出拳头伤害)。**#45 的 arena 里我在测试侧手动同步了修正器**(`equipMainHand`),
**没有**顺手改引擎——那是另一个 bug,要有它自己的 RED。

### ⚠️ 基建教训(又踩一次,这次写死)
live 一开始读到 `NoClassDefFoundError: AttackSnap`,我一度怀疑 dev-jar 烘焙。**真相是僵尸 JVM**:
39801 端口的 owner 是**一个更早启动的旧客户端**,我新起的两个客户端根本抢不到端口。
⭐**`ps | grep bootstraplauncher` 抓不到 fabric 客户端**(它走 **knot**)——所以过去那条"按 PID 杀"的清理法**漏了一整类进程**。
∴ 清理必须 `grep -iE "[k]not|[b]ootstraplauncher|agent\.rpcPort"`,并且**用 `ss -lptn 'sport = :39801'` 认端口 owner**,
别信"我刚起的那个就是在答话的那个"。

## ✅ 引擎 gap #46 GREEN:服务端 avatar 拿着剑打出拳头伤害(2026-07-12)

**发现于 #45 的 arena**(它当时只是"攻速读数不对"的一个副作用),**先测量后设计**(advisor 逼的:我原话是"ATTACK_DAMAGE **大概率**也错、护甲可能不减伤"= 又一次未验证的臆断)。

**测量**(`serverAvatarGearScopeProbeArena`,断言的是**结果**不是属性):
- 修前:**铁剑 0.94 伤害 == 空手 0.94** —— 剑的加成**一点没吃到**(`ATTACK_DAMAGE=1.0`=裸手基础值);`ATTACK_SPEED=4.0`(应 1.6)。
- 穿满钻甲 `ARMOR=0`。**但** `FakePlayer.isInvulnerableTo()` 的字节码是**无条件 `return true`**
  → **服务端 avatar 根本挨不了打** ∴ **护甲陈旧无实义**,advisor 担心的"生存级 bug"那一支**被证伪**。
  ⇒ 真实爆炸半径 = **纯进攻端**:服务端近战 ~6× 弱 + `CombatProcess` 的 `scale>=1.0` 门在用**错误武器**的节奏计时。

**根因**:`LivingEntity.detectEquipmentUpdates()` 是 **private**、只由 `Player.tick()` 调;
avatar 刻意只跑 `baseTick()`(避免双重物理),**而且 `FakePlayer.tick()` 的字节码是空的** —— 
∴ **就算去调 `tick()` 也没用**,必须在 avatar 里显式镜像。

**修**:`ServerPlayerAvatar.step()` 里新增 `syncEquipmentAttributes()`——diff 每个 `EquipmentSlot` 的持有物,
变了就把旧物的 `ItemAttributeModifiers` 摘掉、新物的 `addTransientModifier` 上去(护甲槽一并同步:今天无实义,
但留一条规则好过留一个"等哪天 FakePlayer 能挨打就悄悄烂掉"的特例)。

⭐**全套件抓出我这个修法的一个真洞(单跑绿、全量红)**:`FakePlayerFactory` **跨 arena 复用同一个 FakePlayer**,
而我把 `lastEquipped` 记在 **avatar 实例**上 → 新 avatar 记录为空,**无法移除自己没加过的陈旧修正器**,
上一个 arena 的武器加成漏进来(空手挥砍测出 **2.94** 而非 0.94)。
修=**把记录挂到 FakePlayer 上**(`WeakHashMap<FakePlayer, EnumMap>`)——**状态属于携带它的那个实体,不属于观察它的那个对象**。

**验**:`serverAvatarGearScopeProbeArena` **真 RED→GREEN**(RED=摘掉 `syncEquipmentAttributes()` → 断言炸在
**结果**上:"an iron sword deals no more than a bare fist (0.94 vs 0.94)");GREEN=**剑 5.90 vs 拳 0.94**,
`ATTACK_DAMAGE=6.0`、`ATTACK_SPEED=1.6`、穿甲 `ARMOR=20`。
⭐**结果断言把我自己抓住了**:我最初把铁剑的 ATTACK_DAMAGE 期望写成 7.0(那是钻石剑;1.21 铁剑=1 基础+5 修正=**6**)——
挥砍结果早已证明修生效,而那条"回读常数"的断言还在红。**这正是"断言结果、不断言我以为的常数"的价值**。
非回归:全套件 required **91**,2 个失败(deepwaterclimbout / buoyantwall)**各自单跑即绿**=已知水域 flaky。

**⚠️无 live A/B,且这不是偷懒**:这条路径(`ServerPlayerAvatar` + FakePlayer)**在客户端 rig 上根本不存在**
(live 客户端是真 Player,`tick()` 正常跑,属性一直是对的)。GameTestServer 里跑的是**真 ServerLevel + 真 FakePlayer +
真 `Player.attack()`** —— 对这条路径而言 **arena 就是那条路径本身**,不是它的仿真。要真 live 验证需要一个 headless 专用服务器 rig(未搭)。

## ✅ 引擎 gap #47 GREEN:服务端 avatar 在零敲碎打地重实现 `Player.tick()`(2026-07-12)

**这不是第三个 bug,是 #45/#46 的同一个根**:`ServerPlayerAvatar.step()` 只跑 `baseTick()`(它手工积分位移,
不能让 `aiStep()` 再积一次),而 **NeoForge 的 `FakePlayer.tick()` 字节码是空的** ——
∴ vanilla 在 `Player.tick()`/`LivingEntity.tick()` 里做的**每一件 per-tick 账目,不手工镜像就全部不存在**。
过去是**被 arena 一次伏击一件地**发现的(#45 攻击条、#46 装备属性)。这轮把 1.21.1 的 `Player.tick()` 逐行拉出来对了一遍。

**审计结果(漏项族)**:
1. ⭐**`updatingUsingItem()`(private,只由 `tick()` 调)** —— `Avatar.commandUseItem(hold)` 调 `startUsingItem()`
   **只上膛不扣扳机**:`useItemRemaining` 没人递减 → **吃永远不下咽、弓永远 0 蓄力、盾永远差那 5 tick**。
   = **一整条 Avatar 能力在服务端静默失效**(不是保真度瑕疵)。
2. `cooldowns.tick()` —— ItemCooldowns 永不到期:**第一次用末影珍珠/盾被斧破防之后,那个物品就永久死了**。
3. `lastItemInMainHand` 变了要 `resetAttackStrengthTicker()` —— **换武器不清空冷却条**:
   agent 可以在 A 武器上攒满条、换 B 武器立刻满力挥,而 **#45 报出去的 `attack` 字段会把这个幽灵满条当事实广播**。

**修**:把散落的补丁收编成**单一 `mirrorPlayerTick()`**,并把**故意不做的写成契约**(这才是防下一次伏击的东西):
- 镜像:①持续使用倒计时 ②装备→属性 ③攻击条 ④换手清条 ⑤物品冷却(**vanilla 顺序**:先 ++ 再因换手清零)。
- **故意不做**:`aiStep()/travel()` 驱动(手工积分,跑了会双重积分);`foodData.tick()`(**半真相**:exhaustion 产生于
  从不运行的 `aiStep`,∴ 镜像了也永远不会饿,何况根本挨不了打);**伤害/血量/所有血量反射**
  ——`FakePlayer.isInvulnerableTo` **无条件 return true**,且 `ServerAgentDriver` **压根没接任何反射链**
  (Retreat/Panic/Bunker/Dodge/AutoHeal/AutoShield 一个都没有)。
  ⭐**服务端 agent 是任务自动机,不是生存者**;"19/19 进程已迁到 Avatar"这句话**读起来像它们在服务端都能用**,而血量那一支不能。

**验**:`serverAvatarTickFidelityArena` **真 RED→GREEN**,三条**全是结果断言**:
握"使用"40t 后牛排 **2→1 / 饱食 6→14**(RED:2 / 6,一口没咬);10t 冷却 15 tick 后**真到期**(RED:永不到期);
换武器后冷却条 **1.0 → 0.0**(RED:1.0 不动)。非回归:全量 required **93**,唯一失败 `buoyantwallarena` **单跑即绿**=已知水域 flaky。
⚠️连带:#45 的 arena 必须在装备武器后**先走一 tick 消化换手**再开始计时——那个前置条件一直是隐含的,新行为把它显式化了(不是给新行为让路)。

## 🔴 引擎 gap #48(未修,RED 留在树里):所有服务端 agent 共用**同一具身体**

**发现路径**:#47 全量跑出一个"新面孔"失败(`entityleashrepath`),我没有顺手判它 flaky,而是做了 A/B ——
结果**受害者对调**(带修 leash 红/不带修 gearscope 红)。**回归只会增加失败,不会换人** → 这是争用不是回归。
取证:`identityHashCode` 探针证明**全套件只有一个 FakePlayer**(`getMinecraft(level)` 是**每 level 单例**),
连跑三轮全量**失败集每次换人**(leash / deepwaterclimbout / buoyantwall / descentovershootresync / gearscope 轮流当受害者,**同一份代码**)。

⭐⭐**这多半就是"已知水域 flaky 家族"的真根**:那从来不是水域算法在抖,是 **arena 在抢同一具身体**
(GameTest 并发跑 arena,它们全在驱动同一个实体)。**"每跑受害者都不同"这个签名,我们当作水域的性质接受了很久,它其实是一份未被读懂的 bug 报告。**
生产侧同病:`/agentserver` 起两个 agent **不是两个 bot,是两个 driver 拽着一具身体**(所幸 `ServerAgentCommand` 自己的 scope 写的是 "single demo agent")。

**为什么没修**:显然的修法(每 driver 一个唯一 `FakePlayerFactory` profile)**是对的、arena 也绿了**
(A 走 3.83 格 / B 纹丝不动 0.0),但它让**其它每个 arena 第一次拿到真隔离** → 套件从 **~50s 炸到 >9min 且跑不完**
(每具新身体都是完整 ServerPlayer:stats+advancements)。而且这反过来说明:**共享身体很可能一直在掩盖真实失败**
(arena 过去是靠"身体被邻居传走→提前退出"结束的,其中一些是**假通过**)。
∴ 真修法必须**给隔离设界**(把驱动身体的 arena 串行化,或**单具身体进场即重置**),而不是每次 create 都造一具新的。
`serverAgentDistinctBodiesArena` 以 **`required = false`** 留在树里当**活的 RED 复现**(与 `vineOverWaterClimbArena` 同惯例)。

⚠️**基建**:`pkill -f GameTestServer` **抓不到 gametest JVM**(真实命令行是 `-Dneoforge.gameTestServer=true`,**大小写不同**)
→ 被 `timeout` 杀掉的那几轮留下**僵尸 JVM 攥着 `world/session.lock`**,后续每轮都在等锁,被我一度误读成"#48 把套件跑挂了"。
清理用 `pkill -9 -f "[n]eoforge.gameTestServer"`。(与 #45 的 knot 僵尸 JVM **同族**:**清理脚本抓不到目标进程**是这个仓库反复踩的坑。)

## 🔴 引擎 gap #49(未修):假绿 arena + 不可达目标 repath 空转

**起点**:为验 #48,把 5 个历史"水域 flaky 受害者"**当一组**跑(为此把 `AGENT_GT_ONLY`
升级成**逗号分隔列表**——单一真源 `AgentGameTestSupport.gtOnlySkips`,顺手收编 92 处 inline guard)。
4 个单跑干净通过,1 个把 600s timeout 烧穿。

### ① RIG:注释里的墙,代码从来没建
`descentOvershootResyncArena`(cx=300, cz=660, Y=200)注释:
"*A sheer 5-block face on the ridge's +Z side (dz=7) walls the forward walk*" —— **没有对应的 setBlock**。
山脊到 dz=6 止、深底板从 dz=8 起 → **dz=7 是直通虚空的空柱**。
遥测:bot 从 z=663.5 前走,z≈667.7 起 `fall3`,y 200 → **-60**(世界地板)。

### ② 它是**假绿**(多年什么都没断言)
- 单跑(当前 master,共享身体):**150s 超时 / 49 次 reject-loop**
- 单跑(武装隔离):**151s 超时 / 50 次** → **与并发无关,确定性挂死**
- 但它在全量套件里是 **required PASS** → 只可能是**邻居 arena 撞走了共享身体**才"结束"。
∴ **#48 的共享身体在掩盖真实失败——这是第一个实证**(因果方向与之前记录的相反:
共享身体不是让它 flaky,是让它**假绿**)。

### ③ 引擎真缺陷:目标不可达 → 无限空转(live 会中招)
bot 在 y=-60,目标 y=194(不可达)。循环:
脚下搜(满额 10 万节点)→ best-effort **escape-farthest** 段(终点 212 格外,`commitEnd=456`)
→ 从 commitEnd 搜续段 → bot 还在原地 → `reject mis-anchored`(守卫**守约**清 path/commitEnd)
→ 下一 tick 从脚下重搜 → **同一条 escape 段** → 循环。
**守卫没坏、清理守约**(`adoptPath` 的 javadoc 契约兑现了),
**坏在循环本身没有终止条件**:`escape-farthest` 只产出下一段,**从不结束这趟旅程**。
每轮 **2 次满额 A\***(各 10 万节点 / **~3s 墙钟**)。
**live 影响**:bot 掉进深沟 / 目标被填死 → 服务器每 tick 烧一个满 A*,**agent 侧零信号**。

### 下一步
1. 先修 rig(建 dz=7 那面墙)→ 让 arena 恢复成**真裁判**,看它测的 back-hop wedge 到底红不红;
2. 再给 ③ 单独立 RED。**别顺手加"全局不可达 abort"大改**,先读懂 escape/soft-commit 状态机。

### ⚠️连带更正 #48 的记录
">9min = 真隔离的诚实代价(每具新身体=完整 ServerPlayer)" —— **那个成本从未测量,是断言**。
真相:其中一大块是**隔离把本来就存在的确定性活锁掀了出来**。
∴ "真修法=给隔离设界(串行化/单体重置)"**可能在解错的问题**。
正确顺序:**先修被掩盖的挂死 → 再重测隔离的真实代价 → 然后才谈设界**。
(隔离修**是生效的**:唯一 profile 一 armed,`serverAgentDistinctBodiesArena` 立刻转绿。)

## ✅ #49-③/#50 GREEN + ✅ #48 分流落地 + 🔴 #51 立案(2026-07-12 下午)

### ✅ walker 不可达目标 churn(#50,live TDD)
- 修:`walkerFutileSearchCap=5` — 连续 K 次搜索完成而 bestDist 无改善且位移<2格 → `FAILED`,
  reason=`no route progress after N consecutive searches — goal unreachable`(与 tick 预算的
  "no progress for N ticks"=暂态卡顿**可区分**);每次 futile 后 4→8→16→32t 指数退避压住 kickoff。
  豁免:水域(归 anti-spin)/breakHeld/waterClimbDigging。
- live RED **62.1s/129 搜索/36s A\* CPU/通用 reason** → GREEN **20.0s/6 满额搜索/可区分 reason**;
  可达目标回归精确到达;全量 92 required 绿。探针可复跑:`scripts/probe_unreachable_churn.py`。

### ✅ #48 生产/测试分流(旧"设界"方案废弃,理由见 create() javadoc)
- `/agentserver` → `createIsolated` → `createUnique`(每 agent 唯一身体);
  `serverAgentDistinctBodiesArena` 切隔离入口后**转正 required**。
- 套件级隔离实测 3 轮(139/125/138s):失败集**仍漂移**且成员全 solo 绿
  → 非确定性 ≠ 身体单变量(还有共享世界 region + 服务器线程负载)→ 归自造测试框架(#52)。

### 🔴 #51 descentDriftArena = 假绿家族第二员(已降 required=false 活 RED)
- solo 必红:fix-ON leg 照样 LAUNCH 入坑且穿到 y=-60(pitFloorY=182)= 兜底没接住;
  cap=0 solo 烧穿 300s(守卫把它变 110s 有界失败;A/B 证 launch 与守卫无关)。
- descentOvershootResyncArena **已删除**(user:没用的删,不救)。

### ⛔ 新硬规则:禁止 pkill(user 原话)→ ps 列 PID 逐杀。

### 🚧 #53 stride floor-guard(致命横跨守卫)— 2026-07-13
- **结构**:`Walker.tick()` → `tickInner()` 单出口包裹;守卫在**每条**决策路径后、体动力学积分前跑
  (v1 挂在文件底部 walk-keys 区=对 pillar/stepUp 等 early-return 分支是死代码,GREEN 曾 byte-identical 假验证)。
- **机制**(v4):真实**速度向量**前瞻 ~4 tick 的步幅格;该格可通行且下方 `max(maxDryFall+1, ceil(HP)+3)`
  格内无地板/无水(=当前血量下**致死**的未规划落差)→ sneak-pin+取消跳+`place()` plug 洞口(消耗背包,生存合法)。
  豁免:水中/parkour 起跳(per-tick 字段)/前瞻 8 节点内精确列规划下降(列必须精确,Chebyshev-1 会重开 #51 坑口)。
  **sneak 闩自释放**:hazard 消失的下一 tick 收回(v2 只上不下的闩把 ridge 下坡 pin 成 maxNoProgress=205 死锁)。
- **证据链**(全部新世界;⭐世界持久化教训:ON 轮 plug 的圆石留在世界里让假 OFF 轮"绿"——每轮必 `rm -rf run-gametest/world`):
  - selfShaftDigUpArena:OFF=RED(worstBackslide **281.25**,坠 y=-60)/ ON=GREEN(**1.25**,守卫 5-6 次触发在
    slab 基座边缘+井口平台边缘,正是 RED 坠落点);
  - ridgeOvershootArena:v2 守卫致其死锁 → v3 闩自释放后 GREEN;
  - descentYawArena:v3/v4 trio 里 RED 但 **solo ON=GREEN**(worstBack=-0.25 基线)=跨 arena 干扰签名(#52 病),非守卫回归;
    v4 致死阈值把 5-6 格弦切落差从误 pin 中放行(descentYaw 触发 26→5)。
- **#51 判明**:守卫把 descentDrift 的"发射入坑坠亡"压成"坑口 sneak-pin 活锁"(567 触发全在 pit mouth,
  fellInPit=false)——死亡模式消除,但 drive 朝下方节点螺旋的真根仍开放,归 #51/#54 专修。
- 待:全量 v4 与 OFF 基线彩票集对齐 → live 生存竖挖场景自然覆盖验证。

### 🔴 #53 live 首战:守卫救援被周边系统拆掉(2026-07-13 17:04 死亡)
- 死亡链(fabric latest.log 钉死):守卫在崖唇 (6,102,35) **正确 pin 8 次**(速度压到 0.04)
  → **plug 静默失败 8 次**(`Avatar.place` 返回 void,日志无条件宣称 plug;同格重复触发=放置从未落地的铁证)
  → walk-keys **stuckT 2→4 在涨**(pin 被 anti-stuck 当卡死)→ 恢复脉冲把 2.47HP 身体推下 6 格坠亡。
- v5 修:①plug 改世界真值判定+三态日志(plug/no placeable/plug FAILED);②守卫触发 tick 回退 stuck 计数(pin≠stall);
  ③连续 pin≥30 tick → `path=null` 强制重路由(把守卫从"跟 anti-stuck 打架"改成"向规划反馈致命路径")。
- ⭐元教训:**void 返回值的 actuator=天生的静默失败源**;安全机制必须与 anti-stuck 明确分层,否则互相拆台。

### 🚧 #51 root-cause 完成,修归 #54(2026-07-13)
- 闩修(✅保留,solo descentYaw/selfShaft/ridge 全绿):steepDescentLatch 的释放条件用瞬时 wp-vs-foot,
  下落中 foot 跌破 wp 一个 tick 就把闩清零 → airborne clamp 失效 → 满推力横踢。改成**落地才释放**。
- 但 descentDrift 仍 RED:东向冲量**不来自通用 drive 通道**(driveYaw 指南时仍东漂)=某 early-return 分支自带冲量;
  sneak 在凸角上被 vanilla 边缘扣的"残留 sliver 支撑"语义穿透;stepUp jump 在守卫非触发 tick 逃逸。
- 按 3-strikes:停止在"通用 drive+补丁"架构上打第 3 个补丁;**1 宽梯下降=锁柱 DESCEND per-move 状态机(#54 首个迁移对象)**。
- ⚠️descentYawArena 的 trio/full 组合红、solo 绿(两次复核)=共享身体跨 arena 干扰,非本次改动;归 #52。

### ✅ #55 收案 + #56/#57/#58 三连锁(2026-07-13 晚)
- **#55 伤害源不可见**(重定性,原"扫描器隧道失明"被 RED 僵尸对照当场证伪——compute 从不按光照/LOS 丢实体):
  修=`getLastDamageSource()` 两端单源:①player.hurt 事件带 `source/attackerId/attackerType/attackerDistance`
  (客户端 handleDamageEvent 镜像,免 mixin);②攻击者注入 threat 扫描(过滤改 `Enemy || attackedMe`,
  分数 floor 0.5+0.25 保 top)——激怒中立生物(狼/蜂)从此可见。AgentGameTestCombatSense RED→GREEN;
  live GREEN=死亡#3 事件 `source=mob attackerId=1106 dist=2.08` + 途中 `lost=7 source=fall`。
  ⚠️FakePlayer 无条件免伤→lastDamageSource 永 null:测试必须 makeMockPlayer。
- **#56 holdItem 只搜快捷栏**:放置类 verb 在物品漂出槽 8 后全体静默 no-op="熔炉静默失败"悬案真根(#27 同族——
  修 selectTool 时没 grep 兄弟路径)。修=swapFromMainInv 共享 helper(menu 槽 9-35 SWAP,优先空槽),
  pillar 版同源化;craft 错误拆"无物品/无空位"。live RED→GREEN(台在槽 9:镐+熔炉+#40 回收全链)。
- **#57 垫块吃功能方块**:walker 把刚合成的熔炉当桥料放世界。修=BotConfig.isInteractiveBlock
  (EntityBlock+工作台族)安全类拒绝(whitelist 不豁免)。gametest RED→GREEN(96 required)。
- **#58 意外 GUI=全引擎瘫痪(死亡#3 直接死因)**:对交互方块放置点击=开 GUI;屏幕吞输入→walker 悬空 churn→
  futile-search 误诊"goal unreachable"→僵尸磨死。修两层:①BotApiImpl 屏幕看门狗(movement 活跃+容器屏
  +非 craft/smelt→20 tick 关+`screen.autoClosed` 事件);②walkerPlace 支撑循环跳过交互方块面。
  live GREEN=行进中开背包 20 tick 自动关、行进不断。⭐futile-search 的"不可达"要先排除"根本动不了"。
- 回归口径:失败集漂移 2→4 但全是挂名彩票(leash/deepwaterclimbout/gearscope/descentYaw)零新名=无回归;
  ⚠️AGENT_GT_ONLY 多名单≠solo:gearscope 先跑就把 descentYaw 掀红(#48 又一证)。

## gap#59 (P0, 2026-07-13 18:49 live): 向上 goal 被执行成 downBreak 直下 69 格
- 现场: 密闭土腔内 goto pos(51,86,12)(上4格) 与 goto YLevel84 均持续直下挖 (50,z10) 柱, y82→y13, cancel 才停。
- log 实锤: `[walker] t=1 step=25/36 move=downBreak node=50,56,10` + 同 tick `search slice ... (still running)` = 全量 A* 未完时已按 36 步全 downBreak 临时路执行, horizon soft-commit 向下续接。
- 前奏: (51,80,12) 同格 dirt 放/挖振荡 ~40 次 (~60s, dirt 13→2)。
- 疑点: cancel ok + active=[] 后事件仍显示数格下挖 (y30→13) — 待 log 时间戳判 overrun vs 通道延迟。
- 正面旁证: 69 格自挖竖井全程 HP3.7 零坠伤 = #53 floor-guard live 大样本。
- 诊断: pathArchive 回放 + quick-start/provisional 对"goal在上方+局部密闭"的处理; 修法候选 = 临时路 goal 方向单调性门 + cancel 即时清 dig-aim/releaseKeys。
- ✅收案 (2026-07-13 19:40): 真根≠quick-start, 是 chooseSegment 兜底 escape-farthest 取 distSqr 最远节点不排 break 边(均匀岩层最远恒=正下方) + 每次 adoption penalizeStuckNode 毒化上行路的自增强棘轮; Block/YLevel goal 从不 track bestClimb 必然落到该兜底。
  修 = PathFinder.Node 增 `dug` 标记, bestEscape 只认 `!dug`(walkable-only)节点 → 密封岩 bestEscape=null → no-path → #50 futile 退避接管。
  验证 = gametest escapeFarthestNoRockDrillArena RED(下钻 pathLen=8)→GREEN(hasPath=false) + 96 required 无回归(4失败全已知彩票, leash 经 stash A/B 证前置) + live A/B 同点 goto y84 零挖掘 segment=none 干净失败。
  遗留 watch(归#24): goto YLevel 从 y73 横向 2 高破块隧道 ~10 格零 y 增益(疑 travel≥5 白拿 goalward segment); escape verb canWalkOut=true 即 DONE(skyOpen=false 也停); cancel-overrun 时序; dirt 振荡; 1×1 竖井 PillarUp 成本爆炸(A2b 后继)。

## ✅ gap#61+#62: 放置扫描族缺陷 (2026-07-13, task#61)
- **#61 placeTable 坑沿盲区**: dy 只试 {0,-1} → bot 站 1 格深坑(duskSecure 每黄昏挖的那种)时全脚层邻居=实心墙, 唯一自然位=坑沿顶(dy=+1) → 开阔地报"没有可放置的空位"(live 原句)。修=dyOrder {0,-1,+1}(+1 最后, 平地仍偏好同层)。serverCraftTableHoleRimArena RED(tables=0 同句错误)→GREEN(tables=1)。
- **#62 placeFurnace=placeTable 进化前拷贝**: 只 4 正邻、无 dy 层、还在用 placeTable 注释里点名错误的 isFaceSturdy 门(拒树叶/土径)。#42 同款 copy-paste 分叉家族。serverSmeltFurnaceHoleRimArena RED(furnaces=0 "需要熔炉（背包里没有可放置的熔炉）")→GREEN(furnaces=1)。
- **修(防腐>点修)**: 抽单一共享扫描 `PlaceNearby.place(a,p,lvl,item,expectedBlock,logTag)`(8邻×dy{0,-1,+1}+canBeReplaced/非空支撑门+click失败逐条LOG), placeTable/placeFurnace 两端 delegate; placedTable 赋值语义(gap#276 只破自己的台)保留在 wrapper。
- 服务端悬崖照旧: FakePlayer 开不了菜单, 两 arena 都断言世界侧放置, err=打开工作台/熔炉超时为期望值。
- **live GREEN** (21:17): bot 地下矿室 smelt 3 raw_iron → 熔炉放置成功 + 3 iron_ingot + Acquire Hardware 成就, smelt slot 干净收尾。全量套件 100 required 仅 descentYaw 彩票(solo 绿)零新名。注: smelt 无熔炉回收(placedTable 回收无 furnace 版, 低优先)。
- 残余 watch(归#24): 19:50 那次 live ground-truth 里有一个 (0,1) cell=air/below=grass 的 dy=0 候选"本该成功"却失败, 与坑沿几何不完全吻合——PlaceNearby 已带每候选 click-fail LOG, 下次复现看 log 定位(备选: holdItem 同 tick 验证/useItemOn 服务端拒/同 tick 预测未更新)。

## 🔴 gap#63(P1, task#62): 深部→地表 goto 水平隧道 churn (2026-07-13 21:18-21:24)
- live: goto (-4,37,-6)→(-5,67,0), 6分钟挖~120块石头, 终点 (17,35,2)=距目标31→40格, y反降2。cancel止损。
- 证据: pathchart-0001-1783992344943.png(goal红X在西,轨迹东北钩南,elevation图**plan本身全平**); plans=2 reached=false expanded=15948 **ms=2001顶时间帽** cost=1218 maxYawErr=175°。
- 定性: goalReached=false 的 partial-best 选段采纳"平层横钻背向goal"段+每段repath/penalize继续漂移=churn家族(#35/#50/#59同族); 30格实心岩上升需~60-90 break边,2s预算到不了→best-node选择器可疑。修法与渐进式寻路(HorizonBlocks/bestClimb)和#54结构解重叠。
- 新watch数据点: cancel后事件通道仍流30-60s才静止(主体=事件投递延迟,但事件无时间戳无法精判=可观测性gap)。
- ~~observe items间歇空~~已证伪: rpc_call.py返回裸result,我一半解析用`.get('result',{})`拿到空dict——观测管道自身的bug,不是引擎flake。⭐先验证读数管道再定性引擎。
- ~~autoRetreat未触发watch~~→升级为gap#65并✅GREEN收案(见下)。
- 🟡新watch(2026-07-13 22:31): retreat逃跑路径吃了5HP fall(x115,追击中连续flee~100格)——#26 lethalEdgeBrake只挡致命沿,非致命坠伤仍会吃;若live再现升级为gap。

## 🟢 gap#64(P1, task#63): SmeltProcess 燃料三缺陷 — gametest GREEN, live 待 Stage4 自然 smelt 收官 (2026-07-13 23:29)
- live×2 证据: 炉A 8 coal整组消失; 炉B烧掉crafting_table(煤在包里不用)+只出1/5即超时+input残留。
- 修(SmeltProcess): ①pickFuelMenuSlot=值序选燃料(vanilla getFuel烧值最高优先)+isInteractiveBlock工作方块拉黑(#57安全类复用,显式fuelId豁免) ②SMELT_WAIT火灭(fuel空+!isLit+input在)→自动续装并重置超时预算,无可续装→诚实partial/fail ③COLLECT三槽全取回(result+ingredient+fuel残留;背包满时QUICK_MOVE no-op不丢东西) ④count盈余经③回背包。
- TDD: smeltFuelPolicyArena(cx2400)=真SmeltProcess全状态机驱动(FakePlayer开不了GUI→手动塞containerMenu,menu.clicked同一接缝);RED=烧工作台原样复刻(fuel=crafting_table tableInBag=0)→GREEN三相(coal入炉台留包/火灭续planks/终态三槽空+2锭3生铁8板全回);全量2失败=selfshaft(solo绿彩票)+riverSheerBank(stash基线前置)零新名。
- ⏳live收官: 生存线 Stage 4 自然 smelt(no-cheat不给物品)——观察点=煤优先于木、不烧台、终态背包无泄漏。

## ✅ gap#63 GREEN收案 (2026-07-13 23:20, task#62): A*预算停 partial path 背向 goal 乱钻
- 真根(遥测实锤): `escape-farthest` 方向无关按d²取最远walkable节点,在**预算停**(maxNodes/maxMs, openLeft>0=图未耗尽)时提交背向长走——"所有可达格都背向goal"只是预算帽的伪象,真答案(向上挖)的dig节点f太高在预算内永不pop。live A侧复测: d 22.2→34.4单调跑飞棘轮, STOP行=`maxNodes(600) segment=escape-farthest openLeft=973`+`maxMs(2000) expanded=17060`。
- 修(PathFinder三件): ①escape-farthest按停因分流——图耗尽(open空,"背向是唯一路"可证)保留原样(escape-farthest-exhausted);预算停只许**ground-holding escape**(距goal真3D直线距离不比起点差2格; h是错尺:Goal.Block的Chebyshev让dy跨度内横漂全免费,数字实锤 dx=8/dy=6 h差正好=slack) ②无safe escape→null→#50退避owns fail-stop ③bestClimb扩到"targetPos在起点上方"的3D goal(原只ignoresY)——旱地限定(水启动喂climb让2格冒头节点抢bestAshore→riverSheerBank红,已收口`!startInWater`),高度单调=收敛escape。
- TDD: budgetAwayTunnelChurnArena(cx2300, 密封室+16格背向隧道+正上goal+200节点) RED(endDist 6→17.1)→GREEN(no-path干净fail-stop);gap#59场顺带升级(现在提交4步不降段=climb生效);全量失败集全分类(deepwater/selfshaft=solo绿彩票, leash/riverSheerBank=stash基线红前置, descentYaw=已知彩票)零可归因回归。
- live A/B(mc.debug.replay churn档 replay-0010-1783992278682, 同地形replan): A(旧build)=跑飞棘轮; B2(新build)=y32→43收敛爬升+d 33.7→17.1+一次有界徘徊+终态`no route progress after 5 consecutive searches`干净fail-stop=契约达成(进展 or 可区分失败交策略层staged-hop)。
- 残余(watch, 归#54/progressive): ①endpoint-only门放过"途中乱绕"路径(48步段中段绕到d29.6,walker卡中段→从更远处repath) ②600节点quick-start在挖掘柱位提交横向escape把bot从上挖点拽走(dig节点600内不pop) ③固岩长升仍需staged-hop(+8y)策略层分段。

## ✅ gap#65 GREEN收案 (2026-07-13 22:31, task#64): autoRetreat对远程攻击失灵
- 三腿根因: ①CLEAR_RADIUS=12 < 骷髅交战距15-16(死亡#6全程"不在危险中") ②proactive charging要眼对眼LoS而箭走抛物线拐角照中 ③#55的attackedMe归因字段无消费者。
- 修(RetreatChain): shouldEnter/shouldRelease抽成public static纯门(scan喂入,无client可测); RANGED_RADIUS=18仅对**已交战**(charging||attackedMe)的RangedAttackMob放宽(空闲骷髅仍12,不为路过怪弃任务); underRangedFire=被远程命中任意HP入闩且封释放(attackedMe~2s窗自衰减)。
- TDD: retreatGateMatrixArena(cx1240) 9-case矩阵, RED=case(a)死亡#6几何(hp8+穿墙中箭@14必入)→GREEN 9/9; 全量101required零新名(deepwater=solo绿彩票, leash=stash A/B证前置, vine=已知optional)。
- live GREEN(受控A/B, SurvivalTest): 阴性×2=骷髅@14-16.5空闲/无LoS→retreat正确不出价; 阳性=骷髅@**15.6瞄准**(正是旧盲区12-18)→retreat即刻active,bot从x20拉到x77+,**全程HP20零中箭**(proactive在第一箭落地前跑路),脱离接触后正确释放。
- 契约: 反射层管"正被打/正被瞄"的逃命; 空闲怪路过不触发(hp门+12格)——避免夜间寸步难行。

## 🔴🔴 死亡#6/#7 (2026-07-13 21:55, 引擎层, gap#65/task#64) 
- #6: 骷髅7702穿隧道追击, HP20→8→5→2→0, **autoRetreat全程未接管**(设置全开阈值10, chainPriorities.retreat恒0)=P0反射失灵。裸bot重生。
- #7: 赶尸goto(5,43,21)又犯#63(120步path朝反方向,x-69 vs 目标x5), cancel后余势漂移进洞穴水域, 被creeper炸死@(-73,32,40)。3分钟连折两命。
- 物资: 8铁锭+双石镐+熔炉+全部, 尸点(8,37,21)+(−73,32,40)超窗despawn=铁线第三次清零。
- 决策: ~~#65修好前不推生存线~~ #65已✅GREEN(2026-07-13 22:31); 剩余顺序 #63(P1)→#64(P1), 生存线可恢复。
- 引擎侧新证: ①#63二次复现(地表→浅地下目标也churn) ②cancel余势/事件延迟再证(死亡#7间接因素) ③staged-hop(+8y)workaround在无干扰时有效(y33→42楼梯干净)但骷髅一搅就乱。

## 生存线事故记录 (2026-07-13 19:31/19:36, 死亡#4/#5, 策略层)
- #4: 救援尾段 HP3.7 被苦力怕炸死 @(59,72,-47); #5: 裸装 respawn bot 夜间赶尸 70 格被骷髅+僵尸杀死 @(39,77,-85), 且当时关了 autoSecureAtDusk。物资(277圆石/石镐剑/熔炉/工作台/raw_copper×3)超 5min 全 despawn, 生存线清零重启。
- 教训: 赶尸先算 despawn 窗口 vs 路程+夜险; 裸装夜间不长途; 不为赶路关保护反射。已恢复 autoSecureAtDusk=true, bot 蹲坑熬夜。
